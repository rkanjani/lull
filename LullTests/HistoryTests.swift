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

    func testStopsAtTheLastThingSeenWhenTheEndIsNoticedLater() throws {
        // Lull saw a change of air at 1 a.m. and was closed. The BedJet stopped some time after, and Lull only saw
        // that when it reopened at 5:50 a.m., past the start of phase 4.
        let tweak = HistoryEntry(date: at(150), event: .air(mode: .cool, temperature: 44, fanPercent: 70))
        let stoppedEarly = HistoryEntry(date: at(440), event: .scheduleEnded(name: schedule.name, ending: .early(minutes: 25)), noticedLater: true)
        XCTAssertEqual(phaseNumbers(try night([started, tweak, stoppedEarly]).timeline(until: at(445))), [2])

        // A change of air seen on reopening is dated then too, so it doesn't count as seeing the BedJet still running.
        let changedMeanwhile = HistoryEntry(date: at(440), event: .air(mode: .heat, temperature: 70, fanPercent: 30), noticedLater: true)
        let turnedOff = HistoryEntry(date: at(441), event: .off(timerRanOut: false), noticedLater: true)
        XCTAssertEqual(phaseNumbers(try night([started, tweak, changedMeanwhile, turnedOff]).timeline(until: at(445))), [2])

        // Closed all night: nothing says how far the schedule got.
        XCTAssertEqual(phaseNumbers(try night([started, stoppedEarly]).timeline(until: at(445))), [])
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

    func testNightsSavedBeforePhaseRowsStillLoad() throws {
        // A schedule of two phases and an off 100 minutes in, as Lull saved them before phase rows existed.
        let saved = """
        [{"id":"0C6E4C1A-5B1F-4C55-9E55-7A1C0D2F3B01","date":782778600,"room":44,"noticedLater":false,
          "event":{"scheduleStarted":{"name":"Cool Drift","phases":[
            {"id":"0C6E4C1A-5B1F-4C55-9E55-7A1C0D2F3B02","mode":4,"temperature":44,"fanPercent":60,"minutes":45},
            {"id":"0C6E4C1A-5B1F-4C55-9E55-7A1C0D2F3B03","mode":4,"temperature":44,"fanPercent":30,"minutes":300}]}}},
         {"id":"0C6E4C1A-5B1F-4C55-9E55-7A1C0D2F3B04","date":782784600,"noticedLater":false,"event":{"off":{"timerRanOut":false}}}]
        """
        let entries = try JSONDecoder().decode([HistoryEntry].self, from: Data(saved.utf8))
        let lastNight = Night(id: "saved", evening: entries[0].date, entries: entries)
        let timeline = lastNight.timeline(until: entries[0].date.addingTimeInterval(600 * 60))
        XCTAssertEqual(timeline.map(\.date), [entries[0].date, entries[0].date.addingTimeInterval(45 * 60), entries[1].date])
        XCTAssertEqual(phaseNumbers(timeline), [2])
    }

    func testScheduleFromAnotherPhoneHasNoPhases() throws {
        let elsewhere = HistoryEntry(date: bedtime, event: .scheduleStarted(name: nil, phases: nil))
        let timeline = try night([elsewhere]).timeline(until: at(445))
        XCTAssertEqual(timeline, [elsewhere])
    }
}
