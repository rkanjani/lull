package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * History lists each phase a schedule moved on to, worked out from when it started, since Lull is
 * usually closed while the BedJet plays the schedule out.
 */
class HistoryTest {
    /** 10:30 p.m., when the schedule starts. */
    private val bedtime = LocalDateTime.of(2026, 10, 21, 22, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Cool Drift: phases of 45, 300, 90 and 30 minutes. */
    private val schedule = Schedule.templates[0]

    private fun at(minutes: Int): Long = bedtime + minutes * 60_000L

    private fun night(entries: List<HistoryEntry>): Night = Night.group(entries).first()

    private val started = HistoryEntry(bedtime, HistoryEntry.Event.ScheduleStarted(schedule.name, schedule.phases), room = 44)

    private fun phaseNumbers(timeline: List<HistoryEntry>): List<Int> =
        timeline.mapNotNull { (it.event as? HistoryEntry.Event.PhaseStarted)?.number }

    @Test
    fun listsEveryPhaseChangeSoFar() {
        // Opened in the morning, ten minutes into the last phase.
        val timeline = night(listOf(started)).timeline(until = at(445))
        assertEquals(listOf(bedtime, at(45), at(345), at(435)), timeline.map { it.date })
        assertEquals(listOf(2, 3, 4), phaseNumbers(timeline))
        val last = timeline.last().event as HistoryEntry.Event.PhaseStarted
        assertEquals(4, last.number)
        assertEquals(4, last.of)
        assertEquals(Mode.HEAT, last.phase.mode)
    }

    @Test
    fun leavesOutPhasesStillToCome() {
        val timeline = night(listOf(started)).timeline(until = at(100))
        assertEquals(listOf(2), phaseNumbers(timeline))
    }

    @Test
    fun stopsWhereTheScheduleEnded() {
        val ended = HistoryEntry(at(60), HistoryEntry.Event.ScheduleEnded(schedule.name, HistoryEntry.Ending.ByYou))
        val endedByYou = night(listOf(started, ended)).timeline(until = at(445))
        assertEquals(listOf(2), phaseNumbers(endedByYou))

        val off = HistoryEntry(at(400), HistoryEntry.Event.Off(timerRanOut = false))
        val turnedOff = night(listOf(started, off)).timeline(until = at(445))
        assertEquals(listOf(2, 3), phaseNumbers(turnedOff))

        // A finished schedule is ended by its last phase, not by another phase change.
        val finished = HistoryEntry(at(schedule.totalMinutes), HistoryEntry.Event.ScheduleEnded(schedule.name, HistoryEntry.Ending.Finished))
        val timeline = night(listOf(started, finished)).timeline(until = at(600))
        assertEquals(listOf(2, 3, 4), phaseNumbers(timeline))
        assertEquals(finished, timeline.last())
    }

    @Test
    fun stopsAtTheLastThingSeenWhenTheEndIsNoticedLater() {
        // Lull saw a change of air at 1 a.m. and was closed. The BedJet stopped some time after, and Lull only saw
        // that when it reopened at 5:50 a.m., past the start of phase 4.
        val tweak = HistoryEntry(at(150), HistoryEntry.Event.Air(Mode.COOL, 44, 70))
        val stoppedEarly = HistoryEntry(at(440), HistoryEntry.Event.ScheduleEnded(schedule.name, HistoryEntry.Ending.Early(25)), noticedLater = true)
        assertEquals(listOf(2), phaseNumbers(night(listOf(started, tweak, stoppedEarly)).timeline(until = at(445))))

        // A change of air seen on reopening is dated then too, so it doesn't count as seeing the BedJet still running.
        val changedMeanwhile = HistoryEntry(at(440), HistoryEntry.Event.Air(Mode.HEAT, 70, 30), noticedLater = true)
        val turnedOff = HistoryEntry(at(441), HistoryEntry.Event.Off(timerRanOut = false), noticedLater = true)
        assertEquals(listOf(2), phaseNumbers(night(listOf(started, tweak, changedMeanwhile, turnedOff)).timeline(until = at(445))))

        // Closed all night: nothing says how far the schedule got.
        assertEquals(emptyList<Int>(), phaseNumbers(night(listOf(started, stoppedEarly)).timeline(until = at(445))))
    }

    @Test
    fun aChangeOfAirLeavesTheScheduleRunning() {
        val tweak = HistoryEntry(at(100), HistoryEntry.Event.Air(Mode.COOL, 44, 70))
        val timeline = night(listOf(started, tweak)).timeline(until = at(445))
        assertEquals(listOf(bedtime, at(45), at(100), at(345), at(435)), timeline.map { it.date })
    }

    @Test
    fun phaseChangesAreNotSavedAndKeepTheirRows() {
        val tonight = night(listOf(started))
        assertEquals("only what happened is kept, so the night's curve is unchanged", listOf(started), tonight.entries)
        val ids = tonight.timeline(until = at(445)).map { it.id }
        assertEquals(ids, tonight.timeline(until = at(445)).map { it.id })
        assertEquals(4, ids.toSet().size)
    }

    @Test
    fun nightsSavedBeforePhaseRowsStillLoad() {
        // A schedule of two phases and an off 100 minutes in, as Lull saved them before phase rows existed.
        val saved = """
            [{"date":1792636200000,"room":44,"id":"0c6e4c1a-5b1f-4c55-9e55-7a1c0d2f3b01",
              "event":{"type":"scheduleStarted","name":"Cool Drift","phases":[
                {"mode":"COOL","temperature":44,"fanPercent":60,"minutes":45,"id":"0c6e4c1a-5b1f-4c55-9e55-7a1c0d2f3b02"},
                {"mode":"COOL","temperature":44,"fanPercent":30,"minutes":300,"id":"0c6e4c1a-5b1f-4c55-9e55-7a1c0d2f3b03"}]}},
             {"date":1792642200000,"id":"0c6e4c1a-5b1f-4c55-9e55-7a1c0d2f3b04","event":{"type":"off","timerRanOut":false}}]
        """
        val entries = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(HistoryEntry.serializer()), saved)
        val lastNight = Night(id = "saved", evening = Night.evening(of = entries[0].date), entries = entries)
        val timeline = lastNight.timeline(until = entries[0].date + 600 * 60_000L)
        assertEquals(listOf(entries[0].date, entries[0].date + 45 * 60_000L, entries[1].date), timeline.map { it.date })
        assertEquals(listOf(2), phaseNumbers(timeline))
    }

    @Test
    fun scheduleFromAnotherPhoneHasNoPhases() {
        val elsewhere = HistoryEntry(bedtime, HistoryEntry.Event.ScheduleStarted(null, null))
        val timeline = night(listOf(elsewhere)).timeline(until = at(445))
        assertEquals(listOf(elsewhere), timeline)
    }
}
