package com.dl1991.feiyu.dafeiyu

import android.content.Context
import java.util.UUID

/**
 * B站 write actions (like / comment / follow / DM). Every gate is checked, in order, both when an
 * action is proposed and again right before it is sent:
 *   1. master switch + per-action switch (all OFF by default),
 *   2. the user's own login cookie,
 *   3. risk-control cooldown (any captcha / verification answer pauses ALL writes for 24 h —
 *      we never try to solve or bypass it),
 *   4. dedupe (one like/comment per video, one follow per UP, no repeated DM text),
 *   5. daily quota per action,
 *   6. DM recipients must be on the user's DM allowlist,
 *   7. confirmation: unless the user switched on full-auto, the action waits in 待确认.
 * A send only counts as done on B站's own `code == 0`.
 */
object BiliActions {
    const val LIKE = "like"; const val COMMENT = "comment"; const val FOLLOW = "follow"; const val DM = "dm"
    val TYPES = listOf(LIKE, COMMENT, FOLLOW, DM)
    fun label(t: String) = when (t) { LIKE -> "点赞"; COMMENT -> "评论"; FOLLOW -> "关注"; DM -> "私信"; else -> t }

    private fun allowed(s: DfySettings, type: String): Boolean = s.writeEnabled && when (type) {
        LIKE -> s.allowLike; COMMENT -> s.allowComment; FOLLOW -> s.allowFollow; DM -> s.allowDm; else -> false
    }
    private fun quota(s: DfySettings, type: String): Int = when (type) {
        LIKE -> s.quotaLike; COMMENT -> s.quotaComment; FOLLOW -> s.quotaFollow; DM -> s.quotaDm; else -> 0
    }

    /** Why this action may not go out right now, or null. */
    fun blocker(ctx: Context, st: DfyState, a: BiliAction, now: Long, ignorePendingSelf: Boolean = false): String? {
        val s = st.settings
        if (!allowed(s, a.type)) return "未开启「${label(a.type)}」权限"
        if (!BiliAuth.isLoggedIn(ctx)) return "未登录 B站"
        if (st.meta.writeCooldownUntil > now) return "B站要求验证/风控，对外操作暂停中：" + st.meta.writeCooldownReason
        val dup = st.actions.any { it.id != a.id && it.dedupeKey == a.dedupeKey && (it.status == "done" || (!ignorePendingSelf && it.status in setOf("pending", "approved"))) }
        if (dup) return "重复操作，已跳过"
        val used = st.meta.forToday(DfyStore.today(now)).writesToday[a.type] ?: 0
        if (used >= quota(s, a.type)) return "今日${label(a.type)}额度已用完（${quota(s, a.type)}）"
        if (a.type == DM && a.target.toLongOrNull() !in s.dmAllowlist) return "私信对象不在允许名单中"
        if (a.type == COMMENT || a.type == DM) {
            if (a.content.isBlank() || a.content.length > 300) return "内容为空或过长"
            if (POLITICS.containsMatchIn(a.content)) return "内容涉及敏感话题，已拦截"
        }
        return null
    }

    private val POLITICS = Regex("(习近平|共产党|台独|港独|六四|法轮|政府|政治|选举|总统|主席)")

    /**
     * Propose an action from the life loop / chat / agent. Returns a short status:
     * "dropped:<why>" (gate failed), "pending" (waits for the user), or the execute() result.
     */
    suspend fun propose(ctx: Context, type: String, target: String, targetLabel: String, content: String, reason: String): String {
        val now = System.currentTimeMillis()
        val a = BiliAction(UUID.randomUUID().toString().take(10), type, target, clean(targetLabel, 80), clean(content, 300), clean(reason, 160), now, "pending")
        val st = DfyStore.snapshot(ctx)
        blocker(ctx, st, a, now)?.let { return "dropped:$it" }
        DfyStore.update(ctx) { it.copy(actions = it.actions + a) }
        if (!st.settings.fullAuto) {
            DfyNotify.pendingAction(ctx, a)
            return "pending"
        }
        return execute(ctx, a.id)
    }

    suspend fun reject(ctx: Context, id: String) {
        DfyStore.update(ctx) { s -> s.copy(actions = s.actions.map { if (it.id == id && it.status == "pending") it.copy(status = "rejected", resultAt = System.currentTimeMillis()) else it }) }
    }

    /** User tapped 允许 (or full-auto). Re-checks every gate, then sends. */
    suspend fun execute(ctx: Context, id: String): String {
        val now = System.currentTimeMillis()
        val st = DfyStore.snapshot(ctx)
        val a = st.actions.find { it.id == id } ?: return "not-found"
        if (a.status !in setOf("pending", "approved")) return a.status
        blocker(ctx, st, a, now, ignorePendingSelf = true)?.let { why ->
            mark(ctx, id, "blocked", why); return "blocked:$why"
        }
        val res = runCatching {
            when (a.type) {
                LIKE -> BiliApi.like(ctx, a.target)
                COMMENT -> {
                    val aid = BiliApi.view(ctx, a.target)?.aid ?: throw BiliException(-404, "视频不存在")
                    BiliApi.comment(ctx, aid, a.content)
                }
                FOLLOW -> BiliApi.follow(ctx, a.target.toLong())
                DM -> BiliApi.dm(ctx, a.target.toLong(), a.content)
                else -> throw BiliException(-1, "未知操作")
            }
        }
        val err = res.exceptionOrNull()
        if (err == null) {
            DfyStore.update(ctx) { s ->
                val m = s.meta.forToday(DfyStore.today(now))
                val ep = DfyMemory.newEpisode(EpKind.SOCIAL, now,
                    "我在 B站${label(a.type)}了「${a.targetLabel}」" + (if (a.content.isNotBlank()) "，写的是：${a.content}" else "") + (if (a.reason.isNotBlank()) "（因为${a.reason}）" else ""),
                    "我好像在 B站${label(a.type)}过一个喜欢的内容", "我隐约记得在 B站和外面打过招呼",
                    bvid = if (a.type == LIKE || a.type == COMMENT) a.target else "", title = a.targetLabel)
                s.copy(
                    actions = s.actions.map { if (it.id == id) it.copy(status = "done", resultAt = now, resultMsg = "B站已确认") else it },
                    meta = m.copy(writesToday = m.writesToday + (a.type to ((m.writesToday[a.type] ?: 0) + 1))),
                    episodes = s.episodes + ep,
                    pending = s.pending + PendingItem("p-" + ep.id, ep.id, now),
                )
            }
            return "done"
        }
        val be = err as? BiliException
        val msg = clean(err.message ?: "失败", 120)
        when {
            be?.isRiskControl == true -> DfyStore.update(ctx) { s ->
                s.copy(meta = s.meta.copy(writeCooldownUntil = now + 24 * 3_600_000L, writeCooldownReason = "${be.code} $msg"),
                    actions = s.actions.map { if (it.id == id) it.copy(status = "blocked", resultAt = now, resultMsg = "风控/需要验证：$msg（不会自动处理验证码）") else it })
            }
            be?.isAuth == true -> { BiliAuth.refreshState(ctx); mark(ctx, id, "failed", "登录失效，请重新登录：$msg") }
            else -> mark(ctx, id, "failed", msg) // uncertain results are never retried automatically
        }
        return "failed:$msg"
    }

    private suspend fun mark(ctx: Context, id: String, status: String, msg: String) {
        DfyStore.update(ctx) { s -> s.copy(actions = s.actions.map { if (it.id == id) it.copy(status = status, resultAt = System.currentTimeMillis(), resultMsg = msg) else it }) }
    }
}
