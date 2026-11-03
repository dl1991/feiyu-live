package com.dl1991.feiyu.dafeiyu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool-call parsing and validation for the 大肥鱼 agent (pure JVM). */
class FyAgentToolsTest {
    @Test fun parsesStandardShapeWithThoughtGoalAndActions() {
        val r = AgentTool.parse("""好的：```json
{"thought":"芝士雪豹在问我在看什么，先回他","goal":"把鲸鱼歌声讲明白","actions":[
 {"tool":"reply","args":{"commentId":"c3","about":"告诉他在看鲸鱼","emotion":"开心"}},
 {"tool":"browse","args":{"query":"座头鲸 歌声","site":"WIKI"}}]}
```""")
        assertTrue(r.parsed)
        assertEquals("芝士雪豹在问我在看什么，先回他", r.thought)
        assertEquals("把鲸鱼歌声讲明白", r.goal)
        assertEquals(listOf("reply", "browse"), r.actions.map { it.name })
        assertEquals("happy", r.actions[0].str("emotion"))          // Chinese emotion normalised
        assertEquals("wiki", r.actions[1].str("site"))              // site lower-cased
        assertTrue(r.errors.isEmpty())
    }

    @Test fun parsesOpenAiToolCallsWithStringArguments() {
        val r = AgentTool.parse("""{"tool_calls":[{"type":"function","function":{"name":"say","arguments":"{\"text\":\"大家好呀\"}"}}]}""")
        assertEquals(1, r.actions.size)
        assertEquals("大家好呀", r.actions[0].str("text"))
    }

    @Test fun parsesFlattenedSingleCallAndThinkBlocks() {
        val r = AgentTool.parse("<think>{\"x\":1} 嗯</think>{\"tool\":\"set_topic\",\"topic\":\"深海发光生物\"}")
        assertEquals("set_topic", r.actions.single().name)
        assertEquals("深海发光生物", r.actions.single().str("topic"))
    }

    @Test fun garbageIsNotParsed() {
        val r = AgentTool.parse("我想先跟大家打个招呼")
        assertFalse(r.parsed)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun unknownToolsAndMissingArgsAreRejected() {
        val r = AgentTool.parse("""{"actions":[{"tool":"hack_server","args":{}},{"tool":"reply","args":{"text":"嗨"}},
            {"tool":"say","args":{}},{"tool":"start_poll","args":{"question":"看啥","options":["游戏"]}},{"tool":"say","args":{"text":"好"}}]}""")
        assertEquals(listOf("say"), r.actions.map { it.name })
        assertEquals(4, r.errors.size)
        assertTrue(r.errors.any { it.contains("未知工具") })
        assertTrue(r.errors.any { it.contains("commentId") })
        assertTrue(r.errors.any { it.contains("2 个选项") })
    }

    @Test fun clampsLengthsAndActionCount() {
        val long = "啊".repeat(500)
        val r = AgentTool.parse("""{"actions":[{"tool":"say","args":{"text":"$long"}},{"tool":"rest","args":{"seconds":999}},
            {"tool":"scroll","args":{"direction":"sideways"}},{"tool":"read_page","args":{}}]}""")
        assertEquals(3, r.actions.size)
        assertEquals(120, r.actions[0].str("text").length)
        assertEquals(30, r.actions[1].int("seconds", 0))
        assertEquals("down", r.actions[2].str("direction"))
        assertTrue(r.errors.any { it.contains("超过") })
    }

    @Test fun pollOptionsAcceptPipeString() {
        val (c, e) = AgentTool.shape("start_poll", org.json.JSONObject("""{"question":"看什么","options":"游戏|科技|鲸鱼|美食|旅行"}"""))
        assertNull(e)
        assertEquals(listOf("游戏", "科技", "鲸鱼", "美食"), c!!.list("options"))
    }

    @Test fun contextValidation() {
        val ctx = ToolContext(commentIds = setOf("c1"), hasPage = false, canEnd = false, pollOpen = false, allowedHosts = listOf("zh.wikipedia.org", "bilibili.com"))
        fun one(json: String) = AgentTool.parse(json).actions.single()
        assertNull(AgentTool.check(one("""{"tool":"reply","args":{"commentId":"c1","text":"嗨"}}"""), ctx))
        assertNotNull(AgentTool.check(one("""{"tool":"reply","args":{"commentId":"c9","text":"嗨"}}"""), ctx))
        assertNull(AgentTool.check(one("""{"tool":"browse","args":{"url":"https://zh.wikipedia.org/wiki/鲸"}}"""), ctx))
        assertNull(AgentTool.check(one("""{"tool":"browse","args":{"url":"https://www.bilibili.com/video/1"}}"""), ctx))
        assertNotNull(AgentTool.check(one("""{"tool":"browse","args":{"url":"https://evil.example.com/"}}"""), ctx))
        assertNotNull(AgentTool.check(one("""{"tool":"browse","args":{"url":"#2"}}"""), ctx))     // no page yet
        assertNull(AgentTool.check(one("""{"tool":"browse","args":{"url":"#2"}}"""), ctx.copy(hasPage = true)))
        assertNotNull(AgentTool.check(one("""{"tool":"read_page","args":{}}"""), ctx))
        assertNotNull(AgentTool.check(one("""{"tool":"end_stream","args":{"reason":"累了"}}"""), ctx))
        assertNull(AgentTool.check(one("""{"tool":"end_stream","args":{"reason":"到点"}}"""), ctx.copy(canEnd = true)))
    }

    @Test fun onlyOnePollPerPlan() {
        val r = AgentTool.parse("""{"actions":[{"tool":"start_poll","args":{"question":"A","options":["1","2"]}},{"tool":"start_poll","args":{"question":"B","options":["1","2"]}}]}""")
        val (ok, errs) = AgentTool.validate(r.actions, ToolContext())
        assertEquals(1, ok.size); assertEquals(1, errs.size)
    }

    @Test fun badUrlsAndExpressions() {
        val r = AgentTool.parse("""{"actions":[{"tool":"browse","args":{"url":"javascript:alert(1)"}},{"tool":"set_expression","args":{"expression":"evil"}},{"tool":"set_expression","args":{"expression":"害羞"}}]}""")
        assertEquals(listOf("set_expression"), r.actions.map { it.name })
        assertEquals("shy", r.actions.single().str("expression"))
        assertEquals(2, r.errors.size)
    }

    @Test fun hostMatching() {
        assertTrue(AgentTool.hostAllowed("https://m.baike.baidu.com/item/x", listOf("baike.baidu.com")))
        assertFalse(AgentTool.hostAllowed("https://baike.baidu.com.evil.cn/", listOf("baike.baidu.com")))
        assertTrue(AgentTool.hostAllowed("https://anything.org/", emptyList()))
    }

    @Test fun lifeStateRoundTripsAndEngagementMovesWeights() {
        var s = FyLifeLogic.seed(LifeState(), listOf("鲸鱼" to 60, "游戏" to 40), 1000)
        assertEquals(3, s.goals.size)
        s = FyLifeLogic.engagement(s, "鲸鱼", 4.0, 1.0, 2000)
        s = FyLifeLogic.engagement(s, "游戏", 0.2, 1.0, 2000)
        assertTrue(s.interests.first { it.topic == "鲸鱼" }.weight > 60)
        assertTrue(s.interests.first { it.topic == "游戏" }.weight < 40)
        s = FyLifeLogic.addSkill(s, LifeSkill("座头鲸的歌会随年份变化", "鲸鱼", "维基", "https://zh.wikipedia.org/x", 3000))
        s = FyLifeLogic.relation(s, "sim:3", "鲸鱼饲养员", "喜欢虎鲸", 3000)
        val back = LifeState.from(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        assertTrue(FyLifeLogic.summary(back).contains("鲸鱼"))
    }
}
