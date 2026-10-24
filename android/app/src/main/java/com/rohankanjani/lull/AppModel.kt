package com.rohankanjani.lull

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rohankanjani.lull.bedjetkit.BedJetDiscovery
import com.rohankanjani.lull.bedjetkit.BedJetLink
import com.rohankanjani.lull.bedjetkit.DemoDiscovery
import com.rohankanjani.lull.bedjetkit.DemoLink
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.NearbyBedJet
import com.rohankanjani.lull.bedjetkit.StoredProgram
import com.rohankanjani.lull.widgets.WidgetUpdates
import com.rohankanjani.lull.widgets.requestTileUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Launch flags, passed as intent extras. They match the iOS app's launch arguments. */
data class LaunchOptions(
    /** UI tests launch with this so every run starts from the built-in schedules. */
    val ephemeral: Boolean = false,
    /** Makes a night started in demo mode appear this far along, for listing screenshots. */
    val demoElapsedMinutes: Int = 0,
    /** Shows the simulated BedJet as connected, for listing screenshots. */
    val labelsDemoAsConnected: Boolean = false,
    /** Keeps a record of connection times, so they can be measured on a real phone. */
    val logsConnections: Boolean = false,
    /** Fills an empty History with a few sample nights, for listing screenshots. */
    val demoHistory: Boolean = false,
    /** Starts demo mode with no BedJet chosen, as a first launch with real ones does. */
    val demoStartsUnchosen: Boolean = false,
    /** Makes the first simulated BedJet never answer, as if it were unplugged. */
    val demoFirstUnitAway: Boolean = false,
)

/** Where Lull keeps what it saves. UI tests (`ephemeral`) and model tests keep nothing in the app's own place. */
class Storage(
    /** Null keeps nothing between launches. */
    val directory: File?,
    val prefs: SharedPreferences,
    /** Where the Bluetooth link keeps what it knows about units, including the one an earlier version remembered. */
    val linkPrefs: SharedPreferences,
    /** Only the app itself hands the widgets what they show. */
    val publishesWidgets: Boolean = false,
) {
    fun file(name: String): File? = directory?.let { File(it, name) }
    fun historyFile(id: String): File? = file("history-$id.json")

    companion object {
        fun app(context: Context, options: LaunchOptions) = Storage(
            directory = if (options.ephemeral) null else context.filesDir,
            prefs = context.getSharedPreferences("Lull", Context.MODE_PRIVATE),
            linkPrefs = BleLink.preferences(context),
            publishesWidgets = true,
        )
    }
}

/**
 * @param makeLink Connects to a BedJet. By default a simulated one in demo mode and Bluetooth otherwise.
 * @param discovery Finds BedJets nearby, given whether demo mode is on.
 */
class AppModel(
    application: Application,
    val options: LaunchOptions,
    val storage: Storage = Storage.app(application, options),
    demoMode: Boolean? = null,
    private val makesLink: ((BedJet) -> BedJetLink)? = null,
    discovery: ((Boolean) -> BedJetDiscovery)? = null,
) : AndroidViewModel(application) {
    /**
     * The BedJets this phone was told to use, in the order they were chosen. Each stays connected, so a
     * schedule or setting on one is never disturbed by looking at another.
     */
    var bedJets by mutableStateOf<List<BedJet>>(emptyList()); private set
    var selectedID by mutableStateOf<String?>(null); private set
    /** The BedJet every screen shows and controls. None until one is chosen, and Lull never picks one by itself. */
    val bedJet: BedJet? get() = bedJets.firstOrNull { it.id == selectedID }
    val hasSeveralBedJets: Boolean get() = bedJets.size > 1
    /** Switching waits while a schedule is being saved to the BedJet on screen, so how that went is seen. */
    val canSwitch: Boolean get() = bedJet?.isSavingSchedule != true

    /** BedJets advertising nearby that aren't already known, while a picker is looking. */
    val nearby: List<NearbyBedJet> get() = found.filter { unit -> bedJets.none { it.unit == unit.id } }
    /** [LinkState.SEARCHING] while looking is possible, otherwise why it isn't. */
    var lookingState by mutableStateOf(LinkState.SEARCHING); private set
    private var found by mutableStateOf<List<NearbyBedJet>>(emptyList())

    private var savedSchedules by mutableStateOf<List<Schedule>>(emptyList())
    var schedules: List<Schedule>
        get() = savedSchedules
        set(value) { savedSchedules = value; save() }

    private val prefs = storage.prefs

    private var fahrenheit by mutableStateOf(prefs.getBoolean("useFahrenheit", usesUSUnits()))
    var useFahrenheit: Boolean
        get() = fahrenheit
        set(value) {
            fahrenheit = value
            prefs.edit().putBoolean("useFahrenheit", value).apply()
            publishWidgets()
            publishLiveNotifications()
        }

    private var demo by mutableStateOf(demoMode ?: (isEmulator() || prefs.getBoolean("demoMode", false)))
    var demoMode: Boolean
        get() = demo
        set(value) {
            if (value == demo) return
            demo = value
            prefs.edit().putBoolean("demoMode", value).apply()
            val leaving = fleet
            show(otherFleet ?: startingFleet(demo = value))
            otherFleet = leaving
            restartLooking()
            save()
        }

    var errorMessage by mutableStateOf<String?>(null)

    /** Set when a BedJet would be shown in a Live Update but notifications aren't allowed, so Lull asks once. */
    var wantsNotificationPermission by mutableStateOf(false); private set

    /**
     * Where the screens run what the person asked for. It outlives the screen that asked, as an
     * unstructured Task does on iOS, so a card that disappears mid-save doesn't cancel the save.
     */
    val scope: CoroutineScope get() = viewModelScope

    private val makesDiscovery: (Boolean) -> BedJetDiscovery =
        discovery ?: { demo -> if (demo) DemoDiscovery() else BleDiscovery(application) }
    private var discovery: BedJetDiscovery? = null
    private var lookers = 0
    private var isLoaded = false
    /** The BedJets of the mode not in use, kept as they were saved. */
    private var otherFleet: Fleet? = null
    /** Forgotten units, with the history they had, so choosing one again brings its nights back. */
    private var retired: Map<String, String> = emptyMap()
    /** Set once the BedJet on screen was forgotten, so the next launch asks rather than picking another. */
    private var needsChoice = false

    override fun onCleared() {
        for (bedJet in bedJets) bedJet.pause()
        discovery?.stop()
    }

    // BedJets

    /** Shows another known BedJet. It only asks another phone for the unit once a control is used. */
    fun select(id: String): Boolean {
        if (id == selectedID) return true
        if (!canSwitch || bedJets.none { it.id == id }) return false
        selectedID = id
        needsChoice = false
        save()
        return true
    }

    /** Adds a BedJet the person picked from those nearby and shows it. */
    fun choose(unit: NearbyBedJet) {
        val known = bedJets.firstOrNull { it.unit == unit.id }
        if (known != null) {
            select(known.id)
            return
        }
        if (!canSwitch) return
        val id = retired[unit.id] ?: UUID.randomUUID().toString()
        retired = retired - unit.id
        val saved = SavedBedJet(
            id = id, unit = unit.id, name = unit.name, generation = unit.generation,
            tonightID = bedJet?.tonightID ?: schedules.firstOrNull()?.id,
        )
        val added = BedJet(saved, isDemo = demoMode, app = this)
        bedJets = bedJets + added
        selectedID = added.id
        needsChoice = false
        added.connect(asking = true)
        save()
    }

    /**
     * Stops using a BedJet. The unit carries on as it is, its nights are kept should it be chosen again,
     * and if it was on screen Lull asks which BedJet to show rather than moving on to another by itself.
     */
    fun forget(bedJet: BedJet) {
        bedJet.pause()
        bedJet.unit?.let { unit ->
            retired = retired + (unit to bedJet.id)
            if (!bedJet.isDemo) BleLink.forget(getApplication(), unit)
        }
        bedJets = bedJets.filter { it !== bedJet }
        if (selectedID == bedJet.id) {
            selectedID = null
            needsChoice = true
        }
        save()
    }

    /**
     * What a BedJet is called on screen: the nickname given here, or the name it gives itself, with a short
     * code from its Bluetooth identity when another known BedJet goes by the same name.
     */
    fun title(of: BedJet): String {
        val name = of.displayName
        val unit = of.unit
        if (unit == null || bedJets.none { it !== of && it.displayName == name }) return name
        return "$name · ${code(unit)}"
    }

    /** A nearby unit's name, with its code when another BedJet nearby or known has the same name. */
    fun title(of: NearbyBedJet): String {
        val twins = found.any { it.id != of.id && it.name == of.name } || bedJets.any { it.displayName == of.name }
        return if (twins) "${of.name} · ${code(of.id)}" else of.name
    }

    fun report(message: String, from: BedJet) {
        errorMessage = if (hasSeveralBedJets) "${title(from)}: $message" else message
    }

    fun appMovedToBackground() {
        for (bedJet in bedJets) bedJet.pause()
        discovery?.stop()
        // A redraw asked for moments after another can be held back, and the BedJet changes in bursts, so the
        // widgets are redrawn once more on leaving, with what Lull last knew.
        publishWidgets(forced = true)
    }

    fun appBecameActive() {
        for (bedJet in bedJets) bedJet.resume(asking = bedJet.id == selectedID)
        if (lookers > 0) discovery?.start()
    }

    /** Bluetooth access was just granted or refused; look at the radio again. */
    fun permissionsChanged() {
        if (demoMode) return
        for (bedJet in bedJets) bedJet.connect(asking = bedJet.id == selectedID)
        if (lookers > 0) discovery?.start()
    }

    fun makeLink(bedJet: BedJet): BedJetLink {
        makesLink?.let { return it(bedJet) }
        if (!bedJet.isDemo) return BleLink(getApplication(), bedJet.unit)
        return DemoLink(
            name = bedJet.name ?: DemoLink.DEFAULT_NAME,
            slots = listOf(StoredProgram("Summer nights", Schedule.templates[2].program.steps), null, null),
            scope = scope,
        ).apply { answers = !(options.demoFirstUnitAway && bedJet.unit == DemoDiscovery.SIMULATED[0].id) }
    }

    private fun show(fleet: Fleet) {
        for (bedJet in bedJets) bedJet.pause()
        retired = fleet.retired.orEmpty()
        needsChoice = fleet.needsChoice ?: false
        bedJets = fleet.bedJets.map { BedJet(it, isDemo = demoMode, app = this) }
        // Versions that knew several BedJets showed the first until another was picked.
        selectedID = if (needsChoice) null
        else fleet.selected?.let { id -> bedJets.firstOrNull { it.id == id }?.id } ?: bedJets.firstOrNull()?.id
        for (bedJet in bedJets) bedJet.connect(asking = bedJet.id == selectedID)
    }

    // Looking for BedJets

    /** Starts looking for BedJets nearby, while a picker is open. Looking never connects or changes anything. */
    fun startLooking() {
        lookers += 1
        if (lookers != 1) return
        val discovery = discovery ?: makesDiscovery(demoMode).also { discovery ->
            discovery.onChange = {
                if (this.discovery === discovery) {
                    found = discovery.nearby
                    lookingState = discovery.state
                }
            }
            this.discovery = discovery
        }
        discovery.start()
    }

    fun stopLooking() {
        lookers = maxOf(lookers - 1, 0)
        if (lookers != 0) return
        discovery?.stop()
        found = emptyList()
    }

    private fun restartLooking() {
        discovery?.stop()
        discovery = null
        found = emptyList()
        if (lookers == 0) return
        lookers -= 1
        startLooking()
    }

    // Schedules

    /** Keeps a copy of a program made elsewhere, so reusing its slot never loses it. */
    fun keepCopy(program: StoredProgram) {
        val imported = Schedule.importing(program)
        if (schedules.none { existing -> existing.phases.map { it.step } == imported.phases.map { it.step } }) {
            schedules = schedules + imported
        }
    }

    fun save(schedule: Schedule) {
        val index = schedules.indexOfFirst { it.id == schedule.id }
        schedules = if (index >= 0) schedules.toMutableList().also { it[index] = schedule } else schedules + schedule
    }

    fun delete(schedule: Schedule) {
        schedules = schedules.filter { it.id != schedule.id }
        for (bedJet in bedJets) if (bedJet.tonightID == schedule.id) bedJet.tonightID = schedules.firstOrNull()?.id
    }

    fun degrees(halfCelsius: Int): String = "${Format.degrees(halfCelsius, useFahrenheit)}°"

    /** A phase in words for TalkBack, such as "Heat, 75°, fan 40%, 30 minutes". */
    fun spokenSummary(phase: Phase): String {
        val parts = mutableListOf(phase.mode.title)
        if (bedJet?.adjustsTemperature(phase.mode) ?: phase.mode.usesTemperature) parts += degrees(phase.temperature)
        if (phase.mode.usesFan) parts += "fan ${phase.fanPercent}%"
        parts += Format.spokenDuration(phase.minutes)
        return parts.joinToString(", ")
    }

    // Persistence

    /** The BedJets known in one mode. Demo mode keeps its own, so pretend units never mix with real ones. */
    @Serializable
    private data class Fleet(
        val bedJets: List<SavedBedJet>,
        val selected: String? = null,
        val retired: Map<String, String>? = null,
        val needsChoice: Boolean? = null,
    )

    @Serializable
    private data class Saved(
        val schedules: List<Schedule>,
        val fleet: Fleet? = null,
        val demoFleet: Fleet? = null,
        // Kept by versions that knew a single BedJet.
        val tonightID: String? = null,
        val run: ActiveRun? = null,
        val slot: Int? = null,
    )

    private val fleet: Fleet get() = Fleet(bedJets.map { it.saved }, selectedID, retired, needsChoice)

    private fun load() {
        val saved = storage.file("lull.json")?.let { file -> runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull() }
        savedSchedules = saved?.schedules ?: Schedule.templates
        otherFleet = (if (demoMode) saved?.fleet else saved?.demoFleet)?.let { checked(it, demo = !demoMode) }
        val fleet = if (demoMode) saved?.demoFleet else saved?.fleet
        show(if (fleet != null) checked(fleet, demo = demoMode) else startingFleet(demoMode, legacy = saved))
        isLoaded = true
        save()
    }

    /**
     * A real BedJet is known by its unit. Ones saved without one, by a version that connected to whichever BedJet
     * it found first, can't be told apart from a partner's, so they go and are chosen again; their history stays on disk.
     */
    private fun checked(fleet: Fleet, demo: Boolean): Fleet {
        var bedJets = fleet.bedJets
        if (demo) {
            val free = DemoDiscovery.SIMULATED.map { it.id }.filter { id -> bedJets.none { it.unit == id } }.toMutableList()
            bedJets = bedJets.map { if (it.unit != null) it else it.copy(unit = free.removeFirstOrNull() ?: UUID.randomUUID().toString()) }
        } else {
            bedJets = bedJets.filter { it.unit != null }
        }
        val lost = fleet.selected != null && bedJets.none { it.id == fleet.selected }
        return fleet.copy(
            bedJets = bedJets,
            selected = if (lost) null else fleet.selected,
            needsChoice = if (lost) true else fleet.needsChoice,
        )
    }

    /**
     * What a mode starts with. A BedJet an earlier version of Lull remembered carries over with what it kept, since
     * that was all about that one unit. Without one, nothing is assumed: the person picks.
     */
    private fun startingFleet(demo: Boolean, legacy: Saved? = null): Fleet {
        var first: SavedBedJet
        if (demo) {
            if (options.demoStartsUnchosen) return Fleet(emptyList())
            first = SavedBedJet(unit = DemoDiscovery.SIMULATED[0].id, name = DemoLink.DEFAULT_NAME, generation = Generation.V3)
        } else {
            val unit = BleLink.legacyUnit(storage.linkPrefs) ?: return Fleet(emptyList())
            first = SavedBedJet(
                unit = unit.first, name = unit.second,
                generation = prefs.getString("generation", null)?.let { name -> Generation.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } },
                run = legacy?.run, slot = legacy?.slot,
            )
        }
        first = first.copy(tonightID = legacy?.tonightID ?: schedules.firstOrNull()?.id)
        val history = storage.file(if (demo) "history-demo.json" else "history.json")
        val destination = storage.historyFile(first.id)
        if (history != null && destination != null && history.exists()) history.renameTo(destination)
        return Fleet(listOf(first), selected = first.id)
    }

    fun save() {
        if (!isLoaded) return
        publishWidgets()
        publishLiveNotifications()
        val file = storage.file("lull.json") ?: return
        val saved = Saved(
            schedules = schedules,
            fleet = if (demoMode) otherFleet else fleet,
            demoFleet = if (demoMode) fleet else otherFleet,
        )
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "lull.json.tmp")
            temporary.writeText(json.encodeToString(Saved.serializer(), saved))
            temporary.renameTo(file)
        }
    }

    // Widgets

    /**
     * Hands the widgets and tiles what they show, for the BedJet on screen, and redraws them when it changed.
     * Widgets can't reach the BedJet, so this is all they know until Lull is opened again. [forced] redraws them even
     * if nothing changed.
     */
    fun publishWidgets(forced: Boolean = false) {
        if (!isLoaded || !storage.publishesWidgets) return
        val context = getApplication<Application>()
        // Called with every report from a BedJet, so what was last handed over is kept rather than read back.
        val before = publishedWidgets ?: WidgetSnapshot.load(context)
        val bedJet = bedJet
        val snapshot = WidgetSnapshot(
            tonight = bedJet?.tonight,
            run = bedJet?.run,
            recentHistory = WidgetSnapshot.recent(bedJet?.history.orEmpty()),
            useFahrenheit = useFahrenheit,
            generation = bedJet?.generation ?: Generation.V3,
            bedJets = bedJets.map { one -> widgetBedJet(one, before?.bedJets?.firstOrNull { it.id == one.id }) },
            onScreen = selectedID,
        )
        val changed = snapshot != before
        publishedWidgets = snapshot
        val saved = changed && snapshot.save(context)
        if (!saved && !forced) return
        scope.launch { WidgetUpdates.refresh(context) }
        requestTileUpdates(context)
    }

    private var publishedWidgets: WidgetSnapshot? = null

    /** One BedJet as the widgets see it. */
    private fun widgetBedJet(bedJet: BedJet, before: WidgetBedJet?): WidgetBedJet {
        val now = System.currentTimeMillis()
        val current = LiveStatus.of(bedJet.run, bedJet.status, bedJet.statusReceivedAt, bedJet.generation, now)
        val isWaiting = WidgetSnapshot.isWaiting(bedJet.status, bedJet.isSavingSchedule)
        val live = WidgetSnapshot.live(current, before?.live, isWaiting, now)
        return WidgetBedJet(bedJet.id, title(bedJet), live, bedJet.tonight, bedJet.generation, WidgetSnapshot.recent(bedJet.history))
    }

    // Live Updates

    private val liveNotifications by lazy { LiveNotifications(application) }

    /**
     * Shows the running BedJets in a Live Update in the status bar, and takes out any that stopped, were forgotten or
     * belong to the other mode.
     */
    fun publishLiveNotifications() {
        // UI tests (`ephemeral`) start nights too, and would leave Live Updates behind them, or stop to ask for notifications.
        if (!isLoaded || !storage.publishesWidgets || options.ephemeral) return
        val wanted = mutableListOf<Pair<String, LiveNotifications.Wanted>>()
        for (bedJet in bedJets) {
            val status = LiveStatus.of(bedJet.run, bedJet.status, bedJet.statusReceivedAt, bedJet.generation)
            // Starting a night reports the BedJet's program before the night is known here; what's up stays until then
            // and moves straight on to the night, rather than ending and starting over.
            if (bedJet.isSavingSchedule) {
                wanted += bedJet.id to LiveNotifications.Wanted.Keep
            } else if (status != null) {
                wanted += bedJet.id to LiveNotifications.Wanted.Show(LiveSide(bedJet.id, status, title(bedJet), bedJet.generation))
            } else if (bedJet.status == null) {
                wanted += bedJet.id to LiveNotifications.Wanted.Keep
            }
        }
        val blocked = liveNotifications.show(wanted, useFahrenheit)
        if (blocked && !prefs.getBoolean("askedForNotifications", false)) wantsNotificationPermission = true
    }

    /** The notification permission was just granted or refused. Lull doesn't ask again either way. */
    fun notificationPermissionAnswered() {
        prefs.edit().putBoolean("askedForNotifications", true).apply()
        wantsNotificationPermission = false
        publishLiveNotifications()
    }

    // Last, so every property above is set before anything is loaded or connected.
    init {
        load()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Four characters of the identifier this phone knows the unit by. They never change, unlike names. */
        fun code(unit: String): String = unit.filter { it.isLetterOrDigit() }.take(4).uppercase()

        internal fun usesUSUnits(): Boolean = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US

        /** The emulator has no BedJet nearby, so it always uses the simulated one, like the iOS Simulator. */
        private fun isEmulator(): Boolean =
            Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in setOf("goldfish", "ranchu") ||
                Build.PRODUCT.contains("sdk")
    }
}
