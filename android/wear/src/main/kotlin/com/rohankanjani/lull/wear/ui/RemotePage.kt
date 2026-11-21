package com.rohankanjani.lull.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.PresetChoice
import com.rohankanjani.lull.RemotePreset
import com.rohankanjani.lull.RemoteSetting
import com.rohankanjani.lull.WatchCommand
import com.rohankanjani.lull.WidgetBedJet
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.defaultTemperature
import com.rohankanjani.lull.maxMinutes
import com.rohankanjani.lull.remoteModes
import com.rohankanjani.lull.title
import com.rohankanjani.lull.ui.tint
import com.rohankanjani.lull.wear.WatchModel
import kotlinx.coroutines.delay

/**
 * Air right now rather than a schedule, as the phone's Remote: a mode, and the temperature, fan and timer to run it at.
 * Apply sends what was changed here; the rest stays as the BedJet has it.
 */
@Composable
fun RemotePage(model: WatchModel) {
    val bedJet = model.bedJet
    if (bedJet == null) {
        EmptyMessage(Icons.Filled.Bed, "No BedJet yet", "Choose your BedJet in Lull on your phone.")
    } else {
        Controls(model, bedJet)
    }
    model.unavailable?.let { Footnote(it) }
}

@Composable
private fun Controls(model: WatchModel, bedJet: WidgetBedJet) {
    val fahrenheit = model.snapshot.useFahrenheit
    var mode by remember { mutableStateOf(Mode.COOL) }
    var temperature by remember { mutableIntStateOf(Mode.COOL.defaultTemperature) }
    var fan by remember { mutableIntStateOf(40) }
    var minutes by remember { mutableIntStateOf(30) }
    // What was changed here since the page last followed the BedJet. Only these are sent, so a setting left alone
    // stays as the BedJet has it, even if it changed since the watch last heard.
    var changed by remember { mutableStateOf(emptySet<RemoteSetting>()) }
    // The BedJet wasn't running air from the Remote, so Apply turns it on with everything shown.
    var startsAir by remember { mutableStateOf(false) }

    /** How long the mode may run at this temperature and fan, with a step that suits the span, as on the phone. */
    fun runtimeLimit(): Pair<IntRange, Int> {
        val longest = Phase(mode, temperature, fan, 1).maxMinutes
        val step = if (longest <= 15) 1 else if (longest <= 240) 5 else 15
        return step..maxOf(longest / step * step, step * 2) to step
    }

    /** Keeps the time to what the mode may run at this temperature and fan, on the step the row turns by. */
    fun keepTimeInLimit() {
        val (range, step) = runtimeLimit()
        minutes = if (RemoteSetting.MODE in changed && RemoteSetting.TIME !in changed && !startsAir) {
            // As on the phone's Remote, another mode runs as long as it can unless a time is set here too.
            range.last
        } else {
            ((minutes + step / 2) / step * step).coerceIn(range.first, range.last)
        }
    }

    /** Sets one of the page's settings, noting it as changed if it is. */
    fun change(value: Int, newValue: Int, setting: RemoteSetting, set: (Int) -> Unit) {
        if (newValue == value) return
        set(newValue)
        changed = changed + setting
    }

    /** What the BedJet is doing from the Remote, or the Quick Remote's first button: Cool for 30 minutes. */
    fun follow() {
        changed = emptySet()
        val air = bedJet.live(System.currentTimeMillis()) as? LiveStatus.Air
        startsAir = air == null
        if (air != null) {
            mode = air.mode
            temperature = air.temperature ?: air.mode.defaultTemperature
            fan = air.fanPercent
            val step = runtimeLimit().second
            minutes = maxOf(((air.endsAt - System.currentTimeMillis()) / 60_000L).toInt() / step * step, step)
        } else {
            val first = PresetChoice.defaults[0].preset(fahrenheit)
            mode = first.mode
            temperature = first.temperature
            fan = first.fanPercent
            minutes = first.minutes
        }
    }

    // Starts from what the BedJet is doing, and again for another BedJet.
    LaunchedEffect(bedJet.id) { follow() }
    // Keeps up with it until something here changes.
    LaunchedEffect(bedJet) { if (changed.isEmpty()) follow() }
    // Once a change is sent the BedJet has it, so the page follows it again rather than sending it twice.
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(model.pending) {
        val pending = model.pending
        if (pending == null && sending && model.errorMessage == null) follow()
        sending = pending is WatchCommand.Remote
    }
    // When what the BedJet was last known to be doing ends. Not checked against the clock, so the page still catches up
    // if it wasn't shown at the time.
    val endsAt = bedJet.live?.endsAt
    // What the BedJet is doing ends by the clock, which Lull on the phone may not be there to hear, so the page plays
    // it out too: once air from the Remote has run out, Apply turns it on again with everything shown.
    LaunchedEffect(endsAt) {
        if (endsAt == null) return@LaunchedEffect
        // delay's clock stops while the watch sleeps, so this waits in short steps against the wall clock.
        while (System.currentTimeMillis() < endsAt) delay(minOf(endsAt - System.currentTimeMillis(), 5_000L))
        if (changed.isEmpty()) follow() else startsAir = true
    }
    // Kept to what the mode may run at this temperature and fan, as the phone's phase editor does.
    LaunchedEffect(runtimeLimit().first) { keepTimeInLimit() }

    val modes = remoteModes.filter(bedJet.generation::supports) + Mode.STANDBY
    for (row in modes.chunked(3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in row) {
                ModeButton(option, option == mode, Modifier.weight(1f), option.title) {
                    if (option != mode) {
                        mode = option
                        temperature = option.defaultTemperature
                        changed = changed + RemoteSetting.MODE + RemoteSetting.TEMPERATURE
                        keepTimeInLimit()
                    }
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (mode != Mode.STANDBY) {
        if (bedJet.generation.adjustsTemperature(mode)) {
            // In the degrees Lull shows, whole degrees at a time, as on the phone.
            val low = Format.degrees(mode.temperatureRange.first, fahrenheit)
            val high = maxOf(Format.degrees(mode.temperatureRange.last, fahrenheit), low + 1)
            CrownRow(
                title = "Temperature",
                valueText = model.snapshot.degrees(temperature),
                value = Format.degrees(temperature, fahrenheit),
                onValueChange = { degrees ->
                    change(temperature, Format.halfCelsius(degrees, fahrenheit).coerceIn(mode.temperatureRange), RemoteSetting.TEMPERATURE) { temperature = it }
                },
                range = low..high,
                tint = mode.tint,
            )
        }
        if (mode.usesFan) {
            CrownRow("Fan", "$fan%", fan, { percent -> change(fan, percent, RemoteSetting.FAN) { fan = it } }, 5..100, step = 5, tint = mode.tint)
        }
        val (range, step) = runtimeLimit()
        CrownRow(
            "Turns off in", Format.duration(minutes), minutes, { length -> change(minutes, length, RemoteSetting.TIME) { minutes = it } }, range, step,
            spokenValue = Format.spokenDuration(minutes),
        )
    }
    val preset = RemotePreset(mode, minutes, temperature, fan).clamped
    val changes = if (startsAir) RemoteSetting.entries.toSet() else changed
    ErrandButton(
        model, if (mode == Mode.STANDBY) "Turn Off" else "Apply", "Sending…", WatchCommand.Remote(preset, bedJet.id, changes),
        enabled = changes.isNotEmpty(),
    )
    if (bedJet.live(System.currentTimeMillis()) is LiveStatus.Night) {
        Footnote("Choosing a mode here ends the schedule that's running.")
    }
}
