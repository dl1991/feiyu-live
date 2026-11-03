package com.dl1991.feiyu.dafeiyu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** Live 导播台 state: what the owner has overridden during this stream. */
data class DirectorState(
    val segIndex: Int = 0,
    val segStartedAt: Long = 0,
    val voicePaused: Boolean = false,     // she holds new turns (comments keep queueing)
    val muted: Boolean = false,           // she talks, no sound goes out
    val standby: Boolean = false,         // 「暂停直播画面」: standby card on stream, no turns
    val standbyText: String = "主播马上回来，先别走开～",
    val pinned: LiveEvent? = null,
    val topicCard: String = "",
    val lastAction: String = "",
)

sealed class DirectorCmd {
    data class ChangeTopic(val topic: String) : DirectorCmd()
    data class TopicCard(val text: String) : DirectorCmd()
    object SkipPage : DirectorCmd()
    data class JumpSegment(val index: Int) : DirectorCmd()
    data class Pin(val e: LiveEvent) : DirectorCmd()
}

/**
 * The 导播台. Toggles (mute / pause voice / standby / pin) apply immediately through [state];
 * actions that need her to speak or browse are queued as [DirectorCmd] and taken by the live
 * engine before the next comment, so they never cut another viewer's answer mid-sentence
 * (except standby and pause, which interrupt at once).
 */
object FyDirector {
    private val _s = MutableStateFlow(DirectorState())
    val state: StateFlow<DirectorState> get() = _s
    internal val queue = ConcurrentLinkedQueue<DirectorCmd>()

    fun reset() { _s.value = DirectorState(segStartedAt = System.currentTimeMillis()); queue.clear(); DfySpeaker.muted = false }

    fun segment(c: ContentSettings, st: DirectorState = _s.value): Segment {
        val l = c.enabledSegments
        return l[st.segIndex.mod(l.size)]
    }

    /** Seconds left in the current segment (negative = over time). */
    fun secondsLeft(c: ContentSettings, now: Long = System.currentTimeMillis()): Long {
        val st = _s.value
        return segment(c, st).minutes * 60L - (now - st.segStartedAt) / 1000
    }

    /** Auto-rotate: called by the engine; returns the new segment when it moved on. */
    internal fun tick(ctx: Context, now: Long = System.currentTimeMillis()): Segment? {
        val c = FyContentStore.get(ctx)
        if (!c.autoRotate || _s.value.standby) return null
        if (secondsLeft(c, now) > 0) return null
        val next = (_s.value.segIndex + 1).mod(c.enabledSegments.size)
        _s.value = _s.value.copy(segIndex = next, segStartedAt = now)
        return segment(c)
    }

    internal fun jumpTo(index: Int, ctx: Context): Segment {
        val c = FyContentStore.get(ctx)
        _s.value = _s.value.copy(segIndex = index.mod(c.enabledSegments.size), segStartedAt = System.currentTimeMillis())
        return segment(c)
    }

    private fun note(a: String) { _s.value = _s.value.copy(lastAction = a) }

    // ------------------------------------------------------------------ owner controls

    fun changeTopic(topic: String) { if (topic.isNotBlank()) { queue += DirectorCmd.ChangeTopic(clean(topic, 30)); note("换话题：$topic") } }
    fun pushTopicCard(text: String) { if (text.isNotBlank()) { _s.value = _s.value.copy(topicCard = clean(text, 40)); queue += DirectorCmd.TopicCard(clean(text, 40)); note("话题卡：$text") } }
    fun clearTopicCard() { _s.value = _s.value.copy(topicCard = "") }
    fun skipPage() { queue += DirectorCmd.SkipPage; note("跳过当前网页") }
    fun switchSegment(index: Int) { queue += DirectorCmd.JumpSegment(index); note("切换环节") }
    fun pin(e: LiveEvent) { _s.value = _s.value.copy(pinned = e); queue += DirectorCmd.Pin(e); note("置顶：${e.nick}") }
    fun unpin() { _s.value = _s.value.copy(pinned = null) }

    fun setMuted(on: Boolean) { DfySpeaker.muted = on; _s.value = _s.value.copy(muted = on); note(if (on) "静音" else "取消静音") }

    fun setVoicePaused(on: Boolean) {
        _s.value = _s.value.copy(voicePaused = on); note(if (on) "暂停说话" else "继续说话")
        if (on) DfyLive.interruptSpeech()
    }

    fun setStandby(on: Boolean, text: String? = null) {
        _s.value = _s.value.copy(standby = on, standbyText = text?.takeIf { it.isNotBlank() }?.let { clean(it, 40) } ?: _s.value.standbyText)
        note(if (on) "暂停直播画面" else "恢复直播画面")
        if (on) DfyLive.interruptSpeech()
    }

    /** Prompt block for the current turn: 节目单 + all 直播内容 knobs, in her words. */
    fun promptBlock(c: ContentSettings, now: Long = System.currentTimeMillis()): String = buildString {
        val seg = segment(c)
        val left = secondsLeft(c, now)
        append("【节目单】现在是「${seg.title}」环节").append(if (left > 0) "（还剩约 ${(left + 59) / 60} 分钟）" else "（时间到了，准备过渡）").append("：")
            .append(SegKind.instruction(seg.kind)).append(seg.note.takeIf { it.isNotBlank() }?.let { "（主人备注：$it）" } ?: "").append('\n')
        append("【互动比例】").append(when {
            c.chatRatio >= 75 -> "以和观众聊天为主，少主动换网页，多问观众问题。"
            c.chatRatio >= 45 -> "聊天和冲浪差不多一半一半。"
            else -> "以带大家冲浪为主，弹幕简短回应后继续看网页。"
        }).append('\n')
        append("【回复长度】").append(when (c.replyLength) { "short" -> "每次只说 1 句，尽量不超过 25 个字。"; "long" -> "每次 3~5 句，可以展开讲讲。"; else -> "每次 1~3 句。" }).append('\n')
        append("【性格】").append(listOf(
            level(c.lively, "安静内敛", "正常活泼", "特别活泼、语速快、爱用感叹"),
            level(c.snark, "说话温柔不毒舌", "偶尔小小吐槽", "嘴很毒、爱吐槽（但不人身攻击）"),
            level(c.curiosity, "按部就班", "有好奇心", "好奇心爆棚、什么都想点进去看看"),
            level(c.patience, "没耐心、重复问题会小抱怨", "有耐心", "非常有耐心、反复解释也不烦"),
        ).joinToString("；")).append("；情绪表达强度 ${c.emotionIntensity}/100。\n")
        if (c.catchphrases.isNotEmpty()) append("【口头禅】偶尔（不是每句）自然地说：").append(c.catchphrases.joinToString("、")).append('\n')
        if (c.forbidden.isNotEmpty()) append("【禁用词】绝对不要说这些词：").append(c.forbidden.joinToString("、")).append('\n')
        if (c.topics.isNotEmpty()) append("【主题偏好】按权重优先：").append(c.topics.sortedByDescending { it.weight }.joinToString("、") { "${it.topic}(${it.weight})" }).append('\n')
        append("【自由探索】").append(when {
            c.explore >= 70 -> "可以自由跑题、被有趣的东西吸引就换方向。"
            c.explore >= 35 -> "可以偶尔跑题，但要拉回主题。"
            else -> "紧扣主题，尽量不跑题。"
        }).append('\n')
        if (c.whitelist.isNotEmpty()) append("【网站】只去这些网站：").append(c.whitelist.joinToString("、")).append('\n')
        if (c.blacklist.isNotEmpty()) append("【网站】不要去：").append(c.blacklist.joinToString("、")).append('\n')
        if (!c.requestsOn) append("【点播】今天不接受观众点播和指挥换网页，被要求时礼貌说明今天按节目单走。\n")
        _s.value.topicCard.takeIf { it.isNotBlank() }?.let { append("【话题卡】屏幕上正显示话题卡「$it」，可以围绕它聊。\n") }
        _s.value.pinned?.let { append("【置顶弹幕】${it.nick}：${clean(it.text, 60)}\n") }
    }

    private fun level(v: Int, lo: String, mid: String, hi: String) = when { v >= 70 -> hi; v >= 35 -> mid; else -> lo } + "($v)"
}
