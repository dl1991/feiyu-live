package com.dl1991.feiyu.dafeiyu

import android.content.Context
import com.dl1991.feiyu.net.NetworkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class BiliVideo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val desc: String,
    val ownerMid: Long,
    val ownerName: String,
    val tname: String,
    val duration: Int,
    val view: Long,
    val like: Long,
    val cover: String,
    val source: String,
)

class BiliException(val code: Int, message: String) : Exception(message) {
    /** B站 wants a verification / risk check. We stop — never solve or bypass captchas. */
    val isRiskControl: Boolean get() = code in setOf(-352, -412, -509, 12015, 12016, 10003, -799, 21026, 21027)
    /** Cookie missing / expired / csrf mismatch: the user has to log in again. */
    val isAuth: Boolean get() = code in setOf(-101, -111, -401)
}

/**
 * Public B站 read API (no login needed) + the authenticated POSTs used by [BiliActions].
 * Requests go through Mibot's network routing (Settings › Network), with a desktop-browser UA,
 * the public `buvid3` device cookie and WBI signing where the endpoint requires it.
 * When a cookie from the user's own WebView login exists it is sent too (better subtitles,
 * followed live rooms); it never leaves the device except to *.bilibili.com.
 */
object BiliApi {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val MIXIN = intArrayOf(46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52)

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).followRedirects(true).build()
    }
    private fun client(): OkHttpClient = runCatching { NetworkHttp.client(NetworkHttp.DEFAULT_TAG, base) }.getOrDefault(base)

    @Volatile private var buvid3: String = ""
    @Volatile private var buvid4: String = ""
    @Volatile private var mixinKey: String = ""
    @Volatile private var mixinAt: Long = 0

    private fun cookie(ctx: Context): String {
        val parts = mutableListOf<String>()
        val login = BiliAuth.cookieHeader(ctx)
        if (login.isNotBlank()) parts += login
        if (!login.contains("buvid3=") && buvid3.isNotBlank()) parts += "buvid3=$buvid3"
        if (!login.contains("buvid4=") && buvid4.isNotBlank()) parts += "buvid4=$buvid4"
        return parts.joinToString("; ")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

    private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun query(p: Map<String, String>): String = p.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    private suspend fun raw(ctx: Context, url: String, referer: String = "https://www.bilibili.com/", post: okhttp3.RequestBody? = null): String =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer)
                .header("Origin", if (referer.contains("live.bilibili")) "https://live.bilibili.com" else "https://www.bilibili.com")
            val ck = cookie(ctx)
            if (ck.isNotBlank()) rb.header("Cookie", ck)
            if (post != null) rb.post(post) else rb.get()
            client().newCall(rb.build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful && body.isBlank()) throw BiliException(-r.code, "HTTP ${r.code}")
                body
            }
        }

    private suspend fun json(ctx: Context, url: String, referer: String = "https://www.bilibili.com/", post: okhttp3.RequestBody? = null): JSONObject {
        val body = raw(ctx, url, referer, post)
        val o = runCatching { JSONObject(body) }.getOrElse { throw BiliException(-1, "非 JSON 响应") }
        val code = o.optInt("code", 0)
        if (code != 0) throw BiliException(code, o.optString("message", o.optString("msg", "code $code")))
        return o
    }

    private suspend fun ensureDevice(ctx: Context) {
        if (buvid3.isNotBlank()) return
        runCatching {
            val d = json(ctx, "https://api.bilibili.com/x/frontend/finger/spi").optJSONObject("data")
            buvid3 = d?.optString("b_3").orEmpty(); buvid4 = d?.optString("b_4").orEmpty()
        }
    }

    private suspend fun ensureWbi(ctx: Context) {
        if (mixinKey.isNotBlank() && System.currentTimeMillis() - mixinAt < 6 * 3_600_000L) return
        // nav answers -101 when logged out but still carries wbi_img
        val body = raw(ctx, "https://api.bilibili.com/x/web-interface/nav")
        val img = JSONObject(body).optJSONObject("data")?.optJSONObject("wbi_img") ?: throw BiliException(-2, "无法获取 WBI 密钥")
        fun stem(u: String) = u.substringAfterLast('/').substringBefore('.')
        val k = stem(img.optString("img_url")) + stem(img.optString("sub_url"))
        if (k.length < 64) throw BiliException(-2, "WBI 密钥格式异常")
        mixinKey = MIXIN.map { k[it] }.joinToString("").take(32)
        mixinAt = System.currentTimeMillis()
    }

    /** WBI-signed query string (params sorted, `!'()*` stripped, w_rid = md5(query + mixinKey)). */
    private suspend fun signed(ctx: Context, params: Map<String, String>): String {
        ensureWbi(ctx)
        val p = (params + ("wts" to (System.currentTimeMillis() / 1000).toString())).toSortedMap()
            .mapValues { (_, v) -> v.filterNot { it in "!'()*" } }
        val q = query(p)
        return q + "&w_rid=" + md5(q + mixinKey)
    }

    // ------------------------------------------------------------------ live danmaku (大肥鱼直播)

    data class DanmuInfo(val realRoomId: Long, val token: String, val hosts: List<String>, val buvid3: String, val uid: Long, val roomTitle: String)

    /**
     * Short id → real room id, then the WBI-signed getDanmuInfo (needs a buvid3 cookie; unsigned
     * calls answer -352 — verified 2026-11-02). Logged out, the socket still works with uid 0 but
     * B站 masks nicknames ("Z***") and reports uid 0; each message still carries a stable user_hash.
     */
    suspend fun danmuInfo(ctx: Context, roomId: Long): DanmuInfo {
        ensureDevice(ctx)
        val init = json(ctx, "https://api.live.bilibili.com/room/v1/Room/room_init?id=$roomId", "https://live.bilibili.com/").optJSONObject("data")
            ?: throw BiliException(-404, "直播间不存在")
        val real = init.optLong("room_id").takeIf { it > 0 } ?: roomId
        val title = runCatching {
            json(ctx, "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$real", "https://live.bilibili.com/").optJSONObject("data")?.optString("title").orEmpty()
        }.getOrDefault("")
        val q = signed(ctx, mapOf("id" to real.toString(), "type" to "0", "web_location" to "444.8"))
        val d = json(ctx, "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?$q", "https://live.bilibili.com/$real").optJSONObject("data")
            ?: throw BiliException(-2, "getDanmuInfo 无数据")
        val hosts = d.optJSONArray("host_list")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { h -> "wss://" + h.optString("host") + ":" + h.optInt("wss_port", 443) + "/sub" } } }.orEmpty()
        val ck = cookie(ctx)
        val b3 = Regex("buvid3=([^;]+)").find(ck)?.groupValues?.get(1) ?: buvid3
        return DanmuInfo(real, d.optString("token"), hosts.ifEmpty { listOf("wss://broadcastlv.chat.bilibili.com:443/sub") }, b3, BiliAuth.uid(ctx) ?: 0L, title)
    }

    fun cookieFor(ctx: Context): String = cookie(ctx)
    val userAgent: String get() = UA

    private fun videoFrom(o: JSONObject, source: String): BiliVideo? {
        val bvid = o.optString("bvid")
        if (!Regex("^BV[0-9A-Za-z]{10}$").matches(bvid)) return null
        val owner = o.optJSONObject("owner")
        val stat = o.optJSONObject("stat")
        val title = o.optString("title").replace(Regex("<[^>]+>"), "") // search wraps hits in <em>
        val dur = o.opt("duration")
        val seconds = when (dur) {
            is Number -> dur.toInt()
            is String -> dur.split(':').fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }
            else -> 0
        }
        return BiliVideo(
            bvid = bvid, aid = o.optLong("aid", o.optLong("id")), cid = o.optLong("cid"), title = clean(title, 120),
            desc = clean(o.optString("desc", o.optString("description")), 400),
            ownerMid = owner?.optLong("mid") ?: o.optLong("mid"), ownerName = owner?.optString("name") ?: o.optString("author"),
            tname = o.optString("tname", o.optString("typename")), duration = seconds,
            view = stat?.optLong("view") ?: o.optLong("play"), like = stat?.optLong("like") ?: o.optLong("like"),
            cover = o.optString("pic").let { if (it.startsWith("//")) "https:$it" else it.replace("http://", "https://") }, source = source,
        )
    }

    private fun list(a: JSONArray?, source: String): List<BiliVideo> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> videoFrom(o, source) } }

    suspend fun popular(ctx: Context, page: Int = 1, size: Int = 20): List<BiliVideo> {
        ensureDevice(ctx)
        return list(json(ctx, "https://api.bilibili.com/x/web-interface/popular?ps=$size&pn=$page").optJSONObject("data")?.optJSONArray("list"), "popular")
    }

    suspend fun ranking(ctx: Context, rid: Int = 0): List<BiliVideo> {
        ensureDevice(ctx)
        return list(json(ctx, "https://api.bilibili.com/x/web-interface/ranking/v2?rid=$rid&type=all").optJSONObject("data")?.optJSONArray("list"), "rank")
    }

    /** Video search. May come back empty under B站 risk control (a `v_voucher` answer) — callers fall back. */
    suspend fun search(ctx: Context, keyword: String, page: Int = 1): List<BiliVideo> {
        ensureDevice(ctx)
        val q = signed(ctx, mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString()))
        val d = json(ctx, "https://api.bilibili.com/x/web-interface/wbi/search/type?$q", "https://search.bilibili.com/").optJSONObject("data")
        if (d != null && d.has("v_voucher") && !d.has("result")) throw BiliException(-352, "搜索被风控（v_voucher）")
        return list(d?.optJSONArray("result"), "search:$keyword")
    }

    suspend fun related(ctx: Context, bvid: String): List<BiliVideo> {
        ensureDevice(ctx)
        return list(json(ctx, "https://api.bilibili.com/x/web-interface/archive/related?bvid=$bvid").optJSONArray("data"), "related")
    }

    suspend fun view(ctx: Context, bvid: String, source: String = "view"): BiliVideo? {
        ensureDevice(ctx)
        return json(ctx, "https://api.bilibili.com/x/web-interface/view?bvid=$bvid").optJSONObject("data")?.let { videoFrom(it, source) }
    }

    /** Subtitle text (first track, AI or uploader), capped. Often empty when logged out. */
    suspend fun subtitle(ctx: Context, v: BiliVideo, maxChars: Int = 2500): String {
        if (v.cid <= 0) return ""
        val q = signed(ctx, mapOf("bvid" to v.bvid, "cid" to v.cid.toString()))
        val subs = runCatching { json(ctx, "https://api.bilibili.com/x/player/wbi/v2?$q", "https://www.bilibili.com/video/${v.bvid}") }
            .getOrNull()?.optJSONObject("data")?.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: return ""
        if (subs.length() == 0) return ""
        val pick = (0 until subs.length()).map { subs.getJSONObject(it) }
            .sortedBy { if (it.optString("lan").startsWith("zh") || it.optString("lan").startsWith("ai-zh")) 0 else 1 }.first()
        var url = pick.optString("subtitle_url")
        if (url.isBlank()) return ""
        if (url.startsWith("//")) url = "https:$url"
        val body = runCatching { JSONObject(raw(ctx, url, "https://www.bilibili.com/video/${v.bvid}")) }.getOrNull() ?: return ""
        val lines = body.optJSONArray("body") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until lines.length()) {
            val c = lines.optJSONObject(i)?.optString("content").orEmpty()
            if (c.isNotBlank()) sb.append(c).append(' ')
            if (sb.length >= maxChars) break
        }
        return sb.toString().take(maxChars).trim()
    }

    /** A few hot public comments (first page, no login). */
    suspend fun topComments(ctx: Context, aid: Long, n: Int = 5): List<String> {
        if (aid <= 0) return emptyList()
        val a = runCatching { json(ctx, "https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&sort=1&ps=${n.coerceIn(1, 20)}") }
            .getOrNull()?.optJSONObject("data")?.optJSONArray("replies") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("content")?.optString("message") }
            .map { clean(it, 120) }.filter { it.isNotBlank() }.take(n)
    }

    // ------------------------------------------------------------------ live

    /** Live status for up to 40 UIDs (public). */
    suspend fun liveByUids(ctx: Context, uids: List<Long>): List<LiveRoom> {
        if (uids.isEmpty()) return emptyList()
        val body = JSONObject().put("uids", JSONArray(uids.take(40))).toString().toRequestBody("application/json".toMediaType())
        val d = json(ctx, "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids", "https://live.bilibili.com/", body).optJSONObject("data") ?: return emptyList()
        return d.keys().asSequence().mapNotNull { k ->
            val o = d.optJSONObject(k) ?: return@mapNotNull null
            LiveRoom(o.optLong("uid"), o.optLong("room_id"), o.optString("uname"), clean(o.optString("title"), 80),
                o.optString("area_v2_name", o.optString("area_name")), o.optLong("online"), o.optInt("live_status") == 1)
        }.toList()
    }

    /** Followed rooms that are live right now — needs the user's own login cookie. */
    suspend fun liveFollowed(ctx: Context): List<LiveRoom> {
        if (!BiliAuth.isLoggedIn(ctx)) return emptyList()
        val d = json(ctx, "https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList?page=1&page_size=20", "https://live.bilibili.com/").optJSONObject("data")
        val a = d?.optJSONArray("rooms") ?: d?.optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            LiveRoom(o.optLong("uid"), o.optLong("room_id", o.optLong("roomid")), o.optString("uname"), clean(o.optString("title"), 80),
                o.optString("area_v2_name", o.optString("area_name")), o.optLong("online"), true)
        }
    }

    /** Popular live rooms (all areas, public), used to find rooms that match her interests. */
    suspend fun liveHot(ctx: Context, size: Int = 30): List<LiveRoom> {
        val a = json(ctx, "https://api.live.bilibili.com/room/v1/Area/getListByAreaID?areaId=0&sort=online&pageSize=${size.coerceIn(5, 30)}&page=1", "https://live.bilibili.com/")
            .optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            LiveRoom(o.optLong("uid"), o.optLong("roomid"), o.optString("uname"), clean(o.optString("title"), 80),
                o.optString("areaName", o.optString("area_v2_name")), o.optLong("online"), true)
        }
    }

    // ------------------------------------------------------------------ authenticated writes (BiliActions only)

    private fun csrf(ctx: Context): String = BiliAuth.csrf(ctx) ?: throw BiliException(-101, "未登录 B站")

    suspend fun like(ctx: Context, bvid: String): JSONObject =
        json(ctx, "https://api.bilibili.com/x/web-interface/archive/like", "https://www.bilibili.com/video/$bvid",
            FormBody.Builder().add("bvid", bvid).add("like", "1").add("csrf", csrf(ctx)).build())

    suspend fun comment(ctx: Context, aid: Long, message: String): JSONObject =
        json(ctx, "https://api.bilibili.com/x/v2/reply/add", "https://www.bilibili.com/",
            FormBody.Builder().add("type", "1").add("oid", aid.toString()).add("message", message).add("plat", "1").add("csrf", csrf(ctx)).build())

    suspend fun follow(ctx: Context, mid: Long): JSONObject =
        json(ctx, "https://api.bilibili.com/x/relation/modify", "https://space.bilibili.com/$mid",
            FormBody.Builder().add("fid", mid.toString()).add("act", "1").add("re_src", "11").add("csrf", csrf(ctx)).build())

    suspend fun dm(ctx: Context, toMid: Long, text: String): JSONObject {
        val self = BiliAuth.uid(ctx) ?: throw BiliException(-101, "未登录 B站")
        val c = csrf(ctx)
        val body = FormBody.Builder()
            .add("msg[sender_uid]", self.toString()).add("msg[receiver_id]", toMid.toString()).add("msg[receiver_type]", "1")
            .add("msg[msg_type]", "1").add("msg[msg_status]", "0")
            .add("msg[content]", JSONObject().put("content", text).toString())
            .add("msg[dev_id]", BiliAuth.devId(ctx)).add("msg[timestamp]", (System.currentTimeMillis() / 1000).toString())
            .add("csrf", c).add("csrf_token", c).build()
        return json(ctx, "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg", "https://message.bilibili.com/", body)
    }
}
