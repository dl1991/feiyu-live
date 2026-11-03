package com.dl1991.feiyu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dl1991.feiyu.ui.FyTokens
import com.dl1991.feiyu.ui.FyType
import com.dl1991.feiyu.ui.GlassField

/** Comma / 、 separated list editor. Keeps the text as typed; commits the parsed list on each change. */
@Composable
fun TagsField(label: String, values: List<String>, placeholder: String? = null, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString("，")) }
    GlassField(text, { v -> text = v; onChange(v.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }) }, label, placeholder = placeholder, singleLine = false)
}

/** Small "−  12 分钟  +" stepper. */
@Composable
fun Stepper(value: Int, suffix: String, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBtn("−") { onChange((value - step).coerceAtLeast(min)) }
        Text("$value$suffix", Modifier.width(62.dp).padding(horizontal = 4.dp), fontFamily = FyType.Mono, fontSize = FyType.Secondary,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepBtn("+") { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
fun StepBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(FyTokens.RowShape).border(1.dp, FyTokens.hairline(), FyTokens.RowShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** ● status light + label (industrial indicator). */
@Composable
fun Indicator(on: Boolean?, label: String, value: String, modifier: Modifier = Modifier) {
    val c = when (on) { true -> FyTokens.okColor(); false -> FyTokens.errColor(); null -> FyTokens.warnColor() }
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(92.dp), fontSize = FyType.Secondary, fontFamily = FyType.Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontSize = FyType.Secondary, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    }
}

/** A small mono caption above a group inside a card. */
@Composable
fun GroupLabel(text: String) {
    Text(text, Modifier.padding(top = 8.dp, bottom = 2.dp), fontSize = FyType.Caption, fontFamily = FyType.Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Selectable tile used for layout presets (draws a tiny wireframe). */
@Composable
fun LayoutTile(label: String, kind: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val border = if (selected) FyTokens.accent() else FyTokens.hairline()
    Column(modifier.clip(FyTokens.RowShape).border(if (selected) 1.5.dp else 1.dp, border, FyTokens.RowShape).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        val acc = FyTokens.accent().copy(alpha = 0.75f)
        Column(Modifier.width(44.dp).height(72.dp).border(1.dp, ink, FyTokens.RowShape).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when (kind) {
                "web_big" -> { Box(Modifier.fillMaxWidth().height(36.dp).background(ink)); Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Box(Modifier.size(14.dp).background(acc)); Box(Modifier.weight(1f).height(14.dp).background(ink)) }; Box(Modifier.fillMaxWidth().height(6.dp).background(ink)) }
                "fish_big" -> { Box(Modifier.fillMaxWidth().height(30.dp).background(acc)); Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Box(Modifier.weight(1f).height(20.dp).background(ink)); Box(Modifier.weight(1f).height(20.dp).background(ink)) }; Box(Modifier.fillMaxWidth().height(6.dp).background(ink)) }
                else -> { Box(Modifier.fillMaxWidth().height(22.dp).background(acc)); Box(Modifier.fillMaxWidth().height(28.dp).background(ink)); Box(Modifier.fillMaxWidth().height(6.dp).background(ink)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = FyType.Caption, color = if (selected) FyTokens.accent() else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

val Transparent = Color.Transparent
