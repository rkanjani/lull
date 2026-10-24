package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.rohankanjani.lull.widgets.QuickRemoteWidget
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
            AppCommands.send(AppCommands.Command.StartNight())
            AppCommands.send(AppCommands.Command.Show(LullTab.HISTORY))
            assertTrue(received.isEmpty())
            AppCommands.handler = { received += it }
            assertEquals(listOf(AppCommands.Command.StartNight(), AppCommands.Command.Show(LullTab.HISTORY)), received)
            AppCommands.send(AppCommands.Command.Show(LullTab.REMOTE))
            assertEquals(AppCommands.Command.Show(LullTab.REMOTE), received.last())
            // Start from a widget showing one BedJet names it.
            AppCommands.send(AppCommands.Command.StartNight("side"))
            assertEquals(AppCommands.Command.StartNight("side"), received.last())
            // The Quick Remote's buttons, too.
            AppCommands.send(AppCommands.Command.Remote(RemotePreset.defaults[1]))
            assertEquals(AppCommands.Command.Remote(RemotePreset.defaults[1]), received.last())
            // A Quick Remote set to one side of the bed names it.
            AppCommands.send(AppCommands.Command.Remote(RemotePreset.defaults[0], "right"))
            assertEquals(AppCommands.Command.Remote(RemotePreset.defaults[0], "right"), received.last())
        } finally {
            AppCommands.handler = previous
        }
    }

    // Each BedJet: Tonight, Both Sides and Quick Remote. The twins of WidgetTests.swift's.

    private val mine = "mine"
    private val theirs = "theirs"

    /** Two BedJets: this side partway through tonight's schedule, the other cooled from the Remote for 45 minutes. */
    private fun bothSides(): WidgetSnapshot {
        val night = ActiveRun(schedule, startedAt = now - 3_600_000)
        return WidgetSnapshot(
            tonight = schedule, run = night,
            bedJets = listOf(
                WidgetBedJet(mine, "My side", LiveStatus.Night(night), schedule),
                WidgetBedJet(theirs, "Other side", LiveStatus.Air(Mode.COOL, 44, 40, now + 2_700_000), schedule),
            ),
            onScreen = mine,
        )
    }

    @Test
    fun aWidgetShowsItsOwnBedJet() {
        val snapshot = bothSides()
        assertEquals(theirs, snapshot.bedJet(theirs)?.id)
        // A BedJet that was forgotten since the widget was set up.
        assertNull(snapshot.bedJet("forgotten"))
        // Not yet set to one, a widget shows the one on screen; with none on screen, the first.
        assertEquals(mine, snapshot.usualBedJet?.id)
        assertEquals(theirs, snapshot.copy(onScreen = theirs).usualBedJet?.id)
        assertEquals(mine, snapshot.copy(onScreen = null).usualBedJet?.id)
        // With none known, none, and the widget shows the one on screen once there is one.
        assertNull(WidgetSnapshot().usualBedJet)
        assertEquals(mine, snapshot.bedJet(null)?.id)
    }

    @Test
    fun airFromTheRemoteRunsOutByTheClock() {
        val other = bothSides().bedJet(theirs)!!
        assertTrue(other.live(now) != null)
        assertNull(other.live(now + 2_701_000))
    }

    @Test
    fun tonightShowsTheRemotesTimer() {
        // Set to the other side: no night there, but air from the Remote until its timer runs out.
        val other = bothSides().showing(theirs)
        assertNull(other.run(now))
        assertTrue("the Remote's timer", other.bedJet(null)?.live(now) is LiveStatus.Air)
        assertNull(other.bedJet(null)?.live(now + 2_701_000))
        // Then tonight's schedule, ready to start.
        assertEquals(schedule, other.tonight)
    }

    @Test
    fun redrawsWhenEitherSideChanges() {
        val snapshot = bothSides()
        val dates = snapshot.redraws(now)
        val night = snapshot.run!!
        assertTrue("the other side's timer runs out", dates.contains(now + 2_700_000))
        assertTrue("this side's next phase", dates.contains(night.phaseEnds[1]))
        assertEquals(night.endsAt, dates.last())
        // Air alone still redraws when it turns off.
        val airOnly = WidgetSnapshot(bedJets = listOf(WidgetBedJet(theirs, "Other side", LiveStatus.Air(Mode.HEAT, 66, 40, now + 900_000))))
        assertEquals(now + 900_000, airOnly.redraws(now).last())
    }

    @Test
    fun onlyRealChangesReachTheWidgets() {
        val air = LiveStatus.Air(Mode.HEAT, 66, 40, now)
        // The BedJet reports every second, and its timer drifts against the phone's clock.
        assertEquals(air, WidgetSnapshot.steadied(LiveStatus.Air(Mode.HEAT, 66, 40, now + 20_000), air))
        val warmer = LiveStatus.Air(Mode.HEAT, 68, 40, now)
        assertEquals(warmer, WidgetSnapshot.steadied(warmer, air))
        assertNull(WidgetSnapshot.steadied(null, air))
        assertEquals(air, WidgetSnapshot.steadied(air, null))
    }

    @Test
    fun widgetsKeepWhatTheyShowedWhileLullWaitsToHear() {
        val air = LiveStatus.Air(Mode.HEAT, 66, 40, now + 900_000)
        // Just launched, or saving a night: nothing heard yet, so what was shown stays, until its timer runs out.
        assertEquals(air, WidgetSnapshot.live(null, air, isWaiting = true, now = now))
        assertNull(WidgetSnapshot.live(null, air, isWaiting = true, now = now + 901_000))
        // Heard that it's off.
        assertNull(WidgetSnapshot.live(null, air, isWaiting = false, now = now))
        // Heard something new while waiting.
        val cool = LiveStatus.Air(Mode.COOL, 44, 40, now + 1_800_000)
        assertEquals(cool, WidgetSnapshot.live(cool, air, isWaiting = true, now = now))
    }

    @Test
    fun lullWaitsUntilARunningBedJetSaysWhatItIsRunning() {
        // A report captured from a BedJet 3, set to Heat: its first half, then all of it.
        val half = "01 56 1b 01 00 0e 10 2e 4f 01 13 00 00 14 50 00 00 2d 00 12"
        val heating = Codec.parseStatus(hex(half))!!
        val whole = Codec.parseStatus(hex("$half 01 9a 01 10 10 00 15 35 00 01 53"))!!
        assertTrue(WidgetSnapshot.isWaiting(null, isSavingSchedule = false))
        // Air and a schedule look the same until the second half arrives.
        assertTrue(WidgetSnapshot.isWaiting(heating, isSavingSchedule = false))
        assertFalse(WidgetSnapshot.isWaiting(whole, isSavingSchedule = false))
        assertTrue(WidgetSnapshot.isWaiting(whole, isSavingSchedule = true))
        // Off is off, whichever half has arrived.
        assertFalse(WidgetSnapshot.isWaiting(heating.copy(mode = Mode.STANDBY), isSavingSchedule = false))
    }

    private fun hex(bytes: String): ByteArray = bytes.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun readsWhatAnEarlierLullSaved() {
        // Saved before Lull knew more than one BedJet, with a night running: still read, not blanked.
        val earlier = running(startedMinutesAgo = 60)
        // Fields left at their defaults aren't written, so this has neither of the fields added since.
        val saved = AppModel.json.encodeToString(WidgetSnapshot.serializer(), earlier)
        assertFalse(saved.contains("bedJets"))
        val read = AppModel.json.decodeFromString(WidgetSnapshot.serializer(), saved)
        assertEquals(earlier.run, read.run)
        assertEquals(emptyList<WidgetBedJet>(), read.bedJets)
        // Each BedJet's history came later still: one saved without it reads with none.
        val side = AppModel.json.decodeFromString(WidgetBedJet.serializer(), """{"id":"mine","name":"My side"}""")
        assertEquals(emptyList<HistoryEntry>(), side.recentHistory)
    }

    @Test
    fun aWidgetSetToABedJetShowsItsNight() {
        val sides = bothSides()
        val snapshot = sides.copy(
            bedJets = listOf(sides.bedJets[0], sides.bedJets[1].copy(recentHistory = WidgetSnapshot.recent(HistoryEntry.samples(now), now))),
        )
        // The other side: its own tonight and history, and not the night running on this side.
        val other = snapshot.showing(theirs)
        assertNull(other.run(now))
        assertEquals(schedule, other.tonight)
        assertEquals(snapshot.bedJets[1].recentHistory, other.recentHistory)
        assertEquals(theirs, other.bedJet(null)?.id)
        // This side: its night, as when it's on screen.
        assertEquals(snapshot.run, snapshot.showing(mine).run)
        // Following the one on screen, before one is picked.
        assertEquals(snapshot, snapshot.showing(null))
        // Set to one forgotten since: nothing to show.
        val forgotten = snapshot.showing("forgotten")
        assertNull(forgotten.tonight)
        assertNull(forgotten.run)
        assertEquals(emptyList<HistoryEntry>(), forgotten.recentHistory)
        assertNull(forgotten.bedJet(null))
    }

    @Test
    fun widgetsHeadThemselvesWithTheBedJetsName() {
        // With two, a widget says which side it shows, in its heading and to TalkBack.
        val other = bothSides().showing(theirs)
        assertEquals("Other side", other.headingName)
        assertEquals("Other side · Tonight", other.headed("Tonight"))
        assertEquals("Other side, Tonight, Cool Drift", other.spoken("Tonight, Cool Drift"))
        // Tapping it opens Lull on that side.
        assertEquals(theirs, other.onScreen)
        // With one, there's nothing to tell apart.
        val one = running(startedMinutesAgo = 60)
        assertNull(one.headingName)
        assertEquals("Tonight", one.headed("Tonight"))
        assertEquals("Tonight, Cool Drift", one.spoken("Tonight, Cool Drift"))
    }

    @Test
    fun bothSidesShowWhatsPicked() {
        val snapshot = bothSides().let { it.copy(bedJets = it.bedJets + WidgetBedJet("guest", "Guest bed")) }
        fun ids(sides: Pair<WidgetBedJet?, WidgetBedJet?>) = listOf(sides.first?.id, sides.second?.id)
        // Not yet set: the one on screen on the left and another on the right, and set to the same one twice, the
        // right shows the other.
        assertEquals(listOf(mine, theirs), ids(snapshot.sides(null, null)))
        assertEquals(listOf(theirs, mine), ids(snapshot.copy(onScreen = theirs).sides(null, null)))
        assertEquals(listOf(mine, theirs), ids(snapshot.sides(mine, mine)))
        // As picked: either way round, or the third.
        assertEquals(listOf(theirs, mine), ids(snapshot.sides(theirs, mine)))
        assertEquals(listOf("guest", mine), ids(snapshot.sides("guest", mine)))
        // A side set to a BedJet forgotten since is empty.
        assertEquals(listOf(null, theirs), ids(snapshot.sides("forgotten", theirs)))
    }

    @Test
    fun bothSidesSurviveASaveAndLoad() {
        val snapshot = bothSides()
        val saved = AppModel.json.encodeToString(WidgetSnapshot.serializer(), snapshot)
        assertEquals(snapshot, AppModel.json.decodeFromString(WidgetSnapshot.serializer(), saved))
    }

    @Test
    fun quickRemotePresets() {
        // A new widget's buttons: the same three as before they could be edited.
        assertEquals(listOf("Cool · 30 min", "Heat · 15 min", "Off"), RemotePreset.defaults.map { it.title })
        assertEquals(RemotePreset(Mode.COOL, 30, 44, 40), RemotePreset.defaults[0])
        assertEquals(RemotePreset(Mode.HEAT, 15, 66, 40), RemotePreset.defaults[1])
        assertEquals(Mode.STANDBY, RemotePreset.defaults[2].mode)
        // What else a button sets is read out after its title; Off sets nothing else.
        assertEquals("72° · Fan 40%", RemotePreset.defaults[0].setting(fahrenheit = true))
        assertEquals("Fan 100%", RemotePreset(Mode.TURBO, 10, 86, 100).setting(fahrenheit = true))
        assertNull(RemotePreset.defaults[2].setting(fahrenheit = true))
        assertEquals("Gentle heat · 1 hr 30 min", RemotePreset(Mode.EXTENDED_HEAT, 90, 58, 20).title)
    }

    @Test
    fun quickRemotePresetsKeepToWhatTheBedJetTakes() {
        // Turbo runs 10 minutes at most, at its one temperature.
        assertEquals(RemotePreset(Mode.TURBO, 10, 86, 35), RemotePreset(Mode.TURBO, 60, 50, 33).clamped)
        // Hot, fast Heat stops at the BedJet's safety cut-off, as a phase does.
        val hot = RemotePreset(Mode.HEAT, 240, 80, 100).clamped
        assertEquals(Phase(Mode.HEAT, 80, 100, 240).maxMinutes, hot.minutes)
        assertTrue(hot.minutes < 240)
        // Out of range either way.
        assertEquals(RemotePreset(Mode.COOL, 1, 62, 5), RemotePreset(Mode.COOL, 0, 100, 3).clamped)
        assertEquals(RemotePreset(Mode.COOL, 30, 38, 100), RemotePreset(Mode.COOL, 30, 0, 200).clamped)
    }

    @Test
    fun editingAQuickRemoteChoosesItsButtons() {
        // A widget not yet edited has the usual three.
        assertEquals(PresetChoice.defaults, QuickRemoteWidget.choices(null))
        assertEquals(RemotePreset.defaults, QuickRemoteWidget.choices(null).map { it.preset(fahrenheit = true) })
        // Degrees are read in the unit Lull shows, and left empty mean the mode's usual temperature.
        fun cool(degrees: Int?) = PresetChoice(Mode.COOL, 30, degrees, 40)
        assertEquals(44, cool(72).preset(fahrenheit = true).temperature)
        assertEquals(44, cool(22).preset(fahrenheit = false).temperature)
        assertEquals(Mode.COOL.defaultTemperature, cool(null).preset(fahrenheit = true).temperature)
        // Kept to what the BedJet takes.
        assertEquals(62, cool(100).preset(fahrenheit = true).temperature)
        // Android keeps each widget's choices in its own state; iOS keeps them in the widget's configuration.
        val edited = listOf(PresetChoice(Mode.TURBO, 10, fanPercent = 100), cool(70), PresetChoice(Mode.STANDBY, 30))
        assertEquals(edited, QuickRemoteWidget.choices(QuickRemoteWidget.save(edited)))
        assertEquals(PresetChoice.defaults, QuickRemoteWidget.choices("not what Lull saved"))
    }
}
