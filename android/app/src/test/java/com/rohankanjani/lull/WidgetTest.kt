package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the widgets work out from the snapshot the app saves: when to redraw, which night was last night,
 * and how taps and controls find their way back into the app. Mirrors WidgetTests.swift.
 */
class WidgetTest {
    private val zone = ZoneId.systemDefault()
    /** 11:30 p.m., an hour into tonight's schedule. */
    private val now = LocalDateTime.of(2026, 10, 21, 23, 30).atZone(zone).toInstant().toEpochMilli()
    private val schedule = Schedule.templates[0]

    private fun running(startedMinutesAgo: Int) =
        WidgetSnapshot(tonight = schedule, run = ActiveRun(schedule, startedAt = now - startedMinutesAgo * 60_000L))

    @Test
    fun redrawsThroughTheNightAndAtEveryPhaseChange() {
        val snapshot = running(startedMinutesAgo = 60)
        val run = snapshot.run!!
        val dates = snapshot.redraws(now)
        assertEquals(now, dates.first())
        assertEquals("the last redraw shows the night has ended", run.endsAt, dates.last())
        for (end in run.phaseEnds.filter { it > now }) assertTrue("redraws when phase ending $end does", end in dates)
        assertFalse("the first phase ended before now", run.phaseEnds[0] in dates)
        dates.zipWithNext().forEach { (earlier, later) -> assertTrue(later - earlier <= 300_000L) }
    }

    @Test
    fun drawsOnceWhenNothingIsRunning() {
        assertEquals(listOf(now), WidgetSnapshot(tonight = schedule).redraws(now))
        // A night that has already ended is the same as none.
        assertEquals(listOf(now), running(startedMinutesAgo = schedule.totalMinutes + 1).redraws(now))
    }

    @Test
    fun phaseEndsAddUpToTheNight() {
        val run = running(startedMinutesAgo = 0).run!!
        assertEquals(schedule.phases.size, run.phaseEnds.size)
        assertEquals(run.endsAt, run.phaseEnds.last())
        assertEquals(run.startedAt + schedule.phases[0].minutes * 60_000L, run.phaseEnds[0])
    }

    @Test
    fun lastNightSkipsTheNightStillRunning() {
        val started = HistoryEntry(now - 3_600_000L, HistoryEntry.Event.ScheduleStarted(schedule.name, schedule.phases))
        var snapshot = running(startedMinutesAgo = 60).copy(
            recentHistory = WidgetSnapshot.recent(HistoryEntry.samples(now) + started, before = now),
        )

        val lastNight = snapshot.lastNight(now)!!
        assertEquals("2026-10-20", lastNight.id)
        assertEquals(listOf("Cool Drift"), lastNight.scheduleNames)

        // Ended early, tonight is still tonight, not last night.
        snapshot = snapshot.copy(run = null)
        assertEquals("2026-10-20", snapshot.lastNight(now)?.id)
        snapshot = snapshot.copy(run = running(startedMinutesAgo = 60).run)

        // Once tonight's schedule is over, it is the latest night.
        val morning = now + schedule.totalMinutes * 60_000L
        assertEquals("2026-10-21", snapshot.lastNight(morning)?.id)
    }

    @Test
    fun keepsOnlyRecentHistory() {
        val recent = WidgetSnapshot.recent(HistoryEntry.samples(now), before = now)
        assertFalse(recent.isEmpty())
        assertTrue(recent.all { now - it.date <= 48 * 3_600_000L })
    }

    @Test
    fun survivesASaveAndLoad() {
        val snapshot = running(startedMinutesAgo = 30).copy(recentHistory = HistoryEntry.samples(now), generation = Generation.V2)
        val data = AppModel.json.encodeToString(WidgetSnapshot.serializer(), snapshot)
        assertEquals(snapshot, AppModel.json.decodeFromString(WidgetSnapshot.serializer(), data))
    }

    @Test
    fun phaseDetailFollowsTheBedJet() {
        var snapshot = WidgetSnapshot(useFahrenheit = true)
        val cool = Phase(Mode.COOL, fanPercent = 40, minutes = 60)
        val heat = Phase(Mode.HEAT, temperature = 66, fanPercent = 30, minutes = 30)
        assertEquals("91° · Fan 30%", snapshot.detail(heat))
        assertEquals("Air off", snapshot.detail(Phase(Mode.WAIT, minutes = 30)))
        // A V2 only sets a temperature for Heat.
        snapshot = snapshot.copy(generation = Generation.V2)
        assertEquals("Fan 40%", snapshot.detail(cool))
        assertEquals("91° · Fan 30%", snapshot.detail(heat))
    }

    @Test
    fun widgetLinksOpenTheirTab() {
        for (tab in LullTab.entries) assertEquals(tab, LullTab.from(tab.link))
        assertNull(LullTab.from("lull://settings"))
        assertNull(LullTab.from("https://schedule"))
        // A link can also name its BedJet.
        val link = LullTab.SCHEDULE.link(showing = "E9D74769-E58C-42BE-BBA6-E7388B99FF52")
        assertEquals(LullTab.SCHEDULE, LullTab.from(link))
        assertEquals("E9D74769-E58C-42BE-BBA6-E7388B99FF52", LullTab.bedJet(link))
        assertNull(LullTab.bedJet(LullTab.REMOTE.link))
    }

    @Test
    fun commandsWaitForTheApp() {
        val previous = AppCommands.handler
        try {
            AppCommands.handler = null
            val received = mutableListOf<AppCommands.Command>()
            AppCommands.send(AppCommands.Command.StartNight)
            AppCommands.send(AppCommands.Command.Show(LullTab.HISTORY))
            assertTrue(received.isEmpty())
            AppCommands.handler = { received += it }
            assertEquals(listOf(AppCommands.Command.StartNight, AppCommands.Command.Show(LullTab.HISTORY)), received)
            AppCommands.send(AppCommands.Command.Show(LullTab.REMOTE))
            assertEquals(AppCommands.Command.Show(LullTab.REMOTE), received.last())
        } finally {
            AppCommands.handler = previous
        }
    }
}
