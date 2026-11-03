package com.dl1991.feiyu.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.Routes
import com.dl1991.feiyu.dafeiyu.ContentSettings
import com.dl1991.feiyu.dafeiyu.DfyRoutes
import com.dl1991.feiyu.dafeiyu.FyContentStore
import com.dl1991.feiyu.dafeiyu.SegKind
import com.dl1991.feiyu.dafeiyu.StreamPreset
import com.dl1991.feiyu.dafeiyu.Segment
import com.dl1991.feiyu.dafeiyu.TopicWeight
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

private val REPLY = listOf("short" to "简短", "medium" to "适中", "long" to "展开")
private val LAYOUTS = listOf("web_big" to "网页大屏", "fish_big" to "鱼鱼大屏", "chat_only" to "纯聊天")

/** C. 「直播内容」: 节目单, topics & sources, interaction, 画面, 安全, presets. Applies to the next sentence even mid-stream. */
@Composable
fun ContentScreen(back: (() -> Unit)?, open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FyContentStore.ensure(ctx) }
    val c by FyContentStore.state.collectAsState()
    val active by FyContentStore.activePreset.collectAsState()
    val userPresets by FyContentStore.presets.collectAsState()
    var presetName by remember { mutableStateOf("") }
    var newTopic by remember { mutableStateOf("") }

    fun set(f: (ContentSettings) -> ContentSettings) = FyContentStore.update(ctx, f)
    fun setSeg(i: Int, f: (Segment) -> Segment) = set { s -> s.copy(segments = s.segments.mapIndexed { j, g -> if (j == i) f(g) else g }) }

    FyScaffold("直播内容", back) {
      // key(active): list fields keep typed text locally, so re-create them when a preset loads.
      key(active) {
        FySectionHeader("预设")
        FyCard(padding = PaddingValues(12.dp)) {
            val names = (ContentSettings.BUILTIN.keys + userPresets.keys).toList()
            FyFilterChips(names, active) { FyContentStore.loadPreset(ctx, it) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(presetName, { presetName = it.take(16) }, "保存当前为预设", Modifier.weight(1f), placeholder = "例如：周末夜聊")
                FyButton("保存", { FyContentStore.savePreset(ctx, presetName); presetName = "" }, enabled = presetName.isNotBlank(), tonal = true)
            }
            if (active.isNotBlank() && active in userPresets) {
                Text("删除预设「$active」", Modifier.padding(top = 8.dp).clickable { FyContentStore.deletePreset(ctx, active) }, fontSize = FyType.Secondary)
            }
        }

        FySectionHeader("节目单", actionLabel = "导播台", onAction = { open(DfyRoutes.DIRECTOR) })
        FyCard(padding = PaddingValues(12.dp)) {
            c.segments.forEachIndexed { i, seg ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${seg.title}", Modifier.weight(1f), fontSize = FyType.Body,
                        fontWeight = if (seg.enabled) FontWeight.Medium else FontWeight.Normal)
                    Stepper(seg.minutes, " 分", 1, 120) { m -> setSeg(i) { it.copy(minutes = m) } }
                    StepBtn(if (seg.enabled) "✓" else "·") { setSeg(i) { it.copy(enabled = !it.enabled) } }
                    StepBtn("↑") { if (i > 0) set { s -> s.copy(segments = s.segments.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) } }
                    StepBtn("×") { set { s -> s.copy(segments = s.segments.filterIndexed { j, _ -> j != i }) } }
                }
            }
            GroupLabel("添加环节")
            FyFilterChips(SegKind.ALL.map { SegKind.label(it) }, "") { l ->
                SegKind.ALL.firstOrNull { SegKind.label(it) == l }?.let { k -> set { s -> s.copy(segments = s.segments + Segment(k, 8)) } }
            }
            FyToggle("到时自动切换环节", c.autoRotate) { on -> set { it.copy(autoRotate = on) } }
            Text("合计 ${c.enabledSegments.sumOf { it.minutes }} 分钟", fontSize = FyType.Secondary)
        }

        FySectionHeader("主题与来源")
        FyCard(padding = PaddingValues(12.dp)) {
            c.topics.forEachIndexed { i, tw ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        FySlider(tw.topic, tw.weight) { v -> set { s -> s.copy(topics = s.topics.mapIndexed { j, t -> if (j == i) t.copy(weight = v) else t }) } }
                    }
                    StepBtn("×") { set { s -> s.copy(topics = s.topics.filterIndexed { j, _ -> j != i }) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(newTopic, { newTopic = it.take(24) }, "新主题", Modifier.weight(1f), placeholder = "例如：太空探索")
                FyButton("添加", { set { it.copy(topics = it.topics + TopicWeight(newTopic.trim(), 50)) }; newTopic = "" }, enabled = newTopic.isNotBlank(), tonal = true)
            }
            FySpacer(8.dp)
            FySlider("自由探索", c.explore, left = "紧扣主题", right = "随意跑题") { v -> set { it.copy(explore = v) } }
            TagsField("只去这些网站（白名单，可空）", c.whitelist, placeholder = "zh.wikipedia.org、github.com") { l -> set { it.copy(whitelist = l) } }
            FySpacer(8.dp)
            TagsField("不要去的网站（黑名单）", c.blacklist, placeholder = "example.com") { l -> set { it.copy(blacklist = l) } }
        }

        FySectionHeader("互动")
        FyCard(padding = PaddingValues(12.dp)) {
            FySlider("互动比例", c.chatRatio, left = "多冲浪", right = "多聊天") { v -> set { it.copy(chatRatio = v) } }
            GroupLabel("回复长度")
            FyFilterChips(REPLY.map { it.second }, REPLY.firstOrNull { it.first == c.replyLength }?.second ?: "适中") { l ->
                REPLY.firstOrNull { it.second == l }?.let { r -> set { it.copy(replyLength = r.first) } }
            }
            FySlider("冷场阈值", c.coldSeconds, 3..30, suffix = " 秒", left = "很快接话", right = "多等一会") { v -> set { it.copy(coldSeconds = v) } }
            FyToggle("接受观众点播", c.requestsOn) { on -> set { it.copy(requestsOn = on) } }
            FySlider("主动投票间隔", c.pollEveryMin, 0..60, suffix = " 分", left = "0 = 不主动") { v -> set { it.copy(pollEveryMin = v) } }
            FySlider("主动答题间隔", c.quizEveryMin, 0..60, suffix = " 分", left = "0 = 不主动") { v -> set { it.copy(quizEveryMin = v) } }
        }

        FySectionHeader("画面")
        FyCard(padding = PaddingValues(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LAYOUTS.forEach { (k, label) -> LayoutTile(label, k, c.layout == k, Modifier.weight(1f)) { set { it.copy(layout = k) } } }
            }
            FySpacer(8.dp)
            FySlider("字幕大小", c.subtitleSize, 22..56, suffix = " px") { v -> set { it.copy(subtitleSize = v) } }
            FyToggle("底部弹幕滚动条", c.ticker) { on -> set { it.copy(ticker = on) } }
            GroupLabel("推流画质")
            FyFilterChips(StreamPreset.values().map { it.label }, c.preset().label) { l ->
                StreamPreset.values().firstOrNull { it.label == l }?.let { p -> set { it.copy(streamPreset = p.name) } }
            }
        }

        FySectionHeader("安全")
        FyCard(padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { StatusChip("政治 / 色情 / 赌博 · 始终过滤", ChipKind.ACCENT) }
            FyToggle("过滤医疗 / 投资建议", c.filterMedical) { on -> set { it.copy(filterMedical = on) } }
            TagsField("额外屏蔽词（弹幕含这些词会被忽略）", c.blockKeywords) { l -> set { it.copy(blockKeywords = l) } }
            FySpacer(8.dp)
            FySlider("每位观众每分钟最多处理", c.perViewerPerMin, 1..30, suffix = " 条") { v -> set { it.copy(perViewerPerMin = v) } }
        }
        FyFootnote("人设、性格和口头禅在「人生 & 声音 › 人设」里设置。")
        Text("去人设 ›", Modifier.padding(start = 14.dp, top = 6.dp).clickable { open(Routes.PERSONA) }, fontSize = FyType.Secondary)
      }
    }
}
