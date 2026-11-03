package com.dl1991.feiyu.dafeiyu

import org.json.JSONArray
import org.json.JSONObject

/*
 * 冲浪 / 学知识 data. A SurfSession is one "broadcast": she picks (or is given) a topic, hops
 * across real web pages, narrates, answers viewers, and ends with a recap. A LearnGoal is the
 * knowledge-base side: notes with citations and a self-quiz, linked to long-term memory.
 */

object SurfMode { const val SURF = "surf"; const val LEARN = "learn" }

/** One page visit. */
data class Hop(
    val at: Long,
    val url: String,
    val title: String,
    val how: String,        // start | search | follow | deeper | switch | viewer
    val why: String,
    val topic: String,
    val ok: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("url", url).put("title", title).put("how", how)
        .put("why", why).put("topic", topic).put("ok", ok)
    companion object {
        fun from(o: JSONObject) = Hop(o.optLong("at"), o.optStr("url"), o.optStr("title"), o.optStr("how"), o.optStr("why"), o.optStr("topic"), o.optBoolean("ok", true))
    }
}

/** A bubble in the live room. kind: thought | viewer | reply | system | note | quiz */
data class Bubble(val at: Long, val kind: String, val text: String, val url: String = "") {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("kind", kind).put("text", text).put("url", url)
    companion object { fun from(o: JSONObject) = Bubble(o.optLong("at"), o.optStr("kind"), o.optStr("text"), o.optStr("url")) }
}

data class SurfSession(
    val id: String,
    val mode: String,
    var topic: String,
    var why: String,
    val startedAt: Long,
    var endedAt: Long = 0,
    var status: String = "live",          // live | paused | ended
    var endReason: String = "",
    val trail: MutableList<String> = mutableListOf(),   // topic breadcrumbs
    val hops: MutableList<Hop> = mutableListOf(),
    val bubbles: MutableList<Bubble> = mutableListOf(),
    var recap: String = "",
    var highlights: List<String> = emptyList(),
    var goalId: String = "",
    var modelCalls: Int = 0,
    var approxTokens: Int = 0,
    var startedBy: String = "self",       // self | viewer | schedule | agent
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("mode", mode).put("topic", topic).put("why", why)
        .put("startedAt", startedAt).put("endedAt", endedAt).put("status", status).put("endReason", endReason)
        .put("trail", trail.toJsonArray())
        .put("hops", JSONArray().also { a -> hops.takeLast(120).forEach { a.put(it.toJson()) } })
        .put("bubbles", JSONArray().also { a -> bubbles.takeLast(300).forEach { a.put(it.toJson()) } })
        .put("recap", recap).put("highlights", highlights.toJsonArray()).put("goalId", goalId)
        .put("modelCalls", modelCalls).put("approxTokens", approxTokens).put("startedBy", startedBy)

    fun deepCopy(): SurfSession = copy(trail = trail.toMutableList(), hops = hops.toMutableList(), bubbles = bubbles.toMutableList())

    companion object {
        fun from(o: JSONObject) = SurfSession(
            o.optStr("id"), o.optStr("mode").ifBlank { SurfMode.SURF }, o.optStr("topic"), o.optStr("why"), o.optLong("startedAt"),
            o.optLong("endedAt"), o.optStr("status").ifBlank { "ended" }, o.optStr("endReason"),
            (o.optJSONArray("trail")?.strings() ?: emptyList()).toMutableList(),
            o.optJSONArray("hops")?.let { a -> (0 until a.length()).map { Hop.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf(),
            o.optJSONArray("bubbles")?.let { a -> (0 until a.length()).map { Bubble.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf(),
            o.optStr("recap"), o.optJSONArray("highlights")?.strings() ?: emptyList(), o.optStr("goalId"),
            o.optInt("modelCalls"), o.optInt("approxTokens"), o.optStr("startedBy").ifBlank { "self" },
        )
    }
}

data class KNote(val id: String, val text: String, val sourceUrl: String, val sourceTitle: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("sourceUrl", sourceUrl).put("sourceTitle", sourceTitle).put("at", at)
    companion object { fun from(o: JSONObject) = KNote(o.optStr("id"), o.optStr("text"), o.optStr("sourceUrl"), o.optStr("sourceTitle"), o.optLong("at")) }
}

data class KQuiz(val q: String, val expected: String, val herAnswer: String, val correct: Boolean, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("q", q).put("expected", expected).put("herAnswer", herAnswer).put("correct", correct).put("at", at)
    companion object { fun from(o: JSONObject) = KQuiz(o.optStr("q"), o.optStr("expected"), o.optStr("herAnswer"), o.optBoolean("correct"), o.optLong("at")) }
}

data class KSource(val url: String, val title: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title).put("at", at)
    companion object { fun from(o: JSONObject) = KSource(o.optStr("url"), o.optStr("title"), o.optLong("at")) }
}

data class LearnGoal(
    val id: String,
    var title: String,
    var why: String,
    val createdAt: Long,
    val suggestedBy: String = "self",      // self | owner | agent
    var status: String = "learning",       // learning | learned | paused
    val sources: MutableList<KSource> = mutableListOf(),
    val notes: MutableList<KNote> = mutableListOf(),
    val quiz: MutableList<KQuiz> = mutableListOf(),
    var summary: String = "",
    var insightId: String = "",
    var updatedAt: Long = createdAt,
) {
    val score: Int get() = if (quiz.isEmpty()) -1 else quiz.count { it.correct } * 100 / quiz.size

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("why", why).put("createdAt", createdAt)
        .put("suggestedBy", suggestedBy).put("status", status)
        .put("sources", JSONArray().also { a -> sources.takeLast(60).forEach { a.put(it.toJson()) } })
        .put("notes", JSONArray().also { a -> notes.takeLast(200).forEach { a.put(it.toJson()) } })
        .put("quiz", JSONArray().also { a -> quiz.takeLast(40).forEach { a.put(it.toJson()) } })
        .put("summary", summary).put("insightId", insightId).put("updatedAt", updatedAt)

    fun deepCopy(): LearnGoal = copy(sources = sources.toMutableList(), notes = notes.toMutableList(), quiz = quiz.toMutableList())

    companion object {
        fun from(o: JSONObject) = LearnGoal(
            o.optStr("id"), o.optStr("title"), o.optStr("why"), o.optLong("createdAt"), o.optStr("suggestedBy").ifBlank { "self" },
            o.optStr("status").ifBlank { "learning" },
            o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { KSource.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf(),
            o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { KNote.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf(),
            o.optJSONArray("quiz")?.let { a -> (0 until a.length()).map { KQuiz.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf(),
            o.optStr("summary"), o.optStr("insightId"), o.optLong("updatedAt", o.optLong("createdAt")),
        )
    }
}
