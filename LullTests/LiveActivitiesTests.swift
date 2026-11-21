import ActivityKit
import BedJetKit
import XCTest
@testable import Lull

/// Two running BedJets share one Live Activity, as the system shows it. The twin of LiveNotificationsTest.kt.
@MainActor
final class LiveActivitiesTests: XCTestCase {
    private let mine = UUID()
    private let theirs = UUID()

    override func tearDown() async throws {
        for activity in Activity<BedJetActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    func testBothSidesShareOne() throws {
        try XCTSkipUnless(ActivityAuthorizationInfo().areActivitiesEnabled, "Live Activities are turned off")
        let now = Date()
        let night = ActiveRun(schedule: Schedule.templates[0], startedAt: now.addingTimeInterval(-200 * 60))
        let air = LiveStatus.air(mode: .heat, temperature: 60, fanPercent: 40, endsAt: now.addingTimeInterval(3600))
        LiveActivities().show([
            (mine, .show(.init(id: mine, status: air, name: "My side", generation: .v3))),
            (theirs, .show(.init(id: theirs, status: .night(night), name: "Other side", generation: .v3))),
        ], useFahrenheit: false, at: now)

        // The Dynamic Island shows one of an app's Live Activities, so there is one, with both sides of the bed in it, each
        // in its own mode: Heat on one, Cooling through the night on the other.
        let shown = Activity<BedJetActivityAttributes>.activities
        XCTAssertEqual(shown.count, 1)
        let sides = try XCTUnwrap(shown.first).content.state.sides
        XCTAssertEqual(sides.map(\.id), [mine, theirs])
        XCTAssertEqual(sides.map(\.name), ["My side", "Other side"])
        let modes = sides.map { side -> Mode? in
            switch side.status {
            case let .air(mode, _, _, _): mode
            case let .night(run): run.phase(at: now)?.phase.mode
            }
        }
        XCTAssertEqual(modes, [.heat, .cool])
    }
}
