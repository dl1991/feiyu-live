package com.dl1991.feiyu.dafeiyu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/** Connection state of the B站 danmaku socket, for the 直播接入 page and the room header. */
data class BiliDanmakuStatus(
    val state: String = "idle",       // idle | connecting | live | retrying | failed
    val detail: String = "",
    val roomId: Long = 0,
    val roomTitle: String = "",
    val messages: Int = 0,
    val masked: Boolean = false,      // logged out: B站 hides nicknames ("Z***")
    val watched: Int = 0,
)

/**
 * B站 live danmaku over the public web socket (protocol checked 2026-11-02):
 *  - packet = 16-byte big-endian header (total len u32, header len u16 = 16, protover u16, op u32, seq u32) + body
 *  - op 7 auth `{uid, roomid, protover:2, buvid, platform:"web", type:2, key}` → op 8 `{"code":0}`
 *  - op 2 heartbeat every 30 s → op 3 popularity
 *  - op 5 commands; protover 2 bodies are zlib-wrapped packet batches (we ask for 2 so no brotli is needed)
 * Host + token come from the WBI-signed getDanmuInfo ([BiliApi.danmuInfo]). Without a B站 login,
 * nicknames arrive masked and uid is 0; `user_hash` in the message extra is used as a stable key.
 */
class BiliDanmakuSource(private val ctx: Context, private val roomId: Long) : LiveEventSource {
    override val id = "bili"
    override val label = "B站弹幕 · $roomId"
    private var job: Job? = null
    @Volatile private var ws: WebSocket? = null

    companion object {
        private const val TAG = "FeiyuBili"
        val status = MutableStateFlow(BiliDanmakuStatus())
        private val http by lazy { OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build() }

        fun packet(op: Int, body: ByteArray, ver: Int = 1): ByteArray {
            val b = ByteBuffer.allocate(16 + body.size)
            b.putInt(16 + body.size); b.putShort(16); b.putShort(ver.toShort()); b.putInt(op); b.putInt(1); b.put(body)
            return b.array()
        }

        /** Splits a frame into (op, body) pairs, inflating protover-2 batches. */
        fun unpack(buf: ByteArray, out: MutableList<Pair<Int, ByteArray>> = mutableListOf()): List<Pair<Int, ByteArray>> {
            var off = 0
            while (off + 16 <= buf.size) {
                val bb = ByteBuffer.wrap(buf, off, 16)
                val len = bb.int; val hl = bb.short.toInt(); val ver = bb.short.toInt(); val op = bb.int
                if (len < 16 || off + len > buf.size) break
                val body = buf.copyOfRange(off + hl, off + len)
                when (ver) {
                    2 -> runCatching { unpack(inflate(body), out) }
                    3 -> Unit // brotli: never requested
                    else -> out += op to body
                }
                off += len
            }
            return out
        }

        private fun inflate(b: ByteArray): ByteArray {
            val inf = Inflater(); inf.setInput(b)
            val o = ByteArrayOutputStream(); val tmp = ByteArray(8192)
            while (!inf.finished()) { val n = inf.inflate(tmp); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; o.write(tmp, 0, n) }
            inf.end(); return o.toByteArray()
        }

        /** One op-5 command → event (null for the many we ignore). Pure: unit-tested. */
        fun toEvent(cmdJson: JSONObject): LiveEvent? {
            val cmd = cmdJson.optString("cmd").substringBefore(':')
            fun key(uid: Long, hash: String, nick: String) = "bili:" + when { uid > 0 -> uid.toString(); hash.isNotBlank() -> "h$hash"; else -> nick.hashCode().toUInt().toString(16) }
            return when (cmd) {
                "DANMU_MSG" -> {
                    val info = cmdJson.optJSONArray("info") ?: return null
                    val text = info.optString(1)
                    val user = info.optJSONArray(2) ?: JSONArray()
                    val uid = user.optLong(0); val nick = user.optString(1).ifBlank { "观众" }
                    val extra = info.optJSONArray(0)?.optJSONObject(15)?.optString("extra")?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val hash = extra?.optString("user_hash").orEmpty()
                    if (text.isBlank()) null else LiveEvent(EvType.COMMENT, key(uid, hash, nick), nick, text = text.take(200), source = "bili")
                }
                "SEND_GIFT" -> cmdJson.optJSONObject("data")?.let { d ->
                    val nick = d.optString("uname").ifBlank { "观众" }
                    LiveEvent(EvType.GIFT, key(d.optLong("uid"), "", nick), nick, giftName = d.optString("giftName"), giftCount = d.optInt("num", 1),
                        giftValue = d.optInt("price") * d.optInt("num", 1) / 100, source = "bili")
                }
                "SUPER_CHAT_MESSAGE" -> cmdJson.optJSONObject("data")?.let { d ->
                    val u = d.optJSONObject("user_info"); val nick = u?.optString("uname").orEmpty().ifBlank { "观众" }
                    LiveEvent(EvType.GIFT, key(d.optLong("uid"), "", nick), nick, text = d.optString("message"), giftName = "醒目留言", giftCount = 1, giftValue = d.optInt("price") * 10, source = "bili")
                }
                "GUARD_BUY" -> cmdJson.optJSONObject("data")?.let { d ->
                    val nick = d.optString("username").ifBlank { "观众" }
                    LiveEvent(EvType.GIFT, key(d.optLong("uid"), "", nick), nick, giftName = d.optString("gift_name", "舰长"), giftCount = d.optInt("num", 1), giftValue = d.optInt("price") / 100, source = "bili")
                }
                "LIKE_INFO_V3_CLICK" -> cmdJson.optJSONObject("data")?.let { d ->
                    val nick = d.optString("uname").ifBlank { "观众" }
                    LiveEvent(EvType.LIKE, key(d.optLong("uid"), "", nick), nick, giftCount = 1, source = "bili")
                }
                "INTERACT_WORD" -> cmdJson.optJSONObject("data")?.let { d -> // JSON variant (V2 is protobuf and skipped)
                    val nick = d.optString("uname").ifBlank { "观众" }
                    val type = if (d.optInt("msg_type", 1) == 2) EvType.FOLLOW else EvType.ENTER
                    LiveEvent(type, key(d.optLong("uid"), "", nick), nick, source = "bili")
                }
                else -> null
            }
        }
    }

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                status.value = status.value.copy(state = if (attempt == 0) "connecting" else "retrying", roomId = roomId, detail = "")
                val ended = runCatching { session(this) }.exceptionOrNull()
                if (!isActive) break
                attempt++
                val wait = minOf(60_000L, 3_000L * attempt)
                status.value = status.value.copy(state = if (attempt > 6) "failed" else "retrying", detail = (ended?.message ?: "连接断开").take(100) + "，${wait / 1000}s 后重连")
                Log.w(TAG, "danmaku: ${ended?.message}")
                if (attempt > 6) break
                delay(wait)
            }
        }
    }

    private suspend fun session(scope: CoroutineScope) {
        val info = BiliApi.danmuInfo(ctx, roomId)
        status.value = status.value.copy(roomId = info.realRoomId, roomTitle = info.roomTitle, masked = info.uid == 0L)
        val closed = kotlinx.coroutines.CompletableDeferred<Throwable?>()
        val auth = JSONObject().put("uid", info.uid).put("roomid", info.realRoomId).put("protover", 2).put("buvid", info.buvid3)
            .put("platform", "web").put("type", 2).put("key", info.token).toString()
        val req = Request.Builder().url(info.hosts.first()).header("User-Agent", BiliApi.userAgent).header("Origin", "https://live.bilibili.com")
            .apply { BiliApi.cookieFor(ctx).takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }.build()
        val socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(packet(7, auth.toByteArray()).toByteString()) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                for ((op, body) in unpack(bytes.toByteArray())) when (op) {
                    8 -> {
                        val ok = runCatching { JSONObject(String(body)).optInt("code", -1) == 0 }.getOrDefault(false)
                        if (ok) status.value = status.value.copy(state = "live", detail = if (info.uid == 0L) "未登录 B站：昵称会被打码" else "")
                        else { closed.complete(IllegalStateException("认证失败")); webSocket.close(1000, null) }
                    }
                    5 -> runCatching { JSONObject(String(body)) }.getOrNull()?.let { j ->
                        if (j.optString("cmd") == "WATCHED_CHANGE") status.value = status.value.copy(watched = j.optJSONObject("data")?.optInt("num") ?: 0)
                        toEvent(j)?.let { e -> DfyEventBus.emit(e); if (e.type == EvType.COMMENT) status.value = status.value.copy(messages = status.value.messages + 1) }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(null) }
        })
        ws = socket
        val hb = scope.launch { while (isActive) { socket.send(packet(2, "[object Object]".toByteArray()).toByteString()); delay(30_000) } }
        try { closed.await()?.let { throw it } } finally { hb.cancel(); socket.cancel() }
    }

    override fun stop() {
        job?.cancel(); job = null; ws?.cancel(); ws = null
        status.value = status.value.copy(state = "idle", detail = "")
    }
}
