package com.rohankanjani.lull.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.Schedule

/** Curve with bedtime and wake labels underneath. */
@Composable
fun NightTimeline(
    model: AppModel,
    schedule: Schedule,
    startMinute: Int? = null,
    progress: Double? = null,
    selection: String? = null,
    height: Dp = 150.dp,
) {
    val start = startMinute ?: schedule.bedtime
    val end = start + schedule.totalMinutes
    // Each phase in order, and how far through the night it is while running.
    val spokenPhases = buildList {
        progress?.let { add("${(it * 100).roundToInt()}% done") }
        schedule.phases.forEachIndexed { index, phase -> add("Phase ${index + 1}, ${model.spokenSummary(phase)}") }
    }.joinToString(". ")
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "Night timeline, ${Format.clock(start)} to ${Format.clock(end)}, ${Format.spokenDuration(schedule.totalMinutes)}"
            stateDescription = spokenPhases
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NightCurve(schedule.phases, Modifier.fillMaxWidth().height(height), progress, selection)
        // Bedtime, length and wake across one line, stacked at the largest text sizes.
        if (isLargeText()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeLabel(Format.clock(start), moon = true)
                TimeLabel(Format.clock(end), moon = false)
                Text(Format.duration(schedule.totalMinutes), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, style = Theme.tabular)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(Format.clock(start), moon = true)
                Spacer(Modifier.weight(1f))
                Text(Format.duration(schedule.totalMinutes), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, style = Theme.tabular)
                Spacer(Modifier.weight(1f))
                TimeLabel(Format.clock(end), moon = false)
            }
        }
    }
}

@Composable
private fun TimeLabel(text: String, moon: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(if (moon) Icons.Filled.Bedtime else Icons.Filled.WbSunny, null, tint = Theme.secondary, modifier = Modifier.size(11.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, style = Theme.tabular)
    }
}
