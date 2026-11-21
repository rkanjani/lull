package com.rohankanjani.lull.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohankanjani.lull.ActiveRun
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.Schedule
import com.rohankanjani.lull.activity
import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.spokenSummary
import com.rohankanjani.lull.summary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Plan the night and run it: tonight's schedule on top, the rest of the library below. */
@Composable
fun ScheduleScreen(model: AppModel) {
    var editing by remember { mutableStateOf<Schedule?>(null) }
    val today = LocalDate.now().format(
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMd")),
    )
    val bedJet = LocalBedJet.current
    val run = bedJet.run
    /** The schedule shown in the large card: the one running, otherwise the one picked for tonight. */
    val featuredID = run?.schedule?.id ?: bedJet.tonight?.id

    Screen(model, "Schedule", subtitle = today) {
        val tonight = bedJet.tonight
        val elsewhere = bedJet.runningElsewhereStep
        // Redrawn as time passes: a running night moves on, and with a wake time how long tonight's runs, and whether it
        // can, depend on when it starts. Read afresh whenever the night shown changes.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(run, tonight) {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
        when {
            run != null -> Running(model, run, now)
            elsewhere != null -> RunningElsewhere(model, elsewhere)
            tonight != null -> Planned(model, tonight, now, onEdit = { editing = it })
            else -> Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No schedules yet", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Create one to plan how your night feels.", fontSize = 15.sp, color = Theme.secondary)
                }
            }
        }

        val others = model.schedules.filter { it.id != featuredID }
        if (others.isNotEmpty()) {
            Eyebrow("More schedules", Modifier.padding(top = 8.dp).semantics { heading() })
            others.forEach { schedule -> ScheduleRow(model, schedule, onEdit = { editing = it }) }
        }
        PrimaryButton(
            onClick = { editing = Schedule.blank() },
            prominent = false,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp))
                Text("New Schedule")
            }
        }
    }

    editing?.let { schedule -> ScheduleEditor(model, schedule, onDismiss = { editing = null }) }
}

private fun duplicate(model: AppModel, schedule: Schedule) {
    model.save(schedule.copy(id = UUID.randomUUID().toString(), name = Codec.sanitize("${schedule.name} copy")))
}

/** The long-press menu's choices, offered to TalkBack as actions. */
private fun scheduleActions(model: AppModel, schedule: Schedule) = listOf(
    CustomAccessibilityAction("Duplicate") { duplicate(model, schedule); true },
    CustomAccessibilityAction("Delete") { model.delete(schedule); true },
)

@Composable
private fun Planned(model: AppModel, schedule: Schedule, now: Long, onEdit: (Schedule) -> Unit) {
    val bedJet = LocalBedJet.current
    WithMenu(model, schedule, onEdit) { openMenu ->
        Card(Modifier.combinedClickable(onClick = {}, onLongClick = openMenu, indication = null, interactionSource = null)) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Eyebrow("Tonight")
                        Text(schedule.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Edit", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Theme.secondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onEdit(schedule) }
                            .padding(12.dp)
                            .semantics {
                                contentDescription = "Edit ${schedule.name}"
                                customActions = scheduleActions(model, schedule)
                            },
                    )
                }
                // Started now: with a wake time, the last phase runs until it from now.
                val startMinute = Format.minuteOfDay(now)
                NightTimeline(model, schedule.resolved(startingAt = startMinute), startMinute = startMinute)
                val problem = bedJet.problem(schedule, now)
                if (problem != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.WarningAmber, null, tint = Theme.secondary, modifier = Modifier.size(18.dp))
                        Text(problem, fontSize = 13.sp, color = Theme.secondary)
                    }
                }
                PrimaryButton(
                    onClick = { model.scope.launch { bedJet.startTonight() } },
                    enabled = bedJet.canControl && !bedJet.isBusy && problem == null,
                    label = "Start Night",
                    modifier = Modifier.semantics { if (bedJet.isBusy) stateDescription = "Starting" },
                ) {
                    if (bedJet.isBusy) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Text("Start Night")
                    }
                }
                Text(
                    when (bedJet.linkState) {
                        LinkState.READY -> "Saved to your BedJet, which runs it all night. Your phone can sleep too."
                        LinkState.YIELDED -> "Your BedJet is in use on another phone. Starting here takes over."
                        else -> "Waiting for your BedJet. Keep your phone within a few feet of it."
                    },
                    fontSize = 13.sp, color = Theme.tertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Shown instead of the planned card when the BedJet is already running a schedule this phone didn't start. */
@Composable
private fun RunningElsewhere(model: AppModel, step: Int) {
    val bedJet = LocalBedJet.current
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Running now")
                Text("Phase $step", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                bedJet.status?.let { status ->
                    val minutes = maxOf((status.remainingSeconds + 59) / 60, 1)
                    ModeLabel(
                        status.mode, "${status.mode.activity} · ${Format.duration(minutes)} left in this phase",
                        spoken = "${status.mode.activity}, ${Format.spokenDuration(minutes)} left in this phase",
                    )
                }
                Text("This schedule was started from another phone or the BedJet's own remote.", fontSize = 13.sp, color = Theme.tertiary)
            }
            PrimaryButton("End Night", onClick = { model.scope.launch { bedJet.endNight() } }, prominent = false, enabled = !bedJet.isBusy)
        }
    }
}

@Composable
private fun Running(model: AppModel, run: ActiveRun, now: Long) {
    val bedJet = LocalBedJet.current
    val current = run.phase(now)
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(current?.let { "Phase ${it.index + 1} of ${run.schedule.phases.size}" } ?: "Finishing")
                Text(run.schedule.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                current?.let {
                    ModeLabel(
                        it.phase.mode, "${it.phase.mode.activity} · ${Format.duration(it.minutesLeft)} left",
                        spoken = "${it.phase.mode.activity}, ${Format.spokenDuration(it.minutesLeft)} left",
                    )
                }
            }
            NightTimeline(model, run.schedule, startMinute = Format.minuteOfDay(run.startedAt), progress = run.progress(now))
            PrimaryButton("End Night", onClick = { model.scope.launch { bedJet.endNight() } }, prominent = false, enabled = !bedJet.isBusy)
        }
    }
}

@Composable
private fun ModeLabel(mode: Mode, text: String, spoken: String) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(mode.icon, null, tint = mode.tint, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = mode.tint)
    }
}

@Composable
private fun ScheduleRow(model: AppModel, schedule: Schedule, onEdit: (Schedule) -> Unit) {
    val bedJet = LocalBedJet.current
    // Swapping schedules waits until the one that is running has ended.
    val canSwap = bedJet.run == null
    // At the largest text sizes Use Tonight goes under the card rather than over its corner.
    val beneath = isLargeText()
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WithMenu(model, schedule, onEdit) { openMenu ->
            Box {
                Card(
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .combinedClickable(onClick = { onEdit(schedule) }, onLongClick = openMenu)
                        .clearAndSetSemantics {
                            contentDescription = "${schedule.name}, ${schedule.spokenSummary}"
                            role = Role.Button
                            onClick(label = "Edit this schedule") { onEdit(schedule); true }
                            customActions = scheduleActions(model, schedule)
                        },
                    padding = 18.dp,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            schedule.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            // Leaves room for the Use Tonight button laid over this corner.
                            modifier = Modifier.padding(end = if (canSwap && !beneath) 116.dp else 0.dp),
                        )
                        NightCurve(schedule.phases, Modifier.fillMaxWidth().height(64.dp), lineWidth = 2.5.dp)
                        Text(
                            schedule.summary,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Theme.secondary,
                        )
                    }
                }
                if (canSwap && !beneath) UseTonight(model, schedule, Modifier.align(Alignment.TopEnd).padding(14.dp))
            }
        }
        if (canSwap && beneath) UseTonight(model, schedule, Modifier)
    }
}

@Composable
private fun UseTonight(model: AppModel, schedule: Schedule, modifier: Modifier) {
    val bedJet = LocalBedJet.current
    Pill(
        modifier
            .heightIn(min = 32.dp)
            .clearAndSetSemantics {
                contentDescription = "Use ${schedule.name} tonight"
                role = Role.Button
                onClick { bedJet.tonightID = schedule.id; true }
            },
        onClick = { bedJet.tonightID = schedule.id },
    ) {
        Text("Use Tonight", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
    }
}

/** Long-press menu for a schedule: edit, duplicate, delete. */
@Composable
private fun WithMenu(model: AppModel, schedule: Schedule, onEdit: (Schedule) -> Unit, content: @Composable (openMenu: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        content {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Theme.raised) {
            DropdownMenuItem(
                text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Tune, null) },
                onClick = { open = false; onEdit(schedule) },
            )
            DropdownMenuItem(
                text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { open = false; duplicate(model, schedule) },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = Theme.red) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Theme.red) },
                onClick = { open = false; model.delete(schedule) },
            )
        }
    }
}
