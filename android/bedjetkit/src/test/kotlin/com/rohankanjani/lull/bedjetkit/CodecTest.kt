package com.rohankanjani.lull.bedjetkit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/** Byte fixtures below were captured from a BedJet 3 running firmware V1.20. Mirrors BedJetKit's CodecTests.swift. */
class CodecTest {
    private fun data(hex: String): ByteArray = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.dropFirst(n: Int): ByteArray = copyOfRange(n, size)

    @Test
    fun parsesStatusCapturedFromDevice() {
        val packet = data("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 35 00 01 53")
        val status = Codec.parseStatus(packet)!!
        assertEquals(Mode.STANDBY, status.mode)
        assertEquals(46, status.actualTemperature)
        assertEquals(79, status.targetTemperature)
        assertEquals(100, status.fanPercent)
        assertEquals(45, status.ambientTemperature)
        assertEquals(0, status.extra?.programStep)
        assertEquals(true, status.extra?.ledEnabled)
        assertEquals(true, status.extra?.beepsMuted)
        assertEquals(0, status.lastShutdownReason)
        assertEquals(Codec.Notification.CLEAN_FILTER, status.extra?.notification)
    }

    @Test
    fun firstChunkAloneStillParses() {
        val head = data("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12")
        assertTrue(Codec.isStatusStart(head))
        assertNull(Codec.parseStatus(head)!!.extra)
    }

    @Test
    fun parsesProgramCapturedFromDevice() {
        val reply = data("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        val fragment = Codec.parseFragment(reply.dropFirst(2), 0)!!
        assertEquals(4, fragment.total)
        assertEquals(
            listOf(
                ProgramStep(Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 0),
                ProgramStep(Mode.COOL, fanStep = 13, temperature = 49, hours = 3, minutes = 0),
                ProgramStep(Mode.COOL, fanStep = 9, temperature = 46, hours = 1, minutes = 15),
                ProgramStep(Mode.HEAT, fanStep = 4, temperature = 47, hours = 1, minutes = 0),
            ),
            fragment.steps,
        )
    }

    @Test
    fun writingReproducesTheBytesTheDeviceReturned() {
        val reply = data("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        val steps = Codec.parseFragment(reply.dropFirst(2), 0)!!.steps
        val commands = Codec.writeProgram(0, steps)
        assertEquals(1, commands.size)
        assertArrayEquals(byteArrayOf(0x40, 0x07), commands[0].copyOf(2))
        assertArrayEquals(reply.dropFirst(2), commands[0].dropFirst(2))
    }

    @Test
    fun parsesNamesCapturedFromDevice() {
        val reply = data("04 05 63 68 6f 74 65 20 6d 6f 74 65 7a 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 73 6c 65 65 65 65 70 00 00 00 00 00 00 00 00 00")
        assertEquals(listOf("chote motez", null, "sleeeep"), Codec.parseNames(reply.dropFirst(2)))
    }

    @Test
    fun longProgramsSplitIntoFragments() {
        val steps = (0 until 19).map { ProgramStep(Mode.COOL, fanStep = it, temperature = 40, hours = 0, minutes = 30) }
        val commands = Codec.writeProgram(2, steps)
        assertEquals(listOf(15, 16, 17), commands.map { it[1].toInt() })
        assertTrue(commands.all { it.size == 43 && it[2] == 19.toByte() })
        assertEquals(steps.subList(16, 19), Codec.parseFragment(commands[2].dropFirst(2), 2)?.steps)
    }

    @Test
    fun clockTimeFlagSharesTheHoursByte() {
        val step = ProgramStep(Mode.COOL, fanStep = 14, temperature = 36, hours = 6, minutes = 30, endsAtClockTime = true)
        val command = Codec.writeProgram(0, listOf(step))[0]
        assertArrayEquals(data("04 0e 24 86 1e"), command.copyOfRange(3, 8))
        assertEquals(listOf(step), Codec.parseFragment(command.dropFirst(2), 0)?.steps)
    }

    @Test
    fun nameCommandIsPaddedAndTrimmed() {
        val command = Codec.nameProgram(1, "A very long schedule name ✨")
        assertEquals(19, command.size)
        assertArrayEquals(byteArrayOf(0x40, 0x05, 0x10), command.copyOf(3))
        assertEquals("A very long sch", Codec.parseText(command.dropFirst(3)))
    }

    @Test
    fun handOverRequestsAreSpecificToOneBedJet() {
        val mine = Handoff.uuid("BEDJET-3B04")
        assertEquals(mine, Handoff.uuid("BEDJET-3B04"))
        assertNotEquals(mine, Handoff.uuid("BEDJET-3B05"))
    }

    /** iPhones and Android phones hand the BedJet to each other, so both must derive the same request. */
    @Test
    fun handOverRequestMatchesTheIPhoneApp() {
        assertEquals(UUID.fromString("80B8472D-C1D5-500B-868E-A410AFBDEBF4"), Handoff.uuid("BEDJET-3B04"))
    }

    @Test
    fun simulatedBedJetsGoByTheirOwnNames() = runTest {
        assertEquals(DemoLink.DEFAULT_NAME, DemoLink().deviceName())
        assertEquals("BEDJET-DEMO-2", DemoLink(name = "BEDJET-DEMO-2").deviceName())
    }

    @Test
    fun runtimeCommandSplitsHoursAndMinutes() {
        assertArrayEquals(byteArrayOf(0x02, 2, 30), Codec.setRuntime(150))
    }

    @Test
    fun runtimeChangesTheShutOffTimeAndStopsAtTheModeLimit() = runTest {
        val link = DemoLink()
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.setMode(Mode.HEAT)
        assertEquals(60 * 60, latest?.remainingSeconds)
        link.send(Codec.setRuntime(150))
        assertEquals(150 * 60, latest?.remainingSeconds)
        link.send(Codec.setRuntime(600))
        assertEquals(240 * 60, latest?.remainingSeconds)
    }

    @Test
    fun storeAndReadBackThroughSimulatedDevice() = runTest {
        val old = StoredProgram("old", listOf(ProgramStep(Mode.DRY, fanStep = 1, temperature = 60, hours = 1, minutes = 0)))
        val link = DemoLink(slots = listOf(old, null, null))
        val steps = (0 until 11).map {
            ProgramStep(if (it % 2 == 0) Mode.COOL else Mode.HEAT, fanStep = it, temperature = 50, hours = 0, minutes = 20 + it)
        }
        link.store(StoredProgram("Deep Sleep", steps), 1)
        val stored = link.storedPrograms()
        assertEquals("old", stored[0]?.name)
        assertEquals(StoredProgram("Deep Sleep", steps), stored[1])
        assertNull(stored[2])
    }

    @Test
    fun startingAProgramSyncsTheClockAndRunsTheFirstStep() = runTest {
        val link = DemoLink()
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, fanStep = 5, temperature = 44, hours = 2, minutes = 0))), 0)
        link.startProgram(0)
        assertNotNull(link.clock)
        assertEquals(Mode.COOL, latest?.mode)
        assertEquals(5, latest?.fanStep)
        assertEquals(1, latest?.extra?.programStep)
    }

    @Test
    fun clockTimeStepsEndAtTheirTimeOnTheSimulatedDevice() = runTest {
        val link = DemoLink()
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        val wake = ProgramStep(Mode.COOL, fanStep = 5, temperature = 44, hours = 6, minutes = 30, endsAtClockTime = true)
        link.store(StoredProgram("Wake", listOf(wake)), 0)
        // Sent by hand: `startProgram` would set the unit's clock to now.
        link.send(Codec.setClock(22, 30))
        link.send(Codec.startProgram(0))
        assertEquals("from 22:30 to 6:30", 8 * 3600, latest?.remainingSeconds)
        val pastMidnight = ProgramStep(Mode.COOL, fanStep = 5, temperature = 44, hours = 0, minutes = 10, endsAtClockTime = true)
        link.store(StoredProgram("Late", listOf(pastMidnight)), 1)
        link.send(Codec.setClock(23, 50))
        link.send(Codec.startProgram(1))
        assertEquals("from 23:50 to 0:10", 20 * 60, latest?.remainingSeconds)
        // A demo that pretends the night started earlier still ends it at the time of day.
        link.skipAhead(5)
        assertTrue(20 * 60 - (latest?.remainingSeconds ?: 0) <= 1)
    }

    /** Values follow the heat table in the official BedJet app (v1.0.6), which keeps the unit's safety cut-off. */
    @Test
    fun heatIsCutOffSoonerWhenHotterAndFaster() {
        assertEquals(60, ProgramStep.longestMinutes(Mode.HEAT, temperature = 80, fanStep = 19))
        assertEquals(120, ProgramStep.longestMinutes(Mode.HEAT, temperature = 75, fanStep = 19))
        assertEquals(240, ProgramStep.longestMinutes(Mode.HEAT, temperature = 80, fanStep = 0))
        assertEquals(60, ProgramStep.longestMinutes(Mode.HEAT, temperature = 81, fanStep = 0))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(Mode.HEAT, temperature = 66, fanStep = 19))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(Mode.HEAT, temperature = 50, fanStep = 19))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(Mode.COOL, temperature = 80, fanStep = 19))
        assertFalse(ProgramStep(Mode.HEAT, fanStep = 19, temperature = 80, hours = 2, minutes = 0).fitsUnitLimits)
        assertTrue(ProgramStep(Mode.HEAT, fanStep = 19, temperature = 80, hours = 1, minutes = 0).fitsUnitLimits)
        assertTrue(ProgramStep(Mode.HEAT, fanStep = 19, temperature = 80, hours = 6, minutes = 0, endsAtClockTime = true).fitsUnitLimits)
    }

    @Test
    fun programsPastTheHeatCutOffAreNotSaved() = runTest {
        val link = DemoLink()
        val program = StoredProgram("too hot", listOf(ProgramStep(Mode.HEAT, fanStep = 19, temperature = 80, hours = 3, minutes = 0)))
        try {
            link.store(program, 0)
            fail("stored a step the unit would cut off")
        } catch (error: BedJetError) {
            assertEquals(BedJetError.ProgramTooLong, error)
        }
        assertNull(link.slots[0])
    }

    @Test
    fun unitRefusesAProgramItCannotFinishUntilAcknowledged() = runTest {
        val tooHot = ProgramStep(Mode.HEAT, fanStep = 19, temperature = 80, hours = 3, minutes = 0)
        val link = DemoLink(slots = listOf(StoredProgram("too hot", listOf(tooHot)), null, null))
        var latest: DeviceStatus? = null
        link.onStatus = { latest = it }
        link.startProgram(0)
        assertEquals(Mode.STANDBY, latest?.mode)
        assertEquals(0, latest?.extra?.programStep)
        assertEquals(Codec.Notification.PROGRAM_TOO_LONG, latest?.extra?.notification)
        link.send(Codec.acknowledgeNotification)
        assertEquals(0, latest?.extra?.notification)
    }
}
