package com.dl1991.feiyu.screens

import androidx.compose.foundation.clickable
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
