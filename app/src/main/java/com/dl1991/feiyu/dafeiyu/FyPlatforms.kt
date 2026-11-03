package com.dl1991.feiyu.dafeiyu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Where the stream goes. Every platform is plain RTMP push with the user's own address + key. */
enum class LivePlatform(val id: String, val label: String, val urlHint: String, val help: String) {
    DOUYIN("douyin", "抖音", "rtmp://push-rtmp-l?.douyincdn.com/stage",
        "抖音直播伴侣 / 电脑开播 → 获取推流地址。推流权限受账号实名、等级、粉丝数等限制。"),
    BILIBILI("bilibili", "B站", "rtmp://live-push.bilivideo.com/live-bvc/",
        "B站直播中心 → 我的直播间 → 开播设置，选择分区后复制服务器地址和串流密钥。"),
    KUAISHOU("kuaishou", "快手", "rtmp://open-push.voip.yximgs.com/gifshow/",
        "快手直播伴侣 / 电脑推流 → 推流地址与推流码（需开通推流权限）。"),
    SHIPINHAO("shipinhao", "视频号（通用 RTMP）", "rtmp://…/live",
        "视频号直播 → 推流直播 → 推流地址与密钥；也可填任何支持 RTMP 的平台。"),
    ;
    val urlRef get() = "rtmp_url_$id"
    val keyRef get() = "rtmp_key_$id"

    companion object { fun of(id: String) = values().firstOrNull { it.id == id } ?: DOUYIN }
}

/** Comment / event sources chosen on 「直播接入」, persisted. */
data class SourceConfig(
    val sim: Boolean = true,
    val douyinA11y: Boolean = false,
    val douyinOpen: Boolean = false,
    val bili: Boolean = false,
    val biliRoom: Long = 0,
    val simIntensity: Int = 2,
) {
    fun ids(): Set<String> = buildSet {
        if (sim) add("sim"); if (douyinA11y) add("douyin-a11y"); if (douyinOpen) add("douyin-open"); if (bili && biliRoom > 0) add("bili")
    }
}

object FyPlatforms {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("feiyu_live", Context.MODE_PRIVATE)
    val checklist = MutableStateFlow<Set<Int>>(emptySet())
    val sources = MutableStateFlow(SourceConfig())
    val selected = MutableStateFlow(LivePlatform.DOUYIN)
    @Volatile private var loaded = false

    fun ensure(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        selected.value = LivePlatform.of(p.getString("platform", "douyin") ?: "douyin")
        sources.value = SourceConfig(p.getBoolean("src_sim", true), p.getBoolean("src_dy_a11y", false), p.getBoolean("src_dy_open", false),
            p.getBoolean("src_bili", false), p.getLong("bili_room", 0), p.getInt("sim_intensity", 2))
        checklist.value = p.getStringSet("checklist", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        // migrate the module's single 抖音 endpoint
        if (DfySecrets.has(ctx, DfySecrets.RTMP_URL) && !DfySecrets.has(ctx, LivePlatform.DOUYIN.urlRef)) {
            DfySecrets.set(ctx, LivePlatform.DOUYIN.urlRef, DfySecrets.get(ctx, DfySecrets.RTMP_URL))
            DfySecrets.set(ctx, LivePlatform.DOUYIN.keyRef, DfySecrets.get(ctx, DfySecrets.RTMP_KEY))
        }
        loaded = true
    }

    fun select(ctx: Context, p: LivePlatform) { selected.value = p; prefs(ctx).edit().putString("platform", p.id).apply() }

    fun setSources(ctx: Context, s: SourceConfig) {
        sources.value = s
        prefs(ctx).edit().putBoolean("src_sim", s.sim).putBoolean("src_dy_a11y", s.douyinA11y).putBoolean("src_dy_open", s.douyinOpen)
            .putBoolean("src_bili", s.bili).putLong("bili_room", s.biliRoom).putInt("sim_intensity", s.simIntensity).apply()
    }

    fun setChecklist(ctx: Context, s: Set<Int>) { checklist.value = s; prefs(ctx).edit().putStringSet("checklist", s.map { it.toString() }.toSet()).apply() }

    fun url(ctx: Context, p: LivePlatform) = DfySecrets.get(ctx, p.urlRef)
    fun hasKey(ctx: Context, p: LivePlatform) = DfySecrets.has(ctx, p.keyRef)
    fun save(ctx: Context, p: LivePlatform, url: String?, key: String?) {
        if (!url.isNullOrBlank()) DfySecrets.set(ctx, p.urlRef, url)
        if (!key.isNullOrBlank()) DfySecrets.set(ctx, p.keyRef, key)
    }
    fun clear(ctx: Context, p: LivePlatform) { DfySecrets.set(ctx, p.urlRef, ""); DfySecrets.set(ctx, p.keyRef, "") }

    /** Full RTMP endpoint for the selected platform, or null when incomplete. */
    fun endpoint(ctx: Context): String? {
        ensure(ctx)
        val p = selected.value
        val url = url(ctx, p).trim().trimEnd('/')
        val key = DfySecrets.get(ctx, p.keyRef).trim().trimStart('/')
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) return null
        if (key.isBlank()) return null
        return "$url/$key"
    }

    /** 开播前检查: the 「AI 生成内容」 label is not a checkbox — it is always on. */
    val CHECKLIST = listOf(
        "账号已完成实名认证，我本人对账号与直播内容负责。",
        "我已在所选平台获得推流（电脑 / 第三方）权限；推流码可能受账号等级、粉丝数等限制。",
        "我了解平台对「无人直播 / 录播 / 挂机」的处罚：直播时我在场看护，随时可以暂停或下播。",
        "虚拟角色不冒充真人；画面与语音始终带「AI 生成内容」标识（本应用强制开启，不能关闭）。",
        "不讨论政治、色情、赌博，不给医疗 / 投资建议；违规内容由我负责处理。",
    )
}
