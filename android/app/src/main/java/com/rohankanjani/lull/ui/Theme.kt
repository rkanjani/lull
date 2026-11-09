package com.rohankanjani.lull.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.title
import kotlin.math.roundToInt
import androidx.compose.material3.ColorScheme

/** Lull's Material colours: always dark, white on near-black. */
val Theme.colors: ColorScheme
    get() = darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        background = background,
        onBackground = Color.White,
        surface = surface,
        onSurface = Color.White,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerLow = surface,
        onSurfaceVariant = secondary,
        error = red,
    )

@Composable
fun LullTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Theme.colors, content = content)
}

/** Small all-caps label used above groups of content. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Theme.secondary) {
    Text(
        text.uppercase(),
        // Read as written rather than in capitals, which TalkBack can spell out letter by letter.
        modifier = modifier.semantics { contentDescription = text },
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = color,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.surface)
            .border(BorderStroke(1.dp, Theme.hairline), shape)
            .padding(padding),
    ) { content() }
}

/** The capsule button used for the main action on a screen. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
    enabled: Boolean = true,
    label: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (prominent) Color.White else Theme.raised)
            .border(BorderStroke(1.dp, if (prominent) Color.Transparent else Theme.hairline), CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (label != null) contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (prominent) Color.Black else Color.White,
        ) {
            androidx.compose.material3.ProvideTextStyle(TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)) {
                content()
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = true, enabled: Boolean = true) {
    PrimaryButton(onClick, modifier, prominent, enabled) { Text(text) }
}

/** A small capsule, like the Use Tonight and connection buttons. */
@Composable
fun Pill(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(Theme.raised)
            .border(BorderStroke(1.dp, Theme.hairline), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A slider with a tinted track that snaps to whole steps and shows its value inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 1f,
    tint: Color = Color.White,
    enabled: Boolean = true,
    /** What TalkBack reads for the value, when the shown text abbreviates it. */
    spokenValue: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    // One TalkBack element for the title, value and track together, so touching any of them
    // finds the slider, and swiping up or down adjusts it.
    Column(
        Modifier.alpha(if (enabled) 1f else 0.4f).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ValueHeader(title, valueText, Modifier.clearAndSetSemantics {})
        Slider(
            value = value.coerceIn(range),
            onValueChange = { new ->
                val snapped = (range.start + ((new - range.start) / step).roundToInt() * step).coerceIn(range)
                if (snapped != value) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onValueChange(snapped)
                }
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            // Drawn like the iOS app's slider: a thin tinted track and a white capsule thumb.
            thumb = {
                Box(
                    Modifier
                        .size(width = 38.dp, height = 24.dp)
                        .shadow(3.dp, CircleShape)
                        .background(Color.White, CircleShape),
                )
            },
            track = { state ->
                val fraction = (state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(tint))
                }
            },
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = spokenValue ?: valueText
            },
        )
    }
}

/** A setting's name with its value at the end of the line, stacked instead when large text won't fit on one. */
@Composable
fun ValueHeader(title: String, value: String, modifier: Modifier = Modifier) {
    val name = @Composable { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.secondary) }
    val reading = @Composable { Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = Theme.tabular) }
    if (isLargeText()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            name()
            reading()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.Bottom) {
            name()
            Spacer(Modifier.weight(1f))
            reading()
        }
    }
}

/**
 * Mode buttons, three to a row. Large text gets a column of full-width buttons with the icon beside the name.
 * [hint] says what choosing a mode other than the selected one also does.
 */
@Composable
fun ModePicker(modes: List<Mode>, selection: Mode?, hint: String? = null, onSelect: (Mode) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val large = isLargeText()
    val perRow = if (large) 1 else 3
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { mode ->
                    val isSelected = mode == selection
                    val shape = RoundedCornerShape(18.dp)
                    val color = if (isSelected) Color.Black else mode.tint
                    val button = Modifier
                        .weight(1f)
                        .heightIn(min = 62.dp)
                        .clip(shape)
                        .background(if (isSelected) mode.tint else Theme.raised)
                        .clickable(role = Role.Button, onClickLabel = if (isSelected) null else hint) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(mode)
                        }
                        .semantics(mergeDescendants = true) { selected = isSelected }
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                    if (large) {
                        Row(
                            button.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(mode.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                            Text(mode.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                        }
                    } else {
                        Column(
                            button,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                        ) {
                            Icon(mode.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                            // The name wraps rather than truncating.
                            Text(mode.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center)
                        }
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Horizontal gap helper for rows of text and icons. */
@Composable
fun Gap(width: Dp) = Spacer(Modifier.width(width))
