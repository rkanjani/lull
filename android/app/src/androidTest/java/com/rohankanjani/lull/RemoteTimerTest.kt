package com.rohankanjani.lull

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.DemoDiscovery
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.NearbyBedJet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * How air started from the Remote is timed: as long as the BedJet allows, for a duration picked there, or until a time
 * picked there, which holds across modes. On a device, since the model needs Android. Mirrors RemoteTimerTests.swift.
 */
@RunWith(AndroidJUnit4::class)
class RemoteTimerTest {
    private val mine = NearbyBedJet(UUID.randomUUID().toString(), "BEDJET_3", Generation.V3, -50)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var prefsName: String
    private lateinit var units: CoroutineScope
    private val fakes = mutableMapOf<String, FakeLink>()
    private val launched = mutableListOf<AppModel>()

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "RemoteTimerTest-${UUID.randomUUID()}").apply { mkdirs() }
        prefsName = "RemoteTimerTest-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() = onMain {
        for (model in launched) model.close()
        for (unit in fakes.values) unit.stop()
        units.cancel()
        directory.deleteRecursively()
        context.deleteSharedPreferences(prefsName)
        context.deleteSharedPreferences("$prefsName.link")
    }

    /** Runs on the main thread, where Lull and its links do everything. */
    private fun onMain(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(Dispatchers.Main) {
        if (!::units.isInitialized) units = MainScope()
        block()
    }

    private fun launch(): AppModel {
        val storage = Storage(
            directory = directory,
            prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
            linkPrefs = context.getSharedPreferences("$prefsName.link", Context.MODE_PRIVATE),
        )
        // A starter schedule's wake time can be more than 16 hours away, which would make starting a night depend on the time of day.
        return AppModel(context, LaunchOptions(noWakeTimes = true), storage, demoMode = false, makesLink = { unit(it.unit!!) },
            discovery = { DemoDiscovery(listOf(mine)) }).also { launched += it }
    }

    private fun unit(id: String): FakeLink = fakes.getOrPut(id) { FakeLink("BEDJET_3", units) }

    private suspend fun waitUntil(what: String, timeoutMillis: Long = 6_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            delay(50)
        }
    }

    private suspend fun chosen(unit: NearbyBedJet, model: AppModel): BedJet {
        model.choose(unit)
        val bedJet = model.bedJets.first { it.unit == unit.id }
        waitUntil("${unit.name} to connect") { bedJet.isConnected }
        waitUntil("its slots to be read") { bedJet.deviceSlots.size == 3 }
        return bedJet
    }

    /** Waits for the BedJet to count down from about [minutes], give or take a minute and a half. */
    private suspend fun waitForTimer(bedJet: BedJet, minutes: Int, what: String) =
        waitUntil(what) { abs((bedJet.status?.remainingSeconds ?: 0) - minutes * 60) <= 90 }

    private fun inHours(hours: Int) = System.currentTimeMillis() + hours * 3_600_000L

    @Test
    fun aTimeRunsTheAirUntilThenWhateverTheMode() = onMain {
        val model = launch()
        model.remoteTimerShowsTime = true
        val bedJet = chosen(mine, model)
        bedJet.setMode(Mode.COOL)
        waitForTimer(bedJet, 720, "Cool to run as long as the simulated BedJet allows")

        bedJet.setRuntime(untilMillis = inHours(3))
        waitForTimer(bedJet, 180, "the timer to reach the time")
        // Another mode keeps the same end, where it would otherwise start over at its longest run.
        bedJet.setMode(Mode.HEAT)
        waitUntil("Heat to report") { bedJet.status?.mode == Mode.HEAT }
        waitForTimer(bedJet, 180, "Heat to end at the same time")
        // As far as the mode allows: Heat runs for 4 hours at most.
        bedJet.setRuntime(untilMillis = inHours(6))
        waitForTimer(bedJet, 240, "Heat to stop at its limit")

        // Turned off, the next mode starts over at its longest run.
        bedJet.setMode(Mode.STANDBY)
        assertEquals(RemoteTimer.Longest, bedJet.remoteTimer)
        bedJet.setMode(Mode.COOL)
        waitForTimer(bedJet, 720, "Cool to run as long as it can again")
    }

    @Test
    fun aTimeCutShortByAHotterSettingIsStillKeptTo() = onMain {
        val bedJet = chosen(mine, launch())
        val unit = unit(mine.id)
        bedJet.setMode(Mode.HEAT)
        waitUntil("Heat to report") { bedJet.status?.mode == Mode.HEAT }
        // At the strongest fan, Heat may run 4 hours at 30 °C but only 1 at 40 °C.
        bedJet.setFan(100)
        bedJet.setTemperature(60)
        waitUntil("Heat to allow 4 hours") { bedJet.status?.maxRuntimeMinutes == 240 }

        // A hotter setting reaches the BedJet before Lull hears of it, as one still on its way when a time is picked:
        // the BedJet cuts the 3 hours sent to the hour it now allows.
        unit.reports = false
        unit.send(Codec.setTemperature(80))
        val before = unit.sent.size
        val picking = launch { bedJet.setRuntime(untilMillis = inHours(3)) }
        waitUntil("the time to be sent") { unit.sent.drop(before).any { it.first() == Codec.setRuntime(0).first() } }
        unit.reports = true
        picking.join()
        waitForTimer(bedJet, 60, "Heat to stop at its new limit")

        // Read back from the BedJet, the shorter timer is still Lull's: a fan change keeps to the time picked, and a cooler
        // setting gives back what the hotter one took.
        bedJet.setFan(95)
        assertTrue("not taken for another phone's timer", bedJet.remoteTimer.isAhead)
        bedJet.setTemperature(60)
        waitForTimer(bedJet, 180, "the air to turn off at the time picked again")
    }

    @Test
    fun shownAsATimeTheEndOnScreenHoldsWhereverItCameFrom() = onMain {
        val model = launch()
        model.remoteTimerShowsTime = true
        val bedJet = chosen(mine, model)
        bedJet.setMode(Mode.COOL)
        waitForTimer(bedJet, 720, "Cool to run as long as it can")
        // A countdown Lull didn't set, as after it relaunches: it only knows what the BedJet says.
        unit(mine.id).send(Codec.setRuntime(180))
        waitForTimer(bedJet, 180, "the BedJet to count down 3 hours")
        bedJet.setMode(Mode.HEAT)
        waitUntil("Heat to report") { bedJet.status?.mode == Mode.HEAT }
        waitForTimer(bedJet, 180, "Heat to end when the screen said")
    }

    @Test
    fun shownAsATimerAModeStartsOverAtItsLongestRun() = onMain {
        val bedJet = chosen(mine, launch())
        bedJet.setMode(Mode.COOL)
        bedJet.setRuntime(untilMillis = inHours(3))
        waitForTimer(bedJet, 180, "the timer to reach the time")
        // The Remote shows a duration, so a mode runs as long as it can, as the timer always has.
        bedJet.setMode(Mode.HEAT)
        waitUntil("Heat to report") { bedJet.status?.mode == Mode.HEAT }
        waitForTimer(bedJet, 240, "Heat to run as long as it can")
        assertEquals(RemoteTimer.Longest, bedJet.remoteTimer)
    }

    @Test
    fun aPickedDurationIsLeftAlone() = onMain {
        val bedJet = chosen(mine, launch())
        bedJet.setMode(Mode.COOL)
        waitForTimer(bedJet, 720, "Cool to run as long as it can")
        bedJet.setRuntime(30)
        bedJet.setFan(60)
        delay(1_500)
        assertEquals(RemoteTimer.Picked, bedJet.remoteTimer)
        assertTrue(abs((bedJet.status?.remainingSeconds ?: 0) - 30 * 60) <= 90)
    }

    @Test
    fun aScheduleKeepsItsOwnTiming() = onMain {
        val bedJet = chosen(mine, launch())
        bedJet.setMode(Mode.COOL)
        bedJet.setRuntime(untilMillis = inHours(3))
        assertTrue(bedJet.remoteTimer.isAhead)
        bedJet.startTonight()
        waitUntil("the night to start") { bedJet.run != null }
        assertEquals(RemoteTimer.Longest, bedJet.remoteTimer)
    }

    @Test
    fun aTimeHoldsAfterLullIsClosed() = onMain {
        val model = launch()
        val bedJet = chosen(mine, model)
        bedJet.setMode(Mode.COOL)
        bedJet.setRuntime(untilMillis = inHours(3))
        waitForTimer(bedJet, 180, "the timer to reach the time")

        // The phone closes Lull while the air runs. Opened again, a fan change still keeps to the time picked.
        model.appMovedToBackground()
        val relaunched = launch()
        val again = relaunched.bedJet!!
        waitUntil("it to connect again") { again.isConnected }
        waitUntil("its slots to be read again") { again.deviceSlots.size == 3 }
        assertTrue(again.remoteTimer.isAhead)
        val sent = unit(mine.id).sent.size
        again.setFan(50)
        assertTrue(unit(mine.id).sent.drop(sent).none { it.first() == Codec.setRuntime(0).first() })
        waitForTimer(again, 180, "the air to still turn off at the time")
    }
}
