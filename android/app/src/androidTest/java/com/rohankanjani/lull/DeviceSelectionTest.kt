package com.rohankanjani.lull

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohankanjani.lull.bedjetkit.BedJetError
import com.rohankanjani.lull.bedjetkit.BedJetLink
import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.DemoDiscovery
import com.rohankanjani.lull.bedjetkit.DemoLink
import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.NearbyBedJet
import com.rohankanjani.lull.bedjetkit.ProgramStep
import com.rohankanjani.lull.bedjetkit.StoredProgram
import com.rohankanjani.lull.bedjetkit.startProgram
import com.rohankanjani.lull.bedjetkit.takes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Choosing, switching between and forgetting BedJets, against simulated units that connect only when allowed,
 * record every command they receive and can hold one in flight. Nothing here touches real hardware.
 * Mirrors DeviceSelectionTests.swift.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSelectionTest {
    // Two units share the default name, as real ones often do. The guest room's doesn't say what it is.
    private val mine = NearbyBedJet(UUID.randomUUID().toString(), "BEDJET_3", Generation.V3, -50)
    private val partners = NearbyBedJet(UUID.randomUUID().toString(), "BEDJET_3", Generation.V3, -70)
    private val guest = NearbyBedJet(UUID.randomUUID().toString(), "BEDJET_GUEST", null, -85)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var prefsName: String
    private lateinit var discovery: DemoDiscovery
    /** Where the simulated units run their clocks. */
    private lateinit var units: CoroutineScope
    /** The simulated units, by Bluetooth identity. Each keeps its state across connections, like a real one. */
    private val fakes = mutableMapOf<String, FakeLink>()
    /** How each unit is set up when Lull first connects to it. */
    private val setups = mutableMapOf<String, (FakeLink) -> Unit>()
    private val launched = mutableListOf<AppModel>()

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "DeviceSelectionTest-${UUID.randomUUID()}").apply { mkdirs() }
        prefsName = "DeviceSelectionTest-${UUID.randomUUID()}"
        discovery = DemoDiscovery(listOf(mine, partners, guest))
    }

    @After
    fun tearDown() = onMain {
        for (model in launched) model.appMovedToBackground()
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

    /** Lull launched by the person, on screen. Off screen is a launch to run an automation. */
    private fun launch(demo: Boolean = false, onScreen: Boolean = true): AppModel {
        val storage = Storage(
            directory = directory,
            prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
            linkPrefs = context.getSharedPreferences("$prefsName.link", Context.MODE_PRIVATE),
        )
        // A starter schedule's wake time can be more than 16 hours away, which would make starting a night depend on the time of day.
        return AppModel(context, LaunchOptions(noWakeTimes = true), storage, demoMode = demo, makesLink = { unit(it.unit!!) }, discovery = { discovery }, onScreen = onScreen)
            .also { launched += it }
    }

    private fun unit(id: String): FakeLink = fakes.getOrPut(id) {
        FakeLink(listOf(mine, partners, guest).firstOrNull { it.id == id }?.name ?: "BEDJET", units).also { setups[id]?.invoke(it) }
    }

    private fun bedJet(unit: NearbyBedJet, model: AppModel): BedJet = model.bedJets.first { it.unit == unit.id }

    private suspend fun waitUntil(what: String, timeoutMillis: Long = 6_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            delay(50)
        }
    }

    /** Waits for a BedJet to connect and finish reading what's saved on it, which Lull does on each new connection. */
    private suspend fun connected(unit: NearbyBedJet, model: AppModel): BedJet {
        val bedJet = bedJet(unit, model)
        waitUntil("${unit.name} to connect") { bedJet.isConnected }
        if (bedJet.generation == Generation.V3) waitUntil("${unit.name}'s slots to be read") { bedJet.deviceSlots.size == 3 }
        return bedJet
    }

    // Choosing

    @Test
    fun nothingConnectsUntilABedJetIsChosen() = onMain {
        val model = launch()
        assertNull(model.bedJet)
        model.startLooking()
        // Everything nearby is listed as soon as it's seen, and looking connects to none of it.
        assertEquals(listOf(mine.id, partners.id, guest.id), model.nearby.map { it.id })
        assertTrue(fakes.isEmpty())

        model.choose(partners)
        assertEquals(partners.id, model.bedJet?.unit)
        assertEquals(setOf(partners.id), fakes.keys)
        connected(partners, model)
        // Once chosen it's under Your BedJets, not Nearby.
        assertEquals(listOf(mine.id, guest.id), model.nearby.map { it.id })
    }

    @Test
    fun aLoneBedJetNearbyIsListedAtOnce() = onMain {
        discovery.units = listOf(guest)
        val model = launch()
        model.startLooking()
        assertEquals(listOf(guest.id), model.nearby.map { it.id })
        model.stopLooking()
        assertTrue(model.nearby.isEmpty())
    }

    @Test
    fun sameNamedBedJetsAreToldApartAndNicknamesStick() = onMain {
        val model = launch()
        model.startLooking()
        assertEquals("BEDJET_3 · ${AppModel.code(mine.id)}", model.title(mine))
        assertEquals("BEDJET_GUEST", model.title(guest))
        model.choose(mine)
        model.choose(partners)
        val first = bedJet(mine, model)
        val second = bedJet(partners, model)
        assertEquals("BEDJET_3 · ${AppModel.code(mine.id)}", model.title(first))
        assertEquals("BEDJET_3 · ${AppModel.code(partners.id)}", model.title(second))
        assertNotEquals(model.title(first), model.title(second))

        second.nickname = "Partner's side"
        assertEquals("Partner's side", model.title(second))
        assertEquals("BEDJET_3", model.title(first))

        // Kept on this phone across launches; the BedJet itself isn't renamed.
        model.appMovedToBackground()
        val relaunched = launch()
        assertEquals("Partner's side", bedJet(partners, relaunched).nickname)
        assertEquals("BEDJET_3", bedJet(partners, relaunched).name)
        assertEquals(partners.id, relaunched.bedJet?.unit)
    }

    @Test
    fun reconnectsToTheChosenBedJetAfterRelaunch() = onMain {
        val model = launch()
        model.choose(mine)
        connected(mine, model)
        model.appMovedToBackground()

        fakes.clear()
        val relaunched = launch()
        assertEquals(mine.id, relaunched.bedJet?.unit)
        assertEquals(setOf(mine.id), fakes.keys)
        connected(mine, relaunched)
    }

    @Test
    fun anAbsentBedJetIsNeverSwappedForAnotherNearby() = onMain {
        setups[mine.id] = { it.answers = false }
        val model = launch()
        model.choose(mine)
        model.startLooking()
        delay(2_000)
        // Still looking for the chosen one, with the partner's in range and free.
        assertEquals(mine.id, model.bedJet?.unit)
        assertEquals(LinkState.SEARCHING, model.bedJet?.linkState)
        assertEquals(setOf(mine.id), fakes.keys)
        // Over Bluetooth, a link only takes the unit it was made for.
        assertFalse(takes(partners.id, boundTo = mine.id))
        assertTrue(takes(mine.id, boundTo = mine.id))
    }

    // Switching

    @Test
    fun switchingLeavesTheOtherBedJetRunning() = onMain {
        val model = launch()
        model.choose(mine)
        val first = connected(mine, model)
        first.startTonight()
        assertNotNull(first.run)
        first.setTemperature(70)
        val sentBefore = unit(mine.id).sent.toList()

        model.choose(partners)
        assertEquals(partners.id, model.bedJet?.unit)
        connected(partners, model)
        delay(1_500)

        // The first keeps its connection, its schedule and everything set on it; nothing more was sent to it.
        assertEquals(sentBefore, unit(mine.id).sent)
        assertEquals(0, unit(mine.id).stops)
        assertNotNull(first.run)
        assertTrue(first.isConnected)
        assertTrue((first.status?.extra?.programStep ?: 0) > 0)
    }

    @Test
    fun rapidSwitchingAndLateReportsStayWithTheirBedJet() = onMain {
        val model = launch()
        model.choose(mine)
        model.choose(partners)
        model.choose(guest)
        val first = connected(mine, model)
        val last = connected(guest, model)
        for (id in listOf(first.id, last.id, first.id, last.id)) assertTrue(model.select(id))
        assertEquals(guest.id, model.bedJet?.unit)

        // A report from the first unit, arriving whenever it does, only ever updates the first.
        val before = last.status
        val report = first.status!!.copy(mode = Mode.TURBO)
        unit(mine.id).report(report)
        assertEquals(Mode.TURBO, first.status?.mode)
        assertEquals(before?.mode, last.status?.mode)
        assertEquals(guest.id, model.bedJet?.unit)
    }

    /**
     * A real BedJet 3 sends each report in two halves, and right after reconnecting, such as after restarting to check
     * for firmware, only the first half comes for a moment. Its settings stay shown meanwhile, so the sheet holds still.
     */
    @Test
    fun settingsStayWhileAReportsSecondHalfIsLate() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        waitUntil("a full report") { bedJet.settings != null }
        val settings = bedJet.settings
        unit(mine.id).report(bedJet.status!!.copy(extra = null))
        assertNull(bedJet.status?.extra)
        assertEquals(settings, bedJet.settings)
    }

    /**
     * A BedJet checking for firmware restarts partway through, by itself or for another phone, so nothing is sent
     * meanwhile: not a command, not a night, not another check or network.
     */
    @Test
    fun nothingIsSentWhileTheBedJetChecksForFirmware() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        waitUntil("a full report") { bedJet.settings != null }
        val checking = bedJet.status!!.let { it.copy(extra = it.extra?.copy(wifiPhase = Codec.WiFiPhase.CHECKING)) }
        unit(mine.id).report(checking)
        assertFalse(bedJet.canControl)
        assertFalse(bedJet.canUpdateFirmware)
        assertFalse(bedJet.canJoinWiFi)

        val sentBefore = unit(mine.id).sent.size
        bedJet.setMode(Mode.HEAT)
        bedJet.startTonight()
        bedJet.updateFirmware()
        assertEquals(sentBefore, unit(mine.id).sent.size)
        assertNotNull(model.errorMessage)

        // Done: the result stays in the report, and the BedJet is free again.
        unit(mine.id).report(checking.copy(extra = checking.extra?.copy(wifiPhase = Codec.WiFiPhase.UP_TO_DATE)))
        assertTrue(bedJet.canControl)
        assertTrue(bedJet.canUpdateFirmware)
    }

    /** Restarting to install firmware lasts until the BedJet restarts. It may still say so once it's back. */
    @Test
    fun restartingToInstallOnlyHoldsUntilTheBedJetRestarts() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        waitUntil("a full report") { bedJet.settings != null }
        val installing = bedJet.status!!.let { it.copy(extra = it.extra?.copy(wifiPhase = Codec.WiFiPhase.INSTALLING)) }
        unit(mine.id).report(installing)
        assertFalse(bedJet.canControl)

        // Gone, so restarted: the same result showing afterwards is the one from before.
        unit(mine.id).stop()
        unit(mine.id).report(installing)
        assertTrue(bedJet.canControl)
    }

    /**
     * Like a real BedJet 3, the simulated one restarts to check, downloads the newer firmware it offered, then
     * restarts into it. Lull holds on to it throughout, and says what it's on once it's back.
     */
    @Test
    fun anOfferedUpdateIsInstalledAndSaidOnceBack() = onMain {
        setups[mine.id] = { it.device.offerFirmwareUpdate() }
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        waitUntil("the offer") { bedJet.firmwarePrompt == BedJet.FirmwarePrompt.Available }
        bedJet.deferFirmwareUpdate()
        bedJet.updateFirmware(offered = true)
        assertTrue(bedJet.expectsRestart)
        assertFalse(bedJet.canControl)
        assertNull(bedJet.firmwarePrompt)

        waitUntil("the BedJet to be back on the new firmware", timeoutMillis = 30_000) { bedJet.firmware == "V1.21 (demo)" }
        val prompt = bedJet.firmwarePrompt as? BedJet.FirmwarePrompt.Finished ?: return@onMain fail("How it went wasn't said")
        assertEquals("V1.21 (demo)", prompt.activity.installed)
        assertFalse(bedJet.expectsRestart)
        assertTrue(bedJet.canControl)
        assertEquals(1, unit(mine.id).sent.count { it == Codec.updateFirmware.toList() })
        bedJet.dismissFirmwareResult()
        assertNull(bedJet.firmwarePrompt)
    }

    /** Updating restarts the BedJet, so it waits for the air to be off and the night to be over. */
    @Test
    fun anUpdateWaitsForTheAirToBeOff() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        waitUntil("a report") { bedJet.status != null }
        assertTrue(bedJet.canUpdateFirmware)
        bedJet.setMode(Mode.HEAT)
        waitUntil("the air to be on") { bedJet.status?.mode == Mode.HEAT }
        assertFalse(bedJet.canUpdateFirmware)
        bedJet.updateFirmware()
        assertFalse(Codec.updateFirmware.toList() in unit(mine.id).sent)
        bedJet.setMode(Mode.STANDBY)
        waitUntil("the air to be off") { bedJet.status?.mode == Mode.STANDBY }
        assertTrue(bedJet.canUpdateFirmware)
    }

    /**
     * The BedJet's word that newer firmware is available is offered once and then waits in the sheet. Lull leaves
     * it for the BedJet to clear, so other phones and the official app still see it.
     */
    @Test
    fun anOfferedUpdateIsAskedAboutOnceAndLeftSet() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        waitUntil("a full report") { bedJet.settings != null }
        val offered = bedJet.status!!.let { it.copy(extra = it.extra?.copy(notification = Codec.Notification.UPDATE_AVAILABLE)) }
        unit(mine.id).report(offered)
        assertTrue(bedJet.updateAvailable)
        assertEquals(BedJet.FirmwarePrompt.Available, bedJet.firmwarePrompt)

        bedJet.deferFirmwareUpdate()
        assertNull(bedJet.firmwarePrompt)
        assertTrue(bedJet.updateAvailable)
        assertFalse(Codec.acknowledgeNotification.toList() in unit(mine.id).sent)
    }

    @Test
    fun aCommandMeantForOneBedJetNeverReachesAnother() = onMain {
        val model = launch()
        model.choose(mine)
        model.choose(partners)
        val first = connected(mine, model)
        val second = connected(partners, model)
        assertTrue(model.select(first.id))

        unit(mine.id).holdsSends = true
        val sending = async { first.setMode(Mode.HEAT) }
        waitUntil("the command to be in flight") { unit(mine.id).isHolding }
        assertTrue(model.select(second.id))
        unit(mine.id).release()
        sending.await()

        val heat = Codec.setMode(Mode.HEAT)!!.toList()
        assertTrue(heat in unit(mine.id).sent)
        assertFalse(heat in unit(partners.id).sent)
        assertEquals(Mode.STANDBY, second.status?.mode)
    }

    @Test
    fun switchingWaitsWhileAScheduleIsSaving() = onMain {
        val model = launch()
        model.choose(partners)
        model.choose(mine)
        val first = connected(mine, model)
        val second = connected(partners, model)

        unit(mine.id).holdsSends = true
        val saving = async { first.startTonight() }
        waitUntil("the schedule to start saving") { unit(mine.id).isHolding && first.isSavingSchedule }
        assertFalse(model.canSwitch)
        assertFalse(model.select(second.id))
        model.choose(guest)
        assertEquals(mine.id, model.bedJet?.unit)
        assertEquals(2, model.bedJets.size)

        unit(mine.id).release()
        saving.await()
        assertNotNull(first.run)
        assertTrue(model.select(second.id))
    }

    // What belongs to each BedJet

    @Test
    fun slotsRunsAndHistoryStayWithTheirBedJet() = onMain {
        val theirs = StoredProgram("Theirs", listOf(ProgramStep(Mode.COOL, fanStep = 7, temperature = 40, hours = 2, minutes = 0)))
        setups[partners.id] = { it.device = DemoLink("BEDJET_3", listOf(theirs, null, null), units) }
        val model = launch()
        model.choose(mine)
        val first = connected(mine, model)
        first.startTonight()
        assertEquals(0, first.slot)
        assertNotNull(first.run)
        assertTrue(first.history.any { it.event is HistoryEntry.Event.ScheduleStarted })

        model.choose(partners)
        val second = connected(partners, model)
        assertNull(second.slot)
        assertNull(second.run)
        assertTrue(second.history.isEmpty())
        // Its own slots are read before one is picked, so the program saved on it stays.
        second.startTonight()
        assertEquals(1, second.slot)
        assertEquals(theirs, unit(partners.id).device.slots[0])
        assertEquals(0, first.slot)
        assertNotNull(first.run)
        assertNotEquals(first.history, second.history)
    }

    @Test
    fun aV2RefusesModesItCantRunWithoutChangingTheSchedule() = onMain {
        setups[mine.id] = { it.generation = Generation.V2 }
        val model = launch()
        val dryOut = Schedule("Dry out", listOf(Phase(Mode.COOL, fanPercent = 40, minutes = 60), Phase(Mode.DRY, fanPercent = 50, minutes = 30)))
        model.save(dryOut)
        model.choose(mine)
        val first = connected(mine, model)
        assertEquals(Generation.V2, first.knownGeneration)
        first.tonightID = dryOut.id
        assertEquals(listOf(Mode.DRY), first.unsupportedModes(dryOut))
        assertNotNull(first.problem(dryOut))

        val sentBefore = unit(mine.id).sent.toList()
        first.startTonight()
        assertEquals(sentBefore, unit(mine.id).sent)
        assertNull(first.run)
        assertTrue(model.errorMessage?.contains("can't run Dry") == true)
        assertEquals(dryOut, model.schedules.first { it.id == dryOut.id })

        // A BedJet 3 runs the same schedule as it is.
        model.choose(partners)
        val second = connected(partners, model)
        assertNull(second.problem(dryOut))
    }

    // Carrying over saved state

    private fun write(json: JsonObject, name: String) = File(directory, name).writeText(json.toString())

    private val templates = AppModel.json.encodeToJsonElement(ListSerializer(Schedule.serializer()), Schedule.templates)

    private val aNight = listOf(
        HistoryEntry(System.currentTimeMillis() - 3_600_000, HistoryEntry.Event.ScheduleStarted("Cool Drift", null), room = 44),
    )

    private fun historyFile(entries: List<HistoryEntry>) = buildJsonObject {
        put("entries", AppModel.json.encodeToJsonElement(ListSerializer(HistoryEntry.serializer()), entries))
    }

    @Test
    fun carriesOverTheBedJetAnEarlierVersionRemembered() = onMain {
        context.getSharedPreferences("$prefsName.link", Context.MODE_PRIVATE).edit()
            .putString("peripheral", mine.id).putString("deviceName", "BEDJET_3").commit()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("generation", "V2").commit()
        write(buildJsonObject {
            put("schedules", templates)
            put("tonightID", Schedule.templates[1].id)
            put("slot", 2)
        }, "lull.json")
        write(historyFile(aNight), "history.json")

        val model = launch()
        val first = model.bedJet!!
        assertEquals(mine.id, first.unit)
        assertEquals("BEDJET_3", first.name)
        assertEquals(Generation.V2, first.knownGeneration)
        assertEquals(2, first.slot)
        assertEquals(Schedule.templates[1].id, first.tonightID)
        assertEquals(aNight, first.history)
        assertFalse(File(directory, "history.json").exists())

        // Carrying over happens once; launching again finds the same BedJet with the same nights.
        model.appMovedToBackground()
        val relaunched = launch()
        assertEquals(1, relaunched.bedJets.size)
        assertEquals(first.id, relaunched.bedJet?.id)
        assertEquals(aNight, relaunched.bedJet?.history)
    }

    @Test
    fun withoutARememberedBedJetNothingIsAssumed() = onMain {
        write(buildJsonObject {
            put("schedules", templates)
            put("slot", 1)
        }, "lull.json")
        write(historyFile(aNight), "history.json")
        val model = launch()
        assertNull(model.bedJet)
        assertTrue(model.bedJets.isEmpty())
        // Nights of unknown origin stay where they were rather than being given to whichever BedJet is chosen next.
        assertTrue(File(directory, "history.json").exists())
        model.choose(mine)
        assertTrue(model.bedJet?.history?.isEmpty() == true)
    }

    @Test
    fun bedJetsSavedWithoutAUnitAreChosenAgain() = onMain {
        // Saved by the version that connected to whichever BedJet it found first.
        val unknown = UUID.randomUUID().toString()
        val known = UUID.randomUUID().toString()
        write(buildJsonObject {
            put("schedules", templates)
            put("fleet", buildJsonObject {
                put("bedJets", buildJsonArray {
                    add(buildJsonObject { put("id", unknown); put("name", "BEDJET_3") })
                    add(buildJsonObject { put("id", known); put("unit", partners.id); put("name", "BEDJET_3") })
                })
                put("selected", unknown)
            })
        }, "lull.json")
        write(historyFile(aNight), "history-$unknown.json")

        val model = launch()
        assertEquals(listOf(known), model.bedJets.map { it.id })
        assertNull(model.bedJet)
        assertTrue(File(directory, "history-$unknown.json").exists())
    }

    // Forgetting

    @Test
    fun forgettingTheBedJetOnScreenAsksAgain() = onMain {
        val model = launch()
        model.choose(partners)
        model.choose(mine)
        val first = connected(mine, model)
        first.startTonight()
        val nights = first.history
        assertFalse(nights.isEmpty())

        model.forget(first)
        // Nothing is picked in its place, and the unit was left running as it was.
        assertNull(model.bedJet)
        assertEquals(listOf(partners.id), model.bedJets.map { it.unit })
        assertFalse(Codec.setMode(Mode.STANDBY)!!.toList() in unit(mine.id).sent)
        assertTrue(unit(mine.id).device.programStep > 0)
        assertTrue(File(directory, "history-${first.id}.json").exists())

        model.appMovedToBackground()
        val relaunched = launch()
        assertNull(relaunched.bedJet)

        // Choosing it again brings its nights back.
        relaunched.choose(mine)
        assertEquals(first.id, relaunched.bedJet?.id)
        assertEquals(nights, relaunched.bedJet?.history)
    }

    @Test
    fun demoBedJetsStayApartFromRealOnes() = onMain {
        val model = launch()
        model.choose(mine)
        model.demoMode = true
        assertTrue(model.bedJet?.isDemo == true)
        assertFalse(model.bedJets.any { it.unit == mine.id })
        model.demoMode = false
        assertEquals(mine.id, model.bedJet?.unit)
        assertEquals(1, model.bedJets.size)
        assertFalse(model.bedJet?.isDemo ?: true)
    }

    // The Remote's timer

    /** Opens Lull on [mine] as an earlier launch left it, with the Remote's timer saved as given. */
    private fun launch(longestEnds: Long?, timerEnds: Long?): AppModel {
        val saved = SavedBedJet(unit = mine.id, name = mine.name, generation = Generation.V3, longestEnds = longestEnds, timerEnds = timerEnds)
        write(buildJsonObject {
            put("schedules", templates)
            put("fleet", buildJsonObject {
                put("bedJets", buildJsonArray { add(AppModel.json.encodeToJsonElement(SavedBedJet.serializer(), saved)) })
                put("selected", saved.id)
            })
        }, "lull.json")
        return launch()
    }

    private fun timerCommands(unit: FakeLink, after: Int = 0): List<List<Byte>> =
        unit.sent.drop(after).filter { it.first() == Codec.setRuntime(0).first() }

    private fun remainingSeconds(unit: FakeLink): Int = unit.lastStatus!!.remainingSeconds

    private fun assertAbout(expected: Int, actual: Int) =
        assertTrue("$actual is not about $expected", kotlin.math.abs(expected - actual) <= 15)

    @Test
    fun aPickedTimeKeepsCountingDownAfterLullIsClosed() = onMain {
        // An hour was picked, and 45 minutes are left when Lull is opened again and the fan changed.
        val unit = unit(mine.id)
        unit.device.send(Codec.setMode(Mode.COOL)!!)
        unit.device.send(Codec.setRuntime(45))
        val model = launch(longestEnds = null, timerEnds = null)
        val bedJet = connected(mine, model)

        bedJet.setFan(50)
        assertEquals(emptyList<List<Byte>>(), timerCommands(unit))
        assertAbout(45 * 60, remainingSeconds(unit))
    }

    @Test
    fun theLongestRunKeepsItsEndAsTheFanChanges() = onMain {
        // Cool was chosen on the Remote 45 minutes ago, which gave it 12 hours.
        val unit = unit(mine.id)
        unit.device.send(Codec.setMode(Mode.COOL)!!)
        unit.device.send(Codec.setRuntime(675))
        val ends = System.currentTimeMillis() + 675 * 60_000L
        val model = launch(longestEnds = ends, timerEnds = ends)
        val bedJet = connected(mine, model)

        bedJet.setFan(50)
        assertEquals(emptyList<List<Byte>>(), timerCommands(unit))
        assertAbout(675 * 60, remainingSeconds(unit))
    }

    @Test
    fun heatGetsBackTheTimeItWasCutButNoMore() = onMain {
        // Heat was chosen 90 minutes ago with 4 hours allowed, until 40 °C at 40% fan cut what was left to 2 hours.
        val unit = unit(mine.id)
        unit.device.send(Codec.setMode(Mode.HEAT)!!)
        unit.device.send(Codec.setTemperature(80))
        unit.device.send(Codec.setRuntime(120))
        val now = System.currentTimeMillis()
        val model = launch(longestEnds = now + 150 * 60_000L, timerEnds = now + 120 * 60_000L)
        val bedJet = connected(mine, model)

        // A gentler fan lets Heat run 4 hours again: the cut half hour comes back, but the run still ends when it would have.
        bedJet.setFan(5)
        assertEquals(listOf(Codec.setRuntime(150).toList()), timerCommands(unit))
        assertAbout(150 * 60, remainingSeconds(unit))
    }

    @Test
    fun aTimeSetElsewhereIsLeftAlone() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        bedJet.setMode(Mode.COOL)
        val unit = unit(mine.id)
        assertAbout(720 * 60, remainingSeconds(unit))

        // From another phone, or the BedJet's own remote.
        unit.device.send(Codec.setRuntime(90))
        val sent = unit.sent.size
        bedJet.setFan(50)
        assertEquals(emptyList<List<Byte>>(), timerCommands(unit, after = sent))
        assertAbout(90 * 60, remainingSeconds(unit))
    }

    @Test
    fun aTimePickedWhileTheModeIsStillStartingWins() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        // Cool waits for the BedJet to report it before giving it its longest run.
        val choosing = async { bedJet.setMode(Mode.COOL) }
        delay(100)
        bedJet.setRuntime(180)
        choosing.await()

        assertAbout(180 * 60, remainingSeconds(unit(mine.id)))
    }

    @Test
    fun aPickedTimeIsSavedForTheNextLaunch() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        bedJet.setMode(Mode.COOL)
        bedJet.setRuntime(180)

        model.appMovedToBackground()
        val relaunched = launch()
        val again = connected(mine, relaunched)
        val unit = unit(mine.id)
        val sent = unit.sent.size
        again.setFan(50)
        assertEquals(emptyList<List<Byte>>(), timerCommands(unit, after = sent))
        assertAbout(180 * 60, remainingSeconds(unit))
    }

    @Test
    fun aTimePickedWhileAnotherPhoneHandsTheBedJetOverWins() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        val unit = unit(mine.id)
        // Cool is tapped, and a time picked while the BedJet is still taking it.
        unit.holdsSends = true
        val choosing = async { bedJet.setMode(Mode.COOL) }
        waitUntil("Cool to be in flight") { unit.heldCount == 1 }
        val picking = async { bedJet.setRuntime(180) }
        waitUntil("the time to be in flight") { unit.heldCount == 2 }
        unit.releaseFirst()
        waitUntil("the BedJet to take Cool") { unit.lastStatus?.mode == Mode.COOL }
        unit.release()
        picking.await()
        choosing.await()

        assertAbout(180 * 60, remainingSeconds(unit))
    }

    @Test
    fun heatStillGetsBackItsTimeWhenAChangeIsReplaced() = onMain {
        // Heat at 40 °C with the gentlest fan was chosen on the Remote, and 150 of its 4 hours are left.
        val unit = unit(mine.id)
        unit.device.send(Codec.setMode(Mode.HEAT)!!)
        unit.device.send(Codec.setTemperature(80))
        unit.device.send(Codec.setFan(0))
        unit.device.send(Codec.setRuntime(150))
        val ends = System.currentTimeMillis() + 150 * 60_000L
        val model = launch(longestEnds = ends, timerEnds = ends)
        val bedJet = connected(mine, model)

        // A stronger fan cuts Heat to 2 hours, and a newer slider move replaces it before Lull reads the timer back.
        val stronger = async { bedJet.setFan(40) }
        waitUntil("the stronger fan to be sent") { unit.sent.contains(Codec.setFan(7).toList()) }
        stronger.cancel()
        stronger.join()
        assertAbout(120 * 60, remainingSeconds(unit))

        val sent = unit.sent.size
        bedJet.setFan(5)
        assertEquals(listOf(Codec.setRuntime(150).toList()), timerCommands(unit, after = sent))
        assertAbout(150 * 60, remainingSeconds(unit))
    }

    @Test
    fun turningTheAirOffForgetsTheRemotesTimer() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        bedJet.setMode(Mode.COOL)
        assertNotNull(bedJet.saved.longestEnds)

        bedJet.setMode(Mode.STANDBY)
        assertNull(bedJet.saved.longestEnds)
        assertNull(bedJet.saved.timerEnds)
    }

    @Test
    fun aDamagedSaveNeverSetsTheTimer() = onMain {
        val unit = unit(mine.id)
        unit.device.send(Codec.setMode(Mode.COOL)!!)
        unit.device.send(Codec.setRuntime(675))
        val model = launch(longestEnds = Long.MIN_VALUE, timerEnds = System.currentTimeMillis() + 675 * 60_000L)
        val bedJet = connected(mine, model)

        bedJet.setFan(50)
        assertEquals(emptyList<List<Byte>>(), timerCommands(unit))
        assertAbout(675 * 60, remainingSeconds(unit))
    }

    // Turning off

    @Test
    fun turningOffStopsShowingTheBedJetBeforeItReports() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        bedJet.setMode(Mode.HEAT)
        assertNotNull(LiveStatus.of(bedJet.run, bedJet.status, bedJet.statusReceivedAt, bedJet.generation))

        // Lull lets go of the BedJet once it leaves the screen, which can be before the BedJet says it's off. It took
        // the command, so it's no longer shown as running.
        unit(mine.id).reports = false
        bedJet.setMode(Mode.STANDBY)
        assertEquals(Mode.STANDBY, bedJet.status?.mode)
        assertNull(LiveStatus.of(bedJet.run, bedJet.status, bedJet.statusReceivedAt, bedJet.generation))
    }

    @Test
    fun aReportSentBeforeOffDoesntTurnTheBedJetBackOn() = onMain {
        val window = BedJet.staleReportWindow
        BedJet.staleReportWindow = 500
        try {
            val model = launch()
            model.choose(mine)
            val bedJet = connected(mine, model)
            bedJet.setMode(Mode.COOL)
            val running = requireNotNull(bedJet.status)
            assertTrue(running.isRunning)

            // The BedJet reports every second, so one it sent while still cooling can arrive just after it took Off.
            val link = unit(mine.id)
            link.reports = false
            val off = async { bedJet.setMode(Mode.STANDBY) }
            waitUntil("Off to be taken") { bedJet.status?.mode == Mode.STANDBY }
            link.onStatus?.invoke(running)
            assertEquals(Mode.STANDBY, bedJet.status?.mode)
            assertNull(LiveStatus.of(bedJet.run, bedJet.status, bedJet.statusReceivedAt, bedJet.generation))

            // Later, a report that it's running is believed: someone turned it on again.
            off.await()
            delay(600)
            link.onStatus?.invoke(running)
            assertEquals(Mode.COOL, bedJet.status?.mode)
        } finally {
            BedJet.staleReportWindow = window
        }
    }

    // From outside the app

    @Test
    fun aQuickRemoteButtonSetsItsBedJetOnceItConnects() = onMain {
        val model = launch()
        model.choose(mine)
        model.choose(partners)
        val first = bedJet(mine, model)
        // Tapped as Lull opens, before that BedJet has connected: it's shown, and set once it says what it's doing.
        model.applyQuickRemote(RemotePreset(Mode.HEAT, 45, 70, 30), to = first.id)
        assertEquals(first.id, model.bedJet?.id)

        val link = unit(mine.id)
        val sent = link.sent.toList()
        val mode = sent.indexOf(Codec.setMode(Mode.HEAT)!!.toList())
        val temperature = sent.indexOf(Codec.setTemperature(70).toList())
        val fan = sent.indexOf(Codec.setFan(5).toList())
        assertTrue(mode >= 0)
        assertTrue(mode < temperature)
        assertTrue(temperature < fan)
        // Last, the time, which the mode, temperature and fan each stretched to the longest Heat allows.
        assertEquals(Codec.setRuntime(45).toList(), timerCommands(link).last())
        assertTrue(sent.lastIndexOf(Codec.setRuntime(45).toList()) > fan)
        assertEquals(Mode.HEAT, link.lastStatus?.mode)
        assertEquals(70, link.lastStatus?.targetTemperature)
        assertAbout(45 * 60, remainingSeconds(link))
        // The other BedJet is left as it was.
        assertFalse(unit(partners.id).sent.contains(Codec.setMode(Mode.HEAT)!!.toList()))
    }

    @Test
    fun aWidgetNeverTakesABedJetFromAnotherPhone() = onMain {
        // The partner's phone has their BedJet, which this phone knows too.
        setups[partners.id] = { it.heldElsewhere = true }
        val model = launch()
        model.choose(partners)
        model.choose(mine)
        connected(mine, model)
        val theirs = bedJet(partners, model)
        assertEquals(LinkState.YIELDED, theirs.linkState)

        // A widget set to their side opens Lull on it, but no button on it asks their phone for it.
        model.applyQuickRemote(RemotePreset.defaults[2], to = theirs.id)
        assertEquals(theirs.id, model.bedJet?.id)
        model.startNight(on = theirs.id)
        assertEquals(0, unit(partners.id).requests)
        assertEquals(emptyList<List<Byte>>(), unit(partners.id).sent)
        assertNull(theirs.run)

        // Touching a control in Lull still takes it over, as the Remote says it will.
        theirs.setMode(Mode.HEAT)
        assertEquals(1, unit(partners.id).requests)
        assertTrue(unit(partners.id).sent.contains(Codec.setMode(Mode.HEAT)!!.toList()))
    }

    @Test
    fun aWidgetLeavesAScheduleFromAnotherPhoneRunning() = onMain {
        // The partner's phone started a schedule on this BedJet, then let go of it, as Lull does off screen.
        val theirs = StoredProgram("Theirs", listOf(ProgramStep(Mode.COOL, fanStep = 7, temperature = 40, hours = 2, minutes = 0)))
        setups[mine.id] = { it.device = DemoLink("BEDJET_3", listOf(theirs, null, null), units) }
        unit(mine.id).device.startProgram(0)
        assertTrue(unit(mine.id).device.programStep > 0)
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        assertNotNull(bedJet.runningElsewhereStep)

        // Neither Off on a Quick Remote nor Start Night ends it.
        val sent = unit(mine.id).sent.toList()
        model.applyQuickRemote(RemotePreset.defaults[2], to = null)
        model.startNight(on = bedJet.id)
        assertEquals(sent, unit(mine.id).sent)
        assertTrue(unit(mine.id).device.programStep > 0)
        assertNull(bedJet.run)
    }

    @Test
    fun aQuickRemoteButtonDoesNothingItCant() = onMain {
        setups[mine.id] = { it.generation = Generation.V2 }
        val model = launch()
        model.choose(partners)
        model.choose(mine)
        val first = connected(mine, model)
        // What Lull reads from a BedJet on connecting is done.
        delay(2_000)
        val sent = unit(mine.id).sent.toList()

        // Dry, which a V2 lacks, and a BedJet forgotten since, or never known.
        model.applyQuickRemote(RemotePreset(Mode.DRY, 45, 60, 60), to = first.id)
        model.applyQuickRemote(RemotePreset.defaults[0], to = UUID.randomUUID().toString())
        assertEquals(sent, unit(mine.id).sent)
        assertEquals(first.id, model.bedJet?.id)
        assertFalse(unit(partners.id).sent.contains(Codec.setMode(Mode.COOL)!!.toList()))
    }

    // Automations

    /** Lull in the background with a BedJet it was using, as when an automation runs at bedtime. */
    private suspend fun backgrounded(unit: NearbyBedJet): Pair<AppModel, BedJet> {
        val model = launch()
        model.choose(unit)
        val bedJet = connected(unit, model)
        model.appMovedToBackground()
        waitUntil("${unit.name} to be let go") { !bedJet.isConnected }
        return model to bedJet
    }

    private suspend fun failure(action: suspend () -> String): String? {
        try {
            action()
            fail("The action should have failed")
        } catch (error: AutomationFailure) {
            return error.message
        }
        return null
    }

    @Test
    fun aLaunchOffScreenConnectsToNothing() = onMain {
        val model = launch()
        model.choose(mine)
        connected(mine, model)
        model.appMovedToBackground()

        fakes.clear()
        val relaunched = launch(onScreen = false)
        assertEquals(mine.id, relaunched.bedJet?.unit)
        assertFalse(relaunched.status(relaunched.bedJet!!).nightRunning)
        // Its link is set up for later, but nothing connects, and no other phone is asked for the BedJet.
        assertEquals(0, unit(mine.id).starts + unit(mine.id).listens + unit(mine.id).requests)
    }

    @Test
    fun anActionWaitsOutTheBedJetsWiFi() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        val joining = async { bedJet.joinWiFi("Home", "password1") }
        waitUntil("the BedJet to start on its Wi-Fi") { bedJet.wifiActivity?.isRunning == true }
        val message = failure { model.perform(AutomationRequest.LightRing(on = false), bedJet) }
        assertEquals(AutomationError.Updating.message, message)
        joining.await()
        assertEquals(false, bedJet.wifiActivity?.failed)
    }

    /** Lull in the background with a BedJet that began installing an update, as after updating it at bedtime. */
    private suspend fun installingAnUpdate(): Pair<AppModel, BedJet> {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        bedJet.joinWiFi("Home", "password1")
        unit(mine.id).device.offerFirmwareUpdate()
        bedJet.updateFirmware()
        assertEquals(true, bedJet.wifiActivity?.awaitsRestart)
        model.appMovedToBackground()
        waitUntil("${mine.name} to be let go") { !bedJet.isConnected }
        return model to bedJet
    }

    @Test
    fun anActionIsRefusedWhileTheBedJetInstallsAnUpdate() = onMain {
        val (model, bedJet) = installingAnUpdate()
        val listens = unit(mine.id).listens
        val message = failure { model.perform(AutomationRequest.Air(AirSettings(mode = Mode.HEAT)), bedJet) }
        assertEquals(AutomationError.Updating.message, message)
        // It doesn't wait to reach a BedJet that is installing, or offer to go on in Lull.
        assertEquals(listens, unit(mine.id).listens)
    }

    @Test
    fun anActionGoesAheadOnceTheBedJetShouldBeBackFromAnUpdate() = onMain {
        val (model, bedJet) = installingAnUpdate()
        // As when Lull wakes for the action long after the install, before its own timer has caught up.
        val patience = BedJet.restartPatienceMillis
        BedJet.restartPatienceMillis = 0
        try {
            val done = model.perform(AutomationRequest.Air(AirSettings(mode = Mode.HEAT)), bedJet)
            assertEquals("Turned on Heat on BEDJET_3.", done)
        } finally {
            BedJet.restartPatienceMillis = patience
        }
    }

    @Test
    fun theBedJetStaysBusyWhileAnyCommandIsUnderWay() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        unit(mine.id).holdsSends = true
        val lightRing = async { bedJet.setLED(false) }
        waitUntil("the light ring to be on its way") { unit(mine.id).isHolding }
        // An action that sends nothing finishes while the light ring is still on its way, which keeps the BedJet busy.
        val done = model.perform(AutomationRequest.TurnOff, bedJet)
        assertEquals("BEDJET_3 is already off.", done)
        assertTrue(bedJet.isBusy)
        unit(mine.id).release()
        lightRing.await()
        assertFalse(bedJet.isBusy)
    }

    @Test
    fun startNightSetToADeletedScheduleSaysSo() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val message = failure { model.perform(AutomationRequest.StartNight(UUID.randomUUID().toString()), bedJet) }
        assertEquals(AutomationError.ForgottenSchedule.message, message)
    }

    @Test
    fun anActionSetToAForgottenBedJetSaysSo() = onMain {
        val (model, bedJet) = backgrounded(mine)
        model.forget(bedJet)
        try {
            model.automationTarget(bedJet.id)
            fail("A forgotten BedJet should be said to be forgotten")
        } catch (_: AutomationError.ForgottenBedJet) {
        }
        // Lull never picks another in its place.
        try {
            model.automationTarget(null)
            fail("No BedJet should be picked in its place")
        } catch (_: AutomationError.NoBedJet) {
        }
    }

    @Test
    fun aScheduleNamedByAnAutomationMustPickOutOne() = onMain {
        val model = launch()
        model.save(Schedule("Twin", model.schedules[0].phases))
        model.save(Schedule("Twin", model.schedules[1].phases))
        try {
            AutomationCall(AutomationCall.START_NIGHT, schedule = "twin").request(model)
            fail("Two schedules called Twin should be refused")
        } catch (error: AutomationFailure) {
            assertEquals("Lull has more than one schedule called “twin”. Rename one in Lull.", error.message)
        }
        val coolDrift = model.schedules.first { it.name == "Cool Drift" }
        assertEquals(AutomationRequest.StartNight(coolDrift.id), AutomationCall(AutomationCall.START_NIGHT, schedule = "cool drift").request(model))
    }

    @Test
    fun aStatusSentToLullsScreenLeavesTheBedJetShown() = onMain {
        val model = launch()
        model.choose(mine)
        model.choose(partners)
        connected(mine, model)
        connected(partners, model)
        assertTrue(model.select(bedJet(mine, model).id))
        val other = model.title(bedJet(partners, model))
        runAutomation(model, AutomationCall(AutomationCall.GET_STATUS, bedJet = other), onScreen = true)
        assertEquals(mine.id, model.bedJet?.unit)
    }

    @Test
    fun anActionOffScreenReachesAFreeBedJetAndLetsGo() = onMain {
        val model = launch()
        model.choose(mine)
        connected(mine, model)
        model.appMovedToBackground()

        fakes.clear()
        val relaunched = launch(onScreen = false)
        val target = relaunched.automationTarget(null)
        val done = relaunched.perform(AutomationRequest.StartNight(null), target)
        assertEquals("Started “Cool Drift” on BEDJET_3.", done)
        assertNotNull(target.run)
        assertTrue(relaunched.status(target).nightRunning)
        // It never asked another phone for the BedJet, and let go of it once done.
        assertEquals(0, unit(mine.id).starts)
        assertEquals(0, unit(mine.id).requests)
        assertTrue(unit(mine.id).listens > 0)
        assertTrue(unit(mine.id).stops > 0)
        assertFalse(target.isConnected)
        assertNull(relaunched.errorMessage)
    }

    @Test
    fun anActionOffScreenGivesUpOnABedJetThatIsntFree() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val sentBefore = unit(mine.id).sent.toList()
        unit(mine.id).answers = false
        model.reachPatienceMillis = 1_000
        try {
            model.perform(AutomationRequest.Air(AirSettings(mode = Mode.HEAT)), bedJet)
            fail("The action should have asked to go on in Lull")
        } catch (error: AutomationError.NeedsLull) {
        }
        assertEquals(sentBefore, unit(mine.id).sent)
        assertEquals(0, unit(mine.id).requests)
        assertNull(model.errorMessage)
    }

    @Test
    fun anActionNeverAsksForABedJetBackThatWentToAnotherPhone() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val link = unit(mine.id)
        val sentBefore = link.sent.toList()
        // Another phone gets the BedJet anyway while the action waits for its first report, as when the connection drops.
        link.reports = false
        val action = async { runCatching { model.perform(AutomationRequest.LightRing(on = true), bedJet) } }
        waitUntil("the BedJet to connect") { bedJet.isConnected }
        delay(300)
        link.heldElsewhere = true
        link.onStateChange?.invoke(LinkState.YIELDED)
        link.reports = true

        val result = action.await()
        assertTrue(result.isFailure)
        // It reached the BedJet first, so it fails in the wait rather than by finding the BedJet already taken.
        assertFalse(result.exceptionOrNull() is AutomationError.NeedsLull)
        assertEquals(sentBefore, link.sent)
        assertEquals(0, link.requests)
    }

    @Test
    fun aCancelledActionStopsWaitingForTheBedJetAtOnce() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val link = unit(mine.id)
        link.reports = false
        val action = async { model.perform(AutomationRequest.LightRing(on = true), bedJet) }
        waitUntil("the BedJet to connect") { bedJet.isConnected }
        delay(300)

        val cancelled = System.currentTimeMillis()
        action.cancelAndJoin()
        assertTrue(action.isCancelled)
        // Not left polling until the wait for the report would have run out.
        assertTrue(System.currentTimeMillis() - cancelled < 1_000)
    }

    @Test
    fun anActionOutOfBackgroundTimeDuringItsCommandSendsNothingMore() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val link = unit(mine.id)
        link.holdsSends = true
        val action = async { model.perform(AutomationRequest.StartNight(null), bedJet) }
        waitUntil("tonight to be on its way to the BedJet") { link.isHolding }
        // Android stops the action's work while the BedJet is being given tonight, which cancels it, as iOS running out of
        // background time does.
        action.cancel()
        // Lull is next opened, the BedJet connects again, and only then does the command in flight finish.
        model.appBecameActive()
        waitUntil("the BedJet to connect again") { bedJet.isConnected }
        link.release()

        action.join()
        assertTrue(action.isCancelled)
        // Tonight was never started, long after the automation app gave up on it.
        assertNull(bedJet.run)
        assertEquals(0, link.device.programStep)
    }

    @Test
    fun anAirActionOutOfBackgroundTimeSendsNothingAfterTheCommandInFlight() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        // The air is on with a time picked on the Remote, so nothing waits between the action's commands.
        bedJet.setMode(Mode.HEAT)
        bedJet.setRuntime(minutes = 60)
        model.appMovedToBackground()
        waitUntil("${mine.name} to be let go") { !bedJet.isConnected }
        val link = unit(mine.id)
        link.holdsSends = true
        val action = async { model.perform(AutomationRequest.Air(AirSettings(degrees = 30, fanPercent = 60)), bedJet) }
        waitUntil("the temperature to be on its way to the BedJet") { link.isHolding }
        val sent = link.sent.size
        // Android stops the action's work while the temperature is on its way, which cancels it, as iOS running out of
        // background time does.
        action.cancel()
        // Lull is next opened, the BedJet connects again, and only then does the command in flight finish.
        model.appBecameActive()
        waitUntil("the BedJet to connect again") { bedJet.isConnected }
        link.release()

        action.join()
        assertTrue(action.isCancelled)
        // Android drops the command in flight as it's cancelled, where iOS lets it finish. Either way the fan never went
        // out, long after the automation app gave up on it.
        assertEquals(sent, link.sent.size)
        assertNotEquals(60, link.lastStatus?.fanPercent)
    }

    @Test
    fun nothingElseStartsANightWhileAnActionWaitsForTheBedJet() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val link = unit(mine.id)
        link.reports = false
        val action = async { model.perform(AutomationRequest.StartNight(null), bedJet) }
        waitUntil("the BedJet to connect") { bedJet.isConnected }
        delay(300)
        // The watch asks for Start Night while the action waits to hear from the BedJet.
        model.startNight(on = bedJet)
        assertNull(bedJet.run)
        link.reports = true

        val done = action.await()
        assertTrue(done, done.startsWith("Started"))
        assertEquals(1, bedJet.history.count { it.event is HistoryEntry.Event.ScheduleStarted })
    }

    @Test
    fun anActionsStartNightLeavesTheOneLullIsStarting() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        val link = unit(mine.id)
        // Start Night in Lull is still writing tonight's schedule when an action asks for a night too.
        link.holdsSends = true
        val tapped = async { bedJet.startTonight() }
        waitUntil("Start Night to be in flight") { link.isHolding }
        val action = async { model.perform(AutomationRequest.StartNight(null), bedJet) }
        delay(300)
        link.release()
        tapped.await()

        val done = action.await()
        val running = bedJet.run!!
        assertEquals("“${running.schedule.name}” is already running on BEDJET_3.", done)
        assertEquals(1, bedJet.history.count { it.event is HistoryEntry.Event.ScheduleStarted })
    }

    @Test
    fun aTurnOffActionEndsTheNightLullIsStarting() = onMain {
        val model = launch()
        model.choose(mine)
        val bedJet = connected(mine, model)
        val link = unit(mine.id)
        // Start Night in Lull is still writing tonight's schedule when an action turns the BedJet off.
        link.holdsSends = true
        val tapped = async { bedJet.startTonight() }
        waitUntil("Start Night to be in flight") { link.isHolding }
        val name = bedJet.tonight!!.name
        val action = async { model.perform(AutomationRequest.TurnOff, bedJet) }
        delay(300)
        link.release()
        tapped.await()

        assertEquals("Ended “$name” on BEDJET_3.", action.await())
        assertNull(bedJet.run)
        // The BedJet itself isn't left running the night.
        assertEquals(0, link.device.programStep)
    }

    @Test
    fun comingOnScreenDuringAnActionKeepsTheBedJet() = onMain {
        val (model, bedJet) = backgrounded(mine)
        val stopsBefore = unit(mine.id).stops
        unit(mine.id).holdsSends = true
        val action = async { model.perform(AutomationRequest.LightRing(on = false), bedJet) }
        waitUntil("the action to be in flight") { unit(mine.id).isHolding }
        model.appBecameActive()
        unit(mine.id).release()
        assertEquals("Turned off the light ring on BEDJET_3.", action.await())
        assertTrue(bedJet.isConnected)
        assertEquals(stopsBefore, unit(mine.id).stops)
    }

    @Test
    fun startNightLeavesARunningNightAlone() = onMain {
        val model = launch()
        model.choose(mine)
        val first = connected(mine, model)
        first.startTonight()
        model.appMovedToBackground()

        val sentBefore = unit(mine.id).sent.toList()
        val done = model.perform(AutomationRequest.StartNight(model.schedules[2].id), first)
        assertEquals("“Cool Drift” is already running on BEDJET_3.", done)
        assertEquals(sentBefore, unit(mine.id).sent)
        assertEquals("Cool Drift", first.run?.schedule?.name)
    }

    @Test
    fun fullSlotsAreAnErrorForAnAction() = onMain {
        val theirs = StoredProgram("Theirs", listOf(ProgramStep(Mode.COOL, fanStep = 7, temperature = 40, hours = 2, minutes = 0)))
        setups[mine.id] = { it.device = DemoLink("BEDJET_3", listOf(theirs, theirs, theirs), units) }
        val (model, bedJet) = backgrounded(mine)
        val message = failure { model.perform(AutomationRequest.StartNight(null), bedJet) }
        assertEquals(AutomationError.SlotsFull.message, message)
        // Nobody is there to pick a slot, so none is asked for or replaced.
        assertFalse(bedJet.needsSlotChoice)
        assertNull(bedJet.run)
        assertEquals(listOf(theirs, theirs, theirs), unit(mine.id).device.slots)
        assertNull(model.errorMessage)
    }

    @Test
    fun fullSlotsAreAskedAboutOnceAnActionGoesOnInLull() = onMain {
        val theirs = StoredProgram("Theirs", listOf(ProgramStep(Mode.COOL, fanStep = 7, temperature = 40, hours = 2, minutes = 0)))
        setups[mine.id] = { it.device = DemoLink("BEDJET_3", listOf(theirs, theirs, theirs), units) }
        val (model, bedJet) = backgrounded(mine)
        val done = model.perform(AutomationRequest.StartNight(null), bedJet, mayAsk = true)
        assertEquals("Your BedJet's three slots are full. Pick one to reuse in Lull.", done)
        assertTrue(bedJet.needsSlotChoice)
        assertNull(bedJet.run)
    }

    @Test
    fun actionsOneAfterAnotherEachReachTheBedJet() = onMain {
        val (model, bedJet) = backgrounded(mine)
        // Over Bluetooth, the connection one action lets go of takes a moment to drop, while the next starts at once.
        unit(mine.id).dropsSlowly = true
        model.perform(AutomationRequest.Air(AirSettings(mode = Mode.HEAT)), bedJet)
        val fan = model.perform(AutomationRequest.Air(AirSettings(fanPercent = 70)), bedJet)
        assertEquals("Set the fan on BEDJET_3 to 70%.", fan)
        val runTime = model.perform(AutomationRequest.Air(AirSettings(minutes = 55)), bedJet)
        assertEquals("BEDJET_3 turns off in 55 min.", runTime)
        assertTrue(unit(mine.id).sent.contains(Codec.setFan(13).toList()))
    }

    @Test
    fun turnOffEndsTheNightOrStopsTheAir() = onMain {
        val (model, bedJet) = backgrounded(mine)
        model.perform(AutomationRequest.StartNight(null), bedJet)
        assertEquals("Ended “Cool Drift” on BEDJET_3.", model.perform(AutomationRequest.TurnOff, bedJet))
        assertNull(bedJet.run)
        assertEquals("BEDJET_3 is already off.", model.perform(AutomationRequest.TurnOff, bedJet))
        val airOff = failure { model.perform(AutomationRequest.Air(AirSettings(fanPercent = 50)), bedJet) }
        assertEquals(AutomationError.AirOff.message, airOff)

        assertEquals("Turned on Cool on BEDJET_3.", model.perform(AutomationRequest.Air(AirSettings(mode = Mode.COOL)), bedJet))
        assertEquals("Set the fan on BEDJET_3 to 50%.", model.perform(AutomationRequest.Air(AirSettings(fanPercent = 52)), bedJet))
        assertTrue(Codec.setFan(9).toList() in unit(mine.id).sent)
        assertEquals("Turned off BEDJET_3.", model.perform(AutomationRequest.TurnOff, bedJet))
        assertTrue(Codec.setMode(Mode.STANDBY)!!.toList() in unit(mine.id).sent)
        assertNull(model.errorMessage)
    }

    @Test
    fun aV2RefusesAModeItCantRunFromAnAction() = onMain {
        setups[mine.id] = { it.generation = Generation.V2 }
        val (model, bedJet) = backgrounded(mine)
        val sentBefore = unit(mine.id).sent.toList()
        val message = failure { model.perform(AutomationRequest.Air(AirSettings(mode = Mode.DRY)), bedJet) }
        assertEquals("BEDJET_3 is a BedJet V2, which can't run Dry.", message)
        assertEquals(sentBefore, unit(mine.id).sent)
    }

    @Test
    fun turnOnRunsAsLongAsAllowedRatherThanToATimePickedEarlier() = onMain {
        val model = launch()
        model.remoteTimerShowsTime = true
        model.choose(mine)
        val bedJet = connected(mine, model)
        bedJet.setMode(Mode.COOL)
        bedJet.setRuntime(untilMillis = System.currentTimeMillis() + 30 * 60_000L)
        assertTrue(bedJet.remoteTimer.isAhead)
        model.appMovedToBackground()
        waitUntil("${mine.name} to be let go") { !bedJet.isConnected }

        model.perform(AutomationRequest.Air(AirSettings(mode = Mode.HEAT)), bedJet)
        assertEquals(RemoteTimer.Longest, bedJet.remoteTimer)
    }

    @Test
    fun theRunTimeIsLeftToARunningSchedule() = onMain {
        val (model, bedJet) = backgrounded(mine)
        model.perform(AutomationRequest.StartNight(null), bedJet)
        val sentBefore = unit(mine.id).sent.toList()
        val message = failure { model.perform(AutomationRequest.Air(AirSettings(minutes = 30)), bedJet) }
        assertEquals(AutomationError.ScheduleSetsTiming.message, message)
        assertEquals(sentBefore, unit(mine.id).sent)
        assertNotNull(bedJet.run)
        assertNull(model.errorMessage)
    }
}
