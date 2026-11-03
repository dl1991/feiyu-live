package com.dl1991.feiyu.dafeiyu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.max

/*
 * RTMP push to the user's own 抖音 推流地址 + 推流码 using RootEncoder
 * (pedroSG94/RootEncoder, Apache-2.0; pinned to 2.6.1 — built with Kotlin 2.1.20, readable by
 * this app's Kotlin 2.1.0 compiler; newer 2.7/2.8 are built with Kotlin 2.2/2.4).
 * NOT tested against Douyin's ingest in this build.
 */

data class StreamStatus(
    val state: String = "idle",       // idle | connecting | live | reconnecting | failed | stopped
    val detail: String = "",
    val bitrateKbps: Long = 0,
    val startedAt: Long = 0,
    val reconnects: Int = 0,
    val preset: String = "",
    val source: String = "canvas",    // canvas | screen
)

object DfyRtmp {
    val status = MutableStateFlow(StreamStatus())
    const val EXTRA_PRESET = "preset"; const val EXTRA_MODE = "mode"
    const val EXTRA_PROJ_CODE = "proj_code"; const val EXTRA_PROJ_DATA = "proj_data"

    fun endpoint(ctx: Context): String? = FyPlatforms.endpoint(ctx)
    @Suppress("unused") private fun legacyEndpoint(ctx: Context): String? {
        val url = DfySecrets.get(ctx, DfySecrets.RTMP_URL).trim().trimEnd('/')
        val key = DfySecrets.get(ctx, DfySecrets.RTMP_KEY).trim().trimStart('/')
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) return null
        if (key.isBlank()) return null
        return "$url/$key"
    }

    fun start(ctx: Context, preset: StreamPreset, screenCode: Int? = null, screenData: Intent? = null) {
        val i = Intent(ctx, DfyStreamService::class.java).putExtra(EXTRA_PRESET, preset.name)
            .putExtra(EXTRA_MODE, if (screenData != null) "screen" else "canvas")
        if (screenCode != null && screenData != null) i.putExtra(EXTRA_PROJ_CODE, screenCode).putExtra(EXTRA_PROJ_DATA, screenData)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stop(ctx: Context) { ctx.stopService(Intent(ctx, DfyStreamService::class.java)) }

    fun screenCaptureIntent(ctx: Context): Intent =
        (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()
}

/** Video source that paints [DfyCompositor] frames into the encoder surface. */
private class CanvasVideoSource(private val comp: DfyCompositor) : VideoSource() {
    @Volatile private var running = false
    private var job: Job? = null
    private var surface: Surface? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true
    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (running) return
        surfaceTexture.setDefaultBufferSize(width, height)
        surface = Surface(surfaceTexture)
        running = true
        job = CoroutineScope(Dispatchers.Default).launch {
            val interval = 1000L / max(1, fps)
            var n = 0L
            while (running && isActive) {
                val t = System.currentTimeMillis()
                if (n++ % 4 == 0L) comp.requestWebSnapshot()
                runCatching {
                    val bmp = comp.render()
                    val c = surface?.lockCanvas(null)
                    if (c != null) { c.drawBitmap(bmp, null, android.graphics.Rect(0, 0, width, height), paint); surface?.unlockCanvasAndPost(c) }
                }
                delay(max(1, interval - (System.currentTimeMillis() - t)))
            }
        }
    }
    override fun stop() { running = false; job?.cancel(); surface?.release(); surface = null }
    override fun release() {}
    override fun isRunning(): Boolean = running
}

/** Audio source fed by her TTS PCM ([DfySpeaker.sink]); silence between utterances keeps AAC timing steady. */
private class TtsAudioSource : AudioSource() {
    private val queue = ArrayBlockingQueue<ShortArray>(400)
    @Volatile private var running = false
    private var job: Job? = null
    private var pending = ShortArray(0); private var pendingPos = 0

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean = true

    fun push(a: PcmAudio) {
        val pcm = a.resampled(sampleRate).samples
        var i = 0; val chunk = 1024
        while (i < pcm.size) { val n = minOf(chunk, pcm.size - i); if (!queue.offer(pcm.copyOfRange(i, i + n))) break; i += n }
    }
    fun clear() { queue.clear(); pending = ShortArray(0); pendingPos = 0 }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (running) return
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            val samplesPerFrame = 1024
            val ch = if (isStereo) 2 else 1
            val frameUs = samplesPerFrame * 1_000_000L / sampleRate
            val start = System.nanoTime() / 1000
            var count = 0L
            while (running && isActive) {
                val out = ByteArray(samplesPerFrame * 2 * ch)
                var k = 0
                while (k < samplesPerFrame) {
                    if (pendingPos >= pending.size) { pending = queue.poll() ?: break; pendingPos = 0 }
                    val v = pending[pendingPos++].toInt()
                    for (c in 0 until ch) { val o = (k * ch + c) * 2; out[o] = (v and 0xff).toByte(); out[o + 1] = (v shr 8).toByte() }
                    k++
                }
                getMicrophoneData.inputPCMData(Frame(out, 0, out.size, start + count * frameUs))
                count++
                val next = start + count * frameUs
                val wait = (next - System.nanoTime() / 1000) / 1000
                if (wait > 0) delay(wait)
            }
        }
    }
    override fun stop() { running = false; job?.cancel(); clear() }
    override fun isRunning(): Boolean = running
    override fun release() {}
}

/**
 * Foreground service that owns the stream: composes the canvas (or mirrors the screen via
 * MediaProjection as a fallback), encodes H.264/AAC and pushes RTMP with auto-reconnect.
 */
class DfyStreamService : Service(), ConnectChecker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stream: GenericStream? = null
    private var audio: TtsAudioSource? = null
    private var url = ""
    private var tries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preset = runCatching { StreamPreset.valueOf(intent?.getStringExtra(DfyRtmp.EXTRA_PRESET) ?: "") }.getOrDefault(StreamPreset.P720)
        val mode = intent?.getStringExtra(DfyRtmp.EXTRA_MODE) ?: "canvas"
        val n = notification("准备推流…")
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (mode == "screen") ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(0x0DF5, n, type)
        } else startForeground(0x0DF5, n)
        if (stream != null) return START_NOT_STICKY
        val ep = DfyRtmp.endpoint(this)
        if (ep == null) { fail("未设置推流地址/推流码"); return START_NOT_STICKY }
        if (!DfyLive.isLive()) { fail("先在直播间开播（她要在线才能推流，不支持录播）"); return START_NOT_STICKY }
        url = ep
        try {
            val video: VideoSource = if (mode == "screen") {
                val code = intent?.getIntExtra(DfyRtmp.EXTRA_PROJ_CODE, 0) ?: 0
                @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>(DfyRtmp.EXTRA_PROJ_DATA) ?: throw IllegalStateException("没有录屏授权")
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                ScreenSource(this, mpm.getMediaProjection(code, data))
            } else CanvasVideoSource(DfyCompositor(preset.w, preset.h))
            val a = TtsAudioSource(); audio = a
            DfySpeaker.sink = { pcm -> a.push(pcm) }
            val s = GenericStream(this, this, video, a)
            val ok = s.prepareVideo(preset.w, preset.h, preset.videoBitrate, preset.fps, 2) && s.prepareAudio(44100, false, 96_000)
            if (!ok) { fail("编码器不支持该分辨率/码率"); return START_NOT_STICKY }
            s.getStreamClient().setReTries(30)
            stream = s
            DfyRtmp.status.value = StreamStatus("connecting", "连接中…", preset = preset.label, source = mode, startedAt = System.currentTimeMillis())
            s.startStream(url)
        } catch (e: Exception) {
            Log.w("DafeiyuRtmp", "start", e); fail("推流启动失败：${clean(e.message, 80)}")
        }
        // stop pushing when she goes offline (no 录播 / unattended replay)
        scope.launch { while (isActive) { delay(2000); if (!DfyLive.isLive()) { stopSelf(); break } } }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(NotificationChannel("mibot_dafeiyu_stream", "大肥鱼 · 推流", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply { action = Intent.ACTION_VIEW; data = Uri.parse(DfyRoutes.DEEP_LIVE) }
        val pi = open?.let { PendingIntent.getActivity(this, 0x0DF5, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(this, "mibot_dafeiyu_stream").setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("大肥鱼正在直播推流").setContentText(text).setOngoing(true).apply { pi?.let { setContentIntent(it) } }.build()
    }

    private fun update(text: String) { runCatching { getSystemService(NotificationManager::class.java)?.notify(0x0DF5, notification(text)) } }

    private fun fail(msg: String) {
        DfyRtmp.status.value = DfyRtmp.status.value.copy(state = "failed", detail = msg)
        update(msg); stopSelf()
    }

    override fun onDestroy() {
        DfySpeaker.sink = null
        runCatching { stream?.let { if (it.isStreaming) it.stopStream(); it.release() } }
        stream = null; audio = null
        if (DfyRtmp.status.value.state != "failed") DfyRtmp.status.value = DfyRtmp.status.value.copy(state = "stopped", detail = "已停止推流", bitrateKbps = 0)
        scope.cancel()
        super.onDestroy()
    }

    // ConnectChecker
    override fun onConnectionStarted(url: String) { DfyRtmp.status.value = DfyRtmp.status.value.copy(state = "connecting", detail = "连接中…") }
    override fun onConnectionSuccess() { tries = 0; DfyRtmp.status.value = DfyRtmp.status.value.copy(state = "live", detail = "推流中"); update("推流中 · " + DfyRtmp.status.value.preset) }
    override fun onConnectionFailed(reason: String) {
        val s = stream ?: return
        tries++
        if (s.getStreamClient().reTry(5000, reason)) {
            DfyRtmp.status.value = DfyRtmp.status.value.copy(state = "reconnecting", detail = "网络断了，重连中（第 $tries 次）：$reason", reconnects = DfyRtmp.status.value.reconnects + 1)
        } else fail("推流失败：$reason")
    }
    override fun onNewBitrate(bitrate: Long) { DfyRtmp.status.value = DfyRtmp.status.value.copy(bitrateKbps = bitrate / 1000) }
    override fun onDisconnect() { DfyRtmp.status.value = DfyRtmp.status.value.copy(state = "stopped", detail = "已断开", bitrateKbps = 0) }
    override fun onAuthError() { fail("推流地址鉴权失败（推流码错误或已过期）") }
    override fun onAuthSuccess() {}
}
