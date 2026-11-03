package com.dl1991.feiyu.dafeiyu

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFilterChips
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyRow
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySwitch
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.StatusChip
import com.dl1991.feiyu.ui.t
import kotlinx.coroutines.delay

/** Seconds since composition, ticking every frame (avatar animation). */
@Composable
internal fun animTime(): Double {
    val t by produceState(0.0) { val start = System.nanoTime(); while (true) withFrameNanos { value = (it - start) / 1e9 } }
    return t
}

@Composable
internal fun WhaleAvatar(expr: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val t = animTime()
    val level by DfySpeaker.level.collectAsState()
    Canvas(modifier.size(size)) {
        drawIntoCanvas { c -> DfyAvatar.draw(c.nativeCanvas, this.size.width / 2, this.size.height / 2 + this.size.height * 0.05f, this.size.width * 0.78f, expr, t, level) }
    }
}

/**
 * 直播间 — the headline screen of 大肥鱼人生. Off-air it is the 开播台 (mode, topic, comment
 * sources, voice); on-air it is the room: her browser view + topic trail, the avatar with
 * subtitles, the chat/event stream, and an input to talk to her as a viewer.
 */
@Composable
fun DfyLiveRoomScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val room by DfyLive.room.collectAsState()
    LaunchedEffect(Unit) { DfyStore.ensureLoaded(ctx) }
    if (room.status == "off") OffAir(ctx, room, onBack, onOpen) else OnAir(ctx, room, onBack, onOpen)
}

@Composable
private fun OffAir(ctx: android.content.Context, room: RoomState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val st by DfyStore.state.collectAsState()
    var mode by remember { mutableStateOf(SurfMode.SURF) }
    var topic by remember { mutableStateOf("") }
    val src by FyPlatforms.sources.collectAsState()
    LaunchedEffect(Unit) { FyPlatforms.ensure(ctx) }
    var engine by remember { mutableStateOf(DfyTtsRouter.preferred(ctx)) }
    val now = System.currentTimeMillis()
    val m = DfyMood.decayed(st.mood, now)
    FyScaffold(t("大肥鱼 · 直播间", "DaFeiYu · Live"), onBack, actions = {
        Text(t("设置", "Settings"), Modifier.clickable { onOpen(DfyRoutes.SETTINGS) }.padding(8.dp), color = FyTokens.accent(), fontSize = FyType.Section)
    }) {
        FyCard(padding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WhaleAvatar(Expr.forMood(m.mood, m.intensity, m.energy), 96.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("大肥鱼", fontSize = FyType.Title, fontWeight = FontWeight.SemiBold)
                    Text(t("虚拟角色 · 心情 ", "Virtual character · mood ") + DfyMood.label(m.mood) + " · " + t("活力 ", "energy ") + m.energy.toInt(),
                        fontSize = FyType.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (room.recap.isNotBlank()) Text(t("上次直播：", "Last stream: ") + room.recap, fontSize = FyType.Secondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        FySectionHeader(t("开播", "Go live"))
        FyCard(padding = PaddingValues(14.dp)) {
            val surfLabel = t("带观众冲浪", "Surf")
            val learnLabel = t("学知识", "Learn")
            FyFilterChips(listOf(surfLabel, learnLabel), if (mode == SurfMode.SURF) surfLabel else learnLabel) {
                mode = if (it == learnLabel) SurfMode.LEARN else SurfMode.SURF
            }
            Spacer(Modifier.height(10.dp))
            val goals = st.knowledge.filter { it.status == "learning" && it.notes.isEmpty() }
            GlassField(topic, { topic = it }, if (mode == SurfMode.LEARN) t("学习目标", "Learning goal") else t("主题", "Topic"),
                placeholder = if (mode == SurfMode.LEARN && goals.isNotEmpty()) goals.first().title else t("留空 = 她按自己的好奇心决定", "Blank = she decides"))
            Spacer(Modifier.height(10.dp))
            ToggleLine(t("模拟观众（本地测试，不联网）", "Simulated viewers (local test)"), src.sim) { FyPlatforms.setSources(ctx, src.copy(sim = it)) }
            ToggleLine(t("读取抖音 App 评论（需开启本应用无障碍）", "Read Douyin app comments (accessibility)"), src.douyinA11y) { FyPlatforms.setSources(ctx, src.copy(douyinA11y = it)) }
            ToggleLine(t("B站直播间弹幕", "Bilibili danmaku") + if (src.biliRoom > 0) " · ${src.biliRoom}" else t("（先在直播接入填房间号）", ""), src.bili && src.biliRoom > 0, enabled = src.biliRoom > 0) { FyPlatforms.setSources(ctx, src.copy(bili = it)) }
            Spacer(Modifier.height(6.dp))
            Text(t("语音", "Voice"), fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FyFilterChips(listOf("edge", "system", "api"), engine) { engine = it; DfyTtsRouter.setPreferred(ctx, it) }
            Spacer(Modifier.height(12.dp))
            FyButton(t("开播", "Go live"), {
                val tp = topic.ifBlank { if (mode == SurfMode.LEARN) goals.firstOrNull()?.title.orEmpty() else "" }
                DfyLive.start(ctx, mode, tp, src.ids())
            }, Modifier.fillMaxWidth())
        }
        FyFootnote(t("开播只在本机进行：她会打开网页、说话、和弹幕互动。推流到直播平台是另外一步（直播接入），需要你自己的推流码。没有录播模式。",
            "Going live runs locally. Pushing to Douyin is a separate step with your own stream key. There is no replay mode."))
        FySectionHeader(t("她的地方", "Her places"))
        FyCard {
            FyRow(t("直播接入", "Platforms"), t("平台推流码 · 弹幕来源 · 开播前检查 · 预览", "Stream keys · comment sources · checklist"), onClick = { onOpen(DfyRoutes.PRELIVE) }, showDivider = true)
            FyRow(t("导播台", "Director"), t("节目单、话题卡、暂停画面…", "Run-sheet, topic cards, standby"), onClick = { onOpen(DfyRoutes.DIRECTOR) }, showDivider = true)
            FyRow(t("大肥鱼的家", "Her home"), t("心情、兴趣、今天做了什么", "Mood, interests, today"), onClick = { onOpen(DfyRoutes.HOME) }, showDivider = true)
            FyRow(t("知识库", "Knowledge"), "${st.knowledge.size} " + t("个学习目标", "goals"), onClick = { onOpen(DfyRoutes.KNOWLEDGE) }, showDivider = true)
            FyRow(t("时间线", "Timeline"), null, onClick = { onOpen(DfyRoutes.TIMELINE) }, showDivider = true)
            FyRow(t("人生记忆", "Life memory"), null, onClick = { onOpen(DfyRoutes.MEMORY) }, showDivider = true)
            FyRow(t("观众", "Viewers"), "${st.viewers.size}", onClick = { onOpen(DfyRoutes.VIEWERS) }, showDivider = true)
            FyRow(t("和她私聊", "Chat with her"), null, onClick = { onOpen(DfyRoutes.CHAT) })
        }
        val recent = st.sessions.takeLast(3).reversed()
        if (recent.isNotEmpty()) {
            FySectionHeader(t("最近的直播", "Recent streams"))
            FyCard {
                recent.forEachIndexed { i, s ->
                    FyRow(s.trail.joinToString(" → ").ifBlank { s.topic }, s.recap.ifBlank { s.endReason }, showDivider = i < recent.size - 1)
                }
            }
        }
    }
}

@Composable
internal fun ToggleLine(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = FyType.Body, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        FySwitch(on, if (enabled) onChange else null, enabled)
    }
}

@Composable
private fun BrowserPane(modifier: Modifier) {
    val ctx = LocalContext.current
    val web by produceState<android.webkit.WebView?>(DfyBrowser.webView) { value = DfyBrowser.ensure(ctx) }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(FyTokens.card()).border(1.dp, FyTokens.hairline(), RoundedCornerShape(16.dp))) {
        val w = web
        if (w != null) AndroidView(factory = { (w.parent as? ViewGroup)?.removeView(w); w }, modifier = Modifier.fillMaxSize(),
            onRelease = { v -> (v.parent as? ViewGroup)?.removeView(v) })
        else Text(t("正在准备浏览器…", "Preparing browser…"), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OnAir(ctx: android.content.Context, room: RoomState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val stream by DfyRtmp.status.collectAsState()
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val elapsed by produceState(0L, room.startedAt) { while (true) { value = (System.currentTimeMillis() - room.startedAt) / 1000; delay(1000) } }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (room.status == "live") Color(0xFFEC4B4B) else FyTokens.warnColor()))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text((room.segment.ifBlank { if (room.mode == SurfMode.LEARN) "学知识" else "冲浪" }) + " · " + room.topic.ifBlank { "想主题中…" }, fontWeight = FontWeight.SemiBold, fontSize = FyType.TitleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%02d:%02d".format(elapsed / 60, elapsed % 60) + " · 在线 ${room.online} · 页 ${room.pages} · 首音 ${if (room.lastLatencyMs > 0) "%.1fs".format(room.lastLatencyMs / 1000.0) else "—"}" +
                    (if (stream.state == "live") " · 推流 ${stream.bitrateKbps}kbps" else if (stream.state == "reconnecting") " · 重连中" else ""),
                    fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            StatusChip("AI 生成内容", ChipKind.ACCENT)
            Spacer(Modifier.width(6.dp))
            FyButton(t("导播台", "Director"), { onOpen(DfyRoutes.DIRECTOR) }, tonal = true)
            Spacer(Modifier.width(6.dp))
            FyButton(if (room.status == "ending") t("下播中…", "Ending…") else t("下播", "End"), { DfyLive.stop(ctx) }, enabled = room.status == "live", tonal = true)
        }
        // trail
        if (room.trail.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            room.trail.takeLast(8).forEachIndexed { i, s ->
                Text((if (i > 0) "› " else "") + s, fontSize = FyType.Caption, color = if (i == room.trail.takeLast(8).lastIndex) FyTokens.accent() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // browser
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BrowserPane(Modifier.fillMaxSize())
            val overlay = room.poll?.let { p -> "投票 · ${p.question}  " + p.options.mapIndexed { i, o -> "${i + 1}.$o ${p.tally()[i]}" }.joinToString("  ") }
                ?: room.quiz?.let { "答题 · ${it.question}" } ?: room.guess?.let { "猜谜 · ${it.hint.ifBlank { "猜猜看" }}" }
            if (overlay != null) Text(overlay, Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(FyTokens.accent()).padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onPrimary, fontSize = FyType.Secondary, maxLines = 2)
            val dir by FyDirector.state.collectAsState()
            Column(Modifier.align(Alignment.TopCenter).padding(top = if (overlay != null) 48.dp else 8.dp, start = 8.dp, end = 8.dp)) {
                if (dir.topicCard.isNotBlank()) Text("话题卡 · " + dir.topicCard, Modifier.clip(RoundedCornerShape(6.dp)).background(FyTokens.accent()).padding(horizontal = 10.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.onPrimary, fontSize = FyType.Secondary, maxLines = 1)
                dir.pinned?.let { pe -> Text("置顶 · ${pe.nick}：${pe.text}", Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF2B134)).clickable { FyDirector.unpin() }.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color(0xFF1A1406), fontSize = FyType.Secondary, maxLines = 2) }
            }
            if (dir.standby) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xF0121315)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WhaleAvatar(Expr.SLEEPY, 110.dp)
                    Text(dir.standbyText, color = Color.White, fontSize = FyType.Title, fontWeight = FontWeight.Bold)
                    Text("直播画面已暂停 · 观众看到的是待机卡", color = Color(0xFFB0B3B8), fontSize = FyType.Caption)
                }
            }
            if (room.note.isNotBlank()) Text(room.note, Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(FyTokens.card()).padding(6.dp), fontSize = FyType.Caption)
        }
        // avatar + subtitle
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(FyTokens.card())) { WhaleAvatar(room.expression, 84.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(room.subtitle.ifBlank { if (room.thinking) "嗯…" else " " }, fontSize = FyType.Body, lineHeight = FyType.BodyLine, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("${Expr.label(room.expression)} · ${room.moodLabel} · ${DfyTtsRouter.lastEngine.ifBlank { room.ttsEngine }} · 队列 ${room.queue}",
                    fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // chat stream: her bubbles + viewer events, newest last
        val feed = remember(room.bubbles, room.ticker) {
            (room.bubbles.map { Triple(it.at, ("鱼" to it), null as LiveEvent?) } + room.ticker.map { Triple(it.at, ("观" to Bubble(it.at, "viewer", evText(it))), it) }).sortedBy { it.first }.takeLast(60)
        }
        val list = rememberLazyListState()
        LaunchedEffect(feed.size) { if (feed.isNotEmpty()) list.animateScrollToItem(feed.size - 1) }
        LazyColumn(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(feed) { (_, p, ev) ->
                val (who, b) = p
                val mine = who == "鱼"
                Text(
                    modifier = if (ev != null && ev.type == EvType.COMMENT) Modifier.clickable { FyDirector.pin(ev) } else Modifier,
                    text = 
                    (if (mine) when (b.kind) { "note" -> "📒 "; "quiz" -> ""; "system" -> "· "; else -> "大肥鱼：" } else "") + b.text,
                    fontSize = FyType.Secondary, lineHeight = FyType.SecondaryLine,
                    color = when { b.kind == "system" -> MaterialTheme.colorScheme.onSurfaceVariant; mine -> MaterialTheme.colorScheme.onSurface; else -> FyTokens.accent() },
                )
            }
        }
        // test events (simulated audience controls)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TestChip("+新观众") { DfyEventBus.emit(LiveEvent(EvType.ENTER, "test:" + (100..999).random(), "测试观众" + (1..99).random(), source = "manual")) }
            TestChip("+点赞") { DfyEventBus.emit(LiveEvent(EvType.LIKE, "test:like", "点赞机", giftCount = 25, source = "manual")) }
            TestChip("+关注") { DfyEventBus.emit(LiveEvent(EvType.FOLLOW, "test:fol", "新粉丝", source = "manual")) }
            TestChip("+礼物") { DfyEventBus.emit(LiveEvent(EvType.GIFT, "test:gift", "土豪鲸", giftName = "嘉年华", giftCount = 1, giftValue = 3000, source = "manual")) }
            TestChip("投票") { DfyLive.inject("发起个投票吧：看游戏还是看科技") }
            TestChip("考考我们") { DfyLive.inject("考考我们今天学的") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GlassField(input, { input = it }, t("以观众身份发弹幕（你是「主人」）", "Say something as a viewer (you are 主人)"), placeholder = t("带我们去看…/搜一下…", "Take us to… / search…")) }
            Spacer(Modifier.width(8.dp))
            FyButton(t("发送", "Send"), { if (input.isNotBlank()) { DfyLive.inject(input); input = "" } })
        }
    }
}

private fun evText(e: LiveEvent): String = when (e.type) {
    EvType.ENTER -> "${e.nick} 来了"
    EvType.LIKE -> "${e.nick} 点赞 ×${e.giftCount}"
    EvType.FOLLOW -> "${e.nick} 关注了"
    EvType.GIFT -> "${e.nick} 送出 ${e.giftName}×${e.giftCount}"
    else -> "${e.nick}：${e.text}"
}

@Composable
private fun TestChip(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(FyTokens.card()).border(1.dp, FyTokens.hairline(), RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = FyType.Caption)
}

