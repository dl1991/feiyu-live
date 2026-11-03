package com.dl1991.feiyu.dafeiyu

import org.json.JSONArray
import org.json.JSONObject

/*
 * 大肥鱼 agent tools: the JSON "function calls" the 导演 (and the heuristic fallbacks) emit.
 * Pure Kotlin + org.json (no Android) so parsing / validation is unit-tested on the JVM.
 *
 * Accepted shapes (models differ):
 *   {"thought":"…","goal":"…","actions":[{"tool":"say","args":{"text":"…"}}]}
 *   {"thought":"…","plan":[{"name":"reply","arguments":{…}}]}
 *   {"tool_calls":[{"function":{"name":"say","arguments":"{\"text\":\"…\"}"}}]}   (OpenAI style, arguments as a string)
 *   {"tool":"say","text":"…"}                                                      (single flattened call)
 */

/** One validated tool call. Args are already trimmed / clamped. */
data class ToolCall(val name: String, val args: Map<String, Any> = emptyMap()) {
    fun str(k: String): String = (args[k] as? String).orEmpty()
    @Suppress("UNCHECKED_CAST") fun list(k: String): List<String> = (args[k] as? List<String>).orEmpty()
    fun int(k: String, def: Int): Int = (args[k] as? Int) ?: def
    /** Short human label for the 「她在想」 panel. */
    fun label(): String = when (name) {
        AgentTool.SAY -> "说：" + str("text").ifBlank { str("about") }
        AgentTool.REPLY -> "回 ${str("commentId")}：" + str("text").ifBlank { str("about") }
        AgentTool.BROWSE -> "浏览：" + str("url").ifBlank { str("site").let { s -> if (s.isBlank()) "" else "$s|" } + str("query") }
        AgentTool.READ_PAGE -> "细读当前页"
        AgentTool.SCROLL -> "滚动页面"
        AgentTool.NEXT_SEGMENT -> "进入下一环节"
        AgentTool.SET_TOPIC -> "换话题：" + str("topic")
        AgentTool.START_POLL -> "投票：" + str("question") + " " + list("options").joinToString("/")
        AgentTool.ASK_AUDIENCE -> "问观众：" + str("question").ifBlank { str("about") }
        AgentTool.REMEMBER -> "记住：" + str("viewer").let { if (it.isBlank()) "" else "$it · " } + str("fact")
        AgentTool.NOTE_TO_SELF -> "笔记：" + str("text")
        AgentTool.SET_EXPRESSION -> "表情：" + str("expression")
        AgentTool.REST -> "歇 ${int("seconds", 10)} 秒"
        AgentTool.END_STREAM -> "下播：" + str("reason")
        else -> name
    }.take(80)
}

data class ThinkResult(
    val thought: String,
    val goal: String,
    val actions: List<ToolCall>,
    val errors: List<String>,
    /** True when the reply had a JSON object at all. */
    val parsed: Boolean,
)

/** What the room looks like right now, for contextual validation. */
data class ToolContext(
    val commentIds: Set<String> = emptySet(),
    val hasPage: Boolean = false,
    val canEnd: Boolean = false,
    val pollOpen: Boolean = false,
    /** Host suffixes she may open directly (search engines are always allowed through browse(query)). */
    val allowedHosts: List<String> = emptyList(),
    val sites: List<String> = AgentTool.SITES,
)

object AgentTool {
    const val SAY = "say"; const val REPLY = "reply"; const val BROWSE = "browse"; const val READ_PAGE = "read_page"
    const val SCROLL = "scroll"; const val NEXT_SEGMENT = "next_segment"; const val SET_TOPIC = "set_topic"
    const val START_POLL = "start_poll"; const val ASK_AUDIENCE = "ask_audience"; const val REMEMBER = "remember"
    const val NOTE_TO_SELF = "note_to_self"; const val SET_EXPRESSION = "set_expression"; const val REST = "rest"
    const val END_STREAM = "end_stream"
    val ALL = listOf(SAY, REPLY, BROWSE, READ_PAGE, SCROLL, NEXT_SEGMENT, SET_TOPIC, START_POLL, ASK_AUDIENCE, REMEMBER,
        NOTE_TO_SELF, SET_EXPRESSION, REST, END_STREAM)
    /** Tools whose output is spoken (go through 编剧 + 安全审核, and the approval queue in 真人接管). */
    val SPEAKING = setOf(SAY, REPLY, ASK_AUDIENCE)
    const val MAX_ACTIONS = 3
    val SITES = listOf("bing", "ddg", "wiki", "baike", "bili", "github", "news")
    /** Same ids as [Expr.ALL] (kept literal so this file stays Android-free). */
    val EXPRESSIONS = listOf("neutral", "happy", "curious", "surprised", "confused", "sleepy", "excited", "sad", "annoyed", "shy")
    private val EXPR_ZH = mapOf("平静" to "neutral", "开心" to "happy", "高兴" to "happy", "好奇" to "curious", "惊讶" to "surprised",
        "疑惑" to "confused", "困" to "sleepy", "犯困" to "sleepy", "兴奋" to "excited", "难过" to "sad", "低落" to "sad",
        "不爽" to "annoyed", "生气" to "annoyed", "害羞" to "shy", "joyful" to "happy", "angry" to "annoyed", "tired" to "sleepy")

    fun normExpr(raw: String): String? {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return null
        return t.takeIf { it in EXPRESSIONS } ?: EXPR_ZH[raw.trim()] ?: EXPR_ZH[t]
    }

    /** Tool list for the 导演's prompt (names, args, one-line purpose). */
    val DOC = """
say{text?,about?,emotion?}: 对全场说一句（text=原话，或 about=想表达的意思交给编剧写）
reply{commentId,text?,about?,emotion?}: 回复某条弹幕/礼物（commentId 用列表里的 id，如 c12）
browse{query?,site?,url?}: 上网：query=搜索词（site: bing ddg wiki baike bili github news），或 url=当前页链接编号如 "#3"
read_page{}: 继续往下读当前页面
scroll{direction?}: 滚动页面 down|up
next_segment{}: 进入节目单的下一个环节
set_topic{topic}: 换今天的话题
start_poll{question,options[2-4]}: 发起投票
ask_audience{question}: 向观众提问（会念出来）
remember{fact,viewer?}: 记住观众公开说过的一件小事（viewer=昵称或 commentId），或你学到的事实
note_to_self{text}: 给自己记一条笔记（读网页学到的要点、下次想做的事），不念出来
set_expression{expression}: 换表情 neutral happy curious surprised confused sleepy excited sad annoyed shy
rest{seconds}: 安静歇一会儿（3-30 秒）
end_stream{reason}: 下播（只有到了时长/预算上限才允许）
""".trim()

    // ------------------------------------------------------------------ parsing

    fun parse(raw: String?): ThinkResult {
        val o = extractJson(raw.orEmpty()) ?: return ThinkResult("", "", emptyList(), listOf("没有找到 JSON"), false)
        val thought = s(o, "thought", "inner_thought", "think", "想法").take(200)
        val goal = s(o, "goal", "current_goal", "目标").take(80)
        val errors = mutableListOf<String>()
        val rawCalls = mutableListOf<JSONObject>()
        for (k in listOf("actions", "plan", "tool_calls", "calls", "tools")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { rawCalls += it } ?: errors.add("$k[$i] 不是对象")
            break
        }
        if (rawCalls.isEmpty() && (o.has("tool") || o.has("name"))) rawCalls += o
        val calls = mutableListOf<ToolCall>()
        for (c in rawCalls) {
            val r = toCall(c)
            if (r.first != null) calls += r.first!! else errors += r.second!!
        }
        if (calls.size > MAX_ACTIONS) { errors += "动作超过 $MAX_ACTIONS 个，只取前 $MAX_ACTIONS 个"; }
        return ThinkResult(thought, goal, calls.take(MAX_ACTIONS), errors, true)
    }

    private fun s(o: JSONObject, vararg keys: String): String {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return clip(o.optString(k), 400)
        return ""
    }

    /** One raw call object → validated [ToolCall] or an error. */
    fun toCall(c: JSONObject): Pair<ToolCall?, String?> {
        val fn = c.optJSONObject("function")
        val name = (fn?.optString("name") ?: c.optString("tool").ifBlank { c.optString("name") }).trim().lowercase()
        if (name.isBlank()) return null to "缺少工具名"
        val argsRaw: Any? = fn?.opt("arguments") ?: c.opt("args") ?: c.opt("arguments") ?: c.opt("parameters")
        val args: JSONObject = when (argsRaw) {
            is JSONObject -> argsRaw
            is String -> extractJson(argsRaw) ?: JSONObject()
            else -> JSONObject(c.toString()).apply { remove("tool"); remove("name") } // flattened
        }
        return shape(name, args)
    }

    /** Static validation: required args, enums, lengths. */
    fun shape(name: String, a: JSONObject): Pair<ToolCall?, String?> {
        if (name !in ALL) return null to "未知工具 $name"
        val m = LinkedHashMap<String, Any>()
        fun put(k: String, max: Int, vararg alias: String) {
            val v = (listOf(k) + alias).firstNotNullOfOrNull { key -> a.optString(key, "").takeIf { a.has(key) && !a.isNull(key) && it.isNotBlank() } }
            if (v != null) m[k] = clip(v, max)
        }
        fun need(vararg k: String): String? = if (k.none { m.containsKey(it) }) "$name 缺少参数 ${k.joinToString("/")}" else null
        val err: String? = when (name) {
            SAY -> { put("text", 120, "line", "content"); put("about", 80, "intent"); put("emotion", 12, "expression"); need("text", "about") }
            REPLY -> { put("commentId", 16, "comment_id", "id"); put("text", 120, "line", "content"); put("about", 80, "intent"); put("emotion", 12, "expression")
                need("commentId") ?: need("text", "about") }
            BROWSE -> { put("query", 40, "q", "keyword"); put("url", 400, "link"); put("site", 10, "source")
                val site = m["site"] as? String
                if (site != null && site.lowercase() !in SITES) m.remove("site") else if (site != null) m["site"] = site.lowercase()
                val url = m["url"] as? String
                // a link of the current page by number ("#3" / "3") is resolved by the host
                if (url != null && Regex("^#?\\d{1,2}$").matches(url)) m["url"] = "#" + url.removePrefix("#")
                else if (url != null && !(url.startsWith("https://") || url.startsWith("http://"))) { m.remove("url") }
                need("query", "url") }
            READ_PAGE, NEXT_SEGMENT -> null
            SCROLL -> { put("direction", 6); m["direction"] = if ((m["direction"] as? String)?.lowercase() == "up") "up" else "down"; null }
            SET_TOPIC -> { put("topic", 30); need("topic") }
            START_POLL -> {
                put("question", 40, "q")
                val opts = (a.optJSONArray("options") ?: a.optJSONArray("choices"))?.let { arr -> (0 until arr.length()).map { clip(arr.optString(it), 16) } }
                    ?: a.optString("options").split('|', '/', '、').map { clip(it, 16) }
                val ok = opts.filter { it.isNotBlank() }.distinct().take(4)
                m["options"] = ok
                need("question") ?: if (ok.size < 2) "start_poll 至少要 2 个选项" else null
            }
            ASK_AUDIENCE -> { put("question", 60, "text", "about"); need("question") }
            REMEMBER -> { put("fact", 40, "text"); put("viewer", 24, "nick", "commentId", "about"); need("fact") }
            NOTE_TO_SELF -> { put("text", 120, "note", "fact"); need("text") }
            SET_EXPRESSION -> {
                val e = normExpr(a.optString("expression", a.optString("emotion", a.optString("face", ""))))
                if (e == null) "set_expression 表情无效" else { m["expression"] = e; null }
            }
            REST -> { m["seconds"] = a.optInt("seconds", a.optInt("sec", 10)).coerceIn(3, 30); null }
            END_STREAM -> { put("reason", 40); if (!m.containsKey("reason")) m["reason"] = "到点了"; null }
            else -> "未知工具 $name"
        }
        if (err != null) return null to err
        (m["emotion"] as? String)?.let { e -> val n = normExpr(e); if (n == null) m.remove("emotion") else m["emotion"] = n }
        return ToolCall(name, m) to null
    }

    /** Contextual validation against the room; null = ok. */
    fun check(c: ToolCall, ctx: ToolContext): String? = when (c.name) {
        REPLY -> if (c.str("commentId") !in ctx.commentIds) "reply 的 commentId ${c.str("commentId")} 不在当前弹幕里" else null
        BROWSE -> {
            val url = c.str("url")
            when {
                url.startsWith("#") -> if (!ctx.hasPage) "browse：还没打开网页，不能点链接编号" else null
                url.isNotBlank() && !hostAllowed(url, ctx.allowedHosts) -> "browse 的链接不在允许的网站里"
                url.isBlank() && c.str("query").isBlank() -> "browse 缺少搜索词"
                else -> null
            }
        }
        READ_PAGE, SCROLL -> if (!ctx.hasPage) "${c.name}：还没打开网页" else null
        START_POLL -> if (ctx.pollOpen) "已经有一个投票在进行" else null
        END_STREAM -> if (!ctx.canEnd) "还没到时长/预算上限，不能下播" else null
        else -> null
    }

    /** Validate a whole plan: keeps the calls that pass, returns errors for the rest. */
    fun validate(calls: List<ToolCall>, ctx: ToolContext): Pair<List<ToolCall>, List<String>> {
        val ok = mutableListOf<ToolCall>(); val errs = mutableListOf<String>()
        var pollOpen = ctx.pollOpen
        for (c in calls.take(MAX_ACTIONS)) {
            val e = check(c, ctx.copy(pollOpen = pollOpen))
            if (e == null) { ok += c; if (c.name == START_POLL) pollOpen = true } else errs += e
        }
        return ok to errs
    }

    fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
            .removePrefix("www.").removePrefix("m.")

    fun hostAllowed(url: String, allowed: List<String>): Boolean {
        val h = hostOf(url)
        if (h.isBlank()) return false
        if (allowed.isEmpty()) return true
        return allowed.any { r -> val x = r.lowercase().removePrefix("www.").removePrefix("m."); h == x || h.endsWith(".$x") }
    }

    // ------------------------------------------------------------------ json helpers

    internal fun clip(s: String?, max: Int): String =
        (s ?: "").replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    /** First parseable JSON object in a model reply (tolerates ```json fences, chatter, <think> blocks). */
    fun extractJson(text: String): JSONObject? {
        val t = text.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("```(?:json)?"), "")
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for ((i, c) in t.withIndex()) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> if (depth > 0) inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) { depth--; if (depth == 0 && start >= 0) {
                    try { return JSONObject(t.substring(start, i + 1)) } catch (_: Exception) {}
                    start = -1
                } }
            }
        }
        return null
    }

    fun strings(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
}
