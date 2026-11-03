package com.dl1991.feiyu.dafeiyu

import org.json.JSONArray
import org.json.JSONObject

/*
 * 大肥鱼人生 (DaFeiYu Life) — native Mibot port of the ideas in
 * minedalang/dsh-dafeiyu-life (MIT, © 2026 c1overzz and contributors; see
 * assets/mibot/dafeiyu/NOTICE.md). Kotlin re-implementation for Mibot (GPL-3.0).
 *
 * Data model. Everything is plain JSON in the app sandbox (filesDir/dafeiyu):
 *  - Episode      : layer 1, the exact experience log. Decays clear → fuzzy → trace → forgotten;
 *                   a forgotten episode loses its text for good (no resurrection from the ledger).
 *  - PendingItem  : layer 2, the pending-reflection pool (references live episodes only).
 *  - Insight      : layer 3, long-term identity memory distilled by reflection (slow decay,
 *                   reinforced when re-confirmed); owner-written core facts never decay.
 *  - PickRecord   : owner-facing ledger for the timeline (what was picked/skipped and when).
 *                   The model never reads it back — her memory goes through Episode decay only.
 */

internal fun JSONObject.optStr(k: String): String = if (isNull(k)) "" else optString(k, "")
internal fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
internal fun List<String>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }
internal fun clean(s: String?, max: Int = 240): String =
    (s ?: "").replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

enum class MemStage(val zh: String) { CLEAR("清晰"), FUZZY("模糊"), TRACE("隐约"), FORGOTTEN("已遗忘") }

/** Episode kinds. */
object EpKind {
    const val WATCH = "watch"       // read a video's public material and chose it
    const val SKIP = "skip"         // looked, decided not to
    const val LIVE = "live"         // glanced at live rooms
    const val CHAT = "chat"         // owner conversation (private)
    const val SOCIAL = "social"     // a confirmed write action (like/comment/follow/dm)
    const val TODO = "todo"         // something the owner asked her to do
}

data class Episode(
    val id: String,
    val kind: String,
    val at: Long,
    var stage: MemStage = MemStage.CLEAR,
    var text: String,
    var fuzzy: String,
    var trace: String,
    var importance: Double,
    var reinforcedAt: Long = at,
    var reinforceCount: Int = 0,
    /** Only kept while CLEAR. */
    var bvid: String = "",
    var title: String = "",
    var topics: List<String> = emptyList(),
    var mood: String = "",
    /** "public" (B站 life) or "private" (owner chat). */
    val visibility: String = "public",
) {
    /** The text she can currently recall for this stage ("" when forgotten). */
    fun recallText(): String = when (stage) {
        MemStage.CLEAR -> text
        MemStage.FUZZY -> fuzzy
        MemStage.TRACE -> trace
        MemStage.FORGOTTEN -> ""
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("at", at).put("stage", stage.name)
        .put("text", text).put("fuzzy", fuzzy).put("trace", trace).put("importance", importance)
        .put("reinforcedAt", reinforcedAt).put("reinforceCount", reinforceCount).put("bvid", bvid)
        .put("title", title).put("topics", topics.toJsonArray()).put("mood", mood).put("visibility", visibility)

    companion object {
        fun from(o: JSONObject) = Episode(
            id = o.optStr("id"), kind = o.optStr("kind"), at = o.optLong("at"),
            stage = runCatching { MemStage.valueOf(o.optStr("stage")) }.getOrDefault(MemStage.CLEAR),
            text = o.optStr("text"), fuzzy = o.optStr("fuzzy"), trace = o.optStr("trace"),
            importance = o.optDouble("importance", 0.4), reinforcedAt = o.optLong("reinforcedAt", o.optLong("at")),
            reinforceCount = o.optInt("reinforceCount"), bvid = o.optStr("bvid"), title = o.optStr("title"),
            topics = o.optJSONArray("topics")?.strings() ?: emptyList(), mood = o.optStr("mood"),
            visibility = o.optStr("visibility").ifBlank { "public" },
        )
    }
}

data class PendingItem(
    val id: String,
    val episodeId: String,
    val createdAt: Long,
    var status: String = "pending", // pending | reflected | dropped
    var note: String = "",
    var retries: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("episodeId", episodeId).put("createdAt", createdAt)
        .put("status", status).put("note", note).put("retries", retries)

    companion object {
        fun from(o: JSONObject) = PendingItem(o.optStr("id"), o.optStr("episodeId"), o.optLong("createdAt"),
            o.optStr("status").ifBlank { "pending" }, o.optStr("note"), o.optInt("retries"))
    }
}

data class Insight(
    val id: String,
    var insight: String,
    var behavior: String,
    var kind: String,              // experience | interest | relationship | style | core
    var importance: Double,
    val createdAt: Long,
    var reinforcedAt: Long = createdAt,
    var reinforceCount: Int = 0,
    var tags: List<String> = emptyList(),
    var evidence: List<String> = emptyList(),
    /** Owner-written identity facts: never decay, never auto-edited. */
    var core: Boolean = false,
    var faded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("insight", insight).put("behavior", behavior)
        .put("kind", kind).put("importance", importance).put("createdAt", createdAt).put("reinforcedAt", reinforcedAt)
        .put("reinforceCount", reinforceCount).put("tags", tags.toJsonArray()).put("evidence", evidence.toJsonArray())
        .put("core", core).put("faded", faded)

    companion object {
        fun from(o: JSONObject) = Insight(o.optStr("id"), o.optStr("insight"), o.optStr("behavior"), o.optStr("kind").ifBlank { "experience" },
            o.optDouble("importance", 0.5), o.optLong("createdAt"), o.optLong("reinforcedAt", o.optLong("createdAt")),
            o.optInt("reinforceCount"), o.optJSONArray("tags")?.strings() ?: emptyList(),
            o.optJSONArray("evidence")?.strings() ?: emptyList(), o.optBoolean("core"), o.optBoolean("faded"))
    }
}

data class MoodEvent(val at: Long, val mood: String, val intensity: Int, val source: String, val summary: String = "") {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("mood", mood).put("intensity", intensity).put("source", source).put("summary", summary)
    companion object {
        fun from(o: JSONObject) = MoodEvent(o.optLong("at"), o.optStr("mood"), o.optInt("intensity", 1), o.optStr("source"), o.optStr("summary"))
    }
}

data class MoodState(
    var mood: String = "neutral",
    var intensity: Double = 0.0,
    var valence: Double = 0.0,
    var energy: Double = 56.0,
    var curiosity: Double = 45.0,
    var sociality: Double = 45.0,
    var updatedAt: Long = 0,
    val history: MutableList<MoodEvent> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().put("mood", mood).put("intensity", intensity).put("valence", valence)
        .put("energy", energy).put("curiosity", curiosity).put("sociality", sociality).put("updatedAt", updatedAt)
        .put("history", JSONArray().also { a -> history.takeLast(200).forEach { a.put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject) = MoodState(o.optStr("mood").ifBlank { "neutral" }, o.optDouble("intensity", 0.0), o.optDouble("valence", 0.0),
            o.optDouble("energy", 56.0), o.optDouble("curiosity", 45.0), o.optDouble("sociality", 45.0), o.optLong("updatedAt"),
            o.optJSONArray("history")?.let { a -> (0 until a.length()).map { MoodEvent.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf())
    }
}

data class InterestEvidence(val bvid: String, val at: Long)

data class InterestEntry(
    val topic: String,
    val firstSeen: Long,
    var lastSeen: Long,
    var peakStage: String = "",
    val evidence: MutableList<InterestEvidence> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().put("topic", topic).put("firstSeen", firstSeen).put("lastSeen", lastSeen).put("peakStage", peakStage)
        .put("evidence", JSONArray().also { a -> evidence.forEach { a.put(JSONObject().put("bvid", it.bvid).put("at", it.at)) } })

    companion object {
        fun from(o: JSONObject) = InterestEntry(o.optStr("topic"), o.optLong("firstSeen"), o.optLong("lastSeen"), o.optStr("peakStage"),
            o.optJSONArray("evidence")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { InterestEvidence(it.optStr("bvid"), it.optLong("at")) }.toMutableList() } ?: mutableListOf())
    }
}

/** Owner-facing ledger row for the timeline. */
data class PickRecord(
    val at: Long,
    val bvid: String,
    val title: String,
    val owner: String,
    val decision: String,  // watch | skip
    val note: String,
    val source: String,    // popular | search:<q> | related | rank | todo
    val cover: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("bvid", bvid).put("title", title).put("owner", owner)
        .put("decision", decision).put("note", note).put("source", source).put("cover", cover)
    companion object {
        fun from(o: JSONObject) = PickRecord(o.optLong("at"), o.optStr("bvid"), o.optStr("title"), o.optStr("owner"),
            o.optStr("decision"), o.optStr("note"), o.optStr("source"), o.optStr("cover"))
    }
}

data class LiveRoom(val uid: Long, val roomId: Long, val uname: String, val title: String, val area: String, val online: Long, val live: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("uid", uid).put("roomId", roomId).put("uname", uname).put("title", title)
        .put("area", area).put("online", online).put("live", live)
    companion object {
        fun from(o: JSONObject) = LiveRoom(o.optLong("uid"), o.optLong("roomId"), o.optStr("uname"), o.optStr("title"), o.optStr("area"), o.optLong("online"), o.optBoolean("live"))
    }
}

data class LiveSnapshot(val at: Long, val rooms: List<LiveRoom>, val summary: String) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("summary", summary)
        .put("rooms", JSONArray().also { a -> rooms.forEach { a.put(it.toJson()) } })
    companion object {
        fun from(o: JSONObject) = LiveSnapshot(o.optLong("at"),
            o.optJSONArray("rooms")?.let { a -> (0 until a.length()).map { LiveRoom.from(a.getJSONObject(it)) } } ?: emptyList(), o.optStr("summary"))
    }
}

/** Something the owner (or the Mibot agent on the owner's behalf) asked her to do. */
data class DfyTodo(
    val id: String,
    val text: String,
    /** search = look this up on B站 next run; note = just keep in mind; live = check this streamer (uid). */
    val kind: String,
    val createdAt: Long,
    var done: Boolean = false,
    var doneAt: Long = 0,
    var result: String = "",
    val from: String = "owner",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("kind", kind).put("createdAt", createdAt)
        .put("done", done).put("doneAt", doneAt).put("result", result).put("from", from)
    companion object {
        fun from(o: JSONObject) = DfyTodo(o.optStr("id"), o.optStr("text"), o.optStr("kind").ifBlank { "note" }, o.optLong("createdAt"),
            o.optBoolean("done"), o.optLong("doneAt"), o.optStr("result"), o.optStr("from").ifBlank { "owner" })
    }
}

data class ChatTurn(val at: Long, val role: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("role", role).put("text", text)
    companion object { fun from(o: JSONObject) = ChatTurn(o.optLong("at"), o.optStr("role"), o.optStr("text")) }
}

/** One proposed or executed B站 write action. */
data class BiliAction(
    val id: String,
    val type: String,      // like | comment | follow | dm
    val target: String,    // bvid for like/comment, mid for follow/dm
    val targetLabel: String,
    val content: String,   // comment / dm text
    val reason: String,
    val createdAt: Long,
    var status: String,    // pending | approved | rejected | done | failed | blocked
    var resultAt: Long = 0,
    var resultMsg: String = "",
) {
    val dedupeKey: String get() = when (type) {
        "dm" -> "dm:$target:${content.hashCode()}"
        else -> "$type:$target"
    }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("target", target).put("targetLabel", targetLabel)
        .put("content", content).put("reason", reason).put("createdAt", createdAt).put("status", status)
        .put("resultAt", resultAt).put("resultMsg", resultMsg)
    companion object {
        fun from(o: JSONObject) = BiliAction(o.optStr("id"), o.optStr("type"), o.optStr("target"), o.optStr("targetLabel"), o.optStr("content"),
            o.optStr("reason"), o.optLong("createdAt"), o.optStr("status"), o.optLong("resultAt"), o.optStr("resultMsg"))
    }
}
