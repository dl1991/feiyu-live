package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Run bookkeeping that is not part of her memory. */
data class DfyMeta(
    val lastRunAt: Long = 0,
    val lastRunResult: String = "",
    val activity: String = "",          // what she is doing right now (overlay / home)
    val activityAt: Long = 0,
    val day: String = "",               // yyyy-MM-dd the counters below belong to
    val picksToday: Int = 0,
    val reflectBatchesToday: Int = 0,
    val writesToday: Map<String, Int> = emptyMap(),
    val writeCooldownUntil: Long = 0,   // set when B站 answers with risk control / verification
    val writeCooldownReason: String = "",
    val lastReflectionAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("lastRunAt", lastRunAt).put("lastRunResult", lastRunResult)
        .put("activity", activity).put("activityAt", activityAt).put("day", day).put("picksToday", picksToday)
        .put("reflectBatchesToday", reflectBatchesToday)
        .put("writesToday", JSONObject().also { o -> writesToday.forEach { (k, v) -> o.put(k, v) } })
        .put("writeCooldownUntil", writeCooldownUntil).put("writeCooldownReason", writeCooldownReason)
        .put("lastReflectionAt", lastReflectionAt)

    /** Counters reset when the calendar day changes. */
    fun forToday(today: String): DfyMeta = if (day == today) this else copy(day = today, picksToday = 0, reflectBatchesToday = 0, writesToday = emptyMap())

    companion object {
        fun from(o: JSONObject): DfyMeta {
            val w = o.optJSONObject("writesToday")
            return DfyMeta(o.optLong("lastRunAt"), o.optStr("lastRunResult"), o.optStr("activity"), o.optLong("activityAt"), o.optStr("day"),
                o.optInt("picksToday"), o.optInt("reflectBatchesToday"),
                w?.keys()?.asSequence()?.associateWith { w.optInt(it) } ?: emptyMap(),
                o.optLong("writeCooldownUntil"), o.optStr("writeCooldownReason"), o.optLong("lastReflectionAt"))
        }
    }
}

data class DfyState(
    val settings: DfySettings = DfySettings(),
    val mood: MoodState = MoodState(),
    val episodes: List<Episode> = emptyList(),
    val pending: List<PendingItem> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val interests: List<InterestEntry> = emptyList(),
    val picks: List<PickRecord> = emptyList(),
    val live: List<LiveSnapshot> = emptyList(),
    val todos: List<DfyTodo> = emptyList(),
    val chat: List<ChatTurn> = emptyList(),
    val actions: List<BiliAction> = emptyList(),
    val sessions: List<SurfSession> = emptyList(),
    val knowledge: List<LearnGoal> = emptyList(),
    val viewers: List<ViewerProfile> = emptyList(),
    val meta: DfyMeta = DfyMeta(),
)

/**
 * JSON-file store under filesDir/dafeiyu. All mutations go through [update], which serialises
 * writers with a mutex, writes atomically (tmp + rename) and publishes a new immutable snapshot.
 * The B站 cookie is NOT stored here (see [BiliAuth], encrypted prefs) and never exported.
 */
object DfyStore {
    private const val TAG = "Dafeiyu"
    const val SCHEMA = 1
    private val mutex = Mutex()
    private val _state = MutableStateFlow(DfyState())
    val state: StateFlow<DfyState> get() = _state
    @Volatile private var loaded = false

    private val FILES = listOf("settings", "mood", "episodes", "pending", "insights", "interests", "picks", "live", "todos", "chat", "actions", "sessions", "knowledge", "viewers", "meta")

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "dafeiyu").apply { mkdirs() }

    fun today(now: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    private fun readFile(ctx: Context, name: String): String? = runCatching {
        val f = File(dir(ctx), "$name.json")
        if (f.isFile && f.length() < 8L * 1024 * 1024) f.readText() else null
    }.getOrNull()

    private fun writeFile(ctx: Context, name: String, body: String) {
        val d = dir(ctx)
        val tmp = File(d, "$name.json.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(File(d, "$name.json"))) {
            File(d, "$name.json").writeText(body); tmp.delete()
        }
    }

    private fun arr(s: String?): JSONArray = s?.let { runCatching { JSONObject(it).optJSONArray("items") }.getOrNull() } ?: JSONArray()
    private fun <T> list(a: JSONArray, f: (JSONObject) -> T): List<T> = (0 until a.length()).mapNotNull { i -> runCatching { f(a.getJSONObject(i)) }.getOrNull() }

    private fun parse(get: (String) -> String?): DfyState = DfyState(
        settings = get("settings")?.let { runCatching { DfySettings.from(JSONObject(it)) }.getOrNull() } ?: DfySettings(),
        mood = get("mood")?.let { runCatching { MoodState.from(JSONObject(it)) }.getOrNull() } ?: MoodState(),
        episodes = list(arr(get("episodes")), Episode::from),
        pending = list(arr(get("pending")), PendingItem::from),
        insights = list(arr(get("insights")), Insight::from),
        interests = list(arr(get("interests")), InterestEntry::from),
        picks = list(arr(get("picks")), PickRecord::from),
        live = list(arr(get("live")), LiveSnapshot::from),
        todos = list(arr(get("todos")), DfyTodo::from),
        chat = list(arr(get("chat")), ChatTurn::from),
        actions = list(arr(get("actions")), BiliAction::from),
        sessions = list(arr(get("sessions")), SurfSession::from),
        knowledge = list(arr(get("knowledge")), LearnGoal::from),
        viewers = list(arr(get("viewers")), ViewerProfile::from),
        meta = get("meta")?.let { runCatching { DfyMeta.from(JSONObject(it)) }.getOrNull() } ?: DfyMeta(),
    )

    private fun wrap(items: List<JSONObject>): String =
        JSONObject().put("schema", SCHEMA).put("items", JSONArray().also { a -> items.forEach { a.put(it) } }).toString()

    private fun serialise(s: DfyState): Map<String, String> = mapOf(
        "settings" to s.settings.toJson().toString(),
        "mood" to s.mood.toJson().toString(),
        "episodes" to wrap(s.episodes.map { it.toJson() }),
        "pending" to wrap(s.pending.map { it.toJson() }),
        "insights" to wrap(s.insights.map { it.toJson() }),
        "interests" to wrap(s.interests.map { it.toJson() }),
        "picks" to wrap(s.picks.map { it.toJson() }),
        "live" to wrap(s.live.map { it.toJson() }),
        "todos" to wrap(s.todos.map { it.toJson() }),
        "chat" to wrap(s.chat.map { it.toJson() }),
        "actions" to wrap(s.actions.map { it.toJson() }),
        "sessions" to wrap(s.sessions.map { it.toJson() }),
        "knowledge" to wrap(s.knowledge.map { it.toJson() }),
        "viewers" to wrap(s.viewers.map { it.toJson() }),
        "meta" to s.meta.toJson().toString(),
    )

    /** Size caps so nothing grows without bound. */
    private fun trimmed(s: DfyState, now: Long): DfyState {
        val d90 = now - 92L * 86_400_000
        return s.copy(
            episodes = s.episodes.filter { it.stage != MemStage.FORGOTTEN || now - it.at < 400L * 86_400_000 }.takeLast(3000),
            pending = s.pending.filter { it.status == "pending" || now - it.createdAt < 30L * 86_400_000 }.takeLast(600),
            insights = s.insights.takeLast(400),
            interests = s.interests.take(40),
            picks = s.picks.filter { it.at >= d90 }.takeLast(4000),
            live = s.live.filter { it.at >= d90 }.takeLast(400),
            todos = s.todos.filter { !it.done || now - it.doneAt < 30L * 86_400_000 }.takeLast(200),
            chat = s.chat.takeLast(120),
            actions = s.actions.filter { it.status == "pending" || now - it.createdAt < 60L * 86_400_000 }.takeLast(500),
            sessions = s.sessions.filter { it.status != "ended" || it.startedAt >= d90 }.takeLast(150).map { x ->
                // older broadcasts keep their trail + recap but only a few bubbles
                if (x.status == "ended" && now - x.startedAt > 7L * 86_400_000 && x.bubbles.size > 30)
                    x.deepCopy().also { c -> val keep = c.bubbles.filter { it.kind == "thought" || it.kind == "reply" }.takeLast(30); c.bubbles.clear(); c.bubbles.addAll(keep) }
                else x
            },
            knowledge = s.knowledge.takeLast(200),
            // viewers not seen for 180 days and never more than a passer-by are forgotten
            viewers = s.viewers.filter { it.blocked || it.level != "新观众" || now - it.lastSeen < 180L * 86_400_000 }
                .sortedByDescending { it.lastSeen }.take(3000),
            meta = s.meta.forToday(today(now)),
        )
    }

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _state.value = parse { readFile(ctx, it) }.let { it.copy(meta = it.meta.forToday(today())) }
            loaded = true
        }
    }

    fun snapshot(ctx: Context): DfyState { ensureLoaded(ctx); return _state.value }

    suspend fun update(ctx: Context, f: (DfyState) -> DfyState): DfyState = mutex.withLock {
        ensureLoaded(ctx)
        val before = _state.value
        val after = trimmed(f(before), System.currentTimeMillis())
        val a = serialise(after)
        val b = serialise(before)
        for ((k, v) in a) if (b[k] != v) runCatching { writeFile(ctx, k, v) }.onFailure { Log.w(TAG, "write $k: ${it.message}") }
        _state.value = after
        after
    }

    suspend fun setActivity(ctx: Context, text: String) {
        update(ctx) { it.copy(meta = it.meta.copy(activity = text, activityAt = System.currentTimeMillis())) }
    }

    // ------------------------------------------------------------------ backup

    /** Full backup as one JSON document (no credentials). */
    fun exportJson(ctx: Context): String {
        val s = snapshot(ctx)
        val files = JSONObject()
        serialise(s).forEach { (k, v) -> files.put(k, JSONObject(v)) }
        return JSONObject().put("format", "mibot-dafeiyu-backup").put("schema", SCHEMA)
            .put("exportedAt", System.currentTimeMillis()).put("files", files).toString(2)
    }

    /** Replace everything with a backup; returns an error message or null. */
    suspend fun importJson(ctx: Context, body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrElse { return "不是有效的 JSON" }
        if (o.optString("format") != "mibot-dafeiyu-backup") return "不是大肥鱼人生的备份文件"
        if (o.optInt("schema") > SCHEMA) return "备份来自更新版本，无法导入"
        val files = o.optJSONObject("files") ?: return "备份缺少数据"
        val parsed = parse { name -> files.optJSONObject(name)?.toString() }
        // A restored backup never turns write permissions on by itself.
        val safe = parsed.copy(settings = parsed.settings.copy(writeEnabled = false, fullAuto = false))
        update(ctx) { safe }
        return null
    }

    suspend fun resetAll(ctx: Context, keepSettings: Boolean) {
        update(ctx) { s -> DfyState(settings = if (keepSettings) s.settings else DfySettings()) }
    }

    /** Names of the files kept in [dir] (for diagnostics / the integration smoke test). */
    fun fileNames(): List<String> = FILES.map { "$it.json" }
}
