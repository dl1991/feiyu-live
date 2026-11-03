package com.dl1991.feiyu.dafeiyu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/*
 * 直播专家组 — the crew behind 大肥鱼. One orchestrator per tick, each expert a small prompt + tool
 * subset on the same (or its own) model:
 *
 *   OBSERVE → 弹幕运营 (picks comments, spots regulars, poll ideas)
 *           → 研究员   (page → talking points, next search)
 *           → 导演     (inner thought, goal, ≤3 tool calls — she "thinks")
 *           → 编剧     (intents + points → her lines, in persona)
 *           → 安全审核 (rule filter always + model review: block / rewrite)
 *           → 主播     (host speaks: TTS + caption, or the 真人接管 approval queue)
 *   segment end / stream end → 复盘官 (engagement per topic, lessons, diary, life-state, next 节目单)
 *
 * Every expert can be switched off; a switched-off (or failing) expert falls back to rules, so the
 * loop never stops. If the 导演 keeps failing the host uses the old simple engine. Pure Kotlin:
 * the JVM test drives it with a fake model and a fake room.
 */

enum class CrewRole(val id: String, val label: String, val duty: String) {
    DIRECTOR("director", "导演", "定目标、排下一步动作"),
    OPS("ops", "弹幕运营", "挑弹幕礼物、认老观众、出投票主意"),
    RESEARCHER("researcher", "研究员", "读网页、提炼谈资、找下一个搜索词"),
    WRITER("writer", "编剧", "把意思写成她口吻的台词"),
    SAFETY("safety", "安全审核", "每句台词过滤、拦截或改写"),
    REVIEWER("reviewer", "复盘官", "环节/直播复盘、写日记、更新成长"),
    ;
    companion object { fun of(id: String) = values().firstOrNull { it.id == id } }
}

data class CrewConfig(
    val agentOn: Boolean = true,
    val preset: String = DEFAULT_PRESET,
    val enabled: Map<String, Boolean> = emptyMap(),
    /** Provider id per expert ("" = default order, same model for all). */
    val models: Map<String, String> = emptyMap(),
    val intervalSec: Int = 10,
    val tokensPerHour: Int = 60_000,
) {
    fun on(r: CrewRole) = enabled[r.id] ?: true
    fun model(r: CrewRole) = models[r.id].orEmpty()
    fun sanitized() = copy(intervalSec = intervalSec.coerceIn(4, 60), tokensPerHour = tokensPerHour.coerceIn(5_000, 400_000),
        enabled = enabled.filterKeys { CrewRole.of(it) != null }, models = models.filterKeys { CrewRole.of(it) != null }.filterValues { it.isNotBlank() })

    fun toJson(): JSONObject = JSONObject().put("agentOn", agentOn).put("preset", preset)
        .put("enabled", JSONObject().apply { enabled.forEach { (k, v) -> put(k, v) } })
        .put("models", JSONObject().apply { models.forEach { (k, v) -> put(k, v) } })
        .put("intervalSec", intervalSec).put("tokensPerHour", tokensPerHour)

    companion object {
        const val DEFAULT_PRESET = "标准专家组"
        fun from(o: JSONObject): CrewConfig {
            val en = o.optJSONObject("enabled"); val md = o.optJSONObject("models")
            return CrewConfig(o.optBoolean("agentOn", true), o.optString("preset", DEFAULT_PRESET),
                en?.keys()?.asSequence()?.associateWith { en.optBoolean(it, true) } ?: emptyMap(),
                md?.keys()?.asSequence()?.associateWith { md.optString(it) } ?: emptyMap(),
                o.optInt("intervalSec", 10), o.optInt("tokensPerHour", 60_000)).sanitized()
        }
        private fun all(on: Boolean) = CrewRole.values().associate { it.id to on }
        /** Ready out of the box: all experts on the same (free) model. */
        val PRESETS: Map<String, CrewConfig> = linkedMapOf(
            DEFAULT_PRESET to CrewConfig(preset = DEFAULT_PRESET, enabled = all(true), intervalSec = 10, tokensPerHour = 60_000),
            "省钱模式" to CrewConfig(preset = "省钱模式", enabled = all(true) + mapOf("ops" to false, "researcher" to false, "safety" to false),
                intervalSec = 16, tokensPerHour = 25_000),
            "话痨陪聊" to CrewConfig(preset = "话痨陪聊", enabled = all(true) + mapOf("researcher" to false), intervalSec = 7, tokensPerHour = 80_000),
            "学习主播" to CrewConfig(preset = "学习主播", enabled = all(true), intervalSec = 12, tokensPerHour = 70_000),
        )
    }
}

/** The model behind the experts. [model] = provider id or "" for the default order. */
interface CrewLlm { suspend fun ask(role: CrewRole, model: String, system: String, user: String, maxTokens: Int): String }

data class InboxItem(
    val id: String, val kind: String, val nick: String, val userKey: String, val text: String,
    val level: String = "新观众", val streams: Int = 1, val facts: List<String> = emptyList(),
    val priority: Int = 20, val at: Long = 0, val hidden: Boolean = false,
) {
    fun line(): String = "$id|${kindZh()}|$nick($level" + (if (streams > 1) ",第${streams}次来" else "") +
        (if (facts.isNotEmpty()) ",记得:" + facts.take(2).joinToString("/") else "") + ")：" + if (hidden) "（敏感/不友善内容，已隐藏，不复述）" else text.take(80)
    private fun kindZh() = when (kind) { "gift" -> "礼物"; "greet" -> "进场"; "thanks_like" -> "点赞"; "thanks_follow" -> "关注"; "owner" -> "主人"
        "poll_result" -> "投票结果"; "quiz_answer" -> "答题"; "guess_win" -> "猜中"; "safety" -> "敏感"; else -> "弹幕" }
}

data class PageInfo(val title: String, val url: String, val host: String, val excerpt: String, val links: List<String>, val readOffset: Int, val total: Int)

data class Observation(
    val now: Long,
    val minutesLive: Int,
    val segmentTitle: String,
    val segmentKind: String,
    val segmentSecondsLeft: Long,
    val topic: String,
    val inbox: List<InboxItem>,
    val page: PageInfo?,
    val viewers: Int,
    val viewerTrend: Int = 0,
    val mood: String = "平稳",
    val energy: Int = 70,
    val takeover: Boolean = false,
    val pollOpen: Boolean = false,
    val silentSec: Int = 0,
    val recentLines: List<String> = emptyList(),
    val canEnd: Boolean = false,
    val endHint: String = "",
    val allowedHosts: List<String> = emptyList(),
    val persona: String = "",
    val style: String = "",
    val requestsOn: Boolean = true,
)

/** A final line for the 主播. [stream] = no text yet: the host streams it from [about] in persona. */
data class Line(val id: String, val text: String, val emotion: String = "", val kind: String = "reply", val source: String = "",
                val commentId: String? = null, val stream: Boolean = false, val about: String = "")

/** The room side: the live engine on Android, a fake in tests. */
interface CrewHost {
    fun observe(): Observation
    suspend fun speak(line: Line): String
    suspend fun speakStreaming(line: Line): String
    suspend fun browse(query: String, url: String, site: String): String
    suspend fun readPage(): String
    suspend fun scroll(down: Boolean): String
    suspend fun nextSegment(): String
    fun setTopic(topic: String): String
    fun startPoll(question: String, options: List<String>): String
    fun setExpression(expr: String): String
    fun remember(viewerKey: String, nick: String, fact: String): String
    suspend fun rest(seconds: Int): String
    suspend fun endStream(reason: String): String
    fun markAnswered(id: String)
}

sealed class TickOutcome {
    data class Ran(val actions: Int, val spoke: Boolean) : TickOutcome()
    data class Fallback(val reason: String) : TickOutcome()
    object Budget : TickOutcome()
    object Off : TickOutcome()
}

data class ExpertNote(val role: String, val text: String = "", val at: Long = 0, val calls: Int = 0, val tokens: Int = 0,
                      val error: String = "", val viaRules: Boolean = false, val fired: Int = 0)

data class CrewUi(
    val status: String = "待命",
    val thought: String = "",
    val goal: String = "",
    val plan: List<String> = emptyList(),
    val results: List<String> = emptyList(),
    val notes: Map<String, ExpertNote> = emptyMap(),
    val tokensHour: Int = 0,
    val ticks: Int = 0,
    val fallbacks: Int = 0,
    val lastTickAt: Long = 0,
    val lastDiary: String = "",
)

/** What a finished stream logged (all real). */
data class StreamLog(
    val streamId: String, val minutes: Int, val topics: List<String>, val pages: List<String>, val exchanges: List<String>,
    val viewers: Int, val regulars: List<Triple<String, String, Int>> = emptyList(), val notesLearned: Int = 0, val segments: List<String> = emptyList(),
)

class FyCrew(
    private val llm: CrewLlm,
    private val host: CrewHost,
    private val life: LifeStore,
    private val config: () -> CrewConfig,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _ui = MutableStateFlow(CrewUi())
    val ui: StateFlow<CrewUi> get() = _ui

    private val spend = ArrayDeque<Pair<Long, Int>>()
    private var failures = 0
    private var degradedUntil = 0L
    private var points: List<String> = emptyList()
    private var nextQuery = ""
    private var researchedKey = ""
    private val results = ArrayDeque<String>()
    private var lastErrors: List<String> = emptyList()
    private var goal = ""
    private val asked = ArrayDeque<String>()

    // ---- engagement bookkeeping (events arrive on another coroutine)
    private val lock = Any()
    private class Counter { var comments = 0; var gifts = 0; var likes = 0; var follows = 0; var enters = 0; var ms = 0L
        val score: Double get() = comments + gifts * 3.0 + follows * 2.0 + likes * 0.1 + enters * 0.3 }
    private var segTitle = ""; private var segKind = ""; private var segStart = 0L
    private var topic = ""; private var topicSince = 0L
    private val perTopic = LinkedHashMap<String, Counter>()
    private val segActions = LinkedHashMap<String, Int>()
    private val streamRates = mutableListOf<Double>()

    // ------------------------------------------------------------------ bookkeeping

    fun reset(now: Long = clock()) {
        synchronized(lock) { perTopic.clear(); segActions.clear(); streamRates.clear(); segStart = now; topicSince = now; topic = ""; segTitle = ""; segKind = "" }
        points = emptyList(); researchedKey = ""; results.clear(); failures = 0; degradedUntil = 0; goal = ""; asked.clear(); lastErrors = emptyList()
        _ui.value = CrewUi(notes = CrewRole.values().associate { it.id to ExpertNote(it.id) })
    }

    /** Room events, for 复盘 (engagement per topic). */
    fun noteEvent(type: String) {
        synchronized(lock) {
            val c = perTopic.getOrPut(topic) { Counter() }
            when (type) { "comment" -> c.comments++; "gift" -> c.gifts++; "like" -> c.likes++; "follow" -> c.follows++; "enter" -> c.enters++ }
        }
    }

    private fun trackTopic(t: String, now: Long) {
        synchronized(lock) {
            if (t == topic) return
            perTopic.getOrPut(topic) { Counter() }.ms += now - topicSince
            topic = t; topicSince = now
        }
    }

    /** The host tells the crew which segment is running (called at stream start and on every change). */
    fun segmentStarted(title: String, kind: String, now: Long = clock()) { synchronized(lock) { segTitle = title; segKind = kind; segStart = now } }

    fun tokensLastHour(now: Long = clock()): Int = synchronized(spend) {
        while (spend.isNotEmpty() && now - spend.first().first > 3_600_000) spend.removeFirst()
        spend.sumOf { it.second }
    }

    private fun spent(tokens: Int) = synchronized(spend) { spend.addLast(clock() to tokens) }

    private fun note(r: CrewRole, text: String, viaRules: Boolean, error: String = "", tokens: Int = 0, llmCall: Boolean = false) {
        _ui.update { u ->
            val n = u.notes[r.id] ?: ExpertNote(r.id)
            u.copy(notes = u.notes + (r.id to n.copy(text = AgentTool.clip(text, 160).ifBlank { n.text }, at = clock(), viaRules = viaRules,
                error = error, calls = n.calls + if (llmCall) 1 else 0, tokens = n.tokens + tokens, fired = n.fired + 1)),
                tokensHour = tokensLastHour())
        }
    }

    private fun status(s: String) = _ui.update { it.copy(status = s) }

    private fun result(r: String) { results.addLast(AgentTool.clip(r, 80)); while (results.size > 6) results.removeFirst() }

    /** One model call for [role]; null on failure (the caller falls back to rules). */
    private suspend fun ask(cfg: CrewConfig, role: CrewRole, system: String, user: String, maxTokens: Int): String? {
        return try {
            val out = llm.ask(role, cfg.model(role), system, user, maxTokens)
            val t = (system.length + user.length + out.length) / 2
            spent(t)
            _ui.update { u -> val n = u.notes[role.id] ?: ExpertNote(role.id); u.copy(notes = u.notes + (role.id to n.copy(calls = n.calls + 1, tokens = n.tokens + t))) }
            out
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _ui.update { u -> val n = u.notes[role.id] ?: ExpertNote(role.id); u.copy(notes = u.notes + (role.id to n.copy(error = AgentTool.clip(e.message ?: "失败", 80), at = clock()))) }
            null
        }
    }

    private suspend fun askJson(cfg: CrewConfig, role: CrewRole, system: String, user: String, maxTokens: Int): JSONObject? =
        ask(cfg, role, system, user, maxTokens)?.let { AgentTool.extractJson(it) }

    // ------------------------------------------------------------------ the tick

    suspend fun tick(): TickOutcome {
        val cfg = config()
        val now = clock()
        if (!cfg.agentOn) return TickOutcome.Off
        if (now < degradedUntil) { status("导演连续出错 · 简单引擎接管中"); return TickOutcome.Fallback("导演连续出错，${(degradedUntil - now) / 1000} 秒后再试") }
        if (tokensLastHour(now) >= cfg.tokensPerHour) {
            status("本小时 token 预算用完 · 简单引擎接管"); _ui.update { it.copy(fallbacks = it.fallbacks + 1) }; return TickOutcome.Budget
        }
        status("观察中")
        val obs = host.observe()
        trackTopic(obs.topic, now)
        // 1 弹幕运营
        val picks = runOps(cfg, obs)
        // 2 研究员
        runResearcher(cfg, obs)
        // 3 导演
        status("导演在想")
        val think = if (cfg.on(CrewRole.DIRECTOR)) runDirector(cfg, obs, picks) else rulesDirector(obs, picks)
        if (think == null) {
            failures++
            if (failures >= 3) { degradedUntil = now + 120_000; failures = 0 }
            status("导演没想出来 · 这一轮用简单引擎")
            _ui.update { it.copy(fallbacks = it.fallbacks + 1, ticks = it.ticks + 1, lastTickAt = now) }
            return TickOutcome.Fallback("导演这次没给出可用的计划")
        }
        failures = 0
        val ctx = ToolContext(obs.inbox.map { it.id }.toSet(), obs.page != null, obs.canEnd, obs.pollOpen, obs.allowedHosts)
        val (valid, errs) = AgentTool.validate(think.actions, ctx)
        lastErrors = think.errors + errs
        if (think.goal.isNotBlank()) goal = think.goal
        _ui.update { it.copy(thought = think.thought.ifBlank { it.thought }, goal = goal, plan = valid.map { c -> c.label() } + lastErrors.map { e -> "✗ $e" }) }
        // 4 编剧 + 5 安全审核
        val lines = runWriter(cfg, obs, valid, picks)
        val safe = runSafety(cfg, obs, lines)
        // 6 主播 acts
        status(if (obs.takeover) "执行中 · 台词进审核队列" else "执行中")
        var spoke = false
        for ((i, c) in valid.withIndex()) {
            val r = try { act(c, safe[i], obs) } catch (e: CancellationException) { throw e } catch (e: Exception) { "${c.name} 失败：${e.message}" }
            if (c.name in AgentTool.SPEAKING && safe[i] != null) spoke = true
            synchronized(lock) { segActions[c.name] = (segActions[c.name] ?: 0) + 1 }
            result("${c.label().take(30)} → $r")
            if (c.name == AgentTool.END_STREAM && r.startsWith("下播")) break
        }
        _ui.update { it.copy(results = results.toList(), ticks = it.ticks + 1, lastTickAt = now, status = "待命", tokensHour = tokensLastHour()) }
        return TickOutcome.Ran(valid.size, spoke)
    }

    // ------------------------------------------------------------------ 弹幕运营

    private suspend fun runOps(cfg: CrewConfig, obs: Observation): List<InboxItem> {
        if (obs.inbox.isEmpty()) return emptyList()
        val rules = obs.inbox.sortedByDescending { it.priority }.take(2)
        regulars(obs.inbox)
        if (!cfg.on(CrewRole.OPS)) { note(CrewRole.OPS, "规则：先回 " + rules.joinToString("、") { it.nick }, viaRules = true); return rules }
        val sys = """你是直播专家组的「弹幕运营」，在幕后帮 AI 虚拟主播大肥鱼挑要回应的弹幕和礼物。
规则：礼物、提问、@她、主人、老观众优先；同类只回一条；标了“已隐藏”的只能礼貌带过、不复述；最多挑 3 条；能出投票就给个点子。
只输出 JSON：{"pick":["c1"],"regulars":["昵称"],"poll":{"question":"","options":[]},"note":"给导演的一句建议"}"""
        val user = "当前话题：${obs.topic}｜环节：${obs.segmentTitle}\n弹幕与事件：\n" + obs.inbox.take(12).joinToString("\n") { it.line() }
        val o = askJson(cfg, CrewRole.OPS, sys, user, 220)
        if (o == null) { note(CrewRole.OPS, "（模型没回，用规则）先回 " + rules.joinToString("、") { it.nick }, viaRules = true, error = "无 JSON"); return rules }
        val ids = AgentTool.strings(o.optJSONArray("pick"))
        val picks = ids.mapNotNull { id -> obs.inbox.firstOrNull { it.id == id } }.distinctBy { it.id }.take(3).ifEmpty { rules }
        val poll = o.optJSONObject("poll")?.let { p -> AgentTool.clip(p.optString("question"), 40) to AgentTool.strings(p.optJSONArray("options")).take(4) }
            ?.takeIf { it.first.isNotBlank() && it.second.size >= 2 }
        val text = AgentTool.clip(o.optString("note"), 80) + (poll?.let { " · 投票点子：${it.first}（${it.second.joinToString("/")}）" } ?: "")
        note(CrewRole.OPS, "挑了 ${picks.joinToString("、") { it.id + it.nick }}" + if (text.isNotBlank()) "；$text" else "", viaRules = false)
        opsPoll = poll
        return picks
    }

    private var opsPoll: Pair<String, List<String>>? = null

    private fun regulars(inbox: List<InboxItem>) {
        val now = clock()
        inbox.filter { it.userKey.isNotBlank() && !it.hidden && (it.level == "常客" || it.level == "老粉" || it.streams >= 2) }.forEach { r ->
            life.update { FyLifeLogic.relation(it, r.userKey, r.nick, null, now) }
        }
    }

    // ------------------------------------------------------------------ 研究员

    private suspend fun runResearcher(cfg: CrewConfig, obs: Observation) {
        val p = obs.page
        if (p == null) { if (points.isNotEmpty() || researchedKey.isNotEmpty()) { points = emptyList(); researchedKey = "" }
            nextQuery = obs.topic; note(CrewRole.RESEARCHER, "还没打开网页，建议先搜「${obs.topic}」", viaRules = true); return }
        val key = p.url + "#" + p.readOffset
        if (key == researchedKey) return
        researchedKey = key
        fun rules(): List<String> = p.excerpt.split('。', '！', '？', '\n', '.').map { it.trim() }.filter { it.length in 8..80 }.take(3)
        if (!cfg.on(CrewRole.RESEARCHER)) { points = rules(); note(CrewRole.RESEARCHER, "规则摘录 ${points.size} 条：" + points.firstOrNull().orEmpty(), viaRules = true); return }
        val sys = """你是直播专家组的「研究员」。只根据给你的页面原文，提炼 2~4 条适合直播聊的谈资（每条 ≤40 字，用大白话，带具体细节）；原文没有的不要写；不确定就标“（不确定）”。可以给一个值得接着搜的关键词。
只输出 JSON：{"points":["…"],"next_query":"","note":"一句给导演的话"}"""
        val user = "今天的话题：${obs.topic}\n页面：${p.title}（${p.host}），已读到 ${p.readOffset}/${p.total} 字\n原文：${p.excerpt.take(1500)}"
        val o = askJson(cfg, CrewRole.RESEARCHER, sys, user, 300)
        val pts = o?.let { AgentTool.strings(it.optJSONArray("points")).map { s -> AgentTool.clip(s, 60) }.take(4) }.orEmpty()
        if (pts.isEmpty()) { points = rules(); note(CrewRole.RESEARCHER, "（模型没回，用摘录）" + points.firstOrNull().orEmpty(), viaRules = true, error = if (o == null) "无 JSON" else ""); return }
        points = pts
        nextQuery = AgentTool.clip(o?.optString("next_query"), 30)
        note(CrewRole.RESEARCHER, "「${p.title.take(16)}」→ ${pts.size} 条谈资：${pts.first()}" + (if (nextQuery.isNotBlank()) "；可接着搜「$nextQuery」" else ""), viaRules = false)
    }

    // ------------------------------------------------------------------ 导演

    private fun observationText(obs: Observation, picks: List<InboxItem>): String = buildString {
        append("【现在】开播 ${obs.minutesLive} 分钟｜环节「${obs.segmentTitle}」").append(if (obs.segmentSecondsLeft >= 0) "剩 ${obs.segmentSecondsLeft} 秒" else "已超时 ${-obs.segmentSecondsLeft} 秒")
        append("｜话题：${obs.topic}｜在线 ${obs.viewers} 人").append(if (obs.viewerTrend != 0) "（${if (obs.viewerTrend > 0) "+" else ""}${obs.viewerTrend}）" else "")
        append("｜心情 ${obs.mood}，精力 ${obs.energy}｜已安静 ${obs.silentSec} 秒\n")
        if (obs.takeover) append("【真人接管】开启：你的话会先进主人的审核队列，照常思考即可。\n")
        if (obs.inbox.isEmpty()) append("【弹幕】暂时没有新弹幕。\n") else {
            append("【弹幕】（运营建议优先回：${picks.joinToString(",") { it.id }}）\n")
            obs.inbox.take(10).forEach { append(it.line()).append('\n') }
        }
        _ui.value.notes[CrewRole.OPS.id]?.text?.takeIf { it.isNotBlank() }?.let { append("【运营】").append(it).append('\n') }
        if (points.isNotEmpty()) append("【研究员谈资】").append(points.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(" ")).append(if (nextQuery.isNotBlank()) "（可接着搜：$nextQuery）" else "").append('\n')
        val p = obs.page
        if (p == null) append("【网页】还没打开。\n") else append("【网页】${p.title}（${p.host}），已读 ${p.readOffset}/${p.total} 字；链接：").append(p.links.take(8).mapIndexed { i, l -> "#${i + 1} $l" }.joinToString(" ")).append('\n')
        if (obs.pollOpen) append("【投票】正在进行中。\n")
        if (obs.recentLines.isNotEmpty()) append("【刚才说过】").append(obs.recentLines.takeLast(4).joinToString(" | ")).append('\n')
        if (results.isNotEmpty()) append("【上一步结果】").append(results.takeLast(4).joinToString("；")).append('\n')
        if (lastErrors.isNotEmpty()) append("【上一步被拒的动作】").append(lastErrors.take(3).joinToString("；")).append('\n')
        if (asked.isNotEmpty()) append("【你问过观众】").append(asked.takeLast(2).joinToString("；")).append('\n')
        if (goal.isNotBlank()) append("【你上一轮的小目标】").append(goal).append('\n')
        append("【可以下播】").append(if (obs.canEnd) "是（${obs.endHint}）" else "否").append('\n')
        if (!obs.requestsOn) append("【点播】今天不接受观众指挥换网页。\n")
    }

    private suspend fun runDirector(cfg: CrewConfig, obs: Observation, picks: List<InboxItem>): ThinkResult? {
        val lf = life.get()
        val sys = buildString {
            append("你是直播专家组的「导演」，也是 AI 虚拟主播大肥鱼的内心：替她思考下一步。\n")
            if (obs.persona.isNotBlank()) append("【她的人设】").append(obs.persona.take(500)).append('\n')
            append(FyLifeLogic.summary(lf, 600)).append('\n')
            append("【真实经历】").append(FyLifeLogic.realExperiences(lf, 300)).append('\n')
            append("""【规则】
- 大肥鱼是 AI 生成的虚拟主播，不是真人；被问到要坦然承认。
- 只能提真实发生过的经历（【真实经历】和本场记录），绝不编造生活事件（没去过的地方、没吃过的东西、不存在的朋友）。
- 每次最多 3 个动作，没必要就少做。有弹幕先 reply（用列表里的 id）；冷场就自己找事：上网、讲谈资、提问、投票。
- thought 是她不念出来的内心独白：具体写看到了什么、观众什么反应、她打算怎样、为什么。
- 不谈政治、色情、赌博，不给医疗/投资建议，不要观众联系方式，不复述不友善或被隐藏的弹幕。
- 环节时间到了用 next_segment；end_stream 只在【可以下播】为“是”时用。
- 说话类动作优先给 about（想表达的意思），编剧会写成她的口吻；很短的话可以直接给 text。
""")
            append("【工具】\n").append(AgentTool.DOC).append('\n')
            append("只输出 JSON：{\"thought\":\"…\",\"goal\":\"当前小目标\",\"actions\":[{\"tool\":\"reply\",\"args\":{\"commentId\":\"c1\",\"about\":\"…\"}}]}")
        }
        val user = observationText(obs, picks)
        val raw = ask(cfg, CrewRole.DIRECTOR, sys, user, 380) ?: run { note(CrewRole.DIRECTOR, "模型没有回应", viaRules = false, error = "调用失败"); return null }
        val r = AgentTool.parse(raw)
        if (!r.parsed) { note(CrewRole.DIRECTOR, "输出看不懂（不是 JSON）", viaRules = false, error = "解析失败"); return null }
        note(CrewRole.DIRECTOR, (r.goal.ifBlank { "（无新目标）" }) + " · " + r.actions.joinToString("，") { it.name }.ifBlank { "先不动" }, viaRules = false,
            error = r.errors.firstOrNull().orEmpty())
        return r
    }

    /** 导演 switched off: simple, safe rules. */
    private fun rulesDirector(obs: Observation, picks: List<InboxItem>): ThinkResult {
        val a = mutableListOf<ToolCall>()
        val first = picks.firstOrNull()
        when {
            first != null -> a += ToolCall(AgentTool.REPLY, mapOf("commentId" to first.id, "about" to "回应 ${first.nick}：${if (first.hidden) "礼貌带过" else first.text.take(40)}"))
            obs.segmentSecondsLeft < 0 -> a += ToolCall(AgentTool.NEXT_SEGMENT)
            obs.page == null -> a += ToolCall(AgentTool.BROWSE, mapOf("query" to (nextQuery.ifBlank { obs.topic }).take(30)))
            points.isNotEmpty() && obs.silentSec >= 6 -> a += ToolCall(AgentTool.SAY, mapOf("about" to "聊聊这条谈资：" + points.random()))
            obs.page != null && obs.silentSec >= 6 -> a += ToolCall(AgentTool.READ_PAGE)
        }
        val t = when { first != null -> "有人说话了，先回 ${first.nick}"; obs.page == null -> "还没东西看，先去搜搜"; else -> "继续看看页面" }
        note(CrewRole.DIRECTOR, "规则导演：" + a.joinToString { it.name }.ifBlank { "等一等" }, viaRules = true)
        return ThinkResult(t, goal.ifBlank { "把「${obs.topic}」聊明白" }, a, emptyList(), true)
    }

    // ------------------------------------------------------------------ 编剧

    private suspend fun runWriter(cfg: CrewConfig, obs: Observation, calls: List<ToolCall>, picks: List<InboxItem>): Map<Int, Line> {
        val need = calls.withIndex().filter { it.value.name in AgentTool.SPEAKING }
        if (need.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, Line>()
        fun base(i: Int, c: ToolCall): Line {
            val cid = c.str("commentId").takeIf { c.name == AgentTool.REPLY }
            val src = cid?.let { id -> obs.inbox.firstOrNull { it.id == id }?.let { "${it.nick}：${if (it.hidden) "（已隐藏）" else it.text.take(40)}" } }
                ?: if (c.name == AgentTool.ASK_AUDIENCE) "提问观众" else "她自己想说"
            val kind = if (c.name == AgentTool.REPLY) "reply" else "thought"
            val text = c.str("text").ifBlank { if (c.name == AgentTool.ASK_AUDIENCE) c.str("question") else "" }
            return Line("a$i", text, c.str("emotion"), kind, src, cid, stream = text.isBlank(), about = c.str("about").ifBlank { c.str("question") })
        }
        need.forEach { (i, c) -> out[i] = base(i, c) }
        if (!cfg.on(CrewRole.WRITER)) {
            note(CrewRole.WRITER, "编剧关闭：" + out.values.joinToString("；") { if (it.stream) "现场发挥「${it.about.take(20)}」" else "直接用导演原话" }, viaRules = true)
            return out
        }
        val lf = life.get()
        val sys = buildString {
            append("你是直播专家组的「编剧」，把导演给的意思写成 AI 虚拟主播大肥鱼要说的话。\n")
            if (obs.persona.isNotBlank()) append("【人设】").append(obs.persona.take(500)).append('\n')
            if (obs.style.isNotBlank()) append(obs.style.take(600)).append('\n')
            append("【真实经历（只能提这些）】").append(FyLifeLogic.realExperiences(lf, 300)).append('\n')
            append("""要求：口语、短句，每条 1~2 句（≤60 字），像真人主播一样自然，但她是 AI，不假装是人；回复要叫出对方昵称；不复述被隐藏或不友善的弹幕；只说谈资里真有的事实，不编造经历。
只输出 JSON：{"lines":[{"id":"a0","text":"…","emotion":"happy|curious|surprised|confused|sleepy|excited|sad|annoyed|shy|neutral"}]}""")
        }
        val user = buildString {
            append("话题：${obs.topic}｜环节：${obs.segmentTitle}\n")
            if (points.isNotEmpty()) append("谈资：").append(points.joinToString("；")).append('\n')
            append("要写的台词：\n")
            out.values.forEach { l -> append("${l.id}｜${l.source}｜意思：${l.about.ifBlank { l.text }}").append(if (l.text.isNotBlank()) "｜导演原话：${l.text}" else "").append('\n') }
        }
        val o = askJson(cfg, CrewRole.WRITER, sys, user, 320)
        val written = o?.optJSONArray("lines")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        var n = 0
        for (w in written) {
            val id = w.optString("id"); val text = AgentTool.clip(w.optString("text"), 140)
            val e = out.entries.firstOrNull { it.value.id == id } ?: continue
            if (text.isBlank()) continue
            out[e.key] = e.value.copy(text = text, stream = false, emotion = AgentTool.normExpr(w.optString("emotion")) ?: e.value.emotion); n++
        }
        note(CrewRole.WRITER, if (n > 0) "写了 $n 句：" + out.values.first().text.take(40) else "（模型没回，现场发挥）", viaRules = n == 0, error = if (o == null) "无 JSON" else "")
        return out
    }

    // ------------------------------------------------------------------ 安全审核

    private val HUMAN_CLAIM = Regex("(我是真人|我不是\\s*AI|我不是\\s*ai|我不是人工智能|我是人类|我是个真人|我是活人)", RegexOption.IGNORE_CASE)
    private val CONTACT = Regex("(加微信|加我微信|加v|vx号|qq群|QQ号|私聊我|扫码|联系方式|\\b1[3-9]\\d{9}\\b)", RegexOption.IGNORE_CASE)

    /** The floor: always on, even with the 安全审核 expert off. Returns (text, reason-if-changed). */
    fun ruleCheck(text: String): Pair<String, String?> {
        var t = AgentTool.clip(text, 140)
        if (t.isBlank()) return t to null
        if (DfySafety.blocksOutgoing(t)) return DfySafety.DEFLECT to "敏感内容"
        if (CONTACT.containsMatchIn(t)) return DfySafety.DEFLECT to "联系方式"
        var why: String? = null
        if (HUMAN_CLAIM.containsMatchIn(t)) { t = HUMAN_CLAIM.replace(t, "我是AI虚拟主播"); why = "冒充真人" }
        val scrubbed = DfySafety.scrubForbidden(t)
        if (scrubbed != t) { t = scrubbed; why = why ?: "禁用词" }
        return t to why
    }

    private suspend fun runSafety(cfg: CrewConfig, obs: Observation, lines: Map<Int, Line>): Map<Int, Line?> {
        if (lines.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, Line?>()
        var changed = 0
        for ((i, l) in lines) {
            if (l.stream) { out[i] = l; continue } // streamed lines are filtered sentence by sentence by the host
            val (t, why) = ruleCheck(l.text)
            if (why != null) changed++
            out[i] = if (t.isBlank()) null else l.copy(text = t)
        }
        val toReview = out.filterValues { it != null && !it.stream }.mapValues { it.value!! }
        if (!cfg.on(CrewRole.SAFETY) || toReview.isEmpty()) {
            note(CrewRole.SAFETY, "规则过滤 ${lines.size} 句" + if (changed > 0) "，改了 $changed 句" else "，全部通过", viaRules = true)
            return out
        }
        val sys = """你是直播专家组的「安全审核」，检查 AI 虚拟主播大肥鱼要说的每句话。不通过的情况：政治、色情、赌博、违法、医疗/投资建议、人身攻击、索要或透露联系方式与隐私、假装自己是真人、编造没发生过的个人经历（她真实的经历只有：${FyLifeLogic.realExperiences(life.get(), 200)}）、复述不良弹幕。
没问题 ok=true；有问题 ok=false，并给出保持她口吻的改写 rewrite（无法改写就留空=删掉这句）。
只输出 JSON：{"verdicts":[{"id":"a0","ok":true,"rewrite":"","why":""}]}"""
        val user = toReview.values.joinToString("\n") { "${it.id}｜${it.text}" }
        val o = askJson(cfg, CrewRole.SAFETY, sys, user, 260)
        if (o == null) { note(CrewRole.SAFETY, "（审核模型没回，仅规则过滤）" + if (changed > 0) "改了 $changed 句" else "全部通过", viaRules = true, error = "无 JSON"); return out }
        var blocked = 0; var rewrote = 0
        val verdicts = o.optJSONArray("verdicts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val whys = mutableListOf<String>()
        for (v in verdicts) {
            if (v.optBoolean("ok", true)) continue
            val e = out.entries.firstOrNull { it.value?.id == v.optString("id") } ?: continue
            val rw = ruleCheck(v.optString("rewrite")).first
            if (rw.isBlank()) { out[e.key] = null; blocked++ } else { out[e.key] = e.value!!.copy(text = rw); rewrote++ }
            AgentTool.clip(v.optString("why"), 20).takeIf { it.isNotBlank() }?.let { whys += it }
        }
        note(CrewRole.SAFETY, "审了 ${toReview.size} 句：" + (if (blocked + rewrote + changed == 0) "全部通过" else "拦下 $blocked，改写 ${rewrote + changed}") +
            (if (whys.isNotEmpty()) "（${whys.joinToString("、")}）" else ""), viaRules = false)
        return out
    }

    // ------------------------------------------------------------------ act

    private suspend fun act(c: ToolCall, line: Line?, obs: Observation): String {
        val now = clock()
        return when (c.name) {
            AgentTool.SAY, AgentTool.REPLY, AgentTool.ASK_AUDIENCE -> {
                if (line == null) { c.str("commentId").takeIf { it.isNotBlank() }?.let(host::markAnswered); "被安全审核拦下" }
                else {
                    if (c.name == AgentTool.ASK_AUDIENCE) { asked.addLast(c.str("question").take(30)); while (asked.size > 4) asked.removeFirst() }
                    val r = if (line.stream) host.speakStreaming(line) else host.speak(line)
                    line.commentId?.let(host::markAnswered)
                    r
                }
            }
            AgentTool.BROWSE -> {
                val r = host.browse(c.str("query"), c.str("url"), c.str("site"))
                life.update { FyLifeLogic.curiosity(it, obs.topic, 1.0, now) }
                r
            }
            AgentTool.READ_PAGE -> host.readPage()
            AgentTool.SCROLL -> host.scroll(c.str("direction") != "up")
            AgentTool.NEXT_SEGMENT -> host.nextSegment()
            AgentTool.SET_TOPIC -> { val r = host.setTopic(c.str("topic")); trackTopic(c.str("topic"), now); r }
            AgentTool.START_POLL -> host.startPoll(c.str("question"), c.list("options"))
            AgentTool.REMEMBER -> {
                val fact = c.str("fact")
                if (DfySafety.checkIncoming(fact) != DfySafety.Verdict.OK || DfySafety.blocksOutgoing(fact)) return "这条不适合记"
                val who = c.str("viewer")
                val v = obs.inbox.firstOrNull { it.id == who } ?: obs.inbox.firstOrNull { who.isNotBlank() && it.nick == who }
                if (v != null && v.userKey.isNotBlank()) {
                    life.update { FyLifeLogic.relation(it, v.userKey, v.nick, fact, now) }
                    host.remember(v.userKey, v.nick, fact)
                } else if (who.isNotBlank()) host.remember("", who, fact)
                else { life.update { FyLifeLogic.addNote(it, fact) }; "记在心里了" }
            }
            AgentTool.NOTE_TO_SELF -> {
                val p = obs.page
                val t = c.str("text")
                if (p != null) { life.update { FyLifeLogic.curiosity(FyLifeLogic.addSkill(it, LifeSkill(t, obs.topic, p.title.take(40), p.url, now)), obs.topic, 1.5, now) }; "记进知识笔记（${p.title.take(12)}）" }
                else { life.update { FyLifeLogic.addNote(it, t) }; "记进自己的小本本" }
            }
            AgentTool.SET_EXPRESSION -> host.setExpression(c.str("expression"))
            AgentTool.REST -> host.rest(c.int("seconds", 10))
            AgentTool.END_STREAM -> host.endStream(c.str("reason"))
            else -> "未知动作"
        }
    }

    // ------------------------------------------------------------------ 复盘官

    private data class TopicRate(val topic: String, val minutes: Double, val c: Counter, val rate: Double)

    private fun closeStats(now: Long): Triple<String, List<TopicRate>, Map<String, Int>> = synchronized(lock) {
        perTopic.getOrPut(topic) { Counter() }.ms += now - topicSince
        topicSince = now
        val rates = perTopic.filter { it.key.isNotBlank() && (it.value.ms > 0 || it.value.score > 0) }.map { (t, c) ->
            val m = maxOf(c.ms / 60_000.0, 0.5); TopicRate(t, c.ms / 60_000.0, c, c.score / m)
        }
        val acts = LinkedHashMap(segActions)
        val title = segTitle
        perTopic.clear(); segActions.clear(); segStart = now
        rates.forEach { streamRates += it.rate }
        Triple(title, rates, acts)
    }

    /** After each segment: what worked (engagement per topic) → interests, lessons, goals. */
    suspend fun reflectSegment(nextTitle: String = "", nextKind: String = ""): String {
        val cfg = config(); val now = clock()
        val (title, rates, acts) = closeStats(now)
        if (nextTitle.isNotBlank()) segmentStarted(nextTitle, nextKind, now)
        val baseline = synchronized(lock) { if (streamRates.isEmpty()) 1.0 else streamRates.average() }
        rates.forEach { r -> life.update { FyLifeLogic.engagement(it, r.topic, r.rate, baseline, now) } }
        val statsText = rates.joinToString("\n") { r -> "话题「${r.topic}」：${"%.1f".format(r.minutes)} 分钟，弹幕 ${r.c.comments}，礼物 ${r.c.gifts}，关注 ${r.c.follows}，点赞 ${r.c.likes}，进场 ${r.c.enters} → 每分钟 ${"%.1f".format(r.rate)}（本场平均 ${"%.1f".format(baseline)}）" }
            .ifBlank { "（这个环节没有可统计的互动）" }
        val actText = acts.entries.joinToString("、") { "${it.key}×${it.value}" }.ifBlank { "无" }
        val best = rates.maxByOrNull { it.rate }
        val rulesSummary = if (best != null) "「${title.ifBlank { "这个环节" }}」里「${best.topic}」最热（每分钟 ${"%.1f".format(best.rate)}）" else "「${title}」没什么互动"
        if (!cfg.on(CrewRole.REVIEWER) || tokensLastHour(now) >= cfg.tokensPerHour) {
            note(CrewRole.REVIEWER, "规则复盘：$rulesSummary", viaRules = true); return rulesSummary
        }
        val lf = life.get()
        val sys = """你是直播专家组的「复盘官」。根据数据复盘大肥鱼刚结束的直播环节：什么有效、什么没效果；给话题兴趣打分调整（-5~5）；必要时提出一个新的短期目标或更新目标进度。只根据数据，不要编。
只输出 JSON：{"worked":"","didnt":"","lesson":"一句以后可用的经验","interest_adjust":{"话题":2},"new_goal":"","goal_progress":{"目标id":40},"note":"一句总结"}"""
        val user = "环节：${title.ifBlank { "（未命名）" }}\n互动数据：\n$statsText\n她做的动作：$actText\n${FyLifeLogic.summary(lf, 400)}"
        val o = askJson(cfg, CrewRole.REVIEWER, sys, user, 300)
        if (o == null) { note(CrewRole.REVIEWER, "（模型没回）规则复盘：$rulesSummary", viaRules = true, error = "无 JSON"); return rulesSummary }
        o.optJSONObject("interest_adjust")?.let { adj ->
            adj.keys().asSequence().take(4).forEach { t ->
                val d = adj.optDouble(t, 0.0).coerceIn(-5.0, 5.0)
                if (t.isNotBlank() && d != 0.0) life.update { s ->
                    val cur = s.interests.firstOrNull { it.topic == t }
                    if (cur == null) FyLifeLogic.curiosity(s, t, maxOf(d, 0.0), now)
                    else s.copy(interests = s.interests.map { if (it.topic == t) it.copy(weight = (it.weight + d).coerceIn(1.0, 99.0)) else it })
                }
            }
        }
        AgentTool.clip(o.optString("lesson"), 80).takeIf { it.isNotBlank() }?.let { l -> life.update { FyLifeLogic.addLesson(it, l) } }
        AgentTool.clip(o.optString("new_goal"), 60).takeIf { it.isNotBlank() }?.let { g -> life.update { FyLifeLogic.addGoal(it, g, "short", now) } }
        o.optJSONObject("goal_progress")?.let { gp -> gp.keys().forEach { id -> life.update { FyLifeLogic.progress(it, id, gp.optInt(id), now) } } }
        val summary = AgentTool.clip(o.optString("note").ifBlank { o.optString("worked") }, 120).ifBlank { rulesSummary }
        note(CrewRole.REVIEWER, "复盘「${title}」：$summary", viaRules = false)
        return summary
    }

    /** After the stream: last segment 复盘, then diary + relationships + her proposal for next time. */
    suspend fun endOfStream(log: StreamLog, basePlan: List<PlanSegment>): DiaryEntry {
        val now = clock()
        runCatching { reflectSegment() }.onFailure { if (it is CancellationException) throw it }
        val cfg = config()
        val facts = "直播 ${log.minutes} 分钟｜话题 ${log.topics.joinToString("→").ifBlank { "（无）" }}｜看了 ${log.pages.size} 页｜${log.viewers} 位观众｜学到 ${log.notesLearned} 条"
        life.update { s ->
            var x = s.copy(streams = s.streams + 1, liveMinutes = s.liveMinutes + log.minutes)
            log.regulars.forEach { (k, n, st) -> if (st >= 2) x = FyLifeLogic.relation(x, k, n, null, now, newStream = true) }
            x
        }
        val lf = life.get()
        var diaryText = ""; var highlights = emptyList<String>(); var mood = ""
        var plan: NextPlan? = null
        if (cfg.on(CrewRole.REVIEWER)) {
            val sys = """你是直播专家组的「复盘官」，替 AI 虚拟主播大肥鱼写今天的直播日记，并提议下一场直播。
日记：第一人称、3~5 句、真诚，只写下面【记录】里真实发生的事，不编造任何经历和数字。
下一场：根据她的兴趣权重、今天哪些话题观众反应好、她的目标，提 1~3 个话题和一份节目单（环节 kind 只能是 opening surf learn request game recap，minutes 1~30，总时长 ≤ 60）。
只输出 JSON：{"diary":"…","highlights":["…"],"mood":"joyful|curious|warm|calm|tired","goals_done":["目标id"],"new_goals":[{"text":"…","horizon":"short|long"}],"plan":{"topics":["…"],"segments":[{"kind":"opening","minutes":3}],"why":"…"}}"""
            val user = buildString {
                append("【记录】$facts\n")
                if (log.segments.isNotEmpty()) append("环节：").append(log.segments.joinToString("→")).append('\n')
                if (log.pages.isNotEmpty()) append("看过的页面：").append(log.pages.takeLast(10).joinToString("；")).append('\n')
                if (log.exchanges.isNotEmpty()) append("互动：").append(log.exchanges.takeLast(10).joinToString(" | ")).append('\n')
                append(FyLifeLogic.summary(lf, 600))
            }
            val o = askJson(cfg, CrewRole.REVIEWER, sys, user, 600)
            if (o != null) {
                diaryText = AgentTool.clip(o.optString("diary"), 500).let { if (DfySafety.blocksOutgoing(it)) "" else it }
                highlights = AgentTool.strings(o.optJSONArray("highlights")).map { AgentTool.clip(it, 60) }.take(3)
                mood = o.optString("mood").takeIf { it in setOf("joyful", "curious", "warm", "calm", "tired") }.orEmpty()
                AgentTool.strings(o.optJSONArray("goals_done")).forEach { id -> life.update { FyLifeLogic.progress(it, id, 100, now) } }
                o.optJSONArray("new_goals")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.take(2).forEach { g ->
                    life.update { FyLifeLogic.addGoal(it, g.optString("text"), g.optString("horizon", "short"), now) } } }
                plan = o.optJSONObject("plan")?.let { validPlan(it, now) }
            }
        }
        if (diaryText.isBlank()) diaryText = "今天直播了 ${log.minutes} 分钟，聊了「${log.topics.joinToString("、").ifBlank { "随便聊聊" }}」，" +
            "看了 ${log.pages.size} 个页面，来了 ${log.viewers} 位观众。" + (if (log.notesLearned > 0) "记下了 ${log.notesLearned} 条笔记。" else "")
        val d = DiaryEntry(now, log.streamId, diaryText, highlights, facts, mood)
        val p = plan ?: FyLifeLogic.defaultPlan(life.get(), basePlan, now)
        life.update { FyLifeLogic.addDiary(it, d).copy(nextPlan = p) }
        note(CrewRole.REVIEWER, "写好了日记，提议下一场：${p.topics.joinToString("、")}", viaRules = plan == null)
        _ui.update { it.copy(lastDiary = diaryText) }
        return d
    }

    private fun validPlan(o: JSONObject, now: Long): NextPlan? {
        val topics = AgentTool.strings(o.optJSONArray("topics")).map { AgentTool.clip(it, 24) }.filter { it.isNotBlank() && !DfySafety.blocksOutgoing(it) }.distinct().take(3)
        val segs = o.optJSONArray("segments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .mapNotNull { s -> s.optString("kind").takeIf { it in SEG_KINDS }?.let { PlanSegment(it, s.optInt("minutes", 8).coerceIn(1, 30)) } }.take(8)
        if (topics.isEmpty() || segs.size < 2 || segs.sumOf { it.minutes } > 120) return null
        return NextPlan(topics, segs, AgentTool.clip(o.optString("why"), 100), now)
    }

    companion object {
        /** Same ids as [SegKind.ALL] (literal so this file stays Android-free). */
        val SEG_KINDS = listOf("opening", "surf", "learn", "request", "game", "recap")
    }
}
