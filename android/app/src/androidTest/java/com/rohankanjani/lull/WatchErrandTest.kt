package com.rohankanjani.lull

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohankanjani.lull.bedjetkit.BedJetError
import com.rohankanjani.lull.bedjetkit.DemoDiscovery
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.NearbyBedJet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Errands from the watch, which reach the BedJet through Lull on the phone. Off screen, Lull connects for each one and
 * lets go again, so another phone can still take the BedJet over. Mirrors WatchErrandTests.swift.
 */
@RunWith(AndroidJUnit4::class)
class WatchErrandTest {
    private val mine = NearbyBedJet(UUID.randomUUID().toString(), "BEDJET_3", Generation.V3, -50)
    private val partners = NearbyBedJet(UUID.randomUUID().toString(), "BEDJET_3", Generation.V3, -70)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var prefsName: String
    private lateinit var units: CoroutineScope
    private val fakes = mutableMapOf<String, FakeLink>()
    private val launched = mutableListOf<AppModel>()

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "WatchErrandTest-${UUID.randomUUID()}").apply { mkdirs() }
        prefsName = "WatchErrandTest-${UUID.randomUUID()}"
        AppModel.errandPatience = 2_000
    }

    @After
    fun tearDown() = onMain {
        for (model in launched) model.close()
        for (unit in fakes.values) unit.stop()
        units.cancel()
        directory.deleteRecursively()
        context.deleteSharedPreferences(prefsName)
        context.deleteSharedPreferences("$prefsName.link")
        AppModel.errandPatience = 8_000
    }

    /** Runs on the main thread, where Lull and its links do everything. */
    private fun onMain(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(Dispatchers.Main) {
        if (!::units.isInitialized) units = MainScope()
        block()
    }

    private fun launch(onScreen: Boolean = true): AppModel {
        val storage = Storage(
            directory = directory,
            prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
            linkPrefs = context.getSharedPreferences("$prefsName.link", Context.MODE_PRIVATE),
        )
        // A starter schedule's wake time can be more than 16 hours away, which would make starting a night depend on the time of day.
        return AppModel(context, LaunchOptions(noWakeTimes = true), storage, demoMode = false, makesLink = { unit(it.unit!!) },
            discovery = { DemoDiscovery(listOf(mine, partners)) }, onScreen = onScreen).also { launched += it }
    }

    private fun unit(id: String): FakeLink = fakes.getOrPut(id) { FakeLink("BEDJET_3", units) }

    private suspend fun waitUntil(what: String, timeoutMillis: Long = 6_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            delay(50)
        }
    }

    /** A BedJet chosen and connected, with what's saved on it read, as after using Lull for a while. */
    private suspend fun chosen(unit: NearbyBedJet, model: AppModel): BedJet {
        model.choose(unit)
        val bedJet = model.bedJets.first { it.unit == unit.id }
        waitUntil("${unit.name} to connect") { bedJet.isConnected }
        waitUntil("its slots to be read") { bedJet.deviceSlots.size == 3 }
        return bedJet
    }

    /** The built-in schedules as these tests' launches start with them, without wake times (`noWakeTimes`). */
    private val startingSchedules = Schedule.templates.map { it.copy(wakeTime = null) }

    /** Lull as an earlier launch left it, with just [saved] chosen. */
    private fun save(saved: SavedBedJet) {
        val json = buildJsonObject {
            // The schedules Lull starts with here, without wake times, so the nights these tests start don't depend on the hour.
            put("schedules", AppModel.json.encodeToJsonElement(ListSerializer(Schedule.serializer()), startingSchedules))
            put("fleet", buildJsonObject {
                put("bedJets", buildJsonArray { add(AppModel.json.encodeToJsonElement(SavedBedJet.serializer(), saved)) })
                put("selected", saved.id)
            })
        }
        File(directory, "lull.json").writeText(json.toString())
    }

    @Test
    fun offScreenAnErrandStartsTheNightThenLetsGo() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()
        assertFalse(bedJet.isConnected)
        val stops = unit(mine.id).stops

        val reply = model.runErrand(WatchCommand.StartNight(bedJet.id))
        assertEquals(WatchReply.done, reply)
        assertEquals(bedJet.tonight?.id, bedJet.run?.schedule?.id)
        // Let go again, so another phone can take the BedJet over.
        assertTrue(unit(mine.id).stops > stops)
        assertFalse(bedJet.isConnected)
    }

    @Test
    fun aNightWithAWakeTimeEndsAtIt() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        // Two hours of cooling, ending three hours from now whatever the usual bedtime is: each hour stretches to an hour and a half.
        val wake = (Format.minuteOfDay(System.currentTimeMillis()) + 180) % 1440
        val schedule = Schedule("Wake", listOf(Phase(Mode.COOL, minutes = 60), Phase(Mode.COOL, fanPercent = 20, minutes = 60)), wakeTime = wake)
        model.save(schedule)
        bedJet.tonightID = schedule.id

        val reply = model.runErrand(WatchCommand.StartNight(bedJet.id))
        assertEquals(WatchReply.done, reply)
        val run = bedJet.run!!
        assertEquals(run.schedule.minutesToWake(startingAt = Format.minuteOfDay(run.startedAt)), run.schedule.totalMinutes)
        assertTrue("three hours from the start, a minute either way", run.schedule.totalMinutes in 179..180)
        // The BedJet is given tonight's lengths, and keeps to the wake time itself.
        val stored = bedJet.deviceSlots[bedJet.slot!!]?.steps
        assertEquals(run.schedule.phases.first().step, stored?.first())
        assertEquals(schedule.phases.last().step(endingAt = wake), stored?.last())
    }

    @Test
    fun wokenOffScreenNothingConnectsUntilAnErrandNeedsIt() = onMain {
        val chosen = launch()
        chosen(mine, chosen)
        chosen.appMovedToBackground()
        fakes.clear()

        // Started by the watch: no BedJet is connected, and none is asked for, so another phone keeps its night.
        val model = launch(onScreen = false)
        val bedJet = model.bedJet!!
        delay(500)
        // The link is set up, ready for an errand, and left idle.
        val link = fakes[mine.id] ?: return@onMain fail("No link was set up")
        assertEquals(LinkState.SEARCHING, link.state)
        assertFalse(bedJet.isConnected)

        val reply = model.runErrand(WatchCommand.StartNight(bedJet.id))
        assertEquals(WatchReply.done, reply)
        assertNotNull(bedJet.run)
        assertFalse(bedJet.isConnected)

        // Opened later, it talks to the BedJet as usual.
        model.appBecameActive()
        waitUntil("it to connect") { bedJet.isConnected }
    }

    @Test
    fun aRefreshDoesntCutOffAnErrandOnTheSameBedJet() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()

        val refreshed = async { model.runErrand(WatchCommand.Refresh) }
        val started = async { model.runErrand(WatchCommand.StartNight(bedJet.id)) }
        assertEquals(WatchReply.done, refreshed.await())
        assertEquals(WatchReply.done, started.await())
        assertNotNull(bedJet.run)
        assertFalse(bedJet.isConnected)
    }

    @Test
    fun onScreenTheBedJetStaysConnected() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        val stops = unit(mine.id).stops

        val reply = model.runErrand(WatchCommand.StartNight(null))
        assertEquals(WatchReply.done, reply)
        assertNotNull(bedJet.run)
        assertEquals(stops, unit(mine.id).stops)
        assertTrue(bedJet.isConnected)
    }

    @Test
    fun theRemoteFromTheWatchSetsAndLetsGo() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()

        val preset = RemotePreset(Mode.HEAT, minutes = 20, temperature = 70, fanPercent = 35)
        val reply = model.runErrand(WatchCommand.Remote(preset, bedJet.id))
        assertEquals(WatchReply.done, reply)
        // What the BedJet last said is kept once Lull lets go.
        assertEquals(Mode.HEAT, bedJet.status?.mode)
        assertEquals(70, bedJet.status?.targetTemperature)
        assertEquals(35, bedJet.status?.fanPercent)
        assertFalse(bedJet.isConnected)
    }

    @Test
    fun theWatchsRemoteSetsOnlyWhatWasChangedOnIt() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        bedJet.setMode(Mode.COOL)
        bedJet.setTemperature(50)
        bedJet.setRuntime(180)
        model.appMovedToBackground()

        // The watch last heard of other settings, and only the fan was changed on it.
        val preset = RemotePreset(Mode.HEAT, minutes = 45, temperature = 70, fanPercent = 50)
        val reply = model.runErrand(WatchCommand.Remote(preset, bedJet.id, setOf(RemoteSetting.FAN)))
        assertEquals(WatchReply.done, reply)
        assertEquals(50, bedJet.status?.fanPercent)
        assertEquals(Mode.COOL, bedJet.status?.mode)
        assertEquals(50, bedJet.status?.targetTemperature)
        assertTrue(kotlin.math.abs((bedJet.status?.remainingSeconds ?: 0) - 180 * 60) <= 90)
    }

    @Test
    fun whatWentWrongGoesBackToTheWatchNotIntoAnAlert() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()
        // Unplugged since.
        unit(mine.id).answers = false

        val reply = model.runErrand(WatchCommand.StartNight(bedJet.id))
        assertFalse(reply.ok)
        assertEquals(BedJetError.NotConnected.message, reply.message)
        assertNull(bedJet.run)
        assertNull(model.errorMessage)
    }

    @Test
    fun theWatchsRemoteTriesABedJetOutOfReachOnce() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()
        // Unplugged since.
        unit(mine.id).answers = false
        val requests = unit(mine.id).requests

        // Starting the air from off sets everything. One wait for the BedJet says what's wrong, in time for the watch.
        val preset = RemotePreset(Mode.HEAT, minutes = 20, temperature = 70, fanPercent = 35)
        val reply = model.runErrand(WatchCommand.Remote(preset, bedJet.id))
        assertEquals(BedJetError.NotConnected.message, reply.message)
        assertEquals(requests + 1, unit(mine.id).requests)
        assertFalse(bedJet.isConnected)
    }

    @Test
    fun anErrandForAForgottenBedJetNeverReachesAnother() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)

        val reply = model.runErrand(WatchCommand.StartNight(UUID.randomUUID().toString()))
        assertFalse(reply.ok)
        assertNull(bedJet.run)
    }

    @Test
    fun aBedJetForgottenWhileAnErrandWaitsForItIsLeftAlone() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        // Out of reach, so the errand waits for it.
        unit(mine.id).answers = false
        unit(mine.id).stop()
        val requests = unit(mine.id).requests

        val replied = async { model.runErrand(WatchCommand.StartNight(bedJet.id)) }
        delay(500)
        // Forgotten meanwhile, and back in reach.
        model.forget(bedJet)
        unit(mine.id).answers = true

        val reply = replied.await()
        assertEquals(WatchReply.failed("Choose your BedJet in Lull on your phone first."), reply)
        assertNull(bedJet.run)
        assertEquals(requests, unit(mine.id).requests)
        assertFalse(bedJet.isConnected)
    }

    // Android stops the errand's work instead of answering the watch, so this checks the errand was cancelled where
    // iOS checks its answer.
    @Test
    fun anErrandOutOfBackgroundTimeGivesUp() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()
        // Out of reach, so the errand waits for it.
        unit(mine.id).answers = false
        val requests = unit(mine.id).requests

        val errand = async { model.runErrand(WatchCommand.StartNight(bedJet.id)) }
        delay(500)
        // Android stops the errand's work, which cancels it as WorkManager does, and the BedJet is back in reach before
        // Lull next runs.
        errand.cancel()
        unit(mine.id).answers = true

        errand.join()
        assertTrue(errand.isCancelled)
        assertNull(bedJet.run)
        assertEquals(requests, unit(mine.id).requests)
        assertFalse(bedJet.isConnected)
    }

    @Test
    fun anErrandOutOfBackgroundTimeDuringItsCommandSendsNothingMore() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        model.appMovedToBackground()
        val link = unit(mine.id)
        link.holdsSends = true

        val errand = async { model.runErrand(WatchCommand.StartNight(bedJet.id)) }
        waitUntil("tonight to be on its way to the BedJet") { link.isHolding }
        // Android stops the errand's work while the BedJet is being given tonight, which cancels it.
        errand.cancel()
        // Lull is next opened, the BedJet connects again, and only then does the command in flight finish.
        model.appBecameActive()
        waitUntil("the BedJet to connect again") { bedJet.isConnected }
        link.release()

        errand.join()
        // Tonight was never started, hours after the watch gave up on it.
        assertTrue(errand.isCancelled)
        assertNull(bedJet.run)
        assertEquals(0, link.device.programStep)
    }

    @Test
    fun aRefreshHearsEachBedJetThenLetsGo() = onMain {
        val model = launch()
        val first = chosen(mine, model)
        val second = chosen(partners, model)
        model.appMovedToBackground()
        val asked = System.currentTimeMillis()

        val reply = model.runErrand(WatchCommand.Refresh)
        assertEquals(WatchReply.done, reply)
        assertTrue(first.hasReported(asked))
        assertTrue(second.hasReported(asked))
        assertFalse(first.isConnected)
        assertFalse(second.isConnected)
    }

    @Test
    fun whatARefreshFindsWentWrongWaitsForLullToBeOpened() = onMain {
        // Started from this phone an hour ago, and ended early by the BedJet since.
        val night = ActiveRun(startingSchedules[0], startedAt = System.currentTimeMillis() - 3_600_000)
        save(SavedBedJet(unit = mine.id, name = mine.name, generation = Generation.V3, run = night))
        val model = launch(onScreen = false)
        val bedJet = model.bedJet!!
        assertNotNull(bedJet.run)

        val reply = model.runErrand(WatchCommand.Refresh)
        assertEquals(WatchReply.done, reply)
        assertNull(bedJet.run)
        // Nothing answers a refresh, so it's said in Lull instead, once opened.
        assertTrue(model.errorMessage?.startsWith("Your BedJet turned off") == true)
        assertFalse(bedJet.isConnected)
    }

    @Test
    fun whatAnErrandFindsBeforeItsOwnCommandIsntHowItWent() = onMain {
        // Started from this phone an hour ago, and ended early by the BedJet since.
        val night = ActiveRun(startingSchedules[0], startedAt = System.currentTimeMillis() - 3_600_000)
        save(SavedBedJet(unit = mine.id, name = mine.name, generation = Generation.V3, run = night))
        val model = launch(onScreen = false)
        val bedJet = model.bedJet!!

        val reply = model.runErrand(WatchCommand.StartNight(bedJet.id))
        // Tonight started, so the watch hears it went; the night that ended early is said in Lull, once opened.
        assertEquals(WatchReply.done, reply)
        assertNotNull(bedJet.run)
        assertTrue(model.errorMessage?.startsWith("Your BedJet turned off") == true)
    }

    @Test
    fun anErrandSaysSoWhenTheBedJetIsBusy() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        // A fan change on the phone is still on its way to the BedJet when the watch asks for something.
        val link = unit(mine.id)
        link.holdsSends = true
        val change = async { bedJet.setFan(60) }
        waitUntil("the fan change to be in flight") { link.isHolding }
        val sent = link.sent.toList()

        val started = model.runErrand(WatchCommand.StartNight(bedJet.id))
        val preset = RemotePreset(Mode.HEAT, minutes = 20, temperature = 70, fanPercent = 35)
        val set = model.runErrand(WatchCommand.Remote(preset, bedJet.id))
        // The watch hears the BedJet was left alone, rather than that it went.
        assertEquals(WatchReply.failed(WatchMessages.BUSY), started)
        assertEquals(WatchReply.failed(WatchMessages.BUSY), set)
        assertNull(bedJet.run)
        assertEquals(sent, link.sent)
        link.release()
        change.await()
    }
}
