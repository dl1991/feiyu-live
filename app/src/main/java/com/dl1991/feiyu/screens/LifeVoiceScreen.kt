package com.dl1991.feiyu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.Routes
import com.dl1991.feiyu.dafeiyu.DfyRoutes
import com.dl1991.feiyu.dafeiyu.DfyStore
import com.dl1991.feiyu.dafeiyu.DfyTtsRouter
import com.dl1991.feiyu.dafeiyu.DfyVoiceConfig
import com.dl1991.feiyu.dafeiyu.MemStage
import com.dl1991.feiyu.llm.FyProvider
import com.dl1991.feiyu.llm.FyProviders
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyPageTitle
import com.dl1991.feiyu.ui.FyRow
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FyToggle
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.StatusChip
import kotlinx.coroutines.launch

/** A. 「人生 & 声音」: model keys, voice, persona and her life system. */
@Composable
fun LifeVoiceScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FyProviders.ensure(ctx); DfyStore.ensureLoaded(ctx) }
    val providers by FyProviders.list.collectAsState()
    val probes by FyProviders.probes.collectAsState()
    val voice by DfyVoiceConfig.config.collectAsState()
    val st by DfyStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var voiceMsg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        FyPageTitle("人生 & 声音", kicker = "A · LIFE & VOICE", subtitle = "她用什么模型思考、用什么声音说话、是个什么样的鱼")

        FySectionHeader("模型服务（OpenAI 兼容）")
        FyCard {
            providers.forEachIndexed { i, p ->
                val pr = probes[p.id]
                val needKey = p.needsKey && FyProviders.key(ctx, p).isBlank()
                FyRow(
                    p.name, "${p.model} · ${p.baseUrl.removePrefix("https://")}",
                    onClick = { expanded = if (expanded == p.id) null else p.id }, chevron = false,
                    trailing = {
                        when {
                            testing[p.id] == true -> StatusChip("测试中…", ChipKind.NEUTRAL)
                            !p.enabled -> StatusChip("已停用", ChipKind.NEUTRAL)
                            needKey -> StatusChip("需要 Key", ChipKind.WARN)
                            pr == null -> StatusChip(if (!p.needsKey) "免 Key" else "未测试", ChipKind.NEUTRAL)
                            pr.ok -> StatusChip("在线 ${pr.latencyMs}ms", ChipKind.OK)
                            else -> StatusChip("失败", ChipKind.ERR)
                        }
                    },
                    showDivider = i < providers.lastIndex || expanded == p.id,
                )
                if (expanded == p.id) ProviderEditor(p, pr?.detail, testing[p.id] == true,
                    onTest = { scope.launch { testing[p.id] = true; FyProviders.probe(ctx, p); testing[p.id] = false } },
                    onDone = { expanded = null })
            }
        }
        val missing = FyProviders.PRESETS.filter { pre -> providers.none { it.id == pre.id } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            missing.forEach { pre -> FyButton("+ ${pre.name}", { FyProviders.upsert(ctx, pre); expanded = pre.id }, tonal = true) }
            FyButton("+ 自定义", {
                val id = "custom" + (System.currentTimeMillis() % 100000)
                FyProviders.upsert(ctx, FyProvider(id, "自定义服务", "https://", "", preset = "custom")); expanded = id
            }, tonal = true)
        }
        FyFootnote("按列表顺序调用，失败自动换下一个；「优先免费模型」开启时免费的排前面。Kilo 免费网关不需要 Key。Key 用 Android Keystore 加密保存在本机。「测试」会真的请求 /models 并发一条消息，显示首字延迟。")

        FySectionHeader("声音", actionLabel = "全部设置", onAction = { open(DfyRoutes.VOICE) })
        FyCard {
            FyRow("引擎", when (voice.engine) { "api" -> "OpenAI 兼容 /audio/speech"; "system" -> "系统 TTS（离线）"; else -> "Edge 神经语音（免费）" } +
                " · " + when (voice.engine) { "api" -> voice.apiVoice; "system" -> "系统默认"; else -> voice.edgeVoice }, onClick = { open(DfyRoutes.VOICE) }, showDivider = true)
            FyRow("测试当前声音", voiceMsg.ifBlank { "合成一句并播放，显示耗时" }, chevron = false, onClick = {
                scope.launch { voiceMsg = "合成中…"; val r = DfyTtsRouter.test(ctx, voice.engine); voiceMsg = if (r.ok) "${r.engine} · ${r.latencyMs} ms · ${r.detail}" else "${r.engine} 失败：${r.detail}" }
            })
        }

        FySectionHeader("人设")
        FyCard {
            FyRow("人设编辑", st.settings.personaPrompt.ifBlank { "默认人设：大肥鱼（DeepSeek 蓝鲸同人）" }.lines().first().take(40) +
                " · 称呼你「${st.settings.ownerName}」", onClick = { open(Routes.PERSONA) })
        }

        FySectionHeader("人生系统")
        FyCard {
            FyRow("大肥鱼的家", "心情、兴趣、今天做了什么", onClick = { open(DfyRoutes.HOME) }, showDivider = true)
            FyRow("人生记忆", "${st.episodes.count { it.stage != MemStage.FORGOTTEN }} 条经历 · ${st.insights.count { !it.faded }} 条认识", onClick = { open(DfyRoutes.MEMORY) }, showDivider = true)
            FyRow("时间线", null, onClick = { open(DfyRoutes.TIMELINE) }, showDivider = true)
            FyRow("知识库", "${st.knowledge.size} 个学习目标", onClick = { open(DfyRoutes.KNOWLEDGE) }, showDivider = true)
            FyRow("观众记忆", "${st.viewers.size} 位观众", onClick = { open(DfyRoutes.VIEWERS) }, showDivider = true)
            FyRow("和她私聊", null, onClick = { open(DfyRoutes.CHAT) }, showDivider = true)
            FyRow("B站生活 · 记忆 · 备份", if (st.settings.lifeEnabled) "B站生活循环：开（每 ${st.settings.intervalMinutes} 分钟）" else "B站生活循环：关", onClick = { open(DfyRoutes.SETTINGS) })
        }
        FyFootnote("人生系统移植自 dsh-dafeiyu-life（MIT，© 2026 c1overzz）：心情 8 种（18 小时半衰期）、兴趣阶段、记忆衰减与反思。")
    }
}

@Composable
private fun ProviderEditor(p: FyProvider, detail: String?, testing: Boolean, onTest: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember(p.id) { mutableStateOf(p.name) }
    var base by remember(p.id) { mutableStateOf(p.baseUrl) }
    var model by remember(p.id) { mutableStateOf(p.model) }
    var key by remember(p.id) { mutableStateOf("") }
    var needsKey by remember(p.id) { mutableStateOf(p.needsKey) }
    var enabled by remember(p.id) { mutableStateOf(p.enabled) }
    val hasKey = FyProviders.key(ctx, p).isNotBlank()
    fun save(): FyProvider {
        val np = p.copy(name = name.trim().ifBlank { p.name }, baseUrl = base.trim().trimEnd('/'), model = model.trim(), needsKey = needsKey, enabled = enabled)
        FyProviders.upsert(ctx, np)
        if (key.isNotBlank()) { FyProviders.setKey(ctx, np, key.trim()); key = "" }
        return np
    }
    Column(Modifier.fillMaxWidth().background(FyTokens.tile()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.preset == "custom") GlassField(name, { name = it }, "名称")
        GlassField(base, { base = it }, "Base URL", placeholder = "https://api.example.com/v1")
        GlassField(model, { model = it }, "模型", placeholder = "model-id")
        if (needsKey) GlassField(key, { key = it }, "API Key（加密保存）", secret = true, placeholder = if (hasKey) "已保存 ••••（留空不改）" else "sk-…")
        FyToggle("需要 API Key", needsKey) { needsKey = it }
        FyToggle("启用", enabled) { enabled = it }
        if (detail != null) Text("上次测试：$detail", fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FyButton("保存", { save(); onDone() })
            FyButton(if (testing) "测试中…" else "保存并测试", { save(); onTest() }, enabled = !testing, tonal = true)
            Text("上移", Modifier.clickable { FyProviders.move(ctx, p.id, true) }.padding(6.dp), fontSize = FyType.Secondary, color = FyTokens.accent())
            Text("删除", Modifier.clickable { FyProviders.remove(ctx, p.id); onDone() }.padding(6.dp), fontSize = FyType.Secondary, color = FyTokens.errColor())
        }
    }
    HorizontalDivider(thickness = 1.dp, color = FyTokens.hairline())
}
