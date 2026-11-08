package com.rohankanjani.lull

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Limits
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.ProgramStep
import com.rohankanjani.lull.bedjetkit.StoredProgram
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** One stretch of the night: what the BedJet does and for how long. */
@Serializable
data class Phase(
    val mode: Mode,
    /** Half-degrees Celsius, the unit the BedJet stores. */
    val temperature: Int = mode.defaultTemperature,
    val fanPercent: Int = 40,
    val minutes: Int,
    val id: String = UUID.randomUUID().toString(),
) {
    /** Position on the night curve: -1 is the strongest cooling, +1 the strongest heat. */
    val level: Double
        get() = when (mode) {
            Mode.COOL -> -(0.25 + 0.75 * fanPercent / 100.0)
            Mode.DRY -> -0.15
            Mode.STANDBY, Mode.WAIT -> 0.0
            Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO -> 0.2 + 0.8 * (temperature - 38) / 48.0
        }

    /**
     * Longest this phase may run: the mode's own ceiling, and for Heat the BedJet's safety cut-off,
     * which comes sooner the hotter and faster the air.
     */
    val maxMinutes: Int
        get() = minOf(mode.maxMinutes, ProgramStep.longestMinutes(mode, temperature, fanPercent / 5 - 1))

    val step: ProgramStep
        get() = ProgramStep(mode, fanStep = fanPercent / 5 - 1, temperature = temperature, hours = minutes / 60, minutes = minutes % 60)
}

@Serializable
data class Schedule(
    val name: String,
    val phases: List<Phase>,
    /** Minutes after midnight; used to label the timeline. A schedule runs from whenever it is started. */
    val bedtime: Int = 22 * 60 + 30,
    val id: String = UUID.randomUUID().toString(),
) {
    val totalMinutes: Int get() = phases.sumOf { it.minutes }
    val program: StoredProgram get() = StoredProgram(name, phases.map { it.step })

    fun startMinute(of: Phase): Int = bedtime + phases.takeWhile { it.id != of.id }.sumOf { it.minutes }

    companion object {
        fun importing(program: StoredProgram, bedtime: Int = 22 * 60 + 30): Schedule {
            var clock = bedtime
            val phases = program.steps.map { step ->
                var minutes = step.hours * 60 + step.minutes
                // Steps that end at a time of day become durations, measured from the usual bedtime.
                if (step.endsAtClockTime) minutes = ((minutes - clock) % 1440 + 1440) % 1440
                clock += minutes
                Phase(step.mode, temperature = step.temperature, fanPercent = (step.fanStep + 1) * 5, minutes = maxOf(minutes, 1))
            }
            return Schedule(name = program.name.ifEmpty { "From BedJet" }, phases = phases, bedtime = bedtime)
        }

        val templates: List<Schedule> = listOf(
            Schedule("Cool Drift", listOf(
                Phase(Mode.COOL, fanPercent = 60, minutes = 45),
                Phase(Mode.COOL, fanPercent = 30, minutes = 300),
                Phase(Mode.COOL, fanPercent = 15, minutes = 90),
                Phase(Mode.HEAT, temperature = 54, fanPercent = 30, minutes = 30),
            )),
            Schedule("Warm Welcome", listOf(
                Phase(Mode.TURBO, fanPercent = 100, minutes = 10),
                Phase(Mode.HEAT, temperature = 68, fanPercent = 40, minutes = 30),
                Phase(Mode.COOL, fanPercent = 20, minutes = 360),
                Phase(Mode.HEAT, temperature = 58, fanPercent = 30, minutes = 40),
            )),
            Schedule("Hot Sleeper", listOf(
                Phase(Mode.COOL, fanPercent = 100, minutes = 60),
                Phase(Mode.COOL, fanPercent = 70, minutes = 240),
                Phase(Mode.COOL, fanPercent = 40, minutes = 150),
            )),
            Schedule("Winter Cocoon", listOf(
                Phase(Mode.HEAT, temperature = 72, fanPercent = 50, minutes = 30),
                Phase(Mode.EXTENDED_HEAT, temperature = 62, fanPercent = 30, minutes = 210),
                Phase(Mode.EXTENDED_HEAT, temperature = 58, fanPercent = 20, minutes = 240),
            )),
        )
    }
}

/** A schedule that is running on the BedJet right now. */
@Serializable
data class ActiveRun(val schedule: Schedule, val startedAt: Long) {
    val endsAt: Long get() = startedAt + schedule.totalMinutes * 60_000L

    fun progress(now: Long): Double =
        ((now - startedAt).toDouble() / maxOf(endsAt - startedAt, 1000L)).coerceIn(0.0, 1.0)

    data class Current(val phase: Phase, val index: Int, val minutesLeft: Int)

    /** When each phase ends, the last of them being the end of the night. */
    val phaseEnds: List<Long>
        get() {
            var end = startedAt
            return schedule.phases.map { phase ->
                end += phase.minutes * 60_000L
                end
            }
        }

    fun phase(now: Long): Current? {
        var elapsed = ((now - startedAt) / 60_000L).toInt()
        schedule.phases.forEachIndexed { index, phase ->
            if (elapsed < phase.minutes) return Current(phase, index, phase.minutes - elapsed)
            elapsed -= phase.minutes
        }
        return null
    }
}

/** What this phase is set to on a kind of BedJet, such as "75° · Fan 40%", worded like the schedule editor. */
fun Phase.setting(on: Generation, fahrenheit: Boolean): String =
    Format.setting(mode, temperature.takeIf { on.adjustsTemperature(mode) }, fanPercent, fahrenheit)

/** Modes a phase can use, in the order the editor offers them. */
val phaseModes = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.WAIT)
val remoteModes = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY)

val Mode.title: String
    get() = when (this) {
        Mode.STANDBY -> "Off"
        Mode.HEAT -> "Heat"
        Mode.TURBO -> "Turbo"
        Mode.EXTENDED_HEAT -> "Gentle heat"
        Mode.COOL -> "Cool"
        Mode.DRY -> "Dry"
        Mode.WAIT -> "Pause"
    }

val Mode.activity: String
    get() = when (this) {
        Mode.STANDBY -> "Off"
        Mode.HEAT -> "Heating"
        Mode.TURBO -> "Turbo heating"
        Mode.EXTENDED_HEAT -> "Gently heating"
        Mode.COOL -> "Cooling"
        Mode.DRY -> "Drying"
        Mode.WAIT -> "Paused"
    }

val Mode.defaultTemperature: Int
    get() = when (this) {
        Mode.HEAT -> 66
        Mode.EXTENDED_HEAT -> 58
        Mode.COOL -> 44
        Mode.DRY -> 60
        else -> temperatureRange.first
    }

/** Longest a single phase may run. Turbo and Heat limits follow the unit's own safety timers. */
val Mode.maxMinutes: Int
    get() = when (this) {
        Mode.TURBO -> 10
        Mode.HEAT -> 240
        else -> Limits.MAX_STEP_MINUTES
    }

object Format {
    /** Follows the phone's 12/24-hour setting; the activity updates it whenever Lull comes on screen. */
    var use24Hour by mutableStateOf(false)

    fun clock(minuteOfDay: Int): String {
        val minute = ((minuteOfDay % 1440) + 1440) % 1440
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        return LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    /** A duration in words, for TalkBack: "2 hours, 30 minutes" rather than "2 hr 30 min". */
    fun spokenDuration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts += if (hours == 1) "1 hour" else "$hours hours"
        if (rest > 0 || hours == 0) parts += if (rest == 1) "1 minute" else "$rest minutes"
        return parts.joinToString(", ")
    }

    /** A duration short enough for a small widget: "2h 25m", "45m", "7h". */
    fun shortDuration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        if (hours == 0) return "${rest}m"
        return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
    }

    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        if (hours == 0) return "$rest min"
        return if (rest == 0) "$hours hr" else "$hours hr $rest min"
    }

    fun degrees(halfCelsius: Int, fahrenheit: Boolean): Int {
        val celsius = halfCelsius / 2.0
        return (if (fahrenheit) celsius * 9 / 5 + 32 else celsius).roundHalfAwayFromZero()
    }

    /**
     * What a phase or the Remote is set to, such as "75° · Fan 40%", worded like the schedule editor. The temperature
     * is null for modes that don't let you set it.
     */
    fun setting(mode: Mode, temperature: Int?, fanPercent: Int, fahrenheit: Boolean): String {
        val parts = buildList {
            if (temperature != null) add("${degrees(temperature, fahrenheit)}°")
            if (mode.usesFan) add("Fan $fanPercent%")
        }
        return parts.ifEmpty { listOf("Air off") }.joinToString(" · ")
    }

    fun halfCelsius(fromDegrees: Int, fahrenheit: Boolean): Int {
        val celsius = if (fahrenheit) (fromDegrees - 32) * 5 / 9.0 else fromDegrees.toDouble()
        return (celsius * 2).roundHalfAwayFromZero()
    }

    /** Swift's `rounded()` rounds halves away from zero; Kotlin's `roundToInt` rounds them up. Match iOS. */
    private fun Double.roundHalfAwayFromZero(): Int = if (this < 0) -(-this).roundToInt() else roundToInt()
}
