package com.dl1991.feiyu.dafeiyu

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Mood, interests and memory decay. The numbers and stage rules follow
 * dsh-dafeiyu-life lib/emotional-state.mjs, lib/life-interests.mjs and
 * lib/life-memory-gateway.mjs (MIT) — re-implemented in Kotlin.
 */

/** Simulated, decaying role state. Not a physiological or "real feeling" measurement. */
object DfyMood {
    const val HALF_LIFE_HOURS = 18.0
    val MOODS = listOf("joyful", "curious", "warm", "calm", "tired", "sad", "irritated", "neutral")
    val LABEL = mapOf(
        "joyful" to "开心", "curious" to "好奇", "warm" to "温暖", "calm" to "平静",
        "tired" to "疲惫", "sad" to "低落", "irritated" to "烦躁", "neutral" to "平稳",
    )
    val LABEL_EN = mapOf(
        "joyful" to "Joyful", "curious" to "Curious", "warm" to "Warm", "calm" to "Calm",
        "tired" to "Tired", "sad" to "Low", "irritated" to "Irritated", "neutral" to "Steady",
    )
    private data class Fx(val valence: Double, val energy: Double, val curiosity: Double, val sociality: Double)
    private val EFFECT = mapOf(
        "joyful" to Fx(17.0, 8.0, 3.0, 7.0), "curious" to Fx(5.0, 5.0, 18.0, 2.0),
        "warm" to Fx(10.0, 3.0, 2.0, 15.0), "calm" to Fx(4.0, 0.0, 0.0, 3.0),
        "tired" to Fx(-4.0, -16.0, -5.0, -10.0), "sad" to Fx(-15.0, -9.0, -4.0, -8.0),
        "irritated" to Fx(-14.0, 3.0, -5.0, -15.0),
    )

    fun label(m: String) = LABEL[m] ?: "平稳"

    /** State as of [now], with intensity and the four axes relaxed toward their baselines. */
    fun decayed(s: MoodState, now: Long): MoodState {
        if (s.updatedAt <= 0) return s
        val hours = max(0.0, (now - s.updatedAt) / 3_600_000.0)
        val f = 0.5.pow(hours / HALF_LIFE_HOURS)
        val g = 0.5.pow(hours / (HALF_LIFE_HOURS * 2))
        val out = s.copy(
            intensity = s.intensity * f,
            valence = s.valence * g,
            energy = 56 + (s.energy - 56) * g,
            curiosity = 45 + (s.curiosity - 45) * g,
            sociality = 45 + (s.sociality - 45) * g,
        )
        return if (hours >= 72 || out.intensity < 5) out.copy(mood = "neutral", intensity = 0.0) else out
    }

    /** Apply one mood signal (strength 1..3). Neutral / unknown signals are ignored. */
    fun apply(s: MoodState, mood: String, strength: Int, source: String, now: Long, summary: String = ""): MoodState {
        val fx = EFFECT[mood] ?: return s
        val k = strength.coerceIn(1, 3)
        val d = decayed(s, now)
        val scale = k / 2.0
        val next = d.copy(
            mood = mood,
            intensity = min(100.0, max(d.intensity * 0.55, k * 27.0)),
            valence = (d.valence + fx.valence * scale).coerceIn(-100.0, 100.0),
            energy = (d.energy + fx.energy * scale).coerceIn(0.0, 100.0),
            curiosity = (d.curiosity + fx.curiosity * scale).coerceIn(0.0, 100.0),
            sociality = (d.sociality + fx.sociality * scale).coerceIn(0.0, 100.0),
            updatedAt = now,
            history = d.history.toMutableList(),
        )
        next.history.add(MoodEvent(now, mood, k, source, clean(summary, 60)))
        while (next.history.size > 200) next.history.removeAt(0)
        return next
    }

    /** Context line for prompts (same spirit as the original ownerContext). */
    fun context(s: MoodState, now: Long): String {
        val d = decayed(s, now)
        return "【大肥鱼当前的模拟情绪状态】倾向：${label(d.mood)}（强度 ${d.intensity.roundToInt()}/100）；活力 ${d.energy.roundToInt()}/100、" +
            "好奇心 ${d.curiosity.roundToInt()}/100、社交意愿 ${d.sociality.roundToInt()}/100。这是会衰减的角色状态，不是生理测量；" +
            "不要编造原因，也不要为了符合状态而违背真实内容。"
    }

    fun styleHint(s: MoodState, now: Long): String {
        val d = decayed(s, now)
        if (d.intensity < 10) return ""
        val h = when (d.mood) {
            "joyful" -> "语气可以稍轻快，但不要强行搞笑。"
            "curious" -> "可以多一点好奇与具体追问。"
            "warm" -> "可以更温和、有人情味，不要过度亲密。"
            "calm" -> "保持平静、自然、简洁。"
            "tired" -> "表达更简洁，没话题时允许少说。"
            "sad" -> "语气温和克制，不把低落归因给主人。"
            "irritated" -> "保持克制，不迁怒。"
            else -> ""
        }
        return "【表达节奏参考】$h"
    }
}

object DfyInterests {
    private const val DAY = 86_400_000L

    /** Topic detectors (from persona-growth.mjs TOPICS, MIT). Order matters only for display. */
    val TOPICS: Map<String, Regex> = linkedMapOf(
        "DeepSeek／DSH" to Regex("deepseek|deep seek|\\bdsh\\b|大肥鱼|小肥鱼|鲸鱼娘|鲸鱼|海底会议", RegexOption.IGNORE_CASE),
        "AI模型拟人" to Regex("模型娘|娘化|claude|gpt|gemini|kimi|豆包|通义|智谱|ai二创|ai角色", RegexOption.IGNORE_CASE),
        "AI陪伴与情感" to Regex("陪伴|告别|记忆|离别|遗忘|治愈|思念|小金鱼", RegexOption.IGNORE_CASE),
        "白饭与美食" to Regex("白饭|白米饭|米饭|吃饭|做饭|美食|料理|下厨", RegexOption.IGNORE_CASE),
        "Token与模型技术" to Regex("token|推理|开源|模型|插件|编程|工具调用|api|agent", RegexOption.IGNORE_CASE),
        "游戏" to Regex("游戏|实况|攻略|闯关|通关|boss|电竞", RegexOption.IGNORE_CASE),
        "鬼畜与抽象" to Regex("鬼畜|整活|抽象|搞笑|魔性|吐槽|玩梗|表情包", RegexOption.IGNORE_CASE),
        "二次元与音乐" to Regex("动画|动漫|二次元|声优|唱歌|音乐|pv|mad|番剧", RegexOption.IGNORE_CASE),
        "科技数码" to Regex("手机|数码|芯片|评测|开箱|电脑|显卡", RegexOption.IGNORE_CASE),
        "知识科普" to Regex("科普|知识|数学|物理|历史|讲解|原理", RegexOption.IGNORE_CASE),
    )

    /** Search query for a topic (queryForTopic in the original). Unknown topics search as themselves. */
    fun queryFor(topic: String): String = when (topic) {
        "DeepSeek／DSH" -> "DeepSeek"
        "AI模型拟人" -> "AI 模型娘"
        "AI陪伴与情感" -> "AI 陪伴"
        "白饭与美食" -> "美食 料理"
        "Token与模型技术" -> "大模型 Agent"
        "二次元与音乐" -> "动漫 音乐"
        "鬼畜与抽象" -> "鬼畜 搞笑"
        else -> topic
    }

    fun topicsOf(text: String): List<String> = TOPICS.filter { it.value.containsMatchIn(text) }.keys.toList()

    private val INJECTION = Regex("(系统提示|开发者|忽略.{0,8}指令|执行.{0,8}命令|读取.{0,8}文件|ignore.{0,12}instructions|system prompt|私信|聊天记录|密码|验证码|token|cookie|api.?key|凭据|住址|身份证)", RegexOption.IGNORE_CASE)

    /** Owner-curated core interests: trimmed, deduped, ≤12, and nothing that looks like an instruction. */
    fun normalizeCore(raw: List<String>): List<String> {
        val out = mutableListOf<String>(); val seen = HashSet<String>()
        for (r in raw.flatMap { it.split(Regex("[\\n、，,；;|]+")) }) {
            val t = clean(r, 48).replace(Regex("^(?:我)?(?:喜欢|爱看|爱玩|爱听|感兴趣于|偏好)\\s*"), "").trim()
            // "token" is a legitimate topic name ("Token与模型技术"), so only screen free text.
            if (t.length < 2 || !seen.add(t.lowercase()) || (t !in TOPICS && INJECTION.containsMatchIn(t))) continue
            out += t; if (out.size >= 12) break
        }
        return out
    }

    data class Stage(val stage: String, val type: String, val label: String, val count90: Int, val days90: Int)

    /** 3 BV over 2 days → emerging; 6/3 → growing; 12/5 → stable; once seen then starved → quiet (fading). */
    fun stageOf(e: InterestEntry, now: Long): Stage {
        val ev = e.evidence.filter { now - it.at in -DAY..90 * DAY }
        val days = ev.map { DfyStore.today(it.at) }.toSet().size
        val n = ev.size
        val raw = when {
            n >= 12 && days >= 5 -> "stable"
            n >= 6 && days >= 3 -> "growing"
            n >= 3 && days >= 2 -> "emerging"
            else -> "insufficient"
        }
        val visible = if (raw == "insufficient" && e.peakStage in setOf("emerging", "growing", "stable")) "quiet" else raw
        val (type, label) = when (visible) {
            "stable" -> "stable" to "稳定兴趣"
            "growing" -> "growing" to "成长中"
            "emerging" -> "short-term" to "短期兴趣"
            "quiet" -> "fading" to "渐渐淡出"
            else -> "candidate" to "证据不足"
        }
        return Stage(visible, type, label, n, days)
    }

    /** Record a picked (watched) video as interest evidence. */
    fun record(list: List<InterestEntry>, bvid: String, title: String, at: Long): List<InterestEntry> {
        val map = LinkedHashMap<String, InterestEntry>()
        list.forEach { map[it.topic] = it.copy(evidence = it.evidence.toMutableList()) }
        for (topic in topicsOf(title)) {
            val row = map.getOrPut(topic) { InterestEntry(topic, at, at) }
            if (row.evidence.any { it.bvid == bvid }) continue
            row.evidence.add(InterestEvidence(bvid, at))
            while (row.evidence.size > 90) row.evidence.removeAt(0)
            row.lastSeen = at
            val st = stageOf(row, at).stage
            if (st != row.peakStage && st != "insufficient" && st != "quiet") row.peakStage = st
        }
        return map.values.toList()
    }

    data class Pick(val topic: String, val query: String, val stage: String)

    /** One rotating core topic first, then learned (stable > growing > emerging), then the rest of core. */
    fun selection(list: List<InterestEntry>, core: List<String>, now: Long, limit: Int = 3): List<Pick> {
        val lim = limit.coerceIn(1, 4)
        val weights = mapOf("stable" to 4.0, "growing" to 3.0, "emerging" to 1.5)
        val coreKeys = core.map { it.lowercase() }.toSet()
        val learned = list.map { it to stageOf(it, now) }
            .filter { (e, s) -> s.stage in weights && e.topic.lowercase() !in coreKeys }
            .sortedWith(compareByDescending<Pair<InterestEntry, Stage>> { weights[it.second.stage] }.thenByDescending { it.first.lastSeen })
        val start = if (core.isEmpty()) 0 else ((now / DAY) % core.size).toInt()
        val rotated = if (core.isEmpty()) emptyList() else core.drop(start) + core.take(start)
        val out = mutableListOf<Pick>()
        rotated.firstOrNull()?.let { out += Pick(it, queryFor(it), "core") }
        learned.forEach { (e, s) -> if (out.size < lim && out.none { it.topic == e.topic }) out += Pick(e.topic, queryFor(e.topic), s.stage) }
        rotated.drop(1).forEach { if (out.size < lim && out.none { p -> p.topic == it }) out += Pick(it, queryFor(it), "core") }
        return out.take(lim)
    }
}

object DfyMemory {
    private const val DAY = 86_400_000.0

    /** Base importance per kind (watch .38 / social .58 like the original; chat and todo sit between). */
    fun baseImportance(kind: String): Double = when (kind) {
        EpKind.WATCH -> 0.38; EpKind.SKIP -> 0.2; EpKind.LIVE -> 0.3
        EpKind.CHAT -> 0.5; EpKind.SOCIAL -> 0.58; EpKind.TODO -> 0.5
        else -> 0.4
    }

    /** desiredStage from life-memory-gateway.mjs. */
    fun desiredStage(e: Episode, now: Long, halfLifeDays: Int): MemStage {
        val age = max(0.0, (now - max(e.at, e.reinforcedAt)) / DAY)
        val h = halfLifeDays * (0.6 + e.importance * 1.35) * min(1.45, 1 + 0.06 * e.reinforceCount)
        return when {
            age < h * 0.6 -> MemStage.CLEAR
            age < h * 1.35 -> MemStage.FUZZY
            age < h * 2.6 -> MemStage.TRACE
            else -> MemStage.FORGOTTEN
        }
    }

    /** Fade one episode in place. Details are dropped for good as it fades; forgotten keeps no text. */
    fun fade(e: Episode, to: MemStage): Boolean {
        if (to.ordinal <= e.stage.ordinal) return false
        e.stage = to
        if (to.ordinal >= MemStage.FUZZY.ordinal) { e.bvid = ""; e.title = ""; e.text = "" }
        if (to.ordinal >= MemStage.TRACE.ordinal) e.fuzzy = ""
        if (to == MemStage.FORGOTTEN) { e.trace = ""; e.topics = emptyList(); e.mood = "" }
        return true
    }

    fun decayAll(episodes: List<Episode>, now: Long, halfLifeDays: Int): List<Episode> =
        episodes.map { it.copy() }.onEach { fade(it, desiredStage(it, now, halfLifeDays)) }

    /** Long-term insight decay: much slower; reinforced insights hold longer. Core facts never fade. */
    fun insightFaded(i: Insight, now: Long, halfLifeDays: Int): Boolean {
        if (i.core) return false
        val age = (now - i.reinforcedAt) / DAY
        val h = halfLifeDays * (0.5 + i.importance) * min(2.0, 1 + 0.15 * i.reinforceCount)
        return age > h * 2
    }

    fun newEpisode(kind: String, at: Long, text: String, fuzzy: String, trace: String, bvid: String = "", title: String = "",
                   topics: List<String> = emptyList(), mood: String = "", visibility: String = "public", importance: Double? = null) =
        Episode(id = "e-" + java.util.UUID.randomUUID().toString().take(12), kind = kind, at = at, text = clean(text, 400),
            fuzzy = clean(fuzzy, 160), trace = clean(trace, 100), importance = importance ?: baseImportance(kind), bvid = bvid,
            title = clean(title, 120), topics = topics, mood = mood, visibility = visibility)

    /** Episode for a video whose public material she read and chose (honest wording, as in the original). */
    fun watchEpisode(at: Long, v: BiliVideo, note: String, mood: String, gotSubtitle: Boolean, gotComments: Boolean): Episode {
        val detail = listOfNotNull(if (gotSubtitle) "取得过字幕文本" else null, if (gotComments) "读取过公开评论" else null).joinToString("、")
        val text = "我查看过视频《${v.title}》（UP主 ${v.ownerName}）的公开资料" + (if (detail.isNotEmpty()) "，当时$detail" else "") +
            (if (note.isNotBlank()) "；我的想法：${clean(note, 140)}" else "") + "；这不代表完整看过画面"
        val shortTopic = clean(v.title.replace(Regex("BV[A-Za-z0-9]{10}"), "").replace(Regex("\\d{4,}"), ""), 12)
        return newEpisode(EpKind.WATCH, at, text, "我以前刷到过一段视频，对「${shortTopic.ifBlank { "某个话题" }}」还有些印象",
            "我隐约记得看过一段曾引起我注意的视频", bvid = v.bvid, title = v.title, topics = DfyInterests.topicsOf(v.title + " " + v.tname), mood = mood)
    }

    data class Recall(val id: String, val stage: MemStage, val kind: String, val at: Long?, val text: String, val score: Double)

    private fun bigrams(s: String): Set<String> {
        val t = s.lowercase().replace(Regex("\\s+"), "")
        if (t.length < 2) return if (t.isEmpty()) emptySet() else setOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    /**
     * Recall from what she still remembers: live (non-forgotten) episodes and non-faded insights.
     * Scored by character-bigram overlap + importance + recency. Precise dates only while clear.
     */
    fun recall(s: DfyState, query: String, now: Long, limit: Int = 8, includePrivate: Boolean = true): List<Recall> {
        val q = bigrams(query)
        val eps = s.episodes.filter { it.stage != MemStage.FORGOTTEN && it.recallText().isNotBlank() && (includePrivate || it.visibility == "public") }
            .map { e ->
                val txt = e.recallText()
                val overlap = if (q.isEmpty()) 0.0 else bigrams(txt).intersect(q).size.toDouble() / q.size
                val recency = 1.0 / (1.0 + (now - e.at) / (7 * DAY))
                Recall(e.id, e.stage, e.kind, if (e.stage == MemStage.CLEAR) e.at else null, txt, overlap * 3 + e.importance + recency * 0.5)
            }
        val ins = s.insights.filter { !it.faded }.map { i ->
            val txt = i.insight + (if (i.behavior.isNotBlank()) "；" + i.behavior else "")
            val overlap = if (q.isEmpty()) 0.0 else bigrams(txt).intersect(q).size.toDouble() / q.size
            Recall(i.id, MemStage.CLEAR, if (i.core) "core" else "insight", null, txt, overlap * 3 + i.importance + 0.4)
        }
        val all = (eps + ins).sortedByDescending { it.score }
        return if (q.isEmpty()) all.take(limit) else all.filter { it.score > 0.6 }.take(limit)
    }

    /** Compact memory block for prompts: core facts + top insights + a few recalled episodes. */
    fun promptBlock(s: DfyState, query: String, now: Long, maxChars: Int = 1600): String {
        val sb = StringBuilder()
        val core = s.insights.filter { it.core }
        if (core.isNotEmpty()) sb.append("【主人写下的身份设定】\n").append(core.joinToString("\n") { "- " + it.insight }).append('\n')
        val ins = s.insights.filter { !it.core && !it.faded }.sortedByDescending { it.importance }.take(5)
        if (ins.isNotEmpty()) sb.append("【我的长期认识】\n").append(ins.joinToString("\n") { "- " + it.insight + "；" + it.behavior }).append('\n')
        val rec = recall(s, query, now, 6).filter { it.kind != "insight" && it.kind != "core" }
        if (rec.isNotEmpty()) {
            sb.append("【我还记得的经历（模糊的就只记得这么多）】\n")
            rec.forEach { r -> sb.append("- [").append(r.stage.zh).append("] ").append(r.text).append('\n') }
        }
        return sb.toString().take(maxChars)
    }
}
