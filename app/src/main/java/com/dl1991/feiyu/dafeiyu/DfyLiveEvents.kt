package com.dl1991.feiyu.dafeiyu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Everything that can happen in the room. */
object EvType {
    const val COMMENT = "comment"; const val ENTER = "enter"; const val LIKE = "like"
    const val FOLLOW = "follow"; const val GIFT = "gift"; const val SHARE = "share"
}

data class LiveEvent(
    val type: String,
    val userKey: String,       // stable id from the source (uid / hashed nickname for a11y)
    val nick: String,
    val text: String = "",
    val giftName: String = "",
    val giftCount: Int = 0,
    val giftValue: Int = 0,    // source units (抖币 etc.), 0 if unknown
    val at: Long = System.currentTimeMillis(),
    val source: String = "manual",
)

/**
 * Pluggable comment / event sources. All feed [DfyEventBus]; the conversation manager does not
 * care where an event came from.
 *  - [SimulatedViewers]: local test crowd (no network), default for testing the room.
 *  - manual: the 直播间 input box ("我" as a viewer) / agent injection.
 *  - [DouyinA11yReader]: reads the 抖音 app's live comment list via the user's already-granted
 *    accessibility service (hook documented in notes/dafeiyu-integration.md).
 *  - [OfficialApiSource]: placeholder for 抖音开放平台 live interaction (needs approval).
 */
object DfyEventBus {
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<LiveEvent> get() = _events
    fun emit(e: LiveEvent) { _events.tryEmit(e) }
}

interface LiveEventSource {
    val id: String
    val label: String
    fun start(scope: CoroutineScope)
    fun stop()
}

/** A small simulated audience with personalities, for testing without going live. */
class SimulatedViewers(private val intensity: Int = 2) : LiveEventSource {
    override val id = "sim"
    override val label = "模拟观众"
    private var job: Job? = null
    private data class Fake(val key: String, val nick: String, val style: String)
    private val crowd = listOf(
        Fake("sim:1", "芝士雪豹", "question"), Fake("sim:2", "早睡失败", "cheer"), Fake("sim:3", "鲸鱼饲养员", "regular"),
        Fake("sim:4", "路过的猫", "lurker"), Fake("sim:5", "理工男老王", "nerd"), Fake("sim:6", "嘴硬小张", "tease"),
        Fake("sim:7", "喷子007", "rude"), Fake("sim:8", "学习委员", "director"),
    )
    private val lines = mapOf(
        "question" to listOf("鱼鱼今天在看啥呀", "这个网站是干嘛的？", "你最喜欢吃什么", "鱼鱼会做饭吗", "刚才那个是真的假的？"),
        "cheer" to listOf("666", "哈哈哈哈", "鱼鱼好可爱", "来了来了", "冲冲冲"),
        "regular" to listOf("又来看鱼鱼了", "昨天说的那个后来怎么样了", "今天心情咋样", "我是老观众了吧"),
        "lurker" to listOf("路过", "这是AI吗"),
        "nerd" to listOf("这个原理其实是…你知道吗", "能讲讲 Transformer 吗", "GitHub 上有啥好项目"),
        "tease" to listOf("鱼鱼是不是又在摸鱼", "你这个鲸鱼怎么这么胖", "说错了吧哈哈", "我赌你答不上来"),
        "rude" to listOf("无聊", "这主播不行啊", "滚吧sb"),
        "director" to listOf("带我们去看看维基百科的鲸鱼", "搜一下 今天的新闻", "去B站看看热门", "投票：看游戏还是看科技", "考考我们今天学的"),
    )

    override fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val present = mutableSetOf<Fake>()
            while (isActive) {
                delay((6000L / intensity.coerceIn(1, 5)) + Random.nextLong(0, 5000))
                val f = crowd.random()
                if (f !in present) { present += f; DfyEventBus.emit(LiveEvent(EvType.ENTER, f.key, f.nick, source = id)); continue }
                when (Random.nextInt(20)) {
                    0 -> DfyEventBus.emit(LiveEvent(EvType.GIFT, f.key, f.nick, giftName = listOf("小心心", "玫瑰", "棒棒糖", "嘉年华").random(), giftCount = 1 + Random.nextInt(3), giftValue = listOf(1, 1, 10, 3000).random(), source = id))
                    1, 2 -> DfyEventBus.emit(LiveEvent(EvType.LIKE, f.key, f.nick, giftCount = 1 + Random.nextInt(30), source = id))
                    3 -> DfyEventBus.emit(LiveEvent(EvType.FOLLOW, f.key, f.nick, source = id))
                    else -> DfyEventBus.emit(LiveEvent(EvType.COMMENT, f.key, f.nick, text = lines[f.style]!!.random(), source = id))
                }
            }
        }
    }
    override fun stop() { job?.cancel(); job = null }
}

/** Placeholder: 抖音开放平台 live interaction (弹幕/礼物/点赞 push) requires an approved app + server. */
class OfficialApiSource : LiveEventSource {
    override val id = "douyin-open"
    override val label = "抖音开放平台（需审核，未实现）"
    override fun start(scope: CoroutineScope) { /* intentionally not implemented: needs approved credentials + server webhook */ }
    override fun stop() {}
}

/**
 * Reads live comments from the 抖音 app UI via the user's own accessibility service.
 * Call [onWindow] from that service's onAccessibilityEvent for package com.ss.android.ugc.aweme
 * (see integration note). Heuristic: in the live room the comment list is a RecyclerView whose
 * rows read "昵称：内容" or "昵称 来了"/"送出了…" — we dedupe rows already seen. UNTESTED on a device.
 */
object DouyinA11yReader : LiveEventSource {
    override val id = "douyin-a11y"
    override val label = "抖音 App 评论（无障碍读取）"
    const val PKG = "com.ss.android.ugc.aweme"
    @Volatile var enabled = false
    private val seen = LinkedHashSet<String>()

    override fun start(scope: CoroutineScope) { enabled = true; synchronized(seen) { seen.clear() } }
    override fun stop() { enabled = false }

    private val ROW = Regex("^(.{1,24}?)[:：]\\s*(.{1,120})$")
    private val ENTER = Regex("^(.{1,24}?)\\s*(来了|进入直播间|加入了直播间)$")
    private val GIFT = Regex("^(.{1,24}?)\\s*送(出了|出|给主播)?\\s*(.{1,12}?)\\s*[x×X]?\\s*(\\d{0,4})$")
    private val LIKE = Regex("^(.{1,24}?)\\s*(为主播点赞了|点赞了)")
    private val FOLLOW = Regex("^(.{1,24}?)\\s*(关注了主播|关注了你)")

    /** Feed visible row texts (already extracted from the node tree, newest last). */
    fun onRows(rows: List<String>) {
        if (!enabled) return
        for (raw in rows.takeLast(20)) {
            val t = raw.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) continue
            val fresh = synchronized(seen) { if (seen.add(t)) { while (seen.size > 400) seen.remove(seen.first()); true } else false }
            if (!fresh) continue
            fun key(n: String) = "dy:" + n.hashCode().toUInt().toString(16)
            ENTER.find(t)?.let { m -> DfyEventBus.emit(LiveEvent(EvType.ENTER, key(m.groupValues[1]), m.groupValues[1], source = id)); return@let } ?: run {
                LIKE.find(t)?.let { m -> DfyEventBus.emit(LiveEvent(EvType.LIKE, key(m.groupValues[1]), m.groupValues[1], giftCount = 1, source = id)) }
                    ?: FOLLOW.find(t)?.let { m -> DfyEventBus.emit(LiveEvent(EvType.FOLLOW, key(m.groupValues[1]), m.groupValues[1], source = id)) }
                    ?: GIFT.find(t)?.let { m -> DfyEventBus.emit(LiveEvent(EvType.GIFT, key(m.groupValues[1]), m.groupValues[1], giftName = m.groupValues[3], giftCount = m.groupValues[4].toIntOrNull() ?: 1, source = id)) }
                    ?: ROW.find(t)?.let { m -> DfyEventBus.emit(LiveEvent(EvType.COMMENT, key(m.groupValues[1]), m.groupValues[1], text = m.groupValues[2], source = id)) }
            }
        }
    }

    /** Walk an accessibility tree and collect candidate comment rows. */
    fun onWindow(root: android.view.accessibility.AccessibilityNodeInfo?) {
        if (!enabled || root == null || root.packageName?.toString() != PKG) return
        val out = mutableListOf<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int, inList: Boolean) {
            if (n == null || depth > 30 || out.size > 60) return
            val cls = n.className?.toString().orEmpty()
            val list = inList || cls.contains("RecyclerView") || cls.contains("ListView")
            val txt = (n.text ?: n.contentDescription)?.toString()
            if (list && !txt.isNullOrBlank() && n.childCount == 0) out += txt
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, list)
        }
        walk(root, 0, false)
        onRows(out)
    }
}

/** Viewer memory across streams. Only nickname, counts and short facts the viewer said publicly. */
data class ViewerProfile(
    val key: String,
    var nick: String,
    val firstSeen: Long,
    var lastSeen: Long,
    var streams: Int = 1,
    var messages: Int = 0,
    var gifts: Int = 0,
    var affinity: Int = 0,          // -50..100, moved by praise / rudeness / time spent
    var facts: List<String> = emptyList(), // ≤ 6 short public facts ("喜欢原神", "是学生")
    var lastStreamId: String = "",
    var blocked: Boolean = false,
) {
    /** 新观众 → 见过几次 → 常客 → 老粉 (rudeness can drop below 新). */
    val level: String get() = when {
        affinity < -10 -> "冷淡"
        streams >= 8 && affinity >= 40 -> "老粉"
        streams >= 3 || messages >= 30 -> "常客"
        streams >= 2 || messages >= 5 -> "见过几次"
        else -> "新观众"
    }
    fun toJson(): JSONObject = JSONObject().put("key", key).put("nick", nick).put("firstSeen", firstSeen).put("lastSeen", lastSeen)
        .put("streams", streams).put("messages", messages).put("gifts", gifts).put("affinity", affinity)
        .put("facts", JSONArray(facts)).put("lastStreamId", lastStreamId).put("blocked", blocked)
    companion object {
        fun from(o: JSONObject) = ViewerProfile(o.optStr("key"), o.optStr("nick"), o.optLong("firstSeen"), o.optLong("lastSeen"),
            o.optInt("streams", 1), o.optInt("messages"), o.optInt("gifts"), o.optInt("affinity"),
            o.optJSONArray("facts")?.strings() ?: emptyList(), o.optStr("lastStreamId"), o.optBoolean("blocked"))
    }
}
