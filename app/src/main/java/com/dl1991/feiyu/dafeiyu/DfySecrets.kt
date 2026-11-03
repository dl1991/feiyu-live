package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted local secrets for this module: 抖音推流地址 / 推流码 and the optional TTS API key.
 * Stored with AES-256 (Android Keystore master key). Never exported in backups, never sent to models.
 * If the keystore is unusable on the device nothing is persisted (the user has to re-enter).
 */
object DfySecrets {
    private const val FILE = "mibot_dafeiyu_secrets"
    @Volatile private var p: SharedPreferences? = null
    private val volatileMap = HashMap<String, String>()

    private fun prefs(ctx: Context): SharedPreferences? {
        p?.let { return it }
        val app = ctx.applicationContext
        return runCatching {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(app, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()?.also { p = it }
    }

    fun get(ctx: Context, k: String): String = prefs(ctx)?.getString(k, null) ?: synchronized(volatileMap) { volatileMap[k] } ?: ""
    fun set(ctx: Context, k: String, v: String) {
        val pr = prefs(ctx)
        if (pr != null) pr.edit().putString(k, v.trim()).apply() else synchronized(volatileMap) { volatileMap[k] = v.trim() }
    }
    fun has(ctx: Context, k: String) = get(ctx, k).isNotBlank()

    /** "rtmp://…/stage" + "stream-key" — shown masked in the UI. */
    fun mask(s: String): String = if (s.length <= 8) "••••" else s.take(4) + "••••" + s.takeLast(3)

    const val RTMP_URL = "rtmp_url"
    const val RTMP_KEY = "rtmp_key"
    const val TTS_API_KEY = "tts_api_key"
}
