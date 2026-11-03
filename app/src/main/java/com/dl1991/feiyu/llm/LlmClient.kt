package com.dl1991.feiyu.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmHttpException(val code: Int, msg: String) : Exception(msg)

/**
 * Minimal OpenAI-compatible chat client: `POST {base}/chat/completions`, plain or SSE streaming.
 * Only `delta.content` is surfaced; `reasoning` / `reasoning_content` deltas are skipped so a
 * thinking model never speaks its chain of thought on stream.
 */
object LlmClient {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json".toMediaType()

    private fun url(p: FyProvider, path: String) = p.baseUrl.trim().trimEnd('/') + path

    private fun body(p: FyProvider, system: String?, messages: List<LLMMessage>, maxTokens: Int, temperature: Double?, stream: Boolean): String {
        val arr = JSONArray()
        if (!system.isNullOrBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { arr.put(JSONObject().put("role", it.role.wire).put("content", it.content)) }
        val o = JSONObject().put("model", p.model).put("messages", arr).put("stream", stream).put("max_tokens", maxTokens + p.reasoningHeadroom)
        if (temperature != null) o.put("temperature", temperature)
        return o.toString()
    }

    private fun request(p: FyProvider, key: String, json: String): Request {
        val b = Request.Builder().url(url(p, "/chat/completions")).post(json.toRequestBody(JSON))
            .header("Accept", if (json.contains("\"stream\":true")) "text/event-stream" else "application/json")
            .header("User-Agent", "FeiyuLive/0.1")
        if (key.isNotBlank()) b.header("Authorization", "Bearer $key")
        if (p.preset == "openrouter") b.header("HTTP-Referer", "https://github.com/dl1991/feiyu-live").header("X-Title", "FeiyuLive")
        return b.build()
    }

    private fun errorOf(code: Int, raw: String): LlmHttpException {
        val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: raw.take(160)
        return LlmHttpException(code, "HTTP $code ${msg.trim()}")
    }

    suspend fun chat(p: FyProvider, key: String, system: String?, messages: List<LLMMessage>, maxTokens: Int = 600, temperature: Double? = 0.8): String =
        withContext(Dispatchers.IO) {
            val call = http.newCall(request(p, key, body(p, system, messages, maxTokens, temperature, false)))
            currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            call.execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw errorOf(r.code, raw)
                val msg = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                msg?.optString("content").orEmpty().trim()
            }
        }

    /** Streams content deltas to [onText]; returns the full text. Cancelling the coroutine cancels the HTTP call. */
    suspend fun stream(
        p: FyProvider, key: String, system: String?, messages: List<LLMMessage>, maxTokens: Int = 300, temperature: Double? = 0.9,
        onText: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val call = http.newCall(request(p, key, body(p, system, messages, maxTokens, temperature, true)))
        currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        val sb = StringBuilder()
        call.execute().use { r ->
            if (!r.isSuccessful) throw errorOf(r.code, r.body?.string().orEmpty())
            val src = r.body?.source() ?: return@use
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                o.optJSONObject("error")?.let { throw LlmHttpException(200, it.optString("message", "stream error")) }
                val delta = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val c = if (delta.isNull("content")) "" else delta.optString("content")
                if (c.isNotEmpty()) { sb.append(c); onText(c) }
            }
        }
        sb.toString()
    }

    /**
     * Real probe: `GET /models` (reachability + auth) and a tiny streamed chat, timing the first
     * content token. Latency reported = time to first token of the chat.
     */
    suspend fun probe(p: FyProvider, key: String): ProbeResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val models = runCatching {
            val b = Request.Builder().url(url(p, "/models")).header("User-Agent", "FeiyuLive/0.1")
            if (key.isNotBlank()) b.header("Authorization", "Bearer $key")
            http.newCall(b.build()).execute().use { it.code }
        }.getOrElse { return@withContext ProbeResult(false, System.currentTimeMillis() - t0, "连不上：" + (it.message ?: it.javaClass.simpleName).take(80)) }
        if (models == 401 || models == 403) return@withContext ProbeResult(false, System.currentTimeMillis() - t0, "Key 无效（HTTP $models）")
        var first = -1L
        val start = System.currentTimeMillis()
        return@withContext runCatching {
            val txt = stream(p, key, null, listOf(LLMMessage(LLMMessage.Role.USER, "只回复两个字：在呢")), 16, 0.2) { if (first < 0) first = System.currentTimeMillis() }
            val ms = (if (first > 0) first else System.currentTimeMillis()) - start
            if (txt.isBlank()) ProbeResult(false, ms, "连上了但没有回复（模型可能只输出思考）") else ProbeResult(true, ms, "回复「${txt.take(12)}」")
        }.getOrElse { ProbeResult(false, System.currentTimeMillis() - start, (it.message ?: "失败").take(120)) }
    }
}
