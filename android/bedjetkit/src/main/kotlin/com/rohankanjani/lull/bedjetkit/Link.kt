package com.rohankanjani.lull.bedjetkit

import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.random.Random

/** A connection to one BedJet. `BleLink` talks to real hardware; [DemoLink] simulates a unit. */
interface BedJetLink {
    val state: LinkState
    /** Which protocol the connected unit speaks. Known once the link is ready. */
    val generation: Generation get() = Generation.V3
    /** The most recent status the unit reported. Some V2 commands repeat parts of it. */
    val lastStatus: DeviceStatus?
    var onStateChange: ((LinkState) -> Unit)?
    var onStatus: ((DeviceStatus) -> Unit)?

    fun start()
    fun stop()

    /**
     * Asks for the BedJet on the person's behalf. It accepts one phone at a time, so this also asks
     * another phone that is holding it to hand it over.
     */
    fun requestControl() {}

    /** Writes a command and waits for the unit to acknowledge it. */
    suspend fun send(command: ByteArray)

    /** Asks the unit for stored data and returns the reply payload. */
    suspend fun query(request: Int): ByteArray

    /**
     * Gives a BedJet 3 the Wi-Fi network it fetches firmware over: the name, then the password, which starts a
     * connection test.
     */
    suspend fun writeWiFi(network: ByteArray, password: ByteArray) {
        throw BedJetError.InvalidWiFi(BedJetError.NO_WIFI_SETUP)
    }
}

// Remote

suspend fun BedJetLink.setMode(mode: Mode) {
    when (generation) {
        Generation.V3 -> send(Codec.setMode(mode) ?: return)
        Generation.V2 -> {
            val current = lastStatus?.mode ?: Mode.STANDBY
            val step = lastStatus?.extra?.programStep ?: 0
            val cool = CodecV2.setMode(Mode.COOL, Mode.STANDBY)
            if (mode == Mode.STANDBY && current == Mode.STANDBY && step > 0 && cool != null) {
                // A sequence that is waiting reports the air off but keeps going, and there is no off button.
                // Starting a mode takes over from the sequence, and pressing it again turns the air off.
                send(cool)
                delay(1000)
                send(cool)
                return
            }
            send(CodecV2.setMode(mode, current) ?: return)
        }
    }
}

suspend fun BedJetLink.setTemperature(halfDegrees: Int) {
    when (generation) {
        Generation.V3 -> send(Codec.setTemperature(halfDegrees))
        Generation.V2 -> send(CodecV2.setTemperature(halfDegrees, currentStatus()))
    }
}

/** 0..19, where the fan runs at `(step + 1) * 5` percent. */
suspend fun BedJetLink.setFan(step: Int) {
    when (generation) {
        Generation.V3 -> send(Codec.setFan(step))
        Generation.V2 -> send(CodecV2.setFan(step, currentStatus()))
    }
}

suspend fun BedJetLink.setRuntime(minutes: Int) {
    when (generation) {
        Generation.V3 -> send(Codec.setRuntime(minutes))
        Generation.V2 -> send(CodecV2.setRuntime(minutes, currentStatus()))
    }
}

suspend fun BedJetLink.setLED(on: Boolean) {
    when (generation) {
        Generation.V3 -> send(Codec.setLED(on))
        Generation.V2 -> send(CodecV2.setSettings(ledOn = on, muted = lastStatus?.extra?.beepsMuted == true))
    }
}

suspend fun BedJetLink.setMuted(muted: Boolean) {
    when (generation) {
        Generation.V3 -> send(Codec.setMuted(muted))
        Generation.V2 -> send(CodecV2.setSettings(ledOn = lastStatus?.extra?.ledEnabled != false, muted = muted))
    }
}

/** Clears the notification the unit is raising. The V2 raises none. */
suspend fun BedJetLink.acknowledgeNotification() {
    if (generation != Generation.V3) return
    send(Codec.acknowledgeNotification)
}

/** Several V2 commands set temperature, fan and timer at once, so they need the current values. */
private fun BedJetLink.currentStatus(): DeviceStatus = lastStatus ?: throw BedJetError.NotConnected

// Device

/** The V2 has no clock of its own; it is told the time when a sequence starts. */
suspend fun BedJetLink.syncClock(time: LocalTime = LocalTime.now()) {
    if (generation != Generation.V3) return
    send(Codec.setClock(time.hour, time.minute))
}

suspend fun BedJetLink.deviceName(): String = when (generation) {
    Generation.V3 -> Codec.parseText(query(Codec.Query.DEVICE_NAME))
    Generation.V2 -> CodecV2.parseName(query(CodecV2.Query.DEVICE_NAME))
}

suspend fun BedJetLink.firmwareVersion(): String = when (generation) {
    Generation.V3 -> Codec.parseText(query(Codec.Query.FIRMWARE))
    Generation.V2 -> "${v2Info().firmware} (BedJet V2)"
}

private suspend fun BedJetLink.v2Info(): CodecV2.Info =
    CodecV2.parseInfo(query(CodecV2.Query.INFO)) ?: throw BedJetError.BadResponse

// Wi-Fi and firmware

/**
 * Gives a BedJet 3 the Wi-Fi network it fetches firmware over, then follows the unit's test of it.
 * [progress] hears each [Codec.WiFiPhase] step. Throws the unit's result unless it joined.
 */
suspend fun BedJetLink.joinWiFi(network: String, password: String, progress: (Int) -> Unit = {}) {
    if (generation != Generation.V3) throw BedJetError.InvalidWiFi(BedJetError.NO_WIFI)
    Codec.wifiProblem(network, password)?.let { throw BedJetError.InvalidWiFi(it) }
    val shown = lastStatus?.extra?.wifiPhase
    writeWiFi(network.encodeToByteArray(), password.encodeToByteArray())
    val result = wifiResult(shown, restarts = false, progress)
    // It has a network now, so "no network" is only the old result still showing.
    if (result == Codec.WiFiPhase.NO_NETWORK) throw BedJetError.Timeout
    if (result != Codec.WiFiPhase.WORKS) throw BedJetError.WiFi(result)
}

/**
 * Asks a BedJet 3 to check BedJet's server for newer firmware, over its Wi-Fi. The unit can't only check:
 * newer firmware is installed straight away, and the unit restarts, which drops the connection.
 */
suspend fun BedJetLink.updateFirmware(progress: (Int) -> Unit = {}): FirmwareUpdate {
    if (generation != Generation.V3) throw BedJetError.InvalidWiFi(BedJetError.NO_WIFI)
    val before = firmwareOrNull()
    val shown = lastStatus?.extra?.wifiPhase
    send(Codec.updateFirmware)
    return when (val result = wifiResult(shown, restarts = true, progress)) {
        Codec.WiFiPhase.UP_TO_DATE -> {
            // The unit checks, and installs, while it's restarting, so newer firmware may already be running.
            val after = firmwareOrNull()
            if (before != null && after != null && after != before) FirmwareUpdate.Updated(after) else FirmwareUpdate.UpToDate
        }
        // About to restart into the new firmware, so asking its version now would only wait out the restart.
        Codec.WiFiPhase.INSTALLING -> FirmwareUpdate.Installing
        else -> throw BedJetError.WiFi(result)
    }
}

/** Like Swift's `try?`: anything that goes wrong is ignored, but cancellation still cancels. */
private suspend fun BedJetLink.firmwareOrNull(): String? = try {
    firmwareVersion()
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

/**
 * Follows the unit's Wi-Fi phase until it gives a result. The result of its last attempt stays in the status
 * until it starts again, so a result only counts once the unit has moved on from the one [shown] when asked.
 *
 * Asked to check for firmware, a BedJet 3 restarts within a few seconds and does the whole check while it's
 * away, so the result is there as soon as it's back (captured: 01 43 acknowledged, link lost 4 s later, back
 * within a second reporting No update needed). The official app waits for it to reconnect too. The link comes
 * back by itself, so a dropped link is waited out, except once the unit is installing: then the restart is
 * the end, and it comes back with the new firmware, so the result is [Codec.WiFiPhase.INSTALLING].
 *
 * Only a firmware check [restarts] the unit. Any other dropped link is just Bluetooth, and the result the
 * unit shows once it's back may still be the one from before.
 */
private suspend fun BedJetLink.wifiResult(shown: Int?, restarts: Boolean, progress: (Int) -> Unit): Int {
    var working = false
    var installing = false
    var restarted = false
    var last: Int? = null
    // Polled every 200 ms: 20 seconds to start, 10 minutes in all, which also bounds a restart. A link lost
    // for 20 seconds when nothing restarted the unit is lost, not waited for.
    var waited = 0
    var away = 0
    repeat(3000) {
        if (state != LinkState.READY) {
            if (installing) return Codec.WiFiPhase.INSTALLING
            if (!restarts && away == 100) throw BedJetError.NotConnected
            if (away == 0) {
                waited = 0
                if (restarts) {
                    progress(Codec.WiFiPhase.RESTARTING)
                    restarted = true
                    working = false
                    last = null
                }
            }
            away += 1
            delay(200)
            return@repeat
        }
        away = 0
        val phase = lastStatus?.extra?.wifiPhase
        if (phase != null) {
            if (!Codec.WiFiPhase.isResult(phase)) {
                // Restarting once it's installing is still installing, not restarting to check.
                val restartsToInstall = installing && phase == Codec.WiFiPhase.RESTARTING
                if (phase != Codec.WiFiPhase.IDLE && phase != last && !restartsToInstall) progress(phase)
                working = working || phase != Codec.WiFiPhase.IDLE
                installing = installing || phase == Codec.WiFiPhase.UPDATING
            } else if (working || restarted || (shown != null && phase != shown)) {
                return phase
            }
            last = phase
        }
        waited += 1
        if (!working && waited == 100) {
            // A quick check can come and go between status reports, so a failure still showing is the answer.
            val showing = last
            if (showing != null && Codec.WiFiPhase.isResult(showing) &&
                showing != Codec.WiFiPhase.WORKS && showing != Codec.WiFiPhase.UP_TO_DATE
            ) {
                return showing
            }
            throw BedJetError.Timeout
        }
        delay(200)
    }
    // A slow download can outlast the wait.
    if (installing) return Codec.WiFiPhase.INSTALLING
    throw BedJetError.Timeout
}

// Programs

/**
 * Everything saved on the unit, one entry per slot; `null` where the slot is empty.
 * Empty for a V2, whose single sequence has no name and isn't read back.
 */
suspend fun BedJetLink.storedPrograms(): List<StoredProgram?> {
    if (generation != Generation.V3) return emptyList()
    val names = Codec.parseNames(query(Codec.Query.PROGRAM_NAMES))
    return (0 until Limits.SLOTS).map { slot ->
        val name = names.getOrNull(slot) ?: return@map null
        val steps = programSteps(slot)
        if (steps.isEmpty()) null else StoredProgram(name, steps)
    }
}

/** The steps saved in a BedJet 3 slot. */
suspend fun BedJetLink.programSteps(slot: Int): List<ProgramStep> {
    val steps = mutableListOf<ProgramStep>()
    var index = 0
    while (true) {
        val payload = query(Codec.Query.fragment(slot, index))
        val fragment = Codec.parseFragment(payload, index) ?: throw BedJetError.BadResponse
        steps += fragment.steps
        index += 1
        if (steps.size >= fragment.total || fragment.steps.isEmpty()) return steps
    }
}

/**
 * Saves a program into a slot, replacing whatever was there, and confirms the unit kept it.
 * A V2 has one sequence, so the slot is ignored.
 */
suspend fun BedJetLink.store(program: StoredProgram, slot: Int) {
    if (program.steps.isEmpty()) throw BedJetError.InvalidProgram("Add at least one phase first.")
    val most = if (generation == Generation.V2) CodecV2.STEPS_PER_SEQUENCE else Limits.STEPS_PER_PROGRAM
    if (program.steps.size > most) throw BedJetError.InvalidProgram("A BedJet schedule can hold up to $most phases.")
    if (!program.steps.all { it.fitsUnitLimits }) throw BedJetError.ProgramTooLong
    if (generation != Generation.V3) return storeSequence(program.steps)
    for (command in Codec.writeProgram(slot, program.steps)) send(command)
    send(Codec.nameProgram(slot, program.name))
    if (programSteps(slot) != program.steps) throw BedJetError.VerifyFailed
}

/**
 * Replaces the V2's sequence. The unit's reply to reading a step back isn't known, so instead of
 * verifying here, the start is confirmed by the unit reporting a running step.
 */
private suspend fun BedJetLink.storeSequence(steps: List<ProgramStep>) {
    if (!steps.all { generation.supports(it.mode) }) throw BedJetError.InvalidProgram(CodecV2.UNSUPPORTED_MODES)
    val info = v2Info()
    if (!info.runsSequences) {
        throw BedJetError.InvalidProgram("This BedJet V2's firmware is too old to run schedules, and neither BedJet app can update it. Ask BedJet support about an update.")
    }
    // Steps can only last a duration, so ones that end at a time of day are measured from now.
    val now = LocalTime.now()
    var clock = now.hour * 60 + now.minute
    val commands = steps.mapIndexed { index, step ->
        var minutes = step.hours * 60 + step.minutes
        if (step.endsAtClockTime) minutes = ((minutes - clock) % 1440 + 1440) % 1440
        clock += minutes
        CodecV2.writeStep(step, index, minutes, info.extendedSequences)
    }
    // The official app leaves the unit a moment between writes.
    for (command in listOf(CodecV2.clearSequence) + commands + CodecV2.tagSequence(List(10) { Random.nextInt(256) })) {
        send(command)
        delay(200)
    }
}

suspend fun BedJetLink.startProgram(slot: Int) {
    when (generation) {
        Generation.V3 -> {
            syncClock()
            send(Codec.startProgram(slot))
        }
        Generation.V2 -> {
            val info = v2Info()
            send(CodecV2.runSequence(LocalTime.now(), info.extendedSequences))
        }
    }
}
