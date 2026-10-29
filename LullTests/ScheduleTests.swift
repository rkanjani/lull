import BedJetKit
import XCTest
@testable import Lull

/// Schedules that end at a wake time: how the phases stretch or shrink to reach it, when they can't, and what's saved
/// to the BedJet.
final class ScheduleTests: XCTestCase {
    /// Cool Drift (45, 300 and 90 minutes, then 30 of Heat) ending at 6:30, with every phase free to stretch or shrink.
    private var coolDrift: Schedule {
        var schedule = Schedule.templates[0]
        schedule.wakeTime = 6 * 60 + 30
        for index in schedule.phases.indices { schedule.phases[index].fixedLength = nil }
        return schedule
    }

    /// Cool Drift without a wake time, running for its set lengths.
    private var untimedCoolDrift: Schedule {
        var schedule = Schedule.templates[0]
        schedule.wakeTime = nil
        return schedule
    }

    func testTheNightScalesToEndAtTheWakeTime() {
        // From the usual 10:30 bedtime, 7 hr 45 min of phases stretch to the 8 hours before 6:30.
        let fromBedtime = coolDrift.resolved(startingAt: 22 * 60 + 30)
        XCTAssertEqual(fromBedtime.phases.map(\.minutes), [46, 310, 93, 31])
        XCTAssertEqual(fromBedtime.totalMinutes, 480)
        // An hour late, they shrink in proportion to the 7 hours left.
        XCTAssertEqual(coolDrift.resolved(startingAt: 23 * 60 + 30).phases.map(\.minutes), [41, 271, 81, 27])
        XCTAssertNil(coolDrift.wakeProblem(startingAt: 22 * 60 + 30))
        XCTAssertNil(coolDrift.wakeProblem(startingAt: 23 * 60 + 30))
        XCTAssertEqual(untimedCoolDrift.resolved(startingAt: 23 * 60), untimedCoolDrift, "no wake time, no scaling")
    }

    func testANightTooFarFromItsWakeTimeIsShownAsSet() {
        // At 9 a.m. the 6:30 wake time is 21 hr 30 min away, more than Lull stretches a night to.
        XCTAssertEqual(coolDrift.resolved(startingAt: 9 * 60), coolDrift, "not stretched to most of a day")
        XCTAssertNotEqual(coolDrift.resolved(startingAt: 14 * 60 + 30), coolDrift, "16 hours away is still scaled")
    }

    func testWakeTimeWrapsPastMidnight() {
        let schedule = Schedule(name: "Short", phases: [Phase(.cool, minutes: 60)], wakeTime: 10)
        XCTAssertEqual(schedule.minutesToWake(startingAt: 23 * 60 + 50), 20)
        XCTAssertEqual(schedule.resolved(startingAt: 23 * 60 + 50).phases.map(\.minutes), [20])
    }

    func testPhasesAtALimitStayThereAndTheRestShare() {
        // Turbo runs 10 minutes at most, so doubling the night leaves it at 10 and gives the Cool the rest.
        let turbo = Schedule(name: "Warm", phases: [Phase(.turbo, fan: 100, minutes: 10), Phase(.cool, minutes: 100)], wakeTime: 2 * 60 + 10)
        XCTAssertEqual(turbo.resolved(startingAt: 22 * 60 + 30).phases.map(\.minutes), [10, 210])
        // Halving it would leave a phase under BedJet's 5 minutes.
        let short = Schedule(name: "Brief", phases: [Phase(.cool, minutes: 5), Phase(.cool, minutes: 100)], wakeTime: 23 * 60 + 25)
        XCTAssertEqual(short.resolved(startingAt: 22 * 60 + 30).phases.map(\.minutes), [5, 50])
    }

    func testAPhaseHeldUpDoesntPushAnotherPastItsLimit() {
        // A 1-minute Cool and a 10-minute Turbo in 14 minutes: the Cool is raised to 5, which leaves Turbo 9, within its 10.
        XCTAssertEqual(Schedule.scale([1, 10], within: [5...719, 5...10], to: 14), [5, 9])
    }

    func testRoundingTiesGoToTheEarlierPhase() {
        // So the iPhone and Android scale a night to the same minutes.
        XCTAssertEqual(Schedule.scale([1, 1, 1], within: [1...100, 1...100, 1...100], to: 4), [2, 1, 1])
    }

    func testANightTooFarFromItsWakeTimeIsAProblem() {
        let schedule = Schedule(name: "Long", phases: [Phase(.cool, minutes: 60)], wakeTime: 6 * 60 + 30)
        XCTAssertEqual(schedule.wakeProblem(startingAt: 13 * 60), "\(Format.clock(390)) is more than 16 hr away. Start the night nearer to it.")
    }

    func testANightTooShortForItsPhasesIsAProblem() {
        XCTAssertEqual(coolDrift.wakeProblem(startingAt: 6 * 60 + 15),
                       "Only 15 min is left before \(Format.clock(390)), less than these phases need. Set a later wake time.")
        let one = Schedule(name: "One", phases: [Phase(.cool, minutes: 60)], wakeTime: 6 * 60 + 30)
        XCTAssertEqual(one.wakeProblem(startingAt: 6 * 60 + 28),
                       "Only 2 min is left before \(Format.clock(390)), less than a phase needs. Set a later wake time.")
    }

    func testANightLongerThanItsPhasesCanRunIsAProblem() {
        let turbo = Schedule(name: "Burst", phases: [Phase(.turbo, fan: 100, minutes: 10)], wakeTime: 23 * 60 + 30)
        XCTAssertEqual(turbo.wakeProblem(startingAt: 22 * 60 + 30),
                       "To end at \(Format.clock(1410)), the night would run 1 hr, but these phases can run for up to 10 min in all. "
                       + "Add a phase or set an earlier wake time.")
        XCTAssertEqual(turbo.resolved(startingAt: 22 * 60 + 30).phases.map(\.minutes), [10])
    }

    func testFixedPhasesKeepTheirLength() {
        // A quick cooldown and a warm-up of half an hour each, and the night between them fitted to 6:00.
        let schedule = Schedule(name: "Fixed ends", phases: [
            Phase(.cool, fan: 100, minutes: 30, fixedLength: true),
            Phase(.cool, fan: 30, minutes: 300),
            Phase(.heat, temperature: 58, fan: 30, minutes: 30, fixedLength: true),
        ], wakeTime: 6 * 60)
        XCTAssertEqual(schedule.resolved(startingAt: 22 * 60).phases.map(\.minutes), [30, 420, 30])
        XCTAssertEqual(schedule.resolved(startingAt: 23 * 60 + 30).phases.map(\.minutes), [30, 330, 30])
        XCTAssertNil(schedule.wakeProblem(startingAt: 22 * 60))
    }

    func testANightOfOnlyFixedPhasesCantScale() {
        let schedule = Schedule(name: "Rigid", phases: [
            Phase(.cool, minutes: 30, fixedLength: true), Phase(.heat, temperature: 58, minutes: 30, fixedLength: true),
        ], wakeTime: 6 * 60)
        XCTAssertEqual(schedule.wakeProblem(startingAt: 22 * 60),
                       "Every phase has a fixed length, so the night can't stretch or shrink to end at \(Format.clock(360)). Let a phase change length.")
        XCTAssertNil(schedule.wakeProblem(startingAt: 5 * 60), "an hour before, it fits exactly")
        XCTAssertNotNil(schedule.onlyFixedPhasesProblem)
        XCTAssertNil(coolDrift.onlyFixedPhasesProblem)
        var noWakeTime = schedule
        noWakeTime.wakeTime = nil
        XCTAssertNil(noWakeTime.onlyFixedPhasesProblem, "without a wake time, nothing scales anyway")
    }

    func testNewAndStarterSchedulesEndAtAWakeTime() {
        // Each wakes where its night ends from the usual bedtime, so it plans the same night it did without one.
        for schedule in Schedule.templates + [Schedule.blank()] {
            XCTAssertEqual(schedule.wakeTime, schedule.usualWakeTime, schedule.name)
            XCTAssertEqual(schedule.resolved(startingAt: schedule.bedtime).phases, schedule.phases, schedule.name)
        }
        XCTAssertEqual(Schedule.templates.map(\.wakeTime), [6 * 60 + 15, 5 * 60 + 50, 6 * 60, 6 * 60 + 30])
        // Their short opening phases and closing warm-ups keep their length while the middle of the night stretches or
        // shrinks: Cool Drift started 15 minutes early.
        XCTAssertEqual(Schedule.templates.map { $0.phases.map(\.hasFixedLength) },
                       [[true, false, false, true], [true, true, false, true], [true, false, false], [true, false, false]])
        XCTAssertEqual(Schedule.templates[0].resolved(startingAt: 22 * 60 + 15).phases.map(\.minutes), [45, 312, 93, 30])
        XCTAssertNotEqual(Schedule.blank().id, Schedule.blank().id, "each new schedule is its own")
    }

    func testARemoteTimeIsCountedInWholeMinutes() {
        let evening = Calendar.current.date(bySettingHour: 22, minute: 10, second: 40, of: Date())!
        XCTAssertEqual(Format.minuteOfDay(Format.date(atNext: 6 * 60 + 30, after: evening)), 6 * 60 + 30)
        XCTAssertEqual(Format.date(atNext: 6 * 60 + 30, after: evening).timeIntervalSince(evening), (8 * 60 + 20) * 60 - 40, "past midnight")
        // To the nearest minute: 8 hr 19 min 20 s left, so it ends 20 seconds before 6:30.
        XCTAssertEqual(Format.minutes(until: 6 * 60 + 30, from: evening), 8 * 60 + 19)
        XCTAssertEqual(Format.minutes(until: 22 * 60 + 30, from: evening.addingTimeInterval(-30)), 20)
        XCTAssertEqual(Format.minutes(until: 22 * 60 + 10, from: evening), 1439, "the minute it is now counts as tomorrow's")
        // Seconds left round to the nearest minute, so the time shown doesn't flicker.
        XCTAssertEqual(Format.endMinute(after: 3 * 3600 + 5, from: evening), 1 * 60 + 11)
        XCTAssertEqual(Format.endMinute(after: 3 * 3600 - 25, from: evening), 1 * 60 + 10)
        // A countdown is lengthened to end on its step.
        XCTAssertEqual(Format.minutesEndingOnStep(60, step: 15, from: evening), 65, "11:10 becomes 11:15")
        XCTAssertEqual(Format.minutesEndingOnStep(50, step: 5, from: evening), 50, "11:00 is already on a step")
    }

    func testARemoteTimeIsFoundByTheClockAcrossDaylightSaving() throws {
        let newYork = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let zone = NSTimeZone.default
        NSTimeZone.default = newYork
        defer { NSTimeZone.default = zone }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = newYork
        // 23:00 to 6:30 is 7 hr 30 min on the clock, an hour more when it goes back and an hour less when it goes forward.
        let beforeFallBack = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 31, hour: 23)))
        XCTAssertEqual(Format.minutes(until: 6 * 60 + 30, from: beforeFallBack), 7 * 60 + 30 + 60)
        XCTAssertEqual(Format.minuteOfDay(Format.date(atNext: 6 * 60 + 30, after: beforeFallBack)), 6 * 60 + 30)
        let beforeSpringForward = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 3, day: 7, hour: 23)))
        XCTAssertEqual(Format.minutes(until: 6 * 60 + 30, from: beforeSpringForward), 7 * 60 + 30 - 60)
        // 2:30 doesn't happen that night: it moves on by the hour skipped, to 3:30, as on Android.
        XCTAssertEqual(Format.minuteOfDay(Format.date(atNext: 2 * 60 + 30, after: beforeSpringForward)), 3 * 60 + 30)
    }

    func testTheEditorSaysHowTheNightEnds() {
        var schedule = Schedule(name: "Night", phases: [Phase(.cool, minutes: 60), Phase(.heat, temperature: 58, minutes: 30, fixedLength: true)])
        XCTAssertEqual(schedule.phasesCaption, "Each phase runs for its set length, so the night ends around \(Format.clock(schedule.bedtime + 90)).")
        XCTAssertNil(schedule.scalingNote(of: schedule.phases[0]), "without a wake time, nothing scales")

        schedule.wakeTime = 6 * 60
        let (bedtime, wake) = (Format.clock(schedule.bedtime), Format.clock(360))
        XCTAssertEqual(schedule.phasesCaption, "These lengths are for a night from \(bedtime). Started earlier or later, the phases stretch or "
            + "shrink in proportion to end at \(wake), apart from any marked Fixed length.")
        XCTAssertEqual(schedule.scalingNote(of: schedule.phases[0]), "Fills the rest of the night, so it's as long as the other phases leave before \(wake).",
                       "the only phase that stretches is what the rest leave")
        XCTAssertEqual(schedule.scalingNote(of: schedule.phases[1]),
                       "Stays \(Format.duration(30)) while the rest of the night stretches or shrinks to end at \(wake).")
        var two = schedule
        two.phases.insert(Phase(.cool, fan: 20, minutes: 60), at: 1)
        XCTAssertEqual(two.scalingNote(of: two.phases[0]),
                       "Stretches or shrinks with the rest of the night to end at \(wake). Lengthening it shortens the others that aren't fixed.")

        // Every phase fixed: the caption and each note say why it can't scale.
        schedule.phases[0].fixedLength = true
        let allFixed = "Every phase has a fixed length, so the night can't stretch or shrink to end at \(wake). Let a phase change length."
        XCTAssertEqual(schedule.phasesCaption, allFixed)
        XCTAssertEqual(schedule.scalingNote(of: schedule.phases[0]), allFixed)

        let alone = Schedule(name: "Alone", phases: [Phase(.cool, minutes: 60)], wakeTime: 6 * 60)
        XCTAssertEqual(alone.scalingNote(of: alone.phases[0]), "Fills the night, from \(bedtime) to \(wake) on a usual night.",
                       "a phase on its own is the whole night")
    }

    func testTheEditorSaysWhenTheUsualNightCantFit() {
        var schedule = Schedule(name: "Night", phases: [Phase(.cool, minutes: 60), Phase(.heat, temperature: 58, minutes: 30, fixedLength: true)])
        // Half an hour from the usual bedtime is less than the fixed half hour and 5 minutes for the other.
        schedule.wakeTime = 23 * 60
        XCTAssertEqual(schedule.phasesCaption, "Only 30 min is left before \(Format.clock(23 * 60)), less than these phases need. Set a later wake time.")
        schedule.bedtime = 13 * 60
        schedule.wakeTime = 6 * 60
        XCTAssertEqual(schedule.phasesCaption, "\(Format.clock(360)) is more than 16 hr after \(Format.clock(13 * 60)). "
            + "Set a later bedtime or an earlier wake time.")
        XCTAssertEqual(schedule.scalingNote(of: schedule.phases[0]),
                       "Fills the rest of the night, so it's as long as the other phases leave before \(Format.clock(360)).",
                       "still the only phase that stretches")
    }

    func testTheEditorFitsTheNightFromTheUsualBedtime() {
        // Cool Drift's 45-minute cooldown and 30-minute warm-up are fixed, so a later wake time goes to the 300 and 90 minutes
        // between them, in proportion.
        var schedule = Schedule.templates[0]
        XCTAssertEqual(schedule.fitted(), schedule, "the starters already fit their night")
        schedule.wakeTime = 7 * 60
        let later = schedule.fitted()
        XCTAssertEqual(later.phases.map(\.minutes), [45, 335, 100, 30])
        XCTAssertEqual(later.resolved(startingAt: later.bedtime).phases, later.phases, "a usual night runs what the editor shows")

        // A phase just set keeps its length and the ones that stretch share the rest, a fixed one too.
        var longer = Schedule.templates[0]
        longer.phases[1].minutes = 330
        XCTAssertEqual(longer.fitted(holding: longer.phases[1].id).phases.map(\.minutes), [45, 330, 60, 30])
        longer = Schedule.templates[0]
        longer.phases[0].minutes = 75
        XCTAssertEqual(longer.fitted(holding: longer.phases[0].id).phases.map(\.minutes), [75, 277, 83, 30])
        // As long as the others leave room: the last phase that stretches keeps its 5 minutes.
        longer = Schedule.templates[0]
        longer.phases[1].minutes = 500
        XCTAssertEqual(longer.fitted(holding: longer.phases[1].id).phases.map(\.minutes), [45, 385, 5, 30])

        // A night the phases can't fit in is left as it was, for the caption to say why.
        var tight = Schedule.templates[0]
        tight.wakeTime = 23 * 60
        XCTAssertEqual(tight.fitted(), tight)
        XCTAssertNotNil(tight.usualNightProblem)
        var untimed = Schedule.templates[0]
        untimed.wakeTime = nil
        XCTAssertEqual(untimed.fitted(), untimed, "without a wake time, nothing is fitted")
    }

    func testTheEditorOffersLengthsThatLeaveTheNightRoom() {
        let coolDrift = Schedule.templates[0]
        XCTAssertEqual(coolDrift.lengthChoices(of: coolDrift.phases[1]), 5...385)
        XCTAssertEqual(coolDrift.durationSteps(of: coolDrift.phases[1]), 5...385 / 5 * 5)
        // Warm Welcome's cool is the only phase that stretches, so it's what the rest leave, with no slider.
        let warmWelcome = Schedule.templates[1]
        XCTAssertNil(warmWelcome.lengthChoices(of: warmWelcome.phases[2]))
        XCTAssertNil(warmWelcome.durationSteps(of: warmWelcome.phases[2]))
        let heat = warmWelcome.phases[1]
        XCTAssertEqual(warmWelcome.lengthChoices(of: heat), 5...min(385, heat.maxMinutes))
        // Without a wake time, any length BedJet runs.
        var untimed = coolDrift
        untimed.wakeTime = nil
        XCTAssertEqual(untimed.durationSteps(of: untimed.phases[1]), 5...coolDrift.phases[1].maxMinutes / 5 * 5)
        // Less than a step to choose from: no slider.
        let narrow = Schedule(name: "Narrow", phases: [Phase(.cool, minutes: 60, fixedLength: true), Phase(.cool, minutes: 60)],
                              wakeTime: 22 * 60 + 42)
        XCTAssertEqual(narrow.lengthChoices(of: narrow.phases[0]), 5...7)
        XCTAssertNil(narrow.durationSteps(of: narrow.phases[0]))
    }

    func testFixedPhasesCountTowardsTheShortestNight() {
        // Half an hour fixed and at least 5 minutes for the other: 32 minutes isn't enough.
        let schedule = Schedule(name: "Tight", phases: [Phase(.cool, minutes: 30, fixedLength: true), Phase(.cool, minutes: 60)], wakeTime: 6 * 60)
        XCTAssertEqual(schedule.wakeProblem(startingAt: 5 * 60 + 28),
                       "Only 32 min is left before \(Format.clock(360)), less than these phases need. Set a later wake time.")
    }

    func testFixedLengthIsLeftOutUntilSet() throws {
        let saved = try JSONEncoder().encode(Phase(.cool, minutes: 30))
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("fixedLength"), "older apps read the same")
        let fixed = try JSONDecoder().decode(Phase.self, from: JSONEncoder().encode(Phase(.cool, minutes: 30, fixedLength: true)))
        XCTAssertTrue(fixed.hasFixedLength)
    }

    func testTheProgramsLastStepEndsAtTheWakeTime() {
        let steps = coolDrift.program.steps
        XCTAssertEqual(steps.last, ProgramStep(mode: .heat, fanStep: 5, temperature: 54, hours: 6, minutes: 30, endsAtClockTime: true))
        XCTAssertEqual(Array(steps.dropLast()), untimedCoolDrift.phases.dropLast().map(\.step))
        XCTAssertEqual(untimedCoolDrift.program.steps, untimedCoolDrift.phases.map(\.step), "no wake time, no clock steps")
    }

    func testClockTimeStepsImportAsDurationsFromBedtime() {
        // Ends at 6:30, starting from the usual 22:30 bedtime: eight hours.
        let program = StoredProgram(name: "", steps: [ProgramStep(mode: .cool, fanStep: 5, temperature: 44, hours: 6, minutes: 30, endsAtClockTime: true)])
        let schedule = Schedule(importing: program)
        XCTAssertEqual(schedule.name, "From BedJet")
        XCTAssertEqual(schedule.phases.first?.minutes, 480)
        XCTAssertEqual(schedule.phases.first?.fanPercent, 30)
        XCTAssertEqual(schedule.wakeTime, 390, "a last step that ends at a time of day is a wake time")
    }

    func testAScheduleMatchesTheProgramItWasImportedFrom() {
        XCTAssertTrue(coolDrift.matches(coolDrift.program))
        XCTAssertTrue(coolDrift.matches(coolDrift.resolved(startingAt: 23 * 60 + 30).program), "however it was scaled for a night")
        // The official app can end any step at a time of day; Lull keeps the ones before the last as lengths.
        let official = StoredProgram(name: "Mine", steps: [
            ProgramStep(mode: .cool, fanStep: 9, temperature: 44, hours: 1, minutes: 0, endsAtClockTime: true),
            ProgramStep(mode: .heat, fanStep: 5, temperature: 60, hours: 6, minutes: 0, endsAtClockTime: true),
        ])
        XCTAssertTrue(Schedule(importing: official).matches(official))
        // A copy kept before Lull knew wake times has the same lengths and no wake time, and isn't kept again.
        var keptBefore = Schedule(importing: official)
        keptBefore.wakeTime = nil
        XCTAssertTrue(keptBefore.matches(official))
        XCTAssertFalse(untimedCoolDrift.matches(coolDrift.program), "a wake time makes it a different schedule")
    }

    func testOlderSchedulesHaveNoWakeTime() throws {
        let saved = try JSONEncoder().encode(untimedCoolDrift)
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("wakeTime"), "left out when not set, so older apps read the same")
        XCTAssertNil(try JSONDecoder().decode(Schedule.self, from: saved).wakeTime)
        XCTAssertEqual(try JSONDecoder().decode(Schedule.self, from: JSONEncoder().encode(coolDrift)).wakeTime, 390)
    }

    func testSummaryNamesTheWakeTime() {
        XCTAssertEqual(coolDrift.summary, "Until \(Format.clock(390)) · 4 phases")
        XCTAssertEqual(coolDrift.spokenSummary, "until \(Format.clock(390)), 4 phases")
        XCTAssertEqual(untimedCoolDrift.summary, "7 hr 45 min · 4 phases")
    }
}
