package com.dl1991.feiyu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.dafeiyu.DfyLive
import com.dl1991.feiyu.dafeiyu.DfyMood
import com.dl1991.feiyu.dafeiyu.DfyRoutes
import com.dl1991.feiyu.dafeiyu.DfyStore
import com.dl1991.feiyu.dafeiyu.DfyVoiceConfig
import com.dl1991.feiyu.dafeiyu.Expr
import com.dl1991.feiyu.dafeiyu.FyContentStore
import com.dl1991.feiyu.dafeiyu.FyPlatforms
import com.dl1991.feiyu.dafeiyu.LivePlatform
import com.dl1991.feiyu.dafeiyu.SurfMode
import com.dl1991.feiyu.dafeiyu.WhaleAvatar
import com.dl1991.feiyu.llm.FyProviders
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyPageTitle
import com.dl1991.feiyu.ui.FyRow
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FyStat
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.StatusChip

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { DfyStore.ensureLoaded(ctx); FyProviders.ensure(ctx); FyPlatforms.ensure(ctx); FyContentStore.ensure(ctx) }
    val st by DfyStore.state.collectAsState()
    val room by DfyLive.room.collectAsState()
    val providers by FyProviders.list.collectAsState()
    val probes by FyProviders.probes.collectAsState()
    val voice by DfyVoiceConfig.config.collectAsState()
    val content by FyContentStore.state.collectAsState()
    val preset by FyContentStore.activePreset.collectAsState()
    val platform by FyPlatforms.selected.collectAsState()
    val sources by FyPlatforms.sources.collectAsState()
    val checks by FyPlatforms.checklist.collectAsState()
    val now = System.currentTimeMillis()
    val m = DfyMood.decayed(st.mood, now)
    val usable = providers.filter { FyProviders.usable(ctx, it) }
    val live = room.status != "off"

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        FyPageTitle("大肥鱼直播", kicker = "FEIYU LIVE · v0.3", subtitle = "AI 虚拟主播：冲浪、学知识、和弹幕聊天")
        FyCard(padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(FyTokens.tile(), FyTokens.RowShape)) { WhaleAvatar(if (live) room.expression else Expr.forMood(m.mood, m.intensity, m.energy), 84.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("大肥鱼", fontSize = FyType.Title, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(8.dp))
                        StatusChip(if (live) "● 直播中" else "离线", if (live) ChipKind.ERR else ChipKind.NEUTRAL)
                    }
                    Text("心情 ${DfyMood.label(m.mood)} · 活力 ${m.energy.toInt()} · 虚拟角色", fontSize = FyType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (live) "${room.segment.ifBlank { "冲浪" }} · ${room.topic.ifBlank { "想主题中…" }}" else (st.meta.activity.ifBlank { "在家发呆，等你开播" }),
                        fontSize = FyType.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton(if (live) "进入直播间" else "开播", {
                    if (!live) DfyLive.start(ctx, SurfMode.SURF, "", sources.ids())
                    open(DfyRoutes.LIVE)
                }, Modifier.weight(1f))
                FyButton("导播台", { open(DfyRoutes.DIRECTOR) }, Modifier.weight(1f), tonal = true)
            }
        }
        if (live) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FyStat("在线", "${room.online}", Modifier.weight(1f))
                FyStat("网页", "${room.pages}", Modifier.weight(1f))
                FyStat("队列", "${room.queue}", Modifier.weight(1f))
                FyStat("首音", if (room.lastLatencyMs > 0) "%.1fs".format(room.lastLatencyMs / 1000.0) else "—", Modifier.weight(1f), accent = true)
            }
        }
        FySectionHeader("系统检查")
        FyCard(padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            val okProbe = usable.firstNotNullOfOrNull { probes[it.id] }
            Indicator(usable.isNotEmpty() && okProbe?.ok != false, "模型", if (usable.isEmpty()) "没有可用的模型服务" else usable.joinToString("、") { it.name } +
                (okProbe?.let { if (it.ok) " · ${it.latencyMs} ms" else " · 测试失败" } ?: " · 未测试"))
            Indicator(true, "声音", when (voice.engine) { "api" -> "OpenAI 兼容 TTS · ${voice.apiVoice.substringAfter(':')}"; "system" -> "系统 TTS"; else -> "Edge 神经语音 · ${voice.edgeVoice.removePrefix("zh-CN-")}" } + " · 音量 ${voice.volume}")
            val ep = FyPlatforms.endpoint(ctx) != null
            Indicator(if (ep) true else null, "推流", "${platform.label} · " + if (ep) "推流码已保存" else "未填推流码（本机开播不受影响）")
            Indicator(if (sources.ids().isEmpty()) null else true, "弹幕来源", buildList {
                if (sources.sim) add("模拟观众"); if (sources.bili && sources.biliRoom > 0) add("B站 ${sources.biliRoom}"); if (sources.douyinA11y) add("抖音无障碍"); add("手动")
            }.joinToString("、"))
            FyPlatforms.checklist(platform).let { items -> val n = items.indices.count { it in checks }; Indicator(if (n == items.size) true else null, "开播检查", "$n/${items.size} 已确认") }
            Indicator(true, "AI 标识", "「AI 生成」水印始终显示（强制）")
            val takeover = if (platform == LivePlatform.DOUYIN) content.takeoverDouyin else content.takeoverOthers
            Indicator(if (takeover) true else if (platform == LivePlatform.DOUYIN) false else null, "真人接管",
                if (takeover) "开启：每句话由你审核后才说" else if (platform == LivePlatform.DOUYIN) "关闭：抖音要求真人实时驱动，请开启" else "关闭")
        }
        FySectionHeader("直播内容", actionLabel = "调整", onAction = { open("tab/content") })
        FyCard {
            FyRow("预设", preset.ifBlank { "自定义" } + " · 互动 ${content.chatRatio}% 聊天 · 冷场 ${content.coldSeconds}s", onClick = { open("tab/content") }, showDivider = true)
            FyRow("节目单", content.enabledSegments.joinToString(" → ") { "${it.title}${it.minutes}′" }, onClick = { open("tab/content") }, showDivider = true)
            FyRow("画面", when (content.layout) { "fish_big" -> "鱼大 + 网页小"; "chat_only" -> "纯聊天"; else -> "网页大 + 鱼小" } + " · ${content.preset().label}", onClick = { open("tab/content") })
        }
        val recent = st.sessions.takeLast(3).reversed()
        if (recent.isNotEmpty()) {
            FySectionHeader("最近的直播")
            FyCard { recent.forEachIndexed { i, s -> FyRow(s.trail.joinToString(" → ").ifBlank { s.topic }, s.recap.ifBlank { s.endReason }, showDivider = i < recent.size - 1) } }
        }
        FyFootnote("大肥鱼是 AI 生成的虚拟角色。开播只在本机进行；推流到平台需要你自己的推流码，且必须有人在场看护（不支持录播 / 无人直播）。")
    }
}
