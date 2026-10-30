package com.rohankanjani.lull

import android.icu.text.ListFormatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rohankanjani.lull.bedjetkit.BedJetError
import com.rohankanjani.lull.bedjetkit.BedJetLink
import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.DemoLink
import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.FirmwareUpdate
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Limits
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.StoredProgram
import com.rohankanjani.lull.bedjetkit.acknowledgeNotification
import com.rohankanjani.lull.bedjetkit.deviceName
import com.rohankanjani.lull.bedjetkit.firmwareVersion
import com.rohankanjani.lull.bedjetkit.joinWiFi
import com.rohankanjani.lull.bedjetkit.setFan
import com.rohankanjani.lull.bedjetkit.setLED
import com.rohankanjani.lull.bedjetkit.setMode
import com.rohankanjani.lull.bedjetkit.setMuted
import com.rohankanjani.lull.bedjetkit.setRuntime
import com.rohankanjani.lull.bedjetkit.setTemperature
import com.rohankanjani.lull.bedjetkit.startProgram
import com.rohankanjani.lull.bedjetkit.store
import com.rohankanjani.lull.bedjetkit.storedPrograms
import com.rohankanjani.lull.bedjetkit.syncClock
import com.rohankanjani.lull.bedjetkit.updateFirmware
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What is kept of a BedJet between launches. */
@Serializable
data class SavedBedJet(
    val id: String = UUID.randomUUID().toString(),
    /** The Bluetooth identity of the unit this BedJet always connects to. */
    val unit: String? = null,
    val name: String? = null,
    val nickname: String? = null,
    /** Null until the unit has said what it is. */
    val generation: Generation? = null,
    val tonightID: String? = null,
    val run: ActiveRun? = null,
    val slot: Int? = null,
    /** How the Remote's timer was left, so a time picked there survives Lull being closed while the air runs. */
    val remoteTimer: RemoteTimer? = null,
    val longestEnds: Long? = null,
    val timerEnds: Long? = null,
    /** The Wi-Fi network Lull last gave a BedJet 3. Never the password. */
    val wifiNetwork: String? = null,
)

/** A BedJet 3 using its Wi-Fi because Lull asked: to join a network, or to check for newer firmware. */
data class WiFiActivity(
    val purpose: Purpose,
    /** What the BedJet is doing, until it's over. */
    val step: String = "Asking your BedJet…",
    /** How it went, once it's over. */
    val outcome: String? = null,
    val failed: Boolean = false,
    /** Installing new firmware, so the version is read again once the BedJet is back. */
    val awaitsRestart: Boolean = false,
    /** Not back ten minutes after it began installing, so no longer said to be restarting. */
    val restartOverdue: Boolean = false,
    /**
     * Asked for from the BedJet's offer of an update rather than the BedJet sheet, so how it went is said the same
     * way, until that's closed.
     */
    val offered: Boolean = false,
    /** The newer firmware the BedJet came back on. */
    val installed: String? = null,
    /** When the BedJet was asked, which tells this activity from a later one. */
    val startedAt: Long = System.currentTimeMillis(),
) {
    enum class Purpose { JOINING, UPDATING }

    val isRunning: Boolean get() = outcome == null
}

/** One BedJet Lull knows: its connection, what it reports, the schedule running on it and what it did. */
class BedJet(saved: SavedBedJet, val isDemo: Boolean, private val app: AppModel) {
    val id: String = saved.id
    /** The Bluetooth identity of the unit picked for this BedJet. Lull never connects it to another. */
    val unit: String? = saved.unit
    /** The name the BedJet gives itself, kept so it can be shown while the BedJet is away. */
    var name by mutableStateOf(saved.name); private set
    private var savedNickname by mutableStateOf(saved.nickname)
    /** A name given on this phone, such as "My side". The BedJet itself isn't renamed. */
    var nickname: String?
        get() = savedNickname
        set(value) { savedNickname = value; app.save() }
    val displayName: String
        get() = nickname?.trim()?.takeIf { it.isNotEmpty() } ?: name ?: "BedJet"
    var linkState by mutableStateOf(LinkState.SEARCHING); private set
    var status by mutableStateOf<DeviceStatus?>(null); private set
    /** When [status] arrived. It is kept while another phone has the BedJet or Lull is off screen, so it ages. */
    var statusReceivedAt: Long? = null; private set
    /**
     * The BedJet's own settings from its latest full report: light ring, beeps, a running program. A report's
     * second half arrives separately, and not at all for a moment after reconnecting, so this keeps the last one
     * rather than letting what's shown from it flicker away.
     */
    var settings by mutableStateOf<DeviceStatus.Extra?>(null); private set
    var firmware by mutableStateOf<String?>(null); private set
    /** The kind of BedJet, kept so schedules can be edited for it while it is away. Null until it has said. */
    var knownGeneration by mutableStateOf(saved.generation); private set
    val generation: Generation get() = knownGeneration ?: Generation.V3
    /** What is saved in the BedJet's three program slots. */
    var deviceSlots by mutableStateOf<List<StoredProgram?>>(emptyList()); private set

    private var savedTonightID by mutableStateOf(saved.tonightID)
    var tonightID: String?
        get() = savedTonightID
        set(value) { savedTonightID = value; app.save() }

    private var savedRun by mutableStateOf(saved.run?.takeIf { it.endsAt > System.currentTimeMillis() })
    var run: ActiveRun?
        get() = savedRun
        private set(value) { savedRun = value; app.save() }

    /** The BedJet slot this app keeps tonight's schedule in. */
    private var savedSlot by mutableStateOf(saved.slot)
    var slot: Int?
        get() = savedSlot
        private set(value) { savedSlot = value; app.save() }

    /** What the BedJet did, oldest first, so the morning after can be checked against how the night felt. */
    var history by mutableStateOf<List<HistoryEntry>>(emptyList()); private set
    val nights: List<Night> get() = Night.group(history)

    /** Set once Lull has looked for the BedJet for a while without reaching it, to suggest what to check. */
    var isSlowToFind by mutableStateOf(false); private set
    /** Commands under way. More than one can be, such as a Remote change while an automation runs. */
    private var commandsUnderWay by mutableIntStateOf(0)
    val isBusy: Boolean get() = commandsUnderWay > 0
    /**
     * Set as Lull lets go of a connected BedJet, until the link says it has. Over Bluetooth that takes a moment, and until
     * then the link still says it's ready, though nothing sent on it arrives.
     */
    private var isLettingGo = false
    private var writingSchedule by mutableStateOf(false)
    /**
     * A schedule is being written to the BedJet, which takes several steps. Its Live Update holds still while this is
     * set, and moves on to the night as soon as it clears.
     */
    var isSavingSchedule: Boolean
        get() = writingSchedule
        private set(value) { writingSchedule = value; app.publishLiveNotifications() }
    /** Set when every slot on the BedJet is taken and the user has to pick one to reuse. */
    var needsSlotChoice by mutableStateOf(false)
    /** The BedJet using its Wi-Fi because Lull asked, and how that went. One at a time. */
    var wifiActivity by mutableStateOf<WiFiActivity?>(null); private set
    /** Set while the BedJet says newer firmware is available. */
    var updateAvailable by mutableStateOf(false); private set
    /**
     * Set once an offered update is put off or asked for, so it waits in the BedJet sheet instead of asking again
     * until Lull is next opened.
     */
    var firmwareUpdateDeferred by mutableStateOf(false); private set
    /** The Wi-Fi network Lull last gave the BedJet, kept to show it: a BedJet can't be asked which network it has. */
    private var savedWifiNetwork by mutableStateOf(saved.wifiNetwork)
    var wifiNetwork: String?
        get() = savedWifiNetwork
        private set(value) { savedWifiNetwork = value; app.save() }

    private var link: BedJetLink? = null
    private var lastRefresh = 0L
    /**
     * How air from the Remote is timed. Schedules keep their own timing. Saved, so a time picked there holds after the
     * phone closes Lull while the air runs.
     */
    private var savedRemoteTimer by mutableStateOf(saved.remoteTimer ?: RemoteTimer.Longest)
    var remoteTimer: RemoteTimer
        get() = savedRemoteTimer
        private set(value) {
            if (value == savedRemoteTimer) return
            savedRemoteTimer = value
            app.save()
        }
    /** When the longest run Lull gave the air from the Remote ends, which changing the temperature or fan keeps to. */
    // Only a timer Lull finished setting is kept to; one it was still reading back when Lull closed is left alone.
    private var longestEnds: Long? = plausible(saved.longestEnds)?.takeIf { plausible(saved.timerEnds) != null }
        set(value) { field = value; app.save() }
    /**
     * When the BedJet's timer, as Lull last left it, runs out. A timer that no longer ends then was changed from
     * another phone or the BedJet's own remote.
     */
    private var timerEnds: Long? = plausible(saved.timerEnds)
        set(value) { field = value; app.save() }
    private var timePickedAt = 0L
    /** When a fan or temperature change from this phone cleared [timerEnds] to read it back. */
    private var readingTimerBackSince = 0L
    private var searchTimer: Job? = null
    /** What the history last recorded the BedJet doing, kept across launches to notice what changed while Lull was closed. */
    private var lastSeen: Seen? = null
    /** The latest report, waiting to settle before it is compared with [lastSeen]. */
    private var observed: Seen? = null
    private var settling: Job? = null
    private var connectedAt = 0L
    private var lastCommandAt = 0L
    /** When this phone last turned the BedJet off, to tell a report sent just before that from one sent after. */
    private var turnedOffAt: Long? = null
    /** The Wi-Fi phase the BedJet last reported, and since when, to tell a step under way from one left stuck. */
    private var lastWiFiPhase: Int? = null
    private var wifiPhaseSince = 0L
    /** The BedJet reported it's restarting to install firmware, and hasn't restarted since. */
    private var restartsToInstall by mutableStateOf(false)
    /** The connection the BedJet began installing firmware on, which ends with its restart. */
    private var installBeganOn = 0L

    val saved: SavedBedJet
        get() = SavedBedJet(id, unit, name, nickname, knownGeneration, tonightID, run, slot, remoteTimer, longestEnds, timerEnds, wifiNetwork)

    val tonight: Schedule?
        get() = app.schedules.firstOrNull { it.id == tonightID } ?: app.schedules.firstOrNull()

    val isConnected: Boolean get() = linkState == LinkState.READY
    /**
     * Controls stay live while connecting or while another phone has the BedJet: what is tapped
     * is sent the moment the connection is up.
     */
    /** Not while it uses its Wi-Fi, though: see [isUsingWiFi]. */
    val canControl: Boolean get() = linkState != LinkState.BLUETOOTH_OFF && linkState != LinkState.UNAUTHORIZED && !isUsingWiFi
    /** A schedule is running on the BedJet that this phone didn't start, for example from a partner's phone. */
    val runningElsewhereStep: Int?
        get() {
            if (run != null) return null
            val step = status?.extra?.programStep ?: return null
            return if (step > 0) step else null
        }

    init {
        loadHistory()
        val ended = saved.run
        if (ended != null && run == null) {
            record(HistoryEntry.Event.ScheduleEnded(ended.schedule.name, HistoryEntry.Ending.Finished), at = ended.endsAt)
        }
    }

    // Connection

    /**
     * Starts talking to the BedJet. Only the one on screen asks another phone to hand it over;
     * the rest connect whenever they are free.
     */
    fun connect(asking: Boolean, resuming: Boolean = true) {
        link?.apply {
            onStateChange = null
            onStatus = null
            stop()
        }
        status = null
        settings = null
        firmware = null
        deviceSlots = emptyList()
        val link = app.makeLink(this)
        // Never hand the BedJet to another phone halfway through saving a schedule, while it uses its Wi-Fi for this
        // phone, or while this phone waits for it to restart into new firmware: the other phone would otherwise take it
        // as it comes back, and this one would never hear how the update went.
        (link as? BleLink)?.canYield = { !isBusy && wifiActivity?.isRunning != true && !expectsRestart }
        link.onStateChange = { linkStateChanged(it) }
        link.onStatus = { statusChanged(it) }
        this.link = link
        linkState = link.state
        lastRefresh = 0
        if (resuming) resume(asking)
    }

    /**
     * The BedJet talks to one phone at a time, so it is released whenever Lull leaves the screen, and as soon as
     * an errand or action that reached it from the background is done. Holding on to it would make coming back
     * instant, but a phone that is off screen cannot be relied on to hear another phone asking for the BedJet.
     */
    fun pause() {
        if (linkState == LinkState.READY) isLettingGo = true
        link?.stop()
        // A change still settling would otherwise wait until Lull is opened again.
        if (settling != null) commitObserved()
        saveHistory()
    }

    fun resume(asking: Boolean) {
        if (asking) link?.start() else link?.listen()
        restartSearchTimer()
    }

    /**
     * Whether the BedJet has reported everything it does since [since], both halves of its report, so what it's doing
     * now is known rather than what Lull last saw.
     */
    fun hasReported(since: Long): Boolean {
        val at = statusReceivedAt ?: return false
        return at >= since && status?.extra != null
    }

    /**
     * Waits for the BedJet to say what it's doing, so an errand from the watch acts on that. Gives up after [timeout],
     * leaving the errand's own commands to say what's wrong.
     */
    suspend fun awaitReport(since: Long, timeout: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline && !hasReported(since)) delay(100)
    }

    private fun restartSearchTimer() {
        searchTimer?.cancel()
        searchTimer = null
        isSlowToFind = false
        timeSearch()
    }

    /**
     * Counts how long Lull has been looking, across searching and connecting, but not while
     * another phone has the BedJet or Bluetooth is unavailable, which already say what is wrong.
     */
    private fun timeSearch() {
        if (linkState != LinkState.SEARCHING && linkState != LinkState.CONNECTING) {
            searchTimer?.cancel()
            searchTimer = null
            isSlowToFind = false
            return
        }
        if (searchTimer != null) return
        searchTimer = app.scope.launch {
            delay(15_000)
            // A BedJet restarts to check for firmware and again to install it, which can take ten minutes, and the tips
            // would suggest unplugging it partway through, so they wait for as long as it's expected back.
            while (expectsRestart) delay(1000)
            isSlowToFind = true
        }
    }

    private fun linkStateChanged(state: LinkState) {
        linkState = state
        if (state != LinkState.READY) isLettingGo = false
        timeSearch()
        // Gone, so restarted, or about to come back from one.
        if (state != LinkState.READY) restartsToInstall = false
        val link = link ?: return
        if (state != LinkState.READY) return
        connectedAt = System.currentTimeMillis()
        knownGeneration = link.generation
        app.save()
        logConnection()
        // Phones take turns with the BedJet, so reconnecting is routine; what is saved on it
        // only needs re-reading now and then, or once it's back from restarting into new firmware.
        if (System.currentTimeMillis() - lastRefresh <= 300_000 && wifiActivity?.awaitsRestart != true) return
        app.scope.launch {
            // Anything the person tapped while connecting goes first.
            delay(1200)
            // Setting the clock is a command, so it waits while the BedJet uses its Wi-Fi.
            while ((isBusy || reportsWiFiUnderWay || wifiActivity?.isRunning == true) && link.state == LinkState.READY) delay(200)
            if (link.state != LinkState.READY) return@launch
            // Still on the connection the install began on, so the version is read once it's back from restarting.
            if (wifiActivity?.awaitsRestart == true && connectedAt == installBeganOn) return@launch
            try {
                link.syncClock()
                name = link.deviceName()
                app.save()
                val previous = this@BedJet.firmware
                val firmware = link.firmwareVersion()
                this@BedJet.firmware = firmware
                if (wifiActivity?.awaitsRestart == true) finishInstalling(firmware, previous)
                refreshSlots()
                lastRefresh = System.currentTimeMillis()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Status keeps streaming without these; they are retried on the next connection.
            }
        }
    }

    private fun statusChanged(status: DeviceStatus) {
        // The BedJet reports every second, so a report it sent just before it took Off can arrive just after. Shown, it
        // would put the air back on outside Lull, where it stays if Lull lets go of the BedJet before the next report.
        val turnedOffAt = turnedOffAt
        if (turnedOffAt != null && status.isRunning && System.currentTimeMillis() - turnedOffAt < staleReportWindow) return
        this.turnedOffAt = null
        this.status = status
        statusReceivedAt = System.currentTimeMillis()
        status.extra?.let { settings = it }
        observe(status)
        noticeWiFiPhase(status)
        noticeUpdate(status)
        app.publishLiveNotifications()
        app.publishWidgets()
        val run = run ?: return
        val now = System.currentTimeMillis()
        val settled = now - run.startedAt > 15_000
        val programStopped = status.mode == Mode.STANDBY && status.extra?.programStep == 0
        if (now < run.endsAt && !(settled && programStopped)) return
        this.run = null
        // Lull lets go of the BedJet overnight, so it only learns about an early stop on the next connection.
        // Nothing on this phone asked for it, so say so and keep the unit's reason code for diagnosis.
        val minutesEarly = ((run.endsAt - now) / 60_000).toInt()
        if (isBusy) return
        if (minutesEarly > 2) {
            record(HistoryEntry.Event.ScheduleEnded(run.schedule.name, HistoryEntry.Ending.Early(minutesEarly)), noticedLater = isJustConnected)
        } else {
            record(HistoryEntry.Event.ScheduleEnded(run.schedule.name, HistoryEntry.Ending.Finished), at = minOf(run.endsAt, now))
        }
        if (minutesEarly > 2) {
            app.report(
                "Your BedJet turned off ${Format.duration(minutesEarly)} before tonight's schedule ended " +
                    "(BedJet reason ${status.lastShutdownReason}).",
                from = this,
            )
        }
    }

    private suspend fun refreshSlots() {
        val link = link ?: return
        deviceSlots = link.storedPrograms()
        // Keep a copy of programs made elsewhere so reusing a slot never loses them.
        deviceSlots.forEachIndexed { index, program ->
            if (program != null && index != slot) app.keepCopy(program)
        }
    }

    // Tonight

    suspend fun startTonight() {
        val schedule = tonight ?: return
        if (link == null) return
        isSavingSchedule = true
        try {
            perform { startSchedule(schedule, choosingSlot = true) }
        } finally {
            isSavingSchedule = false
        }
    }

    /**
     * Saves a schedule into Lull's slot on the BedJet and starts it. When every slot is taken, the person picks
     * one to reuse; without anyone there to pick, as in an automation, that is an error instead.
     */
    private suspend fun startSchedule(schedule: Schedule, choosingSlot: Boolean) {
        val link = connectedLink()
        // Demos that show a night in progress pretend it started earlier.
        val elapsed = if (link is DemoLink) app.options.demoElapsedMinutes else 0
        val startedAt = System.currentTimeMillis() - elapsed * 60_000L
        // Known for certain once connected, which `carryOut` waited for; checked before anything is written.
        problem(schedule, startedAt)?.let { throw BedJetError.InvalidProgram(it) }
        // With a wake time, every phase is scaled to end at it from now. A BedJet 3 is still given the wake time itself for
        // the last step, so it keeps to it by its own clock.
        val tonight = schedule.resolved(startingAt = Format.minuteOfDay(startedAt))
        // A V2 holds just the one sequence, which Lull always replaces.
        val slot: Int
        if (link.generation == Generation.V2) {
            slot = 0
        } else {
            if (deviceSlots.isEmpty()) refreshSlots()
            val free = this.slot ?: deviceSlots.indexOf(null).takeIf { it >= 0 }
            if (free == null) {
                if (!choosingSlot) throw AutomationError.SlotsFull
                needsSlotChoice = true
                return
            }
            slot = free
            this.slot = slot
        }
        link.store(tonight.program, slot)
        link.startProgram(slot)
        confirmStarted()
        run = ActiveRun(tonight, startedAt)
        remoteTimer = RemoteTimer.Longest
        record(HistoryEntry.Event.ScheduleStarted(schedule.name, tonight.phases))
        if (link is DemoLink && elapsed > 0) link.skipAhead(elapsed)
        attempt { refreshSlots() }
    }

    /** A BedJet that can't run a schedule stays off and raises a notification instead of starting it. */
    private suspend fun confirmStarted() {
        val link = link ?: return
        repeat(20) {
            delay(200)
            val extra = status?.extra ?: return@repeat
            if (extra.programStep > 0) return
            val refusal = when (extra.notification) {
                Codec.Notification.PROGRAM_NEEDS_CLOCK -> BedJetError.ClockNotSet
                Codec.Notification.PROGRAM_TOO_LONG -> BedJetError.ProgramTooLong
                else -> null
            }
            if (refusal != null) {
                attempt { link.acknowledgeNotification() }
                throw refusal
            }
        }
    }

    suspend fun startTonight(replacingSlot: Int) {
        slot = replacingSlot
        startTonight()
    }

    suspend fun endNight() {
        if (link == null) return
        perform { stopNight() }
    }

    private suspend fun stopNight() {
        val link = connectedLink()
        val name = run?.schedule?.name
        link.setMode(Mode.STANDBY)
        run = null
        forgetRemoteTimer()
        record(HistoryEntry.Event.ScheduleEnded(name, HistoryEntry.Ending.ByYou))
    }

    // Remote

    suspend fun setMode(mode: Mode) {
        if (link == null) return
        // Captured first: a time picked while another phone hands the BedJet over comes after this tap.
        val asked = System.currentTimeMillis()
        // Shown as a time, the end on screen holds across modes: a time picked here, or else the BedJet's own countdown,
        // such as a duration picked here or a timer set on another phone.
        val shown = status
        if (app.remoteTimerShowsTime && mode != Mode.STANDBY && !remoteTimer.isAhead && run == null && runningElsewhereStep == null &&
            shown != null && shown.isRunning && shown.remainingSeconds > 60
        ) {
            // Counted from when the BedJet said it, so a report from a while ago doesn't move the end later.
            remoteTimer = RemoteTimer.Until((statusReceivedAt ?: System.currentTimeMillis()) + shown.remainingSeconds * 1000L)
        }
        perform { changeMode(mode, asked) }
    }

    suspend fun setTemperature(halfCelsius: Int) {
        if (link == null) return
        val asked = System.currentTimeMillis()
        perform { changeTemperature(halfCelsius, asked) }
    }

    suspend fun setFan(percent: Int) {
        if (link == null) return
        val asked = System.currentTimeMillis()
        perform { changeFan(percent, asked) }
    }

    suspend fun setRuntime(minutes: Int) {
        if (link == null) return
        perform { changeRuntime(minutes) }
    }

    private suspend fun changeMode(mode: Mode, asked: Long) {
        val link = connectedLink()
        // Captured first: the BedJet's report that the schedule stopped can clear `run` before this returns.
        val endingSchedule = run?.schedule?.name
        // Until the new mode is given its own run, a fan or temperature change leaves the timer alone.
        forgetRemoteTimer()
        link.setMode(mode)
        // The BedJet only says it's off in its next report, and Lull may have let go of it by then, leaving it shown
        // as running outside Lull. The BedJet took the command, so it is off already; setting `run` publishes it.
        if (mode == Mode.STANDBY) status = status?.copy(mode = Mode.STANDBY)
        turnedOffAt = if (mode == Mode.STANDBY) System.currentTimeMillis() else null
        run = null
        if (endingSchedule != null) record(HistoryEntry.Event.ScheduleEnded(endingSchedule, HistoryEntry.Ending.ByYou))
        // A time picked on the Remote holds across modes until it passes, while the Remote shows the time. Otherwise each
        // mode starts at its longest run, as the timer always has.
        if (mode == Mode.STANDBY || !remoteTimer.isAhead || !app.remoteTimerShowsTime) remoteTimer = RemoteTimer.Longest
        if (mode != Mode.STANDBY) extendRuntime(entering = mode, asked = asked)
    }

    private suspend fun changeTemperature(halfCelsius: Int, asked: Long) {
        val link = connectedLink()
        val keepsTimer = keepsTimer()
        if (keepsTimer) readTimerBack()
        link.setTemperature(halfCelsius)
        if (keepsTimer) extendRuntime(asked = asked)
    }

    private suspend fun changeFan(percent: Int, asked: Long) {
        val link = connectedLink()
        val keepsTimer = keepsTimer()
        if (keepsTimer) readTimerBack()
        link.setFan(percent / 5 - 1)
        if (keepsTimer) extendRuntime(asked = asked)
    }

    private suspend fun changeRuntime(minutes: Int) {
        // From now on the timer counts down as it is: nothing on this phone changes it until a mode is chosen.
        remoteTimer = RemoteTimer.Picked
        timePickedAt = System.currentTimeMillis()
        connectedLink().setRuntime(minutes)
    }

    /**
     * Turns the air off at [untilMillis], or as near it as the mode allows. Lull keeps aiming at it as the temperature and
     * fan change, since each changes how long the BedJet lets the air run, and as the mode changes while the Remote shows
     * the time.
     */
    suspend fun setRuntime(untilMillis: Long) {
        val link = link ?: return
        remoteTimer = RemoteTimer.Until(untilMillis)
        val asked = System.currentTimeMillis()
        timePickedAt = asked
        perform {
            // Sent at once, as far as the BedJet last said the mode may run. A hotter setting still on its way can cut
            // that short, so like a fan or temperature change, the timer is read back from the BedJet's next report and
            // aimed again, rather than taken to be what was sent.
            readTimerBack()
            val limit = status?.let { if (it.maxRuntimeMinutes > 0) it.maxRuntimeMinutes else it.mode.maxMinutes } ?: Limits.MAX_STEP_MINUTES
            link.setRuntime(minutes(untilMillis, limit))
            // Sent, the time is the BedJet's, so moving on from the Remote (switching BedJet, a newer pick) doesn't stop it
            // being read back. Left unread, the next change couldn't tell it from another phone's timer.
            withContext(NonCancellable) { extendRuntime(asked = asked) }
        }
    }

    /** Minutes from now until [endsAt], to the nearest minute, as far as [limit] allows. */
    private fun minutes(endsAt: Long, limit: Int): Int =
        Math.round((endsAt - System.currentTimeMillis()) / 60_000.0).toInt().coerceAtLeast(1).coerceAtMost(limit)

    /**
     * Whether changing the temperature or fan should keep the timer to its end. Asked before the change is sent, since
     * in Heat a hotter setting can cut the BedJet's timer by itself. A timer set from another phone or the BedJet's own
     * remote since Lull last set it is theirs, and Lull stops keeping to it from then on.
     *
     * While a change is sent, [timerEnds] is cleared: the BedJet may cut its own timer for it, so the end is read
     * back afterwards. If a newer slider move replaced that change first, or two changes overlapped, the timer is
     * still Lull's, and the next change reads it back.
     */
    private fun keepsTimer(): Boolean {
        if (remoteTimer == RemoteTimer.Picked) return false
        val timerEnds = timerEnds
        if (timerEnds == null) {
            // A change cleared it long ago and never read it back, so where the timer is now isn't known.
            if (System.currentTimeMillis() - readingTimerBackSince >= 10_000) {
                forgetRemoteTimer()
                return false
            }
            return true
        }
        // Only a report from this connection says where the timer is now.
        val status = status
        val statusReceivedAt = statusReceivedAt
        if (status == null || !status.isRunning) return false
        if (statusReceivedAt == null || statusReceivedAt <= connectedAt) return false
        val unitEnds = statusReceivedAt + status.remainingSeconds * 1000L
        if (abs(unitEnds - timerEnds) >= 60_000) {
            remoteTimer = RemoteTimer.Picked
            return false
        }
        return true
    }

    /**
     * Air started from the Remote runs for as long as the BedJet allows, or until the time picked there, until a
     * duration is picked instead. Changing the temperature or fan keeps to the end that gives: it never adds time, and
     * gives back time the BedJet cut short for a hotter setting once the setting allows. Schedules keep their own timing.
     * Called just after the change is sent. [asked] is when the person made it, so a duration picked after it wins.
     */
    private suspend fun extendRuntime(entering: Mode? = null, asked: Long) {
        // A schedule started from another phone times its own phases; leave its timer alone.
        val link = link
        if (remoteTimer == RemoteTimer.Picked || run != null || runningElsewhereStep != null || link == null) {
            forgetRemoteTimer()
            return
        }
        val sent = System.currentTimeMillis()
        // The limit depends on mode, temperature and fan, and the unit keeps the previous timer when
        // the mode changes, so wait for it to report the new state before reading the limit. Only a report it sent
        // after the change says what it allows now: one from before would aim at a limit it no longer has.
        delay(600)
        repeat(20) {
            val status = status
            val receivedAt = statusReceivedAt
            if (status != null && receivedAt != null && receivedAt > sent && status.isRunning && status.maxRuntimeMinutes > 0 &&
                (entering == null || status.mode == entering)
            ) {
                // A duration picked while this waited wins. So does a time, which this keeps to as well: it may have
                // cleared the timer that time read back. One picked in the same millisecond as the change came before it,
                // as a phone's clock may not tell them apart.
                if (timePickedAt > asked && !remoteTimer.isAhead) return
                if (entering != null && remoteTimer == RemoteTimer.Longest) {
                    longestEnds = System.currentTimeMillis() + status.maxRuntimeMinutes * 60_000L
                }
                val end = when (val timer = remoteTimer) {
                    RemoteTimer.Longest -> longestEnds
                    is RemoteTimer.Until -> timer.endsAt
                    RemoteTimer.Picked -> null
                }
                if (end == null || end <= System.currentTimeMillis()) return
                // Capped at the mode's longest run, so a damaged save can't send a timer the BedJet doesn't allow.
                val minutes = minutes(end, status.maxRuntimeMinutes)
                var remaining = status.remainingSeconds
                if (abs(remaining - minutes * 60) > 60) {
                    link.setRuntime(minutes)
                    remaining = minutes * 60
                }
                timerEnds = System.currentTimeMillis() + remaining * 1000L
                return
            }
            delay(200)
        }
        // The BedJet never reported the air running, so where its timer is isn't known.
        forgetRemoteTimer()
    }

    /** Lets the BedJet move its timer for a fan or temperature change, to be read back once it reports the new limit. */
    private fun readTimerBack() {
        timerEnds = null
        readingTimerBackSince = System.currentTimeMillis()
    }

    /** Stops keeping the Remote's timer to an end, until a mode is next chosen or a time picked to turn off at. */
    private fun forgetRemoteTimer() {
        longestEnds = null
        timerEnds = null
        // A time picked to turn off at outlives this, so a change still reading the timer back mustn't aim at it.
        readingTimerBackSince = 0L
    }

    /**
     * A saved end that Lull could have set: still ahead, and no further off than the BedJet's longest run, so a
     * damaged file can't send a timer the BedJet doesn't allow.
     */
    private fun plausible(end: Long?): Long? {
        val now = System.currentTimeMillis()
        return end?.takeIf { it > now && it < now + 13 * 3_600_000L }
    }

    suspend fun setLED(on: Boolean) {
        if (link == null) return
        perform { connectedLink().setLED(on) }
    }

    suspend fun setMuted(muted: Boolean) {
        if (link == null) return
        perform { connectedLink().setMuted(muted) }
    }

    /** Does what a control asked for, showing anything that goes wrong. */
    private suspend fun perform(work: suspend () -> Unit) {
        // Widgets, tiles, shortcuts and the watch reach here too, so this is checked here and not only by the buttons.
        // Actions from automation apps check the same in [carryOutAction], to say so in their result.
        if (isUsingWiFi) {
            app.report("Your BedJet is setting up Wi-Fi or updating its firmware. Try again once it's done.", from = this)
            return
        }
        try {
            carryOut(work)
        } catch (error: CancellationException) {
            // A newer slider move replaced this one, or the errand it was for ran out of time; nothing went wrong.
            throw error
        } catch (error: Exception) {
            app.report(error.message ?: BedJetError.BadResponse.message.orEmpty(), from = this)
        }
    }

    /** Takes control of the BedJet and does the work, one thing at a time. What goes wrong is thrown to the caller. */
    private suspend fun <T> carryOut(work: suspend () -> T): T = underWay {
        takeControl()
        work()
    }

    /** Counts the work as a command under way while it runs, so the BedJet shows as busy. */
    private suspend fun <T> underWay(work: suspend () -> T): T {
        commandsUnderWay += 1
        try {
            return work()
        } finally {
            commandsUnderWay -= 1
            lastCommandAt = System.currentTimeMillis()
        }
    }

    private fun connectedLink(): BedJetLink = link ?: throw BedJetError.NotConnected

    // Wi-Fi and firmware

    /** Connected with the air off and no schedule running, so restarting the BedJet interrupts nothing. */
    val isIdle: Boolean get() = isConnected && status?.mode == Mode.STANDBY && run == null && runningElsewhereStep == null
    /** Updating restarts the BedJet, so it waits until that interrupts nothing. */
    val canUpdateFirmware: Boolean get() = isIdle && !isBusy && !isUsingWiFi
    val canJoinWiFi: Boolean get() = isConnected && !isUsingWiFi
    /**
     * The BedJet is away because it's checking for or installing firmware, and will be back. Going by the clock too,
     * until [restartPatienceMillis] after it was asked to check: the timer that marks it overdue counts only time the
     * phone is awake, so Lull woken long after, as by an action, would otherwise still be holding everything for it.
     */
    val expectsRestart: Boolean
        get() = wifiActivity?.takeIf { it.purpose == WiFiActivity.Purpose.UPDATING }?.let {
            val overdue = it.restartOverdue || System.currentTimeMillis() - it.startedAt >= restartPatienceMillis
            it.isRunning || (it.awaitsRestart && !overdue)
        } ?: false
    /** Away while it restarts, and only that: Bluetooth being off or another phone having it still say so. */
    val isRestarting: Boolean
        get() = expectsRestart && (linkState == LinkState.SEARCHING || linkState == LinkState.CONNECTING)
    /**
     * The BedJet is joining Wi-Fi, checking for firmware or installing it, or is about to restart for it, whoever
     * asked: this phone, another one, the official app or the BedJet itself. Nothing else is sent to it meanwhile:
     * the restart would cut off a command or a schedule being saved, and a night started now would end with it,
     * while a second check or network could restart it partway through installing.
     */
    val isUsingWiFi: Boolean get() = wifiActivity?.isRunning == true || expectsRestart || reportsWiFiUnderWay

    /**
     * What the BedJet's own report says. A step that hasn't moved on in ten minutes, the longest a check takes, is
     * stuck rather than under way. Restarting to install only lasts until the BedJet does restart, since that
     * result may still be showing once it's back.
     */
    private val reportsWiFiUnderWay: Boolean
        get() {
            val phase = settings?.wifiPhase ?: return false
            if (System.currentTimeMillis() - wifiPhaseSince >= 600_000) return false
            if (phase == Codec.WiFiPhase.INSTALLING) return restartsToInstall
            return phase != Codec.WiFiPhase.IDLE && !Codec.WiFiPhase.isResult(phase)
        }

    private fun noticeWiFiPhase(status: DeviceStatus) {
        val phase = status.extra?.wifiPhase ?: return
        if (phase == lastWiFiPhase) return
        lastWiFiPhase = phase
        wifiPhaseSince = System.currentTimeMillis()
        restartsToInstall = phase == Codec.WiFiPhase.INSTALLING
    }

    /**
     * Gives the BedJet the Wi-Fi network it fetches firmware over. Lull remembers the name, to show it, but never
     * the password.
     */
    suspend fun joinWiFi(network: String, password: String) {
        if (!canJoinWiFi) return
        useWiFi(WiFiActivity.Purpose.JOINING) { link, progress ->
            link.joinWiFi(network, password, progress)
            wifiNetwork = network
            "Your BedJet joined “$network” and can now check for firmware updates."
        }
    }

    /**
     * Asks the BedJet to check for newer firmware, which it installs straight away. [offered] when the person
     * accepted the BedJet's offer of an update, so how it went is said the same way once it's over.
     */
    suspend fun updateFirmware(offered: Boolean = false) {
        // The air may have been turned on, or a schedule started, while the person was confirming.
        if (!canUpdateFirmware) return
        // Asked once a launch: whether this works or not, the update waits in the BedJet sheet from now on.
        firmwareUpdateDeferred = true
        useWiFi(WiFiActivity.Purpose.UPDATING, offered) { link, progress ->
            // Known before asking, even when this comes straight after connecting, to tell newer firmware once it's back.
            if (firmware == null) attempt { firmware = link.firmwareVersion() }
            when (val found = link.updateFirmware(progress)) {
                FirmwareUpdate.UpToDate -> "Your BedJet's firmware is up to date."
                FirmwareUpdate.Installing -> {
                    // Read again once it's back, to show the new version.
                    lastRefresh = 0
                    installBeganOn = connectedAt
                    wifiActivity = wifiActivity?.copy(awaitsRestart = true)
                    stopExpectingRestartLater()
                    "Your BedJet is installing the update and restarts when it's done. Keep it plugged in."
                }
                is FirmwareUpdate.Updated -> {
                    firmware = found.version
                    wifiActivity = wifiActivity?.copy(installed = found.version)
                    "Your BedJet updated its firmware to ${found.version}."
                }
            }
        }
    }

    private suspend fun useWiFi(
        purpose: WiFiActivity.Purpose,
        offered: Boolean = false,
        work: suspend (BedJetLink, (Int) -> Unit) -> String,
    ) {
        val link = link ?: return
        if (wifiActivity?.isRunning == true) return
        wifiActivity = WiFiActivity(
            purpose,
            step = if (purpose == WiFiActivity.Purpose.UPDATING) "Asking your BedJet to check…" else "Asking your BedJet…",
            offered = offered,
        )
        // A command already on its way, such as one from an automation, finishes first so the two don't interleave.
        // Actions that come after are refused while this runs.
        while (isBusy) delay(200)
        try {
            val outcome = work(link) { phase -> step(phase)?.let { wifiActivity = wifiActivity?.copy(step = it) } }
            wifiActivity = wifiActivity?.copy(outcome = outcome)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            wifiActivity = wifiActivity?.copy(
                outcome = error.message ?: BedJetError.BadResponse.message.orEmpty(),
                failed = true,
                awaitsRestart = false,
            )
        }
    }

    /** The BedJet is back from restarting after it began installing, on this firmware. */
    private fun finishInstalling(firmware: String, previous: String?) {
        // Worded as when the update is over before the link is lost, if the version did change.
        wifiActivity = if (previous != null && firmware != previous) {
            wifiActivity?.copy(outcome = "Your BedJet updated its firmware to $firmware.", installed = firmware, awaitsRestart = false)
        } else {
            wifiActivity?.copy(outcome = "Your BedJet restarted with firmware $firmware.", awaitsRestart = false)
        }
    }

    /**
     * A BedJet not back ten minutes after it began installing isn't restarting any more, so from then on it's
     * said to be away like any other, with the tips for finding it. Its version is still read if it does come back.
     */
    private fun stopExpectingRestartLater() {
        val startedAt = wifiActivity?.startedAt ?: return
        app.scope.launch {
            delay(restartPatienceMillis)
            val activity = wifiActivity
            if (activity?.startedAt == startedAt && activity.awaitsRestart) wifiActivity = activity.copy(restartOverdue = true)
        }
    }

    /** What the BedJet is doing at each step it reports while it uses its Wi-Fi. */
    private fun step(phase: Int): String? = when (phase) {
        Codec.WiFiPhase.STARTING -> "Starting…"
        Codec.WiFiPhase.JOINING -> "Joining Wi-Fi…"
        Codec.WiFiPhase.JOINED -> "Connected to Wi-Fi…"
        Codec.WiFiPhase.TESTING -> "Testing the connection…"
        Codec.WiFiPhase.CHECKING -> "Checking for an update…"
        Codec.WiFiPhase.UPDATING -> "Installing the update…"
        Codec.WiFiPhase.RESTARTING -> "Restarting your BedJet to check…"
        else -> null
    }

    /**
     * A BedJet 3 on Wi-Fi checks for firmware by itself now and then, and says when there's newer firmware until
     * that's acknowledged. Lull leaves it set: the BedJet stops saying so once it's updated, and other phones and
     * the official app may still want to show it.
     */
    private fun noticeUpdate(status: DeviceStatus) {
        when (status.extra?.notification) {
            Codec.Notification.UPDATE_AVAILABLE -> updateAvailable = true
            0 -> updateAvailable = false
        }
    }

    sealed interface FirmwarePrompt {
        /** The BedJet says newer firmware is available. */
        data object Available : FirmwarePrompt
        /** How an update the person accepted from the offer went. */
        data class Finished(val activity: WiFiActivity) : FirmwarePrompt
    }

    /**
     * What to ask or tell the person about firmware now. An offered update waits until restarting the BedJet would
     * interrupt nothing, and is asked about once; put off, it waits in the BedJet sheet.
     */
    val firmwarePrompt: FirmwarePrompt?
        get() {
            val activity = wifiActivity
            if (activity != null && activity.offered && !activity.isRunning && !activity.awaitsRestart) return FirmwarePrompt.Finished(activity)
            if (generation != Generation.V3 || !updateAvailable || firmwareUpdateDeferred || !canUpdateFirmware) return null
            return FirmwarePrompt.Available
        }

    fun deferFirmwareUpdate() {
        firmwareUpdateDeferred = true
    }

    /** Closes the report of how an offered update went; it stays in the BedJet sheet. */
    fun dismissFirmwareResult() {
        wifiActivity = wifiActivity?.copy(offered = false)
    }

    /** Like Swift's `try?`: anything that goes wrong is ignored, but cancellation still cancels. */
    private suspend fun attempt(work: suspend () -> Unit) {
        try {
            work()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /** Using a control while another phone has the BedJet asks that phone to hand it over first. */
    private suspend fun takeControl() {
        val link = link ?: return
        if (link.state == LinkState.READY) return
        val wasElsewhere = link.state == LinkState.YIELDED
        link.requestControl()
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            delay(20)
            if (link.state == LinkState.READY) return
        }
        throw if (wasElsewhere) BedJetError.InUseElsewhere else BedJetError.NotConnected
    }

    // Automation

    /**
     * Restarting into new firmware. [expectsRestart] goes by the clock too, so an action long after an install doesn't
     * find it still restarting.
     */
    val isInstallingUpdate: Boolean get() = isRestarting

    /**
     * Waits, for up to [withinMillis], until a BedJet Lull just let go of has dropped the connection. An automation app
     * sends an action straight after the one before, which let go of the BedJet as it finished; reaching for it before
     * the connection drops would find the link still ready, and what's sent on it would be lost.
     */
    suspend fun waitUntilLetGo(withinMillis: Long) {
        val deadline = System.currentTimeMillis() + withinMillis
        while (isLettingGo && System.currentTimeMillis() < deadline) delay(50)
    }

    /**
     * Reaches the BedJet for an action while Lull is off screen, but only if it is free: an iPhone can't ask another
     * phone for it from the background, and an automation nobody is watching shouldn't take it from a partner
     * anyway. Gives up before the link would scan for the unit, which finds nothing in the background.
     */
    suspend fun reachIfFree(withinMillis: Long) {
        resume(asking = false)
        val deadline = System.currentTimeMillis() + withinMillis
        while (System.currentTimeMillis() < deadline) {
            when (linkState) {
                LinkState.READY -> return
                LinkState.YIELDED, LinkState.BLUETOOTH_OFF, LinkState.UNAUTHORIZED -> throw AutomationError.NeedsLull
                LinkState.SEARCHING, LinkState.CONNECTING -> delay(50)
            }
        }
        throw AutomationError.NeedsLull
    }

    /**
     * Starts a schedule for an action. A night already under way, from this phone or another, is left running. With every
     * slot taken, an action that went on in Lull asks which to reuse ([choosingSlot]), as Start Night there does.
     */
    suspend fun start(schedule: Schedule, choosingSlot: Boolean = false): Started = carryOutAction {
        val run = run
        when {
            run != null -> Started.AlreadyRunning(run.schedule.name)
            runningElsewhereStep != null -> Started.AlreadyRunning(null)
            else -> {
                // Only while it is written, so a night left running or the wait for the BedJet doesn't hold anything still.
                isSavingSchedule = true
                try {
                    startSchedule(schedule, choosingSlot)
                } finally {
                    isSavingSchedule = false
                }
                // Not started yet: Lull is asking which slot to reuse.
                if (this.run == null) Started.ChoosingSlot else Started.Now
            }
        }
    }

    /**
     * Sets the air as the Remote would. With a mode it turns the air on in that mode, ending a schedule that is
     * running; without one it changes the air that is already on. Everything is checked before anything is sent.
     */
    suspend fun apply(air: AirSettings, fahrenheit: Boolean): AppliedAir {
        // Captured first: a time picked while the action reaches the BedJet comes after it.
        val asked = System.currentTimeMillis()
        return carryOutAction {
            val mode = air.mode ?: status?.mode ?: Mode.STANDBY
            air.mode?.let { wanted -> problem(using = wanted)?.let { throw AutomationError.UnsupportedMode(it) } }
            if (air.mode == null) {
                if (mode == Mode.STANDBY) throw AutomationError.AirOff
                if (air.degrees != null) Automation.temperatureRefusal(mode, generation)?.let { throw it }
                // The Remote hides its timer while a schedule runs, since the schedule times its own phases.
                if (air.minutes != null && (run != null || runningElsewhereStep != null)) throw AutomationError.ScheduleSetsTiming
            }
            var applied = AppliedAir(mode)
            air.mode?.let { wanted ->
                // Without a run time, Turn On runs as long as the BedJet allows, not until a time once picked on the Remote.
                if (air.minutes == null) remoteTimer = RemoteTimer.Longest
                changeMode(wanted, asked)
            }
            if (air.degrees != null && adjustsTemperature(mode)) {
                val halfCelsius = Automation.halfCelsius(air.degrees, fahrenheit, mode, status)
                changeTemperature(halfCelsius, asked)
                applied = applied.copy(temperature = halfCelsius)
            }
            if (air.fanPercent != null) {
                val percent = Automation.fanPercent(air.fanPercent)
                changeFan(percent, asked)
                applied = applied.copy(fanPercent = percent)
            }
            if (air.minutes != null) {
                val runTime = Automation.runTimeMinutes(air.minutes, mode, status)
                changeRuntime(runTime)
                applied = applied.copy(minutes = runTime)
            }
            applied
        }
    }

    /** Ends the night if a schedule is running, otherwise turns the air off. */
    suspend fun turnOff(): TurnedOff = carryOutAction {
        val run = run
        when {
            run != null -> {
                stopNight()
                TurnedOff.EndedNight(run.schedule.name)
            }
            runningElsewhereStep != null -> {
                stopNight()
                TurnedOff.EndedNight(null)
            }
            status?.isRunning != true -> TurnedOff.AlreadyOff
            else -> {
                changeMode(Mode.STANDBY, asked = System.currentTimeMillis())
                TurnedOff.StoppedAir
            }
        }
    }

    // A V2 sets the light and the beeps together, so it needs to know the other one first, which the status says.
    suspend fun turnLightRing(on: Boolean) = carryOutAction { connectedLink().setLED(on) }

    suspend fun muteBeeps(muted: Boolean) = carryOutAction { connectedLink().setMuted(muted) }

    /**
     * Does an action's work once the BedJet has said what it is doing now. Its commands would go in among the Wi-Fi
     * setup's or the update's, so it is refused while the BedJet uses its Wi-Fi ([isUsingWiFi]), as the controls are.
     * That is checked once the BedJet has reported, since one may have started while the action was reaching it, or on
     * another phone, and none starts after. Once the BedJet is installing and away, the action finds it unreachable as
     * usual, and an update that finished while Lull was closed doesn't hold it up.
     */
    private suspend fun <T> carryOutAction(work: suspend () -> T): T {
        // Not yet under way while it waits: the first report since connecting can end a night that ran out or stopped
        // early while Lull was closed, which is only recorded while no command of Lull's is under way. Control is taken only
        // here: should the BedJet go to another phone during the wait, the action fails rather than asking for it back.
        takeControl()
        awaitStatus()
        return underWay {
            if (isUsingWiFi) throw AutomationError.Updating
            work()
        }
    }

    /**
     * Waits for the BedJet's first full report since it connected, which says what it is doing now rather than what Lull
     * last saw before it let go.
     */
    private suspend fun awaitStatus() {
        awaitReport(since = connectedAt, timeout = 3_000)
        if (!hasReported(since = connectedAt)) throw BedJetError.Timeout
    }

    /** What the BedJet is doing as far as Lull knows: live while connected, otherwise as it was last seen. */
    val reading: AirReading?
        get() {
            val status = status
            val step = status?.extra?.programStep
            if (isConnected && status != null && step != null) {
                return AirReading(status.mode, status.targetTemperature, status.fanPercent, step, status.remainingSeconds, System.currentTimeMillis())
            }
            return lastSeen?.let { AirReading(it.mode, it.temperature, it.fanPercent, it.programStep, it.remainingSeconds, it.at) }
        }

    private fun logConnection() {
        if (!app.options.logsConnections) return
        val timing = (link as? BleLink)?.lastConnection ?: return
        val line = "new connection: radio %.0f, link %.0f, services %.0f, usable %.0f ms, %s (%s)\n".format(
            timing.radioOn * 1000, timing.linkUp * 1000, timing.services * 1000, timing.usable * 1000,
            if (timing.knownUnit) "known unit" else "scanned", name ?: "unnamed",
        )
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        File(app.application.filesDir, "connections.txt").appendText("$stamp $line")
    }

    // From outside the app

    /**
     * Sets the BedJet to a Quick Remote button's preset, as choosing its mode, temperature, fan and time on the
     * Remote would. Only if it's free ([reachesIfFree]); otherwise Lull, open on the Remote, leaves it to the person.
     */
    suspend fun applyPreset(preset: RemotePreset) {
        // A widget can be set to anything, and Lull may have been opened by something else claiming to be one.
        val clamped = preset.clamped
        if (!reachesIfFree()) return
        // The Remote never offers a mode this kind of BedJet lacks, so a button set up for another kind does nothing.
        // Known for certain now that it's connected.
        if (clamped.mode != Mode.STANDBY && !generation.supports(clamped.mode)) return
        // Like choosing the mode on the Remote: a schedule this phone started on this BedJet ends.
        setMode(clamped.mode)
        if (clamped.mode == Mode.STANDBY) return
        // Each step only while the BedJet is still this phone's: one handed to another phone meanwhile isn't asked back.
        if (adjustsTemperature(clamped.mode) && isConnected) setTemperature(clamped.temperature)
        if (clamped.mode.usesFan && isConnected) setFan(clamped.fanPercent)
        // Last: the mode, temperature and fan each stretch the timer to the longest the BedJet allows.
        if (isConnected) setRuntime(clamped.minutes)
    }

    /**
     * Starts tonight from outside the app, only if the BedJet is free ([reachesIfFree]). A night already under way,
     * from this phone or another, is left running and shown.
     */
    suspend fun startTonightIfFree() {
        if (run != null || !reachesIfFree() || run != null) return
        startTonight()
    }

    /**
     * Whether a widget, tile or shortcut may set the BedJet. A tap on one is easily made by accident, on what the
     * widget last showed, perhaps hours ago, so it never asks another phone to hand the BedJet over, as a control in
     * Lull does: it waits for the BedJet to connect by itself and say what it's doing. One another phone has, or
     * running a schedule another phone started, is left as it is, shown in Lull for the person to decide.
     */
    suspend fun reachesIfFree(): Boolean {
        val deadline = System.currentTimeMillis() + freeWait
        while (System.currentTimeMillis() < deadline) {
            if (linkState == LinkState.YIELDED) return false
            // Only a report from this connection says what the BedJet is doing now.
            val statusReceivedAt = statusReceivedAt
            if (isConnected && statusReceivedAt != null && statusReceivedAt > connectedAt) {
                return !isBusy && runningElsewhereStep == null
            }
            delay(50)
        }
        return false
    }

    // History

    /** The settings worth remembering from a status report. The timer is carried along to tell when it ran out. */
    @Serializable
    private data class Seen(
        val mode: Mode,
        val temperature: Int,
        val fanPercent: Int,
        val programStep: Int,
        val remainingSeconds: Int,
        val at: Long = System.currentTimeMillis(),
    ) {
        constructor(status: DeviceStatus, programStep: Int) :
            this(status.mode, status.targetTemperature, status.fanPercent, programStep, status.remainingSeconds)

        fun sameSettings(other: Seen): Boolean =
            mode == other.mode && fanPercent == other.fanPercent && programStep == other.programStep &&
                (!mode.usesTemperature || temperature == other.temperature)
    }

    /** The first reports after connecting describe the BedJet as it was left, not something that just happened. */
    private val isJustConnected: Boolean get() = System.currentTimeMillis() - connectedAt < 10_000

    /** Waits for the BedJet to hold a setting for a few seconds, so dragging a slider is one entry, not twenty. */
    private fun observe(status: DeviceStatus) {
        // Whether a schedule is running arrives in the second half of the report; without it a phase
        // change can't be told from a change someone made.
        val step = status.extra?.programStep ?: return
        val seen = Seen(status, step)
        val isNew = observed?.let { !seen.sameSettings(it) } ?: true
        observed = seen
        val last = lastSeen
        if (last != null && seen.sameSettings(last)) {
            settling?.cancel()
            settling = null
            lastSeen = seen
            return
        }
        if (!isNew && settling != null) return
        settling?.cancel()
        settling = app.scope.launch {
            delay(3000)
            commitObserved()
        }
    }

    private fun commitObserved() {
        settling?.cancel()
        settling = null
        val now = observed ?: return
        // The first report ever only sets where the history starts from.
        val before = lastSeen
        lastSeen = now
        if (before == null) return
        val wasAway = before.at < connectedAt && isJustConnected
        when {
            before.programStep == 0 && now.programStep >= 1 -> {
                // Started from this phone, it is recorded once the BedJet confirms it.
                if (run == null && !isBusy) record(HistoryEntry.Event.ScheduleStarted(null, null), noticedLater = wasAway)
                return
            }
            // The schedule moved on to its next phase, or ended; its start and end are recorded on their own.
            before.programStep >= 1 && now.programStep >= 1 && before.programStep != now.programStep -> return
            before.programStep >= 1 && now.programStep == 0 && now.mode == Mode.STANDBY -> return
        }
        val clock = System.currentTimeMillis()
        if (now.mode == Mode.STANDBY) {
            if (before.mode == Mode.STANDBY) return
            val timerEnds = before.at + before.remainingSeconds * 1000L
            val ranOut = lastCommandAt < before.at && (before.remainingSeconds < 120 || (wasAway && timerEnds < clock))
            record(HistoryEntry.Event.Off(ranOut), at = if (ranOut) minOf(timerEnds, clock) else clock, noticedLater = wasAway && !ranOut)
        } else {
            record(HistoryEntry.Event.Air(now.mode, now.temperature, now.fanPercent), noticedLater = wasAway)
        }
    }

    private fun record(event: HistoryEntry.Event, at: Long = System.currentTimeMillis(), noticedLater: Boolean = false) {
        var entries = history + HistoryEntry(at, event, status?.ambientTemperature, noticedLater)
        val previous = entries.dropLast(1).lastOrNull()
        if (previous != null && previous.date > at) entries = entries.sortedBy { it.date }
        // Two months is plenty to compare one season's nights with the next.
        val cutoff = System.currentTimeMillis() - 60 * 86_400_000L
        history = entries.filter { it.date >= cutoff }
        saveHistory()
    }

    @Serializable
    private data class SavedHistory(val entries: List<HistoryEntry>, val lastSeen: Seen? = null)

    private fun loadHistory() {
        val saved = historyFile?.let { file -> runCatching { AppModel.json.decodeFromString<SavedHistory>(file.readText()) }.getOrNull() }
        history = saved?.entries ?: if (app.options.demoHistory) HistoryEntry.samples() else emptyList()
        lastSeen = saved?.lastSeen
    }

    private fun saveHistory() {
        app.publishWidgets()
        val file = historyFile ?: return
        val saved = SavedHistory(history, lastSeen)
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(AppModel.json.encodeToString(SavedHistory.serializer(), saved))
            temporary.renameTo(file)
        }
    }

    /** Each BedJet keeps its own history, and demo BedJets are separate ones, so pretend nights never mix with real ones. */
    private val historyFile: File? get() = app.storage.historyFile(id)

    // Schedules

    /** Modes the Remote and the phase editor offer for this kind of BedJet. */
    val remoteModes: List<Mode> get() = com.rohankanjani.lull.remoteModes.filter(generation::supports)
    val phaseModes: List<Mode> get() = com.rohankanjani.lull.phaseModes.filter(generation::supports)

    fun adjustsTemperature(mode: Mode): Boolean = generation.adjustsTemperature(mode)

    /**
     * Modes in a schedule that this BedJet can't run. Schedules are shared between BedJets, so one made for a
     * BedJet 3 may not suit a V2; it is never changed to fit, only refused.
     */
    fun unsupportedModes(schedule: Schedule): List<Mode> =
        schedule.phases.map { it.mode }.filter { !generation.supports(it) }.distinct()

    /** Why this BedJet can't use a mode, if it can't. */
    fun problem(using: Mode): String? {
        if (generation.supports(using)) return null
        return "$displayName is a BedJet V2, which can't run ${using.title}."
    }

    /** Why a schedule can't run on this BedJet when started at [now], if it can't. */
    fun problem(schedule: Schedule, now: Long = System.currentTimeMillis()): String? {
        val modes = unsupportedModes(schedule)
        if (modes.isNotEmpty()) {
            val names = ListFormatter.getInstance().format(modes.map { it.title })
            return "$displayName is a BedJet V2, which can't run $names. Edit “${schedule.name}” or pick another schedule for it."
        }
        // Started then, the last phase has to reach the wake time within what BedJet lets it run.
        return schedule.wakeProblem(startingAt = Format.minuteOfDay(now))
    }

    /** A word or two on how the connection is going, short enough for the pill. */
    val connectionLabel: String
        get() {
            // Invites a tap through to the things worth checking.
            if (isSlowToFind) return "Not found"
            return when (linkState) {
                LinkState.READY -> if (isDemo && !app.options.labelsDemoAsConnected) "Demo" else "Connected"
                LinkState.CONNECTING -> "Connecting"
                LinkState.SEARCHING -> "Searching"
                LinkState.YIELDED -> "Other phone"
                LinkState.BLUETOOTH_OFF -> "Bluetooth off"
                LinkState.UNAUTHORIZED -> "No access"
            }
        }

    val spokenConnectionLabel: String
        get() = if (linkState == LinkState.YIELDED && !isSlowToFind) "in use on another phone" else connectionLabel

    companion object {
        /** How long after Off a report saying the BedJet is still running is taken to have been sent before it. */
        var staleReportWindow = 2_000L
        /** How long a widget, tile or shortcut waits for the BedJet to connect by itself before leaving it to the person. */
        var freeWait = 15_000L
        /** How long a BedJet installing an update is expected back from restarting. Tests wait less. */
        var restartPatienceMillis = 600_000L
    }
}

/** How air started from the Remote is timed. */
@Serializable
sealed interface RemoteTimer {
    /** As long as the BedJet allows, which depends on the mode, temperature and fan. */
    @Serializable
    data object Longest : RemoteTimer

    /** For a duration picked on the Remote, or one set on another phone or the BedJet's own remote: it counts down as it is. */
    @Serializable
    data object Picked : RemoteTimer

    /** Until a time picked on the Remote, as far as the mode allows. */
    @Serializable
    data class Until(val endsAt: Long) : RemoteTimer

    /** A time picked on the Remote that hasn't come yet. */
    val isAhead: Boolean get() = this is Until && endsAt > System.currentTimeMillis()
}
