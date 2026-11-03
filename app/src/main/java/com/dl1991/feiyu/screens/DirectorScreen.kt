package com.dl1991.feiyu.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.dafeiyu.ContentSettings
import com.dl1991.feiyu.dafeiyu.DfyLive
import com.dl1991.feiyu.dafeiyu.DfyRoutes
import com.dl1991.feiyu.dafeiyu.EvType
import com.dl1991.feiyu.dafeiyu.FyContentStore
import com.dl1991.feiyu.dafeiyu.FyDirector
import com.dl1991.feiyu.dafeiyu.FyHumanVoice
import com.dl1991.feiyu.dafeiyu.FyPlatforms
import com.dl1991.feiyu.dafeiyu.FyTakeover
import com.dl1991.feiyu.dafeiyu.LivePlatform
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFilterChips
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySlider
import com.dl1991.feiyu.ui.FySpacer
import com.dl1991.feiyu.ui.FyStat
import com.dl1991.feiyu.ui.FyToggle
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.StatusChip
import kotlinx.coroutines.delay

private val REPLY_D = listOf("short" to "简短", "medium" to "适中", "long" to "展开")

/** 导播台: live overrides during a stream. Toggles apply at once; spoken actions queue before the next comment. */
@Composable
fun DirectorScreen(back: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FyContentStore.ensure(ctx) }
    val dir by FyDirector.state.collectAsState()
    val room by DfyLive.room.collectAsState()
    val c by FyContentStore.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    var topic by remember { mutableStateOf("") }
    var card by remember { mutableStateOf("") }
    var standbyText by remember(dir.standbyText) { mutableStateOf(dir.standbyText) }
    val live = room.status == "live"
    LaunchedEffect(Unit) { FyPlatforms.ensure(ctx) }
    val platform by FyPlatforms.selected.collectAsState()
    val drafts by FyTakeover.drafts.collectAsState()
    val autoApprove by FyTakeover.autoApprove.collectAsState()
    val talking by FyHumanVoice.talking.collectAsState()
    val takeover = if (platform == LivePlatform.DOUYIN) c.takeoverDouyin else c.takeoverOthers
    var editing by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }
    var micDenied by remember { mutableStateOf(false) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micDenied = !ok }

    fun set(f: (ContentSettings) -> ContentSettings) = FyContentStore.update(ctx, f)

    FyScaffold("导播台", back) {
        // ---- status
        FyCard(padding = PaddingValues(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyStat("状态", if (live) "直播中" else "未开播", Modifier.weight(1f), accent = live)
                FyStat("在线", room.online.toString(), Modifier.weight(1f))
                FyStat("待回", room.queue.toString(), Modifier.weight(1f))
            }
            val seg = FyDirector.segment(c, dir)
            val left = FyDirector.secondsLeft(c, now)
            Text("环节：${seg.title} · " + if (left >= 0) "剩 ${left / 60}:${"%02d".format(left % 60)}" else "已超时 ${-left / 60} 分",
                Modifier.padding(top = 8.dp), fontSize = FyType.Secondary)
            if (room.pageTitle.isNotBlank()) Text("网页：${room.pageTitle}", fontSize = FyType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (dir.lastAction.isNotBlank()) Text("上一步：${dir.lastAction}", fontSize = FyType.Caption)
            if (!live) { FySpacer(8.dp); FyButton("去直播间开播", { open(DfyRoutes.LIVE) }, tonal = true) }
        }

        // ---- 她在想 · 直播专家组
        CrewPanel(open)

        // ---- 真人接管
        DriveBanner(takeover, autoApprove, talking)
        FySectionHeader("真人接管 · ${platform.label}")
        FyCard(padding = PaddingValues(12.dp)) {
            FyToggle("真人接管模式", takeover, sub = "她只起草，你点「说」她才说（冷场补位、冲浪解说也要审核）") { FyTakeover.setEnabled(ctx, it) }
            FyToggle("自动通过（紧急开关）", autoApprove, enabled = takeover,
                sub = if (autoApprove) "已开启：草稿不经审核直接说出 —— 抖音不允许无人驱动，用完请关闭" else "关闭：每句都等你审核（推荐）") { FyTakeover.setAutoApprove(it) }
            FySpacer(8.dp)
            PushToTalk(talking) { pressed ->
                if (pressed) {
                    if (!FyHumanVoice.hasPermission(ctx)) { micPerm.launch(Manifest.permission.RECORD_AUDIO); false }
                    else FyHumanVoice.start(ctx)
                } else { FyHumanVoice.stop(); true }
            }
            Text(if (micDenied) "需要麦克风权限才能用「真人说话」。" else "按住说话：麦克风声音直接进直播，鱼鱼的嘴跟着你的音量动，AI 暂停；松开恢复。",
                Modifier.padding(top = 6.dp), fontSize = FyType.Caption, color = if (micDenied) FyTokens.warnColor() else Color.Unspecified)
        }

        if (takeover) {
            FySectionHeader("待审回复 · ${drafts.size}")
            FyCard(padding = PaddingValues(12.dp)) {
                if (drafts.isEmpty()) Text(if (live) "暂时没有草稿。有弹幕或冷场时，她起草的话会出现在这里。" else "开播后，她起草的每句话会先出现在这里，等你决定。", fontSize = FyType.Secondary)
                drafts.forEachIndexed { i, d ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (d.urgent) StatusChip("紧急", ChipKind.ERR)
                            Text(d.source, Modifier.weight(1f), fontSize = FyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("→ ${d.text}", Modifier.padding(vertical = 4.dp), fontSize = FyType.Body)
                        if (editing == d.id) {
                            GlassField(editText, { editText = it.take(300) }, "改后说", singleLine = false, minLines = 2)
                            FySpacer(6.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FyButton("说出修改", { FyTakeover.approve(d.id, editText); editing = null }, enabled = editText.isNotBlank())
                                FyButton("取消", { editing = null }, tonal = true)
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FyButton("说", { FyTakeover.approve(d.id, null) })
                            FyButton("改后说", { editing = d.id; editText = d.text }, tonal = true)
                            FyButton("跳过", { FyTakeover.skip(d.id) }, tonal = true)
                        }
                    }
                    if (i < drafts.lastIndex) Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 1.dp).background(FyTokens.hairline()))
                }
            }
            FyFootnote("最多积压 ${FyTakeover.MAX_PENDING} 条，满了她会先停下不再起草。跳过的回复不会执行其中的换页 / 投票等动作。")
        }

        // ---- voice / picture toggles
        FySectionHeader("声音与画面")
        FyCard(padding = PaddingValues(12.dp)) {
            FyToggle("静音", dir.muted, sub = "她继续说，观众听不到") { FyDirector.setMuted(it) }
            FyToggle("暂停说话", dir.voicePaused, sub = "立即打断，弹幕继续排队") { FyDirector.setVoicePaused(it) }
            FyToggle("待机卡（暂停直播画面）", dir.standby, sub = "画面显示待机卡，不再发言") { FyDirector.setStandby(it, standbyText) }
            GlassField(standbyText, { standbyText = it.take(40) }, "待机卡文字")
            if (dir.standby) { FySpacer(6.dp); FyButton("更新待机卡", { FyDirector.setStandby(true, standbyText) }, tonal = true) }
        }

        // ---- topic
        FySectionHeader("话题")
        FyCard(padding = PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(topic, { topic = it.take(30) }, "换话题", Modifier.weight(1f), placeholder = room.topic.ifBlank { "例如：深海里的发光生物" })
                FyButton("换", { FyDirector.changeTopic(topic); topic = "" }, enabled = topic.isNotBlank() && live)
            }
            FySpacer(8.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(card, { card = it.take(40) }, "推送话题卡", Modifier.weight(1f), placeholder = "屏幕上显示一个问题")
                FyButton("推送", { FyDirector.pushTopicCard(card); card = "" }, enabled = card.isNotBlank(), tonal = true)
            }
            if (dir.topicCard.isNotBlank()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("话题卡", ChipKind.ACCENT)
                Text(dir.topicCard, Modifier.weight(1f), fontSize = FyType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("撤下", Modifier.clickable { FyDirector.clearTopicCard() }, fontSize = FyType.Secondary)
            }
            FySpacer(8.dp)
            FyButton("跳过当前网页", { FyDirector.skipPage() }, enabled = live, tonal = true)
        }

        // ---- segments
        FySectionHeader("切换环节")
        FyCard(padding = PaddingValues(12.dp)) {
            val segs = c.enabledSegments
            val labels = segs.mapIndexed { i, s -> "${i + 1}. ${s.title}" }
            val cur = labels.getOrNull(dir.segIndex.mod(segs.size)) ?: ""
            FyFilterChips(labels, cur) { l -> labels.indexOf(l).takeIf { it >= 0 }?.let { FyDirector.switchSegment(it) } }
            FyToggle("到时自动切换", c.autoRotate) { on -> set { it.copy(autoRotate = on) } }
        }

        // ---- live sliders
        FySectionHeader("实时调整")
        FyCard(padding = PaddingValues(12.dp)) {
            FySlider("互动比例", c.chatRatio, left = "多冲浪", right = "多聊天") { v -> set { it.copy(chatRatio = v) } }
            FySlider("活泼", c.lively) { v -> set { it.copy(lively = v) } }
            FySlider("毒舌", c.snark) { v -> set { it.copy(snark = v) } }
            FySlider("情绪表达", c.emotionIntensity) { v -> set { it.copy(emotionIntensity = v) } }
            FySlider("冷场阈值", c.coldSeconds, 3..30, suffix = " 秒") { v -> set { it.copy(coldSeconds = v) } }
            GroupLabel("回复长度")
            FyFilterChips(REPLY_D.map { it.second }, REPLY_D.firstOrNull { it.first == c.replyLength }?.second ?: "适中") { l ->
                REPLY_D.firstOrNull { it.second == l }?.let { r -> set { it.copy(replyLength = r.first) } }
            }
        }
        FyFootnote("这些改动直接写入「直播内容」，下一句生效。")

        // ---- pin a comment
        FySectionHeader("置顶弹幕")
        FyCard(padding = PaddingValues(12.dp)) {
            dir.pinned?.let { p ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("已置顶", ChipKind.OK)
                    Text("${p.nick}：${p.text}", Modifier.weight(1f), fontSize = FyType.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("取消", Modifier.clickable { FyDirector.unpin() }, fontSize = FyType.Secondary)
                }
            }
            val comments = room.ticker.filter { it.type == EvType.COMMENT }.takeLast(8).reversed()
            if (comments.isEmpty()) Text(if (live) "还没有弹幕。" else "开播后，最近的弹幕会出现在这里，点一下置顶。", fontSize = FyType.Secondary)
            comments.forEach { e ->
                Text(
                    "${e.nick}：${e.text}",
                    Modifier.fillMaxWidth().clickable { FyDirector.pin(e) }.padding(vertical = 6.dp),
                    fontSize = FyType.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (dir.pinned == e) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 「真人驱动中」 indicator at the top of the 导播台. */
@Composable
private fun DriveBanner(takeover: Boolean, autoApprove: Boolean, talking: Boolean) {
    val (text, color) = when {
        talking -> "真人说话中 · 麦克风直通直播" to FyTokens.errColor()
        takeover && !autoApprove -> "真人驱动中 · 每句话由你审核" to FyTokens.okColor()
        takeover -> "自动通过中 · AI 正在自动说话" to FyTokens.warnColor()
        else -> "真人接管已关闭 · AI 自动说话" to FyTokens.warnColor()
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
            Text(text, fontSize = FyType.Body, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** Hold-to-talk button. [onPress] gets true on press (return false if the mic could not start) and false on release. */
@Composable
private fun PushToTalk(talking: Boolean, onPress: (Boolean) -> Boolean) {
    val bg = if (talking) FyTokens.errColor() else FyTokens.accent()
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (onPress(true)) {
                        tryAwaitRelease()
                        onPress(false)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (talking) "松开 · 结束真人说话" else "按住 · 真人说话", color = Color.White, fontSize = FyType.Title, fontWeight = FontWeight.Bold)
    }
}
