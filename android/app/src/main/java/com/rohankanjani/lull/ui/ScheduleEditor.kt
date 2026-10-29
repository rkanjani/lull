package com.rohankanjani.lull.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.Schedule
import com.rohankanjani.lull.bedjetkit.Limits
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.defaultTemperature
import com.rohankanjani.lull.maxMinutes
import com.rohankanjani.lull.title

@Composable
fun ScheduleEditor(model: AppModel, initial: Schedule, onDismiss: () -> Unit) {
    val bedJet = LocalBedJet.current
    // With a wake time, opens on the night fitted to run from the usual bedtime to it, which one saved before Lull fitted it
    // may not be.
    var schedule by remember(initial.id) { mutableStateOf(initial.fitted()) }
    var selection by remember { mutableStateOf<String?>(null) }
    var picksBedtime by remember { mutableStateOf(false) }
    var picksWakeTime by remember { mutableStateOf(false) }
    /** A wake time set in this editor, kept while Bedtime is chosen so choosing Wake time again brings it back. */
    var setWakeTime by remember { mutableStateOf<Int?>(null) }
    val focus = LocalFocusManager.current
    val isValid = schedule.name.isNotBlank() && schedule.phases.isNotEmpty()
    // With a wake time the phases that stretch or shrink take up the time a removed one leaves.
    val remove = { id: String -> schedule = schedule.copy(phases = schedule.phases.filter { it.id != id }).fitted() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Theme.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text("Cancel", fontSize = 17.sp, color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            model.save(schedule)
                            onDismiss()
                        },
                        enabled = isValid,
                    ) { Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (isValid) 1f else 0.4f)) }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    BasicTextField(
                        value = schedule.name,
                        onValueChange = { name ->
                            // Wraps rather than clipping a long name at large text sizes; Return still finishes editing.
                            if (name.contains('\n')) {
                                schedule = schedule.copy(name = name.replace("\n", "").take(Limits.NAME_LENGTH))
                                focus.clearFocus()
                                return@BasicTextField
                            }
                            // The BedJet stores 15 characters.
                            schedule = schedule.copy(name = name.take(Limits.NAME_LENGTH))
                        },
                        textStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Schedule name, up to ${Limits.NAME_LENGTH} characters" },
                        decorationBox = { field ->
                            if (schedule.name.isEmpty()) Text("Name", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Theme.tertiary)
                            field()
                        },
                    )

                    Card(Modifier.animateContentSize()) {
                        NightTimeline(model, schedule, selection = selection, height = 170.dp)
                    }

                    NightCard(
                        schedule,
                        onBedtime = { picksBedtime = true },
                        onWakeTime = { picksWakeTime = true },
                        onEndsAtWakeTime = { endsAtWakeTime ->
                            // Choosing Bedtime keeps the wake time set here, so choosing Wake time again brings it back until
                            // the schedule is saved or cancelled. A first one starts where the night ends now.
                            if (endsAtWakeTime) {
                                schedule = schedule.copy(wakeTime = setWakeTime ?: schedule.usualWakeTime).fitted()
                            } else {
                                setWakeTime = schedule.wakeTime
                                schedule = schedule.copy(wakeTime = null)
                            }
                        },
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("Phases", Modifier.semantics { heading() })
                        // What the lengths below mean, so it sits with them rather than with the night's times.
                        Text(schedule.phasesCaption, fontSize = 13.sp, color = Theme.secondary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Each phase starts where the one before it ends, from the usual bedtime: with a wake time, on a usual night.
                        schedule.phases.forEachIndexed { index, phase ->
                            PhaseRow(
                                model, phase, number = index + 1,
                                startsAt = schedule.startMinute(phase),
                                isFixed = schedule.wakeTime != null && phase.fixedLength,
                                onDelete = if (schedule.phases.size > 1) {
                                    { remove(phase.id) }
                                } else {
                                    null
                                },
                            ) { selection = phase.id }
                        }
                    }
                    if (schedule.phases.size < Limits.STEPS_PER_PROGRAM) {
                        PrimaryButton(
                            onClick = {
                                // With a wake time it takes its hour from the phases that stretch or shrink, as far as they have it.
                                val phase = Phase(Mode.COOL, fanPercent = 30, minutes = 60)
                                schedule = schedule.copy(phases = schedule.phases + phase).fitted(holding = phase.id)
                                selection = phase.id
                            },
                            prominent = false,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp))
                                Text("Add Phase")
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().background(Theme.surface.copy(alpha = 0.92f)).navigationBarsPadding().imePadding()) {
                    PrimaryButton(
                        "Use Tonight",
                        onClick = {
                            model.save(schedule)
                            bedJet.tonightID = schedule.id
                            onDismiss()
                        },
                        enabled = isValid,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            val index = schedule.phases.indexOfFirst { it.id == selection }
            if (index >= 0) {
                PhaseEditor(
                    model,
                    phase = schedule.phases[index],
                    // With a wake time the night still ends at it: the phase keeps the length it's given, and the phases that
                    // stretch or shrink share the rest.
                    onChange = { changed ->
                        schedule = schedule.copy(phases = schedule.phases.toMutableList().also { it[index] = changed }).fitted(holding = changed.id)
                    },
                    canDelete = schedule.phases.size > 1,
                    durationSteps = schedule.durationSteps(schedule.phases[index]),
                    scaling = schedule.scalingNote(schedule.phases[index]),
                    onDelete = {
                        selection = null
                        remove(schedule.phases[index].id)
                    },
                    onDismiss = { selection = null },
                )
            }
            if (picksBedtime) {
                // The phases are fitted afresh to the night between the usual bedtime and the wake time when either changes.
                TimeOfDayPicker("Usual bedtime", schedule.bedtime, onPick = { schedule = schedule.copy(bedtime = it).fitted() }, onDismiss = { picksBedtime = false })
            }
            schedule.wakeTime?.let { wakeTime ->
                if (picksWakeTime) {
                    TimeOfDayPicker("Wake time", wakeTime, onPick = { schedule = schedule.copy(wakeTime = it).fitted() }, onDismiss = { picksWakeTime = false })
                }
            }
        }
    }
}

/**
 * When the night starts and ends. Without a wake time it runs from the usual bedtime for as long as its phases. With one
 * the phases are fitted to run from the usual bedtime to the wake time, and stretch or shrink to end at it whenever Start
 * Night is pressed.
 */
@Composable
private fun NightCard(
    schedule: Schedule,
    onBedtime: () -> Unit,
    onWakeTime: () -> Unit,
    onEndsAtWakeTime: (Boolean) -> Unit,
) {
    val wakeTime = schedule.wakeTime
    Card(Modifier.animateContentSize(), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Night", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, modifier = Modifier.semantics { heading() })
            // A wake time or the usual bedtime alone, chosen as the Remote chooses Timer or Time.
            SegmentedChoice(listOf(true to "Wake time", false to "Bedtime"), wakeTime != null, onEndsAtWakeTime, Modifier.fillMaxWidth())
            // The usual bedtime stays where it is, the wake time under it when there is one.
            Column {
                TimeRow("Usual bedtime") {
                    TimeChip(schedule.bedtime, "Usual bedtime", "Change bedtime", onBedtime)
                }
                if (wakeTime != null) {
                    TimeRow("Wake time") {
                        TimeChip(wakeTime, "Wake time", "Change wake time", onWakeTime)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(
    model: AppModel,
    phase: Phase,
    number: Int,
    startsAt: Int,
    /** Keeps its length while a wake time scales the rest of the night. */
    isFixed: Boolean,
    /** Null when this is the only phase, which can't be removed. */
    onDelete: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val bedJet = LocalBedJet.current
    val detail = buildList {
        if (bedJet.adjustsTemperature(phase.mode)) add(model.degrees(phase.temperature))
        if (phase.mode.usesFan) add("Fan ${phase.fanPercent}%")
    }.ifEmpty { listOf("Air off") }.joinToString(" · ")
    val shape = RoundedCornerShape(22.dp)
    // At the largest text sizes the timing moves under the mode instead of beside it.
    val large = isLargeText()
    val badge = (40.dp * LocalDensity.current.fontScale).coerceAtMost(56.dp)
    val mode = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(badge).background(phase.mode.tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(phase.mode.icon, null, tint = phase.mode.tint, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(phase.mode.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, fontSize = 13.sp, color = Theme.secondary)
            }
        }
    }
    val timing = @Composable {
        Column(horizontalAlignment = if (large) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isFixed) Icon(Icons.Filled.Lock, null, tint = Theme.secondary, modifier = Modifier.size(12.dp))
                Text(Format.duration(phase.minutes), fontSize = 15.sp, fontWeight = FontWeight.Medium, style = Theme.tabular)
            }
            Text(Format.clock(startsAt), fontSize = 13.sp, color = Theme.secondary, style = Theme.tabular)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.surface)
            .border(BorderStroke(1.dp, Theme.hairline), shape)
            .clickable(role = Role.Button, onClickLabel = "Edit this phase", onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Phase $number, ${model.spokenSummary(phase)}${if (isFixed) ", fixed length" else ""}, starts ${Format.clock(startsAt)}"
                role = Role.Button
                onClick(label = "Edit this phase") { onClick(); true }
                if (onDelete != null) customActions = listOf(CustomAccessibilityAction("Remove Phase") { onDelete(); true })
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (large) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mode()
                timing()
            }
        } else {
            Box(Modifier.weight(1f)) { mode() }
            timing()
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Theme.tertiary, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhaseEditor(
    model: AppModel,
    phase: Phase,
    onChange: (Phase) -> Unit,
    canDelete: Boolean,
    /**
     * The lengths the Duration slider offers. Null when there's nothing to choose, as for the only phase that stretches or
     * shrinks to fill the night.
     */
    durationSteps: IntRange?,
    /** With a wake time, whether this phase stretches or shrinks with the night, or why the night can't. */
    scaling: String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bedJet = LocalBedJet.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isHeatLimited = phase.maxMinutes < phase.mode.maxMinutes
    LaunchedEffect(phase.maxMinutes) {
        if (phase.minutes > phase.maxMinutes) onChange(phase.copy(minutes = phase.maxMinutes))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Theme.surface, dragHandle = null) {
        // Scrolls once it's taller than the screen, as at the largest text sizes, as the iPhone's sheet does.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp).padding(top = 8.dp).navigationBarsPadding().animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Phase", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
            ModePicker(bedJet.phaseModes, phase.mode) { mode ->
                onChange(
                    phase.copy(
                        mode = mode,
                        temperature = if (phase.temperature in mode.temperatureRange) phase.temperature else mode.defaultTemperature,
                    ),
                )
            }
            if (bedJet.adjustsTemperature(phase.mode)) {
                TemperatureSlider(model, phase.mode, phase.temperature, onChange = { onChange(phase.copy(temperature = it)) })
            }
            if (phase.mode.usesFan) {
                LabeledSlider(
                    "Fan", "${phase.fanPercent}%", phase.fanPercent.toFloat(),
                    onValueChange = { onChange(phase.copy(fanPercent = it.toInt())) },
                    range = 5f..100f, step = 5f, tint = phase.mode.tint,
                )
            }
            if (durationSteps != null) {
                LabeledSlider(
                    "Duration", Format.duration(phase.minutes), phase.minutes.toFloat(),
                    onValueChange = { onChange(phase.copy(minutes = it.toInt())) },
                    range = durationSteps.first.toFloat()..durationSteps.last.toFloat(), step = 5f,
                    spokenValue = Format.spokenDuration(phase.minutes),
                )
            } else {
                ValueHeader(
                    "Duration", Format.duration(phase.minutes),
                    Modifier.clearAndSetSemantics {
                        contentDescription = "Duration"
                        stateDescription = Format.spokenDuration(phase.minutes)
                    },
                )
            }
            if (scaling != null) {
                Toggle("Fixed length", phase.fixedLength, fontWeight = FontWeight.Medium) { onChange(phase.copy(fixedLength = it)) }
                Text(scaling, fontSize = 13.sp, color = Theme.secondary)
            }
            if (isHeatLimited) {
                Text(
                    "At this temperature and fan, BedJet heats for up to ${Format.duration(phase.maxMinutes)} before its safety cut-off." +
                        if (bedJet.generation.supports(Mode.EXTENDED_HEAT)) " Use Gentle heat to run longer." else "",
                    fontSize = 13.sp, color = Theme.secondary,
                )
            }
            if (canDelete) {
                Text(
                    "Remove Phase",
                    fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Theme.red,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onDelete)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
