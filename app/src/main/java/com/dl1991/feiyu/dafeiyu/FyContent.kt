package com.dl1991.feiyu.dafeiyu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One block of the 节目单. */
data class Segment(val kind: String, val minutes: Int, val enabled: Boolean = true, val note: String = "") {
    val title: String get() = SegKind.label(kind)
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("minutes", minutes).put("enabled", enabled).put("note", note)
    companion object {
        fun from(o: JSONObject) = Segment(o.optString("kind", SegKind.SURF), o.optInt("minutes", 8).coerceIn(1, 120), o.optBoolean("enabled", true), o.optString("note"))
    }
}

object SegKind {
    const val OPENING = "opening"; const val SURF = "surf"; const val LEARN = "learn"
    const val REQUEST = "request"; const val GAME = "game"; const val RECAP = "recap"
    val ALL = listOf(OPENING, SURF, LEARN, REQUEST, GAME, RECAP)
    fun label(k: String) = when (k) {
        OPENING -> "开场寒暄"; SURF -> "主题冲浪"; LEARN -> "学知识"; REQUEST -> "观众点播"; GAME -> "小游戏"; RECAP -> "收尾回顾"; else -> k
    }
    /** What she is told to do during the segment. */
    fun instruction(k: String) = when (k) {
        OPENING -> "开场寒暄：跟进来的观众打招呼，聊聊今天的心情和今天的节目单，先别急着开网页。"
        SURF -> "主题冲浪：围绕今天的主题逛网页，边看边说真实想法，顺着链接深入。"
        LEARN -> "学知识：认真读页面，用 [[NOTE]] 记要点，用大白话讲给观众听，偶尔反问观众。"
        REQUEST -> "观众点播：主动请观众点播想看的东西（网站、话题、问题），挑合适的去做，做之前说是谁点的。"
        GAME -> "小游戏：和观众玩投票 [[POLL]]、答题 [[QUIZ]] 或猜谜 [[GUESS]]，节奏轻快。"
        RECAP -> "收尾回顾：回顾今天看过、学到、聊过的东西，感谢观众，预告下次想聊什么。"
        else -> ""
    }
}

data class TopicWeight(val topic: String, val weight: Int) {
    fun toJson(): JSONObject = JSONObject().put("topic", topic).put("weight", weight)
}

/**
 * 「直播内容」— everything that shapes what she does on stream. Read by the live engine on every
 * turn, so changes (including from the 导播台 during a stream) apply to the next sentence.
 * Voice settings live in [VoiceConfig]; per-viewer blocks live on the viewer profiles.
 */
data class ContentSettings(
    // 节目单
    val segments: List<Segment> = DEFAULT_SEGMENTS,
    val autoRotate: Boolean = true,
    // 主题与来源
    val topics: List<TopicWeight> = listOf(TopicWeight("AI 新鲜事", 80), TopicWeight("鲸鱼和海洋", 60), TopicWeight("游戏", 40)),
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    val explore: Int = 50,                 // 自由探索程度 0..100
    // 互动
    val chatRatio: Int = 50,               // 0 = 多冲浪 … 100 = 多聊天
    val replyLength: String = "medium",    // short | medium | long
    val coldSeconds: Int = 6,              // 冷场阈值
    val requestsOn: Boolean = true,        // 点播开关
    val pollEveryMin: Int = 8,             // 0 = 不主动发起
    val quizEveryMin: Int = 12,
    // 人设与情绪
    val lively: Int = 70,
    val snark: Int = 30,
    val curiosity: Int = 80,
    val patience: Int = 70,
    val emotionIntensity: Int = 60,
    val catchphrases: List<String> = listOf("咕噜咕噜", "鱼鱼我呀"),
    val forbidden: List<String> = emptyList(),
    // 画面
    val layout: String = "web_big",        // web_big | fish_big | chat_only
    val subtitleSize: Int = 34,            // px at 720 wide
    val ticker: Boolean = true,
    val streamPreset: String = "P720",
    // 安全
    val filterMedical: Boolean = true,     // politics / adult / gambling are always on
    val blockKeywords: List<String> = emptyList(),
    val perViewerPerMin: Int = 6,          // comments per viewer per minute considered
) {
    fun sanitized() = copy(
        segments = segments.map { it.copy(minutes = it.minutes.coerceIn(1, 120)) }.filter { it.kind in SegKind.ALL }.ifEmpty { DEFAULT_SEGMENTS },
        topics = topics.filter { it.topic.isNotBlank() }.map { it.copy(topic = clean(it.topic, 24), weight = it.weight.coerceIn(0, 100)) }.take(20),
        whitelist = hosts(whitelist), blacklist = hosts(blacklist),
        explore = explore.coerceIn(0, 100), chatRatio = chatRatio.coerceIn(0, 100),
        replyLength = replyLength.takeIf { it in setOf("short", "medium", "long") } ?: "medium",
        coldSeconds = coldSeconds.coerceIn(3, 30), pollEveryMin = pollEveryMin.coerceIn(0, 60), quizEveryMin = quizEveryMin.coerceIn(0, 60),
        lively = lively.coerceIn(0, 100), snark = snark.coerceIn(0, 100), curiosity = curiosity.coerceIn(0, 100), patience = patience.coerceIn(0, 100),
        emotionIntensity = emotionIntensity.coerceIn(0, 100),
        catchphrases = catchphrases.map { clean(it, 16) }.filter { it.isNotBlank() }.distinct().take(8),
        forbidden = forbidden.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(60),
        layout = layout.takeIf { it in setOf("web_big", "fish_big", "chat_only") } ?: "web_big",
        subtitleSize = subtitleSize.coerceIn(22, 56),
        streamPreset = streamPreset.takeIf { n -> StreamPreset.values().any { it.name == n } } ?: "P720",
        blockKeywords = blockKeywords.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(100),
        perViewerPerMin = perViewerPerMin.coerceIn(1, 30),
    )

    val maxTokens: Int get() = when (replyLength) { "short" -> 120; "long" -> 420; else -> 260 }
    val enabledSegments: List<Segment> get() = segments.filter { it.enabled }.ifEmpty { listOf(Segment(SegKind.SURF, 30)) }
    fun preset(): StreamPreset = StreamPreset.values().firstOrNull { it.name == streamPreset } ?: StreamPreset.P720

    fun toJson(): JSONObject = JSONObject()
        .put("segments", JSONArray(segments.map { it.toJson() })).put("autoRotate", autoRotate)
        .put("topics", JSONArray(topics.map { it.toJson() })).put("whitelist", JSONArray(whitelist)).put("blacklist", JSONArray(blacklist))
        .put("explore", explore).put("chatRatio", chatRatio).put("replyLength", replyLength).put("coldSeconds", coldSeconds)
        .put("requestsOn", requestsOn).put("pollEveryMin", pollEveryMin).put("quizEveryMin", quizEveryMin)
        .put("lively", lively).put("snark", snark).put("curiosity", curiosity).put("patience", patience).put("emotionIntensity", emotionIntensity)
        .put("catchphrases", JSONArray(catchphrases)).put("forbidden", JSONArray(forbidden))
        .put("layout", layout).put("subtitleSize", subtitleSize).put("ticker", ticker).put("streamPreset", streamPreset)
        .put("filterMedical", filterMedical).put("blockKeywords", JSONArray(blockKeywords)).put("perViewerPerMin", perViewerPerMin)

    companion object {
        val DEFAULT_SEGMENTS = listOf(
            Segment(SegKind.OPENING, 3), Segment(SegKind.SURF, 12), Segment(SegKind.LEARN, 10),
            Segment(SegKind.REQUEST, 8), Segment(SegKind.GAME, 5), Segment(SegKind.RECAP, 3),
        )
        private fun hosts(l: List<String>) = l.map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/') }
            .filter { it.contains('.') }.distinct().take(40)
        private fun strs(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()

        fun from(o: JSONObject): ContentSettings {
            val d = ContentSettings()
            return ContentSettings(
                segments = o.optJSONArray("segments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Segment::from) } } ?: d.segments,
                autoRotate = o.optBoolean("autoRotate", d.autoRotate),
                topics = o.optJSONArray("topics")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { t -> TopicWeight(t.optString("topic"), t.optInt("weight", 50)) } } } ?: d.topics,
                whitelist = strs(o.optJSONArray("whitelist")), blacklist = strs(o.optJSONArray("blacklist")),
                explore = o.optInt("explore", d.explore), chatRatio = o.optInt("chatRatio", d.chatRatio),
                replyLength = o.optString("replyLength", d.replyLength), coldSeconds = o.optInt("coldSeconds", d.coldSeconds),
                requestsOn = o.optBoolean("requestsOn", d.requestsOn), pollEveryMin = o.optInt("pollEveryMin", d.pollEveryMin), quizEveryMin = o.optInt("quizEveryMin", d.quizEveryMin),
                lively = o.optInt("lively", d.lively), snark = o.optInt("snark", d.snark), curiosity = o.optInt("curiosity", d.curiosity),
                patience = o.optInt("patience", d.patience), emotionIntensity = o.optInt("emotionIntensity", d.emotionIntensity),
                catchphrases = o.optJSONArray("catchphrases")?.let(::strs) ?: d.catchphrases, forbidden = strs(o.optJSONArray("forbidden")),
                layout = o.optString("layout", d.layout), subtitleSize = o.optInt("subtitleSize", d.subtitleSize), ticker = o.optBoolean("ticker", d.ticker),
                streamPreset = o.optString("streamPreset", d.streamPreset),
                filterMedical = o.optBoolean("filterMedical", true), blockKeywords = strs(o.optJSONArray("blockKeywords")),
                perViewerPerMin = o.optInt("perViewerPerMin", d.perViewerPerMin),
            ).sanitized()
        }

        /** Built-in presets. */
        val BUILTIN: Map<String, ContentSettings> by lazy {
            mapOf(
                "学习直播" to ContentSettings(
                    segments = listOf(Segment(SegKind.OPENING, 2), Segment(SegKind.LEARN, 20), Segment(SegKind.GAME, 5), Segment(SegKind.LEARN, 15), Segment(SegKind.RECAP, 4)),
                    topics = listOf(TopicWeight("AI 原理", 80), TopicWeight("科学冷知识", 70), TopicWeight("历史", 40)),
                    whitelist = listOf("zh.wikipedia.org", "baike.baidu.com", "github.com"),
                    explore = 25, chatRatio = 35, replyLength = "long", coldSeconds = 8, requestsOn = true, pollEveryMin = 0, quizEveryMin = 6,
                    lively = 55, snark = 10, curiosity = 90, patience = 90, emotionIntensity = 45, layout = "web_big",
                ),
                "闲聊直播" to ContentSettings(
                    segments = listOf(Segment(SegKind.OPENING, 5), Segment(SegKind.REQUEST, 15), Segment(SegKind.GAME, 8), Segment(SegKind.SURF, 8), Segment(SegKind.RECAP, 4)),
                    explore = 60, chatRatio = 85, replyLength = "short", coldSeconds = 5, requestsOn = true, pollEveryMin = 6, quizEveryMin = 0,
                    lively = 85, snark = 45, curiosity = 60, patience = 75, emotionIntensity = 75, layout = "fish_big",
                ),
                "冲浪直播" to ContentSettings(
                    segments = listOf(Segment(SegKind.OPENING, 2), Segment(SegKind.SURF, 20), Segment(SegKind.REQUEST, 10), Segment(SegKind.SURF, 10), Segment(SegKind.RECAP, 3)),
                    explore = 80, chatRatio = 30, replyLength = "medium", coldSeconds = 6, requestsOn = true, pollEveryMin = 10, quizEveryMin = 0,
                    lively = 75, snark = 35, curiosity = 95, patience = 60, emotionIntensity = 65, layout = "web_big",
                ),
            )
        }
    }
}

object FyContentStore {
    private val _s = MutableStateFlow(ContentSettings())
    val state: StateFlow<ContentSettings> get() = _s
    val presets = MutableStateFlow<Map<String, ContentSettings>>(emptyMap())
    val activePreset = MutableStateFlow("")
    @Volatile private var loaded = false

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "feiyu").apply { mkdirs() }
    private fun write(f: File, text: String) { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text); tmp.renameTo(f) }

    fun get(ctx: Context): ContentSettings { ensure(ctx); return _s.value }

    fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val d = dir(ctx)
            runCatching { JSONObject(File(d, "content.json").readText()) }.getOrNull()?.let { o ->
                _s.value = ContentSettings.from(o); activePreset.value = o.optString("_preset")
            }
            presets.value = runCatching {
                val o = JSONObject(File(d, "presets.json").readText())
                o.keys().asSequence().associateWith { ContentSettings.from(o.getJSONObject(it)) }
            }.getOrDefault(emptyMap())
            loaded = true
            applyRuntime(_s.value)
        }
    }

    fun set(ctx: Context, c0: ContentSettings, preset: String = "") {
        ensure(ctx)
        val c = c0.sanitized()
        _s.value = c; activePreset.value = preset
        write(File(dir(ctx), "content.json"), c.toJson().put("_preset", preset).toString(2))
        applyRuntime(c)
    }

    fun update(ctx: Context, f: (ContentSettings) -> ContentSettings) = set(ctx, f(get(ctx)))

    /** Built-ins first, then the user's own. */
    fun allPresets(): Map<String, ContentSettings> = ContentSettings.BUILTIN + presets.value

    fun savePreset(ctx: Context, name: String) {
        ensure(ctx)
        val n = clean(name, 16).ifBlank { return }
        presets.value = presets.value + (n to _s.value)
        write(File(dir(ctx), "presets.json"), JSONObject().apply { presets.value.forEach { (k, v) -> put(k, v.toJson()) } }.toString(2))
        activePreset.value = n
        set(ctx, _s.value, n)
    }

    fun deletePreset(ctx: Context, name: String) {
        presets.value = presets.value - name
        write(File(dir(ctx), "presets.json"), JSONObject().apply { presets.value.forEach { (k, v) -> put(k, v.toJson()) } }.toString(2))
    }

    fun loadPreset(ctx: Context, name: String) { allPresets()[name]?.let { set(ctx, it, name) } }

    private fun applyRuntime(c: ContentSettings) {
        DfySafety.medicalOn = c.filterMedical
        DfySafety.extraBlock = c.blockKeywords
        DfySafety.forbidden = c.forbidden
    }
}
