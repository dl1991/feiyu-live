package com.dl1991.feiyu.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.FyA11yService
import com.dl1991.feiyu.dafeiyu.DfyRoutes
import com.dl1991.feiyu.dafeiyu.FyPlatforms
import com.dl1991.feiyu.dafeiyu.LivePlatform
import com.dl1991.feiyu.ui.CheckMark
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

/** B. 「直播接入」: where the stream goes (RTMP per platform), where comments come from, and the pre-live checklist. */
@Composable
fun LiveConnectScreen(back: (() -> Unit)?, open: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FyPlatforms.ensure(ctx) }
    val platform by FyPlatforms.selected.collectAsState()
    val src by FyPlatforms.sources.collectAsState()
    val checked by FyPlatforms.checklist.collectAsState()
    var rev by remember { mutableIntStateOf(0) } // bumps after save/clear so the status re-reads secrets
    var url by remember(platform, rev) { mutableStateOf(FyPlatforms.url(ctx, platform)) }
    var key by remember(platform, rev) { mutableStateOf("") }
    val hasKey = remember(platform, rev) { FyPlatforms.hasKey(ctx, platform) }
    val endpointOk = remember(platform, rev) { FyPlatforms.endpoint(ctx) != null }
    var roomText by remember(src.biliRoom) { mutableStateOf(if (src.biliRoom > 0) src.biliRoom.toString() else "") }
    val a11yOn = remember(rev) { FyA11yService.isEnabled(ctx) }
    val allChecked = FyPlatforms.CHECKLIST.indices.all { it in checked }

    FyScaffold("直播接入", back) {
        FySectionHeader("推流平台")
        FyCard(padding = PaddingValues(12.dp)) {
            FyFilterChips(LivePlatform.values().map { it.label }, platform.label) { l ->
                LivePlatform.values().firstOrNull { it.label == l }?.let { FyPlatforms.select(ctx, it) }
            }
            Text(platform.help, fontSize = FyType.Secondary)
            FySpacer(10.dp)
            GlassField(url, { url = it }, "推流地址（服务器）", placeholder = platform.urlHint, keyboardType = KeyboardType.Uri)
            FySpacer(8.dp)
            GlassField(key, { key = it }, "推流码 / 串流密钥", placeholder = if (hasKey) "已保存，留空则不修改" else "粘贴推流码", secret = true)
            FySpacer(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton("保存", { FyPlatforms.save(ctx, platform, url.trim(), key.trim()); rev++ }, tonal = true)
                FyButton("清除", { FyPlatforms.clear(ctx, platform); rev++ }, tonal = true)
                Spacer(Modifier.weight(1f))
                StatusChip(if (endpointOk) "推流已就绪" else if (hasKey) "地址需 rtmp://" else "未配置", if (endpointOk) ChipKind.OK else ChipKind.WARN)
            }
        }
        FyFootnote("推流码加密保存在本机，不进入备份。所有平台都走标准 RTMP 推流。")

        FySectionHeader("弹幕来源 · ${platform.label}")
        FyCard(padding = PaddingValues(12.dp)) {
            when (platform) {
                LivePlatform.DOUYIN -> {
                    FyToggle("读屏获取弹幕", src.douyinA11y, sub = if (a11yOn) "无障碍服务已开启：在抖音直播间页面读取评论" else "需要先开启「大肥鱼」无障碍服务") { on ->
                        FyPlatforms.setSources(ctx, src.copy(douyinA11y = on))
                    }
                    if (!a11yOn) Text("去开启无障碍 ›", Modifier.padding(vertical = 4.dp).clickable {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; rev++
                    }, fontSize = FyType.Secondary)
                    FyToggle("抖音开放平台", src.douyinOpen, sub = "需要开放平台应用资质（官方接口）") { on -> FyPlatforms.setSources(ctx, src.copy(douyinOpen = on)) }
                }
                LivePlatform.BILIBILI -> {
                    FyToggle("B站弹幕", src.bili, sub = "通过直播间号连接弹幕服务器") { on -> FyPlatforms.setSources(ctx, src.copy(bili = on)) }
                    GlassField(roomText, { v ->
                        roomText = v.filter { it.isDigit() }.take(12)
                        FyPlatforms.setSources(ctx, src.copy(biliRoom = roomText.toLongOrNull() ?: 0))
                    }, "直播间号", placeholder = "例如 6", keyboardType = KeyboardType.Number)
                    Text("登录 B站（可看到完整昵称）›", Modifier.padding(top = 8.dp).clickable { open(DfyRoutes.BILI_LOGIN) }, fontSize = FyType.Secondary)
                }
                else -> Text("${platform.label} 暂不支持读取弹幕：只推流，可用模拟观众陪她聊。", fontSize = FyType.Secondary)
            }
            GroupLabel("通用")
            FyToggle("模拟观众", src.sim, sub = "本地生成弹幕，用于排练或冷启动") { on -> FyPlatforms.setSources(ctx, src.copy(sim = on)) }
            if (src.sim) FySlider("模拟热度", src.simIntensity, 1..5, left = "冷清", right = "热闹") { v -> FyPlatforms.setSources(ctx, src.copy(simIntensity = v)) }
        }

        FySectionHeader("开播前检查")
        FyCard(padding = PaddingValues(12.dp)) {
            FyPlatforms.CHECKLIST.forEachIndexed { i, line ->
                val on = i in checked
                Row(Modifier.fillMaxWidth().clickable { FyPlatforms.setChecklist(ctx, if (on) checked - i else checked + i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top) {
                    CheckMark(on, ringWhenOff = true)
                    Spacer(Modifier.width(10.dp))
                    Text(line, Modifier.weight(1f), fontSize = FyType.Secondary)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("AI 生成内容 · 始终开启", ChipKind.ACCENT)
            }
        }

        FySectionHeader("下一步")
        FyCard(padding = PaddingValues(12.dp)) {
            Text(when {
                !allChecked -> "勾选全部检查项后才能开播。"
                !endpointOk -> "还没配置推流：可以先本地排练（不推流）。"
                else -> "一切就绪，去直播间开播。"
            }, fontSize = FyType.Secondary)
            FySpacer(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton("去直播间", { open(DfyRoutes.LIVE) }, enabled = allChecked)
                FyButton("导播台", { open(DfyRoutes.DIRECTOR) }, tonal = true)
            }
        }
    }
}
