package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dl1991.feiyu.net.NetworkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

/** Mono 16-bit PCM. */
class PcmAudio(val samples: ShortArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate.coerceAtLeast(1)
    fun resampled(to: Int): PcmAudio {
        if (to == sampleRate || samples.isEmpty()) return this
        val n = (samples.size.toLong() * to / sampleRate).toInt()
        val out = ShortArray(n)
        val step = sampleRate.toDouble() / to
        for (i in 0 until n) {
            val x = i * step; val a = x.toInt(); val f = x - a
            val s0 = samples[a.coerceAtMost(samples.size - 1)]; val s1 = samples[(a + 1).coerceAtMost(samples.size - 1)]
            out[i] = (s0 + (s1 - s0) * f).toInt().toShort()
        }
        return PcmAudio(out, to)
    }
    companion object {
        fun silence(ms: Int, rate: Int = 24000) = PcmAudio(ShortArray(rate * ms / 1000), rate)
        fun fromLe(bytes: ByteArray, rate: Int, offset: Int = 0): PcmAudio {
            val n = (bytes.size - offset) / 2
            val s = ShortArray(n)
            for (i in 0 until n) s[i] = ((bytes[offset + 2 * i].toInt() and 0xff) or (bytes[offset + 2 * i + 1].toInt() shl 8)).toShort()
            return PcmAudio(s, rate)
        }
    }
}

/** Prosody for one utterance. Edge's free endpoint ignores mstts:express-as styles, so emotion is carried by pitch/rate/volume + wording. */
data class VoiceStyle(val ratePct: Int = 0, val pitchPct: Int = 0, val volumePct: Int = 0) {
    companion object {
        fun forMood(mood: String, intensity: Double, expr: String): VoiceStyle {
            val k = (intensity / 60.0).coerceIn(0.3, 1.0)
            val base = when (expr) {
                Expr.EXCITED -> VoiceStyle(14, 9, 8)
                Expr.SURPRISED -> VoiceStyle(8, 12, 6)
                Expr.SLEEPY -> VoiceStyle(-14, -6, -8)
                Expr.CONFUSED -> VoiceStyle(-5, 3, 0)
                else -> when (mood) {
                    "joyful" -> VoiceStyle(10, 7, 5); "curious" -> VoiceStyle(5, 5, 0); "warm" -> VoiceStyle(-3, 2, 0)
                    "calm" -> VoiceStyle(-6, -2, -3); "tired" -> VoiceStyle(-12, -5, -6); "sad" -> VoiceStyle(-10, -6, -6)
                    "irritated" -> VoiceStyle(6, -3, 6); else -> VoiceStyle(0, 2, 0)
                }
            }
            return VoiceStyle((base.ratePct * k).toInt(), (base.pitchPct * k).toInt(), (base.volumePct * k).toInt())
        }
    }
}

interface TtsEngine {
    val id: String
    suspend fun synth(ctx: Context, text: String, style: VoiceStyle): PcmAudio?
}

/** Android system TTS (offline fallback). Synthesises to a temp WAV and reads its PCM. */
object SystemTts : TtsEngine {
    override val id = "system"
    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()

    private suspend fun ensure(ctx: Context): TextToSpeech? {
        if (tts == null) {
            ready = CompletableDeferred()
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(ctx.applicationContext) { st -> ready.complete(st == TextToSpeech.SUCCESS) }
            }
        }
        val ok = withTimeoutOrNull(5000) { ready.await() } ?: false
        if (ok) tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
        return if (ok) tts else null
    }

    override suspend fun synth(ctx: Context, text: String, style: VoiceStyle): PcmAudio? {
        val t = ensure(ctx) ?: return null
        val f = File(ctx.cacheDir, "dfy_tts_${UUID.randomUUID()}.wav")
        t.setSpeechRate(1f + style.ratePct / 100f)
        t.setPitch(1f + style.pitchPct / 100f)
        val id = UUID.randomUUID().toString()
        val done = suspendCancellableCoroutine<Boolean> { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            })
            if (t.synthesizeToFile(text, null, f, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
        return try { if (done) Wav.read(f) else null } finally { f.delete() }
    }
}

object Wav {
    fun read(f: File): PcmAudio? = fromBytes(f.readBytes())
    fun fromBytes(b: ByteArray): PcmAudio? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF") return null
        var p = 12; var rate = 22050; var ch = 1
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = le32(b, p + 4)
            if (id == "fmt ") { ch = le16(b, p + 10); rate = le32(b, p + 12) }
            if (id == "data") {
                val pcm = PcmAudio.fromLe(b.copyOfRange(p + 8, minOf(b.size, p + 8 + len)), rate)
                return if (ch == 2) PcmAudio(ShortArray(pcm.samples.size / 2) { pcm.samples[it * 2] }, rate) else pcm
            }
            p += 8 + len
        }
        return null
    }
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
}

/** MP3 (or any MediaExtractor format) → mono PCM via MediaCodec. */
object AudioDecode {
    fun decode(bytes: ByteArray): PcmAudio? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(size.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, n); return n
            }
            override fun getSize(): Long = bytes.size.toLong()
            override fun close() {}
        })
        ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(5000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                val chunk = ByteArray(info.size); buf.get(chunk); out.write(chunk)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
            }
        }
        codec.stop(); codec.release(); ex.release()
        val pcm = PcmAudio.fromLe(out.toByteArray(), rate)
        if (ch == 2) PcmAudio(ShortArray(pcm.samples.size / 2) { pcm.samples[it * 2] }, rate) else pcm
    }.onFailure { Log.w("DafeiyuTts", "decode: ${it.message}") }.getOrNull()
}

/**
 * Microsoft Edge "Read aloud" voices (free, unofficial endpoint used by the Edge browser;
 * may change or be rate-limited — reachability from the phone is UNTESTED). Neural zh-CN voices,
 * prosody via SSML. Returns MP3 which we decode to PCM.
 */
object EdgeTts : TtsEngine {
    override val id = "edge"
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val GEC_VERSION = "1-130.0.2849.68"
    val VOICES = listOf("zh-CN-XiaoyiNeural", "zh-CN-XiaoxiaoNeural", "zh-CN-XiaohanNeural", "zh-CN-XiaomengNeural", "zh-CN-liaoning-XiaobeiNeural", "zh-TW-HsiaoChenNeural")
    @Volatile var voice = "zh-CN-XiaoyiNeural"

    private val base by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }
    private fun client() = runCatching { NetworkHttp.client(NetworkHttp.DEFAULT_TAG, base) }.getOrDefault(base)

    private fun gec(): String {
        var t = System.currentTimeMillis() / 1000.0 + 11644473600.0
        t -= t % 300
        val ticks = (t * 1e7).toLong()
        return MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray()).joinToString("") { "%02X".format(it) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun pct(v: Int) = (if (v >= 0) "+" else "") + v + "%"

    override suspend fun synth(ctx: Context, text: String, style: VoiceStyle): PcmAudio? = withContext(Dispatchers.IO) {
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TOKEN&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=$GEC_VERSION&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
        val req = Request.Builder().url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache").build()
        val audio = ByteArrayOutputStream()
        val done = CompletableDeferred<Boolean>()
        val ts = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val ws = client().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'><voice name='$voice'>" +
                    "<prosody pitch='${pct(style.pitchPct)}' rate='${pct(style.ratePct)}' volume='${pct(style.volumePct)}'>${esc(text)}</prosody></voice></speak>"
                webSocket.send("X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("Path:turn.end")) { done.complete(true); webSocket.close(1000, null) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                if (2 + hl <= b.size) audio.write(b, 2 + hl, b.size - 2 - hl)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { Log.w("DafeiyuTts", "edge: ${t.message}"); done.complete(false) }
        })
        val ok = withTimeoutOrNull(15_000) { done.await() } ?: false
        if (!ok) ws.cancel()
        if (!ok || audio.size() == 0) null else AudioDecode.decode(audio.toByteArray())
    }
}

/** Any OpenAI-compatible /audio/speech endpoint (e.g. SiliconFlow CosyVoice2). Key from [DfySecrets]. */
object ApiTts : TtsEngine {
    override val id = "api"
    private val base by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    @Volatile var lastError: String = ""

    override suspend fun synth(ctx: Context, text: String, style: VoiceStyle): PcmAudio? = withContext(Dispatchers.IO) {
        val cfg = DfyVoiceConfig.get(ctx) // read the persisted config every time (no volatile defaults)
        val key = DfySecrets.get(ctx, DfySecrets.TTS_API_KEY).ifBlank { lastError = "未填 TTS API Key"; return@withContext null }
        val body = JSONObject().put("model", cfg.apiModel).put("input", text).put("voice", cfg.apiVoice)
            .put("response_format", "pcm").put("sample_rate", 24000).put("speed", (1.0 + style.ratePct / 100.0).coerceIn(0.5, 2.0)).toString()
        val c = runCatching { NetworkHttp.client(NetworkHttp.DEFAULT_TAG, base) }.getOrDefault(base)
        runCatching {
            c.newCall(Request.Builder().url(cfg.apiBaseUrl.trimEnd('/') + "/audio/speech").header("Authorization", "Bearer $key")
                .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) { lastError = "HTTP ${r.code} " + (r.body?.string().orEmpty().take(120)); null }
                else {
                    val bytes = r.body!!.bytes()
                    // some servers ignore response_format=pcm and send mp3/wav: sniff and decode
                    when {
                        bytes.size > 4 && String(bytes, 0, 4) == "RIFF" -> Wav.fromBytes(bytes)
                        bytes.size > 3 && (String(bytes, 0, 3) == "ID3" || (bytes[0].toInt() and 0xff) == 0xff) -> AudioDecode.decode(bytes)
                        else -> PcmAudio.fromLe(bytes, 24000)
                    }
                }
            }
        }.onFailure { lastError = it.message ?: "失败" }.getOrNull()
    }
}

/**
 * Speaker: one AudioTrack (24 kHz mono, streaming). Plays queued utterances, publishes a
 * 0..1 loudness for lip-sync, mirrors PCM to [sink] (the RTMP audio source), and can be
 * interrupted instantly (barge-in).
 */
object DfySpeaker {
    const val RATE = 24000
    val level = MutableStateFlow(0f)
    val speaking = MutableStateFlow(false)
    @Volatile var sink: ((PcmAudio) -> Unit)? = null
    @Volatile var localPlayback = true
    /** 0..1 output gain (音量). */
    @Volatile var gain = 0.9f
    /** 导播台「静音」: she keeps talking (subtitles, timing) but no sound goes out. */
    @Volatile var muted = false
    private var track: AudioTrack? = null
    @Volatile private var generation = 0

    private fun ensure(): AudioTrack {
        track?.let { return it }
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, RATE / 5 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play(); track = t; return t
    }

    /** Blocking play (call from a background dispatcher). Returns false if interrupted. */
    fun play(a: PcmAudio): Boolean {
        val gen = generation
        val g = if (muted) 0f else gain
        val pcm = a.resampled(RATE).let { r -> if (g >= 0.999f) r else PcmAudio(ShortArray(r.samples.size) { (r.samples[it] * g).toInt().toShort() }, RATE) }
        sink?.invoke(pcm)
        val t = if (localPlayback) ensure() else null
        speaking.value = true
        val frame = RATE / 50 // 20 ms
        var i = 0
        val startNs = System.nanoTime()
        while (i < pcm.samples.size) {
            if (gen != generation) { level.value = 0f; return false }
            val n = minOf(frame, pcm.samples.size - i)
            var acc = 0.0
            for (k in i until i + n) { val v = pcm.samples[k] / 32768.0; acc += v * v }
            level.value = (sqrt(acc / n) * 4).toFloat().coerceIn(0f, 1f)
            if (t != null) t.write(pcm.samples, i, n)
            else { // no local playback: pace by the clock so lip-sync follows the stream audio
                val target = startNs + (i + n) * 1_000_000_000L / RATE
                val wait = (target - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
            i += n
        }
        level.value = 0f
        return true
    }

    fun interrupt() {
        generation++
        level.value = 0f
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    fun idle() { speaking.value = false; level.value = 0f }

    fun release() { interrupt(); track?.release(); track = null }

    /** Loudness of a short clip, for tests/diagnostics. */
    fun peak(a: PcmAudio): Int = a.samples.maxOfOrNull { abs(it.toInt()) } ?: 0
}
