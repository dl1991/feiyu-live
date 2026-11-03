package com.dl1991.feiyu.dafeiyu

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFilterChips
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyRow
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySpace
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.StatusChip
import com.dl1991.feiyu.ui.t
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DfyRoutes {
    const val LIVE = "dfy/live"                 // 直播间 (开播台 off-air)
    const val VOICE = "fy/voice"                // app: 人生 & 声音 › 声音
    const val DIRECTOR = "fy/director"          // app: 导播台
    const val PRELIVE = "dfy/prelive"
    const val HOME = "dfy/home"
    const val TIMELINE = "dfy/timeline"
    const val MEMORY = "dfy/memory"
    const val KNOWLEDGE = "dfy/knowledge"
    const val VIEWERS = "dfy/viewers"
    const val CHAT = "dfy/chat"
    const val SETTINGS = "dfy/settings"
    const val PENDING = "dfy/pending"
    const val BILI_LOGIN = "dfy/bili-login"
    const val DEEP_LIVE = "feiyu://app/live"
    const val DEEP_HOME = "feiyu://app/home"
    const val DEEP_CHAT = "feiyu://app/chat"
    const val DEEP_PENDING = "feiyu://app/pending"
    /** Deep link → route (for the app's DeepLinkHandler). */
    fun routeFor(uri: Uri): String? = if (uri.scheme == "feiyu" && uri.host == "app") when (uri.path?.trim('/')) {
        "", null, "live" -> LIVE; "home" -> HOME; "chat" -> CHAT; "pending" -> PENDING; "knowledge" -> KNOWLEDGE; "settings" -> SETTINGS; else -> LIVE
    } else null
}

/** Wires the module's own screens (home, memory, knowledge, chat, settings…) into the app's NavHost. */
fun NavGraphBuilder.dafeiyuGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    val go: (String) -> Unit = { r -> nav.navigate(r) { launchSingleTop = true } }
    // LIVE, PRELIVE (直播接入), VOICE and DIRECTOR are registered by the app's NavHost (MainActivity).
    composable(DfyRoutes.HOME) { DfyHomeScreen(back, go) }
    composable(DfyRoutes.TIMELINE) { DfyTimelineScreen(back) }
    composable(DfyRoutes.MEMORY) { DfyMemoryScreen(back) }
    composable(DfyRoutes.KNOWLEDGE) { DfyKnowledgeScreen(back) }
    composable(DfyRoutes.VIEWERS) { DfyViewersScreen(back) }
    composable(DfyRoutes.CHAT) { DfyChatScreen(back) }
    composable(DfyRoutes.SETTINGS) { DfySettingsScreen(back, go) }
    composable(DfyRoutes.PENDING) { DfyPendingScreen(back) }
    composable(DfyRoutes.BILI_LOGIN) { DfyBiliLoginScreen(back) }
}

private fun fmt(at: Long, p: String = "MM-dd HH:mm") = SimpleDateFormat(p, Locale.getDefault()).format(Date(at))

@Composable
private fun dfyState(): DfyState {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { DfyStore.ensureLoaded(ctx); DfyLife.decay(ctx) }
    val s by DfyStore.state.collectAsState()
    return s
}

// ------------------------------------------------------------------ 家

@Composable
fun DfyHomeScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val st = dfyState()
    val now = System.currentTimeMillis()
    val m = DfyMood.decayed(st.mood, now)
    FyScaffold(t("大肥鱼的家", "DaFeiYu's home"), onBack) {
        FyCard(padding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WhaleAvatar(Expr.forMood(m.mood, m.intensity, m.energy), 88.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(DfyMood.label(m.mood) + " · " + (st.meta.activity.ifBlank { t("在发呆", "idle") }), fontWeight = FontWeight.SemiBold, fontSize = FyType.TitleSmall)
                    Bar(t("活力", "Energy"), m.energy); Bar(t("好奇心", "Curiosity"), m.curiosity); Bar(t("社交意愿", "Sociality"), m.sociality)
                }
            }
            Text(t("上次出门：", "Last outing: ") + (if (st.meta.lastRunAt > 0) fmt(st.meta.lastRunAt) + " · " + st.meta.lastRunResult else t("还没出过门", "never")),
                fontSize = FyType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton(t("去直播间", "Live room"), { onOpen(DfyRoutes.LIVE) })
                FyButton(t("去 B站逛逛", "Browse B站"), { DfyWorker.runNow(ctx) }, tonal = true)
                FyButton(t("聊天", "Chat"), { onOpen(DfyRoutes.CHAT) }, tonal = true)
            }
        }
        FySectionHeader(t("今天", "Today"))
        FyCard(padding = PaddingValues(14.dp)) {
            Text(DfyPersona.todaySummary(st, now).ifBlank { t("今天还没出门", "Nothing yet today") }, fontSize = FyType.Body, lineHeight = FyType.BodyLine)
        }
        val interests = st.interests.map { it to DfyInterests.stageOf(it, now) }.filter { it.second.stage != "insufficient" }
        FySectionHeader(t("兴趣", "Interests"))
        FyCard {
            st.settings.coreInterests.forEach { FyRow(it, t("主人设定的核心兴趣", "Core (set by you)"), showDivider = true, trailing = { StatusChip(t("核心", "core"), ChipKind.ACCENT) }) }
            interests.forEachIndexed { i, (e, s) ->
                FyRow(e.topic, t("90 天内 ${s.count90} 个证据，分布 ${s.days90} 天", "${s.count90} items over ${s.days90} days"), showDivider = i < interests.size - 1,
                    trailing = { StatusChip(s.label, when (s.stage) { "stable" -> ChipKind.OK; "growing" -> ChipKind.ACCENT; "quiet" -> ChipKind.NEUTRAL; else -> ChipKind.WARN }) })
            }
        }
        FySectionHeader(t("更多", "More"))
        FyCard {
            val pend = st.actions.count { it.status == "pending" }
            FyRow(t("时间线", "Timeline"), null, onClick = { onOpen(DfyRoutes.TIMELINE) }, showDivider = true)
            FyRow(t("人生记忆", "Life memory"), "${st.episodes.count { it.stage != MemStage.FORGOTTEN }} " + t("条经历 · ", "episodes · ") + "${st.insights.count { !it.faded }} " + t("条认识", "insights"), onClick = { onOpen(DfyRoutes.MEMORY) }, showDivider = true)
            FyRow(t("知识库", "Knowledge"), null, onClick = { onOpen(DfyRoutes.KNOWLEDGE) }, showDivider = true)
            FyRow(t("待确认的 B站操作", "Pending B站 actions"), if (pend > 0) "$pend" else t("没有", "none"), onClick = { onOpen(DfyRoutes.PENDING) }, showDivider = true)
            FyRow(t("设置", "Settings"), null, onClick = { onOpen(DfyRoutes.SETTINGS) })
        }
    }
}

@Composable
private fun Bar(label: String, v: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(label, Modifier.width(64.dp), fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { (v / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp), color = FyTokens.accent(), trackColor = FyTokens.tile())
    }
}

// ------------------------------------------------------------------ 时间线

@Composable
fun DfyTimelineScreen(onBack: () -> Unit) {
    val st = dfyState()
    var range by remember { mutableStateOf("7") }
    val now = System.currentTimeMillis()
    val from = now - range.toLong() * 86_400_000
    data class Row_(val at: Long, val kind: String, val title: String, val sub: String)
    val rows = remember(st, range) {
        (st.picks.filter { it.at >= from }.map { Row_(it.at, if (it.decision == "watch") "看了" else "划走", it.title, it.note.ifBlank { it.owner }) } +
            st.mood.history.filter { it.at >= from }.map { Row_(it.at, "心情", DfyMood.label(it.mood) + " ×" + it.intensity, it.summary) } +
            st.insights.filter { it.createdAt >= from && !it.core }.map { Row_(it.createdAt, "认识", it.insight, it.behavior) } +
            st.sessions.filter { it.startedAt >= from }.map { Row_(it.startedAt, if (it.mode == SurfMode.LEARN) "学习直播" else "冲浪直播", it.trail.joinToString(" → ").ifBlank { it.topic }, it.recap) } +
            st.live.filter { it.at >= from && it.summary.isNotBlank() }.map { Row_(it.at, "B站直播", it.summary, "${it.rooms.size} 个房间") })
            .sortedByDescending { it.at }.groupBy { DfyStore.today(it.at) }
    }
    FyScaffold(t("时间线", "Timeline"), onBack) {
        FyFilterChips(listOf("7", "30", "90"), range) { range = it }
        FyFootnote(t("这是给你看的完整记录；她自己可能已经记不清其中一些了。", "Your full record — she may no longer remember some of it."))
        if (rows.isEmpty()) FyCard(padding = PaddingValues(16.dp)) { Text(t("这段时间还没有记录", "Nothing in this range")) }
        rows.forEach { (day, list) ->
            FySectionHeader(day)
            FyCard {
                list.take(40).forEachIndexed { i, r -> FyRow(r.title, fmt(r.at, "HH:mm") + " · " + r.sub, showDivider = i < minOf(40, list.size) - 1, trailing = { StatusChip(r.kind, ChipKind.NEUTRAL) }) }
            }
        }
    }
}

// ------------------------------------------------------------------ 人生记忆

@Composable
fun DfyMemoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st = dfyState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("经历") }
    var msg by remember { mutableStateOf("") }
    var core by remember { mutableStateOf("") }
    FyScaffold(t("人生记忆", "Life memory"), onBack) {
        FyFilterChips(listOf("经历", "待反思", "长期认识", "身份设定"), tab) { tab = it }
        when (tab) {
            "经历" -> {
                val eps = st.episodes.filter { it.stage != MemStage.FORGOTTEN }.sortedByDescending { it.at }.take(150)
                FyFootnote(t("经历会从清晰慢慢变模糊直到遗忘；遗忘的细节无法恢复。", "Memories fade from clear to forgotten; forgotten details are gone."))
                FyCard {
                    if (eps.isEmpty()) FyRow(t("还没有经历", "No experiences yet"))
                    eps.forEachIndexed { i, e ->
                        FyRow(e.recallText(), (if (e.stage == MemStage.CLEAR) fmt(e.at) + " · " else "") + e.kind, showDivider = i < eps.size - 1,
                            trailing = { StatusChip(e.stage.zh, when (e.stage) { MemStage.CLEAR -> ChipKind.OK; MemStage.FUZZY -> ChipKind.WARN; else -> ChipKind.NEUTRAL }) },
                            onClick = { scope.launch { DfyStore.update(ctx) { s -> s.copy(episodes = s.episodes.map { x -> if (x.id == e.id) x.copy().also { DfyMemory.fade(it, MemStage.FORGOTTEN) } else x }) } } }, chevron = false)
                    }
                }
                FyFootnote(t("点一条经历可以让她立即忘掉它。", "Tap an experience to make her forget it."))
            }
            "待反思" -> {
                val pool = st.pending.filter { it.status == "pending" }
                FyCard(padding = PaddingValues(14.dp)) {
                    Text(t("待反思 ${pool.size} 条；够数量后她会自己反思，形成长期认识。", "${pool.size} waiting; she reflects when enough accumulate."), fontSize = FyType.Body)
                    Spacer(Modifier.height(8.dp))
                    FyButton(t("现在反思", "Reflect now"), { scope.launch { msg = DfyLife.reflect(ctx, force = true) } }, tonal = true)
                    if (msg.isNotBlank()) Text(msg, fontSize = FyType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                val byId = st.episodes.associateBy { it.id }
                FyCard {
                    st.pending.takeLast(60).reversed().forEachIndexed { i, p ->
                        FyRow(byId[p.episodeId]?.recallText()?.ifBlank { null } ?: t("（已遗忘）", "(forgotten)"), p.note.ifBlank { fmt(p.createdAt) }, showDivider = i < 59,
                            trailing = { StatusChip(when (p.status) { "pending" -> "待反思"; "reflected" -> "已反思"; else -> "已放下" }, if (p.status == "pending") ChipKind.WARN else ChipKind.NEUTRAL) })
                    }
                }
            }
            "长期认识" -> FyCard {
                val ins = st.insights.filter { !it.core }.sortedByDescending { it.createdAt }
                if (ins.isEmpty()) FyRow(t("还没有形成长期认识", "No insights yet"))
                ins.forEachIndexed { i, x -> FyRow(x.insight, x.behavior, showDivider = i < ins.size - 1, trailing = { StatusChip(if (x.faded) "淡忘" else if (x.reinforceCount > 0) "加深×${x.reinforceCount}" else x.kind, if (x.faded) ChipKind.NEUTRAL else ChipKind.OK) }) }
            }
            else -> {
                FyCard(padding = PaddingValues(14.dp)) {
                    Text(t("你写下的身份设定永远不会被遗忘或被自动修改。", "Facts you write here never fade or get auto-edited."), fontSize = FyType.Secondary)
                    Spacer(Modifier.height(8.dp))
                    GlassField(core, { core = it }, t("新的设定", "New fact"), placeholder = "例如：鱼鱼最喜欢的颜色是海蓝色")
                    Spacer(Modifier.height(8.dp))
                    FyButton(t("添加", "Add"), {
                        if (core.isNotBlank()) { val txt = clean(core, 200); core = ""
                            scope.launch { DfyStore.update(ctx) { it.copy(insights = it.insights + Insight("c-" + UUID.randomUUID().toString().take(8), txt, "", "core", 1.0, System.currentTimeMillis(), core = true)) } } }
                    }, tonal = true)
                }
                FyCard {
                    st.insights.filter { it.core }.forEach { x ->
                        FyRow(x.insight, t("点按删除", "tap to delete"), onClick = { scope.launch { DfyStore.update(ctx) { s -> s.copy(insights = s.insights.filter { it.id != x.id }) } } }, chevron = false)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 知识库

@Composable
fun DfyKnowledgeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st = dfyState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var goal by remember { mutableStateOf("") }
    val g = st.knowledge.find { it.id == open }
    FyScaffold(if (g == null) t("知识库", "Knowledge") else g.title, if (g == null) onBack else ({ open = null })) {
        if (g == null) {
            FyCard(padding = PaddingValues(14.dp)) {
                GlassField(goal, { goal = it }, t("建议她学点什么", "Suggest a learning goal"), placeholder = "例如：鲸鱼为什么会唱歌")
                Spacer(Modifier.height(8.dp))
                FyButton(t("加入学习计划", "Add goal"), {
                    if (goal.isNotBlank() && DfySafety.checkIncoming(goal) == DfySafety.Verdict.OK) {
                        val ng = DfyKnowledge.newGoal(goal, "主人建议", "owner"); goal = ""
                        scope.launch { DfyStore.update(ctx) { it.copy(knowledge = it.knowledge + ng) } }
                    }
                }, tonal = true)
                FyFootnote(t("在直播间选「学知识」开播，她会读多个来源、记笔记、自测。", "Go live in Learn mode: she reads sources, takes notes and self-quizzes."))
            }
            FyCard {
                val list = st.knowledge.sortedByDescending { it.updatedAt }
                if (list.isEmpty()) FyRow(t("还没有学习目标", "No goals yet"))
                list.forEachIndexed { i, x ->
                    FyRow(x.title, "${x.notes.size} 条笔记 · ${x.sources.size} 个来源" + (if (x.score >= 0) " · 自测 ${x.score}%" else ""), onClick = { open = x.id }, showDivider = i < list.size - 1,
                        trailing = { StatusChip(if (x.status == "learned") "学会了" else "在学", if (x.status == "learned") ChipKind.OK else ChipKind.WARN) })
                }
            }
        } else {
            if (g.summary.isNotBlank()) { FySectionHeader(t("总结", "Summary")); FyCard(padding = PaddingValues(14.dp)) { Text(g.summary, fontSize = FyType.Body, lineHeight = FyType.BodyLine) } }
            FySectionHeader(t("笔记（带出处）", "Notes with sources"))
            FyCard { g.notes.forEachIndexed { i, n -> FyRow(n.text, n.sourceTitle.ifBlank { n.sourceUrl }, showDivider = i < g.notes.size - 1,
                onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.sourceUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) } }
            if (g.quiz.isNotEmpty()) {
                FySectionHeader(t("自测", "Self-quiz"))
                FyCard { g.quiz.forEachIndexed { i, q -> FyRow(q.q, "她答：${q.herAnswer.ifBlank { "（没答上）" }} · 参考：${q.expected}", showDivider = i < g.quiz.size - 1,
                    trailing = { StatusChip(if (q.correct) "对" else "错", if (q.correct) ChipKind.OK else ChipKind.ERR) }) } }
            }
            FySectionHeader(t("来源", "Sources"))
            FyCard { g.sources.forEachIndexed { i, s -> FyRow(s.title.ifBlank { s.url }, s.url, showDivider = i < g.sources.size - 1) } }
        }
    }
}

// ------------------------------------------------------------------ 观众

@Composable
fun DfyViewersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st = dfyState()
    val scope = rememberCoroutineScope()
    FyScaffold(t("观众", "Viewers"), onBack) {
        FyFootnote(t("只记昵称、来过几次和 TA 在直播间公开说过的小事。点按可屏蔽/解除。", "Only nicknames, visit counts and small public facts. Tap to block/unblock."))
        val list = st.viewers.filter { !it.key.startsWith("test:") }.sortedWith(compareByDescending<ViewerProfile> { it.streams }.thenByDescending { it.lastSeen }).take(300)
        FyCard {
            if (list.isEmpty()) FyRow(t("还没有观众", "No viewers yet"))
            list.forEachIndexed { i, v ->
                FyRow(v.nick, "来过 ${v.streams} 场 · ${v.messages} 条弹幕" + (if (v.facts.isNotEmpty()) " · " + v.facts.joinToString("、") else ""), showDivider = i < list.size - 1,
                    trailing = { StatusChip(if (v.blocked) "已屏蔽" else v.level, if (v.blocked) ChipKind.ERR else ChipKind.NEUTRAL) },
                    onClick = { scope.launch { DfyStore.update(ctx) { s -> s.copy(viewers = s.viewers.map { if (it.key == v.key) it.copy(blocked = !it.blocked) else it }) } } }, chevron = false)
            }
        }
    }
}

// ------------------------------------------------------------------ 私聊

@Composable
fun DfyChatScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st = dfyState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    FyScaffold(t("和大肥鱼聊天", "Chat with DaFeiYu"), onBack, actions = {
        Text(t("清空", "Clear"), Modifier.clickable { scope.launch { DfyChat.clear(ctx) } }.padding(8.dp), color = FyTokens.accent(), fontSize = FyType.Section)
    }) {
        FyCard(padding = PaddingValues(12.dp)) {
            if (st.chat.isEmpty()) Text(t("跟她说点什么吧。她记得的事会自然带进来，忘了的不会硬编。", "Say hi. She brings in what she still remembers."), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = FyType.Secondary)
            st.chat.takeLast(40).forEach { c ->
                Text((if (c.role == "user") st.settings.ownerName else "大肥鱼") + "：" + c.text, fontSize = FyType.Body, lineHeight = FyType.BodyLine,
                    color = if (c.role == "user") FyTokens.accent() else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 3.dp))
            }
            if (busy) Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = FySpace.Screen)) {
            GlassField(input, { input = it }, t("消息", "Message"), singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            FyButton(t("发送", "Send"), { val x = input; input = ""; busy = true; scope.launch { DfyChat.send(ctx, x); busy = false } }, enabled = !busy && input.isNotBlank())
        }
    }
}

// ------------------------------------------------------------------ 待确认 B站操作

@Composable
fun DfyPendingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st = dfyState()
    val scope = rememberCoroutineScope()
    FyScaffold(t("待确认的 B站操作", "Pending B站 actions"), onBack) {
        if (st.meta.writeCooldownUntil > System.currentTimeMillis()) FyFootnote("⚠ B站要求验证/风控，所有对外操作暂停到 ${fmt(st.meta.writeCooldownUntil)}：${st.meta.writeCooldownReason}")
        val pend = st.actions.filter { it.status == "pending" }.reversed()
        FyCard {
            if (pend.isEmpty()) FyRow(t("没有待确认的操作", "Nothing pending"))
            pend.forEach { a ->
                Column(Modifier.padding(14.dp)) {
                    Text("${BiliActions.label(a.type)} · ${a.targetLabel}", fontWeight = FontWeight.SemiBold, fontSize = FyType.Body)
                    if (a.content.isNotBlank()) Text("「${a.content}」", fontSize = FyType.Body)
                    if (a.reason.isNotBlank()) Text(a.reason, fontSize = FyType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FyButton(t("允许", "Allow"), { scope.launch { BiliActions.execute(ctx, a.id) } })
                        FyButton(t("拒绝", "Reject"), { scope.launch { BiliActions.reject(ctx, a.id) } }, tonal = true)
                    }
                }
            }
        }
        FySectionHeader(t("最近记录", "Recent"))
        FyCard {
            st.actions.filter { it.status != "pending" }.takeLast(30).reversed().forEach { a ->
                FyRow("${BiliActions.label(a.type)} · ${a.targetLabel}", a.resultMsg.ifBlank { a.status }, trailing = { StatusChip(a.status, when (a.status) { "done" -> ChipKind.OK; "failed", "blocked" -> ChipKind.ERR; else -> ChipKind.NEUTRAL }) })
            }
        }
    }
}

// ------------------------------------------------------------------ B站登录（用户自己登录）

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DfyBiliLoginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var done by remember { mutableStateOf(BiliAuth.isLoggedIn(ctx)) }
    FyScaffold(t("登录 B站（你自己登录）", "Sign in to B站 yourself"), onBack, scroll = false) {
        FyFootnote(t("在下面的官方页面里自己登录（验证码、短信都由你完成，本应用不会代填或破解）。登录后只在本机加密保存 Cookie，用于你开启的读/写功能。",
            "Sign in on the official page below. Verification is done by you. The cookie stays encrypted on this device."))
        if (done) FyCard(padding = PaddingValues(14.dp)) {
            Text(t("已登录 ✓ UID ", "Signed in ✓ UID ") + (BiliAuth.uid(ctx) ?: ""), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FyButton(t("退出登录并删除 Cookie", "Sign out & delete cookie"), { BiliAuth.logout(ctx); done = false }, tonal = true)
        } else AndroidView(factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { if (BiliAuth.captureFromWebView(ctx)) done = true }
                }
                loadUrl("https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F")
            }
        }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = FySpace.Screen))
    }
}
