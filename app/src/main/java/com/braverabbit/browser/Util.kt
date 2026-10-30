package com.braverabbit.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

object Engines {
    val names = listOf("Google", "DuckDuckGo", "Bing", "Brave Search")
    val urls = listOf(
        "https://www.google.com/search?q=",
        "https://duckduckgo.com/?q=",
        "https://www.bing.com/search?q=",
        "https://search.brave.com/search?q="
    )
}

object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx.toFloat()
        return d
    }

    /** True for instagram.com and every subdomain (plus its short-link domains). */
    fun isInstagram(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "instagr.am" || host == "ig.me"
    }

    fun askPin(ctx: Context, title: String, onOk: (String) -> Unit) {
        val et = EditText(ctx)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        et.hint = "PIN"
        val box = FrameLayout(ctx)
        box.setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), 0)
        box.addView(et)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
