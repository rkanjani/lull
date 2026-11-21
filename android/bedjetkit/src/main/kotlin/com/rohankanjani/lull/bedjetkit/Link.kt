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

    /**
     * Connects whenever the BedJet is free, without asking another phone that holds it to hand it over. A link that
     * never shares its BedJet with another phone has no one to ask, so it connects as it always does.
     */
    fun listen() = start()

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
        throw BedJetError.InvalidProgram("This BedJet V2 needs a firmware update before it can run schedules. Update it in the official BedJet app.")
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
