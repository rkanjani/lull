import BedJetKit
import XCTest
@testable import Lull

/// History lists each phase a schedule moved on to, worked out from when it started, since Lull is
/// usually closed while the BedJet plays the schedule out.
final class HistoryTests: XCTestCase {
    private let calendar = Calendar.current
    /// 10:30 p.m., when the schedule starts.
    private lazy var bedtime = calendar.date(from: DateComponents(year: 2026, month: 10, day: 21, hour: 22, minute: 30))!
    /// Cool Drift: phases of 45, 300, 90 and 30 minutes.
    private let schedule = Schedule.templates[0]

    private func at(_ minutes: Int) -> Date { bedtime.addingTimeInterval(TimeInterval(minutes * 60)) }

    private func night(_ entries: [HistoryEntry]) throws -> Night {
        try XCTUnwrap(Night.group(entries).first)
    }

    private lazy var started = HistoryEntry(date: bedtime, event: .scheduleStarted(name: schedule.name, phases: schedule.phases), room: 44)

    private func phaseNumbers(_ timeline: [HistoryEntry]) -> [Int] {
        timeline.compactMap { entry -> Int? in
            guard case let .phaseStarted(number, _, _) = entry.event else { return nil }
            return number
        }
    }

    func testListsEveryPhaseChangeSoFar() throws {
        // Opened in the morning, ten minutes into the last phase.
        let timeline = try night([started]).timeline(until: at(445))
        XCTAssertEqual(timeline.map(\.date), [bedtime, at(45), at(345), at(435)])
        XCTAssertEqual(phaseNumbers(timeline), [2, 3, 4])
        guard case let .phaseStarted(number, count, phase) = timeline.last?.event else { return XCTFail("ends with a phase") }
        XCTAssertEqual(number, 4)
        XCTAssertEqual(count, 4)
        XCTAssertEqual(phase.mode, .heat)
    }

    func testLeavesOutPhasesStillToCome() throws {
        let timeline = try night([started]).timeline(until: at(100))
        XCTAssertEqual(phaseNumbers(timeline), [2])
    }

    func testStopsWhereTheScheduleEnded() throws {
        let ended = HistoryEntry(date: at(60), event: .scheduleEnded(name: schedule.name, ending: .byYou))
        let endedByYou = try night([started, ended]).timeline(until: at(445))
        XCTAssertEqual(phaseNumbers(endedByYou), [2])

        let off = HistoryEntry(date: at(400), event: .off(timerRanOut: false))
        let turnedOff = try night([started, off]).timeline(until: at(445))
        XCTAssertEqual(phaseNumbers(turnedOff), [2, 3])

        // A finished schedule is ended by its last phase, not by another phase change.
        let finished = HistoryEntry(date: at(schedule.totalMinutes), event: .scheduleEnded(name: schedule.name, ending: .finished))
        let timeline = try night([started, finished]).timeline(until: at(600))
        XCTAssertEqual(phaseNumbers(timeline), [2, 3, 4])
        XCTAssertEqual(timeline.last, finished)
    }

    func testAChangeOfAirLeavesTheScheduleRunning() throws {
        let tweak = HistoryEntry(date: at(100), event: .air(mode: .cool, temperature: 44, fanPercent: 70))
        let timeline = try night([started, tweak]).timeline(until: at(445))
        XCTAssertEqual(timeline.map(\.date), [bedtime, at(45), at(100), at(345), at(435)])
    }

    func testPhaseChangesAreNotSavedAndKeepTheirRows() throws {
        let tonight = try night([started])
        XCTAssertEqual(tonight.entries, [started], "only what happened is kept, so the night's curve is unchanged")
        let ids = tonight.timeline(until: at(445)).map(\.id)
        XCTAssertEqual(tonight.timeline(until: at(445)).map(\.id), ids)
        XCTAssertEqual(Set(ids).count, 4)
    }

    func testScheduleFromAnotherPhoneHasNoPhases() throws {
        let elsewhere = HistoryEntry(date: bedtime, event: .scheduleStarted(name: nil, phases: nil))
        let timeline = try night([elsewhere]).timeline(until: at(445))
        XCTAssertEqual(timeline, [elsewhere])
    }
}
