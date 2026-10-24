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
        AppCommands.send(.startNight(nil))
        AppCommands.send(.show(.history))
        XCTAssertTrue(received.isEmpty)
        AppCommands.handler = { received.append($0) }
        XCTAssertEqual(received, [.startNight(nil), .show(.history)])
        AppCommands.send(.show(.remote))
        XCTAssertEqual(received.last, .show(.remote))
        // Start from a widget showing one BedJet names it.
        let side = UUID()
        AppCommands.send(.startNight(side))
        XCTAssertEqual(received.last, .startNight(side))
        // The Quick Remote's buttons, too.
        AppCommands.send(.remote(RemotePreset.defaults[1], nil))
        XCTAssertEqual(received.last, .remote(RemotePreset.defaults[1], nil))
        // A Quick Remote set to one side of the bed names it.
        AppCommands.send(.remote(RemotePreset.defaults[0], side))
        XCTAssertEqual(received.last, .remote(RemotePreset.defaults[0], side))
    }

    // MARK: Each BedJet: Tonight, Both Sides and Quick Remote

    private let mine = UUID(), theirs = UUID()

    /// Two BedJets: this side partway through tonight's schedule, the other cooled from the Remote for 45 minutes.
    private func bothSides(air: LiveStatus? = nil) -> WidgetSnapshot {
        let night = ActiveRun(schedule: schedule, startedAt: now.addingTimeInterval(-3600))
        return WidgetSnapshot(tonight: schedule, run: night, bedJets: [
            WidgetBedJet(id: mine, name: "My side", live: .night(night), tonight: schedule),
            WidgetBedJet(id: theirs, name: "Other side",
                         live: air ?? .air(mode: .cool, temperature: 44, fanPercent: 40, endsAt: now.addingTimeInterval(2700)),
                         tonight: schedule),
        ], onScreen: mine)
    }

    func testAWidgetShowsItsOwnBedJet() {
        let snapshot = bothSides()
        XCTAssertEqual(snapshot.bedJet(theirs)?.id, theirs)
        // A BedJet that was forgotten since the widget was set up.
        XCTAssertNil(snapshot.bedJet(UUID()))
        // Not yet set to one, a widget shows the one on screen; with none on screen, the first.
        XCTAssertEqual(snapshot.usualBedJet?.id, mine)
        XCTAssertEqual(WidgetSnapshot(bedJets: snapshot.bedJets, onScreen: theirs).usualBedJet?.id, theirs)
        XCTAssertEqual(WidgetSnapshot(bedJets: snapshot.bedJets).usualBedJet?.id, mine)
        // With none known, none, and the widget shows the one on screen once there is one.
        XCTAssertNil(WidgetSnapshot().usualBedJet)
        XCTAssertEqual(snapshot.bedJet(nil)?.id, mine)
    }

    func testAirFromTheRemoteRunsOutByTheClock() throws {
        let other = try XCTUnwrap(bothSides().bedJet(theirs))
        XCTAssertNotNil(other.live(at: now))
        XCTAssertNil(other.live(at: now.addingTimeInterval(2701)))
    }

    func testTonightShowsTheRemotesTimer() throws {
        // Set to the other side: no night there, but air from the Remote until its timer runs out.
        let other = bothSides().showing(theirs)
        XCTAssertNil(other.run(at: now))
        guard case .air? = other.bedJet(nil)?.live(at: now) else { return XCTFail("the Remote's timer") }
        XCTAssertNil(other.bedJet(nil)?.live(at: now.addingTimeInterval(2701)))
        // Then tonight's schedule, ready to start.
        XCTAssertEqual(other.tonight, schedule)
    }

    func testRedrawsWhenEitherSideChanges() throws {
        let snapshot = bothSides()
        let dates = snapshot.redraws(from: now)
        let night = try XCTUnwrap(snapshot.run)
        XCTAssertTrue(dates.contains(now.addingTimeInterval(2700)), "the other side's timer runs out")
        XCTAssertTrue(dates.contains(night.phaseEnds[1]), "this side's next phase")
        XCTAssertEqual(dates.last, night.endsAt)
        // Air alone still redraws when it turns off.
        let airOnly = WidgetSnapshot(bedJets: [WidgetBedJet(id: theirs, name: "Other side",
                                                            live: .air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(900)))])
        XCTAssertEqual(airOnly.redraws(from: now).last, now.addingTimeInterval(900))
    }

    func testOnlyRealChangesReachTheWidgets() {
        let air = LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now)
        // The BedJet reports every second, and its timer drifts against the phone's clock.
        let drifted = LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(20))
        XCTAssertEqual(WidgetSnapshot.steadied(drifted, after: air), air)
        let warmer = LiveStatus.air(mode: .heat, temperature: 68, fanPercent: 40, endsAt: now)
        XCTAssertEqual(WidgetSnapshot.steadied(warmer, after: air), warmer)
        XCTAssertNil(WidgetSnapshot.steadied(nil, after: air))
        XCTAssertEqual(WidgetSnapshot.steadied(air, after: nil), air)
    }

    func testWidgetsKeepWhatTheyShowedWhileLullWaitsToHear() {
        let air = LiveStatus.air(mode: .heat, temperature: 66, fanPercent: 40, endsAt: now.addingTimeInterval(900))
        // Just launched, or saving a night: nothing heard yet, so what was shown stays, until its timer runs out.
        XCTAssertEqual(WidgetSnapshot.live(nil, after: air, isWaiting: true, at: now), air)
        XCTAssertNil(WidgetSnapshot.live(nil, after: air, isWaiting: true, at: now.addingTimeInterval(901)))
        // Heard that it's off.
        XCTAssertNil(WidgetSnapshot.live(nil, after: air, isWaiting: false, at: now))
        // Heard something new while waiting.
        let cool = LiveStatus.air(mode: .cool, temperature: 44, fanPercent: 40, endsAt: now.addingTimeInterval(1800))
        XCTAssertEqual(WidgetSnapshot.live(cool, after: air, isWaiting: true, at: now), cool)
    }

    func testLullWaitsUntilARunningBedJetSaysWhatItIsRunning() throws {
        // A report captured from a BedJet 3, set to Heat: its first half, then all of it.
        let half = "01 56 1b 01 00 0e 10 2e 4f 01 13 00 00 14 50 00 00 2d 00 12"
        let heating = try XCTUnwrap(Codec.parseStatus(hex(half)))
        let whole = try XCTUnwrap(Codec.parseStatus(hex(half + " 01 9a 01 10 10 00 15 35 00 01 53")))
        XCTAssertTrue(WidgetSnapshot.isWaiting(for: nil, isSavingSchedule: false))
        // Air and a schedule look the same until the second half arrives.
        XCTAssertTrue(WidgetSnapshot.isWaiting(for: heating, isSavingSchedule: false))
        XCTAssertFalse(WidgetSnapshot.isWaiting(for: whole, isSavingSchedule: false))
        XCTAssertTrue(WidgetSnapshot.isWaiting(for: whole, isSavingSchedule: true))
        // Off is off, whichever half has arrived.
        var off = heating
        off.mode = .standby
        XCTAssertFalse(WidgetSnapshot.isWaiting(for: off, isSavingSchedule: false))
    }

    private func hex(_ bytes: String) -> Data {
        Data(bytes.split(separator: " ").map { UInt8($0, radix: 16)! })
    }

    func testReadsWhatAnEarlierLullSaved() throws {
        // Saved before Lull knew more than one BedJet, with a night running: still read, not blanked.
        let earlier = running(startedMinutesAgo: 60)
        var saved = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(earlier)) as? [String: Any])
        saved["bedJets"] = nil
        saved["onScreen"] = nil
        let read = try JSONDecoder().decode(WidgetSnapshot.self, from: JSONSerialization.data(withJSONObject: saved))
        XCTAssertEqual(read.run, earlier.run)
        XCTAssertEqual(read.bedJets, [])
        // Each BedJet's history came later still: one saved without it reads with none.
        var side = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(WidgetBedJet(id: mine, name: "My side"))) as? [String: Any])
        side["recentHistory"] = nil
        XCTAssertEqual(try JSONDecoder().decode(WidgetBedJet.self, from: JSONSerialization.data(withJSONObject: side)).recentHistory, [])
    }

    func testAWidgetSetToABedJetShowsItsNight() {
        var snapshot = bothSides()
        snapshot.bedJets[1].recentHistory = WidgetSnapshot.recent(HistoryEntry.samples(before: now), before: now)
        // The other side: its own tonight and history, and not the night running on this side.
        let other = snapshot.showing(theirs)
        XCTAssertNil(other.run(at: now))
        XCTAssertEqual(other.tonight, schedule)
        XCTAssertEqual(other.recentHistory, snapshot.bedJets[1].recentHistory)
        XCTAssertEqual(other.bedJet(nil)?.id, theirs)
        // This side: its night, as when it's on screen.
        XCTAssertEqual(snapshot.showing(mine).run, snapshot.run)
        // Following the one on screen, before one is picked.
        XCTAssertEqual(snapshot.showing(nil), snapshot)
        // Set to one forgotten since: nothing to show.
        let forgotten = snapshot.showing(UUID())
        XCTAssertNil(forgotten.tonight)
        XCTAssertNil(forgotten.run)
        XCTAssertEqual(forgotten.recentHistory, [])
        XCTAssertNil(forgotten.bedJet(nil))
    }

    func testWidgetsHeadThemselvesWithTheBedJetsName() {
        // With two, a widget says which side it shows, in its heading and to VoiceOver.
        let other = bothSides().showing(theirs)
        XCTAssertEqual(other.headingName, "Other side")
        XCTAssertEqual(other.headed("Tonight"), "Other side · Tonight")
        XCTAssertEqual(other.spoken("Tonight, Cool Drift"), "Other side, Tonight, Cool Drift")
        // Tapping it opens Lull on that side.
        XCTAssertEqual(LullTab.bedJet(in: other.link(to: .schedule)), theirs)
        // With one, there's nothing to tell apart.
        let one = running(startedMinutesAgo: 60)
        XCTAssertNil(one.headingName)
        XCTAssertEqual(one.headed("Tonight"), "Tonight")
        XCTAssertEqual(one.spoken("Tonight, Cool Drift"), "Tonight, Cool Drift")
    }

    func testBothSidesShowWhatsPicked() {
        let guest = UUID()
        var snapshot = bothSides()
        snapshot.bedJets.append(WidgetBedJet(id: guest, name: "Guest bed"))
        func ids(_ sides: (left: WidgetBedJet?, right: WidgetBedJet?)) -> [UUID?] { [sides.left?.id, sides.right?.id] }
        // Not yet set: the one on screen on the left and another on the right, and set to the same one twice, the
        // right shows the other.
        XCTAssertEqual(ids(snapshot.sides(left: nil, right: nil)), [mine, theirs])
        snapshot.onScreen = theirs
        XCTAssertEqual(ids(snapshot.sides(left: nil, right: nil)), [theirs, mine])
        snapshot.onScreen = mine
        XCTAssertEqual(ids(snapshot.sides(left: mine, right: mine)), [mine, theirs])
        // As picked: either way round, or the third.
        XCTAssertEqual(ids(snapshot.sides(left: theirs, right: mine)), [theirs, mine])
        XCTAssertEqual(ids(snapshot.sides(left: guest, right: mine)), [guest, mine])
        // A side set to a BedJet forgotten since is empty.
        XCTAssertEqual(ids(snapshot.sides(left: UUID(), right: theirs)), [nil, theirs])
    }

    func testBothSidesSurviveASaveAndLoad() throws {
        let snapshot = bothSides()
        let data = try JSONEncoder().encode(snapshot)
        XCTAssertEqual(try JSONDecoder().decode(WidgetSnapshot.self, from: data), snapshot)
    }

    func testQuickRemotePresets() {
        // A new widget's buttons: the same three as before they could be edited.
        XCTAssertEqual(RemotePreset.defaults.map(\.title), ["Cool · 30 min", "Heat · 15 min", "Off"])
        XCTAssertEqual(RemotePreset.defaults[0], RemotePreset(mode: .cool, minutes: 30, temperature: 44, fanPercent: 40))
        XCTAssertEqual(RemotePreset.defaults[1], RemotePreset(mode: .heat, minutes: 15, temperature: 66, fanPercent: 40))
        XCTAssertEqual(RemotePreset.defaults[2].mode, .standby)
        // What else a button sets is read out after its title; Off sets nothing else.
        XCTAssertEqual(RemotePreset.defaults[0].setting(fahrenheit: true), "72° · Fan 40%")
        XCTAssertEqual(RemotePreset(mode: .turbo, minutes: 10, temperature: 86, fanPercent: 100).setting(fahrenheit: true), "Fan 100%")
        XCTAssertNil(RemotePreset.defaults[2].setting(fahrenheit: true))
        XCTAssertEqual(RemotePreset(mode: .extendedHeat, minutes: 90, temperature: 58, fanPercent: 20).title, "Gentle heat · 1 hr 30 min")
    }

    func testQuickRemotePresetsKeepToWhatTheBedJetTakes() {
        // Turbo runs 10 minutes at most, at its one temperature.
        let turbo = RemotePreset(mode: .turbo, minutes: 60, temperature: 50, fanPercent: 33).clamped
        XCTAssertEqual(turbo, RemotePreset(mode: .turbo, minutes: 10, temperature: 86, fanPercent: 35))
        // Hot, fast Heat stops at the BedJet's safety cut-off, as a phase does.
        let hot = RemotePreset(mode: .heat, minutes: 240, temperature: 80, fanPercent: 100).clamped
        XCTAssertEqual(hot.minutes, Phase(.heat, temperature: 80, fan: 100, minutes: 240).maxMinutes)
        XCTAssertLessThan(hot.minutes, 240)
        // Out of range either way.
        XCTAssertEqual(RemotePreset(mode: .cool, minutes: 0, temperature: 100, fanPercent: 3).clamped,
                       RemotePreset(mode: .cool, minutes: 1, temperature: 62, fanPercent: 5))
        XCTAssertEqual(RemotePreset(mode: .cool, minutes: 30, temperature: 0, fanPercent: 200).clamped,
                       RemotePreset(mode: .cool, minutes: 30, temperature: 38, fanPercent: 100))
    }

    func testEditingAQuickRemoteChoosesItsButtons() {
        // A widget not yet edited has the usual three.
        XCTAssertEqual(QuickRemoteConfiguration().choices, PresetChoice.defaults)
        XCTAssertEqual(QuickRemoteConfiguration().presets(fahrenheit: true), RemotePreset.defaults)
        // Degrees are read in the unit Lull shows, and left empty mean the mode's usual temperature.
        func cool(_ degrees: Int?) -> PresetChoice { PresetChoice(mode: .cool, minutes: 30, degrees: degrees, fanPercent: 40) }
        XCTAssertEqual(cool(72).preset(fahrenheit: true).temperature, 44)
        XCTAssertEqual(cool(22).preset(fahrenheit: false).temperature, 44)
        XCTAssertEqual(cool(nil).preset(fahrenheit: true).temperature, Mode.cool.defaultTemperature)
        // Kept to what the BedJet takes.
        XCTAssertEqual(cool(100).preset(fahrenheit: true).temperature, 62)
    }
}
