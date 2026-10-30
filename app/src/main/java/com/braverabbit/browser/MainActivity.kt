package com.braverabbit.browser

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import java.net.URLEncoder

class Tab(val webView: WebView) {
    var url: String = ""
    var title: String = "New tab"
    var home: Boolean = true
    var pending: String? = null
    var desktop: Boolean = false
}

/** Only lives in memory: lets tabs survive a theme-change recreate, never a real app close. */
object SessionHolder {
    var urls: List<String>? = null
}

private class Site(val name: String, val url: String, val letter: String, val color: Int)

class MainActivity : AppCompatActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val tabs = ArrayList<Tab>()
    private var active: Tab? = null

    private lateinit var root: LinearLayout
    private lateinit var shieldIcon: ImageView
    private lateinit var addressBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var tabCountBtn: TextView
    private lateinit var menuBtn: ImageButton
    private lateinit var homeView: View
    private lateinit var lockView: View
    private lateinit var pinInput: EditText
    private lateinit var lockError: TextView
    private lateinit var bioBtn: MaterialButton

    private var unlocked = false
    private var lockShown = false
    private var failedPins = 0
    private var lockedUntil = 0L
    private var leavingForPicker = false
    private var backPressedAt = 0L

    private var mobileUa = ""
    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permCallback: ((Map<String, Boolean>) -> Unit)? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            val cb = permCallback
            permCallback = null
            cb?.invoke(r)
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (customView != null) {
                hideCustomView()
                return
            }
            val t = active
            if (t == null) {
                finish()
                return
            }
            if (addressBar.hasFocus()) {
                addressBar.clearFocus()
                return
            }
            if (needsLock()) {
                cancelLock()
                return
            }
            if (!t.home && t.webView.canGoBack()) {
                t.webView.goBack()
                return
            }
            if (!t.home) {
                t.home = true
                refreshContent()
                updateAddress()
                return
            }
            if (tabs.size > 1) {
                closeTab(t)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - backPressedAt < 2000) {
                exitApp()
            } else {
                backPressedAt = now
                toast("Press back again to exit")
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        try {
            startService(Intent(this, TaskWatcherService::class.java))
        } catch (e: Exception) {
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        App.whenReady { startBrowsing() }
    }

    private fun startBrowsing() {
        val saved = SessionHolder.urls
        SessionHolder.urls = null
        val viewUrl = viewUrlFrom(intent)
        if (saved != null && saved.isNotEmpty()) {
            for (u in saved) newTab(if (u.isEmpty()) null else u, false)
            selectTab(tabs[tabs.size - 1])
            if (viewUrl != null) newTab(viewUrl)
        } else {
            newTab(viewUrl)
        }
    }

    private fun viewUrlFrom(i: Intent?): String? {
        val d = i?.data ?: return null
        if (i.action != Intent.ACTION_VIEW) return null
        val s = d.scheme
        return if (s == "http" || s == "https") d.toString() else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = viewUrlFrom(intent)
        if (u != null) App.whenReady { newTab(u) }
    }

    override fun onResume() {
        super.onResume()
        leavingForPicker = false
        updateShield()
        if (active != null) refreshContent()
    }

    override fun onStop() {
        super.onStop()
        if (IgVault.visited) IgVault.snapshotLive(true)
        CookieManager.getInstance().flush()
        hideCustomView()
        if (unlocked && !leavingForPicker) {
            unlocked = false
            if (active != null) refreshContent()
        }
    }

    override fun onDestroy() {
        if (!isFinishing) {
            SessionHolder.urls = tabs.map { if (it.home) "" else (it.pending ?: it.url) }
        }
        for (t in tabs) {
            try {
                (t.webView.parent as? ViewGroup)?.removeView(t.webView)
                t.webView.destroy()
            } catch (e: Exception) {
            }
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun exitApp() {
        IgVault.snapshotLive(IgVault.visited)
        val finishNow = {
            SessionHolder.urls = null
            finishAndRemoveTask()
        }
        if (Prefs.autoClean) PrivacyCleaner.clean(this) { finishNow() } else finishNow()
    }

    // ---------------------------------------------------------------- helpers

    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun showKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(res)
        b.contentDescription = desc
        b.setColorFilter(col(R.color.br_text))
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        b.setBackgroundResource(tv.resourceId)
        b.setOnClickListener { onClick() }
        return b
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(col(R.color.br_bg))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(10), dp(8), dp(10), dp(6))

        shieldIcon = ImageView(this)
        shieldIcon.setImageResource(R.drawable.ic_shield)
        val slp = LinearLayout.LayoutParams(dp(24), dp(24))
        slp.rightMargin = dp(8)
        top.addView(shieldIcon, slp)

        addressBar = EditText(this)
        addressBar.setSingleLine()
        addressBar.hint = "Search or type URL"
        addressBar.textSize = 15f
        addressBar.setTextColor(col(R.color.br_text))
        addressBar.setHintTextColor(col(R.color.br_text2))
        addressBar.background = Ui.rounded(col(R.color.br_surface), dp(22))
        addressBar.setPadding(dp(16), 0, dp(16), 0)
        addressBar.imeOptions = EditorInfo.IME_ACTION_GO
        addressBar.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitAddress()
                true
            } else {
                false
            }
        }
        addressBar.setOnFocusChangeListener { _, has ->
            if (has) {
                val t = active
                val u = if (t == null || t.home) "" else (t.pending ?: t.url)
                addressBar.setText(u)
                addressBar.selectAll()
            } else {
                updateAddress()
            }
        }
        top.addView(addressBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(col(R.color.br_accent))
        progress.visibility = View.GONE
        root.addView(progress, LinearLayout.LayoutParams(MATCH, dp(3)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(col(R.color.br_surface))
        val btnLp = { LinearLayout.LayoutParams(0, dp(48), 1f) }
        bar.addView(iconBtn(R.drawable.ic_back, "Back") { goBack() }, btnLp())
        bar.addView(iconBtn(R.drawable.ic_forward, "Forward") { goForward() }, btnLp())
        bar.addView(iconBtn(R.drawable.ic_refresh, "Refresh") { reload() }, btnLp())
        bar.addView(iconBtn(R.drawable.ic_home, "Home") { goHome() }, btnLp())

        tabCountBtn = TextView(this)
        tabCountBtn.gravity = Gravity.CENTER
        tabCountBtn.textSize = 12f
        tabCountBtn.typeface = Typeface.DEFAULT_BOLD
        tabCountBtn.setTextColor(col(R.color.br_text))
        val stroke = GradientDrawable()
        stroke.setStroke(dp(2), col(R.color.br_text))
        stroke.cornerRadius = dp(6).toFloat()
        tabCountBtn.background = stroke
        val tabWrap = FrameLayout(this)
        tabWrap.setOnClickListener { showTabs() }
        tabWrap.addView(tabCountBtn, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        bar.addView(tabWrap, btnLp())

        menuBtn = iconBtn(R.drawable.ic_menu, "Menu") { showMenu() }
        bar.addView(menuBtn, btnLp())
        root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(48)))

        setContentView(root)
        homeView = buildHome()
        lockView = buildLock()
    }

    private fun buildHome(): View {
        val cText = col(R.color.br_text)
        val cSub = col(R.color.br_text2)
        val cAcc = col(R.color.br_accent)
        val cSurf = col(R.color.br_surface)

        val sv = ScrollView(this)
        sv.setBackgroundColor(col(R.color.br_bg))
        sv.isFillViewport = true
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(24), dp(32), dp(24), dp(24))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher_foreground)
        val logoBg = GradientDrawable()
        logoBg.shape = GradientDrawable.OVAL
        logoBg.setColor(Color.parseColor("#0E1116"))
        logo.background = logoBg
        c.addView(logo, LinearLayout.LayoutParams(dp(120), dp(120)))

        val name = TextView(this)
        name.text = "BRAVE RABBIT"
        name.textSize = 26f
        name.typeface = Typeface.DEFAULT_BOLD
        name.letterSpacing = 0.1f
        name.setTextColor(cText)
        name.setPadding(0, dp(16), 0, 0)
        c.addView(name)

        val tag = TextView(this)
        tag.text = "Browse Freely. Stay Private."
        tag.textSize = 14f
        tag.setTextColor(cAcc)
        tag.setPadding(0, dp(4), 0, dp(24))
        c.addView(tag)

        val search = EditText(this)
        search.setSingleLine()
        search.hint = "Search or type URL"
        search.textSize = 16f
        search.setTextColor(cText)
        search.setHintTextColor(cSub)
        search.background = Ui.rounded(cSurf, dp(28))
        search.setPadding(dp(20), 0, dp(20), 0)
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val s = (v as EditText).text.toString()
                v.setText("")
                hideKeyboard()
                val t = active
                if (t != null) navigate(t, s)
                true
            } else {
                false
            }
        }
        c.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)))

        val sites = listOf(
            Site("Google", "https://www.google.com", "G", Color.parseColor("#4285F4")),
            Site("YouTube", "https://m.youtube.com", "Y", Color.parseColor("#E53935")),
            Site("Facebook", "https://m.facebook.com", "f", Color.parseColor("#1877F2")),
            Site("TikTok", "https://www.tiktok.com", "T", Color.parseColor("#1B1B1B")),
            Site("Gmail", "https://mail.google.com", "M", Color.parseColor("#D93025"))
        )
        val gridTop = LinearLayout(this)
        gridTop.orientation = LinearLayout.VERTICAL
        gridTop.setPadding(0, dp(24), 0, 0)
        for (rowSites in sites.chunked(3)) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (s in rowSites) r.addView(tile(s), LinearLayout.LayoutParams(0, WRAP, 1f))
            gridTop.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        c.addView(gridTop, LinearLayout.LayoutParams(MATCH, WRAP))

        val note = TextView(this)
        note.text = "History, cookies and cache are cleared when you close Brave Rabbit."
        note.textSize = 12f
        note.gravity = Gravity.CENTER
        note.setTextColor(cSub)
        note.setPadding(dp(8), dp(28), dp(8), 0)
        c.addView(note)

        sv.addView(c)
        return sv
    }

    private fun tile(s: Site): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(0, dp(10), 0, dp(10))
        val badge = TextView(this)
        badge.text = s.letter
        badge.gravity = Gravity.CENTER
        badge.textSize = 20f
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(s.color)
        badge.background = bg
        box.addView(badge, LinearLayout.LayoutParams(dp(54), dp(54)))
        val label = TextView(this)
        label.text = s.name
        label.textSize = 12f
        label.gravity = Gravity.CENTER
        label.setTextColor(col(R.color.br_text2))
        label.setPadding(0, dp(6), 0, 0)
        box.addView(label)
        box.setOnClickListener {
            val t = active
            if (t != null) navigate(t, s.url)
        }
        return box
    }

    private fun buildLock(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(col(R.color.br_bg))
        box.setPadding(dp(32), dp(32), dp(32), dp(32))
        box.isClickable = true

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_lock)
        icon.setColorFilter(col(R.color.br_accent))
        box.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))

        val title = TextView(this)
        title.text = "Instagram is locked"
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(col(R.color.br_text))
        title.setPadding(0, dp(16), 0, dp(4))
        box.addView(title)

        val sub = TextView(this)
        sub.text = "Enter your PIN to continue"
        sub.textSize = 14f
        sub.setTextColor(col(R.color.br_text2))
        box.addView(sub)

        pinInput = EditText(this)
        pinInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        pinInput.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        pinInput.gravity = Gravity.CENTER
        pinInput.textSize = 24f
        pinInput.hint = "PIN"
        pinInput.setTextColor(col(R.color.br_text))
        pinInput.setHintTextColor(col(R.color.br_text2))
        pinInput.background = Ui.rounded(col(R.color.br_surface), dp(14))
        pinInput.imeOptions = EditorInfo.IME_ACTION_DONE
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock()
                true
            } else {
                false
            }
        }
        val plp = LinearLayout.LayoutParams(dp(220), dp(56))
        plp.topMargin = dp(20)
        box.addView(pinInput, plp)

        lockError = TextView(this)
        lockError.textSize = 13f
        lockError.setTextColor(col(R.color.br_danger))
        lockError.setPadding(0, dp(8), 0, dp(8))
        box.addView(lockError)

        val unlock = MaterialButton(this)
        unlock.text = "Unlock"
        unlock.setOnClickListener { tryUnlock() }
        box.addView(unlock, LinearLayout.LayoutParams(dp(220), WRAP))

        bioBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        bioBtn.text = "Use fingerprint / face"
        bioBtn.setOnClickListener { promptBiometric() }
        val blp = LinearLayout.LayoutParams(dp(220), WRAP)
        blp.topMargin = dp(8)
        box.addView(bioBtn, blp)

        val cancel = TextView(this)
        cancel.text = "Go back"
        cancel.textSize = 14f
        cancel.setTextColor(col(R.color.br_text2))
        cancel.setPadding(dp(16), dp(16), dp(16), dp(16))
        cancel.setOnClickListener { cancelLock() }
        box.addView(cancel)
        return box
    }

    // ---------------------------------------------------------------- lock logic

    private fun needsLock(): Boolean {
        val t = active ?: return false
        if (!Prefs.lockEnabled || unlocked) return false
        val u: String? = t.pending ?: (if (t.home) null else t.url)
        return Ui.isInstagram(u)
    }

    private fun tryUnlock() {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) {
            lockError.text = "Too many attempts. Wait " + ((lockedUntil - now) / 1000 + 1) + "s"
            return
        }
        val pin = pinInput.text.toString()
        if (PinManager.verify(pin)) {
            failedPins = 0
            pinInput.setText("")
            lockError.text = ""
            onUnlocked()
        } else {
            failedPins++
            pinInput.setText("")
            if (failedPins >= 5) {
                failedPins = 0
                lockedUntil = now + 30000
                lockError.text = "Too many attempts. Wait 30s"
            } else {
                lockError.text = "Wrong PIN"
            }
        }
    }

    private fun onUnlocked() {
        unlocked = true
        hideKeyboard()
        val t = active
        if (t != null) {
            val p = t.pending
            t.pending = null
            if (p != null) {
                t.home = false
                t.url = p
                t.webView.loadUrl(p)
            }
        }
        refreshContent()
        updateAddress()
    }

    private fun cancelLock() {
        val t = active ?: return
        hideKeyboard()
        t.pending = null
        if (Ui.isInstagram(t.url)) {
            t.webView.loadUrl("about:blank")
            t.url = ""
        }
        t.home = true
        unlocked = false
        refreshContent()
        updateAddress()
    }

    private fun relock() {
        if (unlocked) {
            IgVault.snapshotLive(true)
            CookieManager.getInstance().flush()
        }
        unlocked = false
        refreshContent()
    }

    private fun onLockShown() {
        lockError.text = ""
        pinInput.setText("")
        bioBtn.visibility = if (Prefs.biometric) View.VISIBLE else View.GONE
        content.postDelayed({
            if (needsLock()) {
                pinInput.requestFocus()
                showKeyboard(pinInput)
                if (Prefs.biometric) promptBiometric()
            }
        }, 300)
    }

    private fun promptBiometric() {
        try {
            if (!Prefs.biometric) return
            val bm = BiometricManager.from(this)
            if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) !=
                BiometricManager.BIOMETRIC_SUCCESS
            ) return
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Instagram")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (needsLock()) onUnlocked()
                    }
                })
            prompt.authenticate(info)
        } catch (e: Exception) {
        }
    }

    private fun updateSecureFlag() {
        val t = active
        val protectedNow = Prefs.lockEnabled && t != null &&
            Ui.isInstagram(t.pending ?: (if (t.home) null else t.url))
        if (protectedNow) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun updateShield() {
        val c = if (Prefs.autoClean) col(R.color.br_accent) else col(R.color.br_text2)
        shieldIcon.setColorFilter(c)
    }

    private fun pauseMedia(t: Tab) {
        try {
            t.webView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(m){m.pause();})", null
            )
            t.webView.onPause()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- tabs & content

    private fun refreshContent() {
        val t = active ?: return
        content.removeAllViews()
        if (t.home) {
            content.addView(homeView, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            (t.webView.parent as? ViewGroup)?.removeView(t.webView)
            content.addView(t.webView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (needsLock()) {
            content.addView(lockView, FrameLayout.LayoutParams(MATCH, MATCH))
            pauseMedia(t)
            if (!lockShown) {
                lockShown = true
                onLockShown()
            }
        } else {
            lockShown = false
            t.webView.onResume()
        }
        updateSecureFlag()
        updateShield()
    }

    private fun newTab(url: String?, select: Boolean = true): Tab {
        val t = Tab(WebView(this))
        setupWebView(t)
        tabs.add(t)
        if (select) selectTab(t)
        if (url != null) navigate(t, url)
        updateTabCount()
        return t
    }

    private fun selectTab(t: Tab) {
        val prev = active
        if (prev != null && prev !== t) prev.webView.onPause()
        active = t
        val ig = Ui.isInstagram(t.pending ?: (if (t.home) null else t.url))
        if (!ig && unlocked) {
            IgVault.snapshotLive(true)
            unlocked = false
        }
        refreshContent()
        updateAddress()
        updateTabCount()
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        try {
            (t.webView.parent as? ViewGroup)?.removeView(t.webView)
            t.webView.destroy()
        } catch (e: Exception) {
        }
        if (tabs.isEmpty()) {
            active = null
            newTab(null)
        } else if (t === active) {
            selectTab(tabs[minOf(i, tabs.size - 1)])
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        tabCountBtn.text = tabs.size.toString()
    }

    private fun tabLabel(t: Tab): String {
        val u = t.pending ?: (if (t.home) "" else t.url)
        if (Prefs.lockEnabled && Ui.isInstagram(u)) return "Instagram (protected)"
        if (t.home) return "New tab"
        return if (t.title.isBlank()) t.url else t.title
    }

    private fun showTabs() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        val sv = ScrollView(this)
        sv.addView(box)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tabs (" + tabs.size + ")")
            .setView(sv)
            .setPositiveButton("New tab") { _, _ -> newTab(null) }
            .setNegativeButton("Close", null)
            .create()
        for (t in ArrayList(tabs)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val label = TextView(this)
            label.text = (if (t === active) "\u25CF  " else "") + tabLabel(t)
            label.textSize = 15f
            label.maxLines = 1
            label.setTextColor(col(R.color.br_text))
            label.setPadding(dp(12), dp(14), dp(12), dp(14))
            label.setOnClickListener {
                dialog.dismiss()
                selectTab(t)
            }
            row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            val x = iconBtn(R.drawable.ic_close, "Close tab") {
                dialog.dismiss()
                closeTab(t)
                if (tabs.size > 1) showTabs()
            }
            row.addView(x, LinearLayout.LayoutParams(dp(44), dp(44)))
            box.addView(row)
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- navigation

    private fun normalize(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        val looksLikeUrl = !s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))
        return if (looksLikeUrl) "https://" + s else Prefs.engineUrl + URLEncoder.encode(s, "UTF-8")
    }

    private fun submitAddress() {
        val t = active ?: return
        val text = addressBar.text.toString()
        addressBar.clearFocus()
        hideKeyboard()
        navigate(t, text)
    }

    private fun navigate(t: Tab, raw: String) {
        val url = normalize(raw)
        if (url.isEmpty()) return
        t.pending = null
        if (Prefs.lockEnabled && Ui.isInstagram(url) && !unlocked) {
            t.pending = url
            t.home = false
            if (t === active) {
                refreshContent()
                updateAddress()
            }
            return
        }
        t.home = false
        t.url = url
        t.webView.loadUrl(url)
        if (t === active) {
            refreshContent()
            updateAddress()
        }
    }

    /** Returns true when the navigation was held back by the Instagram lock. */
    private fun gate(t: Tab, url: String): Boolean {
        if (Prefs.lockEnabled && Ui.isInstagram(url) && !unlocked) {
            t.pending = url
            t.webView.stopLoading()
            if (t === active) refreshContent()
            return true
        }
        return false
    }

    private fun updateAddress() {
        if (addressBar.hasFocus()) return
        val t = active
        val u = if (t == null || t.home) "" else (t.pending ?: t.url)
        addressBar.setText(
            u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        )
    }

    private fun goBack() {
        val t = active ?: return
        if (!t.home && t.webView.canGoBack()) t.webView.goBack()
    }

    private fun goForward() {
        val t = active ?: return
        if (!t.home && t.webView.canGoForward()) t.webView.goForward()
    }

    private fun reload() {
        val t = active ?: return
        if (!t.home && !needsLock()) t.webView.reload()
    }

    private fun goHome() {
        val t = active ?: return
        t.pending = null
        t.home = true
        if (unlocked) {
            IgVault.snapshotLive(true)
            unlocked = false
        }
        refreshContent()
        updateAddress()
    }

    // ---------------------------------------------------------------- menu & dialogs

    private fun showMenu() {
        val pm = PopupMenu(this, menuBtn)
        pm.menu.add(0, 1, 0, "New tab")
        pm.menu.add(0, 2, 1, "Add bookmark")
        pm.menu.add(0, 3, 2, "Bookmarks")
        pm.menu.add(0, 4, 3, "History")
        pm.menu.add(0, 5, 4, "Downloads")
        val desk = pm.menu.add(0, 6, 5, "Desktop site")
        desk.isCheckable = true
        desk.isChecked = active?.desktop == true
        if (Prefs.lockEnabled) pm.menu.add(0, 7, 6, "Lock Instagram now")
        pm.menu.add(0, 8, 7, "Settings")
        pm.menu.add(0, 9, 8, "Close Brave Rabbit")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(null)
                2 -> addBookmark()
                3 -> showLinks("Bookmarks", Prefs.bookmarks(), true)
                4 -> showLinks("History", Prefs.history().reversed(), false)
                5 -> openDownloads()
                6 -> {
                    val t = active
                    if (t != null) setDesktop(t, !t.desktop)
                }
                7 -> relock()
                8 -> startActivity(Intent(this, SettingsActivity::class.java))
                9 -> exitApp()
            }
            true
        }
        pm.show()
    }

    private fun addBookmark() {
        val t = active
        if (t == null || t.home) {
            toast("Open a page first")
            return
        }
        val u = t.pending ?: t.url
        if (u.isEmpty()) return
        Prefs.addBookmark(if (t.title.isBlank()) u else t.title, u)
        toast("Bookmark added")
    }

    private fun showLinks(title: String, items: List<Pair<String, String>>, deletable: Boolean) {
        if (items.isEmpty()) {
            toast("Nothing here yet")
            return
        }
        val lv = ListView(this)
        val labels = items.map { if (it.first.isBlank()) it.second else it.first }
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val b = AlertDialog.Builder(this).setTitle(title).setView(lv).setNegativeButton("Close", null)
        if (!deletable) {
            b.setNeutralButton("Clear") { _, _ ->
                Prefs.clearHistory()
                toast("History cleared")
            }
        }
        val dlg = b.create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            val t = active
            if (t != null) navigate(t, items[pos].second)
        }
        if (deletable) {
            lv.setOnItemLongClickListener { _, _, pos, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Delete this bookmark?")
                    .setPositiveButton("Delete") { _, _ ->
                        Prefs.removeBookmark(items[pos].second)
                        dlg.dismiss()
                        showLinks(title, Prefs.bookmarks(), true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        dlg.show()
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("Cannot open downloads on this phone")
        }
    }

    private fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        val s = t.webView.settings
        s.userAgentString = if (on) desktopUa else mobileUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        if (!t.home && !needsLock()) t.webView.reload()
        toast(if (on) "Desktop site on" else "Mobile site on")
    }

    // ---------------------------------------------------------------- WebView

    private fun setupWebView(t: Tab) {
        val wv = t.webView
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (mobileUa.isEmpty()) {
            mobileUa = s.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        }
        s.userAgentString = mobileUa
        wv.setBackgroundColor(col(R.color.br_bg))
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val r = request ?: return false
                val u = r.url
                val scheme = u.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    return r.isForMainFrame && gate(t, u.toString())
                }
                if (scheme == "mailto" || scheme == "tel") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                    }
                }
                // every other scheme (intent:, instagram://, market:// ...) is blocked
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == null || url == "about:blank") return
                if (gate(t, url)) return
                t.url = url
                if (t === active) {
                    progress.visibility = View.VISIBLE
                    updateAddress()
                    if (unlocked && !Ui.isInstagram(url)) relock()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                if (url == "about:blank") {
                    view?.clearHistory()
                    return
                }
                t.title = view?.title ?: url
                if (t === active) {
                    progress.visibility = View.GONE
                    updateAddress()
                }
                if (Ui.isInstagram(url)) {
                    if (unlocked) {
                        IgVault.visited = true
                        IgVault.snapshotLive(true)
                    }
                } else {
                    Prefs.addHistory(t.title, url)
                }
                CookieManager.getInstance().flush()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (t === active) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (title != null) t.title = title
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                leavingForPicker = true
                return try {
                    fileChooser.launch(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread { askSitePermission(req) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val cb = callback ?: return
                val o = origin ?: ""
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Location")
                        .setMessage("$o wants to know your location.")
                        .setPositiveButton("Allow") { _, _ ->
                            val perm = Manifest.permission.ACCESS_FINE_LOCATION
                            if (ContextCompat.checkSelfPermission(this@MainActivity, perm) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                cb.invoke(o, true, false)
                            } else {
                                leavingForPicker = true
                                permCallback = { r -> cb.invoke(o, r[perm] == true, false) }
                                permLauncher.launch(arrayOf(perm))
                            }
                        }
                        .setNegativeButton("Block") { _, _ -> cb.invoke(o, false, false) }
                        .setOnCancelListener { cb.invoke(o, false, false) }
                        .show()
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                (window.decorView as FrameLayout).addView(
                    view, FrameLayout.LayoutParams(MATCH, MATCH)
                )
                val c = WindowCompat.getInsetsController(window, window.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun askSitePermission(request: PermissionRequest) {
        val needed = ArrayList<String>()
        val names = ArrayList<String>()
        for (r in request.resources) {
            if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                needed.add(Manifest.permission.CAMERA)
                names.add("camera")
            }
            if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                needed.add(Manifest.permission.RECORD_AUDIO)
                names.add("microphone")
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.resources)
            return
        }
        val host = request.origin.host ?: "This site"
        AlertDialog.Builder(this)
            .setTitle("Permission request")
            .setMessage(host + " wants to use your " + names.joinToString(" and ") + ".")
            .setPositiveButton("Allow") { _, _ ->
                val missing = needed.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    leavingForPicker = true
                    permCallback = { res ->
                        if (missing.all { res[it] == true }) {
                            request.grant(request.resources)
                        } else {
                            request.deny()
                        }
                    }
                    permLauncher.launch(missing.toTypedArray())
                }
            }
            .setNegativeButton("Block") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        val go = {
            try {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                if (mime != null) req.setMimeType(mime)
                if (ua != null) req.addRequestHeader("User-Agent", ua)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie != null) req.addRequestHeader("Cookie", cookie)
                req.setTitle(name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name")
            } catch (e: Exception) {
                toast("Download failed")
            }
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            leavingForPicker = true
            permCallback = { r ->
                if (r.values.all { it }) go() else toast("Storage permission denied")
            }
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else {
            go()
        }
    }
}
