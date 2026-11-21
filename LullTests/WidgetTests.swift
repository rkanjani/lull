import BedJetKit
import XCTest
@testable import Lull

/// What the widgets work out from the snapshot the app saves: when to redraw, which night was last night,
/// and how taps and controls find their way back into the app.
final class WidgetTests: XCTestCase {
    private let calendar = Calendar.current
    /// 11:30 p.m., an hour into tonight's schedule.
    private lazy var now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 21, hour: 23, minute: 30))!
    private let schedule = Schedule.templates[0]

    private func running(startedMinutesAgo minutes: Int) -> WidgetSnapshot {
        WidgetSnapshot(tonight: schedule, run: ActiveRun(schedule: schedule, startedAt: now.addingTimeInterval(TimeInterval(-minutes * 60))))
    }

    func testRedrawsThroughTheNightAndAtEveryPhaseChange() throws {
        let snapshot = running(startedMinutesAgo: 60)
        let run = try XCTUnwrap(snapshot.run)
        let dates = snapshot.redraws(from: now)
        XCTAssertEqual(dates.first, now)
        XCTAssertEqual(dates.last, run.endsAt, "the last redraw shows the night has ended")
        for end in run.phaseEnds where end > now {
            XCTAssertTrue(dates.contains(end), "redraws when phase ending \(end) does")
        }
        XCTAssertFalse(dates.contains(run.phaseEnds[0]), "the first phase ended before now")
        for (earlier, later) in zip(dates, dates.dropFirst()) {
            XCTAssertLessThanOrEqual(later.timeIntervalSince(earlier), 300)
        }
    }

    func testDrawsOnceWhenNothingIsRunning() {
        XCTAssertEqual(WidgetSnapshot(tonight: schedule).redraws(from: now), [now])
        // A night that has already ended is the same as none.
        XCTAssertEqual(running(startedMinutesAgo: schedule.totalMinutes + 1).redraws(from: now), [now])
    }

    func testPhaseEndsAddUpToTheNight() throws {
        let run = try XCTUnwrap(running(startedMinutesAgo: 0).run)
        XCTAssertEqual(run.phaseEnds.count, schedule.phases.count)
        XCTAssertEqual(run.phaseEnds.last, run.endsAt)
        XCTAssertEqual(run.phaseEnds[0], run.startedAt.addingTimeInterval(TimeInterval(schedule.phases[0].minutes * 60)))
    }

    func testLastNightSkipsTheNightStillRunning() throws {
        var snapshot = running(startedMinutesAgo: 60)
        let started = HistoryEntry(date: now.addingTimeInterval(-3600), event: .scheduleStarted(name: schedule.name, phases: schedule.phases))
        snapshot.recentHistory = WidgetSnapshot.recent(HistoryEntry.samples(before: now) + [started], before: now)

        let lastNight = try XCTUnwrap(snapshot.lastNight(at: now))
        XCTAssertEqual(lastNight.id, "2026-10-20")
        XCTAssertEqual(lastNight.scheduleNames, ["Cool Drift"])

        // Ended early, tonight is still tonight, not last night.
        snapshot.run = nil
        XCTAssertEqual(snapshot.lastNight(at: now)?.id, "2026-10-20")
        snapshot.run = running(startedMinutesAgo: 60).run

        // Once tonight's schedule is over, it is the latest night.
        let morning = now.addingTimeInterval(TimeInterval(schedule.totalMinutes * 60))
        XCTAssertEqual(snapshot.lastNight(at: morning)?.id, "2026-10-21")
    }

    func testKeepsOnlyRecentHistory() {
        let recent = WidgetSnapshot.recent(HistoryEntry.samples(before: now), before: now)
        XCTAssertFalse(recent.isEmpty)
        XCTAssertTrue(recent.allSatisfy { now.timeIntervalSince($0.date) <= 48 * 3600 })
    }

    func testSurvivesASaveAndLoad() throws {
        var snapshot = running(startedMinutesAgo: 30)
        snapshot.recentHistory = HistoryEntry.samples(before: now)
        snapshot.generation = .v2
        let data = try JSONEncoder().encode(snapshot)
        XCTAssertEqual(try JSONDecoder().decode(WidgetSnapshot.self, from: data), snapshot)
    }

    func testPhaseDetailFollowsTheBedJet() {
        var snapshot = WidgetSnapshot(useFahrenheit: true)
        let cool = Phase(.cool, fan: 40, minutes: 60), heat = Phase(.heat, temperature: 66, fan: 30, minutes: 30)
        XCTAssertEqual(snapshot.detail(of: heat), "91° · Fan 30%")
        XCTAssertEqual(snapshot.detail(of: Phase(.wait, minutes: 30)), "Air off")
        // A V2 only sets a temperature for Heat.
        snapshot.generation = .v2
        XCTAssertEqual(snapshot.detail(of: cool), "Fan 40%")
        XCTAssertEqual(snapshot.detail(of: heat), "91° · Fan 30%")
    }

    func testWidgetLinksOpenTheirTab() {
        for tab in [LullTab.remote, .schedule, .history] {
            XCTAssertEqual(LullTab(url: tab.url), tab)
        }
        XCTAssertNil(LullTab(url: URL(string: "lull://settings")!))
        XCTAssertNil(LullTab(url: URL(string: "https://schedule")!))
        // A link can also name its BedJet.
        let bedJet = UUID()
        let link = LullTab.schedule.url(showing: bedJet)
        XCTAssertEqual(LullTab(url: link), .schedule)
        XCTAssertEqual(LullTab.bedJet(in: link), bedJet)
        XCTAssertNil(LullTab.bedJet(in: LullTab.remote.url))
    }

    @MainActor
    func testCommandsWaitForTheApp() {
        let previous = AppCommands.handler
        defer { AppCommands.handler = previous }
        AppCommands.handler = nil
        var received: [AppCommands.Command] = []
        AppCommands.send(.startNight)
        AppCommands.send(.show(.history))
        XCTAssertTrue(received.isEmpty)
        AppCommands.handler = { received.append($0) }
        XCTAssertEqual(received, [.startNight, .show(.history)])
        AppCommands.send(.show(.remote))
        XCTAssertEqual(received.last, .show(.remote))
    }
}
