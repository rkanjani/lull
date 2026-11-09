package com.rohankanjani.lull.bedjetkit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalTime

/**
 * Fixtures marked "captured" are packets the official iOS app exchanged with a BedJet V2, as published in
 * github.com/roycamp/bedjet-protocol. The rest follow the formats in the official Android app.
 * Mirrors BedJetKit's CodecV2Tests.swift.
 */
class CodecV2Test {
    private fun data(hex: String): ByteArray = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun status(mode: Mode, fanStep: Int, target: Int, seconds: Int, muted: Boolean = true) = DeviceStatus(
        remainingSeconds = seconds, actualTemperature = 50, targetTemperature = target, mode = mode, fanStep = fanStep,
        maxRuntimeMinutes = 720, minTemperature = 44, maxTemperature = 80, ambientTemperature = 50, lastShutdownReason = 0,
        extra = DeviceStatus.Extra(ledEnabled = true, beepsMuted = muted, programStep = 0, notification = 0),
    )

    @Test
    fun commandsMatchCapturedBytes() {
        assertArrayEquals(data("58 01 0b 9b"), CodecV2.request(CodecV2.Query.INFO))
        assertArrayEquals(data("58 03 06 02 10 8c"), CodecV2.request(CodecV2.Query.DEVICE_NAME))
        assertArrayEquals(data("58 02 01 02 a2"), CodecV2.setMode(Mode.HEAT, Mode.STANDBY))
        assertArrayEquals(data("58 02 01 01 a3"), CodecV2.setMode(Mode.TURBO, Mode.COOL))
        assertArrayEquals(data("58 02 11 01 93"), CodecV2.setSettings(ledOn = true, muted = true))
        assertArrayEquals(data("58 02 11 02 92"), CodecV2.setSettings(ledOn = false, muted = false))
    }

    @Test
    fun modesAreButtonsThatToggle() {
        assertArrayEquals(data("58 02 01 03 a1"), CodecV2.setMode(Mode.STANDBY, Mode.COOL))
        assertNull(CodecV2.setMode(Mode.COOL, Mode.COOL))
        assertNull(CodecV2.setMode(Mode.STANDBY, Mode.STANDBY))
        assertNull(CodecV2.setMode(Mode.DRY, Mode.STANDBY))
    }

    @Test
    fun sceneMatchesCapturedTimerCommand() {
        val heat = status(Mode.HEAT, fanStep = 16, target = 76, seconds = 3 * 60)
        assertArrayEquals(data("58 07 0e 02 11 cc 00 14 00 9f"), CodecV2.setRuntime(20, heat))
    }

    @Test
    fun sceneKeepsTimerWhenChangingFan() {
        val cool = status(Mode.COOL, fanStep = 14, target = 54, seconds = 9 * 3600 + 59 * 60 + 55)
        assertArrayEquals(data("58 07 0e 03 0c b6 09 3b 37 52"), CodecV2.setFan(11, cool))
    }

    @Test
    fun parsesCapturedOffStatus() {
        val off = CodecV2.parseStatus(data("59 0b 02 a7 0c 00 00 41 00 00 00 00 00 a5"))!!
        assertEquals(Mode.STANDBY, off.mode)
        assertEquals(0x27, off.actualTemperature)
        assertEquals(0x41, off.targetTemperature)
        assertEquals(0, off.remainingSeconds)
        assertEquals(false, off.extra?.ledEnabled)
        assertEquals(false, off.extra?.beepsMuted)
        assertEquals(0, off.extra?.programStep)
        assertFalse(off.isRunning)
    }

    @Test
    fun parsesRunningStatus() {
        val cool = CodecV2.parseStatus(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))!!
        assertEquals(Mode.COOL, cool.mode)
        assertEquals(60, cool.fanPercent)
        assertEquals(9 * 3600 + 59 * 60 + 52, cool.remainingSeconds)
        assertEquals(0x36, cool.targetTemperature)
        assertEquals(true, cool.extra?.beepsMuted)
        assertEquals(true, cool.extra?.ledEnabled)
        assertEquals(1, cool.extra?.programStep)
        assertEquals(3, cool.lastShutdownReason)
        assertEquals(720, cool.maxRuntimeMinutes)
    }

    @Test
    fun reassemblesPacketsAcrossNotifications() {
        var buffer = data("00 ff 59 0b 02 34 6c 9e f4")
        var split = CodecV2.takePackets(buffer)
        assertTrue(split.packets.isEmpty())
        buffer = split.rest + data("b6 80 00 08 00 03 26 59 12 05 02 42 65 64")
        split = CodecV2.takePackets(buffer)
        assertEquals(1, split.packets.size)
        assertArrayEquals(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"), split.packets[0])
        buffer = split.rest + data("72 6f 6f 6d 20 20 20 20 20 20 20 20 20 a5")
        split = CodecV2.takePackets(buffer)
        val name = split.packets.firstOrNull()?.let { CodecV2.reply(CodecV2.Query.DEVICE_NAME, it) }?.let(CodecV2::parseName)
        assertEquals("Bedroom", name)
        assertTrue(split.rest.isEmpty())
    }

    @Test
    fun dropsPacketsWithBadChecksums() {
        val split = CodecV2.takePackets(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 27"))
        assertTrue(split.packets.isEmpty())
        assertTrue(split.rest.size < 14)
    }

    @Test
    fun parsesInfo() {
        val packet = data("59 0c 01 02 35 00 00 00 00 00 01 00 00 00 61")
        val info = CodecV2.reply(CodecV2.Query.INFO, packet)?.let(CodecV2::parseInfo)
        assertEquals(CodecV2.Info(protocolVersion = 2, firmware = 53, extendedSequences = true), info)
        assertNull(CodecV2.reply(CodecV2.Query.DEVICE_NAME, packet))
    }

    @Test
    fun writesSequenceSteps() {
        val heat = ProgramStep(Mode.HEAT, fanStep = 7, temperature = 68, hours = 0, minutes = 30)
        assertArrayEquals(data("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"), CodecV2.writeStep(heat, index = 1, minutes = 30, extended = true))
        val cool = ProgramStep(Mode.COOL, fanStep = 12, temperature = 44, hours = 6, minutes = 0)
        assertArrayEquals(data("58 09 05 80 06 03 0d 06 00 00 28 d5"), CodecV2.writeStep(cool, index = 0, minutes = 360, extended = false))
        try {
            CodecV2.writeStep(ProgramStep(Mode.DRY, fanStep = 5, temperature = 60, hours = 1, minutes = 0), index = 0, minutes = 60, extended = true)
            fail("wrote a mode the V2 doesn't have")
        } catch (_: BedJetError.InvalidProgram) {
        }
    }

    @Test
    fun runsSequence() {
        val time = LocalTime.of(23, 5, 9)
        assertArrayEquals(data("58 04 0d 17 05 09 71"), CodecV2.runSequence(time, extended = true))
        assertArrayEquals(data("58 01 0d 99"), CodecV2.runSequence(time, extended = false))
    }

    @Test
    fun storingAndStartingSendsTheOfficialAppsSequence() = runTest {
        val link = RecordingV2Link()
        val program = StoredProgram("Tonight", listOf(
            ProgramStep(Mode.TURBO, fanStep = 19, temperature = 86, hours = 0, minutes = 10),
            ProgramStep(Mode.HEAT, fanStep = 7, temperature = 68, hours = 0, minutes = 30),
            ProgramStep(Mode.COOL, fanStep = 12, temperature = 44, hours = 6, minutes = 0),
        ))
        link.store(program, slot = 2)
        link.startProgram(slot = 2)
        assertEquals(6, link.sent.size)
        assertArrayEquals(data("58 01 0c 9a"), link.sent[0])
        assertArrayEquals(data("58 0a 05 80 07 01 14 00 0a 00 50 00 a2"), link.sent[1])
        assertArrayEquals(data("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"), link.sent[2])
        assertArrayEquals(data("58 0a 05 82 07 03 0d 06 00 00 28 00 d1"), link.sent[3])
        assertArrayEquals(data("58 0d 05 03 0a"), link.sent[4].copyOf(5))
        assertArrayEquals(data("58 04 0d"), link.sent[5].copyOf(3))
    }

    @Test
    fun clockTimeStepsBecomeDurationsFromNow() = runTest {
        // A V2 only runs steps for a length of time, so one that ends at a time of day is measured from when it's stored.
        val link = RecordingV2Link()
        val now = LocalTime.now()
        val end = (now.hour * 60 + now.minute + 90) % 1440
        val program = StoredProgram("Wake", listOf(
            ProgramStep(Mode.COOL, fanStep = 12, temperature = 44, hours = end / 60, minutes = end % 60, endsAtClockTime = true),
        ))
        link.store(program, slot = 0)
        val minutes = (link.sent[1][7].toInt() and 0xff) * 60 + (link.sent[1][8].toInt() and 0xff)
        assertTrue("an hour and a half, or a minute less if the clock ticked over: $minutes", minutes in 89..90)
    }

    @Test
    fun refusesModesTheV2Lacks() = runTest {
        val link = RecordingV2Link()
        val program = StoredProgram("Cocoon", listOf(ProgramStep(Mode.EXTENDED_HEAT, fanStep = 5, temperature = 60, hours = 2, minutes = 0)))
        try {
            link.store(program, slot = 0)
            fail("stored a mode the V2 doesn't have")
        } catch (error: BedJetError) {
            assertEquals(BedJetError.InvalidProgram(CodecV2.UNSUPPORTED_MODES), error)
        }
        assertTrue(link.sent.isEmpty())
    }
}

/** Answers like a V2 on firmware 53 and records every command. */
private class RecordingV2Link : BedJetLink {
    override val state = LinkState.READY
    override val generation = Generation.V2
    override val lastStatus: DeviceStatus? = null
    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null
    val sent = mutableListOf<ByteArray>()

    override fun start() {}
    override fun stop() {}
    override suspend fun send(command: ByteArray) {
        sent += command
    }

    override suspend fun query(request: Int): ByteArray {
        if (request != CodecV2.Query.INFO) throw BedJetError.BadResponse
        return byteArrayOf(2, 53, 0, 0, 0, 0, 0, 1, 0, 0, 0)
    }
}
