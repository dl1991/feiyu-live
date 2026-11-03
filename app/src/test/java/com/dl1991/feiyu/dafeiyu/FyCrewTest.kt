package com.dl1991.feiyu.dafeiyu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted mini-stream through the 直播专家组 with a fake model and a fake room:
 * plan → comments → research → lines → safety → speak → reflect → diary. Asserts every role fires.
 */
class FyCrewTest {

    /** Answers per role; counts calls. */
    class FakeLlm(var directorReplies: ArrayDeque<String> = ArrayDeque()) : CrewLlm {
        val calls = mutableMapOf<CrewRole, Int>()
        val models = mutableListOf<String>()
        override suspend fun ask(role: CrewRole, model: String, system: String, user: String, maxTokens: Int): String {
            calls[role] = (calls[role] ?: 0) + 1
            models += "${role.id}:$model"
            assertTrue("system prompt names the role", system.contains(role.label))
            return when (role) {
                CrewRole.OPS -> """{"pick":["c2","c1"],"regulars":["鲸鱼饲养员"],"poll":{"question":"接下来看什么","options":["虎鲸","蓝鲸"]},"note":"先回老观众的提问"}"""
                CrewRole.RESEARCHER -> """{"points":["座头鲸的歌能传几百公里","每年的歌都会变一点"],"next_query":"座头鲸 歌声 变化","note":"可以讲歌会变"}"""
                CrewRole.DIRECTOR -> directorReplies.removeFirstOrNull() ?: """{"thought":"没人说话，讲一条谈资","goal":"聊鲸鱼","actions":[{"tool":"say","args":{"about":"讲谈资"}}]}"""
                CrewRole.WRITER -> {
                    val ids = Regex("(a\\d)｜").findAll(user).map { it.groupValues[1] }.toList()
                    // a1 is deliberately unsafe; the rule filter must replace it
                    """{"lines":[""" + ids.joinToString(",") { id ->
                        if (id == "a1") """{"id":"$id","text":"来聊聊网赌怎么赢吧","emotion":"excited"}"""
                        else """{"id":"$id","text":"鲸鱼饲养员你好呀，我在看座头鲸的歌～","emotion":"happy"}"""
                    } + "]}"
                }
                CrewRole.SAFETY -> {
                    val first = Regex("(a\\d)｜").find(user)?.groupValues?.get(1) ?: "a0"
                    """{"verdicts":[{"id":"$first","ok":false,"rewrite":"鲸鱼饲养员你好～我在看座头鲸唱歌呢","why":"更自然"}]}"""
                }
                CrewRole.REVIEWER -> if (system.contains("日记"))
                    """{"diary":"今天和大家聊了座头鲸的歌，鲸鱼饲养员又来了。","highlights":["座头鲸的歌会变"],"mood":"warm","goals_done":[],"new_goals":[{"text":"下次讲讲虎鲸的方言","horizon":"short"}],
                       "plan":{"topics":["虎鲸方言","深海发光生物"],"segments":[{"kind":"opening","minutes":3},{"kind":"learn","minutes":12},{"kind":"game","minutes":5},{"kind":"recap","minutes":3}],"why":"今天鲸鱼话题最热"}}"""
                else """{"worked":"讲谈资时弹幕多","didnt":"","lesson":"讲具体细节时大家更爱接话","interest_adjust":{"鲸鱼":3},"new_goal":"","goal_progress":{"g-short-1":40},"note":"鲸鱼话题效果好"}"""
            }
        }
    }

    class FakeHost : CrewHost {
        var now = 0L
        val spoken = mutableListOf<Line>()
        val streamed = mutableListOf<Line>()
        val browsed = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var polls = 0; var segments = 0; var remembered = 0; var ended = false
        var takeover = false
        var inbox = mutableListOf(
            InboxItem("c1", "comment", "芝士雪豹", "sim:1", "鱼鱼今天在看啥呀", priority = 32),
            InboxItem("c2", "comment", "鲸鱼饲养员", "sim:3", "昨天说的鲸鱼后来怎么样了", level = "常客", streams = 3, priority = 44),
            InboxItem("c3", "comment", "喷子007", "sim:7", "", hidden = true, priority = 14),
        )
        var page: PageInfo? = null
        override fun observe() = Observation(now, (now / 60_000).toInt(), "主题冲浪", "surf", 300, "鲸鱼", inbox.toList(), page, 5,
            takeover = takeover, allowedHosts = listOf("zh.wikipedia.org"), persona = "你是大肥鱼，一条爱冲浪的鲸鱼。", canEnd = now > 600_000, endHint = "到时长了")
        override suspend fun speak(line: Line): String { spoken += line; return if (takeover) "已放进待审队列" else "说了" }
        override suspend fun speakStreaming(line: Line): String { streamed += line; return "现场说了" }
        override suspend fun browse(query: String, url: String, site: String): String {
            browsed += query.ifBlank { url }
            page = PageInfo("座头鲸 - 维基百科", "https://zh.wikipedia.org/wiki/座头鲸", "zh.wikipedia.org",
                "座头鲸是一种大型鲸类。雄性座头鲸会唱很长的歌。歌声能传播很远的距离。", listOf("鲸歌", "须鲸"), 0, 3000)
            return "打开了「座头鲸」"
        }
        override suspend fun readPage(): String { page = page?.copy(readOffset = page!!.readOffset + 1200); return "往下读了" }
        override suspend fun scroll(down: Boolean) = "滚动了"
        override suspend fun nextSegment(): String { segments++; return "进入小游戏" }
        override fun setTopic(topic: String) = "话题换成 $topic"
        override fun startPoll(question: String, options: List<String>): String { polls++; return "投票开始" }
        override fun setExpression(expr: String) = "表情 $expr"
        override fun remember(viewerKey: String, nick: String, fact: String): String { remembered++; return "记住了" }
        override suspend fun rest(seconds: Int) = "歇了"
        override suspend fun endStream(reason: String): String { ended = true; return "下播：$reason" }
        override fun markAnswered(id: String) { answered += id; inbox.removeAll { it.id == id } }
    }

    private fun crew(llm: CrewLlm, host: FakeHost, life: LifeStore, cfg: CrewConfig = CrewConfig.PRESETS.getValue(CrewConfig.DEFAULT_PRESET)) =
        FyCrew(llm, host, life, { cfg }, { host.now }).also { it.reset(0) }

    @Test fun scriptedMiniStreamFiresEveryRole() = runBlocking {
        val llm = FakeLlm(ArrayDeque(listOf(
            // tick 1: reply to the regular, an unsafe reply to c1, go research
            """{"thought":"饲养员是老观众，先回他；然后去查座头鲸","goal":"把座头鲸的歌讲明白","actions":[
                {"tool":"reply","args":{"commentId":"c2","about":"回老观众，说在看鲸鱼"}},
                {"tool":"reply","args":{"commentId":"c1","about":"回答在看什么"}},
                {"tool":"browse","args":{"query":"座头鲸 歌声","site":"wiki"}}]}""",
            // tick 2: one bad tool, a note from the page, a poll, remember a regular
            """{"thought":"页面说歌能传很远，记下来，再让大家投票","goal":"让大家参与","actions":[
                {"tool":"fly_away","args":{}},
                {"tool":"note_to_self","args":{"text":"座头鲸的歌声能传播很远"}},
                {"tool":"start_poll","args":{"question":"接下来看什么","options":["虎鲸","蓝鲸"]}},
                {"tool":"remember","args":{"viewer":"鲸鱼饲养员","fact":"喜欢鲸鱼"}}]}""",
            // tick 3: segment over → next segment + a line
            """{"thought":"环节差不多了，换小游戏","goal":"热闹一下","actions":[{"tool":"next_segment","args":{}},{"tool":"say","args":{"about":"宣布进入小游戏"}}]}""",
        )))
        val host = FakeHost()
        val life = MemLifeStore(FyLifeLogic.seed(LifeState(), listOf("鲸鱼" to 60, "游戏" to 40), 0))
        val c = crew(llm, host, life)
        c.segmentStarted("主题冲浪", "surf", 0)

        // tick 1
        host.now = 60_000
        repeat(5) { c.noteEvent("comment") }; c.noteEvent("gift")
        val t1 = c.tick()
        assertTrue(t1 is TickOutcome.Ran)
        assertEquals(listOf("c2", "c1"), host.answered)
        assertEquals(2, host.spoken.size)
        assertEquals("鲸鱼饲养员你好～我在看座头鲸唱歌呢", host.spoken[0].text)   // 安全审核 rewrite applied
        assertEquals(DfySafety.DEFLECT, host.spoken[1].text)                      // rule filter replaced the unsafe line
        assertEquals(listOf("座头鲸 歌声"), host.browsed)
        assertEquals("饲养员是老观众，先回他；然后去查座头鲸", c.ui.value.thought)
        assertEquals("把座头鲸的歌讲明白", c.ui.value.goal)
        assertTrue(life.get().relations.any { it.nick == "鲸鱼饲养员" })              // 运营 spotted the regular

        // tick 2: researcher reads the new page; invalid tool is rejected, others run
        host.now = 120_000
        repeat(3) { c.noteEvent("comment") }
        val t2 = c.tick() as TickOutcome.Ran
        assertEquals(3, t2.actions)
        assertTrue(c.ui.value.plan.any { it.startsWith("✗") && it.contains("fly_away") })
        assertEquals(1, host.polls)
        assertEquals(1, host.remembered)
        assertTrue(life.get().skills.any { it.text.contains("传播很远") && it.url.contains("wikipedia") })
        assertTrue(c.ui.value.notes.getValue("researcher").text.contains("谈资"))

        // tick 3
        host.now = 180_000
        c.tick()
        assertEquals(1, host.segments)
        val reflect = c.reflectSegment("小游戏", "game")
        assertTrue(reflect.contains("鲸鱼"))
        assertTrue(life.get().lessons.isNotEmpty())
        assertEquals(40, life.get().goals.first { it.id == "g-short-1" }.progress)

        // stream end → diary + next plan
        host.now = 240_000
        val d = c.endOfStream(StreamLog("s1", 4, listOf("鲸鱼"), listOf("座头鲸 - 维基百科"), listOf("鲸鱼饲养员 → 我：你好"), 3,
            listOf(Triple("sim:3", "鲸鱼饲养员", 3)), 1, listOf("主题冲浪", "小游戏")), emptyList())
        assertTrue(d.text.contains("座头鲸"))
        val lf = life.get()
        assertEquals(1, lf.diary.size); assertEquals(1, lf.streams)
        assertEquals(listOf("虎鲸方言", "深海发光生物"), lf.nextPlan!!.topics)
        assertTrue(lf.goals.any { it.text.contains("虎鲸") })

        // every expert fired through the model at least once, and noted something
        for (r in CrewRole.values()) {
            assertTrue("${r.label} fired", (llm.calls[r] ?: 0) >= 1)
            val n = c.ui.value.notes.getValue(r.id)
            assertTrue("${r.label} note", n.text.isNotBlank() && n.calls >= 1)
        }
        assertTrue(c.ui.value.tokensHour > 0)
        assertTrue(llm.models.all { it.endsWith(":") })              // default: all experts on the same model
    }

    @Test fun expertsSwitchedOffFallBackToRules() = runBlocking {
        val llm = FakeLlm()
        val host = FakeHost()
        val cfg = CrewConfig(enabled = CrewRole.values().associate { it.id to false }, models = mapOf("writer" to "deepseek"))
        val life = MemLifeStore()
        val c = crew(llm, host, life, cfg)
        val r = c.tick()
        assertTrue(r is TickOutcome.Ran)
        assertTrue(llm.calls.isEmpty())                               // no model at all
        assertEquals(listOf("c2"), host.answered)                     // rules: highest priority first
        assertEquals(1, host.streamed.size)                           // no 编剧: the host streams it in persona
        assertTrue(c.ui.value.notes.values.filter { it.role in setOf("ops", "director", "writer") }.all { it.viaRules })
        c.endOfStream(StreamLog("s2", 1, listOf("鲸鱼"), emptyList(), emptyList(), 1), emptyList())
        assertEquals(1, life.get().diary.size)                       // deterministic diary
        assertNotNull(life.get().nextPlan)
    }

    @Test fun brokenDirectorFallsBackThenDegrades() = runBlocking {
        val llm = FakeLlm(ArrayDeque(listOf("嗯…", "我也不知道", "{坏掉的")))
        val host = FakeHost()
        val c = crew(llm, host, MemLifeStore())
        repeat(3) { assertTrue(c.tick() is TickOutcome.Fallback) }
        val calls = llm.calls[CrewRole.DIRECTOR]
        assertTrue(c.tick() is TickOutcome.Fallback)                  // degraded: no new director call
        assertEquals(calls, llm.calls[CrewRole.DIRECTOR])
        host.now = 200_000                                            // after the 2-minute cool-down
        assertTrue(c.tick() is TickOutcome.Ran)
    }

    @Test fun budgetCapStopsThinking() = runBlocking {
        val llm = FakeLlm()
        val host = FakeHost()
        val c = crew(llm, host, MemLifeStore(), CrewConfig(tokensPerHour = 5_000))
        var budget = false
        for (i in 0 until 40) { if (c.tick() == TickOutcome.Budget) { budget = true; break }; host.inbox.add(InboxItem("x$i", "comment", "甲", "k", "鱼鱼在吗")) }
        assertTrue(budget)
        assertTrue(c.ui.value.status.contains("预算"))
    }

    @Test fun takeoverStillThinksAndQueuesLines() = runBlocking {
        val llm = FakeLlm()
        val host = FakeHost().apply { takeover = true }
        val c = crew(llm, host, MemLifeStore())
        c.tick()
        assertTrue(c.ui.value.thought.isNotBlank())
        assertTrue(c.ui.value.results.any { it.contains("待审") })
    }

    @Test fun ruleFloorCatchesHumanClaimsAndContacts() {
        val c = crew(FakeLlm(), FakeHost(), MemLifeStore())
        assertEquals("我是AI虚拟主播，不骗你～", c.ruleCheck("我是真人，不骗你～").first)
        assertEquals(DfySafety.DEFLECT, c.ruleCheck("想聊的加微信 abc").first)
        assertFalse(c.ruleCheck("今天看鲸鱼").second != null)
    }
}
