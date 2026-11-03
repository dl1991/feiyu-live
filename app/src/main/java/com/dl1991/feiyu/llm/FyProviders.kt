package com.dl1991.feiyu.llm

import android.content.Context
import com.dl1991.feiyu.dafeiyu.DfySecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One OpenAI-compatible model endpoint. The API key lives in [DfySecrets] under [keyRef]. */
data class FyProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean = true,
    val enabled: Boolean = true,
    val preset: String = "custom",
    val free: Boolean = false,
    /** Free gateways often route to reasoning models that spend tokens before answering. */
    val reasoningHeadroom: Int = 0,
) {
    val keyRef: String get() = "llm_key_$id"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("baseUrl", baseUrl).put("model", model)
        .put("needsKey", needsKey).put("enabled", enabled).put("preset", preset).put("free", free).put("reasoningHeadroom", reasoningHeadroom)
    companion object {
        fun from(o: JSONObject) = FyProvider(o.optString("id"), o.optString("name"), o.optString("baseUrl"), o.optString("model"),
            o.optBoolean("needsKey", true), o.optBoolean("enabled", true), o.optString("preset", "custom"), o.optBoolean("free"), o.optInt("reasoningHeadroom"))
    }
}

/** Probe result shown next to a provider / voice ("在线 · 820 ms"). */
data class ProbeResult(val ok: Boolean, val latencyMs: Long, val detail: String, val at: Long = System.currentTimeMillis())

object FyProviders {
    /** Presets (verified 2026-11-02: Kilo gateway answers /models and streams kilo-auto/free without a key; OpenRouter lists openrouter/free). */
    val PRESETS = listOf(
        FyProvider("kilo", "Kilo 免费网关", "https://api.kilo.ai/api/gateway", "kilo-auto/free", needsKey = false, preset = "kilo", free = true, reasoningHeadroom = 600),
        FyProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", preset = "deepseek"),
        FyProvider("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", preset = "siliconflow", free = true),
        FyProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free", preset = "openrouter", free = true, reasoningHeadroom = 600),
    )

    private val _list = MutableStateFlow<List<FyProvider>>(emptyList())
    val list: StateFlow<List<FyProvider>> get() = _list
    val probes = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "feiyu/providers.json").apply { parentFile?.mkdirs() }

    fun ensure(ctx: Context): List<FyProvider> {
        if (loaded) return _list.value
        synchronized(this) {
            if (!loaded) {
                val f = file(ctx)
                _list.value = runCatching { JSONArray(f.readText()).let { a -> (0 until a.length()).map { FyProvider.from(a.getJSONObject(it)) } } }
                    .getOrNull()?.filter { it.id.isNotBlank() }
                    // first run: only the keyless gateway is on, so the app talks out of the box
                    ?: PRESETS.map { it.copy(enabled = !it.needsKey) }
                loaded = true
            }
        }
        return _list.value
    }

    private fun save(ctx: Context, l: List<FyProvider>) {
        _list.value = l
        val f = file(ctx); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray(l.map { it.toJson() }).toString(2)); tmp.renameTo(f)
    }

    fun upsert(ctx: Context, p: FyProvider) {
        val l = ensure(ctx)
        save(ctx, if (l.any { it.id == p.id }) l.map { if (it.id == p.id) p else it } else l + p)
    }

    fun remove(ctx: Context, id: String) { save(ctx, ensure(ctx).filter { it.id != id }); DfySecrets.set(ctx, "llm_key_$id", "") }

    fun move(ctx: Context, id: String, up: Boolean) {
        val l = ensure(ctx).toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in l.indices) return
        val x = l[i]; l[i] = l[j]; l[j] = x; save(ctx, l)
    }

    fun key(ctx: Context, p: FyProvider): String = DfySecrets.get(ctx, p.keyRef)
    fun setKey(ctx: Context, p: FyProvider, key: String) = DfySecrets.set(ctx, p.keyRef, key)
    fun usable(ctx: Context, p: FyProvider) = p.enabled && p.baseUrl.startsWith("http") && p.model.isNotBlank() && (!p.needsKey || key(ctx, p).isNotBlank())

    /** Enabled providers with credentials, in the user's order; [preferFree] moves free ones first. */
    fun candidates(ctx: Context, preferFree: Boolean): List<FyProvider> {
        val l = ensure(ctx).filter { usable(ctx, it) }
        return if (preferFree) l.sortedBy { if (it.free) 0 else 1 } else l
    }

    suspend fun probe(ctx: Context, p: FyProvider): ProbeResult {
        val r = LlmClient.probe(p, key(ctx, p))
        probes.value = probes.value + (p.id to r)
        return r
    }
}
