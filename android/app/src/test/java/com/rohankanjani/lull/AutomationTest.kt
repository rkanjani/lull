package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What automations send and say, kept to what the BedJet allows as the Remote keeps it. Mirrors AutomationTests.swift. */
class AutomationTest {
    private fun status(mode: Mode, min: Int = 38, max: Int = 80, maxRuntime: Int = 0) = DeviceStatus(
        remainingSeconds = 0, actualTemperature = 50, targetTemperature = 60, mode = mode, fanStep = 7,
        maxRuntimeMinutes = maxRuntime, minTemperature = min, maxTemperature = max, ambientTemperature = 45,
        lastShutdownReason = 0, extra = null,
    )

    @Test
    fun temperatureIsInThePersonsUnitAndKeptToTheLimits() {
        assertEquals(60, Automation.halfCelsius(86, fahrenheit = true, mode = Mode.HEAT, status = null))
        assertEquals(60, Automation.halfCelsius(30, fahrenheit = false, mode = Mode.HEAT, status = null))
        // The mode's own range, until the BedJet reports the range for the mode it is in.
        assertEquals(80, Automation.halfCelsius(120, fahrenheit = true, mode = Mode.HEAT, status = null))
        assertEquals(45, Automation.halfCelsius(50, fahrenheit = true, mode = Mode.HEAT, status = null))
        assertEquals(70, Automation.halfCelsius(100, fahrenheit = true, mode = Mode.HEAT, status = status(Mode.HEAT, max = 70)))
        assertEquals(76, Automation.halfCelsius(100, fahrenheit = true, mode = Mode.HEAT, status = status(Mode.COOL, max = 62)))
        // Any number an automation passes is kept to the range, however far out.
        assertEquals(80, Automation.halfCelsius(Int.MAX_VALUE, fahrenheit = true, mode = Mode.HEAT, status = null))
        assertEquals(45, Automation.halfCelsius(Int.MIN_VALUE, fahrenheit = true, mode = Mode.HEAT, status = null))
    }

    @Test
    fun aTemperatureIsRefusedWhereTheBedJetTakesNone() {
        assertNull(Automation.temperatureRefusal(Mode.COOL, Generation.V3))
        assertNull(Automation.temperatureRefusal(Mode.HEAT, Generation.V2))
        assertEquals(AutomationError.FixedTemperature(Mode.TURBO), Automation.temperatureRefusal(Mode.TURBO, Generation.V3))
        // A V2 only takes one for Heat, though Cool has one on a BedJet 3.
        assertEquals(AutomationError.HeatOnlyTemperature, Automation.temperatureRefusal(Mode.COOL, Generation.V2))
        assertEquals(AutomationError.FixedTemperature(Mode.TURBO), Automation.temperatureRefusal(Mode.TURBO, Generation.V2))
    }

    @Test
    fun fanIsRoundedToStepsOfFive() {
        assertEquals(40, Automation.fanPercent(42))
        assertEquals(45, Automation.fanPercent(43))
        assertEquals(5, Automation.fanPercent(0))
        assertEquals(100, Automation.fanPercent(150))
    }

    @Test
    fun runTimeIsKeptToWhatTheBedJetAllows() {
        assertEquals(30, Automation.runTimeMinutes(30, Mode.HEAT, null))
        assertEquals(1, Automation.runTimeMinutes(0, Mode.HEAT, null))
        assertEquals(Mode.HEAT.maxMinutes, Automation.runTimeMinutes(600, Mode.HEAT, null))
        assertEquals(10, Automation.runTimeMinutes(600, Mode.TURBO, null))
        assertEquals(120, Automation.runTimeMinutes(600, Mode.COOL, status(Mode.COOL, maxRuntime = 120)))
    }

    @Test
    fun modesAreNamedAsOnTheRemote() {
        assertEquals(listOf("cool", "heat", "gentle_heat", "turbo", "dry"), remoteModes.map { it.automationName })
        assertEquals("pause", Mode.WAIT.automationName)
        assertEquals("off", Mode.STANDBY.automationName)
        assertEquals("Gentle heat", Mode.EXTENDED_HEAT.title)
    }

    @Test
    fun actionsSayWhatTheyDid() {
        val heat = AppliedAir(Mode.HEAT, temperature = 60, fanPercent = 40, minutes = 90)
        assertEquals("Turned on Heat on My side: 86°, fan 40%, turns off in 1 hr 30 min.", Automation.airDone(heat, AirSettings(mode = Mode.HEAT, degrees = 86), "My side", fahrenheit = true))
        assertEquals("Turned on Cool on My side.", Automation.airDone(AppliedAir(Mode.COOL), AirSettings(mode = Mode.COOL), "My side", fahrenheit = true))
        assertEquals("Set My side to 86°.", Automation.airDone(AppliedAir(Mode.HEAT, temperature = 60), AirSettings(degrees = 86), "My side", fahrenheit = true))
        assertEquals("My side turns off in 1 hr 30 min.", Automation.airDone(AppliedAir(Mode.HEAT, minutes = 90), AirSettings(minutes = 90), "My side", fahrenheit = true))
    }

    // Status

    private val now = 1_800_000_000_000L

    private fun reading(mode: Mode, step: Int = 0, remaining: Int, minutesAgo: Int) =
        AirReading(mode, temperature = 60, fanPercent = 40, programStep = step, remainingSeconds = remaining, at = now - minutesAgo * 60_000L)

    @Test
    fun statusFollowsTheNightFromWhenItStarted() {
        val schedule = Schedule.templates[1]
        // Warm Welcome: 10 minutes of Turbo, then Heat at 68 for 30 minutes.
        val run = ActiveRun(schedule, startedAt = now - 15 * 60_000L)
        val report = BedJetReport(run = run, reading = reading(Mode.TURBO, step = 1, remaining = 600, minutesAgo = 15), generation = Generation.V3, now = now)
        assertTrue(report.nightRunning)
        assertEquals("Warm Welcome", report.schedule)
        assertEquals(run.endsAt, report.nightEnds)
        assertEquals(Mode.HEAT, report.mode)
        assertEquals(68, report.temperature)
        assertEquals("“Warm Welcome” is running on My side.", report.summary("My side", fahrenheit = true))
        val ended = BedJetReport(run = run, reading = null, generation = Generation.V3, now = run.endsAt + 60_000L)
        assertFalse(ended.nightRunning)
    }

    @Test
    fun statusSaysOffOnceTheTimerRanOut() {
        val running = BedJetReport(run = null, reading = reading(Mode.HEAT, remaining = 3600, minutesAgo = 30), generation = Generation.V3, now = now)
        assertEquals(Mode.HEAT, running.mode)
        assertEquals("My side is on: Heat, 86°, fan 40%.", running.summary("My side", fahrenheit = true))
        val ranOut = BedJetReport(run = null, reading = reading(Mode.HEAT, remaining = 3600, minutesAgo = 90), generation = Generation.V3, now = now)
        assertEquals(Mode.STANDBY, ranOut.mode)
        assertEquals("My side is off.", ranOut.summary("My side", fahrenheit = true))
    }

    @Test
    fun statusUsesWhatWasLastSeen() {
        val elsewhere = BedJetReport(run = null, reading = reading(Mode.COOL, step = 2, remaining = 1200, minutesAgo = 5), generation = Generation.V3, now = now)
        assertTrue(elsewhere.nightRunning)
        assertNull(elsewhere.schedule)
        assertEquals("A schedule from another phone is running on My side.", elsewhere.summary("My side", fahrenheit = true))
        assertEquals(now - 300_000L, elsewhere.lastSeen)
        val never = BedJetReport(run = null, reading = null, generation = Generation.V3, now = now)
        assertEquals("Lull hasn't reached My side yet.", never.summary("My side", fahrenheit = true))
        // A V2 only takes a temperature for Heat.
        val cool = BedJetReport(run = null, reading = reading(Mode.COOL, remaining = 300, minutesAgo = 1), generation = Generation.V2, now = now)
        assertNull(cool.temperature)
        assertEquals(40, cool.fanPercent)
        assertEquals("My side is on: Cool, fan 40%.", cool.summary("My side", fahrenheit = true))
    }

    // Intents from automation apps

    @Test
    fun automationAppsCanSendOptionsAsTextNumbersOrSwitches() {
        val call = AutomationCall.from(AutomationCall.TURN_ON, mapOf("mode" to " gentle_heat ", "temperature" to "86", "fan" to 42L, "bedjet" to "My side")::get)!!
        assertEquals(AutomationCall(AutomationCall.TURN_ON, bedJet = "My side", mode = "gentle_heat", temperature = 86, fan = 42), call)
        assertEquals(true, AutomationCall.from(AutomationCall.SET_LIGHT_RING, mapOf("on" to "on")::get)?.on)
        assertEquals(false, AutomationCall.from(AutomationCall.MUTE_BEEPS, mapOf("muted" to false)::get)?.muted)
        assertEquals(30, AutomationCall.from(AutomationCall.SET_RUN_TIME, mapOf("minutes" to 29.6)::get)?.minutes)
        assertEquals(false, AutomationCall.from(AutomationCall.SET_LIGHT_RING, mapOf("on" to 0L)::get)?.on)
        // Not a number is no number, rather than a crash.
        assertNull(AutomationCall.from(AutomationCall.TURN_ON, mapOf("temperature" to "NaN")::get)?.temperature)
        assertNull(AutomationCall.from(AutomationCall.TURN_ON, mapOf("temperature" to Double.NaN)::get)?.temperature)
        assertNull(AutomationCall.from("com.rohankanjani.lull.SOMETHING_ELSE") { null })
        assertNull(AutomationCall.from(null) { null })
        // A blank option is no option, so Start Night picks tonight's schedule.
        assertNull(AutomationCall.from(AutomationCall.START_NIGHT, mapOf("schedule" to "  ")::get)?.schedule)
    }
}
