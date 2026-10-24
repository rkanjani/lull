package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** What a running BedJet shows in its Live Update, and when that is worth redrawing. The twin of `LiveStatusTests.swift`. */
class LiveStatusTest {
    /** 11:30 p.m., an hour into tonight's schedule. */
    private val now = LocalDateTime.of(2026, 10, 21, 23, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val schedule = Schedule.templates[0]

    private fun run(startedMinutesAgo: Int) = ActiveRun(schedule, startedAt = now - startedMinutesAgo * 60_000L)

    private fun status(mode: Mode, target: Int = 66, fanStep: Int = 7, seconds: Int = 2700, programStep: Int? = 0) = DeviceStatus(
        remainingSeconds = seconds, actualTemperature = 50, targetTemperature = target, mode = mode, fanStep = fanStep,
        maxRuntimeMinutes = 720, minTemperature = 38, maxTemperature = 80, ambientTemperature = 45, lastShutdownReason = 0,
        extra = programStep?.let { DeviceStatus.Extra(ledEnabled = true, beepsMuted = false, programStep = it, notification = 0) },
    )

    @Test
    fun aNightRunningFromThisPhoneShowsTheNight() {
        val night = run(startedMinutesAgo = 60)
        // While a night runs, the BedJet reports the phase's own mode and timer; the night is what's shown.
        assertEquals(LiveStatus.Night(night), LiveStatus.of(night, status(Mode.COOL, programStep = 1), generation = Generation.V3, now = now))
        assertEquals(LiveStatus.Night(night), LiveStatus.of(night, null, generation = Generation.V3, now = now))
    }

    @Test
    fun aFinishedNightShowsNothing() {
        val ended = run(startedMinutesAgo = schedule.totalMinutes + 1)
        assertNull(LiveStatus.of(ended, status(Mode.STANDBY, seconds = 0), generation = Generation.V3, now = now))
    }

    @Test
    fun airFromTheRemoteShowsUntilItsTimerRunsOut() {
        val live = LiveStatus.of(null, status(Mode.HEAT, target = 66, fanStep = 7, seconds = 2700), generation = Generation.V3, now = now)
        assertEquals(LiveStatus.Air(Mode.HEAT, temperature = 66, fanPercent = 40, endsAt = now + 2_700_000), live)
        assertEquals(now + 2_700_000, live?.endsAt)
        assertEquals(now + 2_700_000, live?.staleAt(now))
    }

    @Test
    fun aReportFromEarlierCountsFromWhenItArrived() {
        // Lull keeps the last report while another phone has the BedJet or Lull is off screen.
        val reported = now - 600_000
        val live = LiveStatus.of(null, status(Mode.HEAT, seconds = 2700), reported, generation = Generation.V3, now = now)
        assertEquals(reported + 2_700_000, live?.endsAt)
        // A timer that has run out since is over.
        assertNull(LiveStatus.of(null, status(Mode.HEAT, seconds = 300), reported, generation = Generation.V3, now = now))
    }

    @Test
    fun offShowsNothing() {
        assertNull(LiveStatus.of(null, status(Mode.STANDBY, seconds = 0), generation = Generation.V3, now = now))
        assertNull(LiveStatus.of(null, null, generation = Generation.V3, now = now))
    }

    @Test
    fun aScheduleFromAnotherPhoneIsNotShown() {
        // Its phases aren't known here, and its timer only counts down the step it's on.
        assertNull(LiveStatus.of(null, status(Mode.COOL, programStep = 2), generation = Generation.V3, now = now))
        // Until the second half of the report arrives, it can't be told from air started at the Remote.
        assertNull(LiveStatus.of(null, status(Mode.COOL, programStep = null), generation = Generation.V3, now = now))
    }

    @Test
    fun temperatureIsLeftOutWhereItCantBeSet() {
        val cool = LiveStatus.of(null, status(Mode.COOL), generation = Generation.V3, now = now)
        assertEquals(LiveStatus.Air(Mode.COOL, temperature = 66, fanPercent = 40, endsAt = now + 2_700_000), cool)
        // A BedJet V2 only sets the temperature for Heat.
        val v2Cool = LiveStatus.of(null, status(Mode.COOL), generation = Generation.V2, now = now)
        assertEquals(LiveStatus.Air(Mode.COOL, temperature = null, fanPercent = 40, endsAt = now + 2_700_000), v2Cool)
        val v2Heat = LiveStatus.of(null, status(Mode.HEAT), generation = Generation.V2, now = now)
        assertEquals(LiveStatus.Air(Mode.HEAT, temperature = 66, fanPercent = 40, endsAt = now + 2_700_000), v2Heat)
    }

    @Test
    fun aNightGoesStaleWhenItsPhaseEnds() {
        val night = run(startedMinutesAgo = 60)
        val live = LiveStatus.Night(night)
        // Cool Drift's first phase is 45 minutes; an hour in, the second ends at 45 + 300.
        assertEquals(night.phaseEnds[1], live.staleAt(now))
        assertEquals(night.endsAt, live.staleAt(night.endsAt))
        assertEquals(night.endsAt, live.endsAt)
    }

    @Test
    fun onlyRealChangesAreRedrawn() {
        val air = LiveStatus.Air(Mode.HEAT, 66, 40, now)
        // The BedJet's timer drifts against the phone's clock.
        assertFalse(LiveStatus.Air(Mode.HEAT, 66, 40, now + 45_000).differsEnough(air))
        assertTrue(LiveStatus.Air(Mode.HEAT, 66, 40, now + 1_800_000).differsEnough(air))
        assertTrue(LiveStatus.Air(Mode.COOL, null, 40, now).differsEnough(air))
        assertTrue(LiveStatus.Air(Mode.HEAT, 68, 40, now).differsEnough(air))
        assertTrue(LiveStatus.Air(Mode.HEAT, 66, 60, now).differsEnough(air))
        val night = LiveStatus.Night(run(startedMinutesAgo = 60))
        assertTrue(night.differsEnough(air))
        assertFalse(night.differsEnough(night))
        assertTrue(LiveStatus.Night(run(startedMinutesAgo = 0)).differsEnough(night))
    }

    @Test
    fun namesAndUnitsAreRedrawnToo() {
        val shown = LiveContent(listOf(side(LiveStatus.Night(run(startedMinutesAgo = 60)))), useFahrenheit = true)
        assertFalse(shown.differsEnough(shown))
        assertTrue(shown.copy(sides = listOf(shown.sides[0].copy(name = "Left"))).differsEnough(shown))
        assertTrue(shown.copy(useFahrenheit = false).differsEnough(shown))
    }

    @Test
    fun settingsAreWordedLikeTheScheduleEditor() {
        assertEquals("33° · Fan 40%", Format.setting(Mode.HEAT, 66, 40, fahrenheit = false))
        assertEquals("Fan 30%", Format.setting(Mode.COOL, null, 30, fahrenheit = true))
        assertEquals("Air off", Format.setting(Mode.WAIT, null, 30, fahrenheit = true))
    }

    @Test
    fun aLiveUpdateSurvivesBeingSaved() {
        // Kept in live.json, so the alarm can redraw it while Lull is closed.
        for (status in listOf(LiveStatus.Air(Mode.HEAT, 66, 40, now), LiveStatus.Night(run(startedMinutesAgo = 60)))) {
            val content = LiveContent(listOf(side(status, generation = Generation.V2)), useFahrenheit = true)
            val saved = AppModel.json.encodeToString(LiveContent.serializer(), content)
            assertEquals(content, AppModel.json.decodeFromString(LiveContent.serializer(), saved))
        }
    }

    // The Live Update. The twins of LiveStatusTests' Live Activity tests.

    private val mine = "mine"
    private val theirs = "theirs"

    private fun side(status: LiveStatus, id: String = mine, name: String = "My side", generation: Generation = Generation.V3) =
        LiveSide(id, status, name, generation)

    private val night get() = LiveContent(listOf(side(LiveStatus.Night(run(startedMinutesAgo = 60)))), useFahrenheit = true)
    private val bootedAt = now - 3_600_000

    private fun shown(content: LiveContent = night, postedAt: Long = now) =
        LiveNotifications.Shown(content, content.staleAt(now), postedAt)

    private fun LiveContent.renamed() = copy(sides = listOf(sides[0].copy(name = "Left")))

    @Test
    fun aLiveUpdateIsOnlyRedrawnWhenSomethingChanged() {
        val shown = shown()
        assertEquals(shown, LiveNotifications.decide(shown, night, { true }, bootedAt, now))
        assertNull(LiveNotifications.decide(shown, night.renamed(), { true }, bootedAt, now))
        assertNull(LiveNotifications.decide(null, night, { true }, bootedAt, now))
        // At the end of the phase, to show the next one.
        assertNull(LiveNotifications.decide(shown, night, { true }, bootedAt, shown.staleAt))
    }

    @Test
    fun aSwipedAwayLiveUpdateStaysDownUntilThereIsSomethingNew() {
        val phaseEnds = shown().staleAt
        val swiped = LiveNotifications.decide(shown(), night, { false }, bootedAt, phaseEnds)
        assertEquals(Long.MAX_VALUE, swiped?.staleAt)
        // Even after the phone restarts.
        assertEquals(swiped, LiveNotifications.decide(swiped, night, { false }, bootedAt = phaseEnds, now = phaseEnds + 60_000))
        assertNull(LiveNotifications.decide(swiped, night.renamed(), { false }, bootedAt, phaseEnds))
    }

    @Test
    fun bothSidesOfTheBedShareOneLiveUpdate() {
        // As on the iPhone, where the Dynamic Island shows one of an app's Live Activities: one holds both, in Lull's order.
        val air = side(LiveStatus.Air(Mode.HEAT, 66, 40, now + 600_000), id = theirs, name = "Other side")
        val wanted = listOf(mine to LiveNotifications.Wanted.Show(night.sides[0]), theirs to LiveNotifications.Wanted.Show(air))
        val both = LiveNotifications.content(wanted, useFahrenheit = true, shown = null, now = now)!!
        assertEquals(listOf(mine, theirs), both.sides.map { it.id })
        // A third doesn't fit, and is left out.
        val third = side(LiveStatus.Air(Mode.COOL, 44, 30, now + 600_000), id = "third")
        val three = LiveNotifications.content(wanted + ("third" to LiveNotifications.Wanted.Show(third)), true, null, now)
        assertEquals(listOf(mine, theirs), three?.sides?.map { it.id })
        // Nothing running.
        assertNull(LiveNotifications.content(emptyList(), true, both, now))
        // One side stops, and the other carries on alone.
        assertEquals(listOf(theirs), LiveNotifications.content(listOf(theirs to LiveNotifications.Wanted.Show(air)), true, both, now)?.sides?.map { it.id })
        // When it ends soonest, so does what the whole may be trusted for.
        assertEquals(minOf(night.staleAt(now), now + 600_000), both.staleAt(now))
    }

    @Test
    fun aBedJetIsKeptUntilLullHearsFromIt() {
        val air = side(LiveStatus.Air(Mode.HEAT, 66, 40, now + 600_000))
        val shown = LiveContent(listOf(air), useFahrenheit = true)
        val keep = listOf(mine to LiveNotifications.Wanted.Keep)
        assertEquals(listOf(air), LiveNotifications.content(keep, true, shown, now)?.sides)
        // Its timer has run out since.
        assertNull(LiveNotifications.content(keep, true, shown, now + 601_000))
        // Nothing shown for it yet.
        assertNull(LiveNotifications.content(keep, true, null, now))
    }

    /** iOS has no twin: ActivityKit keeps track of what is up itself, where Android has Lull's own `live.json`. */
    @Test
    fun aLiveUpdateTakenDownByARestartComesBack() {
        // Posted before the phone restarted, so it isn't showing because it was swiped away.
        assertNull(LiveNotifications.decide(shown(postedAt = bootedAt - 1), night, { false }, bootedAt, now))
    }
}
