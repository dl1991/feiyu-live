package com.dl1991.feiyu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.dafeiyu.DfySecrets
import com.dl1991.feiyu.dafeiyu.DfySpeaker
import com.dl1991.feiyu.dafeiyu.DfyTtsRouter
import com.dl1991.feiyu.dafeiyu.DfyVoiceConfig
import com.dl1991.feiyu.dafeiyu.EdgeTts
import com.dl1991.feiyu.dafeiyu.FyDirector
import com.dl1991.feiyu.dafeiyu.VoiceConfig
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFilterChips
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySlider
import com.dl1991.feiyu.ui.FySpacer
import com.dl1991.feiyu.ui.FyToggle
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.StatusChip
import kotlinx.coroutines.launch

private val ENGINES = listOf("edge" to "Edge 神经语音", "system" to "系统 TTS", "api" to "OpenAI 兼容")

/** 声音: engine, voice, prosody, volume, mute and a test button. Everything persists via [DfyVoiceConfig]. */
@Composable
fun VoiceScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    remember { DfyVoiceConfig.get(ctx) } // load persisted config once
    val cfg by DfyVoiceConfig.config.collectAsState()
    val dir by FyDirector.state.collectAsState()
    val scope = rememberCoroutineScope()
    var testMsg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf(if (DfySecrets.has(ctx, DfySecrets.TTS_API_KEY)) "已保存密钥" else "") }
    // Text fields keep local text and commit on save, so typing doesn't write prefs on every key.
    var baseUrl by remember(cfg.apiBaseUrl) { mutableStateOf(cfg.apiBaseUrl) }
    var model by remember(cfg.apiModel) { mutableStateOf(cfg.apiModel) }
    var apiVoice by remember(cfg.apiVoice) { mutableStateOf(cfg.apiVoice) }

    fun set(f: (VoiceConfig) -> VoiceConfig) = DfyVoiceConfig.update(ctx, f)

    FyScaffold("声音", back) {
        FySectionHeader("引擎")
        FyCard(padding = PaddingValues(12.dp)) {
            FyFilterChips(ENGINES.map { it.second }, ENGINES.firstOrNull { it.first == cfg.engine }?.second ?: ENGINES[0].second) { label ->
                ENGINES.firstOrNull { it.second == label }?.let { e -> set { it.copy(engine = e.first) } }
            }
            Text(
                when (cfg.engine) {
                    "api" -> "调用 /audio/speech（如 SiliconFlow CosyVoice），音色最自然，需要 API Key。"
                    "system" -> "安卓系统自带 TTS，离线可用，音色取决于手机。"
                    else -> "微软 Edge 在线神经语音，免费，需要联网。失败时自动回退到系统 TTS。"
                },
                fontSize = FyType.Secondary,
            )
        }

        when (cfg.engine) {
            "edge" -> {
                FySectionHeader("音色")
                FyCard(padding = PaddingValues(12.dp)) {
                    FyFilterChips(EdgeTts.VOICES, cfg.edgeVoice) { v -> set { it.copy(edgeVoice = v) } }
                }
            }
            "api" -> {
                FySectionHeader("OpenAI 兼容 TTS")
                FyCard(padding = PaddingValues(12.dp)) {
                    GlassField(baseUrl, { baseUrl = it }, "Base URL", placeholder = "https://api.siliconflow.cn/v1")
                    FySpacer(8.dp)
                    GlassField(model, { model = it }, "模型")
                    FySpacer(8.dp)
                    GlassField(apiVoice, { apiVoice = it }, "音色 voice")
                    FySpacer(8.dp)
                    GlassField(apiKey, { apiKey = it }, "API Key", placeholder = if (keyMsg.isNotBlank()) "已保存，留空则不修改" else "sk-…", secret = true)
                    FySpacer(10.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FyButton("保存", {
                            set { it.copy(apiBaseUrl = baseUrl, apiModel = model, apiVoice = apiVoice) }
                            if (apiKey.isNotBlank()) { DfySecrets.set(ctx, DfySecrets.TTS_API_KEY, apiKey.trim()); apiKey = "" }
                            keyMsg = if (DfySecrets.has(ctx, DfySecrets.TTS_API_KEY)) "已保存密钥" else "未设置密钥"
                        }, tonal = true)
                        if (keyMsg.isNotBlank()) StatusChip(keyMsg, if (DfySecrets.has(ctx, DfySecrets.TTS_API_KEY)) ChipKind.OK else ChipKind.WARN)
                    }
                }
            }
        }

        FySectionHeader("语调")
        FyCard(padding = PaddingValues(12.dp)) {
            FySlider("语速", cfg.speedPct, -50..50, suffix = "%", left = "慢", right = "快") { v -> set { it.copy(speedPct = v) } }
            FySlider("音高", cfg.pitchPct, -50..50, suffix = "%", left = "低", right = "高") { v -> set { it.copy(pitchPct = v) } }
            FySlider("情绪强度", cfg.emotionStrength, 0..100, left = "平稳播报", right = "情绪饱满") { v -> set { it.copy(emotionStrength = v) } }
            FyToggle("语气词", cfg.fillers, sub = "说话时自然加入 嗯 / 哈 / 诶") { on -> set { it.copy(fillers = on) } }
        }

        FySectionHeader("输出")
        FyCard(padding = PaddingValues(12.dp)) {
            FySlider("音量", cfg.volume, 0..100, suffix = "%") { v -> set { it.copy(volume = v) } }
            FyToggle("静音", dir.muted, sub = "她照常说话，但不出声（直播中同样生效）") { on -> FyDirector.setMuted(on) }
        }

        FySectionHeader("试听")
        FyCard(padding = PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton(if (testing) "合成中…" else "测试当前声音", {
                    testing = true; testMsg = "合成中…"
                    scope.launch {
                        val r = runCatching { DfyTtsRouter.test(ctx, cfg.engine) }.getOrNull()
                        testMsg = when {
                            r == null -> "测试失败"
                            r.ok -> "${r.engine} · 首包 ${r.latencyMs} ms · 音频 ${r.audioMs} ms · ${r.detail}"
                            else -> "${r.engine} 失败：${r.detail}"
                        }
                        testing = false
                    }
                }, enabled = !testing)
                FyButton("停止", { DfySpeaker.interrupt() }, tonal = true)
            }
            if (testMsg.isNotBlank()) { FySpacer(8.dp); Text(testMsg, fontSize = FyType.Secondary) }
        }
        FyFootnote("所有声音设置都会保存；API Key 加密存放在本机，不会出现在备份里。")
    }
}
