package com.dl1991.feiyu.dafeiyu

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Floating companion (needs 悬浮窗 permission, SYSTEM_ALERT_WINDOW — already in the manifest).
 * Off-air: a small whale bubble with her mood and current activity. On-air: a mini live window
 * (the stream canvas, downscaled) so the user can watch while using other apps.
 * Tap → open the 直播间 / 家; drag to move; long-press → close.
 */
object DfyOverlay {
    fun canDraw(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
    fun show(ctx: Context) { if (canDraw(ctx)) ctx.startService(Intent(ctx, DfyOverlayService::class.java)) }
    fun hide(ctx: Context) { ctx.stopService(Intent(ctx, DfyOverlayService::class.java)) }
    /** Called after a background life run; the service polls state itself, so this only (re)starts it if enabled. */
    fun refresh(ctx: Context) { if (DfyStore.snapshot(ctx).settings.overlayEnabled) runCatching { show(ctx) } }
}

class DfyOverlayService : Service() {
    private var view: CompanionView? = null
    private val main = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (view != null || !DfyOverlay.canDraw(this)) return START_NOT_STICKY
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            (150 * d).toInt(), (96 * d).toInt(),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (12 * d).toInt(); y = (180 * d).toInt() }
        val v = CompanionView(this)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val longPress = Runnable { if (!moved) stopSelf() }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; main.postDelayed(longPress, 900) }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > 12) { moved = true; main.removeCallbacks(longPress) }
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); runCatching { wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved) packageManager.getLaunchIntentForPackage(packageName)?.apply {
                        action = Intent.ACTION_VIEW; data = Uri.parse(if (DfyLive.isLive()) DfyRoutes.DEEP_LIVE else DfyRoutes.DEEP_CHAT); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }?.let { startActivity(it) }
                }
            }
            true
        }
        wm.addView(v, lp); view = v
        // resize when going on / off air
        val tick = object : Runnable {
            override fun run() {
                val live = DfyLive.isLive()
                val want = if (live) Pair((140 * d).toInt(), (249 * d).toInt()) else Pair((150 * d).toInt(), (96 * d).toInt())
                if (lp.width != want.first || lp.height != want.second) { lp.width = want.first; lp.height = want.second; runCatching { wm.updateViewLayout(v, lp) } }
                v.invalidate(); main.postDelayed(this, if (live) 100L else 400L)
            }
        }
        main.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        view?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        view = null
        super.onDestroy()
    }
}

private class CompanionView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 0x1B, 0x1D, 0x22) }
    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE8, 0xEA, 0xEE); textSize = 11 * d }
    private val dim = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9E, 0xA3, 0xAD); textSize = 10 * d }
    private val t0 = System.nanoTime()
    private var mini: DfyCompositor? = null
    private var n = 0

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, 16 * d, 16 * d, bg)
        val t = (System.nanoTime() - t0) / 1e9
        if (DfyLive.isLive()) {
            val comp = mini ?: DfyCompositor(360, 640).also { mini = it }
            if (n++ % 5 == 0) comp.requestWebSnapshot()
            val f = comp.render()
            c.save(); c.clipRect(r); c.drawBitmap(f, null, Rect(0, 0, width, height), null); c.restore()
            return
        }
        mini = null
        val st = DfyStore.state.value
        val m = DfyMood.decayed(st.mood, System.currentTimeMillis())
        val expr = Expr.forMood(m.mood, m.intensity, m.energy)
        DfyAvatar.draw(c, 34 * d, height / 2f, 52 * d, expr, t, DfySpeaker.level.value)
        val act = st.meta.activity.ifBlank { if (st.meta.lastRunResult.isNotBlank()) st.meta.lastRunResult else "在发呆" }
        c.drawText("大肥鱼 · " + DfyMood.label(m.mood), 66 * d, 34 * d, txt)
        c.drawText(TextUtils.ellipsize(act, dim, width - 74 * d, TextUtils.TruncateAt.END).toString(), 66 * d, 54 * d, dim)
        c.drawText("点我聊天", 66 * d, 74 * d, dim)
    }
}
