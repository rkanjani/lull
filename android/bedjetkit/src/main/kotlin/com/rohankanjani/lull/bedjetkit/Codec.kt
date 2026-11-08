package com.rohankanjani.lull.bedjetkit

/**
 * Byte-level encoding for the BedJet 3 GATT protocol.
 *
 * Commands are written to characteristic 0x2004. Status arrives as notifications on 0x2000,
 * and replies to `query` requests are read from 0x2006.
 */
object Codec {
    object Query {
        const val DEVICE_NAME = 0x00
        const val PROGRAM_NAMES = 0x04
        const val FIRMWARE = 0x20
        fun fragment(slot: Int, index: Int): Int = 7 + slot * 4 + index
    }

    /** Codes the unit raises in byte 29 of its status. */
    object Notification {
        const val CLEAN_FILTER = 1
        const val UPDATE_AVAILABLE = 2
        const val UPDATE_FAILED = 3
        /** A program was started but the unit's clock isn't set, so it didn't run. */
        const val PROGRAM_NEEDS_CLOCK = 4
        /** A program was started with a step longer than the unit allows, so it didn't run. */
        const val PROGRAM_TOO_LONG = 5
    }

    /**
     * Where the unit is in using its Wi-Fi, reported in byte 26 of its status. Below 20 it is still at it; from 20
     * up it has finished, and that result stays until it next starts. See `docs/BEDJET_3_FIRMWARE.md`.
     */
    object WiFiPhase {
        const val IDLE = 0
        const val STARTING = 1
        const val JOINING = 2
        const val JOINED = 3
        const val TESTING = 4
        const val CHECKING = 5
        const val UPDATING = 6
        const val RESTARTING = 7
        const val NO_NETWORK = 20
        const val CANNOT_JOIN = 21
        const val NO_ADDRESS = 22
        const val NO_SERVER = 23
        const val WORKS = 24
        const val TEST_FAILED = 25
        const val UP_TO_DATE = 26
        const val RADIO_OFF = 27
        /** Newer firmware was found, and the unit restarts to install it. */
        const val INSTALLING = 28
        const val UPDATE_FAILED = 29

        fun isResult(phase: Int): Boolean = phase >= NO_NETWORK
    }

    /** One fragment of a stored program, as read back from the unit. */
    data class Fragment(val total: Int, val steps: List<ProgramStep>)

    // Commands

    fun setMode(mode: Mode): ByteArray? = mode.button?.let { bytes(0x01, it) }
    fun setRuntime(minutes: Int): ByteArray = bytes(0x02, minutes / 60, minutes % 60)
    fun setTemperature(halfDegrees: Int): ByteArray = bytes(0x03, halfDegrees.coerceIn(0, 255))
    fun setFan(step: Int): ByteArray = bytes(0x07, step.coerceIn(0, 19))
    fun setClock(hour: Int, minute: Int): ByteArray = bytes(0x08, hour, minute)
    fun setLED(on: Boolean): ByteArray = bytes(0x01, if (on) 0x46 else 0x47)
    fun setMuted(muted: Boolean): ByteArray = bytes(0x01, if (muted) 0x48 else 0x49)
    fun startProgram(slot: Int): ByteArray = bytes(0x01, 0x80 + slot)
    fun deleteProgram(slot: Int): ByteArray = bytes(0x01, 0x83 + slot)
    val acknowledgeNotification: ByteArray get() = bytes(0x01, 0x52)
    /** Checks BedJet's server over the unit's Wi-Fi. Newer firmware is installed straight away, restarting the unit. */
    val updateFirmware: ByteArray get() = bytes(0x01, 0x43)
    fun query(request: Int, tag: Int): ByteArray = bytes(0x41, request, tag)

    /**
     * Why the unit can't be given this Wi-Fi network, if it can't. Network names are at most 32 bytes and WPA
     * passwords 8 to 63; the official app only checks that the password is long enough.
     */
    fun wifiProblem(network: String, password: String): String? {
        val passwordBytes = password.encodeToByteArray().size
        return when {
            network.isEmpty() -> "Enter the name of your Wi-Fi network."
            network.encodeToByteArray().size > 32 -> "Wi-Fi network names are at most 32 characters."
            passwordBytes < 8 -> "Wi-Fi passwords are at least 8 characters."
            passwordBytes > 63 -> "Wi-Fi passwords are at most 63 characters."
            else -> null
        }
    }

    fun nameProgram(slot: Int, name: String): ByteArray {
        val bytes = ByteArray(19)
        bytes[0] = 0x40
        bytes[1] = (4 + slot).toByte()
        bytes[2] = 0x10
        sanitize(name).encodeToByteArray().forEachIndexed { i, byte -> bytes[3 + i] = byte }
        return bytes
    }

    /** One write per 8 steps; every fragment repeats the program's total step count. */
    fun writeProgram(slot: Int, steps: List<ProgramStep>): List<ByteArray> {
        val fragments = maxOf(1, (steps.size + Limits.STEPS_PER_FRAGMENT - 1) / Limits.STEPS_PER_FRAGMENT)
        return (0 until fragments).map { index ->
            val bytes = ByteArray(43)
            bytes[0] = 0x40
            bytes[1] = Query.fragment(slot, index).toByte()
            bytes[2] = steps.size.toByte()
            val chunk = steps.drop(index * Limits.STEPS_PER_FRAGMENT).take(Limits.STEPS_PER_FRAGMENT)
            chunk.forEachIndexed { i, step ->
                val base = 3 + i * 5
                bytes[base] = step.mode.raw.toByte()
                bytes[base + 1] = step.fanStep.coerceIn(0, 19).toByte()
                bytes[base + 2] = step.temperature.coerceIn(0, 255).toByte()
                bytes[base + 3] = ((step.hours and 0x7f) or (if (step.endsAtClockTime) 0x80 else 0)).toByte()
                bytes[base + 4] = step.minutes.toByte()
            }
            bytes
        }
    }

    /** Names are 15 ASCII bytes on the unit. */
    fun sanitize(name: String): String =
        name.filter { it.code in 32 until 127 }.take(Limits.NAME_LENGTH)

    // Replies

    /** Payload of a fragment reply (after the echoed request and tag bytes). */
    fun parseFragment(payload: ByteArray, index: Int): Fragment? {
        val bytes = payload.unsigned()
        val total = bytes.firstOrNull() ?: return null
        if (total > Limits.STEPS_PER_PROGRAM) return null
        val expected = minOf(Limits.STEPS_PER_FRAGMENT, maxOf(0, total - index * Limits.STEPS_PER_FRAGMENT))
        if (bytes.size < 1 + expected * 5) return null
        val steps = (0 until expected).map { i ->
            val base = 1 + i * 5
            val mode = Mode.fromRaw(bytes[base]) ?: return null
            ProgramStep(
                mode = mode,
                fanStep = bytes[base + 1],
                temperature = bytes[base + 2],
                hours = bytes[base + 3] and 0x7f,
                minutes = bytes[base + 4],
                endsAtClockTime = bytes[base + 3] and 0x80 != 0,
            )
        }
        return Fragment(total, steps)
    }

    /** 16-byte name slots. A slot starting with 0x01 has never been saved. */
    fun parseNames(payload: ByteArray): List<String?> =
        (payload.indices step 16).map { start ->
            val slot = payload.copyOfRange(start, minOf(start + 16, payload.size))
            if (slot.first() == 0x01.toByte()) null else parseText(slot)
        }

    fun parseText(payload: ByteArray): String =
        payload.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

    // Status

    /** True for a status notification, as opposed to the remainder served by a follow-up read. */
    fun isStatusStart(chunk: ByteArray): Boolean =
        chunk.size >= 20 && chunk[1] == 0x56.toByte() && chunk[3] == 0x01.toByte()

    fun parseStatus(packet: ByteArray): DeviceStatus? {
        val b = packet.unsigned()
        if (b.size < 20) return null
        val mode = Mode.fromRaw(b[9]) ?: return null
        return DeviceStatus(
            remainingSeconds = b[4] * 3600 + b[5] * 60 + b[6],
            actualTemperature = b[7],
            targetTemperature = b[8],
            mode = mode,
            fanStep = b[10],
            maxRuntimeMinutes = b[11] * 60 + b[12],
            minTemperature = b[13],
            maxTemperature = b[14],
            ambientTemperature = b[17],
            lastShutdownReason = b[18],
            extra = if (b.size >= 30) {
                DeviceStatus.Extra(
                    ledEnabled = b[27] and 0x10 != 0,
                    beepsMuted = b[27] and 0x01 != 0,
                    programStep = b[28],
                    notification = b[29],
                    wifiPhase = b[26],
                )
            } else {
                null
            },
        )
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.unsigned(): IntArray = IntArray(size) { this[it].toInt() and 0xff }
}
