import AppIntents
import BedJetKit
import XCTest
@testable import Lull

/// What Shortcuts actions send and say, kept to what the BedJet allows as the Remote keeps it.
final class AutomationTests: XCTestCase {
    private func status(_ mode: Mode, min: Int = 38, max: Int = 80, maxRuntime: Int = 0) -> DeviceStatus {
        DeviceStatus(remainingSeconds: 0, actualTemperature: 50, targetTemperature: 60, mode: mode, fanStep: 7,
                     maxRuntimeMinutes: maxRuntime, minTemperature: min, maxTemperature: max, ambientTemperature: 45,
                     lastShutdownReason: 0, extra: nil)
    }

    func testTemperatureIsInThePersonsUnitAndKeptToTheLimits() {
        XCTAssertEqual(Automation.halfCelsius(degrees: 86, fahrenheit: true, mode: .heat, status: nil), 60)
        XCTAssertEqual(Automation.halfCelsius(degrees: 30, fahrenheit: false, mode: .heat, status: nil), 60)
        // The mode's own range, until the BedJet reports the range for the mode it is in.
        XCTAssertEqual(Automation.halfCelsius(degrees: 120, fahrenheit: true, mode: .heat, status: nil), 80)
        XCTAssertEqual(Automation.halfCelsius(degrees: 50, fahrenheit: true, mode: .heat, status: nil), 45)
        XCTAssertEqual(Automation.halfCelsius(degrees: 100, fahrenheit: true, mode: .heat, status: status(.heat, max: 70)), 70)
        XCTAssertEqual(Automation.halfCelsius(degrees: 100, fahrenheit: true, mode: .heat, status: status(.cool, max: 62)), 76)
        // Any number a shortcut passes is kept to the range, however far out.
        XCTAssertEqual(Automation.halfCelsius(degrees: .max, fahrenheit: true, mode: .heat, status: nil), 80)
        XCTAssertEqual(Automation.halfCelsius(degrees: .min, fahrenheit: true, mode: .heat, status: nil), 45)
    }

    func testATemperatureIsRefusedWhereTheBedJetTakesNone() {
        XCTAssertNil(Automation.temperatureRefusal(mode: .cool, generation: .v3))
        XCTAssertNil(Automation.temperatureRefusal(mode: .heat, generation: .v2))
        XCTAssertEqual(Automation.temperatureRefusal(mode: .turbo, generation: .v3), .fixedTemperature(.turbo))
        // A V2 only takes one for Heat, though Cool has one on a BedJet 3.
        XCTAssertEqual(Automation.temperatureRefusal(mode: .cool, generation: .v2), .heatOnlyTemperature)
        XCTAssertEqual(Automation.temperatureRefusal(mode: .turbo, generation: .v2), .fixedTemperature(.turbo))
    }

    func testFanIsRoundedToStepsOfFive() {
        XCTAssertEqual(Automation.fanPercent(42), 40)
        XCTAssertEqual(Automation.fanPercent(43), 45)
        XCTAssertEqual(Automation.fanPercent(0), 5)
        XCTAssertEqual(Automation.fanPercent(150), 100)
    }

    func testRunTimeIsKeptToWhatTheBedJetAllows() {
        XCTAssertEqual(Automation.runTimeMinutes(30, mode: .heat, status: nil), 30)
        XCTAssertEqual(Automation.runTimeMinutes(0, mode: .heat, status: nil), 1)
        XCTAssertEqual(Automation.runTimeMinutes(600, mode: .heat, status: nil), Mode.heat.maxMinutes)
        XCTAssertEqual(Automation.runTimeMinutes(600, mode: .turbo, status: nil), 10)
        XCTAssertEqual(Automation.runTimeMinutes(600, mode: .cool, status: status(.cool, maxRuntime: 120)), 120)
    }

    func testModesAreNamedAsOnTheRemote() {
        for mode in AirMode.allCases {
            XCTAssertEqual(String(localized: AirMode.caseDisplayRepresentations[mode]!.title), mode.mode.title)
        }
        for mode in Mode.allCases {
            XCTAssertEqual(String(localized: BedJetMode.caseDisplayRepresentations[BedJetMode(mode)]!.title), mode.title)
        }
        XCTAssertEqual(AirMode.allCases.map(\.mode), Mode.remoteModes)
        XCTAssertEqual(AirMode.allCases.map(\.rawValue), ["cool", "heat", "gentle_heat", "turbo", "dry"])
    }

    func testModesHaveTheRemotesIcons() {
        for mode in AirMode.allCases {
            XCTAssertEqual(symbol(of: AirMode.caseDisplayRepresentations[mode]!), mode.mode.symbol)
        }
        for mode in Mode.allCases {
            XCTAssertEqual(symbol(of: BedJetMode.caseDisplayRepresentations[BedJetMode(mode)]!), mode.symbol)
        }
    }

    /// The SF Symbol a mode is shown with in Shortcuts. AppIntents doesn't expose it, so this reads it the way `dump`
    /// shows it, and says so if a newer SDK stores it differently.
    private func symbol(of representation: DisplayRepresentation) -> String? {
        guard let image = representation.image else { return nil }
        let name = Mirror(reflecting: image).descendant("storage", "symbol", "systemName") as? String
        if name == nil { XCTFail("AppIntents no longer stores a symbol image the way this test reads it") }
        return name
    }

    func testActionsSayWhatTheyDid() {
        let heat = AppliedAir(mode: .heat, temperature: 60, fanPercent: 40, minutes: 90)
        XCTAssertEqual(Automation.airDone(heat, asked: AirSettings(mode: .heat, degrees: 86), on: "My side", fahrenheit: true),
                       "Turned on Heat on My side: 86°, fan 40%, turns off in 1 hr 30 min.")
        XCTAssertEqual(Automation.airDone(AppliedAir(mode: .cool), asked: AirSettings(mode: .cool), on: "My side", fahrenheit: true),
                       "Turned on Cool on My side.")
        XCTAssertEqual(Automation.airDone(AppliedAir(mode: .heat, temperature: 60), asked: AirSettings(degrees: 86), on: "My side", fahrenheit: true),
                       "Set My side to 86°.")
        XCTAssertEqual(Automation.airDone(AppliedAir(mode: .heat, minutes: 90), asked: AirSettings(minutes: 90), on: "My side", fahrenheit: true),
                       "My side turns off in 1 hr 30 min.")
    }

    // MARK: Status

    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func reading(_ mode: Mode, step: Int = 0, remaining: Int, minutesAgo: Int) -> AirReading {
        AirReading(mode: mode, temperature: 60, fanPercent: 40, programStep: step, remainingSeconds: remaining,
                   at: now.addingTimeInterval(TimeInterval(-minutesAgo * 60)))
    }

    func testStatusFollowsTheNightFromWhenItStarted() {
        let schedule = Schedule.templates[1]
        // Warm Welcome: 10 minutes of Turbo, then Heat at 68 for 30 minutes.
        let run = ActiveRun(schedule: schedule, startedAt: now.addingTimeInterval(-15 * 60))
        let report = BedJetReport(run: run, reading: reading(.turbo, step: 1, remaining: 600, minutesAgo: 15), generation: .v3, now: now)
        XCTAssertTrue(report.nightRunning)
        XCTAssertEqual(report.schedule, "Warm Welcome")
        XCTAssertEqual(report.nightEnds, run.endsAt)
        XCTAssertEqual(report.mode, .heat)
        XCTAssertEqual(report.temperature, 68)
        XCTAssertEqual(report.summary(of: "My side", fahrenheit: true), "“Warm Welcome” is running on My side.")
        let ended = BedJetReport(run: run, reading: nil, generation: .v3, now: run.endsAt.addingTimeInterval(60))
        XCTAssertFalse(ended.nightRunning)
    }

    func testStatusSaysOffOnceTheTimerRanOut() {
        let running = BedJetReport(run: nil, reading: reading(.heat, remaining: 3600, minutesAgo: 30), generation: .v3, now: now)
        XCTAssertEqual(running.mode, .heat)
        XCTAssertEqual(running.summary(of: "My side", fahrenheit: true), "My side is on: Heat, 86°, fan 40%.")
        let ranOut = BedJetReport(run: nil, reading: reading(.heat, remaining: 3600, minutesAgo: 90), generation: .v3, now: now)
        XCTAssertEqual(ranOut.mode, .standby)
        XCTAssertEqual(ranOut.summary(of: "My side", fahrenheit: true), "My side is off.")
    }

    func testStatusUsesWhatWasLastSeen() {
        let elsewhere = BedJetReport(run: nil, reading: reading(.cool, step: 2, remaining: 1200, minutesAgo: 5), generation: .v3, now: now)
        XCTAssertTrue(elsewhere.nightRunning)
        XCTAssertNil(elsewhere.schedule)
        XCTAssertEqual(elsewhere.summary(of: "My side", fahrenheit: true), "A schedule from another phone is running on My side.")
        XCTAssertEqual(elsewhere.lastSeen, now.addingTimeInterval(-300))
        let never = BedJetReport(run: nil, reading: nil, generation: .v3, now: now)
        XCTAssertEqual(never.summary(of: "My side", fahrenheit: true), "Lull hasn't reached My side yet.")
        // A V2 only takes a temperature for Heat.
        let cool = BedJetReport(run: nil, reading: reading(.cool, remaining: 300, minutesAgo: 1), generation: .v2, now: now)
        XCTAssertNil(cool.temperature)
        XCTAssertEqual(cool.fanPercent, 40)
        XCTAssertEqual(cool.summary(of: "My side", fahrenheit: true), "My side is on: Cool, fan 40%.")
    }
}
