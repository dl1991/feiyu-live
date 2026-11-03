package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.util.Log
import com.dl1991.feiyu.llm.FyProviders
import com.dl1991.feiyu.llm.LLMMessage
import com.dl1991.feiyu.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Model access for 大肥鱼 through the app's own OpenAI-compatible providers ([FyProviders]):
 * the user's enabled endpoints in their order, falling back to the next one on error.
 * With [DfySettings.preferFreeModels] free endpoints go first.
 */
object DfyLlm {
    private const val TAG = "Dafeiyu"

    class NoModel : Exception("没有可用的模型：请先在「人生 & 声音 › 模型服务」启用一个（Kilo 免费网关不需要 Key）")

    /** One chat completion. Returns the text, or throws [NoModel] / the last provider error. */
    suspend fun ask(
        ctx: Context,
        system: String,
        messages: List<LLMMessage>,
        maxTokens: Int = 900,
        temperature: Double? = 0.8,
        preferFree: Boolean = true,
        @Suppress("UNUSED_PARAMETER") origin: String = "dafeiyu",
    ): String = withContext(Dispatchers.IO) {
        val cands = FyProviders.candidates(ctx, preferFree).take(3)
        if (cands.isEmpty()) throw NoModel()
        var lastErr: String? = null
        for (p in cands) {
            try {
                val r = LlmClient.chat(p, FyProviders.key(ctx, p), system, messages, maxTokens, temperature).trim()
                if (r.isNotEmpty()) return@withContext r
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e.message; Log.w(TAG, "ask ${p.id}: ${e.message}")
            }
        }
        throw IllegalStateException("模型调用失败" + (lastErr?.let { "：" + it.take(160) } ?: ""))
    }

    /** Provider id that answered the last live turn; tried first next time (latency matters live). */
    @Volatile private var fastId: String? = null

    /**
     * Streaming completion for the live room: [onText] receives deltas as they arrive. Falls back
     * to the next provider only if one fails before producing any text. Returns the full text.
     * Cancelling the calling coroutine (barge-in) stops the stream.
     */
    suspend fun stream(
        ctx: Context,
        system: String,
        messages: List<LLMMessage>,
        maxTokens: Int = 300,
        temperature: Double? = 0.9,
        preferFree: Boolean = true,
        onText: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        var cands = FyProviders.candidates(ctx, preferFree)
        fastId?.let { id -> cands.find { it.id == id }?.let { f -> cands = listOf(f) + cands.filter { it.id != id } } }
        if (cands.isEmpty()) throw NoModel()
        var lastErr: Exception? = null
        for (p in cands.take(3)) {
            val sb = StringBuilder()
            try {
                LlmClient.stream(p, FyProviders.key(ctx, p), system, messages, maxTokens, temperature) { d -> sb.append(d); onText(d) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e; Log.w(TAG, "stream ${p.id}: ${e.message}")
                if (sb.isEmpty()) continue // nothing spoken yet: next provider
            }
            if (sb.isNotEmpty()) { fastId = p.id; return@withContext sb.toString() }
        }
        throw lastErr ?: IllegalStateException("模型调用失败")
    }

    suspend fun askOnce(ctx: Context, system: String, user: String, maxTokens: Int = 900, temperature: Double? = 0.8, preferFree: Boolean = true): String =
        ask(ctx, system, listOf(LLMMessage(role = LLMMessage.Role.USER, content = user)), maxTokens, temperature, preferFree)

    /** First JSON object in a model reply (tolerates ```json fences and chatter around it). */
    fun extractJson(text: String): JSONObject? {
        val t = text.replace(Regex("```(?:json)?"), "")
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for ((i, c) in t.withIndex()) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) { depth--; if (depth == 0 && start >= 0) {
                    runCatching { return JSONObject(t.substring(start, i + 1)) }.onFailure { Log.d(TAG, "json parse: ${it.message}") }
                    start = -1
                } }
            }
        }
        return null
    }
}
