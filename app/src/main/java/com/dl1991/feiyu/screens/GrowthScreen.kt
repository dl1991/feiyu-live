package com.dl1991.feiyu.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.dafeiyu.FyAgent
import com.dl1991.feiyu.dafeiyu.FyContentStore
import com.dl1991.feiyu.dafeiyu.FyLifeLogic
import com.dl1991.feiyu.dafeiyu.NextPlan
import com.dl1991.feiyu.dafeiyu.PlanSegment
import com.dl1991.feiyu.dafeiyu.SegKind
import com.dl1991.feiyu.ui.ChipKind
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySpacer
import com.dl1991.feiyu.ui.FyStat
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.StatusChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「她的成长」: goals, interests, skills, diary, regulars, and the plan she proposes for the next stream. */
@Composable
fun GrowthScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FyAgent.ensure(ctx) }
    val life by FyAgent.lifeState.collectAsState()
    val plan = life.nextPlan
    var topics by remember(plan?.createdAt, plan?.status) { mutableStateOf(plan?.topics?.joinToString("、").orEmpty()) }
    var segs by remember(plan?.createdAt, plan?.status) { mutableStateOf(plan?.segments ?: emptyList()) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    fun edited(): NextPlan = NextPlan(topics.split('、', ',', '，', '/').map { it.trim().take(24) }.filter { it.isNotBlank() }.take(4),
        segs, plan?.why.orEmpty(), plan?.createdAt ?: System.currentTimeMillis(), "edited")

    FyScaffold("她的成长", back) {
        FyCard(padding = PaddingValues(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyStat("直播", "${life.streams} 场", Modifier.weight(1f), accent = true)
                FyStat("时长", "${life.liveMinutes} 分", Modifier.weight(1f))
                FyStat("学到", "${life.skills.size} 条", Modifier.weight(1f))
                FyStat("熟人", "${life.relations.count { it.streams >= 2 }}", Modifier.weight(1f))
            }
            Text("「先让她自己直播养她自己」：这里的一切都来自真实直播记录——兴趣随观众反应和她的好奇心变化，日记只写发生过的事。",
                Modifier.padding(top = 8.dp), fontSize = FyType.Caption)
        }

        FySectionHeader("下一场 · 她的提议")
        FyCard(padding = PaddingValues(12.dp)) {
            if (plan == null) Text("直播结束后，复盘官会根据今天的观众反应和她的兴趣，提议下一场的话题和节目单。", fontSize = FyType.Secondary)
            else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(when (plan.status) { "applied" -> "已采用"; "edited" -> "已修改"; else -> "她的提议" }, if (plan.status == "applied") ChipKind.OK else ChipKind.ACCENT)
                    if (plan.why.isNotBlank()) Text(plan.why, Modifier.weight(1f), fontSize = FyType.Caption)
                }
                FySpacer(8.dp)
                GlassField(topics, { topics = it.take(100) }, "话题（用、分隔）")
                GroupLabel("节目单（点 −/+ 调时长，点 ✕ 删掉）")
                segs.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}. ${SegKind.label(s.kind)}", Modifier.weight(1f), fontSize = FyType.Body)
                        Text("−", Modifier.clickable { segs = segs.mapIndexed { j, x -> if (j == i) x.copy(minutes = (x.minutes - 1).coerceAtLeast(1)) else x } }.padding(horizontal = 6.dp), fontSize = FyType.Title)
                        Text("${s.minutes} 分", fontSize = FyType.Secondary, fontFamily = FyType.Mono)
                        Text("+", Modifier.clickable { segs = segs.mapIndexed { j, x -> if (j == i) x.copy(minutes = (x.minutes + 1).coerceAtMost(60)) else x } }.padding(horizontal = 6.dp), fontSize = FyType.Title)
                        Text("✕", Modifier.clickable { if (segs.size > 1) segs = segs.filterIndexed { j, _ -> j != i } }.padding(horizontal = 6.dp), fontSize = FyType.Secondary)
                    }
                }
                GroupLabel("加一个环节")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegKind.ALL.forEach { k -> Text(SegKind.label(k), Modifier.clickable { segs = segs + PlanSegment(k, 5) }.padding(vertical = 4.dp), fontSize = FyType.Caption, color = FyTokens.accent()) }
                }
                FySpacer(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FyButton("保存修改", { FyAgent.updateLife(ctx) { it.copy(nextPlan = edited()) } }, tonal = true)
                    FyButton("采用为下场节目单", { FyAgent.applyPlan(ctx, edited()) }, enabled = segs.isNotEmpty())
                }
            }
            FySpacer(6.dp)
            FyButton("按兴趣重新排一份", {
                val base = FyContentStore.get(ctx).enabledSegments.map { PlanSegment(it.kind, it.minutes) }
                FyAgent.updateLife(ctx) { it.copy(nextPlan = FyLifeLogic.defaultPlan(it, base, System.currentTimeMillis())) }
            }, tonal = true)
        }
        FyFootnote("采用后写入「直播内容 › 节目单」和主题偏好；开播时若没填话题，她会从自己的提议里选第一个。")

        FySectionHeader("目标")
        FyCard(padding = PaddingValues(12.dp)) {
            if (life.goals.isEmpty()) Text("第一次开播时会生成她的起步目标。", fontSize = FyType.Secondary)
            life.goals.sortedBy { if (it.status == "done") 1 else 0 }.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(if (g.horizon == "long") "长期" else "短期", if (g.status == "done") ChipKind.OK else ChipKind.NEUTRAL)
                    Text(g.text, Modifier.weight(1f), fontSize = FyType.Secondary)
                    Text(if (g.status == "done") "完成" else "${g.progress}%", fontSize = FyType.Caption, fontFamily = FyType.Mono)
                }
            }
        }

        FySectionHeader("兴趣（观众反应 + 好奇心）")
        FyCard(padding = PaddingValues(12.dp)) {
            if (life.interests.isEmpty()) Text("还没有。", fontSize = FyType.Secondary)
            life.interests.sortedByDescending { it.weight }.take(12).forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.topic, Modifier.weight(1f), fontSize = FyType.Secondary)
                    Text("█".repeat((t.weight / 10).toInt().coerceIn(1, 10)), fontSize = FyType.Caption, color = FyTokens.accent())
                    Text(" ${t.weight.toInt()}", fontSize = FyType.Caption, fontFamily = FyType.Mono)
                }
            }
            if (life.lessons.isNotEmpty()) { GroupLabel("复盘经验"); life.lessons.takeLast(5).reversed().forEach { Text("· $it", fontSize = FyType.Caption) } }
        }

        FySectionHeader("日记")
        FyCard(padding = PaddingValues(12.dp)) {
            if (life.diary.isEmpty()) Text("每场直播结束后，复盘官替她写一篇。", fontSize = FyType.Secondary)
            life.diary.takeLast(6).reversed().forEach { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(fmt.format(Date(d.at)) + "  " + d.facts, fontSize = FyType.Caption, fontFamily = FyType.Mono)
                    Text(d.text, fontSize = FyType.Body)
                    if (d.highlights.isNotEmpty()) Text("亮点：" + d.highlights.joinToString("；"), fontSize = FyType.Caption, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        FySectionHeader("学到的东西")
        FyCard(padding = PaddingValues(12.dp)) {
            if (life.skills.isEmpty()) Text("她读网页时记下的知识笔记（带来源）会出现在这里。", fontSize = FyType.Secondary)
            life.skills.takeLast(10).reversed().forEach { k -> Text("· ${k.text}（${k.source}）", Modifier.padding(vertical = 2.dp), fontSize = FyType.Secondary) }
        }

        FySectionHeader("熟悉的观众")
        FyCard(padding = PaddingValues(12.dp)) {
            if (life.relations.isEmpty()) Text("常来的观众和她记住的小事。", fontSize = FyType.Secondary)
            life.relations.take(12).forEach { r ->
                Text("${r.nick} · ${r.streams} 场" + if (r.note.isNotBlank()) " · ${r.note}" else "", Modifier.padding(vertical = 2.dp), fontSize = FyType.Secondary)
            }
        }
    }
}
