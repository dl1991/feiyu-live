package com.dl1991.feiyu.dafeiyu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.min

/** Stream presets. Douyin accepts H.264 + AAC over RTMP; 720p is the safe default on phones. */
enum class StreamPreset(val label: String, val w: Int, val h: Int, val fps: Int, val videoBitrate: Int) {
    P720_LOW("720×1280 · 1.5 Mbps", 720, 1280, 20, 1_500_000),
    P720("720×1280 · 2.5 Mbps", 720, 1280, 24, 2_500_000),
    P1080("1080×1920 · 4.5 Mbps", 1080, 1920, 24, 4_500_000),
}

/**
 * Builds each frame of the stream canvas (portrait):
 *   top bar (name · live dot) + topic trail; fixed 「AI 生成」 corner watermark drawn last, on top of every layout
 *   and the standby card (hard-coded — there is no setting that turns it off)
 *   browsing area (snapshot of the shared WebView)
 *   avatar (expression + blink + breathing + TTS lip-sync) | comment ticker
 *   subtitle bar (what she is saying) + footer disclaimer.
 * Drawn on a background thread into a Bitmap; the WebView snapshot is taken on the main
 * thread at a lower rate (≈6 fps) and reused between frames.
 */
class DfyCompositor(val w: Int, val h: Int) {
    companion object { const val WATERMARK = "AI 生成" }
    private val s = w / 720f
    private val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frame)
    @Volatile private var web: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())
    private var snapping = false
    private val t0 = System.nanoTime()

    private val bg = Paint().apply { color = Color.rgb(0x0E, 0x10, 0x14) }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 0x1B, 0x1D, 0x22) }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE8, 0xEA, 0xEE); textSize = 30f * s; isFakeBoldText = true }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9E, 0xA3, 0xAD); textSize = 21f * s }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f * s }
    private val tick = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE8, 0xEA, 0xEE); textSize = 23f * s }
    private val nick = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE0, 0x7E, 0x66); textSize = 23f * s; isFakeBoldText = true }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xAE, 0x45, 0x30) }
    private val labelText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f * s; isFakeBoldText = true }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xEC, 0x4B, 0x4B) }
    private val webPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // 「AI 生成」 watermark: readable size, semi-opaque dark backing + thin outline, fixed top-right corner.
    private val wmBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(165, 0, 0, 0) }
    private val wmEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f * s }
    private val wmText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f * s; isFakeBoldText = true }

    /** Ask the main thread for a fresh WebView snapshot (skipped if one is in flight). */
    fun requestWebSnapshot() {
        if (snapping) return
        snapping = true
        main.post {
            try {
                val v: View? = DfyBrowser.webView
                if (v != null) {
                    if (v.width == 0 || v.height == 0) { // not on screen: lay it out off-screen
                        v.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                        v.layout(0, 0, 720, 1000)
                    }
                    val scale = min(1f, 720f / v.width)
                    val bmp = Bitmap.createBitmap((v.width * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
                    val c = Canvas(bmp); c.scale(scale, scale); c.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
                    v.draw(c)
                    web = bmp
                }
            } catch (_: Throwable) {
            } finally { snapping = false }
        }
    }

    private val cardText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f * s; isFakeBoldText = true }
    private val pinBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 0xF2, 0xB1, 0x34) }
    private val pinText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1A, 0x14, 0x06); textSize = 24f * s; isFakeBoldText = true }

    /**
     * Layout follows 直播内容 › 画面: web_big (网页大 + 鱼小), fish_big (鱼大 + 网页小), chat_only (纯聊天).
     * The 「AI 生成」 watermark is drawn last in every layout, including the standby card.
     */
    fun render(): Bitmap {
        draw()
        watermark(canvas)
        return frame
    }

    /** Fixed corner watermark. Not tied to any setting — always drawn, always on top. */
    private fun watermark(c: Canvas) {
        val tag = WATERMARK
        val tw = wmText.measureText(tag)
        val m = 18f * s
        val r = RectF(w - m - tw - 32 * s, m, w - m, m + 52 * s)
        c.drawRoundRect(r, 12 * s, 12 * s, wmBg)
        c.drawRoundRect(r, 12 * s, 12 * s, wmEdge)
        val fm = wmText.fontMetrics
        c.drawText(tag, r.left + 16 * s, r.centerY() - (fm.ascent + fm.descent) / 2, wmText)
    }

    private fun draw() {
        val st = DfyLive.state.value
        val cs = FyContentStore.state.value
        val dir = FyDirector.state.value
        val t = (System.nanoTime() - t0) / 1e9
        val c = canvas
        sub.textSize = cs.subtitleSize * s
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val pad = 20f * s
        // top bar
        c.drawCircle(pad + 10 * s, 52 * s, 9 * s, dot)
        c.drawText("大肥鱼 · ${st.segment.ifBlank { if (st.mode == SurfMode.LEARN) "学知识" else "冲浪" }}", pad + 30 * s, 62 * s, title)
        if (dir.standby) { standby(c, dir.standbyText, t, pad); return }
        val trail = (st.trail.takeLast(4).joinToString("  ›  ").ifBlank { st.topic }).let { TextUtils.ellipsize(it, small, w - 2 * pad, TextUtils.TruncateAt.START).toString() }
        c.drawText(trail, pad, 104 * s, small)
        val subTop: Float
        when (cs.layout) {
            "fish_big" -> {
                val av = RectF(pad, 118 * s, w - pad, h * 0.50f)
                c.drawRoundRect(av, 22 * s, 22 * s, card)
                DfyAvatar.draw(c, av.centerX(), av.centerY() + 10 * s, av.height() * 0.78f, st.expression, t, DfySpeaker.level.value)
                c.drawText("${Expr.label(st.expression)} · ${st.moodLabel}", av.left + 16 * s, av.bottom - 16 * s, small)
                val rowTop = av.bottom + 14 * s; val rowBottom = h * 0.80f
                val webW = (w - 2 * pad - 14 * s) * (if (cs.ticker) 0.52f else 1f)
                drawWeb(c, RectF(pad, rowTop, pad + webW, rowBottom), st)
                if (cs.ticker) drawTicker(c, RectF(pad + webW + 14 * s, rowTop, w - pad, rowBottom), st)
                subTop = rowBottom + 14 * s
            }
            "chat_only" -> {
                val av = RectF(pad, 118 * s, w - pad, h * 0.42f)
                c.drawRoundRect(av, 22 * s, 22 * s, card)
                DfyAvatar.draw(c, av.centerX(), av.centerY() + 10 * s, av.height() * 0.8f, st.expression, t, DfySpeaker.level.value)
                val tk = RectF(pad, av.bottom + 14 * s, w - pad, h * 0.80f)
                if (cs.ticker) drawTicker(c, tk, st, rows = 12) else c.drawRoundRect(tk, 22 * s, 22 * s, card)
                subTop = tk.bottom + 14 * s
            }
            else -> {
                val area = RectF(pad, 118 * s, w - pad, h * 0.62f)
                drawWeb(c, area, st)
                val rowTop = area.bottom + 16 * s; val rowBottom = h * 0.84f
                val av = RectF(pad, rowTop, pad + (rowBottom - rowTop), rowBottom)
                c.drawRoundRect(av, 22 * s, 22 * s, card)
                DfyAvatar.draw(c, av.centerX(), av.centerY() + 8 * s, av.width() * 0.62f, st.expression, t, DfySpeaker.level.value)
                c.drawText("${Expr.label(st.expression)} · ${st.moodLabel}", av.left + 14 * s, av.bottom - 14 * s, small)
                if (cs.ticker) drawTicker(c, RectF(av.right + 14 * s, rowTop, w - pad, rowBottom), st)
                subTop = rowBottom + 14 * s
            }
        }
        // poll / quiz / topic card overlay
        val overlay = dir.topicCard.takeIf { it.isNotBlank() }?.let { "话题卡：$it" }
            ?: st.poll?.let { p -> "投票：${p.question}  " + p.options.mapIndexed { i, o -> "${i + 1}.$o ${p.tally()[i]}" }.joinToString("  ") }
            ?: st.quiz?.let { "答题：${it.question}（弹幕回答）" } ?: st.guess?.let { "猜谜：${it.hint}（弹幕回答）" }
        if (overlay != null) {
            val ob = RectF(pad + 12 * s, 130 * s, w - pad - 12 * s, 188 * s)
            c.drawRoundRect(ob, 12 * s, 12 * s, label)
            c.drawText(TextUtils.ellipsize(overlay, labelText, ob.width() - 24 * s, TextUtils.TruncateAt.END).toString(), ob.left + 12 * s, ob.bottom - 20 * s, labelText)
        }
        dir.pinned?.let { e ->
            val pb = RectF(pad + 12 * s, 198 * s, w - pad - 12 * s, 248 * s)
            c.drawRoundRect(pb, 10 * s, 10 * s, pinBg)
            c.drawText(TextUtils.ellipsize("置顶 · ${e.nick}：${e.text}", pinText, pb.width() - 24 * s, TextUtils.TruncateAt.END).toString(), pb.left + 12 * s, pb.bottom - 16 * s, pinText)
        }
        // subtitle
        val sb = RectF(pad, subTop, w - pad, h - 64 * s)
        c.drawRoundRect(sb, 18 * s, 18 * s, card)
        val text = st.subtitle.ifBlank { if (st.thinking) "嗯…" else "" }
        if (text.isNotBlank() && sb.height() > 20) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, sub, (sb.width() - 40 * s).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.clipRect(sb); c.translate(sb.left + 20 * s, sb.top + maxOf(0f, (sb.height() - layout.height) / 2)); layout.draw(c); c.restore()
        }
        c.drawText("虚拟角色「大肥鱼」· 画面与语音由 AI 实时生成", pad, h - 26 * s, small)
    }

    private fun drawWeb(c: Canvas, area: RectF, st: RoomState) {
        c.drawRoundRect(area, 18 * s, 18 * s, card)
        web?.let { b ->
            val src = Rect(0, 0, b.width, min(b.height, (b.width * area.height() / area.width()).toInt()))
            c.save(); c.clipRect(area); c.drawBitmap(b, src, area, webPaint); c.restore()
        } ?: c.drawText("正在打开网页…", area.left + 24 * s, area.centerY(), small)
        c.drawRoundRect(area, 18 * s, 18 * s, hair)
        if (st.pageTitle.isNotBlank()) {
            val tb = RectF(area.left, area.bottom - 46 * s, area.right, area.bottom)
            c.drawRect(tb, card)
            c.drawText(TextUtils.ellipsize(st.pageTitle, small, tb.width() - 24 * s, TextUtils.TruncateAt.END).toString(), tb.left + 12 * s, tb.bottom - 15 * s, small)
        }
    }

    private fun drawTicker(c: Canvas, tk: RectF, st: RoomState, rows: Int = 6) {
        c.drawRoundRect(tk, 22 * s, 22 * s, card)
        var y = tk.top + 40 * s
        c.drawText("在线 ${st.online}  ·  弹幕", tk.left + 16 * s, y, small); y += 38 * s
        for (e in st.ticker.filter { it.type == EvType.COMMENT || it.type == EvType.GIFT }.takeLast(rows)) {
            val n = e.nick.take(8) + "："
            c.drawText(n, tk.left + 16 * s, y, nick)
            val body = if (e.type == EvType.GIFT) "送出 ${e.giftName}×${e.giftCount}" else e.text
            c.drawText(TextUtils.ellipsize(body, tick, tk.width() - 32 * s - nick.measureText(n), TextUtils.TruncateAt.END).toString(), tk.left + 16 * s + nick.measureText(n), y, tick)
            y += 36 * s
            if (y > tk.bottom - 10 * s) break
        }
    }

    /** 「暂停直播画面」 standby card: no web, no speech, sleeping whale + the owner's line. */
    private fun standby(c: Canvas, text: String, t: Double, pad: Float) {
        val area = RectF(pad, 118 * s, w - pad, h - 90 * s)
        c.drawRoundRect(area, 22 * s, 22 * s, card)
        DfyAvatar.draw(c, area.centerX(), area.top + area.height() * 0.38f, area.width() * 0.55f, Expr.SLEEPY, t, 0f, full = true)
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, cardText, (area.width() - 60 * s).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        c.save(); c.translate(area.left + 30 * s, area.top + area.height() * 0.70f); lay.draw(c); c.restore()
        c.drawText("稍等片刻 · 直播暂停中", area.left + 30 * s, area.bottom - 30 * s, small)
        c.drawText("虚拟角色「大肥鱼」· 画面与语音由 AI 实时生成", pad, h - 26 * s, small)
    }
}
