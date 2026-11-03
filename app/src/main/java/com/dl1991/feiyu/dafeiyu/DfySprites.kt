package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Avatar styles: 二次元大肥鱼 (bundled sprites, default) · 简笔鱼 (procedural fish) · 自己的立绘 (imported zip). */
object AvatarStyle {
    const val ANIME = "anime"; const val FISH = "fish"; const val CUSTOM = "custom"
    val ALL = listOf(ANIME, FISH, CUSTOM)
    fun label(s: String) = when (s) { FISH -> "简笔鱼"; CUSTOM -> "自己的立绘"; else -> "二次元大肥鱼" }
}

/** A loaded sprite set: one full-frame PNG per expression + small overlay patches (blink / mouth frames). */
class SpriteSet(
    val name: String,
    val width: Int, val height: Int,
    val expressions: Map<String, Bitmap>,
    val overlays: Map<String, Pair<Bitmap, Pair<Float, Float>>>,
    val map: Map<String, String>,
    val noBlink: Set<String>,
    val crops: Map<String, RectF>,
    val pivotX: Float, val pivotY: Float,
) {
    fun sprite(expr: String): Bitmap? = expressions[map[expr] ?: expr] ?: expressions["neutral"] ?: expressions.values.firstOrNull()
    fun key(expr: String): String = (map[expr] ?: expr).takeIf { it in expressions } ?: "neutral"
}

/**
 * Sprite avatar renderer + loader.
 *
 * Manifest (assets/dafeiyu/avatar/manifest.json; an imported zip must contain the same shape):
 * ```
 * { "width":512, "height":768,
 *   "expressions": { "neutral":"neutral.png", "happy":"happy.png", … },          // full frames, identical framing
 *   "overlays": { "blink":{"file":"blink.png","x":149,"y":225},                  // optional patches drawn on top
 *                 "mouth_slight":{…}, "mouth_wide":{…} },
 *   "map": { "excited":"happy", "curious":"thinking", … },                      // engine expression → sprite
 *   "noBlink": ["happy"], "crops": {"full":[l,t,r,b], "bust":[l,t,r,b]}, "pivot":[x,y] }
 * ```
 * Only "expressions.neutral" is required; everything else falls back.
 *
 * Animation (all derived from the caller's clock `t`, so it is stateless per surface): idle breathing
 * (scale about the feet / bust bottom), slow body sway (±1°), random blinks (hashed per 4 s slot,
 * sometimes double), a bounce while speaking. Mouth frames come from TTS loudness with attack/release
 * smoothing and hysteresis (global, since there is one speaker).
 */
object DfySprites {
    private const val ASSET_DIR = "dafeiyu/avatar"
    private const val PREFS = "feiyu_avatar"
    private val _style = MutableStateFlow(AvatarStyle.ANIME)
    val style: StateFlow<String> get() = _style
    val customName = MutableStateFlow("")
    @Volatile private var bundled: SpriteSet? = null
    @Volatile private var custom: SpriteSet? = null
    @Volatile private var inited = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun customDir(ctx: Context) = File(ctx.applicationContext.filesDir, "feiyu/avatar_custom")

    /** Load style + bitmaps (call off the main thread; cheap to call again). */
    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            val app = ctx.applicationContext
            _style.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("style", AvatarStyle.ANIME)?.takeIf { it in AvatarStyle.ALL } ?: AvatarStyle.ANIME
            bundled = runCatching { load(JSONObject(app.assets.open("$ASSET_DIR/manifest.json").bufferedReader().readText())) { f -> app.assets.open("$ASSET_DIR/$f").use { BitmapFactory.decodeStream(it) } } }.getOrNull()
            custom = runCatching { loadDir(customDir(app)) }.getOrNull()
            customName.value = custom?.name.orEmpty()
            if (_style.value == AvatarStyle.CUSTOM && custom == null) _style.value = AvatarStyle.ANIME
            inited = true
        }
    }

    fun setStyle(ctx: Context, s: String) {
        val v = s.takeIf { it in AvatarStyle.ALL } ?: AvatarStyle.ANIME
        if (v == AvatarStyle.CUSTOM && custom == null) return
        _style.value = v
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("style", v).apply()
    }

    fun hasCustom() = custom != null

    /** The set to draw, or null → procedural fish (style 简笔鱼, or not loaded yet). */
    fun current(): SpriteSet? = when (_style.value) {
        AvatarStyle.FISH -> null
        AvatarStyle.CUSTOM -> custom ?: bundled
        else -> bundled
    }

    private fun loadDir(dir: File): SpriteSet? {
        val mf = File(dir, "manifest.json"); if (!mf.isFile) return null
        return load(JSONObject(mf.readText())) { f -> File(dir, File(f).name).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } }
    }

    private fun load(m: JSONObject, open: (String) -> Bitmap?): SpriteSet {
        val ex = m.getJSONObject("expressions")
        val exprs = LinkedHashMap<String, Bitmap>()
        for (k in ex.keys()) open(ex.getString(k))?.let { exprs[k] = it }
        val neutral = exprs["neutral"] ?: error("manifest: expressions.neutral missing")
        val w = m.optInt("width", neutral.width); val h = m.optInt("height", neutral.height)
        val ov = LinkedHashMap<String, Pair<Bitmap, Pair<Float, Float>>>()
        m.optJSONObject("overlays")?.let { o ->
            for (k in o.keys()) { val e = o.getJSONObject(k); open(e.getString("file"))?.let { ov[k] = it to (e.optDouble("x", 0.0).toFloat() to e.optDouble("y", 0.0).toFloat()) } }
        }
        val map = HashMap<String, String>()
        m.optJSONObject("map")?.let { o -> for (k in o.keys()) map[k] = o.getString(k) }
        val noBlink = HashSet<String>(); m.optJSONArray("noBlink")?.let { a -> for (i in 0 until a.length()) noBlink += a.getString(i) }
        val crops = HashMap<String, RectF>()
        m.optJSONObject("crops")?.let { o -> for (k in o.keys()) { val a = o.getJSONArray(k); crops[k] = RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat()) } }
        crops.getOrPut("full") { RectF(0f, 0f, w.toFloat(), h.toFloat()) }
        crops.getOrPut("bust") { val side = min(w, (h * 0.56f).toInt()).toFloat(); RectF((w - side) / 2, 0f, (w + side) / 2, side) }
        val pv = m.optJSONArray("pivot")
        return SpriteSet(m.optString("name", "立绘"), w, h, exprs, ov, map, noBlink, crops,
            pv?.optDouble(0)?.toFloat() ?: (w / 2f), pv?.optDouble(1)?.toFloat() ?: (h * 0.92f))
    }

    /**
     * 「导入自己的立绘」: a .zip with manifest.json + PNGs (same names as the bundled set). Entries are
     * flattened (no paths), PNG/JSON only, ≤ 40 files / 24 MB. Returns an error message or null on success.
     */
    fun importZip(ctx: Context, uri: Uri): String? {
        val app = ctx.applicationContext
        val tmp = File(app.filesDir, "feiyu/avatar_import.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            var total = 0L; var count = 0
            app.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins.buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val name = File(e.name).name
                        if (name.startsWith(".") || !(name.endsWith(".png", true) || name == "manifest.json")) continue
                        if (++count > 40) return "文件太多（最多 40 个）"
                        File(tmp, name).outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) { val n = z.read(buf); if (n < 0) break; total += n; if (total > 24L shl 20) return "压缩包太大（最多 24 MB）"; out.write(buf, 0, n) }
                        }
                    }
                }
            } ?: return "打不开这个文件"
            if (!File(tmp, "manifest.json").isFile) return "压缩包里没有 manifest.json"
            val set = runCatching { loadDir(tmp) }.getOrElse { return "manifest 无法读取：${it.message}" } ?: return "manifest 无法读取"
            val dst = customDir(app); dst.deleteRecursively(); dst.parentFile?.mkdirs()
            if (!tmp.renameTo(dst)) { tmp.copyRecursively(dst, overwrite = true); tmp.deleteRecursively() }
            custom = set; customName.value = set.name
            setStyle(app, AvatarStyle.CUSTOM)
            return null
        } finally { tmp.deleteRecursively() }
    }

    fun removeCustom(ctx: Context) {
        customDir(ctx.applicationContext).deleteRecursively(); custom = null; customName.value = ""
        if (_style.value == AvatarStyle.CUSTOM) setStyle(ctx, AvatarStyle.ANIME)
    }

    // ---------------------------------------------------------------- animation

    private fun hash(n: Long): Double { var x = n * 0x9E3779B97F4A7C15uL.toLong() + 0x632BE59BD9B4E019L; x = (x xor (x ushr 31)) * -0x40a7b892e31b1a47L; x = x xor (x ushr 29); return ((x ushr 11).toDouble() / (1L shl 53).toDouble()) }

    /** Random-looking blinks: one per 4 s slot at a hashed offset, 25 % of them doubled. */
    fun blinking(t: Double): Boolean {
        val slot = floor(t / 4.0).toLong(); val off = 0.4 + hash(slot) * 3.2; val p = t - slot * 4.0 - off
        if (p in 0.0..0.12) return true
        return hash(slot + 7_777) > 0.75 && p in 0.26..0.36
    }

    // global mouth smoothing (one speaker)
    @Volatile private var lastNs = 0L
    @Volatile private var smooth = 0f
    @Volatile private var frame = 0   // 0 closed, 1 slight, 2 wide

    /** Smoothed loudness → mouth frame with hysteresis. Thresholds tuned for DfySpeaker.level (rms×4, 0..1). */
    fun mouthFrame(level: Float): Int {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.25f)
        lastNs = now
        val tau = if (level > smooth) 0.045f else 0.12f          // fast attack, softer release
        smooth += (level - smooth) * (1f - exp(-dt / tau))
        val v = smooth
        frame = when (frame) {
            0 -> if (v > 0.14f) (if (v > 0.48f) 2 else 1) else 0
            1 -> if (v > 0.48f) 2 else if (v < 0.09f) 0 else 1
            else -> if (v < 0.09f) 0 else if (v < 0.38f) 1 else 2
        }
        return frame
    }

    fun speakingAmount(): Float = smooth.coerceIn(0f, 1f)

    /** Draw [expr] of [set] fitted into [box] using crop "full" or "bust". */
    fun draw(c: Canvas, set: SpriteSet, box: RectF, expr: String, t: Double, mouth: Float, crop: String = "bust", animate: Boolean = true) {
        val cr = set.crops[crop] ?: set.crops["full"]!!
        val k = min(box.width() / cr.width(), box.height() / cr.height())
        val left = box.centerX() - cr.width() * k / 2; val top = box.centerY() - cr.height() * k / 2
        val key = set.key(expr)
        val base = set.expressions[key] ?: return
        val mf = if (animate) mouthFrame(mouth) else 0
        val talk = if (animate) speakingAmount() else 0f
        val px = set.pivotX; val py = if (crop == "full") set.pivotY else cr.bottom
        val breathe = if (animate) (sin(t * 2 * PI / 3.4) * 0.011).toFloat() else 0f
        val sway = if (animate) (sin(t * 2 * PI / 5.3) * 1.0 + sin(t * 2 * PI / 2.1) * 0.25).toFloat() else 0f
        val bounce = if (animate && talk > 0.05f) (-abs(sin(t * 2 * PI / 0.42)) * cr.height() * 0.012f * min(1f, talk * 2.2f)).toFloat() else 0f
        c.save()
        c.translate(left, top); c.scale(k, k); c.translate(-cr.left, -cr.top)
        c.clipRect(cr)
        c.translate(px, py + bounce)
        c.rotate(sway)
        c.scale(1f - breathe * 0.35f, 1f + breathe)
        c.translate(-px, -py)
        c.drawBitmap(base, 0f, 0f, paint)
        if (animate && key !in set.noBlink && blinking(t)) set.overlays["blink"]?.let { (b, xy) -> c.drawBitmap(b, xy.first, xy.second, paint) }
        val mouthKey = when (mf) { 2 -> "mouth_wide"; 1 -> "mouth_slight"; else -> null }
        mouthKey?.let { m -> (set.overlays[m] ?: set.overlays["mouth_slight"])?.let { (b, xy) -> c.drawBitmap(b, xy.first, xy.second, paint) } }
        c.restore()
    }
}
