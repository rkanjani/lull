package com.rohankanjani.lull.bedjetkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sign

/**
 * A simulated BedJet that understands the same bytes as the real one.
 * Used for the Android emulator (which has no BedJet nearby), Demo mode and tests.
 */
class DemoLink(
    val name: String = DEFAULT_NAME,
    slots: List<StoredProgram?> = listOf(null, null, null),
    /** Where the one-second clock runs. The app passes its main scope. */
    private val scope: CoroutineScope? = null,
    wifiNetwork: String? = null,
) : BedJetLink {
    override var state: LinkState = LinkState.SEARCHING
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChange?.invoke(value)
        }
    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null

    /** False pretends the unit is switched off or out of range: it is looked for but never connects. */
    var answers = true
    var slots: List<StoredProgram?> = slots
        private set
    var clock: Pair<Int, Int>? = null
        private set
    /** The Wi-Fi network the unit was given, which it needs to check for firmware. */
    var wifiNetwork: String? = wifiNetwork
        private set
    override val lastStatus: DeviceStatus? get() = if (state == LinkState.READY) reported else null
    /** The step of the program running on the unit, connected or not; 0 when none is. */
    val programStep: Int get() = status.extra?.programStep ?: 0

    private var status = DeviceStatus(
        remainingSeconds = 0, actualTemperature = 46, targetTemperature = 60, mode = Mode.STANDBY, fanStep = 7,
        maxRuntimeMinutes = 0, minTemperature = 38, maxTemperature = 80, ambientTemperature = 45, lastShutdownReason = 0,
        extra = DeviceStatus.Extra(ledEnabled = true, beepsMuted = false, programStep = 0, notification = 0),
    )

    companion object {
        const val DEFAULT_NAME = "BEDJET-DEMO"
    }

    private data class Run(val slot: Int, val step: Int, val stepEnds: Long)

    private var running: Run? = null
    private val pendingSteps = mutableMapOf<Int, List<ProgramStep>>()
    /** The Wi-Fi phases still to report, one a second, like a unit joining a network or checking for firmware. */
    private var wifiSteps = ArrayDeque<Int>()
    /** Seconds left before the unit is back from restarting, as it does when asked to check for firmware. */
    private var restarting = 0
    /** Back from restarting, the unit's first report comes without its second half, as a real one's does. */
    private var firstHalfOnly = false
    private val reported: DeviceStatus get() = if (firstHalfOnly) status.copy(extra = null) else status
    private var timer: Job? = null

    override fun start() {
        if (timer != null || !answers) return
        state = LinkState.CONNECTING
        timer = (scope ?: MainScope()).launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    override fun stop() {
        timer?.cancel()
        timer = null
        state = LinkState.SEARCHING
    }

    override suspend fun send(command: ByteArray) {
        val bytes = command.map { it.toInt() and 0xff }
        if (bytes.size < 2) throw BedJetError.BadResponse
        when {
            bytes[0] == 0x01 -> press(bytes[1])
            bytes[0] == 0x02 && bytes.size >= 3 ->
                status = status.copy(remainingSeconds = minOf(bytes[1] * 60 + bytes[2], status.maxRuntimeMinutes) * 60)
            bytes[0] == 0x03 -> {
                status = status.copy(targetTemperature = bytes[1])
                limitHeat()
            }
            bytes[0] == 0x07 -> {
                status = status.copy(fanStep = bytes[1])
                limitHeat()
            }
            bytes[0] == 0x08 && bytes.size >= 3 -> clock = bytes[1] to bytes[2]
            bytes[0] == 0x40 -> store(command)
        }
        // A unit that is restarting says nothing until it's back.
        if (restarting == 0) onStatus?.invoke(reported)
    }

    /** Pretends the network works whatever the password, then reports its firmware as current when asked to check. */
    override suspend fun writeWiFi(network: ByteArray, password: ByteArray) {
        wifiNetwork = network.decodeToString()
        wifiSteps = ArrayDeque(
            listOf(Codec.WiFiPhase.STARTING, Codec.WiFiPhase.JOINING, Codec.WiFiPhase.JOINED, Codec.WiFiPhase.TESTING, Codec.WiFiPhase.WORKS),
        )
    }

    override suspend fun query(request: Int): ByteArray = when (request) {
        Codec.Query.DEVICE_NAME -> name.encodeToByteArray() + 0
        Codec.Query.FIRMWARE -> "V1.20 (demo)".encodeToByteArray() + 0
        Codec.Query.PROGRAM_NAMES -> slots.fold(ByteArray(0)) { data, program ->
            val slot = ByteArray(16)
            if (program != null) {
                program.name.encodeToByteArray().take(Limits.NAME_LENGTH).forEachIndexed { i, byte -> slot[i] = byte }
            } else {
                slot[0] = 0x01
            }
            data + slot
        }
        in 7 until 19 -> {
            val slot = (request - 7) / 4
            val index = (request - 7) % 4
            val steps = slots[slot]?.steps ?: emptyList()
            val fragments = Codec.writeProgram(slot, steps)
            if (index in fragments.indices) fragments[index].copyOfRange(2, fragments[index].size)
            else byteArrayOf(steps.size.toByte())
        }
        else -> throw BedJetError.BadResponse
    }

    private fun press(button: Int) {
        when (button) {
            in 0x01..0x06 -> {
                running = null
                setProgramStep(0)
                val mode = Mode.entries.firstOrNull { it.button == button } ?: Mode.STANDBY
                enter(mode, if (mode == Mode.STANDBY) 0 else if (mode == Mode.TURBO) 10 else 60)
                // Manual modes start at an hour and can be stretched; these ceilings are illustrative.
                status = status.copy(
                    maxRuntimeMinutes = when (mode) {
                        Mode.STANDBY -> 0
                        Mode.TURBO -> 10
                        Mode.HEAT -> 240
                        else -> 720
                    },
                )
                limitHeat()
            }
            0x46, 0x47 -> status = status.copy(extra = status.extra?.copy(ledEnabled = button == 0x46))
            0x48, 0x49 -> status = status.copy(extra = status.extra?.copy(beepsMuted = button == 0x48))
            0x52 -> setNotification(0)
            0x43 -> {
                // Like the real unit, restart, which turns off the air and drops the connection for a few seconds, and
                // check while away, so the result is there when it's back.
                running = null
                setProgramStep(0)
                enter(Mode.STANDBY, 0)
                restarting = 2
                state = LinkState.CONNECTING
                wifiSteps = ArrayDeque(listOf(if (wifiNetwork == null) Codec.WiFiPhase.NO_NETWORK else Codec.WiFiPhase.UP_TO_DATE))
            }
            in 0x80..0x82 -> {
                val slot = button - 0x80
                val program = slots[slot] ?: return
                // Like the real unit, refuse to start rather than stop partway through the night.
                if (clock == null) {
                    setNotification(Codec.Notification.PROGRAM_NEEDS_CLOCK)
                    return
                }
                if (!program.steps.all { it.fitsUnitLimits }) {
                    setNotification(Codec.Notification.PROGRAM_TOO_LONG)
                    return
                }
                val now = System.currentTimeMillis()
                running = Run(slot, -1, now)
                advanceProgram(now)
            }
            in 0x83..0x85 -> slots = slots.toMutableList().also { it[button - 0x83] = null }
        }
    }

    private fun setProgramStep(step: Int) {
        status = status.copy(extra = status.extra?.copy(programStep = step))
    }

    private fun setNotification(code: Int) {
        status = status.copy(extra = status.extra?.copy(notification = code))
    }

    private fun enter(mode: Mode, minutes: Int) {
        // Like the real unit, report the limits of the mode it is in.
        status = status.copy(
            mode = mode,
            remainingSeconds = minutes * 60,
            maxRuntimeMinutes = minutes,
            minTemperature = if (mode.usesTemperature) mode.temperatureRange.first else 20,
            maxTemperature = if (mode.usesTemperature) mode.temperatureRange.last else 80,
            targetTemperature = if (status.targetTemperature in mode.temperatureRange) status.targetTemperature
            else mode.temperatureRange.first,
        )
    }

    /** Hot air at a high fan is cut off sooner, so manual Heat's ceiling follows temperature and fan. */
    private fun limitHeat() {
        if (status.mode != Mode.HEAT || running != null) return
        val longest = ProgramStep.longestMinutes(Mode.HEAT, status.targetTemperature, status.fanStep)
        val ceiling = minOf(240, longest)
        status = status.copy(maxRuntimeMinutes = ceiling, remainingSeconds = minOf(status.remainingSeconds, ceiling * 60))
    }

    private fun store(command: ByteArray) {
        if (command.size < 3) return
        val target = command[1].toInt() and 0xff
        if (target in 4..6) {
            val slot = target - 4
            val name = Codec.parseText(command.copyOfRange(3, command.size))
            val steps = pendingSteps.remove(slot)
            slots = slots.toMutableList().also {
                it[slot] = if (steps != null) StoredProgram(name, steps) else it[slot]?.copy(name = name)
            }
        } else if (target in 7 until 19) {
            val slot = (target - 7) / 4
            val index = (target - 7) % 4
            val fragment = Codec.parseFragment(command.copyOfRange(2, command.size), index) ?: return
            val steps = (if (index == 0) emptyList() else pendingSteps[slot] ?: emptyList()) + fragment.steps
            pendingSteps[slot] = steps
            if (steps.size >= fragment.total) {
                slots = slots.toMutableList().also { it[slot] = StoredProgram(it[slot]?.name ?: "", steps) }
            }
        }
    }

    /** Pretends the running program started this much earlier, for demos that should show a night in progress. */
    fun skipAhead(minutes: Int) {
        val run = running ?: return
        running = run.copy(stepEnds = run.stepEnds - minutes * 60_000L)
        while (true) {
            val current = running ?: break
            if (System.currentTimeMillis() < current.stepEnds) break
            advanceProgram(current.stepEnds)
        }
        running?.let { status = status.copy(remainingSeconds = maxOf(0, ((it.stepEnds - System.currentTimeMillis()) / 1000).toInt())) }
        onStatus?.invoke(status)
    }

    private fun advanceProgram(start: Long) {
        val run = running ?: return
        val program = slots[run.slot] ?: return
        val step = run.step + 1
        if (step >= program.steps.size) {
            running = null
            setProgramStep(0)
            enter(Mode.STANDBY, 0)
            return
        }
        val next = program.steps[step]
        val minutes = next.hours * 60 + next.minutes
        running = run.copy(step = step, stepEnds = start + minutes * 60_000L)
        status = status.copy(fanStep = next.fanStep, targetTemperature = next.temperature)
        setProgramStep(step + 1)
        enter(next.mode, minutes)
    }

    private fun tick() {
        if (restarting > 0) {
            restarting -= 1
            if (restarting > 0) return
            firstHalfOnly = true
        } else {
            firstHalfOnly = false
        }
        if (state == LinkState.CONNECTING) state = LinkState.READY
        wifiSteps.removeFirstOrNull()?.let { phase -> status = status.copy(extra = status.extra?.copy(wifiPhase = phase)) }
        running?.let { if (System.currentTimeMillis() >= it.stepEnds) advanceProgram(it.stepEnds) }
        if (status.mode != Mode.STANDBY && status.mode != Mode.WAIT) {
            status = status.copy(remainingSeconds = maxOf(0, status.remainingSeconds - 1))
            if (status.remainingSeconds == 0 && running == null) enter(Mode.STANDBY, 0)
        }
        val goal = if (status.mode == Mode.STANDBY || status.mode == Mode.WAIT) status.ambientTemperature else status.targetTemperature
        status = status.copy(actualTemperature = status.actualTemperature + (goal - status.actualTemperature).sign)
        onStatus?.invoke(reported)
    }
}
