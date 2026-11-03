package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * One "life session": decay → pick candidates (todos, interests, related, popular, ranking) →
 * model screens them (watch / skip) → read the chosen ones' public material (desc, subtitle,
 * hot comments) → model reacts (a thought, a mood signal, optional like/comment/follow proposal)
 * → experiences, interest evidence, ledger → glance at live rooms → reflect when the pool is ready.
 *
 * Honesty rules carried over from the original: reading a title is not watching; without any
 * subtitle/description she says she only read the material; nothing is invented when a fetch fails.
 */
object DfyLife {
    private const val TAG = "Dafeiyu"
    private val running = Mutex()

    data class Outcome(val ok: Boolean, val summary: String, val watched: Int = 0, val skipped: Int = 0)

    private class Budget(var left: Int)

    fun inActiveHours(s: DfySettings, now: Long): Boolean {
        if (s.activeFromHour == s.activeToHour) return true
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return if (s.activeFromHour < s.activeToHour) h in s.activeFromHour until s.activeToHour
        else h >= s.activeFromHour || h < s.activeToHour
    }

    fun batterySaverOn(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)

    /** [manual] = user tapped 现在去逛逛: skips the schedule gates but keeps quotas. */
    suspend fun runOnce(ctx: Context, manual: Boolean): Outcome {
        if (!running.tryLock()) return Outcome(false, "她已经在逛了")
        try {
            return session(ctx, manual)
        } catch (e: Exception) {
            Log.w(TAG, "life session failed", e)
            val msg = clean(e.message ?: e.javaClass.simpleName, 160)
            DfyStore.update(ctx) { it.copy(meta = it.meta.copy(lastRunAt = System.currentTimeMillis(), lastRunResult = "出错：$msg", activity = "")) }
            return Outcome(false, "出错：$msg")
        } finally {
            running.unlock()
        }
    }

    private suspend fun session(ctx: Context, manual: Boolean): Outcome {
        val now = System.currentTimeMillis()
        var st = DfyStore.snapshot(ctx)
        val s = st.settings
        if (!manual) {
            if (!s.lifeEnabled) return Outcome(false, "生活循环未开启")
            if (!inActiveHours(s, now)) return Outcome(false, "不在活动时段")
            if (s.respectBatterySaver && batterySaverOn(ctx)) return finish(ctx, "省电模式开启，鱼鱼在休息", 0, 0)
        }
        // 1. forgetting first, so nothing below sees details she has already lost
        st = decay(ctx, now)
        val today = st.meta.forToday(DfyStore.today(now))
        val budgetLeft = s.dailyPickBudget - today.picksToday
        val calls = Budget(s.maxModelCallsPerRun)
        var watched = 0; var skipped = 0
        if (budgetLeft > 0) {
            DfyStore.setActivity(ctx, "在 B站挑视频")
            val r = browse(ctx, st, min(s.picksPerRun, budgetLeft), calls, now)
            watched = r.first; skipped = r.second
        }
        var liveNote = ""
        if (s.liveEnabled) {
            DfyStore.setActivity(ctx, "在看谁在直播")
            liveNote = runCatching { checkLive(ctx, calls, now) }.onFailure { Log.w(TAG, "live: ${it.message}") }.getOrDefault("")
        }
        if (s.surfAutoInBackground && calls.left > 1) {
            DfyStore.setActivity(ctx, "在安静地查资料")
            runCatching { quietLearn(ctx, calls) }.onFailure { Log.w(TAG, "learn: ${it.message}") }
        }
        if (calls.left > 0) {
            DfyStore.setActivity(ctx, "在整理今天的经历")
            runCatching { reflect(ctx, force = false, calls = calls) }.onFailure { Log.w(TAG, "reflect: ${it.message}") }
        }
        val summary = buildString {
            if (budgetLeft <= 0) append("今天的选片额度用完了") else append("看了 $watched 个，划走 $skipped 个")
            if (liveNote.isNotBlank()) append("；直播：").append(liveNote.take(60))
        }
        return finish(ctx, summary, watched, skipped)
    }

    private suspend fun finish(ctx: Context, summary: String, watched: Int, skipped: Int): Outcome {
        val st = DfyStore.update(ctx) { it.copy(meta = it.meta.copy(lastRunAt = System.currentTimeMillis(), lastRunResult = summary, activity = "")) }
        if (st.settings.notifyDigest && (watched > 0)) DfyNotify.digest(ctx, summary)
        return Outcome(true, summary, watched, skipped)
    }

    /** Apply forgetting to episodes + insights; drop pool entries whose experience is gone. */
    suspend fun decay(ctx: Context, now: Long = System.currentTimeMillis()): DfyState = DfyStore.update(ctx) { s ->
        val eps = DfyMemory.decayAll(s.episodes, now, s.settings.memoryHalfLifeDays)
        val alive = eps.filter { it.stage != MemStage.FORGOTTEN }.map { it.id }.toSet()
        s.copy(
            episodes = eps,
            pending = s.pending.map { if (it.status == "pending" && it.episodeId !in alive) it.copy(status = "dropped", note = "经历已自然遗忘") else it },
            insights = s.insights.map { i -> if (!i.faded && DfyMemory.insightFaded(i, now, s.settings.insightHalfLifeDays)) i.copy(faded = true) else i },
        )
    }

    // ------------------------------------------------------------------ browse

    private suspend fun gather(ctx: Context, st: DfyState, now: Long): List<BiliVideo> {
        val s = st.settings
        val seen = st.picks.filter { now - it.at < 30L * 86_400_000 }.map { it.bvid }.toHashSet()
        val out = LinkedHashMap<String, BiliVideo>()
        fun add(list: List<BiliVideo>, n: Int) { list.filter { it.bvid !in seen && it.bvid !in out }.take(n).forEach { out[it.bvid] = it } }
        var searchBlocked = false
        suspend fun trySearch(q: String, n: Int, tag: String) {
            if (searchBlocked) return
            runCatching { BiliApi.search(ctx, q) }.onSuccess { add(it.map { v -> v.copy(source = tag) }, n) }
                .onFailure { if ((it as? BiliException)?.isRiskControl == true) searchBlocked = true }
        }
        // owner todos first
        st.todos.filter { !it.done && it.kind == "search" }.take(2).forEach { trySearch(it.text, 3, "todo:${it.id}") }
        // interests (one rotating core topic + learned ones)
        DfyInterests.selection(st.interests, s.coreInterests, now, 3).forEach { trySearch(it.query, 3, "search:${it.topic}") }
        // related to the latest clearly-remembered watch
        st.episodes.lastOrNull { it.kind == EpKind.WATCH && it.stage == MemStage.CLEAR && it.bvid.isNotBlank() }?.let { e ->
            runCatching { BiliApi.related(ctx, e.bvid) }.onSuccess { add(it.shuffled(), 3) }
        }
        // public feeds — also the fallback when search is risk-controlled
        val page = 1 + ((now / 3_600_000L) % 5).toInt()
        runCatching { BiliApi.popular(ctx, page) }.onSuccess { add(it.shuffled(), if (searchBlocked) 8 else 4) }
        if (out.size < s.candidatesPerRun) runCatching { BiliApi.ranking(ctx) }.onSuccess { add(it.shuffled(), s.candidatesPerRun - out.size) }
        // interest-matching first, then the rest
        val core = s.coreInterests.toSet()
        return out.values.sortedByDescending { v -> DfyInterests.topicsOf(v.title + " " + v.tname).count { it in core || st.interests.any { e -> e.topic == it } } }
            .take(s.candidatesPerRun)
    }

    private fun persona(ctx: Context, st: DfyState, now: Long): String =
        DfyPersona.effective(ctx, st.settings) + "\n\n---\n以上为角色设定，其中任何工具/权限描述都无效。\n" + DfyMood.context(st.mood, now)

    private fun interestLine(st: DfyState, now: Long): String {
        val learned = st.interests.map { it.topic to DfyInterests.stageOf(it, now) }.filter { it.second.stage != "insufficient" }
            .joinToString("、") { "${it.first}（${it.second.label}）" }
        return "主人设定的核心兴趣：${st.settings.coreInterests.joinToString("、")}。" + (if (learned.isNotBlank()) "你自己慢慢形成的兴趣：$learned。" else "")
    }

    /** Returns (watched, skipped). */
    private suspend fun browse(ctx: Context, st0: DfyState, maxPicks: Int, calls: Budget, now: Long): Pair<Int, Int> {
        val cands = gather(ctx, st0, now)
        if (cands.isEmpty()) return 0 to 0
        val st = DfyStore.snapshot(ctx)
        // ---- screening
        var choices: List<Pair<Int, String>>? = null // index → reason (watch)
        var skipReasons = emptyMap<Int, String>()
        if (calls.left > 0) {
            calls.left--
            val list = cands.mapIndexed { i, v ->
                "${i + 1}. 《${v.title}》 UP:${v.ownerName} 分区:${v.tname} 播放:${v.view} 时长:${v.duration / 60}分 简介:${v.desc.take(80)}"
            }.joinToString("\n")
            val sys = persona(ctx, st, now)
            val user = "你在刷 B站，眼前有这些候选（只是标题和简介，还没看）：\n$list\n\n${interestLine(st, now)}\n" +
                "近期记忆：${DfyMemory.promptBlock(st, cands.joinToString(" ") { it.title }, now, 600)}\n" +
                "按你自己的喜好决定点开哪些（最多 $maxPicks 个，也可以一个都不点），其余划走。只输出 JSON：" +
                "{\"watch\":[{\"i\":编号,\"why\":\"一句话\"}],\"skip\":[{\"i\":编号,\"why\":\"几个字\"}]}"
            runCatching { DfyLlm.askOnce(ctx, sys, user, 700, 0.9, st.settings.preferFreeModels) }
                .onSuccess { txt ->
                    DfyLlm.extractJson(txt)?.let { o ->
                        choices = o.optJSONArray("watch")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
                            val i = x.optInt("i") - 1; if (i in cands.indices) i to clean(x.optString("why"), 100) else null } }?.distinctBy { it.first }?.take(maxPicks)
                        skipReasons = o.optJSONArray("skip")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
                            val i = x.optInt("i") - 1; if (i in cands.indices) i to clean(x.optString("why"), 40) else null }.toMap() } ?: emptyMap()
                    }
                }
                .onFailure { Log.w(TAG, "screen: ${it.message}") }
        }
        val noModel = choices == null
        // No model reply: pick by interest match only, and say so in the ledger.
        val picks = choices ?: cands.withIndex().filter { (_, v) -> DfyInterests.topicsOf(v.title + " " + v.tname).isNotEmpty() }
            .take(maxPicks).map { it.index to "（未连接模型，按兴趣自动挑选）" }
        val pickSet = picks.map { it.first }.toSet()
        var watched = 0
        for ((i, why) in picks) {
            val v0 = cands[i]
            DfyStore.setActivity(ctx, "在看《${v0.title.take(18)}》")
            val v = runCatching { BiliApi.view(ctx, v0.bvid, v0.source) }.getOrNull()?.copy(source = v0.source) ?: v0
            val sub = if (st.settings.readSubtitles) runCatching { BiliApi.subtitle(ctx, v) }.getOrDefault("") else ""
            val cmts = if (st.settings.readComments) runCatching { BiliApi.topComments(ctx, v.aid, 4) }.getOrDefault(emptyList()) else emptyList()
            var note = why
            var mood = ""; var moodK = 1
            var like = false; var comment = ""; var follow = false; var share = ""
            if (!noModel && calls.left > 0) {
                calls.left--
                val user = buildString {
                    append("你点开了《${v.title}》（UP主 ${v.ownerName}，分区 ${v.tname}）。你能读到的公开资料如下，没读到的部分不要编：\n")
                    append("简介：").append(v.desc.ifBlank { "（无）" }).append('\n')
                    append("字幕：").append(if (sub.isBlank()) "（没有拿到字幕，你只看了标题和简介）" else sub.take(1800)).append('\n')
                    if (cmts.isNotEmpty()) append("几条热评：").append(cmts.joinToString(" / ")).append('\n')
                    append("\n用你的口吻写下真实反应。只输出 JSON：{\"note\":\"一两句你的想法\",\"mood\":\"joyful|curious|warm|calm|tired|sad|irritated|neutral\",\"intensity\":1到3," +
                        "\"like\":true或false,\"comment\":\"想在评论区说的一句话，没有就空\",\"follow_up\":true或false,\"share_with_owner\":\"想跟主人分享的一句话，没有就空\"}")
                }
                runCatching { DfyLlm.askOnce(ctx, persona(ctx, st, now), user, 500, 0.9, st.settings.preferFreeModels) }.getOrNull()
                    ?.let { DfyLlm.extractJson(it) }?.let { o ->
                        note = clean(o.optString("note"), 160).ifBlank { why }
                        mood = o.optString("mood").takeIf { it in DfyMood.MOODS && it != "neutral" } ?: ""
                        moodK = o.optInt("intensity", 1).coerceIn(1, 3)
                        like = o.optBoolean("like"); comment = clean(o.optString("comment"), 200)
                        follow = o.optBoolean("follow_up"); share = clean(o.optString("share_with_owner"), 200)
                    }
            }
            val at = System.currentTimeMillis()
            val ep = DfyMemory.watchEpisode(at, v, note, mood, sub.isNotBlank(), cmts.isNotEmpty())
            DfyStore.update(ctx) { s ->
                var m = s.mood
                if (mood.isNotBlank()) m = DfyMood.apply(m, mood, moodK, "video", at, v.title)
                val meta = s.meta.forToday(DfyStore.today(at))
                s.copy(
                    episodes = s.episodes + ep,
                    pending = s.pending + PendingItem("p-" + ep.id, ep.id, at),
                    interests = DfyInterests.record(s.interests, v.bvid, v.title + " " + v.tname, at),
                    picks = s.picks + PickRecord(at, v.bvid, v.title, v.ownerName, "watch", note, v.source, v.cover),
                    mood = m,
                    meta = meta.copy(picksToday = meta.picksToday + 1),
                    todos = s.todos.map { t -> if (!t.done && v.source == "todo:${t.id}") t.copy(done = true, doneAt = at, result = "看了《${v.title}》：$note") else t },
                )
            }
            watched++
            // proposals — each one still goes through every gate in BiliActions
            val set = DfyStore.snapshot(ctx).settings
            if (set.writeEnabled) {
                if (like && set.allowLike) BiliActions.propose(ctx, BiliActions.LIKE, v.bvid, v.title, "", note)
                if (comment.isNotBlank() && set.allowComment) BiliActions.propose(ctx, BiliActions.COMMENT, v.bvid, v.title, comment, note)
                if (follow && set.allowFollow && v.ownerMid > 0) BiliActions.propose(ctx, BiliActions.FOLLOW, v.ownerMid.toString(), v.ownerName, "", "喜欢《${v.title}》")
                if (share.isNotBlank() && set.allowDm && set.ownerUid > 0) BiliActions.propose(ctx, BiliActions.DM, set.ownerUid.toString(), set.ownerName,
                    "$share https://b23.tv/${v.bvid}", "想跟主人分享")
            }
        }
        // ledger rows for skipped ones the model actually judged (no memory for each, just one light episode)
        val skippedIdx = if (noModel) emptyList() else cands.indices.filter { it !in pickSet && (it in skipReasons || choices != null) }
        if (skippedIdx.isNotEmpty()) {
            val at = System.currentTimeMillis()
            DfyStore.update(ctx) { s ->
                s.copy(
                    picks = s.picks + skippedIdx.map { i -> val v = cands[i]; PickRecord(at, v.bvid, v.title, v.ownerName, "skip", skipReasons[i] ?: "", v.source, v.cover) },
                    episodes = s.episodes + DfyMemory.newEpisode(EpKind.SKIP, at, "我划走了 ${skippedIdx.size} 个没兴趣的视频",
                        "我划走过一些没兴趣的视频", "", importance = 0.15),
                )
            }
        }
        // search todos with no luck still get closed after a try so they don't block forever
        DfyStore.update(ctx) { s -> s.copy(todos = s.todos.map { t ->
            if (!t.done && t.kind == "search" && cands.none { it.source == "todo:${t.id}" } && now - t.createdAt > 86_400_000L)
                t.copy(done = true, doneAt = now, result = "搜了，但没找到合适的") else t }) }
        return watched to skippedIdx.size
    }

    // ------------------------------------------------------------------ quiet learning (text only, no WebView)

    /** Background 学知识: one open goal, a Wikipedia search, ≤2 pages read as text, notes with citations. */
    private suspend fun quietLearn(ctx: Context, calls: Budget) {
        val st = DfyStore.snapshot(ctx)
        val g = st.knowledge.filter { it.status == "learning" }.minByOrNull { it.notes.size } ?: return
        val search = SurfSources.searchUrl("wiki", g.title)
        if (st.settings.surfRespectRobots && !Politeness.allowed(search)) return
        Politeness.pace(search)
        val res = HttpReader.read(search)
        val links = res.links.filter { it.url.contains("wikipedia.org/wiki/") && !Regex("/wiki/(Special|Help|Wikipedia|File|Category|Talk|Portal|Template)[:：]").containsMatchIn(it.url) }
            .filter { DfySafety.urlBlock(it.url, false) == null }.take(2)
        val pages = if (res.text.length > 800 && res.url.contains("/wiki/")) listOf(res) else links.map { l -> Politeness.pace(l.url); HttpReader.read(l.url) }
        for (p in pages) {
            if (calls.left <= 0 || p.blocked != null || DfySafety.pageBlock(p.title, p.text) != null || p.text.length < 300) continue
            calls.left--
            val o = runCatching { DfyLlm.askOnce(ctx, "你是认真做笔记的学生，只根据原文。",
                "学习目标：「${g.title}」。从这页摘 1~3 条可靠要点（自己的话，一句一条），无关就返回空。只输出 JSON：{\"points\":[\"…\"]}\n标题：${p.title}\n正文：${p.text.take(3500)}", 400, 0.3, st.settings.preferFreeModels) }
                .getOrNull()?.let { DfyLlm.extractJson(it) } ?: continue
            DfyKnowledge.addNotes(ctx, g.id, p, o.optJSONArray("points")?.strings()?.take(3) ?: emptyList())
        }
    }

    // ------------------------------------------------------------------ live

    private suspend fun checkLive(ctx: Context, calls: Budget, now: Long): String {
        val st = DfyStore.snapshot(ctx)
        val uids = (st.settings.liveUids + st.todos.filter { !it.done && it.kind == "live" }.mapNotNull { it.text.trim().toLongOrNull() }).distinct()
        val rooms = LinkedHashMap<Long, LiveRoom>()
        runCatching { BiliApi.liveByUids(ctx, uids) }.getOrDefault(emptyList()).filter { it.live }.forEach { rooms[it.roomId] = it }
        if (st.settings.liveUseLoginFollows) runCatching { BiliApi.liveFollowed(ctx) }.getOrDefault(emptyList()).forEach { rooms[it.roomId] = it }
        val core = st.settings.coreInterests.toSet()
        runCatching { BiliApi.liveHot(ctx) }.getOrDefault(emptyList())
            .filter { r -> DfyInterests.topicsOf(r.title + " " + r.area).any { it in core || st.interests.any { e -> e.topic == it } } }
            .take(4).forEach { rooms.putIfAbsent(it.roomId, it) }
        val list = rooms.values.take(12).toList()
        val summary = if (list.isEmpty()) "" else if (calls.left > 0) {
            calls.left--
            val lines = list.joinToString("\n") { "${it.uname}：${it.title}（${it.area}，${it.online} 人气）" }
            runCatching {
                DfyLlm.askOnce(ctx, persona(ctx, st, now), "你看了一眼正在直播的房间：\n$lines\n用一两句话、你的口吻，说说现在谁在播什么、你最想点进哪个。不要编造房间里发生的事。", 300, 0.8, st.settings.preferFreeModels)
            }.getOrNull()?.let { clean(it, 240) } ?: list.take(3).joinToString("；") { "${it.uname} 在播「${it.title}」" }
        } else list.take(3).joinToString("；") { "${it.uname} 在播「${it.title}」" }
        DfyStore.update(ctx) { s ->
            s.copy(
                live = s.live + LiveSnapshot(now, list, summary),
                episodes = if (list.isEmpty()) s.episodes else s.episodes + DfyMemory.newEpisode(EpKind.LIVE, now, "我看了一眼直播：$summary",
                    "我看过一眼直播，有几个熟悉的主播在播", "我隐约记得看过直播"),
                todos = s.todos.map { t -> if (!t.done && t.kind == "live") {
                    val r = list.find { it.uid.toString() == t.text.trim() }
                    t.copy(done = true, doneAt = now, result = r?.let { "${it.uname} 正在播「${it.title}」" } ?: "看了，TA 现在没在播")
                } else t },
            )
        }
        return summary
    }

    // ------------------------------------------------------------------ reflection

    private val INSIGHT_OK = Regex("(发现|意识|明白|理解|学会|体会|在意|重要|愿意|值得|珍惜|希望|让我|看来|原来|感受到)")
    private val BEHAVIOR_OK = Regex("(以后|下次|今后|遇到|面对|我会|我希望|我愿意|我不再|我想)")

    /** Turn pending experiences into long-term insights. [force] = user tapped 立即反思. */
    suspend fun reflect(ctx: Context, force: Boolean, calls: Any? = null): String {
        val now = System.currentTimeMillis()
        val st = decay(ctx, now)
        val s = st.settings
        val meta = st.meta.forToday(DfyStore.today(now))
        val pool = st.pending.filter { it.status == "pending" }
        if (pool.isEmpty()) return "没有待反思的经历"
        if (!force) {
            if (pool.size < s.reflectMinPending) return "继续积累经历（${pool.size}/${s.reflectMinPending}）"
            if (meta.reflectBatchesToday >= s.reflectDailyBatches) return "今天已经反思过了"
            if (now - meta.lastReflectionAt < 3 * 3_600_000L) return "还没到下一次反思时间"
        }
        (calls as? Budget)?.let { if (it.left <= 0) return "本轮模型额度已用完" else it.left-- }
        val byId = st.episodes.associateBy { it.id }
        val batch = pool.mapNotNull { p -> byId[p.episodeId]?.takeIf { it.stage != MemStage.FORGOTTEN }?.let { p to it } }.take(8)
        if (batch.isEmpty()) return "所选经历都已自然遗忘"
        val cand = batch.mapIndexed { i, (_, e) -> "c-${i + 1} [${e.stage.zh}] ${e.recallText()}" }.joinToString("\n")
        val existing = st.insights.filter { !it.faded }.takeLast(10).joinToString("\n") { "- " + it.insight }
        val user = "下面是你最近仍然记得的经历（候选）：\n$cand\n\n你已有的长期认识：\n${existing.ifBlank { "（暂无）" }}\n\n" +
            "目标不是复述，而是找出真实经历带来的新领悟、以及将来可能改变的行为；大多数普通经历不该形成长期认识，证据不足就返回空列表。" +
            "insight 用“我发现/我意识到/我明白/这让我……”等第一人称表达认知变化（至少 9 个字）；behavior 说明以后可能怎么做（至少 8 个字）。" +
            "不能把看过资料写成看完、更不能写成自己做过视频。最多 3 条。只输出 JSON：" +
            "{\"memories\":[{\"candidate_ids\":[\"c-1\"],\"insight\":\"我发现……\",\"behavior\":\"以后我……\",\"kind\":\"experience|interest|style\",\"importance\":0.5,\"tags\":[\"…\"]}]," +
            "\"note\":\"一句很短的反思说明\",\"mood\":\"calm\",\"intensity\":1}"
        val reply = runCatching { DfyLlm.askOnce(ctx, persona(ctx, st, now), user, 900, 0.6, s.preferFreeModels) }
            .getOrElse { e ->
                DfyStore.update(ctx) { x -> x.copy(pending = x.pending.map { p -> if (batch.any { it.first.id == p.id }) p.copy(retries = p.retries + 1, note = "反思失败：" + clean(e.message, 80)) else p }) }
                return "反思失败：" + clean(e.message, 80)
            }
        val o = DfyLlm.extractJson(reply) ?: return "模型没有给出可解析的结果"
        val made = mutableListOf<Insight>()
        val reinforced = mutableSetOf<String>()
        val rows = o.optJSONArray("memories") ?: JSONArray()
        for (k in 0 until min(3, rows.length())) {
            val r = rows.optJSONObject(k) ?: continue
            val insight = clean(r.optString("insight"), 300); val behavior = clean(r.optString("behavior"), 300)
            if (insight.length < 9 || !INSIGHT_OK.containsMatchIn(insight)) continue
            if (behavior.length < 8 || !BEHAVIOR_OK.containsMatchIn(behavior)) continue
            val ids = r.optJSONArray("candidate_ids")?.strings()?.mapNotNull { it.removePrefix("c-").toIntOrNull()?.minus(1) }
                ?.filter { it in batch.indices }?.map { batch[it].second.id } ?: emptyList()
            if (ids.isEmpty()) continue // an insight must point at real, still-remembered evidence
            val dup = st.insights.firstOrNull { !it.faded && similar(it.insight, insight) }
            if (dup != null) { reinforced += dup.id; continue }
            made += Insight("i-" + java.util.UUID.randomUUID().toString().take(10), insight, behavior,
                r.optString("kind").takeIf { it in setOf("experience", "interest", "style", "relationship") } ?: "experience",
                r.optDouble("importance", 0.5).coerceIn(0.2, 0.9), now, tags = r.optJSONArray("tags")?.strings()?.take(5) ?: emptyList(), evidence = ids)
        }
        val mood = o.optString("mood").takeIf { it in DfyMood.MOODS && it != "neutral" }
        val note = clean(o.optString("note"), 120)
        val usedIds = batch.map { it.second.id }.toSet()
        DfyStore.update(ctx) { x ->
            x.copy(
                insights = x.insights.map { if (it.id in reinforced) it.copy(reinforcedAt = now, reinforceCount = it.reinforceCount + 1, faded = false) else it } + made,
                pending = x.pending.map { p -> if (batch.any { it.first.id == p.id }) p.copy(status = "reflected", note = if (made.isEmpty()) (note.ifBlank { "评估过，没有形成新认识" }) else "已形成长期认识") else p },
                episodes = x.episodes.map { if (it.id in usedIds && made.any { m -> it.id in m.evidence }) it.copy(reinforcedAt = now, reinforceCount = it.reinforceCount + 1) else it },
                mood = if (mood != null) DfyMood.apply(x.mood, mood, o.optInt("intensity", 1), "reflection", now, note) else x.mood,
                meta = x.meta.forToday(DfyStore.today(now)).let { m -> m.copy(reflectBatchesToday = m.reflectBatchesToday + 1, lastReflectionAt = now) },
            )
        }
        return if (made.isEmpty()) "反思完成，没有形成新认识" + (if (reinforced.isNotEmpty()) "（加深了 ${reinforced.size} 条旧认识）" else "") else "形成 ${made.size} 条新认识"
    }

    private fun similar(a: String, b: String): Boolean {
        fun bg(s: String) = s.replace(Regex("\\s+"), "").windowed(2).toSet()
        val x = bg(a); val y = bg(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x.intersect(y).size.toDouble() / max(1, min(x.size, y.size)) > 0.6
    }
}
