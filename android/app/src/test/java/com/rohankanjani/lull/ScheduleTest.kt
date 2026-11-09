package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.bedjetkit.ProgramStep
import com.rohankanjani.lull.bedjetkit.StoredProgram
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    @Test
    fun schedulesSurviveSavingAndLoading() {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(Schedule.serializer())
        val saved = json.encodeToString(serializer, Schedule.templates)
        assertEquals(Schedule.templates, json.decodeFromString(serializer, saved))
    }

    @Test
    fun phasesBecomeTheStepsTheBedJetStores() {
        val phase = Phase(Mode.HEAT, temperature = 68, fanPercent = 40, minutes = 95)
        assertEquals(ProgramStep(Mode.HEAT, fanStep = 7, temperature = 68, hours = 1, minutes = 35), phase.step)
    }

    @Test
    fun clockTimeStepsImportAsDurationsFromBedtime() {
        // Ends at 6:30, starting from the usual 22:30 bedtime: eight hours.
        val program = StoredProgram("", listOf(ProgramStep(Mode.COOL, fanStep = 5, temperature = 44, hours = 6, minutes = 30, endsAtClockTime = true)))
        val schedule = Schedule.importing(program)
        assertEquals("From BedJet", schedule.name)
        assertEquals(480, schedule.phases.single().minutes)
        assertEquals(30, schedule.phases.single().fanPercent)
        assertEquals("a last step that ends at a time of day is a wake time", 390, schedule.wakeTime)
    }

    @Test
    fun aRunKnowsWhichPhaseItIsIn() {
        val schedule = Schedule.templates[0]
        val run = ActiveRun(schedule, startedAt = 0)
        assertEquals(0, run.phase(now = 10 * 60_000L)?.index)
        assertEquals(35, run.phase(now = 10 * 60_000L)?.minutesLeft)
        assertEquals(1, run.phase(now = 50 * 60_000L)?.index)
        assertNull(run.phase(now = schedule.totalMinutes * 60_000L))
        assertEquals(0.5, run.progress(now = run.endsAt / 2), 0.001)
    }

    @Test
    fun temperaturesRoundLikeTheIPhoneApp() {
        assertEquals(86, Format.degrees(60, fahrenheit = true))
        assertEquals(30, Format.degrees(60, fahrenheit = false))
        assertEquals(23, Format.degrees(45, fahrenheit = false))
        assertEquals(60, Format.halfCelsius(86, fahrenheit = true))
        assertEquals("1 hr 5 min", Format.duration(65))
        assertEquals("2 hr", Format.duration(120))
        assertEquals("45 min", Format.duration(45))
    }

    @Test
    fun heatPhasesAreCappedByTheSafetyCutOff() {
        assertEquals(60, Phase(Mode.HEAT, temperature = 80, fanPercent = 100, minutes = 30).maxMinutes)
        assertEquals(10, Phase(Mode.TURBO, minutes = 10).maxMinutes)
        assertEquals(240, Phase(Mode.HEAT, temperature = 66, fanPercent = 40, minutes = 30).maxMinutes)
    }

    // Wake time: how the phases stretch or shrink to reach it, when they can't, and what's saved to the BedJet.

    /** Cool Drift (45, 300 and 90 minutes, then 30 of Heat) ending at 6:30, with every phase free to stretch or shrink. */
    private val coolDrift = Schedule.templates[0].let { cool ->
        cool.copy(wakeTime = 6 * 60 + 30, phases = cool.phases.map { it.copy(fixedLength = false) })
    }

    /** Cool Drift without a wake time, running for its set lengths. */
    private val untimedCoolDrift = Schedule.templates[0].copy(wakeTime = null)

    @Test
    fun theNightScalesToEndAtTheWakeTime() {
        // From the usual 10:30 bedtime, 7 hr 45 min of phases stretch to the 8 hours before 6:30.
        val fromBedtime = coolDrift.resolved(startingAt = 22 * 60 + 30)
        assertEquals(listOf(46, 310, 93, 31), fromBedtime.phases.map { it.minutes })
        assertEquals(480, fromBedtime.totalMinutes)
        // An hour late, they shrink in proportion to the 7 hours left.
        assertEquals(listOf(41, 271, 81, 27), coolDrift.resolved(startingAt = 23 * 60 + 30).phases.map { it.minutes })
        assertNull(coolDrift.wakeProblem(startingAt = 22 * 60 + 30))
        assertNull(coolDrift.wakeProblem(startingAt = 23 * 60 + 30))
        assertEquals("no wake time, no scaling", untimedCoolDrift, untimedCoolDrift.resolved(startingAt = 23 * 60))
    }

    @Test
    fun aNightTooFarFromItsWakeTimeIsShownAsSet() {
        // At 9 a.m. the 6:30 wake time is 21 hr 30 min away, more than Lull stretches a night to.
        assertEquals("not stretched to most of a day", coolDrift, coolDrift.resolved(startingAt = 9 * 60))
        assertNotEquals("16 hours away is still scaled", coolDrift, coolDrift.resolved(startingAt = 14 * 60 + 30))
    }

    @Test
    fun wakeTimeWrapsPastMidnight() {
        val schedule = Schedule("Short", listOf(Phase(Mode.COOL, minutes = 60)), wakeTime = 10)
        assertEquals(20, schedule.minutesToWake(startingAt = 23 * 60 + 50))
        assertEquals(listOf(20), schedule.resolved(startingAt = 23 * 60 + 50).phases.map { it.minutes })
    }

    @Test
    fun phasesAtALimitStayThereAndTheRestShare() {
        // Turbo runs 10 minutes at most, so doubling the night leaves it at 10 and gives the Cool the rest.
        val turbo = Schedule("Warm", listOf(Phase(Mode.TURBO, fanPercent = 100, minutes = 10), Phase(Mode.COOL, minutes = 100)), wakeTime = 2 * 60 + 10)
        assertEquals(listOf(10, 210), turbo.resolved(startingAt = 22 * 60 + 30).phases.map { it.minutes })
        // Halving it would leave a phase under BedJet's 5 minutes.
        val short = Schedule("Brief", listOf(Phase(Mode.COOL, minutes = 5), Phase(Mode.COOL, minutes = 100)), wakeTime = 23 * 60 + 25)
        assertEquals(listOf(5, 50), short.resolved(startingAt = 22 * 60 + 30).phases.map { it.minutes })
    }

    @Test
    fun aPhaseHeldUpDoesntPushAnotherPastItsLimit() {
        // A 1-minute Cool and a 10-minute Turbo in 14 minutes: the Cool is raised to 5, which leaves Turbo 9, within its 10.
        assertEquals(listOf(5, 9), Schedule.scale(listOf(1, 10), listOf(5..719, 5..10), 14))
    }

    @Test
    fun roundingTiesGoToTheEarlierPhase() {
        // So the iPhone and Android scale a night to the same minutes.
        assertEquals(listOf(2, 1, 1), Schedule.scale(listOf(1, 1, 1), listOf(1..100, 1..100, 1..100), 4))
    }

    @Test
    fun aNightTooFarFromItsWakeTimeIsAProblem() {
        val schedule = Schedule("Long", listOf(Phase(Mode.COOL, minutes = 60)), wakeTime = 6 * 60 + 30)
        assertEquals("${Format.clock(390)} is more than 16 hr away. Start the night nearer to it.", schedule.wakeProblem(startingAt = 13 * 60))
    }

    @Test
    fun aNightTooShortForItsPhasesIsAProblem() {
        assertEquals(
            "Only 15 min is left before ${Format.clock(390)}, less than these phases need. Set a later wake time.",
            coolDrift.wakeProblem(startingAt = 6 * 60 + 15),
        )
        val one = Schedule("One", listOf(Phase(Mode.COOL, minutes = 60)), wakeTime = 6 * 60 + 30)
        assertEquals(
            "Only 2 min is left before ${Format.clock(390)}, less than a phase needs. Set a later wake time.",
            one.wakeProblem(startingAt = 6 * 60 + 28),
        )
    }

    @Test
    fun aNightLongerThanItsPhasesCanRunIsAProblem() {
        val turbo = Schedule("Burst", listOf(Phase(Mode.TURBO, fanPercent = 100, minutes = 10)), wakeTime = 23 * 60 + 30)
        assertEquals(
            "To end at ${Format.clock(1410)}, the night would run 1 hr, but these phases can run for up to 10 min in all. " +
                "Add a phase or set an earlier wake time.",
            turbo.wakeProblem(startingAt = 22 * 60 + 30),
        )
        assertEquals(listOf(10), turbo.resolved(startingAt = 22 * 60 + 30).phases.map { it.minutes })
    }

    @Test
    fun fixedPhasesKeepTheirLength() {
        // A quick cooldown and a warm-up of half an hour each, and the night between them fitted to 6:00.
        val schedule = Schedule("Fixed ends", listOf(
            Phase(Mode.COOL, fanPercent = 100, minutes = 30, fixedLength = true),
            Phase(Mode.COOL, fanPercent = 30, minutes = 300),
            Phase(Mode.HEAT, temperature = 58, fanPercent = 30, minutes = 30, fixedLength = true),
        ), wakeTime = 6 * 60)
        assertEquals(listOf(30, 420, 30), schedule.resolved(startingAt = 22 * 60).phases.map { it.minutes })
        assertEquals(listOf(30, 330, 30), schedule.resolved(startingAt = 23 * 60 + 30).phases.map { it.minutes })
        assertNull(schedule.wakeProblem(startingAt = 22 * 60))
    }

    @Test
    fun aNightOfOnlyFixedPhasesCantScale() {
        val schedule = Schedule("Rigid", listOf(
            Phase(Mode.COOL, minutes = 30, fixedLength = true), Phase(Mode.HEAT, temperature = 58, minutes = 30, fixedLength = true),
        ), wakeTime = 6 * 60)
        assertEquals(
            "Every phase has a fixed length, so the night can't stretch or shrink to end at ${Format.clock(360)}. Let a phase change length.",
            schedule.wakeProblem(startingAt = 22 * 60),
        )
        assertNull("an hour before, it fits exactly", schedule.wakeProblem(startingAt = 5 * 60))
        assertNotNull(schedule.onlyFixedPhasesProblem)
        assertNull(coolDrift.onlyFixedPhasesProblem)
        assertNull("without a wake time, nothing scales anyway", schedule.copy(wakeTime = null).onlyFixedPhasesProblem)
    }

    @Test
    fun newAndStarterSchedulesEndAtAWakeTime() {
        // Each wakes where its night ends from the usual bedtime, so it plans the same night it did without one.
        for (schedule in Schedule.templates + Schedule.blank()) {
            assertEquals(schedule.name, schedule.usualWakeTime, schedule.wakeTime)
            assertEquals(schedule.name, schedule.phases, schedule.resolved(startingAt = schedule.bedtime).phases)
        }
        assertEquals(listOf(6 * 60 + 15, 5 * 60 + 50, 6 * 60, 6 * 60 + 30), Schedule.templates.map { it.wakeTime })
        // Their short opening phases and closing warm-ups keep their length while the middle of the night stretches or
        // shrinks: Cool Drift started 15 minutes early.
        assertEquals(
            listOf(listOf(true, false, false, true), listOf(true, true, false, true), listOf(true, false, false), listOf(true, false, false)),
            Schedule.templates.map { schedule -> schedule.phases.map { it.fixedLength } },
        )
        assertEquals(listOf(45, 312, 93, 30), Schedule.templates[0].resolved(startingAt = 22 * 60 + 15).phases.map { it.minutes })
        assertNotEquals("each new schedule is its own", Schedule.blank().id, Schedule.blank().id)
    }

    @Test
    fun aRemoteTimeIsCountedInWholeMinutes() {
        val evening = java.time.LocalDate.now().atTime(22, 10, 40).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(6 * 60 + 30, Format.minuteOfDay(Format.nextTime(6 * 60 + 30, after = evening)))
        assertEquals("past midnight", ((8 * 60 + 20) * 60 - 40) * 1000L, Format.nextTime(6 * 60 + 30, after = evening) - evening)
        // To the nearest minute: 8 hr 19 min 20 s left, so it ends 20 seconds before 6:30.
        assertEquals(8 * 60 + 19, Format.minutes(until = 6 * 60 + 30, from = evening))
        assertEquals(20, Format.minutes(until = 22 * 60 + 30, from = evening - 30_000))
        assertEquals("the minute it is now counts as tomorrow's", 1439, Format.minutes(until = 22 * 60 + 10, from = evening))
        // Seconds left round to the nearest minute, so the time shown doesn't flicker.
        assertEquals(1 * 60 + 11, Format.endMinute(afterSeconds = 3 * 3600 + 5, from = evening))
        assertEquals(1 * 60 + 10, Format.endMinute(afterSeconds = 3 * 3600 - 25, from = evening))
        // A countdown is lengthened to end on its step.
        assertEquals("11:10 becomes 11:15", 65, Format.minutesEndingOnStep(60, step = 15, from = evening))
        assertEquals("11:00 is already on a step", 50, Format.minutesEndingOnStep(50, step = 5, from = evening))
    }

    @Test
    fun aRemoteTimeIsFoundByTheClockAcrossDaylightSaving() {
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            val newYork = java.time.ZoneId.of("America/New_York")
            // 23:00 to 6:30 is 7 hr 30 min on the clock, an hour more when it goes back and an hour less when it goes forward.
            val beforeFallBack = java.time.LocalDateTime.of(2026, 10, 31, 23, 0).atZone(newYork).toInstant().toEpochMilli()
            assertEquals(7 * 60 + 30 + 60, Format.minutes(until = 6 * 60 + 30, from = beforeFallBack))
            assertEquals(6 * 60 + 30, Format.minuteOfDay(Format.nextTime(6 * 60 + 30, after = beforeFallBack)))
            val beforeSpringForward = java.time.LocalDateTime.of(2026, 3, 7, 23, 0).atZone(newYork).toInstant().toEpochMilli()
            assertEquals(7 * 60 + 30 - 60, Format.minutes(until = 6 * 60 + 30, from = beforeSpringForward))
            // 2:30 doesn't happen that night: it moves on by the hour skipped, to 3:30, as on iOS.
            assertEquals(3 * 60 + 30, Format.minuteOfDay(Format.nextTime(2 * 60 + 30, after = beforeSpringForward)))
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test
    fun theEditorSaysHowTheNightEnds() {
        var schedule = Schedule("Night", listOf(Phase(Mode.COOL, minutes = 60), Phase(Mode.HEAT, temperature = 58, minutes = 30, fixedLength = true)))
        assertEquals("Each phase runs for its set length, so the night ends around ${Format.clock(schedule.bedtime + 90)}.", schedule.phasesCaption)
        assertNull("without a wake time, nothing scales", schedule.scalingNote(schedule.phases[0]))

        schedule = schedule.copy(wakeTime = 6 * 60)
        val bedtime = Format.clock(schedule.bedtime)
        val wake = Format.clock(360)
        assertEquals(
            "These lengths are for a night from $bedtime. Started earlier or later, the phases stretch or " +
                "shrink in proportion to end at $wake, apart from any marked Fixed length.",
            schedule.phasesCaption,
        )
        assertEquals(
            "the only phase that stretches is what the rest leave",
            "Fills the rest of the night, so it's as long as the other phases leave before $wake.", schedule.scalingNote(schedule.phases[0]),
        )
        assertEquals(
            "Stays ${Format.duration(30)} while the rest of the night stretches or shrinks to end at $wake.",
            schedule.scalingNote(schedule.phases[1]),
        )
        val two = schedule.copy(phases = schedule.phases.toMutableList().also { it.add(1, Phase(Mode.COOL, fanPercent = 20, minutes = 60)) })
        assertEquals(
            "Stretches or shrinks with the rest of the night to end at $wake. Lengthening it shortens the others that aren't fixed.",
            two.scalingNote(two.phases[0]),
        )

        // Every phase fixed: the caption and each note say why it can't scale.
        schedule = schedule.copy(phases = schedule.phases.map { it.copy(fixedLength = true) })
        val allFixed = "Every phase has a fixed length, so the night can't stretch or shrink to end at $wake. Let a phase change length."
        assertEquals(allFixed, schedule.phasesCaption)
        assertEquals(allFixed, schedule.scalingNote(schedule.phases[0]))

        val alone = Schedule("Alone", listOf(Phase(Mode.COOL, minutes = 60)), wakeTime = 6 * 60)
        assertEquals("a phase on its own is the whole night", "Fills the night, from $bedtime to $wake on a usual night.", alone.scalingNote(alone.phases[0]))
    }

    @Test
    fun theEditorSaysWhenTheUsualNightCantFit() {
        val schedule = Schedule("Night", listOf(Phase(Mode.COOL, minutes = 60), Phase(Mode.HEAT, temperature = 58, minutes = 30, fixedLength = true)))
        // Half an hour from the usual bedtime is less than the fixed half hour and 5 minutes for the other.
        assertEquals(
            "Only 30 min is left before ${Format.clock(23 * 60)}, less than these phases need. Set a later wake time.",
            schedule.copy(wakeTime = 23 * 60).phasesCaption,
        )
        val farOff = schedule.copy(bedtime = 13 * 60, wakeTime = 6 * 60)
        assertEquals(
            "${Format.clock(360)} is more than 16 hr after ${Format.clock(13 * 60)}. Set a later bedtime or an earlier wake time.",
            farOff.phasesCaption,
        )
        assertEquals(
            "still the only phase that stretches",
            "Fills the rest of the night, so it's as long as the other phases leave before ${Format.clock(360)}.",
            farOff.scalingNote(farOff.phases[0]),
        )
    }

    @Test
    fun theEditorFitsTheNightFromTheUsualBedtime() {
        // Cool Drift's 45-minute cooldown and 30-minute warm-up are fixed, so a later wake time goes to the 300 and 90 minutes
        // between them, in proportion.
        val coolDrift = Schedule.templates[0]
        assertEquals("the starters already fit their night", coolDrift, coolDrift.fitted())
        val later = coolDrift.copy(wakeTime = 7 * 60).fitted()
        assertEquals(listOf(45, 335, 100, 30), later.phases.map { it.minutes })
        assertEquals("a usual night runs what the editor shows", later.phases, later.resolved(later.bedtime).phases)

        // A phase just set keeps its length and the ones that stretch share the rest, a fixed one too.
        fun setting(index: Int, minutes: Int) =
            coolDrift.copy(phases = coolDrift.phases.toMutableList().also { it[index] = it[index].copy(minutes = minutes) })
                .fitted(holding = coolDrift.phases[index].id).phases.map { it.minutes }
        assertEquals(listOf(45, 330, 60, 30), setting(1, 330))
        assertEquals(listOf(75, 277, 83, 30), setting(0, 75))
        // As long as the others leave room: the last phase that stretches keeps its 5 minutes.
        assertEquals(listOf(45, 385, 5, 30), setting(1, 500))

        // A night the phases can't fit in is left as it was, for the caption to say why.
        val tight = coolDrift.copy(wakeTime = 23 * 60)
        assertEquals(tight, tight.fitted())
        assertNotNull(tight.usualNightProblem)
        val untimed = coolDrift.copy(wakeTime = null)
        assertEquals("without a wake time, nothing is fitted", untimed, untimed.fitted())
    }

    @Test
    fun theEditorOffersLengthsThatLeaveTheNightRoom() {
        val coolDrift = Schedule.templates[0]
        assertEquals(5..385, coolDrift.lengthChoices(coolDrift.phases[1]))
        assertEquals(5..385 / 5 * 5, coolDrift.durationSteps(coolDrift.phases[1]))
        // Warm Welcome's cool is the only phase that stretches, so it's what the rest leave, with no slider.
        val warmWelcome = Schedule.templates[1]
        assertNull(warmWelcome.lengthChoices(warmWelcome.phases[2]))
        assertNull(warmWelcome.durationSteps(warmWelcome.phases[2]))
        val heat = warmWelcome.phases[1]
        assertEquals(5..minOf(385, heat.maxMinutes), warmWelcome.lengthChoices(heat))
        // Without a wake time, any length BedJet runs.
        val untimed = coolDrift.copy(wakeTime = null)
        assertEquals(5..coolDrift.phases[1].maxMinutes / 5 * 5, untimed.durationSteps(untimed.phases[1]))
        // Less than a step to choose from: no slider.
        val narrow = Schedule("Narrow", listOf(Phase(Mode.COOL, minutes = 60, fixedLength = true), Phase(Mode.COOL, minutes = 60)), wakeTime = 22 * 60 + 42)
        assertEquals(5..7, narrow.lengthChoices(narrow.phases[0]))
        assertNull(narrow.durationSteps(narrow.phases[0]))
    }

    @Test
    fun fixedPhasesCountTowardsTheShortestNight() {
        // Half an hour fixed and at least 5 minutes for the other: 32 minutes isn't enough.
        val schedule = Schedule("Tight", listOf(Phase(Mode.COOL, minutes = 30, fixedLength = true), Phase(Mode.COOL, minutes = 60)), wakeTime = 6 * 60)
        assertEquals(
            "Only 32 min is left before ${Format.clock(360)}, less than these phases need. Set a later wake time.",
            schedule.wakeProblem(startingAt = 5 * 60 + 28),
        )
    }

    @Test
    fun fixedLengthIsLeftOutUntilSet() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse("older apps read the same", json.encodeToString(Phase.serializer(), Phase(Mode.COOL, minutes = 30)).contains("fixedLength"))
        val fixed = json.decodeFromString(Phase.serializer(), json.encodeToString(Phase.serializer(), Phase(Mode.COOL, minutes = 30, fixedLength = true)))
        assertTrue(fixed.fixedLength)
    }

    @Test
    fun theProgramsLastStepEndsAtTheWakeTime() {
        val steps = coolDrift.program.steps
        assertEquals(ProgramStep(Mode.HEAT, fanStep = 5, temperature = 54, hours = 6, minutes = 30, endsAtClockTime = true), steps.last())
        assertEquals(untimedCoolDrift.phases.dropLast(1).map { it.step }, steps.dropLast(1))
        assertEquals("no wake time, no clock steps", untimedCoolDrift.phases.map { it.step }, untimedCoolDrift.program.steps)
    }

    @Test
    fun aScheduleMatchesTheProgramItWasImportedFrom() {
        assertTrue(coolDrift.matches(coolDrift.program))
        assertTrue("however it was scaled for a night", coolDrift.matches(coolDrift.resolved(startingAt = 23 * 60 + 30).program))
        // The official app can end any step at a time of day; Lull keeps the ones before the last as lengths.
        val official = StoredProgram("Mine", listOf(
            ProgramStep(Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 0, endsAtClockTime = true),
            ProgramStep(Mode.HEAT, fanStep = 5, temperature = 60, hours = 6, minutes = 0, endsAtClockTime = true),
        ))
        assertTrue(Schedule.importing(official).matches(official))
        // A copy kept before Lull knew wake times has the same lengths and no wake time, and isn't kept again.
        assertTrue(Schedule.importing(official).copy(wakeTime = null).matches(official))
        assertFalse("a wake time makes it a different schedule", untimedCoolDrift.matches(coolDrift.program))
    }

    @Test
    fun olderSchedulesHaveNoWakeTime() {
        val json = Json { ignoreUnknownKeys = true }
        val saved = json.encodeToString(Schedule.serializer(), untimedCoolDrift)
        assertFalse("left out when not set, so older apps read the same", saved.contains("wakeTime"))
        assertNull(json.decodeFromString(Schedule.serializer(), saved).wakeTime)
        assertEquals(390, json.decodeFromString(Schedule.serializer(), json.encodeToString(Schedule.serializer(), coolDrift)).wakeTime)
    }

    @Test
    fun summaryNamesTheWakeTime() {
        assertEquals("Until ${Format.clock(390)} · 4 phases", coolDrift.summary)
        assertEquals("until ${Format.clock(390)}, 4 phases", coolDrift.spokenSummary)
        assertEquals("7 hr 45 min · 4 phases", untimedCoolDrift.summary)
    }
}
