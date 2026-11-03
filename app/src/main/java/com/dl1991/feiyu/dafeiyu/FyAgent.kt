package com.dl1991.feiyu.dafeiyu

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Android side of the 大肥鱼 agent: persisted 专家组 config (filesDir/feiyu/crew.json), her life
 * state (filesDir/feiyu/life.json), the model adapter (same providers as the live room, optionally a
 * provider per expert) and the crew UI state the 导播台 renders. The loop itself is [FyCrew].
 */
object FyAgent {
    private val _cfg = MutableStateFlow(CrewConfig.PRESETS.getValue(CrewConfig.DEFAULT_PRESET))
    val cfg: StateFlow<CrewConfig> get() = _cfg
    private val _life = MutableStateFlow(LifeState())
    val lifeState: StateFlow<LifeState> get() = _life
    /** Latest crew UI (mirrors the running crew; kept after the stream ends). */
    val ui = MutableStateFlow(CrewUi(notes = CrewRole.values().associate { it.id to ExpertNote(it.id) }))
    @Volatile private var loaded = false
    private var store: LifeStore? = null
    private var mirror: Job? = null

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "feiyu").apply { mkdirs() }

    fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { CrewConfig.from(JSONObject(File(dir(ctx), "crew.json").readText())) }.getOrNull()?.let { _cfg.value = it }
            val file = FileLifeStore(File(dir(ctx), "life.json"))
            store = object : LifeStore {
                override fun get(): LifeState = file.get()
                override fun update(f: (LifeState) -> LifeState): LifeState = file.update(f).also { _life.value = it }
            }
            _life.value = file.get()
            loaded = true
        }
    }

    fun config(ctx: Context): CrewConfig { ensure(ctx); return _cfg.value }

    fun setConfig(ctx: Context, c: CrewConfig) {
        ensure(ctx)
        val n = c.sanitized(); _cfg.value = n
        val f = File(dir(ctx), "crew.json"); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(n.toJson().toString(2)); tmp.renameTo(f)
    }

    fun update(ctx: Context, f: (CrewConfig) -> CrewConfig) = setConfig(ctx, f(config(ctx)))

    /** Preset keeps the per-expert model choices and the on/off master switch. */
    fun applyPreset(ctx: Context, name: String) {
        val p = CrewConfig.PRESETS[name] ?: return
        update(ctx) { cur -> p.copy(agentOn = cur.agentOn, models = cur.models) }
    }

    fun life(ctx: Context): LifeStore { ensure(ctx); return store!! }

    fun updateLife(ctx: Context, f: (LifeState) -> LifeState) { life(ctx).update(f) }

    /** First run: goals + interests seeded from 直播内容 › 主题偏好. */
    fun seed(ctx: Context) {
        val topics = FyContentStore.get(ctx).topics.map { it.topic to it.weight }
        updateLife(ctx) { FyLifeLogic.seed(it, topics, System.currentTimeMillis()) }
    }

    /** A crew for one stream; its UI is mirrored into [ui] on [scope]. */
    fun newCrew(ctx: Context, host: CrewHost, scope: CoroutineScope): FyCrew {
        val app = ctx.applicationContext
        ensure(app)
        val c = FyCrew(AndroidCrewLlm(app), host, life(app), { _cfg.value })
        c.reset()
        mirror?.cancel()
        mirror = scope.launch { c.ui.collect { ui.value = it } }
        return c
    }

    /** Write her proposed plan into 直播内容 (节目单 + 主题偏好). */
    fun applyPlan(ctx: Context, plan: NextPlan) {
        if (plan.segments.isEmpty()) return
        FyContentStore.update(ctx) { c ->
            c.copy(segments = plan.segments.map { Segment(it.kind, it.minutes) },
                topics = (plan.topics.mapIndexed { i, t -> TopicWeight(t, (90 - i * 10).coerceAtLeast(30)) } + c.topics.filter { o -> plan.topics.none { it == o.topic } }).take(20))
        }
        updateLife(ctx) { it.copy(nextPlan = plan.copy(status = "applied")) }
    }
}

/** Experts talk through the same OpenAI-compatible providers as the room; [model] = provider id to try first. */
class AndroidCrewLlm(private val ctx: Context) : CrewLlm {
    override suspend fun ask(role: CrewRole, model: String, system: String, user: String, maxTokens: Int): String {
        val st = DfyStore.snapshot(ctx).settings
        val temp = when (role) { CrewRole.WRITER -> 0.9; CrewRole.DIRECTOR -> 0.7; CrewRole.SAFETY -> 0.1; CrewRole.REVIEWER -> 0.5; else -> 0.4 }
        return DfyLlm.ask(ctx, system, listOf(com.dl1991.feiyu.llm.LLMMessage(com.dl1991.feiyu.llm.LLMMessage.Role.USER, user)),
            maxTokens, temp, st.preferFreeModels, origin = "crew-${role.id}", prefer = model.ifBlank { null })
    }
}

/** Hints from the agent to the simulated audience so the sim reacts to polls / questions / topics. */
object SimCue {
    @Volatile var topic = ""
    @Volatile var pollOptions = 0
    @Volatile var question = ""
    @Volatile var questionAt = 0L
    fun reset() { topic = ""; pollOptions = 0; question = ""; questionAt = 0 }
}
