package com.dl1991.feiyu.dafeiyu

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.sin


/** Avatar expressions (a superset of the six the stream needs). */
object Expr {
    const val NEUTRAL = "neutral"; const val HAPPY = "happy"; const val CURIOUS = "curious"; const val SURPRISED = "surprised"
    const val CONFUSED = "confused"; const val SLEEPY = "sleepy"; const val EXCITED = "excited"; const val SAD = "sad"; const val ANNOYED = "annoyed"
    const val SHY = "shy"
    val ALL = listOf(NEUTRAL, HAPPY, CURIOUS, SURPRISED, CONFUSED, SLEEPY, EXCITED, SAD, ANNOYED, SHY)
    fun label(e: String) = when (e) {
        HAPPY -> "开心"; CURIOUS -> "好奇"; SURPRISED -> "惊讶"; CONFUSED -> "疑惑"; SLEEPY -> "犯困"; EXCITED -> "兴奋"
        SAD -> "低落"; ANNOYED -> "不爽"; SHY -> "害羞"; else -> "平静"
    }
    /** Default expression for a mood (a turn may override it with [[FACE …]]). */
    fun forMood(mood: String, intensity: Double, energy: Double): String = when {
        energy < 25 -> SLEEPY
        mood == "joyful" && intensity > 55 -> EXCITED
        mood == "joyful" || mood == "warm" -> HAPPY
        mood == "curious" -> CURIOUS
        mood == "tired" -> SLEEPY
        mood == "sad" -> SAD
        mood == "irritated" -> ANNOYED
        else -> NEUTRAL
    }
}

/**
 * Avatar entry point shared by the Compose room, the floating whale and the RTMP compositor.
 * Default style is the sprite-based 「二次元大肥鱼」 ([DfySprites]); 「简笔鱼」 is the old procedural
 * fish ([DfyFish]) and also the fallback whenever sprites are not loaded yet / failed to load.
 * [full] = whole body inside a box size×1.5size (standby card); otherwise a bust inside ≈size×size.
 */
object DfyAvatar {
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, expr: String, t: Double, mouth: Float, full: Boolean = false) {
        val sp = DfySprites.current()
        if (sp == null) { DfyFish.draw(c, cx, cy, size, expr, t, mouth); return }
        if (full) {
            val bw = size; val bh = size * 1.5f
            DfySprites.draw(c, sp, RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), expr, t, mouth, "full")
        } else {
            val side = size * 1.08f; val y = cy - size * 0.06f
            DfySprites.draw(c, sp, RectF(cx - side / 2, y - side / 2, cx + side / 2, y + side / 2), expr, t, mouth, "bust")
        }
    }
    fun blinking(t: Double) = DfyFish.blinking(t)
}

/**
 * Procedural 大肥鱼 「简笔鱼」 (a round blue whale-girl, flat vector style — no external art assets, so no
 * licensing questions). Draws into any android.graphics.Canvas: the Compose room and the RTMP
 * compositor share it. Animation inputs: [t] seconds (breathing / tail sway / blink schedule),
 * [mouth] 0..1 from TTS loudness.
 */
object DfyFish {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4D, 0x6B, 0xFE) }   // DeepSeek-ish blue
    private val belly = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE8, 0xEE, 0xFF) }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x14, 0x18, 0x2B) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0xFF, 0x7A, 0x8A) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x14, 0x18, 0x2B); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mouthFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x8A, 0x2B, 0x3A) }
    private val spout = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0x9F, 0xD2, 0xFF) }

    /** Blink every ~3.7 s for 140 ms (slightly irregular). */
    fun blinking(t: Double): Boolean { val p = (t + sin(t * 0.37) * 0.4) % 3.7; return p < 0.14 }

    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, expr: String, t: Double, mouth: Float) {
        val breathe = (sin(t * 2 * PI / 3.2) * 0.025).toFloat()
        val bob = (sin(t * 2 * PI / 2.4) * size * 0.015).toFloat()
        val w = size * (1f + breathe); val h = size * 0.78f * (1f - breathe * 0.6f)
        val y = cy + bob
        stroke.strokeWidth = size * 0.028f
        // tail
        val sway = (sin(t * 2 * PI / 1.8) * size * 0.06).toFloat()
        val tail = Path().apply {
            moveTo(cx + w * 0.42f, y + h * 0.05f)
            quadTo(cx + w * 0.62f, y - h * 0.05f + sway, cx + w * 0.72f, y - h * 0.32f + sway)
            quadTo(cx + w * 0.6f, y - h * 0.12f + sway, cx + w * 0.78f, y - h * 0.02f + sway)
            quadTo(cx + w * 0.6f, y + h * 0.05f, cx + w * 0.42f, y + h * 0.22f)
            close()
        }
        c.drawPath(tail, body)
        // body + belly
        c.drawOval(RectF(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2), body)
        c.drawOval(RectF(cx - w * 0.36f, y + h * 0.02f, cx + w * 0.30f, y + h * 0.48f), belly)
        // fin (waves a little when excited)
        val fin = if (expr == Expr.EXCITED || expr == Expr.HAPPY) (sin(t * 2 * PI / 0.6) * size * 0.04).toFloat() else 0f
        c.drawOval(RectF(cx - w * 0.52f, y + h * 0.08f + fin, cx - w * 0.30f, y + h * 0.24f + fin), body)
        // spout when surprised / excited
        if (expr == Expr.SURPRISED || expr == Expr.EXCITED) {
            val s = size * 0.05f
            c.drawCircle(cx - w * 0.05f, y - h * 0.62f, s, spout); c.drawCircle(cx + w * 0.05f, y - h * 0.70f, s * 0.8f, spout); c.drawCircle(cx - w * 0.12f, y - h * 0.74f, s * 0.7f, spout)
        }
        // eyes
        val ex = w * 0.17f; val ey = y - h * 0.08f; val er = size * 0.06f
        val blink = blinking(t)
        for (side in listOf(-1f, 1f)) {
            val x = cx - w * 0.06f + side * ex
            when {
                blink || expr == Expr.SLEEPY -> c.drawLine(x - er, ey, x + er, ey + (if (expr == Expr.SLEEPY) er * 0.3f else 0f), stroke)
                expr == Expr.HAPPY || expr == Expr.EXCITED -> c.drawArc(RectF(x - er, ey - er * 0.6f, x + er, ey + er * 1.2f), 200f, 140f, false, stroke)
                expr == Expr.ANNOYED -> { c.drawCircle(x, ey + er * 0.2f, er * 0.75f, ink); c.drawLine(x - er, ey - er * (0.9f + side * 0.3f), x + er, ey - er * (0.9f - side * 0.3f), stroke) }
                expr == Expr.SAD -> { c.drawCircle(x, ey + er * 0.2f, er * 0.75f, ink); c.drawLine(x - er, ey - er * (0.9f - side * 0.35f), x + er, ey - er * (0.9f + side * 0.35f), stroke) }
                else -> {
                    val r = if (expr == Expr.SURPRISED) er * 1.15f else er
                    c.drawCircle(x, ey, r, ink)
                    c.drawCircle(x - r * 0.3f, ey - r * 0.35f, r * 0.32f, white)
                }
            }
        }
        if (expr == Expr.CONFUSED) { // question-mark squiggle above head
            stroke.strokeWidth = size * 0.022f
            c.drawArc(RectF(cx + w * 0.18f, y - h * 0.82f, cx + w * 0.32f, y - h * 0.66f), 180f, 270f, false, stroke)
            c.drawCircle(cx + w * 0.25f, y - h * 0.52f, size * 0.012f, ink)
            stroke.strokeWidth = size * 0.028f
        }
        // blush
        c.drawOval(RectF(cx - w * 0.38f, ey + er * 1.1f, cx - w * 0.24f, ey + er * 1.9f), blush)
        c.drawOval(RectF(cx + w * 0.14f, ey + er * 1.1f, cx + w * 0.28f, ey + er * 1.9f), blush)
        // mouth: opens with TTS loudness; shape by expression
        val mx = cx - w * 0.06f; val my = ey + er * 2.1f
        val open = (mouth * size * 0.07f).coerceAtMost(size * 0.07f)
        when {
            open > size * 0.006f -> c.drawOval(RectF(mx - size * 0.05f, my - open * 0.4f, mx + size * 0.05f, my + open), mouthFill)
            expr == Expr.SURPRISED -> c.drawCircle(mx, my + size * 0.01f, size * 0.025f, mouthFill)
            expr == Expr.SAD || expr == Expr.ANNOYED -> c.drawArc(RectF(mx - size * 0.045f, my, mx + size * 0.045f, my + size * 0.05f), 200f, 140f, false, stroke)
            expr == Expr.CONFUSED -> c.drawLine(mx - size * 0.035f, my + size * 0.012f, mx + size * 0.035f, my - size * 0.008f, stroke)
            else -> c.drawArc(RectF(mx - size * 0.05f, my - size * 0.03f, mx + size * 0.05f, my + size * 0.03f), 20f, 140f, false, stroke)
        }
    }
}
