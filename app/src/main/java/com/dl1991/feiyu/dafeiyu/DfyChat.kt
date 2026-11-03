package com.dl1991.feiyu.dafeiyu

import android.content.Context
import com.dl1991.feiyu.llm.LLMMessage

/**
 * Owner ↔ 大肥鱼 chat. Persona + mood + what she still remembers + today's summary go into the
 * system prompt; the last turns go in as history. Each exchange becomes a private CHAT episode
 * (decays like everything else) and enters the reflection pool.
 */
object DfyChat {
    suspend fun send(ctx: Context, text: String, from: String = "owner"): String {
        val msg = clean(text, 2000)
        if (msg.isBlank()) return ""
        val now = System.currentTimeMillis()
        val st = DfyStore.update(ctx) { it.copy(chat = it.chat + ChatTurn(now, "user", msg)) }
        val history = st.chat.takeLast(16).map {
            LLMMessage(role = if (it.role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT, content = it.text)
        }
        val reply = try {
            DfyLlm.ask(ctx, DfyPersona.chatSystem(ctx, st, msg, now), history, 700, 0.85, st.settings.preferFreeModels, "dafeiyu-chat")
        } catch (e: DfyLlm.NoModel) {
            "（鱼鱼现在说不了话：${e.message}）"
        } catch (e: Exception) {
            "（鱼鱼走神了：${clean(e.message, 80)}）"
        }
        val at = System.currentTimeMillis()
        val failed = reply.startsWith("（鱼鱼")
        DfyStore.update(ctx) { s ->
            val ep = DfyMemory.newEpisode(EpKind.CHAT, at,
                (if (from == "agent") "主人问我" else "主人和我聊天，说") + "：" + clean(msg, 80) + "；我回答：" + clean(reply, 80),
                "我和主人聊过一会儿天", "我隐约记得和主人说过话", visibility = "private")
            s.copy(
                chat = s.chat + ChatTurn(at, "assistant", reply),
                episodes = if (failed) s.episodes else s.episodes + ep,
                pending = if (failed) s.pending else s.pending + PendingItem("p-" + ep.id, ep.id, at),
            )
        }
        return reply
    }

    suspend fun clear(ctx: Context) { DfyStore.update(ctx) { it.copy(chat = emptyList()) } }
}
