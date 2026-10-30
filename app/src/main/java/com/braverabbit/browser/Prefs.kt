package com.braverabbit.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private lateinit var sp: SharedPreferences
    val raw: SharedPreferences get() = sp

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("br_prefs", Context.MODE_PRIVATE)
    }

    var lockEnabled: Boolean
        get() = sp.getBoolean("lock", true)
        set(v) { sp.edit().putBoolean("lock", v).apply() }

    var biometric: Boolean
        get() = sp.getBoolean("bio", false)
        set(v) { sp.edit().putBoolean("bio", v).apply() }

    var autoClean: Boolean
        get() = sp.getBoolean("autoclean", true)
        set(v) { sp.edit().putBoolean("autoclean", v).apply() }

    /** 0 = follow system, 1 = light, 2 = dark */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var engine: Int
        get() = sp.getInt("engine", 0)
        set(v) { sp.edit().putInt("engine", v).apply() }

    val engineUrl: String
        get() = Engines.urls[engine.coerceIn(0, Engines.urls.size - 1)]

    fun bookmarks(): List<Pair<String, String>> = readList("bookmarks")

    fun addBookmark(title: String, url: String) {
        val l = bookmarks().toMutableList()
        if (l.any { it.second == url }) return
        l.add(Pair(title, url))
        writeList("bookmarks", l)
    }

    fun removeBookmark(url: String) {
        writeList("bookmarks", bookmarks().filter { it.second != url })
    }

    fun history(): List<Pair<String, String>> = readList("history")

    fun addHistory(title: String, url: String) {
        val l = history().toMutableList()
        if (l.isNotEmpty() && l[l.size - 1].second == url) return
        l.add(Pair(title, url))
        while (l.size > 300) l.removeAt(0)
        writeList("history", l)
    }

    fun clearHistory() {
        sp.edit().remove("history").apply()
    }

    private fun readList(key: String): List<Pair<String, String>> {
        val s = sp.getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.getString("t"), o.getString("u")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, l: List<Pair<String, String>>) {
        val a = JSONArray()
        for (p in l) {
            val o = JSONObject()
            o.put("t", p.first)
            o.put("u", p.second)
            a.put(o)
        }
        sp.edit().putString(key, a.toString()).apply()
    }
}
