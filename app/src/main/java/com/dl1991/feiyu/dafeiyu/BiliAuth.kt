package com.dl1991.feiyu.dafeiyu

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * The user's OWN B站 login, captured from a WebView the user signs in to by hand
 * (password / SMS / QR — any verification is done by the user, never automated).
 * Only the bilibili.com cookie string is kept, in EncryptedSharedPreferences.
 * It is never exported in backups and never shown to any model.
 */
object BiliAuth {
    private const val FILE = "mibot_dafeiyu_bili"
    val loggedIn = MutableStateFlow(false)
    @Volatile private var cache: SharedPreferences? = null

    @SuppressLint("ApplySharedPref")
    private fun prefs(ctx: Context): SharedPreferences {
        cache?.let { return it }
        val app = ctx.applicationContext
        val p = runCatching {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(app, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrElse {
            // Keystore broken on this device: refuse to persist the cookie in clear text.
            app.getSharedPreferences(FILE + "_volatile", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        }
        cache = p
        loggedIn.value = !p.getString("cookie", null).isNullOrBlank()
        return p
    }

    private fun parse(cookie: String): Map<String, String> = cookie.split(';').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }.toMap()

    /** Only the cookies B站 needs; drops analytics noise. */
    private val KEEP = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut", "_uuid", "bili_ticket", "bili_ticket_expires")

    fun cookieHeader(ctx: Context): String = prefs(ctx).getString("cookie", null).orEmpty()
    fun isLoggedIn(ctx: Context): Boolean = parse(cookieHeader(ctx))["SESSDATA"].isNullOrBlank().not()
    fun csrf(ctx: Context): String? = parse(cookieHeader(ctx))["bili_jct"]?.takeIf { it.isNotBlank() }
    fun uid(ctx: Context): Long? = parse(cookieHeader(ctx))["DedeUserID"]?.toLongOrNull()
    fun uname(ctx: Context): String = prefs(ctx).getString("uname", null).orEmpty()
    fun savedAt(ctx: Context): Long = prefs(ctx).getLong("savedAt", 0)

    fun devId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("dev_id", null) ?: UUID.randomUUID().toString().uppercase().also { p.edit().putString("dev_id", it).apply() }
    }

    /** Read the WebView cookie jar; returns true once SESSDATA + bili_jct + DedeUserID are present. */
    fun captureFromWebView(ctx: Context): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val raw = listOf("https://www.bilibili.com", "https://bilibili.com", "https://passport.bilibili.com", "https://m.bilibili.com")
            .mapNotNull { cm.getCookie(it) }.joinToString("; ")
        val kv = parse(raw).filterKeys { it in KEEP }
        if (kv["SESSDATA"].isNullOrBlank() || kv["bili_jct"].isNullOrBlank() || kv["DedeUserID"].isNullOrBlank()) return false
        val header = kv.entries.joinToString("; ") { it.key + "=" + it.value }
        prefs(ctx).edit().putString("cookie", header).putLong("savedAt", System.currentTimeMillis()).apply()
        loggedIn.value = true
        return true
    }

    fun setUname(ctx: Context, name: String) { prefs(ctx).edit().putString("uname", clean(name, 40)).apply() }

    /** Forget the login here and in the WebView jar. */
    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("cookie").remove("uname").remove("savedAt").apply()
        runCatching {
            val cm = CookieManager.getInstance()
            listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid").forEach { n ->
                cm.setCookie("https://www.bilibili.com", "$n=; Max-Age=0; Domain=.bilibili.com; Path=/")
            }
            cm.flush()
        }
        loggedIn.value = false
    }

    fun refreshState(ctx: Context) { loggedIn.value = isLoggedIn(ctx) }
}
