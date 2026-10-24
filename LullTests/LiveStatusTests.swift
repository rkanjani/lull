import ActivityKit
@testable import BedJetKit
import XCTest
@testable import Lull

/// What a running BedJet shows in its Live Activity, and when that is worth redrawing.
final class LiveStatusTests: XCTestCase {
    private let calendar = Calendar.current
    /// 11:30 p.m., an hour into tonight's schedule.
    private lazy var now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 21, hour: 23, minute: 30))!
    private let schedule = Schedule.templates[0]

    private func run(startedMinutesAgo minutes: Int) -> ActiveRun {
        ActiveRun(schedule: schedule, startedAt: now.addingTimeInterval(TimeInterval(-minutes * 60)))
    }

    private func status(_ mode: Mode, target: Int = 66, fanStep: Int = 7, seconds: Int = 2700, programStep: Int? = 0) -> DeviceStatus {
        DeviceStatus(
            remainingSeconds: seconds, actualTemperature: 50, targetTemperature: target, mode: mode, fanStep: fanStep,
            maxRuntimeMinutes: 720, minTemperature: 38, maxTemperature: 80, ambientTemperature: 45, lastShutdownReason: 0,
            extra: programStep.map { .init(ledEnabled: true, beepsMuted: false, programStep: $0, notification: 0) }
        )
    }

    func testANightRunningFromThisPhoneShowsTheNight() {
        let night = run(startedMinutesAgo: 60)
        // While a night runs, the BedJet reports the phase's own mode and timer; the night is what's shown.
        XCTAssertEqual(LiveStatus.of(run: night, status: status(.cool, programStep: 1), generation: .v3, at: now), .night(night))
        XCTAssertEqual(LiveStatus.of(run: night, status: nil, generation: .v3, at: now), .night(night))
    }

    func testAFinishedNightShowsNothing() {
        let ended = run(startedMinutesAgo: schedule.totalMinutes + 1)
        XCTAssertNil(LiveStatus.of(run: ended, status: status(.standby, seconds: 0), generation: .v3, at: now))
    }

    func testAirFromTheRemoteShowsUntilItsTimerRunsOut() {
        let live = LiveStatus.of(run: nil, status: status(.heat, target: 66, fanStep: 7, seconds: 2700), generation: .v3, at: now)
        XCTAssertEqual(live, .air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(2700)))
        XCTAssertEqual(live?.endsAt, now.addingTimeInterval(2700))
        XCTAssertEqual(live?.staleDate(at: now), now.addingTimeInterval(2700))
    }

    func testAReportFromEarlierCountsFromWhenItArrived() {
        // Lull keeps the last report while another phone has the BedJet or Lull is off screen.
        let reported = now.addingTimeInterval(-600)
        let live = LiveStatus.of(run: nil, status: status(.heat, seconds: 2700), reportedAt: reported, generation: .v3, at: now)
        XCTAssertEqual(live?.endsAt, reported.addingTimeInterval(2700))
        // A timer that has run out since is over.
        XCTAssertNil(LiveStatus.of(run: nil, status: status(.heat, seconds: 300), reportedAt: reported, generation: .v3, at: now))
    }

    func testOffShowsNothing() {
        XCTAssertNil(LiveStatus.of(run: nil, status: status(.standby, seconds: 0), generation: .v3, at: now))
        XCTAssertNil(LiveStatus.of(run: nil, status: nil, generation: .v3, at: now))
    }

    func testAScheduleFromAnotherPhoneIsNotShown() {
        // Its phases aren't known here, and its timer only counts down the step it's on.
        XCTAssertNil(LiveStatus.of(run: nil, status: status(.cool, programStep: 2), generation: .v3, at: now))
        // Until the second half of the report arrives, it can't be told from air started at the Remote.
        XCTAssertNil(LiveStatus.of(run: nil, status: status(.cool, programStep: nil), generation: .v3, at: now))
    }

    func testTemperatureIsLeftOutWhereItCantBeSet() {
        let cool = LiveStatus.of(run: nil, status: status(.cool), generation: .v3, at: now)
        XCTAssertEqual(cool, .air(mode: .cool, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(2700)))
        // A BedJet V2 only sets the temperature for Heat.
        let v2Cool = LiveStatus.of(run: nil, status: status(.cool), generation: .v2, at: now)
        XCTAssertEqual(v2Cool, .air(mode: .cool, temperature: nil, fanPercent: 40, endsAt: now.addingTimeInterval(2700)))
        let v2Heat = LiveStatus.of(run: nil, status: status(.heat), generation: .v2, at: now)
        XCTAssertEqual(v2Heat, .air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(2700)))
    }

    func testANightGoesStaleWhenItsPhaseEnds() throws {
        let night = run(startedMinutesAgo: 60)
        let live = LiveStatus.night(night)
        // Cool Drift's first phase is 45 minutes; an hour in, the second ends at 45 + 300.
        XCTAssertEqual(live.staleDate(at: now), night.phaseEnds[1])
        XCTAssertEqual(live.staleDate(at: night.endsAt), night.endsAt)
        XCTAssertEqual(live.endsAt, night.endsAt)
    }

    func testOnlyRealChangesAreRedrawn() {
        let air = LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now)
        // The BedJet's timer drifts against the phone's clock.
        XCTAssertFalse(LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(45)).differsEnough(from: air))
        XCTAssertTrue(LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(1800)).differsEnough(from: air))
        XCTAssertTrue(LiveStatus.air(mode: .cool, temperature: nil, fanPercent: 40, endsAt: now).differsEnough(from: air))
        XCTAssertTrue(LiveStatus.air(mode: .heat, temperature: 68, fanPercent: 40, endsAt: now).differsEnough(from: air))
        XCTAssertTrue(LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 60, endsAt: now).differsEnough(from: air))
        let night = LiveStatus.night(run(startedMinutesAgo: 60))
        XCTAssertTrue(night.differsEnough(from: air))
        XCTAssertFalse(night.differsEnough(from: night))
        XCTAssertTrue(LiveStatus.night(run(startedMinutesAgo: 0)).differsEnough(from: night))
    }

    func testNamesAndUnitsAreRedrawnToo() {
        let shown = BedJetActivityAttributes.ContentState(sides: [side(.night(run(startedMinutesAgo: 60)))], useFahrenheit: true)
        var renamed = shown
        renamed.sides[0].name = "Left"
        var celsius = shown
        celsius.useFahrenheit = false
        XCTAssertFalse(shown.differsEnough(from: shown))
        XCTAssertTrue(renamed.differsEnough(from: shown))
        XCTAssertTrue(celsius.differsEnough(from: shown))
    }

    func testSettingsAreWordedLikeTheScheduleEditor() {
        XCTAssertEqual(Format.setting(mode: .heat, temperature: 66, fanPercent: 40, fahrenheit: false), "33° · Fan 40%")
        XCTAssertEqual(Format.setting(mode: .cool, temperature: nil, fanPercent: 30, fahrenheit: true), "Fan 30%")
        XCTAssertEqual(Format.setting(mode: .wait, temperature: nil, fanPercent: 30, fahrenheit: true), "Air off")
    }

    func testALiveActivitySurvivesBeingSaved() throws {
        // ActivityKit hands the content to the widget extension as Codable data.
        for status in [LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now), .night(run(startedMinutesAgo: 60))] {
            let state = BedJetActivityAttributes.ContentState(sides: [side(status, generation: .v2)], useFahrenheit: true)
            let saved = try JSONEncoder().encode(state)
            XCTAssertEqual(try JSONDecoder().decode(BedJetActivityAttributes.ContentState.self, from: saved), state)
        }
    }

    // MARK: The Live Activity. The twins of LiveStatusTest's Live Update tests.

    private let mine = UUID(), theirs = UUID()

    private func side(_ status: LiveStatus, id: UUID? = nil, name: String = "My side",
                      generation: Generation = .v3) -> BedJetActivityAttributes.Side {
        .init(id: id ?? mine, status: status, name: name, generation: generation)
    }

    private var night: BedJetActivityAttributes.ContentState {
        .init(sides: [side(.night(run(startedMinutesAgo: 60)))], useFahrenheit: true)
    }

    private func shown(_ state: BedJetActivityAttributes.ContentState) -> ActivityContent<BedJetActivityAttributes.ContentState> {
        ActivityContent(state: state, staleDate: state.staleDate(at: now))
    }

    @MainActor
    func testALiveActivityIsOnlyRedrawnWhenSomethingChanged() {
        var renamed = night
        renamed.sides[0].name = "Left"
        XCTAssertEqual(LiveActivities.step(for: night, shown: shown(night), isUp: true, at: now), .leave)
        XCTAssertEqual(LiveActivities.step(for: renamed, shown: shown(night), isUp: true, at: now), .update)
        // At the end of the phase, to show the next one.
        let phaseEnds = night.staleDate(at: now)!
        XCTAssertEqual(LiveActivities.step(for: night, shown: shown(night), isUp: true, at: phaseEnds), .update)
        // Nothing running any more.
        XCTAssertEqual(LiveActivities.step(for: nil, shown: shown(night), isUp: true, at: now), .end)
    }

    @MainActor
    func testASwipedAwayLiveActivityStaysDownUntilThereIsSomethingNew() {
        var renamed = night
        renamed.sides[0].name = "Left"
        let phaseEnds = night.staleDate(at: now)!
        XCTAssertEqual(LiveActivities.step(for: night, shown: shown(night), isUp: false, at: phaseEnds), .leave)
        XCTAssertEqual(LiveActivities.step(for: renamed, shown: shown(night), isUp: false, at: now), .replace)
    }

    @MainActor
    func testBothSidesOfTheBedShareOneLiveActivity() throws {
        // The Dynamic Island shows only one of an app's Live Activities, so one holds both, in the order Lull lists them.
        let air = side(.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(600)),
                       id: theirs, name: "Other side")
        let both = try XCTUnwrap(LiveActivities.state(for: [(mine, .show(night.sides[0])), (theirs, .show(air))],
                                                      useFahrenheit: true, shown: nil, at: now))
        XCTAssertEqual(both.sides.map(\.id), [mine, theirs])
        // A third doesn't fit, and is left out.
        let third = side(.air(mode: .cool, temperature: 44, fanPercent: 30, endsAt: now.addingTimeInterval(600)), id: UUID())
        let three = LiveActivities.state(for: [(mine, .show(night.sides[0])), (theirs, .show(air)), (third.id, .show(third))],
                                         useFahrenheit: true, shown: nil, at: now)
        XCTAssertEqual(three?.sides.map(\.id), [mine, theirs])
        // Nothing running.
        XCTAssertNil(LiveActivities.state(for: [], useFahrenheit: true, shown: both, at: now))
        // One side stops, and the other carries on alone.
        let left = LiveActivities.state(for: [(theirs, .show(air))], useFahrenheit: true, shown: both, at: now)
        XCTAssertEqual(left?.sides.map(\.id), [theirs])
        // When it ends soonest, so does what the whole may be trusted for.
        XCTAssertEqual(both.staleDate(at: now), min(night.staleDate(at: now)!, now.addingTimeInterval(600)))
    }

    @MainActor
    func testABedJetIsKeptUntilLullHearsFromIt() {
        let air = side(.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(600)))
        let shown = BedJetActivityAttributes.ContentState(sides: [air], useFahrenheit: true)
        XCTAssertEqual(LiveActivities.state(for: [(mine, .keep)], useFahrenheit: true, shown: shown, at: now)?.sides, [air])
        // Its timer has run out since.
        XCTAssertNil(LiveActivities.state(for: [(mine, .keep)], useFahrenheit: true, shown: shown, at: now.addingTimeInterval(601)))
        // Nothing shown for it yet.
        XCTAssertNil(LiveActivities.state(for: [(mine, .keep)], useFahrenheit: true, shown: nil, at: now))
    }
}
