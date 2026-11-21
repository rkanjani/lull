package com.rohankanjani.lull.widgets

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.currentState
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import com.rohankanjani.lull.AppCommands
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.HistoryEntry
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.Night
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.ui.Theme
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the BedJet ran last night, and the room temperature through it. */
class LastNightWidget : GlanceAppWidget() {
    // The widget's real size, so the curve spans it; layouts switch at the sizes below.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshot.load(context) ?: WidgetSnapshot()
        WidgetUpdates.scheduleNext(context, snapshot)
        // The BedJet the widget is set to, drawn as the one on screen is.
        provideContent { LastNightContent(currentSnapshot().showing(currentState(PickedBedJet)), System.currentTimeMillis()) }
    }

    companion object {
        val SMALL = DpSize(120.dp, 120.dp)
        val MEDIUM = DpSize(250.dp, 120.dp)
    }
}

class LastNightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = LastNightWidget()
}

@Composable
fun LastNightContent(snapshot: WidgetSnapshot, now: Long) {
    val context = LocalContext.current
    Box(
        GlanceModifier
            .fillMaxSize()
            .widgetBackground()
            // Opens Lull on the BedJet the widget shows, which may not be the one on screen.
            .clickable(actionStartActivity(AppCommands.openIntent(context, LullTab.HISTORY, showing = snapshot.onScreen)))
            .padding(WidgetPadding),
    ) {
        val night = snapshot.lastNight(now)
        val start = night?.entries?.firstOrNull()?.date
        val end = night?.entries?.lastOrNull()?.date
        if (night != null && start != null && end != null) Content(snapshot, night, start, end) else Empty(snapshot)
    }
}

@Composable
private fun Content(snapshot: WidgetSnapshot, night: Night, start: Long, end: Long) {
    val title = night.evening.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")))
    val names = night.scheduleNames.joinToString(", ")
    val rooms = night.entries.mapNotNull { it.room }
    val room = if (rooms.isEmpty()) null else {
        val low = rooms.min()
        val high = rooms.max()
        if (low == high) snapshot.degrees(low) else "${Format.degrees(low, snapshot.useFahrenheit)}–${snapshot.degrees(high)}"
    }
    val changes = night.entries.count { it.event is HistoryEntry.Event.Air }
    val times = "${clock(start)} – ${clock(end)}"
    val spoken = snapshot.spoken(listOfNotNull(
        "Last night, $title",
        names.ifEmpty { null },
        "${clock(start)} to ${clock(end)}",
        room?.let { "room ${it.replace("–", " to ")}" },
        if (changes > 0) "$changes ${if (changes == 1) "change" else "changes"}" else null,
    ).joinToString(", "))
    val curveWidth = LocalSize.current.width - WidgetPadding * 2 + 16.dp
    val described = GlanceModifier.fillMaxSize().semantics { contentDescription = spoken }

    if (LocalSize.current.width < LastNightWidget.MEDIUM.width) {
        Column(described) {
            // Stacked: a name and "Last night" don't fit across a small widget together.
            snapshot.headingName?.let {
                WidgetEyebrow(it)
                Gap(3.dp)
            }
            WidgetEyebrow("Last night")
            Gap(4.dp)
            Text(title, style = widgetText(17.sp, weight = FontWeight.Medium), maxLines = 1)
            Text(names.ifEmpty { times }, style = widgetText(12.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            WidgetCurve(night.phases, curveWidth, 40.dp, lineWidth = 2.5f)
            Spacer(GlanceModifier.defaultWeight())
            Text(
                if (names.isEmpty()) Format.duration(((end - start) / 60_000).toInt()) else times,
                style = widgetText(11.sp, Theme.secondary, FontWeight.Medium), maxLines = 1,
            )
        }
        return
    }
    Column(described) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(GlanceModifier.defaultWeight()) {
                WidgetEyebrow(snapshot.headed("Last night"))
                Gap(3.dp)
                Text(title, style = widgetText(19.sp, weight = FontWeight.Medium), maxLines = 1)
                if (names.isNotEmpty()) Text(names, style = widgetText(12.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (room != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WidgetIcon(Icons.Filled.Thermostat, Color.White, 13.dp)
                        Spacer(GlanceModifier.width(3.dp))
                        Text(room, style = widgetText(13.sp, weight = FontWeight.Medium), maxLines = 1)
                    }
                }
                if (changes > 0) {
                    Text("$changes ${if (changes == 1) "change" else "changes"}", style = widgetText(12.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        WidgetCurve(night.phases, curveWidth, 44.dp)
        Spacer(GlanceModifier.defaultWeight())
        TimeRow(start, end)
    }
}

@Composable
private fun Empty(snapshot: WidgetSnapshot) {
    val spoken = "${snapshot.spoken("Last night")}. No nights yet. Nights with your BedJet show up here the morning after."
    Column(GlanceModifier.fillMaxSize().semantics { contentDescription = spoken }) {
        snapshot.headingName?.let {
            WidgetEyebrow(it)
            Gap(3.dp)
        }
        WidgetEyebrow("Last night")
        Spacer(GlanceModifier.defaultWeight())
        Text("No nights yet", style = widgetText(17.sp, weight = FontWeight.Medium))
        Gap(6.dp)
        Text("Nights with your BedJet show up here the morning after.", style = widgetText(12.sp, Theme.secondary))
    }
}
