package com.dl1991.feiyu.dafeiyu

import android.content.Context
import com.dl1991.feiyu.llm.AgentToolDefinition
import com.dl1991.feiyu.llm.AgentToolParam
import com.dl1991.feiyu.llm.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Main-chat integration: one command entry point (formerly a Mibot agent tool). Read-only actions answer from what
 * 大肥鱼 still remembers (forgotten details are not resurrected); write actions only touch her own
 * state (todos, a learning goal, a message into her live room). Nothing here posts to B站/抖音.
 */
object DfyTools {
    const val NAME = "dafeiyu_life"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "大肥鱼 (DaFeiYu), the user's virtual whale-girl companion who surfs the web, learns things and live-streams in the 大肥鱼直播 app. " +
            "action=status: her mood, what she is doing, whether she is live. action=today: what she watched/learned/streamed today. " +
            "action=recall(query): search her memories (only what she still remembers; faded ones are vague). action=knowledge(query): notes she took while learning, with source URLs. " +
            "action=add_todo(text, kind=search|note|live|learn): give her a task (search=look it up on B站, learn=set a learning goal, live=check a streamer uid). " +
            "action=ask(text): ask her something and get her reply in character. action=live_say(text): if she is live, send a viewer message from the user into her room. " +
            "action=run_now: start one background life run (B站 browsing). She is a fictional character; never present her as a real person.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the user's language."),
            "action" to AgentToolParam("string", "status | today | recall | knowledge | add_todo | ask | live_say | run_now",
                enumValues = listOf("status", "today", "recall", "knowledge", "add_todo", "ask", "live_say", "run_now")),
            "query" to AgentToolParam("string", "recall/knowledge: what to look for."),
            "text" to AgentToolParam("string", "add_todo/ask/live_say: the text."),
            "kind" to AgentToolParam("string", "add_todo: search | note | live | learn", enumValues = listOf("search", "note", "live", "learn")),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "query", "text", "kind"),
    )

    suspend fun execute(ctx: Context, argsJson: String): ToolExecutionResult {
        val a = runCatching { JSONObject(argsJson) }.getOrElse { return ToolExecutionResult("invalid arguments", false) }
        val now = System.currentTimeMillis()
        DfyLife.decay(ctx, now)
        val st = DfyStore.snapshot(ctx)
        return when (a.optString("action")) {
            "status" -> {
                val m = DfyMood.decayed(st.mood, now); val room = DfyLive.state.value
                ok(JSONObject().put("mood", DfyMood.label(m.mood)).put("intensity", m.intensity.toInt()).put("energy", m.energy.toInt())
                    .put("activity", st.meta.activity.ifBlank { "idle" }).put("last_run", st.meta.lastRunResult)
                    .put("live", room.status == "live").put("live_topic", room.topic).put("live_page", room.pageTitle)
                    .put("open_todos", st.todos.count { !it.done }).put("pending_bili_actions", st.actions.count { it.status == "pending" }))
            }
            "today" -> {
                val day = DfyStore.today(now)
                val sessions = st.sessions.filter { DfyStore.today(it.startedAt) == day }
                ok(JSONObject().put("summary", DfyPersona.todaySummary(st, now).ifBlank { "今天还没去逛" })
                    .put("streams", JSONArray().also { arr -> sessions.forEach { arr.put(JSONObject().put("topic", it.trail.joinToString(" → ")).put("recap", it.recap).put("pages", it.hops.size)) } })
                    .put("learned", JSONArray().also { arr -> st.knowledge.filter { DfyStore.today(it.updatedAt) == day }.forEach { arr.put(JSONObject().put("goal", it.title).put("notes", it.notes.size).put("summary", it.summary)) } }))
            }
            "recall" -> {
                val q = a.optString("query")
                val r = DfyMemory.recall(st, q, now, 8)
                ok(JSONObject().put("memories", JSONArray().also { arr -> r.forEach { arr.put(JSONObject().put("stage", it.stage.zh).put("kind", it.kind).put("text", it.text).apply { it.at?.let { t -> put("date", DfyStore.today(t)) } }) } })
                    .put("note", "Only memories she still has; faded ones are vague by design."))
            }
            "knowledge" -> {
                val n = DfyKnowledge.lookup(st, a.optString("query"), 10)
                ok(JSONObject().put("notes", JSONArray().also { arr -> n.forEach { arr.put(JSONObject().put("note", it.text).put("source", it.sourceTitle).put("url", it.sourceUrl)) } })
                    .put("goals", JSONArray().also { arr -> st.knowledge.takeLast(8).forEach { arr.put(JSONObject().put("title", it.title).put("status", it.status).put("score", it.score)) } }))
            }
            "add_todo" -> {
                val text = clean(a.optString("text"), 120).ifBlank { return ToolExecutionResult("text required", false) }
                if (DfySafety.checkIncoming(text) != DfySafety.Verdict.OK) return ToolExecutionResult("refused: unsuitable content", false)
                val kind = a.optString("kind").takeIf { it in setOf("search", "note", "live", "learn") } ?: "note"
                if (kind == "learn") {
                    val g = DfyKnowledge.newGoal(text, "主人建议的学习目标", "agent")
                    DfyStore.update(ctx) { it.copy(knowledge = it.knowledge + g) }
                    ok(JSONObject().put("status", "goal_added").put("goal_id", g.id).put("hint", "She studies it next time she goes live in 学知识 mode."))
                } else {
                    val t = DfyTodo(UUID.randomUUID().toString().take(8), text, kind, now, from = "agent")
                    DfyStore.update(ctx) { it.copy(todos = it.todos + t) }
                    ok(JSONObject().put("status", "added").put("id", t.id))
                }
            }
            "ask" -> {
                val text = a.optString("text").ifBlank { return ToolExecutionResult("text required", false) }
                ok(JSONObject().put("reply", DfyChat.send(ctx, text, from = "agent")))
            }
            "live_say" -> {
                if (!DfyLive.isLive()) return ToolExecutionResult("She is not live right now.", false)
                val text = clean(a.optString("text"), 200).ifBlank { return ToolExecutionResult("text required", false) }
                DfyLive.inject(text); ok(JSONObject().put("status", "sent"))
            }
            "run_now" -> { DfyWorker.runNow(ctx); ok(JSONObject().put("status", "started")) }
            else -> ToolExecutionResult("unknown action", false)
        }
    }

    private fun ok(o: JSONObject) = ToolExecutionResult(o.toString(2), true)
}
