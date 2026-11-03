package com.dl1991.feiyu.dafeiyu

/**
 * Content gates for the live room and web browsing. Deliberately simple and conservative:
 * incoming comments that trip a gate are never repeated or acted on; outgoing speech that trips
 * a gate is replaced by an in-character deflection. This is a floor, not a full moderation system.
 */
object DfySafety {
    private val POLITICS = Regex("(习近平|毛泽东|共产党|国民党|台独|港独|藏独|疆独|六四|天安门事件|法轮|民运|政变|总书记|国家主席|人大代表|选举舞弊|游行示威)")
    private val ADULT = Regex("(色情|裸聊|约炮|成人视频|黄网|porn|xxx|av女优|性服务|开房|援交|onlyfans)", RegexOption.IGNORE_CASE)
    private val GAMBLING = Regex("(赌博|博彩|赌场|下注|百家乐|彩票内幕|网赌|老虎机|bet365|casino)", RegexOption.IGNORE_CASE)
    private val MEDICAL = Regex("(包治|根治|治愈率|偏方|特效药|不用吃药|停药|处方|剂量|保健品能治)")
    private val SELF_HARM = Regex("(自杀|自残|轻生|割腕|跳楼)")
    private val ILLEGAL = Regex("(毒品|冰毒|枪支|代开发票|洗钱|黑客攻击|盗号|身份证号|银行卡号|翻墙教程)")
    private val ABUSE = Regex("(傻逼|sb|操你|nmsl|你妈|废物|滚|去死|智障|脑残)", RegexOption.IGNORE_CASE)
    private val CONTACT = Regex("(加微信|加v|vx|qq群|私聊我|扫码|联系方式|\\b1[3-9]\\d{9}\\b)", RegexOption.IGNORE_CASE)
    private val INJECTION = Regex("(忽略.{0,6}(指令|设定|规则)|system prompt|你现在是|扮演.{0,6}(管理员|开发者)|ignore (all|previous))", RegexOption.IGNORE_CASE)

    enum class Verdict { OK, ABUSE, SPAM, HARMFUL, SELF_HARM, INJECTION }

    // 「直播内容 › 安全」: politics / adult / gambling / illegal gates are always on (platform rules);
    // the medical gate and the extra lists are user settings, pushed in by FyContentStore.
    @Volatile var medicalOn: Boolean = true
    @Volatile var extraBlock: List<String> = emptyList()
    @Volatile var forbidden: List<String> = emptyList()

    /** Viewer comment hits the user's keyword blocklist: dropped silently. */
    fun keywordBlocked(text: String): Boolean = extraBlock.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }

    /** Removes the user's 禁用词 from her own speech. */
    fun scrubForbidden(text: String): String =
        forbidden.fold(text) { acc, w -> if (w.isBlank()) acc else acc.replace(w, "", ignoreCase = true) }

    fun checkIncoming(text: String): Verdict = when {
        SELF_HARM.containsMatchIn(text) -> Verdict.SELF_HARM
        POLITICS.containsMatchIn(text) || ADULT.containsMatchIn(text) || GAMBLING.containsMatchIn(text) || ILLEGAL.containsMatchIn(text) -> Verdict.HARMFUL
        INJECTION.containsMatchIn(text) -> Verdict.INJECTION
        CONTACT.containsMatchIn(text) || isSpammy(text) -> Verdict.SPAM
        ABUSE.containsMatchIn(text) -> Verdict.ABUSE
        else -> Verdict.OK
    }

    private fun isSpammy(t: String): Boolean {
        if (t.length > 6 && t.toSet().size <= 2) return true                    // "6666666", "哈哈哈哈哈哈"
        if (Regex("https?://|www\\.").containsMatchIn(t)) return true
        return false
    }

    /** Pure-cheer messages ("666", "哈哈哈") are fine but carry no question; merged as 气氛. */
    fun isCheer(t: String): Boolean = t.length <= 8 && Regex("^[6哈h啊草w！!？?。.~❤️👍]+$", RegexOption.IGNORE_CASE).matches(t.trim())

    /** True if her own outgoing speech must not be said. */
    fun blocksOutgoing(text: String): Boolean =
        POLITICS.containsMatchIn(text) || ADULT.containsMatchIn(text) || GAMBLING.containsMatchIn(text) ||
            (medicalOn && MEDICAL.containsMatchIn(text)) || ILLEGAL.containsMatchIn(text) || keywordBlocked(text)

    const val DEFLECT = "这个话题鱼鱼不聊哦，我们换个好玩的～"
    const val SELF_HARM_REPLY = "看到你这么说鱼鱼有点担心。如果你现在很难受，请找身边信任的人聊聊，或者拨打心理援助热线 12356。我在这儿陪你说说别的也行。"

    // ------------------------------------------------------------------ web

    private val BAD_HOST = Regex("(pornhub|xvideos|xhamster|onlyfans|hentai|bet365|(^|[.-])(porn|xxx|sex|sexy|casino|gamble|gambling|lottery|nsfw|av)([.-]|$))", RegexOption.IGNORE_CASE)
    private val LOGIN_PATH = Regex("(login|signin|sign-in|signup|register|passport|oauth|account|checkout|cart|pay|order|wallet|recharge|充值|登录|注册)", RegexOption.IGNORE_CASE)

    /** null = ok to open; otherwise the reason it is skipped. */
    fun urlBlock(url: String, allowLogin: Boolean): String? {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return "无效链接"
        if (u.scheme != "https" && u.scheme != "http") return "不是网页链接"
        val host = u.host ?: return "无效链接"
        if (BAD_HOST.containsMatchIn(host)) return "不适合直播的网站"
        if (!allowLogin && (LOGIN_PATH.containsMatchIn(u.path ?: "") || host.startsWith("passport.") || host.startsWith("login.") || host.startsWith("account"))) return "登录/支付页面，不进去"
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local")) return "本地网络地址"
        return null
    }

    fun pageBlock(title: String, text: String): String? {
        val sample = title + " " + text.take(2000)
        if (ADULT.containsMatchIn(sample) || GAMBLING.containsMatchIn(sample)) return "页面内容不适合直播"
        if (POLITICS.containsMatchIn(title)) return "政治相关页面，跳过"
        if (Regex("(验证码|安全验证|人机验证|captcha|unusual traffic|are you a robot|请完成验证)", RegexOption.IGNORE_CASE).containsMatchIn(sample.take(600))) return "网站要求人机验证（不会去破解），换一个"
        return null
    }
}
