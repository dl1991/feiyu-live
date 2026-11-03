package com.dl1991.feiyu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.dafeiyu.ContentSettings
import com.dl1991.feiyu.dafeiyu.DfyPersona
import com.dl1991.feiyu.dafeiyu.DfyStore
import com.dl1991.feiyu.dafeiyu.FyContentStore
import com.dl1991.feiyu.ui.FyButton
import com.dl1991.feiyu.ui.FyCard
import com.dl1991.feiyu.ui.FyFootnote
import com.dl1991.feiyu.ui.FyScaffold
import com.dl1991.feiyu.ui.FySectionHeader
import com.dl1991.feiyu.ui.FySlider
import com.dl1991.feiyu.ui.FySpacer
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.GlassField
import kotlinx.coroutines.launch

/**
 * 人设: the persona prompt (stored in [DfyStore] settings, shared with chat) plus the live
 * personality knobs, catchphrases and forbidden words (stored in [FyContentStore], read every turn).
 */
@Composable
fun PersonaScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { DfyStore.ensureLoaded(ctx); FyContentStore.ensure(ctx) }
    val st by DfyStore.state.collectAsState()
    val c by FyContentStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val saved = st.settings.personaPrompt
    var persona by remember(saved) { mutableStateOf(saved.ifBlank { DfyPersona.default(ctx) }) }

    fun content(f: (ContentSettings) -> ContentSettings) = FyContentStore.update(ctx, f)
    fun savePersona(text: String) = scope.launch {
        DfyStore.update(ctx) { it.copy(settings = it.settings.copy(personaPrompt = text.take(12_000)).sanitized()) }
        msg = if (text.isBlank()) "已恢复默认人设" else "人设已保存"
    }

    FyScaffold("人设", back) {
        FySectionHeader("人设提示词")
        FyCard(padding = PaddingValues(12.dp)) {
            GlassField(persona, { persona = it }, "她是谁、怎么说话、喜欢什么", singleLine = false, minLines = 8)
            FySpacer(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FyButton("保存", { savePersona(persona) })
                FyButton("恢复默认", { persona = DfyPersona.default(ctx); savePersona("") }, tonal = true)
            }
            if (msg.isNotBlank()) { FySpacer(6.dp); Text(msg, fontSize = FyType.Secondary) }
        }
        FyFootnote("人设同时用于直播和私聊。留空 = 使用内置的大肥鱼人设。")

        FySectionHeader("性格")
        FyCard(padding = PaddingValues(12.dp)) {
            FySlider("活泼", c.lively, left = "安静内敛", right = "特别活泼") { v -> content { it.copy(lively = v) } }
            FySlider("毒舌", c.snark, left = "温柔", right = "爱吐槽") { v -> content { it.copy(snark = v) } }
            FySlider("好奇", c.curiosity, left = "按部就班", right = "什么都想点开") { v -> content { it.copy(curiosity = v) } }
            FySlider("耐心", c.patience, left = "会小抱怨", right = "反复解释也不烦") { v -> content { it.copy(patience = v) } }
            FySlider("情绪表达", c.emotionIntensity, left = "淡定", right = "情绪外放") { v -> content { it.copy(emotionIntensity = v) } }
        }
        FyFootnote("直播中修改也会在下一句生效。")

        FySectionHeader("口头禅")
        FyCard(padding = PaddingValues(12.dp)) {
            TagsField("用逗号或顿号分隔，最多 8 个", c.catchphrases, placeholder = "咕噜咕噜、鱼鱼我呀") { l -> content { it.copy(catchphrases = l) } }
        }
        FyFootnote("她会偶尔自然地说，不会每句都说。")

        FySectionHeader("禁用词")
        FyCard(padding = PaddingValues(12.dp)) {
            TagsField("她绝对不会说的词", c.forbidden, placeholder = "例如：家人们、老铁") { l -> content { it.copy(forbidden = l) } }
        }
        FyFootnote("禁用词会写进提示词，也会交给安全过滤。")
    }
}
