package com.braverabbit.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** PIN is never stored in plain text: salted PBKDF2 hash only. */
object PinManager {
    private const val DEFAULT_PIN = "1258"

    fun ensureDefault() {
        if (Prefs.raw.getString("pin_hash", null) == null) set(DEFAULT_PIN)
    }

    fun set(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        Prefs.raw.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun verify(pin: String): Boolean {
        val s = Prefs.raw.getString("pin_salt", null) ?: return false
        val h = Prefs.raw.getString("pin_hash", null) ?: return false
        val salt = Base64.decode(s, Base64.NO_WRAP)
        val expected = Base64.decode(h, Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/**
 * Keeps the Instagram login cookies in Android-Keystore-encrypted storage, separate from
 * everything else, so routine privacy cleaning can wipe the browser and then put them back.
 */
object IgVault {
    private const val URL = "https://www.instagram.com"
    private const val ALIAS = "br_ig_vault_key"

    /** True once an Instagram page has loaded in this process. */
    @Volatile
    var visited = false

    private fun store() = App.instance.getSharedPreferences("ig_vault", Context.MODE_PRIVATE)

    /** Copy the live Instagram cookies into the encrypted vault. */
    fun snapshotLive(afterVisit: Boolean) {
        try {
            val live = CookieManager.getInstance().getCookie(URL) ?: ""
            if (live.contains("sessionid=")) {
                store().edit().putString("c", encrypt(live)).apply()
            } else if (afterVisit && visited) {
                // Instagram was used in this run and the session cookie is gone: user logged out.
                store().edit().clear().apply()
            }
        } catch (e: Exception) {
            // never log cookies
        }
    }

    /** Put the vault cookies back into the browser. */
    fun restore() {
        try {
            val enc = store().getString("c", null) ?: return
            val raw = decrypt(enc)
            val cm = CookieManager.getInstance()
            for (pair in raw.split("; ")) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val name = pair.substring(0, i)
                val value = pair.substring(i + 1)
                val httpOnly = if (name == "sessionid") "; HttpOnly" else ""
                cm.setCookie(URL, "$name=$value; Domain=.instagram.com; Path=/; Max-Age=31536000; Secure$httpOnly")
            }
            cm.flush()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Explicit logout: wipe the vault and the live Instagram cookies. */
    fun clearAll() {
        try {
            val cm = CookieManager.getInstance()
            val live = cm.getCookie(URL) ?: ""
            for (pair in live.split("; ")) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val name = pair.substring(0, i)
                cm.setCookie(URL, "$name=; Domain=.instagram.com; Path=/; Max-Age=0")
            }
            cm.flush()
        } catch (e: Exception) {
        }
        store().edit().clear().apply()
        visited = false
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
}

/** Wipes normal browsing data but keeps the encrypted Instagram session. Must run on the main thread. */
object PrivacyCleaner {
    fun clean(ctx: Context, done: () -> Unit) {
        val cm = CookieManager.getInstance()
        IgVault.snapshotLive(IgVault.visited)
        cm.removeAllCookies {
            try {
                WebStorage.getInstance().deleteAllData()
                val db = WebViewDatabase.getInstance(ctx)
                db.clearFormData()
                db.clearHttpAuthUsernamePassword()
            } catch (e: Exception) {
            }
            Prefs.clearHistory()
            deleteChildren(ctx.cacheDir)
            ctx.externalCacheDir?.let { deleteChildren(it) }
            IgVault.restore()
            cm.flush()
            done()
        }
    }

    private fun deleteChildren(dir: File) {
        try {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
        }
    }
}
