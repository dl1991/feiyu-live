package com.dl1991.feiyu.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The app is Chinese-first: every screen shows the Chinese string. [en] is kept for a later English pass. */
@Composable
@ReadOnlyComposable
@Suppress("UNUSED_PARAMETER")
fun t(zh: String, en: String): String = zh

@Suppress("UNUSED_PARAMETER")
fun tc(context: Context, zh: String, en: String): String = zh

object FyType {
    val PageHeader: TextUnit = 22.sp
    val Title: TextUnit = 17.sp
    val TitleSmall: TextUnit = 15.sp
    val Body: TextUnit = 14.sp
    val BodyLine: TextUnit = 20.sp
    val Secondary: TextUnit = 12.5.sp
    val SecondaryLine: TextUnit = 17.sp
    val Caption: TextUnit = 11.sp
    val Footnote: TextUnit = 11.5.sp
    val Section: TextUnit = 11.5.sp
    val Button: TextUnit = 14.sp
    val Mono: FontFamily = FontFamily.Monospace
}

object FySpace {
    val XS: Dp = 4.dp
    val S: Dp = 8.dp
    val M: Dp = 12.dp
    val L: Dp = 16.dp
    val XL: Dp = 20.dp
    val Screen: Dp = 12.dp
    val RowMin: Dp = 48.dp
    val TopBar: Dp = 52.dp
    val ButtonMin: Dp = 38.dp
}

object FyTokens {
    val CardShape = RoundedCornerShape(6.dp)
    val RowShape = RoundedCornerShape(4.dp)
    val ScreenPadding: Dp = FySpace.Screen

    @Composable @ReadOnlyComposable fun hairline(): Color = MaterialTheme.colorScheme.outline
    @Composable @ReadOnlyComposable fun card(): Color = MaterialTheme.colorScheme.surface
    @Composable @ReadOnlyComposable fun tile(): Color = MaterialTheme.colorScheme.surfaceVariant
    @Composable @ReadOnlyComposable fun field(): Color = MaterialTheme.colorScheme.surfaceVariant
    @Composable @ReadOnlyComposable fun tonal(): Color = MaterialTheme.colorScheme.surfaceVariant
    @Composable @ReadOnlyComposable fun accent(): Color = MaterialTheme.colorScheme.primary
    @Composable @ReadOnlyComposable fun selected(): Color = MaterialTheme.colorScheme.primary.copy(alpha = if (LocalFyDark.current) 0.20f else 0.10f)
    @Composable @ReadOnlyComposable fun okColor(): Color = if (LocalFyDark.current) FyPalette.OkDark else FyPalette.Ok
    @Composable @ReadOnlyComposable fun warnColor(): Color = if (LocalFyDark.current) FyPalette.WarnDark else FyPalette.Warn
    @Composable @ReadOnlyComposable fun errColor(): Color = if (LocalFyDark.current) FyPalette.ErrDark else FyPalette.Err
}

/** Page frame: concrete ground, left-aligned title bar with a hairline rule. */
@Composable
fun FyScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    bottomPadding: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        FyTopBar(title, onBack, actions)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = bottomPadding),
            content = content,
        )
    }
}

@Composable
fun FyTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(FySpace.TopBar).padding(horizontal = FySpace.M), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier.size(34.dp).clip(FyTokens.RowShape).border(1.dp, FyTokens.hairline(), FyTokens.RowShape)
                        .clickable(onClick = onBack).semantics { contentDescription = "返回" },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(10.dp))
            }
            Text(title, Modifier.weight(1f), fontSize = FyType.Title, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(thickness = 1.dp, color = FyTokens.hairline())
    }
}

/** Large page title for the tab roots: mono kicker + bold title. */
@Composable
fun FyPageTitle(title: String, kicker: String? = null, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = FySpace.L, end = FySpace.L, top = FySpace.L, bottom = FySpace.S)) {
        if (kicker != null) Text(kicker, fontFamily = FyType.Mono, fontSize = FyType.Caption, color = FyTokens.accent(), letterSpacing = 1.2.sp)
        Text(title, fontSize = FyType.PageHeader, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, fontSize = FyType.Secondary, lineHeight = FyType.SecondaryLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** White panel with a 1 dp rule and small radius. */
@Composable
fun FyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = FyTokens.ScreenPadding)
            .clip(FyTokens.CardShape)
            .background(FyTokens.card())
            .border(1.dp, FyTokens.hairline(), FyTokens.CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Mono section label with a short orange tick. */
@Composable
fun FySectionHeader(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = FySpace.Screen + 2.dp, end = FySpace.Screen + 2.dp, top = FySpace.L + 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(11.dp).background(FyTokens.accent()))
        Spacer(Modifier.width(6.dp))
        Text(text, Modifier.weight(1f), fontSize = FyType.Section, fontWeight = FontWeight.SemiBold, fontFamily = FyType.Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actionLabel != null && onAction != null) {
            Text("$actionLabel ›", Modifier.clickable(onClick = onAction), fontSize = FyType.Section, color = FyTokens.accent())
        }
    }
}

@Composable
fun FyRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = onClick != null,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = FySpace.RowMin)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = FySpace.M + 2.dp, vertical = FySpace.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(FySpace.M))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = FyType.Body, lineHeight = FyType.BodyLine, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, fontSize = FyType.Secondary, lineHeight = FyType.SecondaryLine, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) { Spacer(Modifier.width(FySpace.S)); trailing() }
            if (chevron) Text("›", Modifier.padding(start = 6.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showDivider) HorizontalDivider(Modifier.padding(start = FySpace.M + 2.dp), thickness = 1.dp, color = FyTokens.hairline())
    }
}

enum class ChipKind { OK, WARN, ERR, NEUTRAL, ACCENT }

@Composable
fun StatusChip(text: String, kind: ChipKind) {
    val c = when (kind) {
        ChipKind.OK -> FyTokens.okColor()
        ChipKind.WARN -> FyTokens.warnColor()
        ChipKind.ERR -> FyTokens.errColor()
        ChipKind.ACCENT -> FyTokens.accent()
        ChipKind.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            .background(c.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = FyType.Caption, fontWeight = FontWeight.SemiBold, fontFamily = FyType.Mono, color = c, maxLines = 1,
    )
}

/** Filled accent = the one main action; [tonal] = outlined secondary. */
@Composable
fun FyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tonal: Boolean = false) {
    val accent = FyTokens.accent()
    val bg = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f); tonal -> FyTokens.card(); else -> accent }
    val fg = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f); tonal -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.onPrimary }
    Box(
        modifier
            .heightIn(min = FySpace.ButtonMin)
            .clip(FyTokens.RowShape)
            .background(bg)
            .then(if (tonal && enabled) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), FyTokens.RowShape) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = FySpace.L, vertical = FySpace.S),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = FyType.Button, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun FyFootnote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = FySpace.Screen + 2.dp, end = FySpace.Screen + 2.dp, top = FySpace.S),
        fontSize = FyType.Footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp,
    )
}

@Composable
fun FySpacer(h: Dp = 12.dp) = Spacer(Modifier.height(h))

@Composable
fun FyFilterChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = FySpace.XS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { opt ->
            val sel = opt == selected
            Text(
                opt,
                modifier = Modifier
                    .clip(FyTokens.RowShape)
                    .background(if (sel) FyTokens.selected() else FyTokens.card())
                    .border(1.dp, if (sel) FyTokens.accent() else FyTokens.hairline(), FyTokens.RowShape)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = FySpace.M, vertical = 6.dp),
                color = if (sel) FyTokens.accent() else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = FyType.Secondary,
            )
        }
    }
}

@Composable
fun FySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = FyTokens.accent(), checkedBorderColor = FyTokens.accent(),
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant, uncheckedTrackColor = FyTokens.tile(),
            uncheckedBorderColor = FyTokens.hairline(),
        ),
    )
}

@Composable
fun CheckMark(on: Boolean, size: Dp = 20.dp, ringWhenOff: Boolean = false, color: Color = FyTokens.accent()) {
    if (!on && !ringWhenOff) return
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.size(size).clip(shape).then(
            if (on) Modifier.background(color) else Modifier.border(1.6.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), shape),
        ),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", color = Color.White, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Bold) }
}

/** Text field: label above, flat fill, accent rule on focus. */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    @Suppress("UNUSED_PARAMETER") autofill: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val shape = FyTokens.RowShape
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) Text(label, fontSize = FyType.Caption, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(shape).background(FyTokens.field())
                .border(if (focused) 1.5.dp else 1.dp, if (focused) FyTokens.accent() else FyTokens.hairline(), shape)
                .padding(horizontal = FySpace.M, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, fontSize = FyType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value, onValueChange = onValueChange, singleLine = singleLine, minLines = minLines,
                    interactionSource = interaction,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = FyType.Body, lineHeight = FyType.BodyLine),
                    cursorBrush = SolidColor(FyTokens.accent()),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (secret) {
                Spacer(Modifier.width(6.dp))
                Text(if (reveal) "隐藏" else "显示", Modifier.clip(CircleShape).clickable { reveal = !reveal }.padding(2.dp),
                    fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Labelled slider with the value printed in mono on the right. [left]/[right] name the two ends. */
@Composable
fun FySlider(
    label: String,
    value: Int,
    range: IntRange = 0..100,
    step: Int = 1,
    suffix: String = "",
    left: String? = null,
    right: String? = null,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = FyType.Body, color = MaterialTheme.colorScheme.onSurface)
            Text("$value$suffix", fontFamily = FyType.Mono, fontSize = FyType.Secondary, color = FyTokens.accent(), fontWeight = FontWeight.SemiBold)
        }
        val steps = ((range.last - range.first) / step.coerceAtLeast(1) - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange(((v - range.first) / step).roundToInt() * step + range.first) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (steps > 40) 0 else steps,
            colors = SliderDefaults.colors(thumbColor = FyTokens.accent(), activeTrackColor = FyTokens.accent(), inactiveTrackColor = FyTokens.tile(),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.height(30.dp),
        )
        if (left != null || right != null) Row {
            Text(left.orEmpty(), Modifier.weight(1f), fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(right.orEmpty(), fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Switch row used inside cards. */
@Composable
fun FyToggle(label: String, on: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = FyType.Body, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) Text(sub, fontSize = FyType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        FySwitch(on, if (enabled) onChange else null, enabled)
    }
}

/** Small mono stat tile ("在线 12"). */
@Composable
fun FyStat(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier.clip(FyTokens.RowShape).border(1.dp, FyTokens.hairline(), FyTokens.RowShape).background(FyTokens.card()).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontSize = FyType.Caption, fontFamily = FyType.Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = FyType.TitleSmall, fontWeight = FontWeight.Bold, color = if (accent) FyTokens.accent() else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
