package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToLong

/** One thing the BedJet did, kept so the morning after can be checked against how the night felt. */
@Serializable
data class HistoryEntry(
    /** Milliseconds since 1970. */
    val date: Long,
    val event: Event,
    /** What the BedJet measured in the room at the time, in half-degrees Celsius. */
    val room: Int? = null,
    /**
     * Lull lets go of the BedJet when it leaves the screen, so a change made meanwhile, on the BedJet's
     * own remote or another phone, is only seen when Lull is opened again and is dated then.
     */
    val noticedLater: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
) {
    @Serializable
    sealed interface Event {
        /** The air the BedJet settled on, whichever phone or remote chose it. */
        @Serializable @SerialName("air")
        data class Air(val mode: Mode, val temperature: Int, val fanPercent: Int) : Event

        @Serializable @SerialName("off")
        data class Off(val timerRanOut: Boolean) : Event

        /** Names and phases are missing for schedules started from another phone. */
        @Serializable @SerialName("scheduleStarted")
        data class ScheduleStarted(val name: String?, val phases: List<Phase>?) : Event

        @Serializable @SerialName("scheduleEnded")
        data class ScheduleEnded(val name: String?, val ending: Ending) : Event
    }

    @Serializable
    sealed interface Ending {
        @Serializable @SerialName("byYou")
        data object ByYou : Ending

        @Serializable @SerialName("finished")
        data object Finished : Ending

        @Serializable @SerialName("early")
        data class Early(val minutes: Int) : Ending
    }

    companion object {
        /** A few believable nights ending last night, for screenshots and trying History without a BedJet. */
        fun samples(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<HistoryEntry> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            fun at(nightsAgo: Int, hour: Int, minute: Int, event: Event, room: Int): HistoryEntry {
                // Hours past midnight belong to the morning after the evening.
                val day = today.plusDays((if (hour < 12) 1 - nightsAgo else -nightsAgo).toLong())
                val date = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                return HistoryEntry(date, event, room)
            }
            val coolDrift = Schedule.templates[0]
            val warmWelcome = Schedule.templates[1]
            return listOf(
                at(3, 22, 45, Event.ScheduleStarted(warmWelcome.name, warmWelcome.phases), room = 40),
                at(3, 7, 5, Event.ScheduleEnded(warmWelcome.name, Ending.Finished), room = 39),

                at(2, 22, 15, Event.Air(Mode.HEAT, 70, 50), room = 41),
                at(2, 23, 0, Event.Air(Mode.COOL, 44, 40), room = 43),
                at(2, 2, 30, Event.Air(Mode.COOL, 44, 20), room = 42),
                at(2, 5, 45, Event.Air(Mode.HEAT, 58, 30), room = 40),
                at(2, 6, 30, Event.Off(timerRanOut = true), room = 41),

                at(1, 22, 30, Event.ScheduleStarted(coolDrift.name, coolDrift.phases), room = 46),
                at(1, 1, 50, Event.Air(Mode.COOL, 44, 70), room = 47),
                at(1, 7, 15, Event.ScheduleEnded(coolDrift.name, Ending.Finished), room = 44),
            )
        }
    }
}

/** Everything recorded between one noon and the next, so 3 a.m. belongs with the evening before. */
data class Night(
    /** The evening the night began, as `yyyy-MM-dd`. */
    val id: String,
    val evening: LocalDate,
    /** Oldest first, so the night reads in the order it happened. */
    val entries: List<HistoryEntry>,
) {
    /** Schedules started this night, in order, for naming the night at a glance. */
    val scheduleNames: List<String>
        get() = entries.mapNotNull { (it.event as? HistoryEntry.Event.ScheduleStarted)?.let { started -> started.name ?: "A schedule" } }

    /**
     * The night redrawn as phases, so it can be drawn like a schedule. Each entry lasts until the next. A schedule
     * plays out its own phases, and a change made during one lasts until the schedule moves to its next phase.
     */
    val phases: List<Phase>
        get() {
            val phases = mutableListOf<Phase>()
            var schedule: Pair<Long, List<Phase>>? = null
            entries.forEachIndexed { index, entry ->
                val next = entries.getOrNull(index + 1)?.date
                when (val event = entry.event) {
                    is HistoryEntry.Event.ScheduleStarted -> {
                        val schedulePhases = event.phases
                        if (schedulePhases != null) {
                            schedule = entry.date to schedulePhases
                            val end = next ?: (entry.date + schedulePhases.sumOf { it.minutes } * 60_000L)
                            phases += play(schedulePhases, startedAt = entry.date, from = entry.date, to = end)
                        } else {
                            schedule = null
                            if (next != null) phases += Phase(Mode.WAIT, minutes = minutes(entry.date, next))
                        }
                    }
                    is HistoryEntry.Event.Air -> {
                        // How long the last setting lasted is unknown, so it gets a short stretch to be visible.
                        val end = next ?: (entry.date + 30 * 60_000L)
                        fun tweak(until: Long) = Phase(event.mode, temperature = event.temperature, fanPercent = event.fanPercent, minutes = minutes(entry.date, until))
                        val running = schedule
                        val phaseEnds = running?.let { phaseEnd(it.second, startedAt = it.first, after = entry.date) }
                        if (running == null || phaseEnds == null || phaseEnds >= end) {
                            phases += tweak(end)
                        } else {
                            phases += tweak(phaseEnds)
                            phases += play(running.second, startedAt = running.first, from = phaseEnds, to = end)
                        }
                    }
                    is HistoryEntry.Event.Off, is HistoryEntry.Event.ScheduleEnded -> {
                        schedule = null
                        if (next != null) phases += Phase(Mode.WAIT, minutes = minutes(entry.date, next))
                    }
                }
            }
            return phases.filter { it.minutes > 0 }
        }

    companion object {
        fun group(entries: List<HistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Night> =
            entries.groupBy { evening(it.date, zone) }
                .map { (evening, entries) -> Night(id = evening.toString(), evening = evening, entries = entries.sortedBy { it.date }) }
                .sortedByDescending { it.evening }

        fun evening(of: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            Instant.ofEpochMilli(of - 12 * 3_600_000L).atZone(zone).toLocalDate()

        /** The stretch of a schedule between two times, idle once the schedule is over. */
        private fun play(schedule: List<Phase>, startedAt: Long, from: Long, to: Long): List<Phase> {
            val played = mutableListOf<Phase>()
            var phaseStart = startedAt
            for (phase in schedule) {
                val phaseEnd = phaseStart + phase.minutes * 60_000L
                if (phaseEnd > from && phaseStart < to) {
                    played += phase.copy(minutes = minutes(maxOf(phaseStart, from), minOf(phaseEnd, to)))
                }
                phaseStart = phaseEnd
            }
            if (phaseStart < to) played += Phase(Mode.WAIT, minutes = minutes(maxOf(phaseStart, from), to))
            return played
        }

        private fun phaseEnd(schedule: List<Phase>, startedAt: Long, after: Long): Long? {
            var phaseEnd = startedAt
            for (phase in schedule) {
                phaseEnd += phase.minutes * 60_000L
                if (phaseEnd > after) return phaseEnd
            }
            return null
        }

        private fun minutes(start: Long, end: Long): Int = maxOf(((end - start) / 60_000.0).roundToLong().toInt(), 0)
    }
}

