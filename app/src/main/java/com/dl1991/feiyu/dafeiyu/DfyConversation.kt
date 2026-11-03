package com.dl1991.feiyu.dafeiyu

import kotlin.math.max

/**
 * Conversation manager: turns the raw event stream into prioritised "things to respond to".
 * Similar comments within 30 s are merged; enters / likes / follows are batched; cheers only
 * lift the room's energy; harmful content is never queued for a reply that would repeat it.
 */
class ConvItem(
    val id: Long,
    val kind: String,          // comment | greet | thanks_like | thanks_follow | gift | directive | poll_result | quiz_answer | guess_win | owner | safety
    var basePriority: Int,
    val createdAt: Long,
    val events: MutableList<LiveEvent> = mutableListOf(),
    var note: String = "",
) {
    fun priority(now: Long): Int = basePriority + ((now - createdAt) / 3000).toInt() // waiting raises priority
    fun expired(now: Long): Boolean = now - createdAt > when (kind) { "gift", "owner" -> 120_000L; "greet", "thanks_like", "thanks_follow" -> 30_000L; else -> 50_000L }
}

data class Poll(val question: String, val options: List<String>, val openedAt: Long, val closesAt: Long, val votes: MutableMap<String, Int> = mutableMapOf()) {
    fun tally(): List<Int> = options.indices.map { i -> votes.values.count { it == i } }
}
data class QuizState(val question: String, val answer: String, val openedAt: Long, var winner: String = "")
data class GuessState(val answer: String, val hint: String, val openedAt: Long, var winner: String = "")

class ConversationManager(private val ownerKey: String = "owner") {
    private val items = mutableListOf<ConvItem>()
    private var seq = 0L
    private var greetBatch: ConvItem? = null
    private var likeBatch: ConvItem? = null
    private var followBatch: ConvItem? = null
    var cheers = 0; private set
    var likesTotal = 0; private set
    var poll: Poll? = null
    var quiz: QuizState? = null
    var guess: GuessState? = null
    private var lastSafetyReplyAt = 0L

    private val QUESTION = Regex("[?？]|吗|呢|怎么|为什么|什么|哪|谁|多少|能不能|可以吗|会不会")
    private val MENTION = Regex("鱼鱼|大肥鱼|肥鱼|@")
    private val DIRECTIVE = Regex("(带我们|带大家|去看|去搜|搜一下|搜搜|查一下|打开|看看.{1,20}(吧|呗)|投票|考考|出题|猜谜|玩个|换个话题|讲讲|学一下)")
    val PRAISE = Regex("(可爱|厉害|好棒|喜欢你|爱了|牛|聪明|萌|好听|绝了|太强)")
    val TEASE = Regex("(胖|笨|摸鱼|答不上|说错|菜|呆|傻鱼)")

    @Synchronized fun size() = items.size

    private fun bg(t: String) = t.lowercase().replace(Regex("\\s+"), "").windowed(2).toSet()
    private fun similar(a: String, b: String): Boolean {
        val x = bg(a); val y = bg(b); if (x.isEmpty() || y.isEmpty()) return a.trim() == b.trim()
        return x.intersect(y).size.toDouble() / max(1, minOf(x.size, y.size)) >= 0.6
    }

    /** Result of ingesting one event, for the engine's mood / memory side effects. */
    data class Ingest(val verdict: DfySafety.Verdict, val praise: Boolean, val tease: Boolean, val queued: Boolean, val bargeIn: Boolean)

    @Synchronized
    fun ingest(e: LiveEvent, level: String, now: Long = System.currentTimeMillis()): Ingest {
        when (e.type) {
            EvType.ENTER -> {
                val b = greetBatch?.takeIf { it in items } ?: ConvItem(++seq, "greet", 18, now).also { greetBatch = it; items += it }
                if (b.events.none { it.userKey == e.userKey }) b.events += e
                if (level != "新观众") b.basePriority = max(b.basePriority, 26)
                return Ingest(DfySafety.Verdict.OK, false, false, true, false)
            }
            EvType.LIKE -> {
                likesTotal += max(1, e.giftCount)
                val b = likeBatch?.takeIf { it in items } ?: ConvItem(++seq, "thanks_like", 6, now).also { likeBatch = it; items += it }
                b.events += e; if (b.events.sumOf { max(1, it.giftCount) } >= 20) b.basePriority = 16
                return Ingest(DfySafety.Verdict.OK, true, false, true, false)
            }
            EvType.FOLLOW -> {
                val b = followBatch?.takeIf { it in items } ?: ConvItem(++seq, "thanks_follow", 24, now).also { followBatch = it; items += it }
                b.events += e
                return Ingest(DfySafety.Verdict.OK, true, false, true, false)
            }
            EvType.GIFT -> {
                val same = items.find { it.kind == "gift" && it.events.firstOrNull()?.userKey == e.userKey }
                val pr = 40 + minOf(40, e.giftValue / 50)
                if (same != null) { same.events += e; same.basePriority = max(same.basePriority, pr) } else items += ConvItem(++seq, "gift", pr, now, mutableListOf(e))
                return Ingest(DfySafety.Verdict.OK, true, false, true, e.giftValue >= 100)
            }
            EvType.SHARE -> { cheers += 2; return Ingest(DfySafety.Verdict.OK, true, false, false, false) }
        }
        // comments
        val text = e.text.trim()
        val v = DfySafety.checkIncoming(text)
        when (v) {
            DfySafety.Verdict.SELF_HARM -> { items += ConvItem(++seq, "safety", 90, now, mutableListOf(e), "self_harm"); return Ingest(v, false, false, true, true) }
            DfySafety.Verdict.HARMFUL, DfySafety.Verdict.INJECTION -> {
                // Never repeat or act on it; at most one generic in-character deflection a minute.
                if (now - lastSafetyReplyAt > 60_000) { lastSafetyReplyAt = now; items += ConvItem(++seq, "safety", 12, now, mutableListOf(e.copy(text = "")), if (v == DfySafety.Verdict.INJECTION) "injection" else "harmful") }
                return Ingest(v, false, false, false, false)
            }
            DfySafety.Verdict.SPAM -> return Ingest(v, false, false, false, false)
            DfySafety.Verdict.ABUSE -> {
                // Rude: no queued reply of the insult itself; the engine lowers mood/affinity.
                if (now - lastSafetyReplyAt > 45_000) { lastSafetyReplyAt = now; items += ConvItem(++seq, "comment", 14, now, mutableListOf(e.copy(text = "（一句不友善的话，内容不复述）")), "rude") }
                return Ingest(v, false, false, false, false)
            }
            DfySafety.Verdict.OK -> {}
        }
        // poll / quiz / guess answers
        poll?.let { p ->
            if (now < p.closesAt) {
                val idx = text.trim().toIntOrNull()?.minus(1)?.takeIf { it in p.options.indices }
                    ?: p.options.indexOfFirst { o -> o.isNotBlank() && text.contains(o) }.takeIf { it >= 0 }
                if (idx != null) { p.votes[e.userKey] = idx; return Ingest(v, false, false, false, false) }
            }
        }
        quiz?.let { q -> if (q.winner.isEmpty() && (text.contains(q.answer) || similar(text, q.answer))) { q.winner = e.nick; items += ConvItem(++seq, "quiz_answer", 45, now, mutableListOf(e)) ; return Ingest(v, true, false, true, true) } }
        guess?.let { g -> if (g.winner.isEmpty() && text.contains(g.answer)) { g.winner = e.nick; items += ConvItem(++seq, "guess_win", 45, now, mutableListOf(e)); return Ingest(v, true, false, true, true) } }
        if (DfySafety.isCheer(text)) { cheers++; return Ingest(v, true, false, false, false) }
        val praise = PRAISE.containsMatchIn(text); val tease = TEASE.containsMatchIn(text)
        val owner = e.userKey == ownerKey
        val directive = DIRECTIVE.containsMatchIn(text)
        var pr = 20
        if (QUESTION.containsMatchIn(text)) pr += 12
        if (MENTION.containsMatchIn(text)) pr += 10
        if (directive) pr += 16
        pr += when (level) { "老粉" -> 14; "常客" -> 10; "见过几次" -> 5; "冷淡" -> -8; else -> 6 }
        if (owner) pr += 30
        // merge with a similar pending comment
        val merged = items.find { it.kind in setOf("comment", "directive") && now - it.createdAt < 30_000 && it.events.any { x -> similar(x.text, text) } }
        if (merged != null) { merged.events += e; merged.basePriority = max(merged.basePriority, pr) + 3 }
        else items += ConvItem(++seq, if (owner) "owner" else if (directive) "directive" else "comment", pr, now, mutableListOf(e))
        return Ingest(v, praise, tease, true, pr >= 36)
    }

    /** Highest-priority live item, or null. Expired items are dropped. */
    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): ConvItem? {
        items.removeAll { it.expired(now) || (it.events.isEmpty() && it.note.isEmpty()) }
        // batches wait a little to gather (enter 6 s, likes 25 s, follows 8 s) unless the room is quiet
        val ready = items.filter { when (it.kind) {
            "greet" -> now - it.createdAt > 6000; "thanks_like" -> now - it.createdAt > 25_000 || it.basePriority >= 16
            "thanks_follow" -> now - it.createdAt > 8000; else -> true } }
        val best = ready.maxByOrNull { it.priority(now) } ?: return null
        items.remove(best)
        if (best === greetBatch) greetBatch = null
        if (best === likeBatch) likeBatch = null
        if (best === followBatch) followBatch = null
        return best
    }

    @Synchronized fun pushSystem(kind: String, priority: Int, note: String) { items += ConvItem(++seq, kind, priority, System.currentTimeMillis(), note = note) }

    @Synchronized fun takeCheers(): Int { val c = cheers; cheers = 0; return c }

    /** Close an expired poll; returns its result line or null. */
    @Synchronized
    fun closePollIfDue(now: Long = System.currentTimeMillis()): String? {
        val p = poll ?: return null
        if (now < p.closesAt) return null
        poll = null
        val t = p.tally(); val total = t.sum()
        if (total == 0) return "投票「${p.question}」没人投"
        val win = t.indices.maxByOrNull { t[it] } ?: 0
        return "投票「${p.question}」结果：" + p.options.mapIndexed { i, o -> "$o ${t[i]}票" }.joinToString("，") + "；胜出：${p.options[win]}"
    }
}
