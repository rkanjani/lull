package com.rohankanjani.lull.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.rohankanjani.lull.ActiveRun
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.Schedule
import com.rohankanjani.lull.WatchCommand
import com.rohankanjani.lull.WidgetBedJet
import com.rohankanjani.lull.activity
import com.rohankanjani.lull.spokenSummary
import com.rohankanjani.lull.summary
import com.rohankanjani.lull.ui.NightCurve
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.wear.WatchModel
import com.rohankanjani.lull.widgets.clock
import com.rohankanjani.lull.widgets.ending

/** Tonight's schedule and Start Night, or where the night is up to once it's running, as the Tonight widget shows. */
@Composable
fun TonightPage(model: WatchModel, now: Long) {
    val shown = model.shown
    val bedJet = model.bedJet
    val run = shown.run(now)
    when {
        model.snapshot.bedJets.isEmpty() ->
            EmptyMessage(Icons.Filled.Bed, "No BedJet yet", "Choose your BedJet in Lull on your phone.")
        run != null && bedJet != null -> Running(model, run, bedJet, now)
        else -> {
            val air = bedJet?.live(now) as? LiveStatus.Air
            if (air != null) Air(air, model.snapshot.useFahrenheit)
            val tonight = shown.tonight
            if (tonight != null && bedJet != null) {
                Planned(model, tonight, bedJet)
            } else if (bedJet != null) {
                EmptyMessage(Icons.Filled.Bedtime, "No schedule yet", "Open Lull to plan how your night feels.")
            }
        }
    }
    model.unavailable?.let { Footnote(it) }
}

@Composable
private fun Running(model: WatchModel, run: ActiveRun, bedJet: WidgetBedJet, now: Long) {
    val current = run.phase(now)
    val mode = current?.phase?.mode ?: com.rohankanjani.lull.bedjetkit.Mode.STANDBY
    val minutesLeft = current?.minutesLeft ?: 0
    val spoken = listOfNotNull(
        run.schedule.name,
        current?.let { "phase ${it.index + 1} of ${run.schedule.phases.size}" },
        current?.let { "${it.phase.mode.activity}, ${Format.spokenDuration(it.minutesLeft)} left" },
        "ends at ${clock(run.endsAt)}",
    ).joinToString(", ")
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Eyebrow(current?.let { "Phase ${it.index + 1} of ${run.schedule.phases.size}" } ?: "Finishing")
        Text(run.schedule.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
        // Side by side, so End Night fits on the smallest watches without scrolling.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeLabel(mode)
            Text("${Format.shortDuration(minutesLeft)} left", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = Theme.tabular)
        }
    }
    Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NightCurve(run.schedule.phases, Modifier.fillMaxWidth().height(34.dp), progress = run.progress(now), lineWidth = 2.5.dp)
        Text(LiveStatus.Night(run).ending, color = Theme.secondary, fontSize = 12.sp, style = Theme.tabular)
    }
    ErrandButton(model, "End Night", "Ending…", WatchCommand.EndNight(bedJet.id), prominent = false)
}

@Composable
private fun Planned(model: WatchModel, schedule: Schedule, bedJet: WidgetBedJet) {
    Column(
        Modifier.clearAndSetSemantics { contentDescription = "Tonight, ${schedule.name}, ${schedule.spokenSummary}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(schedule.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center)
        Text(schedule.summary, color = Theme.secondary, fontSize = 13.sp, style = Theme.tabular)
    }
    NightCurve(schedule.phases, Modifier.fillMaxWidth().height(34.dp), lineWidth = 2.5.dp)
    ErrandButton(model, "Start Night", "Starting…", WatchCommand.StartNight(bedJet.id))
}

/** Air from the Remote, running until its timer runs out. */
@Composable
private fun Air(air: LiveStatus.Air, fahrenheit: Boolean) {
    val setting = Format.setting(air.mode, air.temperature, air.fanPercent, fahrenheit)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.raised)
            .clearAndSetSemantics { contentDescription = "${air.mode.activity}, $setting, ${air.ending}" }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ModeLabel(air.mode)
        Text(setting, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = Theme.tabular)
        Text(air.ending, color = Theme.secondary, fontSize = 12.sp, style = Theme.tabular)
    }
}
