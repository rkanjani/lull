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
import com.rohankanjani.lull.bedjetkit.takes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

    private fun launch(demo: Boolean = false): AppModel {
        val storage = Storage(
            directory = directory,
            prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
            linkPrefs = context.getSharedPreferences("$prefsName.link", Context.MODE_PRIVATE),
        )
        return AppModel(context, LaunchOptions(), storage, demoMode = demo, makesLink = { unit(it.unit!!) }, discovery = { discovery })
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
}

/**
 * A BedJet for tests. It speaks like the simulated one, but connects only when allowed, records every command,
 * can hold a command in flight, and keeps its state between connections like a real unit.
 */
class FakeLink(name: String, private val scope: CoroutineScope) : BedJetLink {
    var device: DemoLink = DemoLink(name, scope = scope)
        set(value) {
            field = value
            attach()
        }
    override var generation: Generation = Generation.V3
    var answers = true
    var holdsSends = false
    val sent = mutableListOf<List<Byte>>()
    var stops = 0
        private set
    private val held = mutableListOf<CompletableDeferred<Unit>>()
    val isHolding: Boolean get() = held.isNotEmpty()

    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null
    override val state: LinkState get() = device.state
    override val lastStatus: DeviceStatus? get() = device.lastStatus

    init {
        attach()
    }

    private fun attach() {
        device.onStateChange = { onStateChange?.invoke(it) }
        device.onStatus = { onStatus?.invoke(it) }
    }

    override fun start() {
        device.answers = answers
        device.start()
    }

    override fun stop() {
        stops += 1
        device.stop()
    }

    override fun requestControl() = start()

    override suspend fun send(command: ByteArray) {
        if (holdsSends) CompletableDeferred<Unit>().also { held += it }.await()
        if (state != LinkState.READY) throw BedJetError.NotConnected
        sent += command.toList()
        device.send(command)
    }

    override suspend fun query(request: Int): ByteArray {
        if (state != LinkState.READY) throw BedJetError.NotConnected
        return device.query(request)
    }

    fun release() {
        holdsSends = false
        val waiting = held.toList()
        held.clear()
        for (continuation in waiting) continuation.complete(Unit)
    }

    /** A status report, however late it arrives. */
    fun report(status: DeviceStatus) {
        onStatus?.invoke(status)
    }
}
