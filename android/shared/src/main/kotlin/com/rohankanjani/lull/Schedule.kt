package com.rohankanjani.lull

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Limits
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.ProgramStep
import com.rohankanjani.lull.bedjetkit.StoredProgram
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
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
    /**
     * True when this phase keeps its length while a wake time stretches or shrinks the rest of the night. Left out when
     * false, so schedules read the same in versions of Lull before it.
     */
    val fixedLength: Boolean = false,
) {
    /**
     * How short and how long this phase may run once a wake time scales the night: its own length when it's fixed, or
     * anything from BedJet's 5 minutes to its longest.
     */
    val lengthLimits: IntRange get() = if (fixedLength) minutes..minutes else 5..maxOf(maxMinutes, 5)

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

    /** The step a BedJet 3 runs until a time of day, minutes after midnight, rather than for a length of time. */
    fun step(endingAt: Int): ProgramStep {
        val clock = (endingAt % 1440 + 1440) % 1440
        return step.copy(hours = clock / 60, minutes = clock % 60, endsAtClockTime = true)
    }
}

@Serializable
data class Schedule(
    val name: String,
    val phases: List<Phase>,
    /** Minutes after midnight; used to label the timeline. A schedule runs from whenever it is started. */
    val bedtime: Int = 22 * 60 + 30,
    val id: String = UUID.randomUUID().toString(),
    /**
     * Minutes after midnight the night ends at, if it has a wake time. Whenever the night is started, its phases stretch
     * or shrink in proportion to reach it. The lengths stored are a night from the usual bedtime, which the editor fits
     * to end at it, and they're the proportions other nights are scaled by.
     */
    val wakeTime: Int? = null,
) {
    val totalMinutes: Int get() = phases.sumOf { it.minutes }

    /** When the night ends if it runs from the usual bedtime for its set lengths. A new wake time starts here, so nothing moves. */
    val usualWakeTime: Int get() = (bedtime + totalMinutes) % 1440

    /**
     * What's saved to the BedJet. With a wake time the last step ends at that time of day, which a BedJet 3 keeps to by
     * its own clock; a V2 is handed it as a length, measured from when it is stored.
     */
    val program: StoredProgram
        get() {
            val steps = phases.map { it.step }.toMutableList()
            val last = phases.lastOrNull()
            if (wakeTime != null && last != null) steps[steps.lastIndex] = last.step(endingAt = wakeTime)
            return StoredProgram(name, steps)
        }

    fun startMinute(of: Phase): Int = bedtime + phases.takeWhile { it.id != of.id }.sumOf { it.minutes }

    /** Whether this is the schedule a program read from a BedJet was saved from or imported as, so it isn't kept twice. */
    fun matches(program: StoredProgram): Boolean = matches(imported = importing(program))

    /** [matches] for a program already imported, so a list of schedules can be checked against it once. */
    fun matches(imported: Schedule): Boolean {
        if (wakeTime != null && wakeTime == imported.wakeTime) {
            // Its lengths are scaled afresh each night, so only what each phase does has to agree.
            return phases.size == imported.phases.size && phases.zip(imported.phases).all { (a, b) ->
                a.mode == b.mode && a.temperature == b.temperature && a.fanPercent == b.fanPercent
            }
        }
        // Copies kept before Lull knew wake times hold the same steps, all as lengths.
        return program.steps == imported.program.steps || (wakeTime == null && phases.map { it.step } == imported.phases.map { it.step })
    }

    // Wake time

    /** How long a night started at [startingAt] minutes after midnight has until the wake time. Null without one. */
    fun minutesToWake(startingAt: Int): Int? {
        if (wakeTime == null || phases.isEmpty()) return null
        return ((wakeTime - startingAt) % 1440 + 1440) % 1440
    }

    /**
     * The night as it runs when started at [startingAt]: with a wake time, every phase stretches or shrinks in proportion
     * so the night ends then. A phase BedJet won't run longer or shorter stays at its limit, and the rest share the
     * difference. Unchanged without a wake time, or while it's more than [LONGEST_NIGHT] away, as during the day, so the
     * timeline and widgets show the night as set rather than one stretched to most of a day.
     */
    fun resolved(startingAt: Int): Schedule {
        val target = minutesToWake(startingAt) ?: return this
        if (target > LONGEST_NIGHT) return this
        val lengths = scale(phases.map { it.minutes }, phases.map { it.lengthLimits }, target)
        return copy(phases = phases.mapIndexed { index, phase -> phase.copy(minutes = lengths[index]) })
    }

    /** With a wake time, how long the night from the usual bedtime runs to reach it: the night the editor shows. Null without one. */
    val usualNight: Int? get() = minutesToWake(bedtime)

    /**
     * With a wake time, the night fitted to run from the usual bedtime to it, as Start Night would fit it then: the phases
     * without a fixed length stretch or shrink in proportion, so the lengths the editor shows are the ones a usual night
     * runs. [holding] keeps the length it was just given, within what leaves the others room. A night the phases can't
     * fill or fit in is left as it is, for the caption to say why, so a time passed on the way to another doesn't squash
     * them. Unchanged without a wake time.
     */
    fun fitted(holding: String? = null): Schedule {
        val night = usualNight ?: return this
        if (night > LONGEST_NIGHT) return this
        val set = phases.toMutableList()
        val limits = phases.map { it.lengthLimits }.toMutableList()
        val index = phases.indexOfFirst { it.id == holding }
        val choices = if (index >= 0) lengthChoices(phases[index]) else null
        if (choices != null) {
            val minutes = phases[index].minutes.coerceIn(choices)
            set[index] = phases[index].copy(minutes = minutes)
            limits[index] = minutes..minutes
        }
        if (limits.sumOf { it.first } > night || night > limits.sumOf { it.last }) return this
        val lengths = scale(set.map { it.minutes }, limits, night)
        return copy(phases = set.mapIndexed { i, phase -> phase.copy(minutes = lengths[i]) })
    }

    /**
     * The lengths the editor offers [phase]. With a wake time, the ones that leave the night from the usual bedtime room
     * to end at it, since the other phases that stretch or shrink can't go shorter or longer than BedJet lets them. Null
     * when it's the only phase that stretches or shrinks, so its length is whatever the others leave.
     */
    fun lengthChoices(phase: Phase): IntRange? {
        val longest = maxOf(phase.maxMinutes, 5)
        val night = usualNight
        if (night == null || night > LONGEST_NIGHT) return 5..longest
        val others = phases.filter { it.id != phase.id }
        val stretching = others.filter { !it.fixedLength }
        // With nothing else to take up the difference, a phase that stretches is the rest of the night, and a fixed one
        // can't be fitted anyway.
        if (stretching.isEmpty()) return if (phase.fixedLength) 5..longest else null
        val left = night - others.filter { it.fixedLength }.sumOf { it.minutes }
        val shortest = maxOf(left - stretching.sumOf { it.lengthLimits.last }, 5)
        val room = minOf(left - stretching.sumOf { it.lengthLimits.first }, longest)
        // When no length would let the night fit, any will do: the caption says why it can't.
        return if (shortest <= room) shortest..room else 5..longest
    }

    /**
     * [lengthChoices] in BedJet's 5-minute steps, for the Duration slider. Null when there's less than a step to choose
     * from, so the sheet shows the length without a slider.
     */
    fun durationSteps(phase: Phase): IntRange? {
        val choices = lengthChoices(phase) ?: return null
        val low = (choices.first + 4) / 5 * 5
        val high = choices.last / 5 * 5
        return if (low < high) low..high else null
    }

    /** Why the night from the usual bedtime can't be fitted to end at the wake time, if it can't. */
    val usualNightProblem: String? get() {
        val wakeTime = wakeTime ?: return null
        val night = usualNight ?: return null
        if (night > LONGEST_NIGHT) {
            return "${Format.clock(wakeTime)} is more than ${Format.duration(LONGEST_NIGHT)} after ${Format.clock(bedtime)}. " +
                "Set a later bedtime or an earlier wake time."
        }
        return wakeProblem(bedtime)
    }

    /** Why a schedule with a wake time and only fixed phases can't stretch or shrink to reach it, if that's the case. */
    val onlyFixedPhasesProblem: String? get() {
        val wakeTime = wakeTime ?: return null
        if (phases.isEmpty() || !phases.all { it.fixedLength }) return null
        return "Every phase has a fixed length, so the night can't stretch or shrink to end at ${Format.clock(wakeTime)}. Let a phase change length."
    }

    /**
     * What the editor says about the phases: without a wake time, that they run for their set lengths and when the night
     * ends; with one, what their lengths are for and how they reach it.
     */
    val phasesCaption: String get() {
        val wakeTime = wakeTime ?: return "Each phase runs for its set length, so the night ends around ${Format.clock(usualWakeTime)}."
        // When the night can't be fitted, say why rather than how it fits.
        (onlyFixedPhasesProblem ?: usualNightProblem)?.let { return it }
        val wake = Format.clock(wakeTime)
        return "These lengths are for a night from ${Format.clock(bedtime)}. Started earlier or later, the phases stretch or " +
            "shrink in proportion to end at $wake, apart from any marked Fixed length."
    }

    /** With a wake time, whether [phase] stretches or shrinks with the night, or why it can't. */
    fun scalingNote(phase: Phase): String? {
        val wakeTime = wakeTime ?: return null
        onlyFixedPhasesProblem?.let { return it }
        val wake = Format.clock(wakeTime)
        if (phase.fixedLength) return "Stays ${Format.duration(phase.minutes)} while the rest of the night stretches or shrinks to end at $wake."
        // The only phase that stretches or shrinks is whatever the rest leave, a phase on its own the whole night.
        if (phases.all { it.id == phase.id || it.fixedLength }) {
            return if (phases.size == 1) "Fills the night, from ${Format.clock(bedtime)} to $wake on a usual night."
            else "Fills the rest of the night, so it's as long as the other phases leave before $wake."
        }
        return "Stretches or shrinks with the rest of the night to end at $wake. Lengthening it shortens the others that aren't fixed."
    }

    /** Why the night can't end at its wake time when started at [startingAt], if it can't. */
    fun wakeProblem(startingAt: Int): String? {
        val wakeTime = wakeTime ?: return null
        val target = minutesToWake(startingAt) ?: return null
        val wake = Format.clock(wakeTime)
        if (target > LONGEST_NIGHT) return "$wake is more than ${Format.duration(LONGEST_NIGHT)} away. Start the night nearer to it."
        if (target != totalMinutes) onlyFixedPhasesProblem?.let { return it }
        if (target < phases.sumOf { it.lengthLimits.first }) {
            return "Only ${Format.duration(target)} is left before $wake, less than ${if (phases.size == 1) "a phase needs" else "these phases need"}. " +
                "Set a later wake time."
        }
        val longest = phases.sumOf { it.lengthLimits.last }
        if (target > longest) {
            return "To end at $wake, the night would run ${Format.duration(target)}, but these phases can run for up to " +
                "${Format.duration(longest)} in all. Add a phase or set an earlier wake time."
        }
        return null
    }

    companion object {
        /** Longest Lull stretches a night to reach its wake time, so a start well after it isn't taken as a night nearly a day long. */
        const val LONGEST_NIGHT = 16 * 60

        /**
         * Whole minutes in proportion to [weights] that add up to [total], each kept within its limits. Lengths at a limit
         * are set aside and the rest share what's left; the minutes lost to rounding go to the lengths that lost most. When
         * the limits can't add up to [total], each length is as near it as its limits allow.
         */
        fun scale(weights: List<Int>, limits: List<IntRange>, total: Int): List<Int> {
            val fixed = arrayOfNulls<Int>(weights.size)
            val ideal = DoubleArray(weights.size)
            while (true) {
                val free = weights.indices.filter { fixed[it] == null }
                if (free.isEmpty()) break
                val left = (total - fixed.filterNotNull().sum()).toDouble()
                val weight = free.sumOf { maxOf(weights[it], 1) }.toDouble()
                for (index in free) ideal[index] = left * maxOf(weights[index], 1) / weight
                val under = free.filter { ideal[it] < limits[it].first }
                val over = free.filter { ideal[it] > limits[it].last }
                if (under.isEmpty() && over.isEmpty()) break
                // Only the side that's further out is held at its limits this round: raising the short ones leaves less for
                // the rest, which can bring the long ones back within theirs, and the other way round.
                val short = under.sumOf { limits[it].first - ideal[it] }
                val long = over.sumOf { ideal[it] - limits[it].last }
                if (short >= long) for (index in under) fixed[index] = limits[index].first
                if (long >= short) for (index in over) fixed[index] = limits[index].last
            }
            val lengths = weights.indices.map { fixed[it] ?: floor(ideal[it]).toInt() }.toMutableList()
            var short = total - lengths.sum()
            for (index in weights.indices.filter { fixed[it] == null }.sortedByDescending { ideal[it] - floor(ideal[it]) }) {
                if (short <= 0) break
                lengths[index] += 1
                short -= 1
            }
            return lengths
        }

        fun importing(program: StoredProgram, bedtime: Int = 22 * 60 + 30): Schedule {
            var clock = bedtime
            val phases = program.steps.map { step ->
                var minutes = step.hours * 60 + step.minutes
                // Steps that end at a time of day become durations, measured from the usual bedtime.
                if (step.endsAtClockTime) minutes = ((minutes - clock) % 1440 + 1440) % 1440
                clock += minutes
                Phase(step.mode, temperature = step.temperature, fanPercent = (step.fanStep + 1) * 5, minutes = maxOf(minutes, 1))
            }
            // A last step that ends at a time of day is a wake time, as Lull saves one.
            val wakeTime = program.steps.lastOrNull()?.takeIf { it.endsAtClockTime }?.let { it.hours * 60 + it.minutes }
            return Schedule(name = program.name.ifEmpty { "From BedJet" }, phases = phases, bedtime = bedtime, wakeTime = wakeTime)
        }

        /** What New Schedule starts from: one long cool phase, with a wake time where its night ends. */
        fun blank(): Schedule {
            val schedule = Schedule("New schedule", listOf(Phase(Mode.COOL, fanPercent = 40, minutes = 240)))
            return schedule.copy(wakeTime = schedule.usualWakeTime)
        }

        /**
         * The schedules Lull starts with. Each has a wake time where its night ends from the usual bedtime, so it plans the
         * same night it always did, and stretches or shrinks when started earlier or later. Their short opening phases and
         * closing warm-ups have a fixed length, so only the long middle of the night changes.
         */
        val templates: List<Schedule> = listOf(
            Schedule("Cool Drift", listOf(
                Phase(Mode.COOL, fanPercent = 60, minutes = 45, fixedLength = true),
                Phase(Mode.COOL, fanPercent = 30, minutes = 300),
                Phase(Mode.COOL, fanPercent = 15, minutes = 90),
                Phase(Mode.HEAT, temperature = 54, fanPercent = 30, minutes = 30, fixedLength = true),
            ), wakeTime = 6 * 60 + 15),
            Schedule("Warm Welcome", listOf(
                Phase(Mode.TURBO, fanPercent = 100, minutes = 10, fixedLength = true),
                Phase(Mode.HEAT, temperature = 68, fanPercent = 40, minutes = 30, fixedLength = true),
                Phase(Mode.COOL, fanPercent = 20, minutes = 360),
                Phase(Mode.HEAT, temperature = 58, fanPercent = 30, minutes = 40, fixedLength = true),
            ), wakeTime = 5 * 60 + 50),
            Schedule("Hot Sleeper", listOf(
                Phase(Mode.COOL, fanPercent = 100, minutes = 60, fixedLength = true),
                Phase(Mode.COOL, fanPercent = 70, minutes = 240),
                Phase(Mode.COOL, fanPercent = 40, minutes = 150),
            ), wakeTime = 6 * 60),
            Schedule("Winter Cocoon", listOf(
                Phase(Mode.HEAT, temperature = 72, fanPercent = 50, minutes = 30, fixedLength = true),
                Phase(Mode.EXTENDED_HEAT, temperature = 62, fanPercent = 30, minutes = 210),
                Phase(Mode.EXTENDED_HEAT, temperature = 58, fanPercent = 20, minutes = 240),
            ), wakeTime = 6 * 60 + 30),
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
    /** Follows the phone's or watch's 12/24-hour setting, as [followClockSetting] last read it. */
    var use24Hour by mutableStateOf(false)

    /**
     * Reads the 12/24-hour setting. iOS formats times by the system's setting itself; here each place that draws times
     * reads it first: Lull as it starts and comes on screen, and the watch app, its tile and its complication.
     */
    fun followClockSetting(context: Context) {
        use24Hour = DateFormat.is24HourFormat(context)
    }

    /** Minutes after midnight at [millis], in the phone's time zone. */
    fun minuteOfDay(millis: Long): Int {
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /**
     * When the clock next reads [minuteOfDay] after [after]: the start of that minute, up to a day later. The minute
     * [after] is in counts as tomorrow's.
     */
    fun nextTime(minuteOfDay: Int, after: Long): Long {
        val minute = ((minuteOfDay % 1440) + 1440) % 1440
        // By the calendar rather than a count of milliseconds, so a night that crosses a daylight-saving change still ends
        // at the time on the clock. A time the clocks skip moves on by the hour skipped, keeping its minutes, as on iOS.
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(after).atZone(zone)
        var next = start.toLocalDate().atTime(minute / 60, minute % 60).atZone(zone)
        if (!next.isAfter(start)) next = start.toLocalDate().plusDays(1).atTime(minute / 60, minute % 60).atZone(zone)
        return next.toInstant().toEpochMilli()
    }

    /**
     * Whole minutes from [from] until the clock next reads [until], to the nearest minute, so a countdown of that long ends
     * within half a minute of it.
     */
    fun minutes(until: Int, from: Long): Int = Math.round((nextTime(until, from) - from) / 60_000.0).toInt()

    /**
     * The minute of the day a countdown of [afterSeconds] from [from] ends, to the nearest minute, so a time worked out from
     * the BedJet's seconds left holds still as they tick.
     */
    fun endMinute(afterSeconds: Int, from: Long): Int = minuteOfDay(from + (afterSeconds + 30) * 1000L)

    /** [minutes] from [from], lengthened so the countdown ends on a multiple of [step] minutes: 6:30 or 6:45, not 6:37. */
    fun minutesEndingOnStep(minutes: Int, step: Int, from: Long): Int {
        val end = minuteOfDay(from) + minutes
        return minutes + (step - end % step) % step
    }

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
