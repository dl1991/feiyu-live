package com.dl1991.feiyu.dafeiyu

import org.json.JSONObject

/**
 * User settings for 大肥鱼人生. Defaults are conservative: the life loop is off until the user
 * turns it on, every B站 write action is off, and confirmation is required for each write.
 */
data class DfySettings(
    // Life loop
    val lifeEnabled: Boolean = false,
    val intervalMinutes: Int = 180,          // WorkManager minimum is 15
    val activeFromHour: Int = 9,
    val activeToHour: Int = 23,              // exclusive; equal to from = all day
    val respectBatterySaver: Boolean = true,
    val unmeteredOnly: Boolean = false,
    val picksPerRun: Int = 3,
    val candidatesPerRun: Int = 8,
    val dailyPickBudget: Int = 12,
    val coreInterests: List<String> = listOf("DeepSeek／DSH", "AI模型拟人", "鬼畜与抽象", "游戏"),
    val readSubtitles: Boolean = true,
    val readComments: Boolean = true,
    // Live
    val liveEnabled: Boolean = true,
    val liveUids: List<Long> = emptyList(),  // streamers she "follows" without login
    val liveUseLoginFollows: Boolean = true, // with a cookie: also read the account's followed live list
    // Memory
    val memoryHalfLifeDays: Int = 30,        // episodes; 15..120 like the original
    val insightHalfLifeDays: Int = 180,
    val reflectMinPending: Int = 4,
    val reflectDailyBatches: Int = 2,
    // Model
    val preferFreeModels: Boolean = true,
    val maxModelCallsPerRun: Int = 6,
    // Persona
    val personaPrompt: String = "",          // empty = bundled default
    val ownerName: String = "主人",
    // B站 write actions — all OFF by default
    val writeEnabled: Boolean = false,
    val allowLike: Boolean = false,
    val allowComment: Boolean = false,
    val allowFollow: Boolean = false,
    val allowDm: Boolean = false,
    val quotaLike: Int = 10,
    val quotaComment: Int = 3,
    val quotaFollow: Int = 2,
    val quotaDm: Int = 2,
    val fullAuto: Boolean = false,           // false = every write waits for the user's approval
    val dmAllowlist: List<Long> = emptyList(), // DMs only ever go to these UIDs
    val ownerUid: Long = 0,
    // 冲浪 / 学知识
    val surfMaxPages: Int = 18,
    val surfMaxMinutes: Int = 25,
    val surfMaxModelCalls: Int = 30,
    val surfMaxTokens: Int = 80_000,         // rough (chars / 2) budget per session
    val surfHopSeconds: Int = 12,            // pause between page loads (politeness)
    val surfSources: List<String> = listOf("bing", "wiki", "baike", "bili", "github", "news", "ddg"),
    val surfAllowLoginPages: Boolean = false,
    val surfAllowForms: Boolean = false,     // form submit / posting stays blocked unless switched on
    val surfRespectRobots: Boolean = true,
    val surfAutoInBackground: Boolean = false, // scheduled runs may also do a short text-only learn session
    val surfViewerObedience: Int = 70,       // 0..100: how readily she follows viewer steering
    // Companion
    val overlayEnabled: Boolean = false,
    val notifyDigest: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lifeEnabled", lifeEnabled).put("intervalMinutes", intervalMinutes).put("activeFromHour", activeFromHour)
        .put("activeToHour", activeToHour).put("respectBatterySaver", respectBatterySaver).put("unmeteredOnly", unmeteredOnly)
        .put("picksPerRun", picksPerRun).put("candidatesPerRun", candidatesPerRun).put("dailyPickBudget", dailyPickBudget)
        .put("coreInterests", coreInterests.toJsonArray()).put("readSubtitles", readSubtitles).put("readComments", readComments)
        .put("liveEnabled", liveEnabled).put("liveUids", liveUids.map { it.toString() }.toJsonArray()).put("liveUseLoginFollows", liveUseLoginFollows)
        .put("memoryHalfLifeDays", memoryHalfLifeDays).put("insightHalfLifeDays", insightHalfLifeDays)
        .put("reflectMinPending", reflectMinPending).put("reflectDailyBatches", reflectDailyBatches)
        .put("preferFreeModels", preferFreeModels).put("maxModelCallsPerRun", maxModelCallsPerRun)
        .put("personaPrompt", personaPrompt).put("ownerName", ownerName)
        .put("writeEnabled", writeEnabled).put("allowLike", allowLike).put("allowComment", allowComment)
        .put("allowFollow", allowFollow).put("allowDm", allowDm).put("quotaLike", quotaLike).put("quotaComment", quotaComment)
        .put("quotaFollow", quotaFollow).put("quotaDm", quotaDm).put("fullAuto", fullAuto)
        .put("dmAllowlist", dmAllowlist.map { it.toString() }.toJsonArray()).put("ownerUid", ownerUid)
        .put("overlayEnabled", overlayEnabled).put("notifyDigest", notifyDigest)
        .put("surfMaxPages", surfMaxPages).put("surfMaxMinutes", surfMaxMinutes).put("surfMaxModelCalls", surfMaxModelCalls)
        .put("surfMaxTokens", surfMaxTokens).put("surfHopSeconds", surfHopSeconds).put("surfSources", surfSources.toJsonArray())
        .put("surfAllowLoginPages", surfAllowLoginPages).put("surfAllowForms", surfAllowForms).put("surfRespectRobots", surfRespectRobots)
        .put("surfAutoInBackground", surfAutoInBackground).put("surfViewerObedience", surfViewerObedience)

    fun sanitized(): DfySettings = copy(
        intervalMinutes = intervalMinutes.coerceIn(15, 24 * 60),
        activeFromHour = activeFromHour.coerceIn(0, 23),
        activeToHour = activeToHour.coerceIn(0, 24),
        picksPerRun = picksPerRun.coerceIn(1, 6),
        candidatesPerRun = candidatesPerRun.coerceIn(3, 20),
        dailyPickBudget = dailyPickBudget.coerceIn(1, 60),
        memoryHalfLifeDays = memoryHalfLifeDays.coerceIn(15, 120),
        insightHalfLifeDays = insightHalfLifeDays.coerceIn(30, 720),
        reflectMinPending = reflectMinPending.coerceIn(2, 20),
        reflectDailyBatches = reflectDailyBatches.coerceIn(0, 6),
        maxModelCallsPerRun = maxModelCallsPerRun.coerceIn(1, 20),
        quotaLike = quotaLike.coerceIn(0, 50), quotaComment = quotaComment.coerceIn(0, 20),
        quotaFollow = quotaFollow.coerceIn(0, 10), quotaDm = quotaDm.coerceIn(0, 10),
        coreInterests = DfyInterests.normalizeCore(coreInterests),
        liveUids = liveUids.filter { it > 0 }.distinct().take(40),
        dmAllowlist = dmAllowlist.filter { it > 0 }.distinct().take(10),
        surfMaxPages = surfMaxPages.coerceIn(3, 80), surfMaxMinutes = surfMaxMinutes.coerceIn(3, 120),
        surfMaxModelCalls = surfMaxModelCalls.coerceIn(4, 120), surfMaxTokens = surfMaxTokens.coerceIn(10_000, 500_000),
        surfHopSeconds = surfHopSeconds.coerceIn(4, 120), surfViewerObedience = surfViewerObedience.coerceIn(0, 100),
        surfSources = surfSources.filter { it in SurfSources.ALL }.distinct().ifEmpty { listOf("bing", "wiki") },
    )

    companion object {
        fun from(o: JSONObject): DfySettings {
            val d = DfySettings()
            fun longs(k: String) = o.optJSONArray(k)?.strings()?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            return DfySettings(
                lifeEnabled = o.optBoolean("lifeEnabled", d.lifeEnabled), intervalMinutes = o.optInt("intervalMinutes", d.intervalMinutes),
                activeFromHour = o.optInt("activeFromHour", d.activeFromHour), activeToHour = o.optInt("activeToHour", d.activeToHour),
                respectBatterySaver = o.optBoolean("respectBatterySaver", d.respectBatterySaver), unmeteredOnly = o.optBoolean("unmeteredOnly", d.unmeteredOnly),
                picksPerRun = o.optInt("picksPerRun", d.picksPerRun), candidatesPerRun = o.optInt("candidatesPerRun", d.candidatesPerRun),
                dailyPickBudget = o.optInt("dailyPickBudget", d.dailyPickBudget),
                coreInterests = o.optJSONArray("coreInterests")?.strings() ?: d.coreInterests,
                readSubtitles = o.optBoolean("readSubtitles", d.readSubtitles), readComments = o.optBoolean("readComments", d.readComments),
                liveEnabled = o.optBoolean("liveEnabled", d.liveEnabled), liveUids = longs("liveUids"),
                liveUseLoginFollows = o.optBoolean("liveUseLoginFollows", d.liveUseLoginFollows),
                memoryHalfLifeDays = o.optInt("memoryHalfLifeDays", d.memoryHalfLifeDays), insightHalfLifeDays = o.optInt("insightHalfLifeDays", d.insightHalfLifeDays),
                reflectMinPending = o.optInt("reflectMinPending", d.reflectMinPending), reflectDailyBatches = o.optInt("reflectDailyBatches", d.reflectDailyBatches),
                preferFreeModels = o.optBoolean("preferFreeModels", d.preferFreeModels), maxModelCallsPerRun = o.optInt("maxModelCallsPerRun", d.maxModelCallsPerRun),
                personaPrompt = o.optStr("personaPrompt"), ownerName = o.optStr("ownerName").ifBlank { d.ownerName },
                writeEnabled = o.optBoolean("writeEnabled", false), allowLike = o.optBoolean("allowLike", false),
                allowComment = o.optBoolean("allowComment", false), allowFollow = o.optBoolean("allowFollow", false),
                allowDm = o.optBoolean("allowDm", false), quotaLike = o.optInt("quotaLike", d.quotaLike), quotaComment = o.optInt("quotaComment", d.quotaComment),
                quotaFollow = o.optInt("quotaFollow", d.quotaFollow), quotaDm = o.optInt("quotaDm", d.quotaDm), fullAuto = o.optBoolean("fullAuto", false),
                dmAllowlist = longs("dmAllowlist"), ownerUid = o.optLong("ownerUid", 0),
                overlayEnabled = o.optBoolean("overlayEnabled", false), notifyDigest = o.optBoolean("notifyDigest", d.notifyDigest),
                surfMaxPages = o.optInt("surfMaxPages", d.surfMaxPages), surfMaxMinutes = o.optInt("surfMaxMinutes", d.surfMaxMinutes),
                surfMaxModelCalls = o.optInt("surfMaxModelCalls", d.surfMaxModelCalls), surfMaxTokens = o.optInt("surfMaxTokens", d.surfMaxTokens),
                surfHopSeconds = o.optInt("surfHopSeconds", d.surfHopSeconds), surfSources = o.optJSONArray("surfSources")?.strings() ?: d.surfSources,
                surfAllowLoginPages = o.optBoolean("surfAllowLoginPages", false), surfAllowForms = o.optBoolean("surfAllowForms", false),
                surfRespectRobots = o.optBoolean("surfRespectRobots", true), surfAutoInBackground = o.optBoolean("surfAutoInBackground", false),
                surfViewerObedience = o.optInt("surfViewerObedience", d.surfViewerObedience),
            ).sanitized()
        }
    }
}
