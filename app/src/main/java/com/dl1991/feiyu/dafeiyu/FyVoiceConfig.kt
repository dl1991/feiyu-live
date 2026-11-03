package com.dl1991.feiyu.dafeiyu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every voice setting, persisted as a whole (fixes the earlier bug where the OpenAI-compatible
 * TTS base URL / model / voice lived in volatile fields and reset to defaults on restart).
 * The API key stays in [DfySecrets] (encrypted), never in this file.
 */
data class VoiceConfig(
    val engine: String = "edge",                 // edge | system | api
    val edgeVoice: String = "zh-CN-XiaoyiNeural",
    val apiBaseUrl: String = "https://api.siliconflow.cn/v1",
    val apiModel: String = "FunAudioLLM/CosyVoice2-0.5B",
    val apiVoice: String = "FunAudioLLM/CosyVoice2-0.5B:anna",
    val speedPct: Int = 0,                       // -50..50, added to the mood prosody
    val pitchPct: Int = 0,                       // -50..50
    val emotionStrength: Int = 70,               // 0..100: how strongly mood moves prosody
    val fillers: Boolean = true,                 // 嗯/哈/诶 fillers in speech
    val volume: Int = 90,                        // 0..100
) {
    fun sanitized() = copy(
        engine = engine.takeIf { it in setOf("edge", "system", "api") } ?: "edge",
        speedPct = speedPct.coerceIn(-50, 50), pitchPct = pitchPct.coerceIn(-50, 50),
        emotionStrength = emotionStrength.coerceIn(0, 100), volume = volume.coerceIn(0, 100),
        apiBaseUrl = apiBaseUrl.trim().trimEnd('/'),
    )

    /** Mood prosody scaled by [emotionStrength], then the user's fixed speed/pitch offsets. */
    fun apply(mood: VoiceStyle): VoiceStyle {
        val k = emotionStrength / 70.0
        return VoiceStyle(
            ((mood.ratePct * k) + speedPct).toInt().coerceIn(-60, 80),
            ((mood.pitchPct * k) + pitchPct).toInt().coerceIn(-50, 50),
            (mood.volumePct * k).toInt().coerceIn(-30, 30),
        )
    }
}

object DfyVoiceConfig {
    private const val PREFS = "feiyu_voice"
    private val _cfg = MutableStateFlow(VoiceConfig())
    val config: StateFlow<VoiceConfig> get() = _cfg
    @Volatile private var loaded = false

    fun get(ctx: Context): VoiceConfig {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val d = VoiceConfig()
                _cfg.value = VoiceConfig(
                    engine = p.getString("engine", d.engine) ?: d.engine,
                    edgeVoice = p.getString("edge_voice", d.edgeVoice) ?: d.edgeVoice,
                    apiBaseUrl = p.getString("api_base", d.apiBaseUrl) ?: d.apiBaseUrl,
                    apiModel = p.getString("api_model", d.apiModel) ?: d.apiModel,
                    apiVoice = p.getString("api_voice", d.apiVoice) ?: d.apiVoice,
                    speedPct = p.getInt("speed", d.speedPct), pitchPct = p.getInt("pitch", d.pitchPct),
                    emotionStrength = p.getInt("emotion", d.emotionStrength), fillers = p.getBoolean("fillers", d.fillers),
                    volume = p.getInt("volume", d.volume),
                ).sanitized()
                loaded = true
                applyRuntime(_cfg.value)
            }
        }
        return _cfg.value
    }

    fun set(ctx: Context, c0: VoiceConfig) {
        val c = c0.sanitized()
        _cfg.value = c; loaded = true
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("engine", c.engine).putString("edge_voice", c.edgeVoice)
            .putString("api_base", c.apiBaseUrl).putString("api_model", c.apiModel).putString("api_voice", c.apiVoice)
            .putInt("speed", c.speedPct).putInt("pitch", c.pitchPct).putInt("emotion", c.emotionStrength)
            .putBoolean("fillers", c.fillers).putInt("volume", c.volume)
            .commit()
        applyRuntime(c)
    }

    fun update(ctx: Context, f: (VoiceConfig) -> VoiceConfig) = set(ctx, f(get(ctx)))

    private fun applyRuntime(c: VoiceConfig) {
        EdgeTts.voice = c.edgeVoice
        DfySpeaker.gain = c.volume / 100f
    }
}
