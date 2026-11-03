package com.dl1991.feiyu.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.Routes
import com.dl1991.feiyu.dafeiyu.CrewConfig
import com.dl1991.feiyu.dafeiyu.CrewRole
import com.dl1991.feiyu.dafeiyu.FyAgent
import com.dl1991.feiyu.llm.FyProviders
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFilterChips
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySlider
import com.dl1991.feiyu.ui.FySpacer
import com.dl1991.feiyu.ui.FyToggle
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.StatusChip

private const val DEFAULT_MODEL = "默认（同一个）"

/** 导播台 › 「她在想」 + 直播专家组: her inner thought, goal, plan, and every expert's latest note. */
@Composable
fun CrewPanel(open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FyAgent.ensure(ctx); FyProviders.ensure(ctx) }
    val cfg by FyAgent.cfg.collectAsState()
    val ui by FyAgent.ui.collectAsState()
    val providers by FyProviders.list.collectAsState()
    var modelFor by remember { mutableStateOf<String?>(null) }

    FySectionHeader("她在想 · 直播专家组", actionLabel = "她的成长", onAction = { open(Routes.GROWTH) })
    FyCard(padding = PaddingValues(12.dp)) {
        FyToggle("AI 专家组（会思考的主播）", cfg.agentOn,
            sub = if (cfg.agentOn) "观察 → 思考 → 行动 → 复盘；某个专家出错就用规则顶上，导演连续出错回到简单引擎" else "关闭：用简单引擎（回弹幕 + 冷场补位）") { on ->
            FyAgent.update(ctx) { it.copy(agentOn = on) }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(ui.status, when { ui.status.contains("预算") || ui.status.contains("简单引擎") -> ChipKind.WARN; ui.status == "待命" -> ChipKind.NEUTRAL; else -> ChipKind.ACCENT })
            Text("思考 ${ui.ticks} · 兜底 ${ui.fallbacks} · ${ui.tokensHour / 1000}k/${cfg.tokensPerHour / 1000}k tok/时", fontSize = FyType.Caption, fontFamily = FyType.Mono)
        }
        FySpacer(8.dp)
        Text("「她在想」", fontSize = FyType.Secondary, fontWeight = FontWeight.Bold, color = FyTokens.accent())
        Text(ui.thought.ifBlank { "开播后，她不念出来的内心想法会出现在这里。" }, Modifier.padding(top = 2.dp), fontSize = FyType.Body)
        if (ui.goal.isNotBlank()) Text("目标：${ui.goal}", Modifier.padding(top = 6.dp), fontSize = FyType.Secondary, fontWeight = FontWeight.SemiBold)
        if (ui.plan.isNotEmpty()) {
            GroupLabel("接下来")
            ui.plan.forEach { Text("· $it", fontSize = FyType.Secondary, color = if (it.startsWith("✗")) FyTokens.warnColor() else MaterialTheme.colorScheme.onSurface) }
        }
        if (ui.results.isNotEmpty()) {
            GroupLabel("刚做了")
            ui.results.takeLast(3).forEach { Text(it, fontSize = FyType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (ui.lastDiary.isNotBlank()) { GroupLabel("最新日记"); Text(ui.lastDiary, fontSize = FyType.Secondary) }
    }

    FySpacer(8.dp)
    FyCard(padding = PaddingValues(12.dp)) {
        GroupLabel("预设（开箱即用）")
        FyFilterChips(CrewConfig.PRESETS.keys.toList(), cfg.preset) { FyAgent.applyPreset(ctx, it) }
        FySlider("思考间隔", cfg.intervalSec, 4..60, suffix = " 秒") { v -> FyAgent.update(ctx) { it.copy(intervalSec = v) } }
        FySlider("预算上限", cfg.tokensPerHour / 1000, 5..200, step = 5, suffix = "k token/时") { v -> FyAgent.update(ctx) { it.copy(tokensPerHour = v * 1000) } }
        val names = providers.filter { it.enabled }.associate { it.id to it.name }
        CrewRole.values().forEach { r ->
            val n = ui.notes[r.id]
            val on = cfg.on(r)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                FyToggle(r.label, on, sub = r.duty + when {
                    r == CrewRole.SAFETY && !on -> "（关闭后仍有规则过滤）"
                    !on -> "（关闭：用规则代替）"
                    else -> ""
                }) { v -> FyAgent.update(ctx) { it.copy(enabled = it.enabled + (r.id to v), preset = "自定义") } }
                Text(n?.text?.takeIf { it.isNotBlank() }?.let { (if (n?.viaRules == true) "〔规则〕" else "") + it } ?: "还没发言",
                    fontSize = FyType.Secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!n?.error.isNullOrBlank()) Text("⚠ ${n?.error}", fontSize = FyType.Caption, color = FyTokens.warnColor())
                val model = cfg.model(r)
                Text("模型：${names[model] ?: DEFAULT_MODEL} · 调用 ${n?.calls ?: 0} · ${n?.tokens ?: 0} tok  ›",
                    Modifier.clickable { modelFor = if (modelFor == r.id) null else r.id }.padding(vertical = 4.dp),
                    fontSize = FyType.Caption, fontFamily = FyType.Mono, color = FyTokens.accent())
                if (modelFor == r.id) {
                    val labels = listOf(DEFAULT_MODEL) + names.values
                    FyFilterChips(labels, names[model] ?: DEFAULT_MODEL) { l ->
                        val id = names.entries.firstOrNull { it.value == l }?.key.orEmpty()
                        FyAgent.update(ctx) { c -> c.copy(models = if (id.isBlank()) c.models - r.id else c.models + (r.id to id)) }
                        modelFor = null
                    }
                }
            }
        }
    }
    FyFootnote("所有专家默认用同一个模型（「模型服务」里第一个可用的，Kilo 免费网关无需 Key）。真人接管时她照常思考，台词进下面的审核队列。")
}
