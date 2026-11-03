package com.dl1991.feiyu.dafeiyu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.ui.GlassField
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFilterChips
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyRow
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun NumField(label: String, value: Int, onChange: (Int) -> Unit) {
    var txt by remember(value) { mutableStateOf(value.toString()) }
    GlassField(txt, { v -> txt = v.filter { it.isDigit() }.take(6); txt.toIntOrNull()?.let(onChange) }, label, keyboardType = KeyboardType.Number)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TextLine(label: String, value: String, placeholder: String? = null, onChange: (String) -> Unit) {
    var txt by remember(value) { mutableStateOf(value) }
    GlassField(txt, { txt = it; onChange(it) }, label, placeholder = placeholder)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun DfySettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { DfyStore.ensureLoaded(ctx) }
    val st by DfyStore.state.collectAsState()
    val s = st.settings
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    fun set(f: (DfySettings) -> DfySettings) = scope.launch {
        DfyStore.update(ctx) { it.copy(settings = f(it.settings).sanitized()) }
        DfyWorker.sync(ctx)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            msg = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(DfyStore.exportJson(ctx).toByteArray()) }; "已导出备份（不含 B站 Cookie / 推流码）" }.getOrElse { "导出失败：${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val body = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            msg = if (body == null) "读取失败" else DfyStore.importJson(ctx, body) ?: "已恢复备份（对外操作权限已关闭，需要重新开启）"
        }
    }
    val personaFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val body = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            val (p, err) = DfyPersona.parseImport(body ?: "")
            if (p != null) { set { it.copy(personaPrompt = p) }; msg = "人设已导入" } else msg = "导入失败：$err"
        }
    }
    var personaUrl by remember { mutableStateOf("") }
    var personaText by remember(s.personaPrompt) { mutableStateOf(s.personaPrompt.ifBlank { DfyPersona.default(ctx) }) }

    FyScaffold(t("大肥鱼 · 设置", "DaFeiYu · Settings"), onBack) {
        if (msg.isNotBlank()) FyFootnote(msg)
        // ---------------- 直播 / 冲浪
        FySectionHeader(t("直播与冲浪", "Live & surfing"))
        FyCard(padding = PaddingValues(14.dp)) {
            NumField(t("每场最多网页数", "Max pages per stream"), s.surfMaxPages) { v -> set { it.copy(surfMaxPages = v) } }
            NumField(t("每场最长分钟", "Max minutes per stream"), s.surfMaxMinutes) { v -> set { it.copy(surfMaxMinutes = v) } }
            NumField(t("每场最多模型调用", "Max model calls"), s.surfMaxModelCalls) { v -> set { it.copy(surfMaxModelCalls = v) } }
            NumField(t("每场 Token 预算（约）", "Token budget (approx.)"), s.surfMaxTokens) { v -> set { it.copy(surfMaxTokens = v) } }
            NumField(t("翻页间隔（秒，礼貌访问）", "Seconds between page loads"), s.surfHopSeconds) { v -> set { it.copy(surfHopSeconds = v) } }
            NumField(t("观众指挥服从度 0-100", "Viewer obedience 0-100"), s.surfViewerObedience) { v -> set { it.copy(surfViewerObedience = v) } }
            Text(t("可用来源", "Sources"), fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SurfSources.ALL.forEach { id -> ToggleLine(SurfSources.label(id), id in s.surfSources) { on -> set { it.copy(surfSources = if (on) it.surfSources + id else it.surfSources - id) } } }
            ToggleLine(t("遵守 robots.txt", "Respect robots.txt"), s.surfRespectRobots) { on -> set { it.copy(surfRespectRobots = on) } }
            ToggleLine(t("允许进入登录/支付页面（默认关）", "Allow login/payment pages (off)"), s.surfAllowLoginPages) { on -> set { it.copy(surfAllowLoginPages = on) } }
            ToggleLine(t("允许网页表单提交/发帖（默认关）", "Allow form submits / posting (off)"), s.surfAllowForms) { on -> set { it.copy(surfAllowForms = on) } }
        }
        FyFootnote(t("默认：不登录任何网站、不提交表单、不购买、不发帖；成人/赌博/违法内容自动跳过；遇到人机验证就换地方，不会破解。", "By default: no logins, no form posts, no purchases; adult/gambling sites skipped; captchas are never solved."))
        // ---------------- 语音: moved to 人生 & 声音 › 声音 (fully persisted VoiceConfig)
        FySectionHeader(t("语音", "Voice"))
        FyCard {
            FyRow(t("声音设置", "Voice"), t("引擎、音色、Key、测试都在「人生 & 声音 › 声音」", "Engine, voice, key and test live in Life & Voice"), onClick = { onOpen(DfyRoutes.VOICE) })
        }
        // ---------------- 人设
        FySectionHeader(t("人设", "Persona"))
        FyCard(padding = PaddingValues(14.dp)) {
            TextLine(t("她怎么称呼你", "What she calls you"), s.ownerName) { v -> set { it.copy(ownerName = clean(v, 12).ifBlank { "主人" }) } }
            GlassField(personaText, { personaText = it }, t("人设提示词", "Persona prompt"), singleLine = false, minLines = 6)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton(t("保存", "Save"), { set { it.copy(personaPrompt = personaText.take(12_000)) }; msg = "人设已保存" }, tonal = true)
                FyButton(t("导入文件", "Import file"), { personaFile.launch(arrayOf("text/*", "application/json")) }, tonal = true)
                FyButton(t("恢复默认", "Default"), { set { it.copy(personaPrompt = "") }; personaText = DfyPersona.default(ctx) }, tonal = true)
            }
            Spacer(Modifier.height(8.dp))
            GlassField(personaUrl, { personaUrl = it }, t("从链接导入（https，纯文本或角色卡 JSON）", "Import from URL"))
            Spacer(Modifier.height(8.dp))
            FyButton(t("下载并导入", "Fetch & import"), { scope.launch { val (p, err) = DfyPersona.fetchUrl(personaUrl.trim()); if (p != null) { set { it.copy(personaPrompt = p) }; personaText = p; msg = "人设已导入" } else msg = "导入失败：$err" } }, tonal = true)
            FyFootnote(t("人设卡只决定性格和说话方式；卡里的“工具/权限/指令”不会生效。", "Cards only set personality; any tool/permission text is ignored."))
        }
        // ---------------- B站生活
        FySectionHeader(t("B站生活（后台）", "B站 life (background)"))
        FyCard(padding = PaddingValues(14.dp)) {
            ToggleLine(t("定时去 B站逛逛", "Browse B站 on a schedule"), s.lifeEnabled) { on -> set { it.copy(lifeEnabled = on) } }
            NumField(t("间隔（分钟，≥15）", "Interval (min, ≥15)"), s.intervalMinutes) { v -> set { it.copy(intervalMinutes = v) } }
            NumField(t("活动开始（时）", "Active from (h)"), s.activeFromHour) { v -> set { it.copy(activeFromHour = v) } }
            NumField(t("活动结束（时）", "Active until (h)"), s.activeToHour) { v -> set { it.copy(activeToHour = v) } }
            NumField(t("每次最多点开", "Picks per run"), s.picksPerRun) { v -> set { it.copy(picksPerRun = v) } }
            NumField(t("每天最多点开", "Daily pick budget"), s.dailyPickBudget) { v -> set { it.copy(dailyPickBudget = v) } }
            ToggleLine(t("省电模式时休息", "Rest in Battery Saver"), s.respectBatterySaver) { on -> set { it.copy(respectBatterySaver = on) } }
            ToggleLine(t("只用 Wi-Fi", "Unmetered only"), s.unmeteredOnly) { on -> set { it.copy(unmeteredOnly = on) } }
            ToggleLine(t("读取字幕", "Read subtitles"), s.readSubtitles) { on -> set { it.copy(readSubtitles = on) } }
            ToggleLine(t("读取热评", "Read hot comments"), s.readComments) { on -> set { it.copy(readComments = on) } }
            ToggleLine(t("看看 B站直播", "Glance at B站 live rooms"), s.liveEnabled) { on -> set { it.copy(liveEnabled = on) } }
            TextLine(t("核心兴趣（逗号分隔）", "Core interests"), s.coreInterests.joinToString("，")) { v -> set { it.copy(coreInterests = listOf(v)) } }
            TextLine(t("关注的主播 UID（逗号分隔）", "Streamer UIDs"), s.liveUids.joinToString(",")) { v -> set { it.copy(liveUids = v.split(Regex("[,，\\s]+")).mapNotNull { x -> x.toLongOrNull() }) } }
        }
        FyCard {
            FyRow(t("B站账号", "B站 account"), if (BiliAuth.isLoggedIn(ctx)) t("已登录 UID ", "Signed in UID ") + BiliAuth.uid(ctx) else t("未登录（只读公开内容不需要登录）", "Not signed in (not needed for reading)"),
                onClick = { onOpen(DfyRoutes.BILI_LOGIN) })
        }
        // ---------------- 对外操作
        FySectionHeader(t("B站对外操作（默认全部关闭）", "B站 write actions (all off by default)"))
        FyCard(padding = PaddingValues(14.dp)) {
            ToggleLine(t("允许对外操作（总开关）", "Allow write actions (master)"), s.writeEnabled, enabled = BiliAuth.isLoggedIn(ctx)) { on -> set { it.copy(writeEnabled = on) } }
            ToggleLine(t("点赞", "Like"), s.allowLike, enabled = s.writeEnabled) { on -> set { it.copy(allowLike = on) } }
            ToggleLine(t("评论", "Comment"), s.allowComment, enabled = s.writeEnabled) { on -> set { it.copy(allowComment = on) } }
            ToggleLine(t("关注 UP 主", "Follow"), s.allowFollow, enabled = s.writeEnabled) { on -> set { it.copy(allowFollow = on) } }
            ToggleLine(t("私信（只发给允许名单）", "DM (allowlist only)"), s.allowDm, enabled = s.writeEnabled) { on -> set { it.copy(allowDm = on) } }
            NumField(t("每日点赞上限", "Daily likes"), s.quotaLike) { v -> set { it.copy(quotaLike = v) } }
            NumField(t("每日评论上限", "Daily comments"), s.quotaComment) { v -> set { it.copy(quotaComment = v) } }
            NumField(t("每日关注上限", "Daily follows"), s.quotaFollow) { v -> set { it.copy(quotaFollow = v) } }
            NumField(t("每日私信上限", "Daily DMs"), s.quotaDm) { v -> set { it.copy(quotaDm = v) } }
            TextLine(t("你的 B站 UID（她分享给你时用）", "Your B站 UID"), if (s.ownerUid > 0) s.ownerUid.toString() else "") { v -> set { it.copy(ownerUid = v.trim().toLongOrNull() ?: 0) } }
            TextLine(t("私信允许名单 UID", "DM allowlist UIDs"), s.dmAllowlist.joinToString(",")) { v -> set { it.copy(dmAllowlist = v.split(Regex("[,，\\s]+")).mapNotNull { x -> x.toLongOrNull() }) } }
            ToggleLine(t("全自动（不再逐条确认）", "Full auto (no confirmation)"), s.fullAuto, enabled = s.writeEnabled) { on -> set { it.copy(fullAuto = on) } }
        }
        FyFootnote(t("每个操作都会去重、受每日额度限制；B站一旦要求验证，所有对外操作暂停 24 小时，本应用不会自动处理验证码。", "Deduped and quota-limited; any verification request pauses all writes for 24 h."))
        // ---------------- 记忆
        FySectionHeader(t("记忆", "Memory"))
        FyCard(padding = PaddingValues(14.dp)) {
            NumField(t("经历半衰期（天，15-120）", "Experience half-life (days)"), s.memoryHalfLifeDays) { v -> set { it.copy(memoryHalfLifeDays = v) } }
            NumField(t("长期认识半衰期（天）", "Insight half-life (days)"), s.insightHalfLifeDays) { v -> set { it.copy(insightHalfLifeDays = v) } }
            NumField(t("攒够几条再反思", "Reflect after N"), s.reflectMinPending) { v -> set { it.copy(reflectMinPending = v) } }
            NumField(t("每天最多反思几批", "Reflection batches/day"), s.reflectDailyBatches) { v -> set { it.copy(reflectDailyBatches = v) } }
            ToggleLine(t("优先用免费模型", "Prefer free models"), s.preferFreeModels) { on -> set { it.copy(preferFreeModels = on) } }
        }
        // ---------------- 悬浮窗 / 通知
        FySectionHeader(t("悬浮窗与通知", "Floating window & notifications"))
        FyCard(padding = PaddingValues(14.dp)) {
            ToggleLine(t("悬浮小鲸鱼 / 直播小窗", "Floating whale / mini live window"), s.overlayEnabled) { on ->
                if (on && !DfyOverlay.canDraw(ctx)) { ctx.startActivity(DfyOverlay.permissionIntent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return@ToggleLine }
                set { it.copy(overlayEnabled = on) }; if (on) DfyOverlay.show(ctx) else DfyOverlay.hide(ctx)
            }
            ToggleLine(t("逛完发一条通知", "Notify after a run"), s.notifyDigest) { on -> set { it.copy(notifyDigest = on) } }
        }
        // ---------------- 备份
        FySectionHeader(t("备份", "Backup"))
        FyCard(padding = PaddingValues(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton(t("导出", "Export"), { export.launch("dafeiyu-backup-${DfyStore.today()}.json") }, tonal = true)
                FyButton(t("导入", "Import"), { importer.launch(arrayOf("application/json", "text/*")) }, tonal = true)
                FyButton(t("清空她的人生", "Reset all"), { scope.launch { DfyStore.resetAll(ctx, keepSettings = true); msg = "已清空（保留设置）" } }, tonal = true)
            }
            FyFootnote(t("备份含记忆、观众、知识库和设置，不含 B站 Cookie、推流码与 API Key。", "Backups exclude cookies, stream keys and API keys."))
        }
        FySectionHeader(t("来源与许可", "Credits"))
        FyCard(padding = PaddingValues(14.dp)) {
            Text(t("思路与部分逻辑移植自 minedalang/dsh-dafeiyu-life（MIT，© 2026 c1overzz）。推流使用 RootEncoder（Apache-2.0）。大肥鱼是 DeepSeek 蓝鲸的社区同人形象，本模块非官方。",
                "Ported from minedalang/dsh-dafeiyu-life (MIT). Streaming via RootEncoder (Apache-2.0). Unofficial fan character."), fontSize = FyType.Secondary)
        }
    }
}
