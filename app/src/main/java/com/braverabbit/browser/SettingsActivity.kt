package com.braverabbit.browser

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        sv.setBackgroundColor(col(R.color.br_bg))
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16), dp(12), dp(16), dp(32))
        sv.addView(column)
        setContentView(sv)
        populate()
    }

    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun populate() {
        column.removeAllViews()

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = ImageButton(this)
        back.setImageResource(R.drawable.ic_back)
        back.setColorFilter(col(R.color.br_text))
        back.setBackgroundColor(0)
        back.setOnClickListener { finish() }
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = TextView(this)
        title.text = "Settings"
        title.textSize = 22f
        title.setTextColor(col(R.color.br_text))
        title.setPadding(dp(8), 0, 0, 0)
        header.addView(title)
        column.addView(header)

        section("Instagram privacy")

        switchRow(
            "Instagram PIN lock",
            "Ask for a PIN every time Instagram is opened",
            Prefs.lockEnabled
        ) { sw, on ->
            if (on) {
                Prefs.lockEnabled = true
            } else {
                sw.isChecked = true
                Ui.askPin(this, "Enter PIN to turn lock off") { pin ->
                    if (PinManager.verify(pin)) {
                        Prefs.lockEnabled = false
                        populate()
                    } else {
                        toast("Wrong PIN")
                    }
                }
            }
        }

        row("Change PIN", "Default PIN is 1258 - please change it") { changePin() }

        switchRow(
            "Biometric unlock",
            "Use fingerprint / face if your phone supports it",
            Prefs.biometric
        ) { sw, on ->
            if (on) {
                val ok = BiometricManager.from(this)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    BiometricManager.BIOMETRIC_SUCCESS
                if (ok) {
                    Prefs.biometric = true
                } else {
                    sw.isChecked = false
                    toast("No fingerprint / face set up on this phone")
                }
            } else {
                Prefs.biometric = false
            }
        }

        row("Clear Instagram login session", "Logs you out of Instagram inside Brave Rabbit") {
            val doIt = {
                AlertDialog.Builder(this)
                    .setMessage("This removes the saved Instagram login. You will need to log in again.")
                    .setPositiveButton("Clear") { _, _ ->
                        IgVault.clearAll()
                        toast("Instagram session cleared")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                Unit
            }
            if (Prefs.lockEnabled) {
                Ui.askPin(this, "Enter PIN") { pin ->
                    if (PinManager.verify(pin)) doIt() else toast("Wrong PIN")
                }
            } else {
                doIt()
            }
        }

        section("Privacy")

        switchRow(
            "Automatic privacy cleaning",
            "Clear tabs, history, cookies, cache and form data when the browser is closed",
            Prefs.autoClean
        ) { _, on -> Prefs.autoClean = on }

        row("Clear all browsing data now", "Keeps your Instagram login") {
            AlertDialog.Builder(this)
                .setMessage("Clear history, cookies, cache and site data now? Your Instagram login is kept.")
                .setPositiveButton("Clear") { _, _ ->
                    PrivacyCleaner.clean(this) { toast("Browsing data cleared") }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        section("Appearance & search")

        val themeNames = arrayOf("Follow system", "Light", "Dark")
        row("Theme", themeNames[Prefs.theme.coerceIn(0, 2)]) {
            AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(themeNames, Prefs.theme.coerceIn(0, 2)) { d, which ->
                    Prefs.theme = which
                    d.dismiss()
                    App.applyTheme()
                    populate()
                }
                .show()
        }

        row("Default search engine", Engines.names[Prefs.engine.coerceIn(0, Engines.names.size - 1)]) {
            AlertDialog.Builder(this)
                .setTitle("Search engine")
                .setSingleChoiceItems(
                    Engines.names.toTypedArray(),
                    Prefs.engine.coerceIn(0, Engines.names.size - 1)
                ) { d, which ->
                    Prefs.engine = which
                    d.dismiss()
                    populate()
                }
                .show()
        }

        section("About")
        row("About Brave Rabbit", "Version 1.0") { showAbout() }
    }

    private fun changePin() {
        Ui.askPin(this, "Current PIN") { cur ->
            if (!PinManager.verify(cur)) {
                toast("Wrong PIN")
                return@askPin
            }
            Ui.askPin(this, "New PIN (4-8 digits)") { p1 ->
                if (p1.length < 4) {
                    toast("PIN must be at least 4 digits")
                    return@askPin
                }
                Ui.askPin(this, "Confirm new PIN") { p2 ->
                    if (p1 == p2) {
                        PinManager.set(p1)
                        toast("PIN changed")
                    } else {
                        toast("PINs did not match")
                    }
                }
            }
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Brave Rabbit")
            .setMessage(
                "Browse Freely. Stay Private.\n\n" +
                    "Brave Rabbit is an independent browser built on Android WebView. " +
                    "It is not made by Brave Software and does not use the Brave engine.\n\n" +
                    "Known limits of WebView:\n" +
                    "- Google sign-in may be blocked inside WebView browsers.\n" +
                    "- Only the Instagram login is kept between sessions; other sites log out when the browser closes.\n" +
                    "- Instagram screens are hidden from screenshots and the recent-apps preview while the lock is on."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun section(text: String) {
        val t = TextView(this)
        t.text = text.uppercase()
        t.textSize = 12f
        t.letterSpacing = 0.08f
        t.setTextColor(col(R.color.br_accent))
        t.setPadding(dp(4), dp(24), 0, dp(6))
        column.addView(t)
    }

    private fun row(title: String, sub: String, onClick: () -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.background = Ui.rounded(col(R.color.br_surface), dp(14))
        val a = TextView(this)
        a.text = title
        a.textSize = 16f
        a.setTextColor(col(R.color.br_text))
        val b = TextView(this)
        b.text = sub
        b.textSize = 13f
        b.setTextColor(col(R.color.br_text2))
        box.addView(a)
        box.addView(b)
        box.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6)
        column.addView(box, lp)
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (SwitchCompat, Boolean) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.background = Ui.rounded(col(R.color.br_surface), dp(14))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val a = TextView(this)
        a.text = title
        a.textSize = 16f
        a.setTextColor(col(R.color.br_text))
        val b = TextView(this)
        b.text = sub
        b.textSize = 13f
        b.setTextColor(col(R.color.br_text2))
        texts.addView(a)
        texts.addView(b)
        val sw = SwitchCompat(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { v, on -> onChange(sw, on) }
        box.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sw)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6)
        column.addView(box, lp)
    }
}
