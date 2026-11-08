package com.rohankanjani.lull.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.HistoryEntry
import com.rohankanjani.lull.Night
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.title
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the BedJet did each night, so the morning after you can see what you picked at 3 a.m. */
@Composable
fun HistoryScreen(model: AppModel) {
    val bedJet = LocalBedJet.current
    val expanded = remember { mutableStateListOf<String>() }
    Screen(model, "History") {
        val nights = bedJet.nights
        if (nights.isEmpty()) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No nights yet", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Every change to your BedJet shows up here with the time and room temperature, so in the morning you can see what you picked overnight.",
                        fontSize = 15.sp, color = Theme.secondary,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            nights.forEach { night ->
                NightRow(model, night, isExpanded = night.id in expanded) {
                    if (!expanded.remove(night.id)) expanded += night.id
                }
            }
        }
    }
}

private fun minuteOfDay(millis: Long): Int {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return time.hour * 60 + time.minute
}

private fun time(millis: Long): String = Format.clock(minuteOfDay(millis))

/** One night at a glance, opening up to everything that happened in it. */
@Composable
private fun NightRow(model: AppModel, night: Night, isExpanded: Boolean, toggle: () -> Unit) {
    val title = night.evening.format(
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")),
    )
    // The schedule that ran, or else when the first and last changes happened.
    fun summary(through: String): String = night.scheduleNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: run {
        val first = night.entries.firstOrNull()?.date
        val last = night.entries.lastOrNull()?.date
        if (first == null || last == null) "" else {
            val start = time(first)
            val end = time(last)
            if (start == end) start else "$start $through $end"
        }
    }
    // At the largest text sizes the curve moves under the text so the date and schedule keep the width.
    val large = isLargeText()
    val turn by animateFloatAsState(if (isExpanded) 180f else 0f, tween(350), label = "chevron")
    Card(padding = 18.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = toggle)
                    .testTag("Night")
                    .clearAndSetSemantics {
                        contentDescription = "$title, ${summary(through = "to")}"
                        stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                        role = Role.Button
                        onClick(label = if (isExpanded) "Hide this night" else "Show everything that happened this night") { toggle(); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(summary(through = "–"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, style = Theme.tabular)
                    if (large) NightCurve(night.phases, Modifier.fillMaxWidth().height(40.dp).padding(top = 6.dp), lineWidth = 2.dp)
                }
                if (!large) NightCurve(night.phases, Modifier.size(width = 88.dp, height = 40.dp), lineWidth = 2.dp)
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Theme.tertiary, modifier = Modifier.size(20.dp).rotate(turn))
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(350)) + expandVertically(tween(350)),
                exit = fadeOut(tween(350)) + shrinkVertically(tween(350)),
            ) {
                // Redrawn each minute, so a phase the schedule moves on to while History is open shows up.
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(60_000 - now % 60_000)
                        now = System.currentTimeMillis()
                    }
                }
                val timeline = night.timeline(until = now)
                Column(Modifier.padding(top = 20.dp)) {
                    timeline.forEachIndexed { index, entry ->
                        key(entry.id) {
                            EntryRow(model, entry, isLast = index == timeline.size - 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(model: AppModel, entry: HistoryEntry, isLast: Boolean) {
    val bedJet = LocalBedJet.current
    val event = entry.event
    // Parts are joined with a dot on screen; TalkBack gets commas and durations in words.
    fun air(mode: Mode, temperature: Int, fan: Int, spoken: Boolean): String {
        // A pause in a schedule blows no air.
        if (mode == Mode.WAIT) return mode.title
        val parts = if (bedJet.adjustsTemperature(mode)) {
            listOf(mode.title, model.degrees(temperature), "fan $fan%")
        } else {
            listOf(mode.title, "fan $fan%")
        }
        return parts.joinToString(if (spoken) ", " else " · ")
    }
    fun title(spoken: Boolean): String = when (event) {
        is HistoryEntry.Event.Air -> air(event.mode, event.temperature, event.fanPercent, spoken)
        is HistoryEntry.Event.PhaseStarted -> air(event.phase.mode, event.phase.temperature, event.phase.fanPercent, spoken)
        is HistoryEntry.Event.Off -> "Turned off"
        is HistoryEntry.Event.ScheduleStarted -> "Started ${event.name ?: "a schedule"}"
        is HistoryEntry.Event.ScheduleEnded -> {
            val name = event.name ?: "Schedule"
            when (val ending = event.ending) {
                HistoryEntry.Ending.ByYou -> "Ended $name"
                HistoryEntry.Ending.Finished -> "$name finished"
                is HistoryEntry.Ending.Early ->
                    "$name stopped ${if (spoken) Format.spokenDuration(ending.minutes) else Format.duration(ending.minutes)} early"
            }
        }
    }
    fun detail(spoken: Boolean): String? = buildList {
        if (event is HistoryEntry.Event.PhaseStarted) add("Phase ${event.number} of ${event.of}")
        if (event is HistoryEntry.Event.Off && event.timerRanOut) add("Timer ran out")
        entry.room?.let { add("Room ${model.degrees(it)}") }
        if (entry.noticedLater) add("Seen when Lull reopened")
    }.takeIf { it.isNotEmpty() }?.joinToString(if (spoken) ", " else " · ")
    val detail = detail(spoken = false)
    // At the largest text sizes the time sits above what happened instead of in its own column.
    val large = isLargeText()
    val badge = 28.dp * LocalDensity.current.fontScale.coerceAtMost(1.6f)
    val icon: ImageVector = when {
        event is HistoryEntry.Event.Air -> event.mode.icon
        event is HistoryEntry.Event.PhaseStarted -> event.phase.mode.icon
        event is HistoryEntry.Event.Off -> Icons.Filled.PowerSettingsNew
        event is HistoryEntry.Event.ScheduleStarted -> Icons.Filled.NightsStay
        event is HistoryEntry.Event.ScheduleEnded && event.ending is HistoryEntry.Ending.Early -> Icons.Filled.PriorityHigh
        else -> Icons.Filled.WbTwilight
    }
    val tint: Color = when {
        event is HistoryEntry.Event.Air -> event.mode.tint
        event is HistoryEntry.Event.PhaseStarted -> event.phase.mode.tint
        event is HistoryEntry.Event.ScheduleEnded && event.ending is HistoryEntry.Ending.Early -> Theme.warm
        event is HistoryEntry.Event.Off -> Theme.neutral
        else -> Color.White
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(time(entry.date), title(spoken = true), detail(spoken = true)).joinToString(", ")
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!large) {
            Text(
                time(entry.date), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, style = Theme.tabular,
                textAlign = TextAlign.End, modifier = Modifier.width(64.dp * LocalDensity.current.fontScale.coerceAtMost(1.6f)).padding(top = 6.dp),
            )
        }
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(badge).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            }
            // Joins the entries into one line through the night.
            if (!isLast) Box(Modifier.width(1.dp).weight(1f).heightIn(min = 14.dp).background(Theme.hairline))
        }
        Column(
            Modifier.weight(1f).padding(top = 4.dp, bottom = if (isLast) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (large) Text(time(entry.date), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, style = Theme.tabular)
            Text(title(spoken = false), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, fontSize = 13.sp, color = Theme.secondary)
        }
        Spacer(Modifier.width(0.dp))
    }
}
