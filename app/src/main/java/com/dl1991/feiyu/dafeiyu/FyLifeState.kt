package com.dl1991.feiyu.dafeiyu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * 「养她自己」— her persistent life state as a streamer: goals, interests (weights moved by real
 * engagement and her own curiosity), skills (knowledge notes from pages she actually read), a diary
 * written after each stream, relationships with regulars, and the plan she proposes for the next
 * stream. Everything here comes from logged experience; nothing is invented. Pure Kotlin + org.json.
 */

data class LifeGoal(val id: String, val text: String, val horizon: String, val progress: Int = 0,
                    val status: String = "active", val createdAt: Long = 0, val doneAt: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("horizon", horizon).put("progress", progress)
        .put("status", status).put("createdAt", createdAt).put("doneAt", doneAt)
    companion object { fun from(o: JSONObject) = LifeGoal(o.optString("id"), o.optString("text"), o.optString("horizon", "short"),
        o.optInt("progress"), o.optString("status", "active"), o.optLong("createdAt"), o.optLong("doneAt")) }
}

data class LifeInterest(val topic: String, val weight: Double = 50.0, val engagement: Double = 0.0, val curiosity: Double = 0.0,
                        val samples: Int = 0, val lastAt: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("topic", topic).put("weight", weight).put("engagement", engagement)
        .put("curiosity", curiosity).put("samples", samples).put("lastAt", lastAt)
    companion object { fun from(o: JSONObject) = LifeInterest(o.optString("topic"), o.optDouble("weight", 50.0), o.optDouble("engagement", 0.0),
        o.optDouble("curiosity", 0.0), o.optInt("samples"), o.optLong("lastAt")) }
}

/** A knowledge note from a page she read (always cited). */
data class LifeSkill(val text: String, val topic: String, val source: String, val url: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("topic", topic).put("source", source).put("url", url).put("at", at)
    companion object { fun from(o: JSONObject) = LifeSkill(o.optString("text"), o.optString("topic"), o.optString("source"), o.optString("url"), o.optLong("at")) }
}

data class DiaryEntry(val at: Long, val streamId: String, val text: String, val highlights: List<String> = emptyList(),
                      val facts: String = "", val mood: String = "") {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("streamId", streamId).put("text", text)
        .put("highlights", JSONArray(highlights)).put("facts", facts).put("mood", mood)
    companion object { fun from(o: JSONObject) = DiaryEntry(o.optLong("at"), o.optString("streamId"), o.optString("text"),
        AgentTool.strings(o.optJSONArray("highlights")), o.optString("facts"), o.optString("mood")) }
}

data class LifeRelation(val key: String, val nick: String, val streams: Int = 1, val note: String = "", val lastSeen: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("nick", nick).put("streams", streams).put("note", note).put("lastSeen", lastSeen)
    companion object { fun from(o: JSONObject) = LifeRelation(o.optString("key"), o.optString("nick"), o.optInt("streams", 1), o.optString("note"), o.optLong("lastSeen")) }
}

data class PlanSegment(val kind: String, val minutes: Int) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("minutes", minutes)
    companion object { fun from(o: JSONObject) = PlanSegment(o.optString("kind", "surf"), o.optInt("minutes", 8).coerceIn(1, 120)) }
}

/** Her own proposal for the next stream. status: proposed | edited (by the owner) | applied (written to 节目单). */
data class NextPlan(val topics: List<String>, val segments: List<PlanSegment>, val why: String = "", val createdAt: Long = 0, val status: String = "proposed") {
    fun toJson(): JSONObject = JSONObject().put("topics", JSONArray(topics)).put("segments", JSONArray(segments.map { it.toJson() }))
        .put("why", why).put("createdAt", createdAt).put("status", status)
    companion object { fun from(o: JSONObject) = NextPlan(AgentTool.strings(o.optJSONArray("topics")),
        o.optJSONArray("segments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PlanSegment::from) } } ?: emptyList(),
        o.optString("why"), o.optLong("createdAt"), o.optString("status", "proposed")) }
}

data class LifeState(
    val streams: Int = 0,
    val liveMinutes: Int = 0,
    val goals: List<LifeGoal> = emptyList(),
    val interests: List<LifeInterest> = emptyList(),
    val skills: List<LifeSkill> = emptyList(),
    val diary: List<DiaryEntry> = emptyList(),
    val relations: List<LifeRelation> = emptyList(),
    val selfNotes: List<String> = emptyList(),
    val lessons: List<String> = emptyList(),      // 复盘 conclusions ("问观众问题时弹幕最多")
    val nextPlan: NextPlan? = null,
    val seeded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("streams", streams).put("liveMinutes", liveMinutes)
        .put("goals", JSONArray(goals.map { it.toJson() })).put("interests", JSONArray(interests.map { it.toJson() }))
        .put("skills", JSONArray(skills.map { it.toJson() })).put("diary", JSONArray(diary.map { it.toJson() }))
        .put("relations", JSONArray(relations.map { it.toJson() })).put("selfNotes", JSONArray(selfNotes)).put("lessons", JSONArray(lessons))
        .put("nextPlan", nextPlan?.toJson() ?: JSONObject.NULL).put("seeded", seeded)

    companion object {
        private fun <T> arr(o: JSONObject, k: String, f: (JSONObject) -> T): List<T> =
            o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { runCatching { f(it) }.getOrNull() } } } ?: emptyList()
        fun from(o: JSONObject) = LifeState(
            o.optInt("streams"), o.optInt("liveMinutes"), arr(o, "goals", LifeGoal::from), arr(o, "interests", LifeInterest::from),
            arr(o, "skills", LifeSkill::from), arr(o, "diary", DiaryEntry::from), arr(o, "relations", LifeRelation::from),
            AgentTool.strings(o.optJSONArray("selfNotes")), AgentTool.strings(o.optJSONArray("lessons")),
            o.optJSONObject("nextPlan")?.let(NextPlan::from), o.optBoolean("seeded"),
        )
    }
}

/** Where the life state lives. Android: [FileLifeStore]; tests: [MemLifeStore]. */
interface LifeStore {
    fun get(): LifeState
    fun update(f: (LifeState) -> LifeState): LifeState
}

class MemLifeStore(private var s: LifeState = LifeState()) : LifeStore {
    @Synchronized override fun get() = s
    @Synchronized override fun update(f: (LifeState) -> LifeState): LifeState { s = f(s); return s }
}

/** JSON file (atomic tmp + rename). */
class FileLifeStore(private val file: File) : LifeStore {
    private var cache: LifeState? = null
    @Synchronized override fun get(): LifeState = cache ?: (runCatching { LifeState.from(JSONObject(file.readText())) }.getOrNull() ?: LifeState()).also { cache = it }
    @Synchronized override fun update(f: (LifeState) -> LifeState): LifeState {
        val n = f(get()); cache = n
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(n.toJson().toString(2)); tmp.renameTo(file)
        return n
    }
}

object FyLifeLogic {
    const val MAX_SKILLS = 200; const val MAX_DIARY = 60; const val MAX_REL = 80; const val MAX_NOTES = 40

    /** First run: starting goals + interests from the 直播内容 topic weights. */
    fun seed(s: LifeState, topics: List<Pair<String, Int>>, now: Long): LifeState {
        if (s.seeded) return s
        val goals = s.goals.ifEmpty { listOf(
            LifeGoal("g-long-1", "让老观众愿意再回来，记住每个常客的小事", "long", createdAt = now),
            LifeGoal("g-long-2", "每场直播真学会一点新东西，并讲给大家听", "long", createdAt = now),
            LifeGoal("g-short-1", "这场至少记住 3 位观众公开说过的小事", "short", createdAt = now),
        ) }
        val ints = (s.interests + topics.filter { (t, _) -> s.interests.none { it.topic == t } }.map { (t, w) -> LifeInterest(t, w.toDouble().coerceIn(5.0, 95.0), lastAt = now) })
        return s.copy(goals = goals, interests = ints.take(24), seeded = true)
    }

    private fun interest(s: LifeState, topic: String, now: Long): Pair<LifeInterest, List<LifeInterest>> {
        val t = AgentTool.clip(topic, 24)
        val cur = s.interests.firstOrNull { it.topic == t } ?: LifeInterest(t, 40.0, lastAt = now)
        return cur to s.interests.filter { it.topic != t }
    }

    /** Curiosity: she chose to look into it (browse / note). Small, capped push. */
    fun curiosity(s: LifeState, topic: String, amount: Double, now: Long): LifeState {
        if (topic.isBlank()) return s
        val (cur, rest) = interest(s, topic, now)
        val c = (cur.curiosity + amount).coerceIn(0.0, 30.0)
        return s.copy(interests = (rest + cur.copy(curiosity = c, weight = (cur.weight + amount * 0.5).coerceIn(1.0, 99.0), lastAt = now)).sortedByDescending { it.weight }.take(24))
    }

    /**
     * Engagement after a segment: weight moves toward topics that got the room talking.
     * [engPerMin] for this topic, [baseline] = this stream's average. Moves at most ±10 per segment.
     */
    fun engagement(s: LifeState, topic: String, engPerMin: Double, baseline: Double, now: Long): LifeState {
        if (topic.isBlank()) return s
        val (cur, rest) = interest(s, topic, now)
        val ema = if (cur.samples == 0) engPerMin else cur.engagement * 0.6 + engPerMin * 0.4
        val rel = (engPerMin - baseline) / maxOf(baseline, 0.5)
        val delta = (rel * 6.0).coerceIn(-10.0, 10.0) + cur.curiosity * 0.05
        val w = (cur.weight + delta).coerceIn(1.0, 99.0)
        return s.copy(interests = (rest + cur.copy(weight = w, engagement = ema, samples = cur.samples + 1, lastAt = now)).sortedByDescending { it.weight }.take(24))
    }

    fun addSkill(s: LifeState, k: LifeSkill): LifeState {
        if (k.text.length < 4 || s.skills.any { it.text == k.text }) return s
        return s.copy(skills = (s.skills + k).takeLast(MAX_SKILLS))
    }

    fun addNote(s: LifeState, n: String): LifeState = if (n.isBlank()) s else s.copy(selfNotes = (s.selfNotes - n + n).takeLast(MAX_NOTES))

    fun relation(s: LifeState, key: String, nick: String, note: String?, now: Long, newStream: Boolean = false): LifeState {
        if (key.isBlank()) return s
        val cur = s.relations.firstOrNull { it.key == key }
        val n = (cur ?: LifeRelation(key, nick, 0, "", now)).let { r ->
            r.copy(nick = nick.ifBlank { r.nick }, streams = r.streams + if (newStream || cur == null) 1 else 0,
                note = note?.takeIf { it.isNotBlank() }?.let { (if (r.note.isBlank()) it else (r.note + "；" + it)).takeLast(120) } ?: r.note, lastSeen = now)
        }
        return s.copy(relations = (s.relations.filter { it.key != key } + n).sortedByDescending { it.lastSeen }.take(MAX_REL))
    }

    fun addGoal(s: LifeState, text: String, horizon: String, now: Long): LifeState {
        val t = AgentTool.clip(text, 60)
        if (t.length < 4 || s.goals.any { it.status == "active" && similar(it.text, t) }) return s
        val active = s.goals.count { it.status == "active" && it.horizon == horizon }
        if (active >= (if (horizon == "long") 4 else 5)) return s
        return s.copy(goals = s.goals + LifeGoal("g-${now.toString(36)}-${s.goals.size}", t, if (horizon == "long") "long" else "short", createdAt = now))
    }

    fun progress(s: LifeState, id: String, progress: Int, now: Long): LifeState = s.copy(goals = s.goals.map {
        if (it.id != id) it else { val p = progress.coerceIn(0, 100); it.copy(progress = p, status = if (p >= 100) "done" else it.status, doneAt = if (p >= 100) now else it.doneAt) }
    })

    fun addLesson(s: LifeState, l: String): LifeState = if (l.length < 4) s else s.copy(lessons = (s.lessons.filter { !similar(it, l) } + AgentTool.clip(l, 80)).takeLast(12))

    fun addDiary(s: LifeState, d: DiaryEntry): LifeState = s.copy(diary = (s.diary.filter { it.streamId != d.streamId } + d).takeLast(MAX_DIARY))

    /** Compact prompt block (≤ [max] chars): who she is becoming. */
    fun summary(s: LifeState, max: Int = 700): String = buildString {
        append("【我的成长】已直播 ${s.streams} 场（共 ${s.liveMinutes} 分钟）。")
        val g = s.goals.filter { it.status == "active" }
        if (g.isNotEmpty()) append("目标：").append(g.take(4).joinToString("；") { "[${it.id}](${if (it.horizon == "long") "长期" else "短期"} ${it.progress}%)${it.text}" }).append("。")
        if (s.interests.isNotEmpty()) append("兴趣：").append(s.interests.sortedByDescending { it.weight }.take(6).joinToString("、") { "${it.topic}${it.weight.toInt()}" }).append("。")
        if (s.lessons.isNotEmpty()) append("复盘经验：").append(s.lessons.takeLast(3).joinToString("；")).append("。")
        if (s.skills.isNotEmpty()) append("最近学到：").append(s.skills.takeLast(3).joinToString("；") { it.text.take(40) }).append("。")
        if (s.relations.isNotEmpty()) append("熟人：").append(s.relations.filter { it.streams >= 2 || it.note.isNotBlank() }.take(5).joinToString("、") { r -> r.nick + (if (r.note.isNotBlank()) "(${r.note.take(20)})" else "") }).append("。")
    }.take(max)

    /** The only past experiences she may mention (honesty rule): diary of real streams + what she read. */
    fun realExperiences(s: LifeState, max: Int = 400): String {
        val items = s.diary.takeLast(3).map { "上次直播：" + it.text.take(80) } + s.skills.takeLast(3).map { "读到过：" + it.text.take(50) + "（${it.source.take(16)}）" }
        return if (items.isEmpty()) "（还没有记录下来的过往经历——不要编造任何过去的经历）" else items.joinToString("；").take(max)
    }

    /** Deterministic next-stream plan from the top interests (used when the 复盘官 can't answer). */
    fun defaultPlan(s: LifeState, base: List<PlanSegment>, now: Long): NextPlan {
        val topics = s.interests.sortedByDescending { it.weight + it.curiosity }.map { it.topic }.take(3).ifEmpty { listOf("鲸鱼和海洋") }
        val segs = base.ifEmpty { listOf(PlanSegment("opening", 3), PlanSegment("surf", 12), PlanSegment("learn", 10), PlanSegment("game", 5), PlanSegment("recap", 3)) }
        return NextPlan(topics, segs, "按兴趣权重和上一场观众反应排的", now)
    }

    fun similar(a: String, b: String): Boolean {
        fun bg(t: String) = t.replace(Regex("\\s+"), "").windowed(2).toSet()
        val x = bg(a); val y = bg(b); if (x.isEmpty() || y.isEmpty()) return a.trim() == b.trim()
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size) >= 0.6
    }

}
