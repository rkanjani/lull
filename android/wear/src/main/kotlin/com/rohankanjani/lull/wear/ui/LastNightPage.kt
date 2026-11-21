package com.rohankanjani.lull.wear.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.HistoryEntry
import com.rohankanjani.lull.ui.NightCurve
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.wear.WatchModel
import com.rohankanjani.lull.widgets.clock
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the BedJet ran last night, and the room temperature through it, as the Last Night widget shows. */
@Composable
fun LastNightPage(model: WatchModel, now: Long) {
    val shown = model.shown
    val night = shown.lastNight(now)
    val start = night?.entries?.firstOrNull()?.date
    val end = night?.entries?.lastOrNull()?.date
    if (night == null || start == null || end == null) {
        EmptyMessage(Icons.Filled.History, "No nights yet", "Nights with your BedJet show up here the morning after.")
        return
    }
    val title = night.evening.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")))
    val names = night.scheduleNames.joinToString(", ")
    val rooms = night.entries.mapNotNull { it.room }
    val room = rooms.minOrNull()?.let { low ->
        val high = rooms.max()
        if (low == high) shown.degrees(low) else "${Format.degrees(low, shown.useFahrenheit)}–${shown.degrees(high)}"
    }
    val changes = night.entries.count { it.event is HistoryEntry.Event.Air }
    val spoken = listOfNotNull(
        "Last night, $title",
        names.ifEmpty { null },
        "${clock(start)} to ${clock(end)}",
        room?.let { "room ${it.replace("–", " to ")}" },
        if (changes > 0) "$changes ${if (changes == 1) "change" else "changes"}" else null,
    ).joinToString(", ")

    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (names.isNotEmpty()) Text(names, color = Theme.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center)
        }
        NightCurve(night.phases, Modifier.fillMaxWidth().height(44.dp), lineWidth = 2.5.dp)
        // Start, length and end of the night along one line, as under the curve in the widget.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(clock(start), moon = true)
            Spacer(Modifier.weight(1f))
            Text(Format.duration(((end - start) / 60_000).toInt()), color = Theme.secondary, fontSize = 11.sp, style = Theme.tabular)
            Spacer(Modifier.weight(1f))
            TimeLabel(clock(end), moon = false)
        }
        // Centred, clear of the round screen's edge, which this line sits near.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (room != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Thermostat, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(room, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = Theme.tabular)
                }
            }
            if (changes > 0) Text("$changes ${if (changes == 1) "change" else "changes"}", color = Theme.secondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TimeLabel(text: String, moon: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(if (moon) Icons.Filled.Bedtime else Icons.Filled.WbSunny, contentDescription = null, tint = Theme.secondary, modifier = Modifier.size(9.dp))
        Text(text, color = Theme.secondary, fontSize = 11.sp, style = Theme.tabular)
    }
}
