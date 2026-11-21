package com.rohankanjani.lull.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.RemoteTimer
import com.rohankanjani.lull.activity
import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.maxMinutes
import com.rohankanjani.lull.title
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Direct control, for when you want air right now rather than a schedule. */
@Composable
fun RemoteScreen(model: AppModel) {
    val bedJet = LocalBedJet.current
    val status = bedJet.status
    val mode = status?.mode ?: Mode.STANDBY
    var temperature by remember { mutableIntStateOf(60) }
    var fan by remember { mutableFloatStateOf(40f) }
    var runtime by remember { mutableFloatStateOf(60f) }
    /** When the air turns off, as a minute of the day, for the Remote showing it as a time. */
    var endMinute by remember { mutableIntStateOf(0) }
    var picksEnd by remember { mutableStateOf(false) }
    /** While a slider is in use, and briefly after, it leads and the BedJet's reports are not applied to it. */
    var followAfter by remember { mutableLongStateOf(0L) }
    val pending = remember { mutableStateMapOf<String, Job>() }
    /** The mode just tapped, shown as selected until the BedJet reports it. */
    var chosenMode by remember { mutableStateOf<Mode?>(null) }

    /**
     * Forwards what the person sets to the BedJet shortly after they stop moving the slider.
     * Only their input passes through here, so values applied from the BedJet are never echoed back.
     */
    fun sending(name: String, send: suspend () -> Unit) {
        followAfter = System.currentTimeMillis() + 1500
        pending[name]?.cancel()
        pending[name] = model.scope.launch {
            delay(350)
            send()
        }
    }

    LaunchedEffect(status) {
        // Follow the unit, which can also be changed from its own remote, but never
        // while a slider is being moved or its new value is still on the way there.
        if (status == null || bedJet.isBusy || System.currentTimeMillis() <= followAfter) return@LaunchedEffect
        temperature = status.targetTemperature
        fan = status.fanPercent.toFloat()
        val limit = runtimeLimit(status)
        runtime = minutesLeft(status).toFloat().coerceIn(limit.range)
        endMinute = Format.endMinute(status.remainingSeconds, System.currentTimeMillis())
    }

    LaunchedEffect(bedJet.id) {
        // Another BedJet's settings show straight away, and a slider change still on its way is dropped
        // rather than sent once this BedJet is no longer the one on screen.
        for (job in pending.values) job.cancel()
        pending.clear()
        followAfter = 0
        chosenMode = null
    }

    Screen(model, "Remote") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ClimateOrb(model, status) }

        if (bedJet.linkState == LinkState.YIELDED) {
            Text(
                "In use on another phone. Touch a control to take over.",
                fontSize = 13.sp, color = Theme.secondary, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        // Only the controls: the BedJet button stays usable, so Demo mode is reachable with Bluetooth off.
        Box(Modifier.alpha(if (bedJet.canControl) 1f else 0.4f)) {
            ModePicker(
                bedJet.remoteModes + Mode.STANDBY, chosenMode ?: status?.mode,
                hint = if (bedJet.run != null) "End the schedule that's running" else null,
            ) { picked ->
                if (!bedJet.canControl) return@ModePicker
                chosenMode = picked
                model.scope.launch {
                    bedJet.setMode(picked)
                    chosenMode = null
                }
            }
        }

        AnimatedVisibility(
            visible = status?.isRunning == true,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 3 },
        ) {
            val shown = status ?: return@AnimatedVisibility
            Card(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (bedJet.adjustsTemperature(shown.mode)) {
                        TemperatureSlider(
                            model, shown.mode, temperature,
                            onChange = {
                                temperature = it
                                sending("temperature") { bedJet.setTemperature(temperature) }
                            },
                            limits = if (shown.minTemperature < shown.maxTemperature) shown.minTemperature..shown.maxTemperature else null,
                            enabled = bedJet.canControl,
                        )
                    }
                    LabeledSlider(
                        "Fan", "${fan.toInt()}%", fan,
                        onValueChange = {
                            fan = it
                            sending("fan") { bedJet.setFan(fan.toInt()) }
                        },
                        range = 5f..100f, step = 5f, tint = shown.mode.tint, enabled = bedJet.canControl,
                    )
                    if (bedJet.run == null) {
                        // When the air turns off: after a duration, the default, or at a time of day. Choosing one only
                        // changes what's shown, since the BedJet counts down either way.
                        SegmentedChoice(
                            listOf(false to "Timer", true to "Time"), model.remoteTimerShowsTime, { model.remoteTimerShowsTime = it },
                            Modifier.fillMaxWidth(),
                        )
                        // Both are laid out, the one not chosen hidden, so the card keeps one height and nothing on the screen
                        // moves when switching. The hidden one can't be seen, touched or read.
                        val showsTime = model.remoteTimerShowsTime
                        val hidden = Modifier.alpha(0f).clearAndSetSemantics {}
                        Box {
                            val limit = runtimeLimit(shown)
                            Box(if (showsTime) hidden else Modifier) {
                                LabeledSlider(
                                    "Turns off in", Format.duration(runtime.toInt()), runtime,
                                    onValueChange = {
                                        runtime = it
                                        sending("runtime") { bedJet.setRuntime(runtime.toInt()) }
                                    },
                                    range = limit.range, step = limit.step, enabled = bedJet.canControl && !showsTime,
                                    spokenValue = Format.spokenDuration(runtime.toInt()),
                                    // As tall as the time's row, so "Turns off" stays put when switching between them.
                                    headerMinHeight = TimeRowHeight,
                                )
                            }
                            Column(if (showsTime) Modifier else hidden, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimeRow("Turns off at", fontSize = 15.sp, color = Theme.secondary) {
                                    TimeChip(endMinute, "Turns off at", "Change the time") {
                                        if (bedJet.canControl && showsTime) picksEnd = true
                                    }
                                }
                                Text(turnsOffCaption(bedJet.remoteTimer, shown, endMinute), fontSize = 13.sp, color = Theme.secondary)
                            }
                        }
                    } else {
                        // A schedule sets its own timing, so this is read-only until it ends.
                        ValueHeader(
                            "Phase ends in", Format.duration(minutesLeft(shown)),
                            Modifier.clearAndSetSemantics {
                                contentDescription = "Phase ends in"
                                stateDescription = Format.spokenDuration(minutesLeft(shown))
                            },
                        )
                    }
                }
            }
        }
        if (bedJet.run != null) {
            Text(
                "Choosing a mode here ends the schedule that's running.",
                fontSize = 13.sp, color = Theme.tertiary, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (picksEnd) {
        TimeOfDayPicker("Turns off at", endMinute, onPick = { picked ->
            endMinute = picked
            sending("runtime") { bedJet.setRuntime(untilMillis = Format.nextTime(picked, System.currentTimeMillis())) }
        }, onDismiss = { picksEnd = false })
    }
}

/** How long until the air turns off, or why it turns off sooner than the time picked. */
private fun turnsOffCaption(timer: RemoteTimer, status: DeviceStatus, endMinute: Int): String {
    val cutShort = timer is RemoteTimer.Until && timer.isAhead &&
        timer.endsAt - System.currentTimeMillis() > (status.remainingSeconds + 60) * 1000L
    if (!cutShort) return "In ${Format.duration(minutesLeft(status))}."
    val longest = if (status.maxRuntimeMinutes > 0) status.maxRuntimeMinutes else status.mode.maxMinutes
    return "${status.mode.title} runs for up to ${Format.duration(longest)}, so it turns off at ${Format.clock(endMinute)}."
}

private fun minutesLeft(status: DeviceStatus): Int = maxOf((status.remainingSeconds + 59) / 60, 1)

private class RuntimeLimit(val range: ClosedFloatingPointRange<Float>, val step: Float)

/** How long the current mode may run, as the BedJet reports it, with a step size that suits the span. */
private fun runtimeLimit(status: DeviceStatus): RuntimeLimit {
    val longest = if (status.maxRuntimeMinutes > 0) status.maxRuntimeMinutes else status.mode.maxMinutes
    val step = if (longest <= 15) 1 else if (longest <= 240) 5 else 15
    return RuntimeLimit(step.toFloat()..maxOf(longest / step * step, step * 2).toFloat(), step.toFloat())
}

/**
 * Temperature in the person's unit, stored in half-degrees Celsius.
 * The Remote passes the limits the BedJet reports for the mode it is in, which can be narrower than the mode's range.
 */
@Composable
fun TemperatureSlider(
    model: AppModel,
    mode: Mode,
    temperature: Int,
    onChange: (Int) -> Unit,
    limits: IntRange? = null,
    enabled: Boolean = true,
) {
    val bounds = limits ?: mode.temperatureRange
    val low = Format.degrees(bounds.first, model.useFahrenheit)
    val high = maxOf(Format.degrees(bounds.last, model.useFahrenheit), low + 1)
    LabeledSlider(
        "Temperature", model.degrees(temperature), Format.degrees(temperature, model.useFahrenheit).toFloat(),
        onValueChange = { value ->
            val halves = Format.halfCelsius(value.toInt(), model.useFahrenheit)
            onChange(halves.coerceIn(bounds))
        },
        range = low.toFloat()..high.toFloat(), tint = mode.tint, enabled = enabled,
    )
}

/** The glowing live readout: what the BedJet is doing this second. */
@Composable
fun ClimateOrb(model: AppModel, status: DeviceStatus?) {
    val bedJet = LocalBedJet.current
    val mode = status?.mode ?: Mode.STANDBY
    val isOn = status?.isRunning ?: false
    val tint by animateColorAsState(mode.tint, tween(600), label = "tint")
    val ring by animateDpAsState(if (isOn) 2.5.dp else 1.dp, tween(600), label = "ring")
    // Grows with the text inside it, up to what fits across a phone.
    val diameter = (204.dp * LocalDensity.current.fontScale).coerceAtMost(320.dp)
    val spoken = when {
        status == null -> "Looking for ${model.title(bedJet)}"
        !status.isRunning -> "Off, room ${model.degrees(status.ambientTemperature)}"
        else -> buildList {
            add(status.mode.activity)
            if (bedJet.adjustsTemperature(status.mode)) add("set to ${model.degrees(status.targetTemperature)}")
            add("fan ${status.fanPercent}%")
            add("air ${model.degrees(status.actualTemperature)}")
        }.joinToString(", ")
    }
    Box(Modifier.size(diameter).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().blur(24.dp, BlurredEdgeTreatment.Unbounded)) {
            drawCircle(
                Brush.radialGradient(
                    listOf(tint.copy(alpha = if (isOn) 0.55f else 0.12f), Color.Transparent),
                    radius = 112.dp.toPx(),
                ),
            )
        }
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val width = ring.toPx()
            drawCircle(
                Brush.sweepGradient(listOf(tint.copy(alpha = 0.9f), tint.copy(alpha = 0.15f), tint.copy(alpha = 0.9f))),
                radius = size.minDimension / 2 - width / 2,
                style = Stroke(width),
            )
        }
        Column(
            Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (status != null) {
                // White, unlike other labels, so it stays readable over the glow.
                Eyebrow(mode.activity, color = Color.White)
                BasicText(
                    if (isOn && bedJet.adjustsTemperature(mode)) model.degrees(status.targetTemperature) else if (isOn) "${status.fanPercent}%" else "Off",
                    style = Theme.tabular.copy(fontSize = 60.sp, fontWeight = FontWeight.Light, color = Color.White, textAlign = TextAlign.Center),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 60.sp * LocalDensity.current.fontScale),
                )
                Text(
                    if (isOn) "Fan ${status.fanPercent}% · Air ${model.degrees(status.actualTemperature)}"
                    else "Room ${model.degrees(status.ambientTemperature)}",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, style = Theme.tabular,
                    textAlign = TextAlign.Center,
                )
            } else {
                CircularProgressIndicator(color = Theme.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Text(
                    "Looking for ${model.title(bedJet)}",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Theme.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
