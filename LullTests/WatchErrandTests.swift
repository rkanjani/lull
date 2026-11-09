import BedJetKit
import XCTest
@testable import Lull

/// Errands from the watch, which reach the BedJet through Lull on the phone. Off screen, Lull connects for each one and
/// lets go again, so another phone can still take the BedJet over. The Android app's `WatchErrandTest` is the twin.
@MainActor
final class WatchErrandTests: XCTestCase {
    private struct Missing: Error {}

    private let mine = NearbyBedJet(id: UUID(), name: "BEDJET_3", generation: .v3, rssi: -50)
    private let partners = NearbyBedJet(id: UUID(), name: "BEDJET_3", generation: .v3, rssi: -70)
    private var directory: URL!
    private var defaults: UserDefaults!
    private var units: [UUID: FakeLink] = [:]

    override func setUp() async throws {
        // A starter schedule's wake time can be more than 16 hours away, which would make starting a night depend on the time of day.
        AppModel.startsWithoutWakeTimes = true
        directory = FileManager.default.temporaryDirectory.appending(path: "WatchErrandTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defaults = UserDefaults(suiteName: "WatchErrandTests-\(UUID().uuidString)")
        units = [:]
        AppModel.errandPatience = 2
    }

    override func tearDown() async throws {
        AppModel.startsWithoutWakeTimes = ProcessInfo.processInfo.arguments.contains("-noWakeTimes")
        for unit in units.values { unit.stop() }
        try? FileManager.default.removeItem(at: directory)
        AppModel.errandPatience = 8
    }

    private func launch(onScreen: Bool = true) -> AppModel {
        AppModel(storage: Storage(directory: directory, defaults: defaults), demoMode: false,
                 makeLink: { [unowned self] bedJet in self.unit(bedJet.unit!) },
                 discovery: { _ in DemoDiscovery(units: [self.mine, self.partners]) }, onScreen: onScreen)
    }

    private func unit(_ id: UUID) -> FakeLink {
        if let unit = units[id] { return unit }
        let unit = FakeLink(name: "BEDJET_3")
        units[id] = unit
        return unit
    }

    private func waitUntil(_ what: String, timeout: TimeInterval = 6, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else {
                XCTFail("Timed out waiting for \(what)")
                throw Missing()
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    /// A BedJet chosen and connected, with what's saved on it read, as after using Lull for a while.
    private func chosen(_ unit: NearbyBedJet, in model: AppModel) async throws -> BedJet {
        model.choose(unit)
        let bedJet = try XCTUnwrap(model.bedJets.first { $0.unit == unit.id })
        try await waitUntil("\(unit.name) to connect") { bedJet.isConnected }
        try await waitUntil("its slots to be read") { bedJet.deviceSlots.count == 3 }
        return bedJet
    }

    private struct Saved: Encodable {
        struct Fleet: Encodable {
            var bedJets: [SavedBedJet]
            var selected: UUID?
        }
        var schedules: [Schedule]
        var fleet: Fleet
    }

    /// Lull as an earlier launch left it, with just `saved` chosen.
    private func save(_ saved: SavedBedJet) throws {
        let fleet = Saved.Fleet(bedJets: [saved], selected: saved.id)
        let file = directory.appending(path: "lull.json")
        // The schedules Lull starts with here, without wake times, so the nights these tests start don't depend on the hour.
        try JSONEncoder().encode(Saved(schedules: AppModel.startingSchedules, fleet: fleet)).write(to: file)
    }

    func testOffScreenAnErrandStartsTheNightThenLetsGo() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()
        XCTAssertFalse(bedJet.isConnected)
        let stops = unit(mine.id).stops

        let reply = await model.runErrand(.startNight(bedJet.id))
        XCTAssertEqual(reply, .done)
        XCTAssertEqual(bedJet.run?.schedule.id, bedJet.tonight?.id)
        // Let go again, so another phone can take the BedJet over.
        XCTAssertGreaterThan(unit(mine.id).stops, stops)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testANightWithAWakeTimeEndsAtIt() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        // Two hours of cooling, ending three hours from now whatever the usual bedtime is: each hour stretches to an hour and a half.
        let wake = (Format.minuteOfDay(Date()) + 180) % 1440
        let schedule = Schedule(name: "Wake", phases: [Phase(.cool, minutes: 60), Phase(.cool, fan: 20, minutes: 60)], wakeTime: wake)
        model.save(schedule)
        bedJet.tonightID = schedule.id

        let reply = await model.runErrand(.startNight(bedJet.id))
        XCTAssertEqual(reply, .done)
        let run = try XCTUnwrap(bedJet.run)
        XCTAssertEqual(run.schedule.totalMinutes, run.schedule.minutesToWake(startingAt: Format.minuteOfDay(run.startedAt)))
        XCTAssertTrue((179...180).contains(run.schedule.totalMinutes), "three hours from the start, a minute either way")
        // The BedJet is given tonight's lengths, and keeps to the wake time itself.
        let slot = try XCTUnwrap(bedJet.slot)
        XCTAssertEqual(bedJet.deviceSlots[slot]?.steps.first, run.schedule.phases.first?.step)
        XCTAssertEqual(bedJet.deviceSlots[slot]?.steps.last, schedule.phases.last?.step(endingAt: wake))
    }

    func testWokenOffScreenNothingConnectsUntilAnErrandNeedsIt() async throws {
        let chosen = launch()
        _ = try await self.chosen(mine, in: chosen)
        chosen.appMovedToBackground()
        units = [:]

        // Woken by the watch: no BedJet is connected, and none is asked for, so another phone keeps its night.
        let model = launch(onScreen: false)
        let bedJet = try XCTUnwrap(model.bedJet)
        try await Task.sleep(nanoseconds: 500_000_000)
        // The link is set up, ready for an errand, and left idle.
        let link = try XCTUnwrap(units[mine.id])
        XCTAssertEqual(link.state, .searching)
        XCTAssertFalse(bedJet.isConnected)

        let reply = await model.runErrand(.startNight(bedJet.id))
        XCTAssertEqual(reply, .done)
        XCTAssertNotNil(bedJet.run)
        XCTAssertFalse(bedJet.isConnected)

        // Opened later, it talks to the BedJet as usual.
        model.appBecameActive()
        try await waitUntil("it to connect") { bedJet.isConnected }
    }

    func testARefreshDoesntCutOffAnErrandOnTheSameBedJet() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()

        async let refreshed = model.runErrand(.refresh)
        async let started = model.runErrand(.startNight(bedJet.id))
        let (refresh, start) = await (refreshed, started)
        XCTAssertEqual(refresh, .done)
        XCTAssertEqual(start, .done)
        XCTAssertNotNil(bedJet.run)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testOnScreenTheBedJetStaysConnected() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        let stops = unit(mine.id).stops

        let reply = await model.runErrand(.startNight(nil))
        XCTAssertEqual(reply, .done)
        XCTAssertNotNil(bedJet.run)
        XCTAssertEqual(unit(mine.id).stops, stops)
        XCTAssertTrue(bedJet.isConnected)
    }

    func testTheRemoteFromTheWatchSetsAndLetsGo() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()

        let preset = RemotePreset(mode: .heat, minutes: 20, temperature: 70, fanPercent: 35)
        let reply = await model.runErrand(.remote(preset, bedJet.id))
        XCTAssertEqual(reply, .done)
        // What the BedJet last said is kept once Lull lets go.
        XCTAssertEqual(bedJet.status?.mode, .heat)
        XCTAssertEqual(bedJet.status?.targetTemperature, 70)
        XCTAssertEqual(bedJet.status?.fanPercent, 35)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testTheWatchsRemoteSetsOnlyWhatWasChangedOnIt() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        await bedJet.setMode(.cool)
        await bedJet.setTemperature(50)
        await bedJet.setRuntime(minutes: 180)
        model.appMovedToBackground()

        // The watch last heard of other settings, and only the fan was changed on it.
        let preset = RemotePreset(mode: .heat, minutes: 45, temperature: 70, fanPercent: 50)
        let reply = await model.runErrand(.remote(preset, bedJet.id, changed: [.fan]))
        XCTAssertEqual(reply, .done)
        XCTAssertEqual(bedJet.status?.fanPercent, 50)
        XCTAssertEqual(bedJet.status?.mode, .cool)
        XCTAssertEqual(bedJet.status?.targetTemperature, 50)
        XCTAssertEqual(Double(bedJet.status?.remainingSeconds ?? 0), 180 * 60, accuracy: 90)
    }

    func testWhatWentWrongGoesBackToTheWatchNotIntoAnAlert() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()
        // Unplugged since.
        unit(mine.id).answers = false

        let reply = await model.runErrand(.startNight(bedJet.id))
        XCTAssertFalse(reply.ok)
        XCTAssertEqual(reply.message, BedJetError.notConnected.errorDescription)
        XCTAssertNil(bedJet.run)
        XCTAssertNil(model.errorMessage)
    }

    func testTheWatchsRemoteTriesABedJetOutOfReachOnce() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()
        // Unplugged since.
        unit(mine.id).answers = false
        let requests = unit(mine.id).requests

        // Starting the air from off sets everything. One wait for the BedJet says what's wrong, in time for the watch.
        let preset = RemotePreset(mode: .heat, minutes: 20, temperature: 70, fanPercent: 35)
        let reply = await model.runErrand(.remote(preset, bedJet.id))
        XCTAssertEqual(reply.message, BedJetError.notConnected.errorDescription)
        XCTAssertEqual(unit(mine.id).requests, requests + 1)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testAnErrandForAForgottenBedJetNeverReachesAnother() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        let forgotten = UUID()

        let reply = await model.runErrand(.startNight(forgotten))
        XCTAssertFalse(reply.ok)
        XCTAssertNil(bedJet.run)
    }

    func testABedJetForgottenWhileAnErrandWaitsForItIsLeftAlone() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        // Out of reach, so the errand waits for it.
        unit(mine.id).answers = false
        unit(mine.id).stop()
        let requests = unit(mine.id).requests

        async let replied = model.runErrand(.startNight(bedJet.id))
        try await Task.sleep(nanoseconds: 500_000_000)
        // Forgotten meanwhile, and back in reach.
        model.forget(bedJet)
        unit(mine.id).answers = true

        let reply = await replied
        XCTAssertEqual(reply, .failed("Choose your BedJet in Lull on your iPhone first."))
        XCTAssertNil(bedJet.run)
        XCTAssertEqual(unit(mine.id).requests, requests)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testAnErrandOutOfBackgroundTimeGivesUp() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()
        // Out of reach, so the errand waits for it.
        unit(mine.id).answers = false
        let requests = unit(mine.id).requests

        let errand = Task { await model.runErrand(.startNight(bedJet.id)) }
        try await Task.sleep(nanoseconds: 500_000_000)
        // iOS runs out of background time, which cancels the errand as `PhoneSession` does, and the BedJet is back in
        // reach before Lull next runs.
        errand.cancel()
        model.letGoOfErrands()
        unit(mine.id).answers = true

        let reply = await errand.value
        XCTAssertEqual(reply, .failed("Lull on your iPhone didn't answer in time. Open it there to check on your BedJet."))
        XCTAssertNil(bedJet.run)
        XCTAssertEqual(unit(mine.id).requests, requests)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testAnErrandOutOfBackgroundTimeDuringItsCommandSendsNothingMore() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        model.appMovedToBackground()
        let link = unit(mine.id)
        link.holdsSends = true

        let errand = Task { await model.runErrand(.startNight(bedJet.id)) }
        try await waitUntil("tonight to be on its way to the BedJet") { link.isHolding }
        // iOS runs out of background time while the BedJet is being given tonight, which cancels the errand.
        errand.cancel()
        model.letGoOfErrands()
        // Lull is next opened, the BedJet connects again, and only then does the command in flight finish.
        model.appBecameActive()
        try await waitUntil("the BedJet to connect again") { bedJet.isConnected }
        link.release()

        let reply = await errand.value
        // Tonight was never started, hours after the watch gave up on it.
        XCTAssertEqual(reply, .failed("Lull on your iPhone didn't answer in time. Open it there to check on your BedJet."))
        XCTAssertNil(bedJet.run)
        XCTAssertEqual(link.device.programStep, 0)
    }

    func testARefreshHearsEachBedJetThenLetsGo() async throws {
        let model = launch()
        let first = try await chosen(mine, in: model)
        let second = try await chosen(partners, in: model)
        model.appMovedToBackground()
        let asked = Date()

        let reply = await model.runErrand(.refresh)
        XCTAssertEqual(reply, .done)
        XCTAssertTrue(first.hasReported(since: asked))
        XCTAssertTrue(second.hasReported(since: asked))
        XCTAssertFalse(first.isConnected)
        XCTAssertFalse(second.isConnected)
    }

    func testWhatARefreshFindsWentWrongWaitsForLullToBeOpened() async throws {
        // Started from this phone an hour ago, and ended early by the BedJet since.
        let night = ActiveRun(schedule: AppModel.startingSchedules[0], startedAt: Date().addingTimeInterval(-3600))
        try save(SavedBedJet(unit: mine.id, name: mine.name, generation: .v3, run: night))
        let model = launch(onScreen: false)
        let bedJet = try XCTUnwrap(model.bedJet)
        XCTAssertNotNil(bedJet.run)

        let reply = await model.runErrand(.refresh)
        XCTAssertEqual(reply, .done)
        XCTAssertNil(bedJet.run)
        // Nothing answers a refresh, so it's said in Lull instead, once opened.
        XCTAssertTrue(model.errorMessage?.hasPrefix("Your BedJet turned off") == true)
        XCTAssertFalse(bedJet.isConnected)
    }

    func testWhatAnErrandFindsBeforeItsOwnCommandIsntHowItWent() async throws {
        // Started from this phone an hour ago, and ended early by the BedJet since.
        let night = ActiveRun(schedule: AppModel.startingSchedules[0], startedAt: Date().addingTimeInterval(-3600))
        try save(SavedBedJet(unit: mine.id, name: mine.name, generation: .v3, run: night))
        let model = launch(onScreen: false)
        let bedJet = try XCTUnwrap(model.bedJet)

        let reply = await model.runErrand(.startNight(bedJet.id))
        // Tonight started, so the watch hears it went; the night that ended early is said in Lull, once opened.
        XCTAssertEqual(reply, .done)
        XCTAssertNotNil(bedJet.run)
        XCTAssertTrue(model.errorMessage?.hasPrefix("Your BedJet turned off") == true)
    }

    func testAnErrandSaysSoWhenTheBedJetIsBusy() async throws {
        let model = launch()
        let bedJet = try await chosen(mine, in: model)
        // A fan change on the phone is still on its way to the BedJet when the watch asks for something.
        let link = unit(mine.id)
        link.holdsSends = true
        let change = Task { await bedJet.setFan(percent: 60) }
        try await waitUntil("the fan change to be in flight") { link.isHolding }
        let sent = link.sent

        let started = await model.runErrand(.startNight(bedJet.id))
        let preset = RemotePreset(mode: .heat, minutes: 20, temperature: 70, fanPercent: 35)
        let set = await model.runErrand(.remote(preset, bedJet.id))
        // The watch hears the BedJet was left alone, rather than that it went.
        XCTAssertEqual(started, .failed(WatchMessages.busy))
        XCTAssertEqual(set, .failed(WatchMessages.busy))
        XCTAssertNil(bedJet.run)
        XCTAssertEqual(link.sent, sent)
        link.release()
        await change.value
    }

    func testANightThatEndedIsNotedWhenAnActionFromShortcutsFindsIt() async throws {
        // Started from this phone an hour ago, and ended early by the BedJet since.
        let night = ActiveRun(schedule: AppModel.startingSchedules[0], startedAt: Date().addingTimeInterval(-3600))
        try save(SavedBedJet(unit: mine.id, name: mine.name, generation: .v3, run: night))
        let model = launch(onScreen: false)
        let bedJet = try XCTUnwrap(model.bedJet)
        // As over Bluetooth, the BedJet says what it's doing a moment after it connects, once the action waits to hear it.
        // That wait is under way, but the action isn't ending the night, so the early end is recorded and said.
        let link = unit(mine.id)
        link.reports = false
        let action = Task { try await model.perform(.lightRing(on: true), on: bedJet) }
        try await waitUntil("the BedJet to connect") { bedJet.isConnected }
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(bedJet.isBusy)
        link.reports = true

        _ = try await action.value
        XCTAssertNil(bedJet.run)
        XCTAssertTrue(bedJet.history.contains { if case .scheduleEnded = $0.event { true } else { false } })
        XCTAssertTrue(model.errorMessage?.hasPrefix("Your BedJet turned off") == true)
    }

    func testANightThatEndedIsNotedWhenTheRemoteIsUsedAsLullConnects() async throws {
        // Started from this phone an hour ago, and ended early by the BedJet since.
        let night = ActiveRun(schedule: AppModel.startingSchedules[0], startedAt: Date().addingTimeInterval(-3600))
        try save(SavedBedJet(unit: mine.id, name: mine.name, generation: .v3, run: night))
        let link = unit(mine.id)
        link.reports = false
        let model = launch()
        let bedJet = try XCTUnwrap(model.bedJet)
        try await waitUntil("the BedJet to connect") { bedJet.isConnected }
        // The fan is changed the moment Lull is opened, and the BedJet's first report arrives while that's under way.
        link.holdsSends = true
        let change = Task { await bedJet.setFan(percent: 60) }
        try await waitUntil("the fan change to be in flight") { link.isHolding }
        link.reports = true
        try await waitUntil("the BedJet to report") { bedJet.run == nil }
        link.release()
        await change.value

        XCTAssertTrue(bedJet.history.contains { if case .scheduleEnded(_, .early) = $0.event { true } else { false } })
        XCTAssertTrue(model.errorMessage?.hasPrefix("Your BedJet turned off") == true)
    }

    func testANightLullEndsIsNotedOnceThoughTheBedJetSaysSoFirst() async throws {
        // Started from this phone an hour ago, and still running as far as Lull knows.
        let night = ActiveRun(schedule: AppModel.startingSchedules[0], startedAt: Date().addingTimeInterval(-3600))
        try save(SavedBedJet(unit: mine.id, name: mine.name, generation: .v3, run: night))
        let link = unit(mine.id)
        link.reports = false
        let model = launch()
        let bedJet = try XCTUnwrap(model.bedJet)
        try await waitUntil("the BedJet to connect") { bedJet.isConnected && link.lastStatus != nil }
        link.holdsSends = true
        let ending = Task { await bedJet.endNight() }
        try await waitUntil("End Night to be in flight") { link.isHolding }
        // The BedJet's report that the schedule stopped arrives before the command returns.
        link.report(try XCTUnwrap(link.lastStatus))
        XCTAssertNil(bedJet.run)
        link.release()
        await ending.value

        let ends = bedJet.history.compactMap { if case let .scheduleEnded(_, ending) = $0.event { ending } else { nil } }
        XCTAssertEqual(ends, [.byYou])
        XCTAssertNil(model.errorMessage)
    }
}
