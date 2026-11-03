package com.dl1991.feiyu.dafeiyu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/** One reply the engine drafted and is holding until the host decides. */
data class Draft(
    val id: Long,
    val source: String,            // what it answers: 「昵称：弹幕」, 冷场补位, 冲浪解说, 开场 …
    val text: String,              // what she would say (safety-scrubbed)
    val tags: List<String>,        // [[TAGS]] applied only if it is spoken
    val kind: String,              // bubble kind: reply | thought
    val urgent: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Completed true when spoken, false when skipped. Shared by copy() (edited text). */
    val done: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/**
 * 真人接管模式 — Douyin requires virtual-human streams to be driven live by a real person.
 * When active, the engine never speaks on its own: every turn (comment replies, 冷场补位,
 * surfing narration, segment transitions, director prompts) becomes a [Draft] the host must
 * approve with 说 / 改后说, or drop with 跳过. The host can also talk through her with push-to-talk
 * ([FyHumanVoice]). An「自动通过」kill switch (off by default) lets drafts flow straight through.
 */
object FyTakeover {
    const val MAX_PENDING = 6
    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> get() = _drafts
    /** Kill switch: auto-approve drafts. Runtime only, reset to OFF every stream and whenever takeover is turned on. */
    val autoApprove = MutableStateFlow(false)
    internal val approved = Channel<Draft>(Channel.UNLIMITED)
    private var nextId = 1L

    /** Takeover setting for the currently selected platform (抖音 defaults ON). */
    fun enabled(ctx: Context): Boolean {
        FyPlatforms.ensure(ctx)
        val c = FyContentStore.get(ctx)
        return if (FyPlatforms.selected.value == LivePlatform.DOUYIN) c.takeoverDouyin else c.takeoverOthers
    }

    /** Engine must hold every line for approval. */
    fun active(ctx: Context): Boolean = enabled(ctx) && !autoApprove.value

    /** 导播台 toggle: writes the setting for the selected platform. Turning it on always re-arms approval. */
    fun setEnabled(ctx: Context, on: Boolean) {
        FyPlatforms.ensure(ctx)
        val douyin = FyPlatforms.selected.value == LivePlatform.DOUYIN
        FyContentStore.update(ctx) { if (douyin) it.copy(takeoverDouyin = on) else it.copy(takeoverOthers = on) }
        if (on) autoApprove.value = false
        else flushAll(speak = false)
    }

    fun setAutoApprove(on: Boolean) {
        autoApprove.value = on
        if (on) _drafts.value.toList().forEach { approve(it.id, null) } // let what is waiting go out in order
    }

    fun reset() {
        flushAll(speak = false)
        autoApprove.value = false
        while (approved.tryReceive().isSuccess) Unit
    }

    fun pendingCount() = _drafts.value.size
    fun pendingFillers() = _drafts.value.count { it.kind == "thought" }

    internal fun add(source: String, text: String, tags: List<String>, kind: String, urgent: Boolean = false): Draft {
        val d = synchronized(this) { Draft(nextId++, source, text, tags, kind, urgent) }
        _drafts.value = if (urgent) listOf(d) + _drafts.value else _drafts.value + d
        return d
    }

    private fun take(id: Long): Draft? = synchronized(this) {
        val d = _drafts.value.firstOrNull { it.id == id } ?: return null
        _drafts.value = _drafts.value.filter { it.id != id }
        d
    }

    /** 说 (edited == null) / 改后说 (edited text). */
    fun approve(id: Long, edited: String?) {
        val d = take(id) ?: return
        val text = edited?.trim()?.takeIf { it.isNotBlank() } ?: d.text
        approved.trySend(if (text == d.text) d else d.copy(text = text))
    }

    /** 跳过: drop it (its tags are not applied). */
    fun skip(id: Long) {
        val d = take(id) ?: return
        d.done.complete(false)
        DfyLive.noteSkipped()
    }

    private fun flushAll(speak: Boolean) {
        val all = synchronized(this) { val l = _drafts.value; _drafts.value = emptyList(); l }
        all.forEach { if (speak) approved.trySend(it) else it.done.complete(false) }
    }
}

/**
 * 真人说话 (push-to-talk): while held, the phone mic goes straight into the stream audio
 * (through [DfySpeaker.sink]) and her mouth follows the mic level; the AI is paused meanwhile.
 * Not played back locally (no feedback loop).
 */
object FyHumanVoice {
    private const val TAG = "FyHumanVoice"
    private const val RATE = 44100
    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> get() = _talking
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context): Boolean {
        if (_talking.value) return true
        if (!hasPermission(ctx)) return false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); Log.w(TAG, "mic init failed"); return false }
        _talking.value = true
        DfyLive.pauseForHuman()
        rec = r
        runCatching { r.startRecording() }.onFailure { stop(); return false }
        thread = Thread({
            val buf = ShortArray(RATE / 50) // 20 ms
            DfySpeaker.speaking.value = true
            while (_talking.value) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                val frame = buf.copyOf(n)
                var acc = 0.0
                for (v in frame) { val x = v / 32768.0; acc += x * x }
                DfySpeaker.level.value = (sqrt(acc / n) * 5).toFloat().coerceIn(0f, 1f)
                runCatching { DfySpeaker.sink?.invoke(PcmAudio(frame, RATE)) }
            }
        }, "feiyu-ptt").also { it.start() }
        return true
    }

    fun stop() {
        if (!_talking.value && rec == null) return
        _talking.value = false
        runCatching { thread?.join(300) }
        thread = null
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null
        DfySpeaker.idle()
        DfyLive.noteHumanSpoke()
    }
}
