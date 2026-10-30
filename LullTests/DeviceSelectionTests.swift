import BedJetKit
import XCTest
@testable import Lull

/// Choosing, switching between and forgetting BedJets, against simulated units that connect only when allowed,
/// record every command they receive and can hold one in flight. Nothing here touches real hardware.
@MainActor
final class DeviceSelectionTests: XCTestCase {
    private struct Missing: Error {}

    // Two units share the default name, as real ones often do. The guest room's doesn't say what it is.
    private let mine = NearbyBedJet(id: UUID(), name: "BEDJET_3", generation: .v3, rssi: -50)
    private let partners = NearbyBedJet(id: UUID(), name: "BEDJET_3", generation: .v3, rssi: -70)
    private let guest = NearbyBedJet(id: UUID(), name: "BEDJET_GUEST", generation: nil, rssi: -85)

    private var directory: URL!
    private var defaults: UserDefaults!
    private var discovery: DemoDiscovery!
    /// The simulated units, by Bluetooth identity. Each keeps its state across connections, like a real one.
    private var units: [UUID: FakeLink] = [:]
    /// How each unit is set up when Lull first connects to it.
    private var setups: [UUID: (FakeLink) -> Void] = [:]

    override func setUp() async throws {
        // A starter schedule's wake time can be more than 16 hours away, which would make starting a night depend on the time of day.
        AppModel.startsWithoutWakeTimes = true
        directory = FileManager.default.temporaryDirectory.appending(path: "DeviceSelectionTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defaults = UserDefaults(suiteName: "DeviceSelectionTests-\(UUID().uuidString)")
        discovery = DemoDiscovery(units: [mine, partners, guest])
        units = [:]
        setups = [:]
    }

    override func tearDown() async throws {
        AppModel.startsWithoutWakeTimes = ProcessInfo.processInfo.arguments.contains("-noWakeTimes")
        for unit in units.values { unit.stop() }
        try? FileManager.default.removeItem(at: directory)
    }

    /// Lull launched by the person, on screen. Off screen is a launch to run an action or list BedJets in Shortcuts.
    private func launch(demo: Bool = false, onScreen: Bool = true) -> AppModel {
        AppModel(storage: Storage(directory: directory, defaults: defaults), demoMode: demo,
                 makeLink: { [unowned self] bedJet in self.unit(bedJet.unit!) },
                 discovery: { [unowned self] _ in self.discovery }, onScreen: onScreen)
    }

    private func unit(_ id: UUID) -> FakeLink {
        if let unit = units[id] { return unit }
        let unit = FakeLink(name: [mine, partners, guest].first { $0.id == id }?.name ?? "BEDJET")
        setups[id]?(unit)
        units[id] = unit
        return unit
    }

    private func bedJet(_ unit: NearbyBedJet, in model: AppModel) throws -> BedJet {
        try XCTUnwrap(model.bedJets.first { $0.unit == unit.id })
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

    /// Waits for a BedJet to connect and finish reading what's saved on it, which Lull does on each new connection.
    private func connected(_ unit: NearbyBedJet, in model: AppModel) async throws -> BedJet {
        let bedJet = try bedJet(unit, in: model)
        try await waitUntil("\(unit.name) to connect") { bedJet.isConnected }
        if bedJet.generation == .v3 { try await waitUntil("\(unit.name)'s slots to be read") { bedJet.deviceSlots.count == 3 } }
        return bedJet
    }

    // MARK: Choosing

    func testNothingConnectsUntilABedJetIsChosen() async throws {
        let model = launch()
        XCTAssertNil(model.bedJet)
        model.startLooking()
        // Everything nearby is listed as soon as it's seen, and looking connects to none of it.
        XCTAssertEqual(model.nearby.map(\.id), [mine.id, partners.id, guest.id])
        XCTAssertTrue(units.isEmpty)

        model.choose(partners)
        XCTAssertEqual(model.bedJet?.unit, partners.id)
        XCTAssertEqual(Set(units.keys), [partners.id])
        _ = try await connected(partners, in: model)
        // Once chosen it's under Your BedJets, not Nearby.
        XCTAssertEqual(model.nearby.map(\.id), [mine.id, guest.id])
    }

    func testALoneBedJetNearbyIsListedAtOnce() {
        discovery.units = [guest]
        let model = launch()
        model.startLooking()
        XCTAssertEqual(model.nearby.map(\.id), [guest.id])
        model.stopLooking()
        XCTAssertTrue(model.nearby.isEmpty)
    }

    func testSameNamedBedJetsAreToldApartAndNicknamesStick() async throws {
        let model = launch()
        model.startLooking()
        XCTAssertEqual(model.title(of: mine), "BEDJET_3 · \(AppModel.code(of: mine.id))")
        XCTAssertEqual(model.title(of: guest), "BEDJET_GUEST")
        model.choose(mine)
        model.choose(partners)
        let first = try bedJet(mine, in: model), second = try bedJet(partners, in: model)
        XCTAssertEqual(model.title(of: first), "BEDJET_3 · \(AppModel.code(of: mine.id))")
        XCTAssertEqual(model.title(of: second), "BEDJET_3 · \(AppModel.code(of: partners.id))")
        XCTAssertNotEqual(model.title(of: first), model.title(of: second))

        second.nickname = "Partner's side"
        XCTAssertEqual(model.title(of: second), "Partner's side")
        XCTAssertEqual(model.title(of: first), "BEDJET_3")

        // Kept on this phone across launches; the BedJet itself isn't renamed.
        let relaunched = launch()
        XCTAssertEqual(try bedJet(partners, in: relaunched).nickname, "Partner's side")
        XCTAssertEqual(try bedJet(partners, in: relaunched).name, "BEDJET_3")
        XCTAssertEqual(relaunched.bedJet?.unit, partners.id)
    }

    func testReconnectsToTheChosenBedJetAfterRelaunch() async throws {
        let model = launch()
        model.choose(mine)
        _ = try await connected(mine, in: model)
        model.appMovedToBackground()

        units = [:]
        let relaunched = launch()
        XCTAssertEqual(relaunched.bedJet?.unit, mine.id)
        XCTAssertEqual(Set(units.keys), [mine.id])
        _ = try await connected(mine, in: relaunched)
    }

    func testAnAbsentBedJetIsNeverSwappedForAnotherNearby() async throws {
        setups[mine.id] = { $0.answers = false }
        let model = launch()
        model.choose(mine)
        model.startLooking()
        try await Task.sleep(nanoseconds: 2_000_000_000)
        // Still looking for the chosen one, with the partner's in range and free.
        XCTAssertEqual(model.bedJet?.unit, mine.id)
        XCTAssertEqual(model.bedJet?.linkState, .searching)
        XCTAssertEqual(Set(units.keys), [mine.id])
        // Over Bluetooth, a link only takes the unit it was made for.
        XCTAssertFalse(BLELink.takes(partners.id, boundTo: mine.id))
        XCTAssertTrue(BLELink.takes(mine.id, boundTo: mine.id))
    }

    // MARK: Switching

    func testSwitchingLeavesTheOtherBedJetRunning() async throws {
        let model = launch()
        model.choose(mine)
        let first = try await connected(mine, in: model)
        await first.startTonight()
        XCTAssertNotNil(first.run)
        await first.setTemperature(70)
        let sentBefore = unit(mine.id).sent

        model.choose(partners)
        XCTAssertEqual(model.bedJet?.unit, partners.id)
        _ = try await connected(partners, in: model)
        try await Task.sleep(nanoseconds: 1_500_000_000)

        // The first keeps its connection, its schedule and everything set on it; nothing more was sent to it.
        XCTAssertEqual(unit(mine.id).sent, sentBefore)
        XCTAssertEqual(unit(mine.id).stops, 0)
        XCTAssertNotNil(first.run)
        XCTAssertTrue(first.isConnected)
        XCTAssertGreaterThan(first.status?.extra?.programStep ?? 0, 0)
    }

    func testRapidSwitchingAndLateReportsStayWithTheirBedJet() async throws {
        let model = launch()
        model.choose(mine)
        model.choose(partners)
        model.choose(guest)
        let first = try await connected(mine, in: model)
        let last = try await connected(guest, in: model)
        for id in [first.id, last.id, first.id, last.id] { XCTAssertTrue(model.select(id)) }
        XCTAssertEqual(model.bedJet?.unit, guest.id)

        // A report from the first unit, arriving whenever it does, only ever updates the first.
        let before = last.status
        var report = try XCTUnwrap(first.status)
        report.mode = .turbo
        unit(mine.id).report(report)
        XCTAssertEqual(first.status?.mode, .turbo)
        XCTAssertEqual(last.status?.mode, before?.mode)
        XCTAssertEqual(model.bedJet?.unit, guest.id)
    }

    /// A real BedJet 3 sends each report in two halves, and right after reconnecting, such as after restarting to check
    /// for firmware, only the first half comes for a moment. Its settings stay shown meanwhile, so the sheet holds still.
    func testSettingsStayWhileAReportsSecondHalfIsLate() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        try await waitUntil("a full report") { bedJet.settings != nil }
        let settings = bedJet.settings
        var firstHalf = try XCTUnwrap(bedJet.status)
        firstHalf.extra = nil
        unit(mine.id).report(firstHalf)
        XCTAssertNil(bedJet.status?.extra)
        XCTAssertEqual(bedJet.settings, settings)
    }

    /// A BedJet checking for firmware restarts partway through, by itself or for another phone, so nothing is sent
    /// meanwhile: not a command, not a night, not another check or network.
    func testNothingIsSentWhileTheBedJetChecksForFirmware() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        try await waitUntil("a full report") { bedJet.settings != nil }
        var checking = try XCTUnwrap(bedJet.status)
        checking.extra?.wifiPhase = Codec.WiFiPhase.checking
        unit(mine.id).report(checking)
        XCTAssertFalse(bedJet.canControl)
        XCTAssertFalse(bedJet.canUpdateFirmware)
        XCTAssertFalse(bedJet.canJoinWiFi)

        let sentBefore = unit(mine.id).sent.count
        await bedJet.setMode(.heat)
        await bedJet.startTonight()
        await bedJet.updateFirmware()
        XCTAssertEqual(unit(mine.id).sent.count, sentBefore)
        XCTAssertNotNil(model.errorMessage)

        // Done: the result stays in the report, and the BedJet is free again.
        var done = checking
        done.extra?.wifiPhase = Codec.WiFiPhase.upToDate
        unit(mine.id).report(done)
        XCTAssertTrue(bedJet.canControl)
        XCTAssertTrue(bedJet.canUpdateFirmware)
    }

    /// Restarting to install firmware lasts until the BedJet restarts. It may still say so once it's back.
    func testRestartingToInstallOnlyHoldsUntilTheBedJetRestarts() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        try await waitUntil("a full report") { bedJet.settings != nil }
        var installing = try XCTUnwrap(bedJet.status)
        installing.extra?.wifiPhase = Codec.WiFiPhase.installing
        unit(mine.id).report(installing)
        XCTAssertFalse(bedJet.canControl)

        // Gone, so restarted: the same result showing afterwards is the one from before.
        unit(mine.id).stop()
        unit(mine.id).report(installing)
        XCTAssertTrue(bedJet.canControl)
    }

    /// Like a real BedJet 3, the simulated one restarts to check, downloads the newer firmware it offered, then
    /// restarts into it. Lull holds on to it throughout, and says what it's on once it's back.
    func testAnOfferedUpdateIsInstalledAndSaidOnceBack() async throws {
        setups[mine.id] = { $0.device.offerFirmwareUpdate() }
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        try await waitUntil("the offer") { bedJet.firmwarePrompt == .available }
        bedJet.deferFirmwareUpdate()
        await bedJet.updateFirmware(offered: true)
        XCTAssertTrue(bedJet.expectsRestart)
        XCTAssertFalse(bedJet.canControl)
        XCTAssertNil(bedJet.firmwarePrompt)

        try await waitUntil("the BedJet to be back on the new firmware", timeout: 30) { bedJet.firmware == "V1.21 (demo)" }
        guard case let .finished(activity)? = bedJet.firmwarePrompt else { return XCTFail("How it went wasn't said") }
        XCTAssertEqual(activity.installed, "V1.21 (demo)")
        XCTAssertFalse(bedJet.expectsRestart)
        XCTAssertTrue(bedJet.canControl)
        XCTAssertEqual(unit(mine.id).sent.filter { $0 == Codec.updateFirmware }.count, 1)
        bedJet.dismissFirmwareResult()
        XCTAssertNil(bedJet.firmwarePrompt)
    }

    /// Updating restarts the BedJet, so it waits for the air to be off and the night to be over.
    func testAnUpdateWaitsForTheAirToBeOff() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        try await waitUntil("a report") { bedJet.status != nil }
        XCTAssertTrue(bedJet.canUpdateFirmware)
        await bedJet.setMode(.heat)
        try await waitUntil("the air to be on") { bedJet.status?.mode == .heat }
        XCTAssertFalse(bedJet.canUpdateFirmware)
        await bedJet.updateFirmware()
        XCTAssertFalse(unit(mine.id).sent.contains(Codec.updateFirmware))
        await bedJet.setMode(.standby)
        try await waitUntil("the air to be off") { bedJet.status?.mode == .standby }
        XCTAssertTrue(bedJet.canUpdateFirmware)
    }

    /// The BedJet's word that newer firmware is available is offered once and then waits in the sheet. Lull leaves
    /// it for the BedJet to clear, so other phones and the official app still see it.
    func testAnOfferedUpdateIsAskedAboutOnceAndLeftSet() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        try await waitUntil("a full report") { bedJet.settings != nil }
        var offered = try XCTUnwrap(bedJet.status)
        offered.extra?.notification = Codec.Notification.updateAvailable
        unit(mine.id).report(offered)
        XCTAssertTrue(bedJet.updateAvailable)
        XCTAssertEqual(bedJet.firmwarePrompt, .available)

        bedJet.deferFirmwareUpdate()
        XCTAssertNil(bedJet.firmwarePrompt)
        XCTAssertTrue(bedJet.updateAvailable)
        XCTAssertFalse(unit(mine.id).sent.contains(Codec.acknowledgeNotification))
    }

    func testACommandMeantForOneBedJetNeverReachesAnother() async throws {
        let model = launch()
        model.choose(mine)
        model.choose(partners)
        let first = try await connected(mine, in: model)
        let second = try await connected(partners, in: model)
        XCTAssertTrue(model.select(first.id))

        unit(mine.id).holdsSends = true
        let sending = Task { await first.setMode(.heat) }
        try await waitUntil("the command to be in flight") { unit(mine.id).isHolding }
        XCTAssertTrue(model.select(second.id))
        unit(mine.id).release()
        await sending.value

        let heat = try XCTUnwrap(Codec.setMode(.heat))
        XCTAssertTrue(unit(mine.id).sent.contains(heat))
        XCTAssertFalse(unit(partners.id).sent.contains(heat))
        XCTAssertEqual(second.status?.mode, .standby)
    }

    func testSwitchingWaitsWhileAScheduleIsSaving() async throws {
        let model = launch()
        model.choose(partners)
        model.choose(mine)
        let first = try await connected(mine, in: model)
        let second = try await connected(partners, in: model)

        unit(mine.id).holdsSends = true
        let saving = Task { await first.startTonight() }
        try await waitUntil("the schedule to start saving") { unit(mine.id).isHolding && first.isSavingSchedule }
        XCTAssertFalse(model.canSwitch)
        XCTAssertFalse(model.select(second.id))
        model.choose(guest)
        XCTAssertEqual(model.bedJet?.unit, mine.id)
        XCTAssertEqual(model.bedJets.count, 2)

        unit(mine.id).release()
        await saving.value
        XCTAssertNotNil(first.run)
        XCTAssertTrue(model.select(second.id))
    }

    // MARK: What belongs to each BedJet

    func testSlotsRunsAndHistoryStayWithTheirBedJet() async throws {
        let theirs = StoredProgram(name: "Theirs", steps: [ProgramStep(mode: .cool, fanStep: 7, temperature: 40, hours: 2, minutes: 0)])
        setups[partners.id] = { $0.device = DemoLink(name: "BEDJET_3", slots: [theirs, nil, nil]) }
        let model = launch()
        model.choose(mine)
        let first = try await connected(mine, in: model)
        await first.startTonight()
        XCTAssertEqual(first.slot, 0)
        XCTAssertNotNil(first.run)
        XCTAssertTrue(first.history.contains { if case .scheduleStarted = $0.event { true } else { false } })

        model.choose(partners)
        let second = try await connected(partners, in: model)
        XCTAssertNil(second.slot)
        XCTAssertNil(second.run)
        XCTAssertTrue(second.history.isEmpty)
        // Its own slots are read before one is picked, so the program saved on it stays.
        await second.startTonight()
        XCTAssertEqual(second.slot, 1)
        XCTAssertEqual(unit(partners.id).device.slots[0], theirs)
        XCTAssertEqual(first.slot, 0)
        XCTAssertNotNil(first.run)
        XCTAssertNotEqual(first.history, second.history)
    }

    func testAV2RefusesModesItCantRunWithoutChangingTheSchedule() async throws {
        setups[mine.id] = { $0.generation = .v2 }
        let model = launch()
        let dryOut = Schedule(name: "Dry out", phases: [Phase(.cool, fan: 40, minutes: 60), Phase(.dry, fan: 50, minutes: 30)])
        model.save(dryOut)
        model.choose(mine)
        let first = try await connected(mine, in: model)
        XCTAssertEqual(first.knownGeneration, .v2)
        first.tonightID = dryOut.id
        XCTAssertEqual(first.unsupportedModes(in: dryOut), [.dry])
        XCTAssertNotNil(first.problem(running: dryOut))

        let sentBefore = unit(mine.id).sent
        await first.startTonight()
        XCTAssertEqual(unit(mine.id).sent, sentBefore)
        XCTAssertNil(first.run)
        XCTAssertTrue(model.errorMessage?.contains("can't run Dry") == true)
        XCTAssertEqual(model.schedules.first { $0.id == dryOut.id }, dryOut)

        // A BedJet 3 runs the same schedule as it is.
        model.choose(partners)
        let second = try await connected(partners, in: model)
        XCTAssertNil(second.problem(running: dryOut))
    }

    // MARK: Carrying over saved state

    private struct SingleBedJetSave: Codable {
        var schedules: [Schedule]
        var tonightID: Schedule.ID?
        var slot: Int?
    }

    private struct SeveralBedJetsSave: Codable {
        struct Fleet: Codable {
            var bedJets: [SavedBedJet]
            var selected: UUID?
        }
        var schedules: [Schedule]
        var fleet: Fleet
    }

    private struct HistoryFile: Codable { var entries: [HistoryEntry] }

    private func write(_ value: some Encodable, to name: String) throws {
        try JSONEncoder().encode(value).write(to: directory.appending(path: name))
    }

    private let aNight = [HistoryEntry(date: Date().addingTimeInterval(-3600), event: .scheduleStarted(name: "Cool Drift", phases: nil), room: 44)]

    func testCarriesOverTheBedJetAnEarlierVersionRemembered() async throws {
        defaults.set(mine.id.uuidString, forKey: "BedJetKit.peripheral")
        defaults.set("BEDJET_3", forKey: "BedJetKit.deviceName")
        defaults.set("v2", forKey: "generation")
        try write(SingleBedJetSave(schedules: Schedule.templates, tonightID: Schedule.templates[1].id, slot: 2), to: "lull.json")
        try write(HistoryFile(entries: aNight), to: "history.json")

        let model = launch()
        let first = try XCTUnwrap(model.bedJet)
        XCTAssertEqual(first.unit, mine.id)
        XCTAssertEqual(first.name, "BEDJET_3")
        XCTAssertEqual(first.knownGeneration, .v2)
        XCTAssertEqual(first.slot, 2)
        XCTAssertEqual(first.tonightID, Schedule.templates[1].id)
        XCTAssertEqual(first.history, aNight)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appending(path: "history.json").path))

        // Carrying over happens once; launching again finds the same BedJet with the same nights.
        model.appMovedToBackground()
        let relaunched = launch()
        XCTAssertEqual(relaunched.bedJets.count, 1)
        XCTAssertEqual(relaunched.bedJet?.id, first.id)
        XCTAssertEqual(relaunched.bedJet?.history, aNight)
    }

    func testWithoutARememberedBedJetNothingIsAssumed() throws {
        try write(SingleBedJetSave(schedules: Schedule.templates, tonightID: nil, slot: 1), to: "lull.json")
        try write(HistoryFile(entries: aNight), to: "history.json")
        let model = launch()
        XCTAssertNil(model.bedJet)
        XCTAssertTrue(model.bedJets.isEmpty)
        // Nights of unknown origin stay where they were rather than being given to whichever BedJet is chosen next.
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appending(path: "history.json").path))
        model.choose(mine)
        XCTAssertTrue(model.bedJet?.history.isEmpty == true)
    }

    func testBedJetsSavedWithoutAUnitAreChosenAgain() throws {
        // Saved by the version that connected to whichever BedJet it found first.
        let unknown = SavedBedJet(name: "BEDJET_3"), known = SavedBedJet(unit: partners.id, name: "BEDJET_3")
        try write(SeveralBedJetsSave(schedules: Schedule.templates, fleet: .init(bedJets: [unknown, known], selected: unknown.id)), to: "lull.json")
        try write(HistoryFile(entries: aNight), to: "history-\(unknown.id.uuidString).json")

        let model = launch()
        XCTAssertEqual(model.bedJets.map(\.id), [known.id])
        XCTAssertNil(model.bedJet)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appending(path: "history-\(unknown.id.uuidString).json").path))
    }

    // MARK: Forgetting

    func testForgettingTheBedJetOnScreenAsksAgain() async throws {
        let model = launch()
        model.choose(partners)
        model.choose(mine)
        let first = try await connected(mine, in: model)
        await first.startTonight()
        let nights = first.history
        XCTAssertFalse(nights.isEmpty)

        model.forget(first)
        // Nothing is picked in its place, and the unit was left running as it was.
        XCTAssertNil(model.bedJet)
        XCTAssertEqual(model.bedJets.map(\.unit), [partners.id])
        XCTAssertFalse(unit(mine.id).sent.contains(try XCTUnwrap(Codec.setMode(.standby))))
        XCTAssertGreaterThan(unit(mine.id).device.programStep, 0)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appending(path: "history-\(first.id.uuidString).json").path))

        model.appMovedToBackground()
        let relaunched = launch()
        XCTAssertNil(relaunched.bedJet)

        // Choosing it again brings its nights back.
        relaunched.choose(mine)
        XCTAssertEqual(relaunched.bedJet?.id, first.id)
        XCTAssertEqual(relaunched.bedJet?.history, nights)
    }

    func testDemoBedJetsStayApartFromRealOnes() async throws {
        let model = launch()
        model.choose(mine)
        model.demoMode = true
        XCTAssertTrue(model.bedJet?.isDemo == true)
        XCTAssertFalse(model.bedJets.contains { $0.unit == mine.id })
        model.demoMode = false
        XCTAssertEqual(model.bedJet?.unit, mine.id)
        XCTAssertEqual(model.bedJets.count, 1)
        XCTAssertFalse(model.bedJet?.isDemo ?? true)
    }

    // MARK: The Remote's timer

    /// Opens Lull on `mine` as an earlier launch left it, with the Remote's timer saved as given.
    private func launch(longestEnds: Date?, timerEnds: Date?) throws -> AppModel {
        let saved = SavedBedJet(unit: mine.id, name: mine.name, generation: .v3, longestEnds: longestEnds, timerEnds: timerEnds)
        try write(SeveralBedJetsSave(schedules: Schedule.templates, fleet: .init(bedJets: [saved], selected: saved.id)), to: "lull.json")
        return launch()
    }

    private func timerCommands(_ unit: FakeLink, after count: Int = 0) -> [Data] {
        unit.sent.dropFirst(count).filter { $0.first == Codec.setRuntime(minutes: 0).first }
    }

    private func remainingSeconds(_ unit: FakeLink) throws -> Int {
        try XCTUnwrap(unit.lastStatus?.remainingSeconds)
    }

    func testAPickedTimeKeepsCountingDownAfterLullIsClosed() async throws {
        // An hour was picked, and 45 minutes are left when Lull is opened again and the fan changed.
        let unit = unit(mine.id)
        try await unit.device.send(XCTUnwrap(Codec.setMode(.cool)))
        try await unit.device.send(Codec.setRuntime(minutes: 45))
        let model = try launch(longestEnds: nil, timerEnds: nil)
        let bedJet = try await connected(mine, in: model)

        await bedJet.setFan(percent: 50)
        XCTAssertEqual(timerCommands(unit), [])
        XCTAssertEqual(try remainingSeconds(unit), 45 * 60, accuracy: 15)
    }

    func testTheLongestRunKeepsItsEndAsTheFanChanges() async throws {
        // Cool was chosen on the Remote 45 minutes ago, which gave it 12 hours.
        let unit = unit(mine.id)
        try await unit.device.send(XCTUnwrap(Codec.setMode(.cool)))
        try await unit.device.send(Codec.setRuntime(minutes: 675))
        let ends = Date().addingTimeInterval(675 * 60)
        let model = try launch(longestEnds: ends, timerEnds: ends)
        let bedJet = try await connected(mine, in: model)

        await bedJet.setFan(percent: 50)
        XCTAssertEqual(timerCommands(unit), [])
        XCTAssertEqual(try remainingSeconds(unit), 675 * 60, accuracy: 15)
    }

    func testHeatGetsBackTheTimeItWasCutButNoMore() async throws {
        // Heat was chosen 90 minutes ago with 4 hours allowed, until 40 °C at 40% fan cut what was left to 2 hours.
        let unit = unit(mine.id)
        try await unit.device.send(XCTUnwrap(Codec.setMode(.heat)))
        try await unit.device.send(Codec.setTemperature(80))
        try await unit.device.send(Codec.setRuntime(minutes: 120))
        let model = try launch(longestEnds: Date().addingTimeInterval(150 * 60), timerEnds: Date().addingTimeInterval(120 * 60))
        let bedJet = try await connected(mine, in: model)

        // A gentler fan lets Heat run 4 hours again: the cut half hour comes back, but the run still ends when it would have.
        await bedJet.setFan(percent: 5)
        XCTAssertEqual(timerCommands(unit), [Codec.setRuntime(minutes: 150)])
        XCTAssertEqual(try remainingSeconds(unit), 150 * 60, accuracy: 15)
    }

    func testATimeSetElsewhereIsLeftAlone() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.setMode(.cool)
        let unit = unit(mine.id)
        XCTAssertEqual(try remainingSeconds(unit), 720 * 60, accuracy: 15)

        // From another phone, or the BedJet's own remote.
        try await unit.device.send(Codec.setRuntime(minutes: 90))
        let sent = unit.sent.count
        await bedJet.setFan(percent: 50)
        XCTAssertEqual(timerCommands(unit, after: sent), [])
        XCTAssertEqual(try remainingSeconds(unit), 90 * 60, accuracy: 15)
    }

    func testATimePickedWhileTheModeIsStillStartingWins() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        // Cool waits for the BedJet to report it before giving it its longest run.
        let choosing = Task { await bedJet.setMode(.cool) }
        try await Task.sleep(nanoseconds: 100_000_000)
        await bedJet.setRuntime(minutes: 180)
        await choosing.value

        XCTAssertEqual(try remainingSeconds(unit(mine.id)), 180 * 60, accuracy: 15)
    }

    func testAPickedTimeIsSavedForTheNextLaunch() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.setMode(.cool)
        await bedJet.setRuntime(minutes: 180)

        model.appMovedToBackground()
        let relaunched = launch()
        let again = try await connected(mine, in: relaunched)
        let unit = unit(mine.id)
        let sent = unit.sent.count
        await again.setFan(percent: 50)
        XCTAssertEqual(timerCommands(unit, after: sent), [])
        XCTAssertEqual(try remainingSeconds(unit), 180 * 60, accuracy: 15)
    }

    func testATimePickedWhileAnotherPhoneHandsTheBedJetOverWins() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        let unit = unit(mine.id)
        // Cool is tapped, and a time picked while the BedJet is still taking it.
        unit.holdsSends = true
        let choosing = Task { await bedJet.setMode(.cool) }
        try await waitUntil("Cool to be in flight") { unit.heldCount == 1 }
        let picking = Task { await bedJet.setRuntime(minutes: 180) }
        try await waitUntil("the time to be in flight") { unit.heldCount == 2 }
        unit.releaseFirst()
        try await waitUntil("the BedJet to take Cool") { unit.lastStatus?.mode == .cool }
        unit.release()
        await picking.value
        await choosing.value

        XCTAssertEqual(try remainingSeconds(unit), 180 * 60, accuracy: 15)
    }

    func testHeatStillGetsBackItsTimeWhenAChangeIsReplaced() async throws {
        // Heat at 40 °C with the gentlest fan was chosen on the Remote, and 150 of its 4 hours are left.
        let unit = unit(mine.id)
        try await unit.device.send(XCTUnwrap(Codec.setMode(.heat)))
        try await unit.device.send(Codec.setTemperature(80))
        try await unit.device.send(Codec.setFan(step: 0))
        try await unit.device.send(Codec.setRuntime(minutes: 150))
        let ends = Date().addingTimeInterval(150 * 60)
        let model = try launch(longestEnds: ends, timerEnds: ends)
        let bedJet = try await connected(mine, in: model)

        // A stronger fan cuts Heat to 2 hours, and a newer slider move replaces it before Lull reads the timer back.
        let stronger = Task { await bedJet.setFan(percent: 40) }
        try await waitUntil("the stronger fan to be sent") { unit.sent.contains(Codec.setFan(step: 7)) }
        stronger.cancel()
        await stronger.value
        XCTAssertEqual(try remainingSeconds(unit), 120 * 60, accuracy: 15)

        let sent = unit.sent.count
        await bedJet.setFan(percent: 5)
        XCTAssertEqual(timerCommands(unit, after: sent), [Codec.setRuntime(minutes: 150)])
        XCTAssertEqual(try remainingSeconds(unit), 150 * 60, accuracy: 15)
    }

    func testTurningTheAirOffForgetsTheRemotesTimer() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.setMode(.cool)
        XCTAssertNotNil(bedJet.saved.longestEnds)

        await bedJet.setMode(.standby)
        XCTAssertNil(bedJet.saved.longestEnds)
        XCTAssertNil(bedJet.saved.timerEnds)
    }

    func testADamagedSaveNeverSetsTheTimer() async throws {
        let unit = unit(mine.id)
        try await unit.device.send(XCTUnwrap(Codec.setMode(.cool)))
        try await unit.device.send(Codec.setRuntime(minutes: 675))
        let model = try launch(longestEnds: .distantPast, timerEnds: Date().addingTimeInterval(675 * 60))
        let bedJet = try await connected(mine, in: model)

        await bedJet.setFan(percent: 50)
        XCTAssertEqual(timerCommands(unit), [])
        XCTAssertEqual(try remainingSeconds(unit), 675 * 60, accuracy: 15)
    }

    // MARK: Turning off

    func testTurningOffStopsShowingTheBedJetBeforeItReports() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.setMode(.heat)
        XCTAssertNotNil(LiveStatus.of(run: bedJet.run, status: bedJet.status, reportedAt: bedJet.statusReceivedAt,
                                      generation: bedJet.generation))

        // Lull lets go of the BedJet once it leaves the screen, which can be before the BedJet says it's off. It took
        // the command, so it's no longer shown as running.
        unit(mine.id).reports = false
        await bedJet.setMode(.standby)
        XCTAssertEqual(bedJet.status?.mode, .standby)
        XCTAssertNil(LiveStatus.of(run: bedJet.run, status: bedJet.status, reportedAt: bedJet.statusReceivedAt,
                                   generation: bedJet.generation))
    }

    func testAReportSentBeforeOffDoesntTurnTheBedJetBackOn() async throws {
        let window = BedJet.staleReportWindow
        BedJet.staleReportWindow = 0.5
        defer { BedJet.staleReportWindow = window }
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.setMode(.cool)
        let running = try XCTUnwrap(bedJet.status)
        XCTAssertTrue(running.isRunning)

        // The BedJet reports every second, so one it sent while still cooling can arrive just after it took Off.
        let link = unit(mine.id)
        link.reports = false
        let off = Task { await bedJet.setMode(.standby) }
        try await waitUntil("Off to be taken") { bedJet.status?.mode == .standby }
        link.onStatus?(running)
        XCTAssertEqual(bedJet.status?.mode, .standby)
        XCTAssertNil(LiveStatus.of(run: bedJet.run, status: bedJet.status, reportedAt: bedJet.statusReceivedAt,
                                   generation: bedJet.generation))

        // Later, a report that it's running is believed: someone turned it on again.
        await off.value
        try await Task.sleep(nanoseconds: 600_000_000)
        link.onStatus?(running)
        XCTAssertEqual(bedJet.status?.mode, .cool)
    }

    // MARK: From outside the app

    func testAQuickRemoteButtonSetsItsBedJetOnceItConnects() async throws {
        let model = launch()
        model.choose(mine)
        model.choose(partners)
        let first = try bedJet(mine, in: model)
        // Tapped as Lull opens, before that BedJet has connected: it's shown, and set once it says what it's doing.
        await model.applyQuickRemote(RemotePreset(mode: .heat, minutes: 45, temperature: 70, fanPercent: 30), to: first.id)
        XCTAssertEqual(model.bedJet?.id, first.id)

        let link = unit(mine.id), sent = link.sent
        let mode = try XCTUnwrap(sent.firstIndex(of: XCTUnwrap(Codec.setMode(.heat))))
        let temperature = try XCTUnwrap(sent.firstIndex(of: Codec.setTemperature(70)))
        let fan = try XCTUnwrap(sent.firstIndex(of: Codec.setFan(step: 5)))
        XCTAssertLessThan(mode, temperature)
        XCTAssertLessThan(temperature, fan)
        // Last, the time, which the mode, temperature and fan each stretched to the longest Heat allows.
        XCTAssertEqual(timerCommands(link).last, Codec.setRuntime(minutes: 45))
        XCTAssertGreaterThan(try XCTUnwrap(sent.lastIndex(of: Codec.setRuntime(minutes: 45))), fan)
        XCTAssertEqual(link.lastStatus?.mode, .heat)
        XCTAssertEqual(link.lastStatus?.targetTemperature, 70)
        XCTAssertEqual(try remainingSeconds(link), 45 * 60, accuracy: 15)
        // The other BedJet is left as it was.
        XCTAssertFalse(unit(partners.id).sent.contains(try XCTUnwrap(Codec.setMode(.heat))))
    }

    func testAWidgetNeverTakesABedJetFromAnotherPhone() async throws {
        // The partner's phone has their BedJet, which this phone knows too.
        setups[partners.id] = { $0.heldElsewhere = true }
        let model = launch()
        model.choose(partners)
        model.choose(mine)
        _ = try await connected(mine, in: model)
        let theirs = try bedJet(partners, in: model)
        XCTAssertEqual(theirs.linkState, .yielded)

        // A widget set to their side opens Lull on it, but no button on it asks their phone for it.
        await model.applyQuickRemote(RemotePreset.defaults[2], to: theirs.id)
        XCTAssertEqual(model.bedJet?.id, theirs.id)
        await model.startNight(on: theirs.id)
        XCTAssertEqual(unit(partners.id).requests, 0)
        XCTAssertEqual(unit(partners.id).sent, [])
        XCTAssertNil(theirs.run)

        // Touching a control in Lull still takes it over, as the Remote says it will.
        await theirs.setMode(.heat)
        XCTAssertEqual(unit(partners.id).requests, 1)
        XCTAssertTrue(unit(partners.id).sent.contains(try XCTUnwrap(Codec.setMode(.heat))))
    }

    func testAWidgetLeavesAScheduleFromAnotherPhoneRunning() async throws {
        // The partner's phone started a schedule on this BedJet, then let go of it, as Lull does off screen.
        let theirs = StoredProgram(name: "Theirs", steps: [ProgramStep(mode: .cool, fanStep: 7, temperature: 40, hours: 2, minutes: 0)])
        setups[mine.id] = { $0.device = DemoLink(name: "BEDJET_3", slots: [theirs, nil, nil]) }
        try await unit(mine.id).device.startProgram(slot: 0)
        XCTAssertGreaterThan(unit(mine.id).device.programStep, 0)
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        XCTAssertNotNil(bedJet.runningElsewhereStep)

        // Neither Off on a Quick Remote nor Start Night ends it.
        let sent = unit(mine.id).sent
        await model.applyQuickRemote(RemotePreset.defaults[2], to: nil)
        await model.startNight(on: bedJet.id)
        XCTAssertEqual(unit(mine.id).sent, sent)
        XCTAssertGreaterThan(unit(mine.id).device.programStep, 0)
        XCTAssertNil(bedJet.run)
    }

    func testAQuickRemoteButtonDoesNothingItCant() async throws {
        setups[mine.id] = { $0.generation = .v2 }
        let model = launch()
        model.choose(partners)
        model.choose(mine)
        let first = try await connected(mine, in: model)
        // What Lull reads from a BedJet on connecting is done.
        try await Task.sleep(nanoseconds: 2_000_000_000)
        let sent = unit(mine.id).sent

        // Dry, which a V2 lacks, and a BedJet forgotten since, or never known.
        await model.applyQuickRemote(RemotePreset(mode: .dry, minutes: 45, temperature: 60, fanPercent: 60), to: first.id)
        await model.applyQuickRemote(RemotePreset.defaults[0], to: UUID())
        XCTAssertEqual(unit(mine.id).sent, sent)
        XCTAssertEqual(model.bedJet?.id, first.id)
        XCTAssertFalse(unit(partners.id).sent.contains(try XCTUnwrap(Codec.setMode(.cool))))
    }

    // MARK: Actions from Shortcuts

    /// Lull in the background with a BedJet it was using, as when an automation runs at bedtime.
    private func backgrounded(_ unit: NearbyBedJet) async throws -> (AppModel, BedJet) {
        let model = launch()
        model.choose(unit)
        let bedJet = try await connected(unit, in: model)
        model.appMovedToBackground()
        try await waitUntil("\(unit.name) to be let go") { !bedJet.isConnected }
        return (model, bedJet)
    }

    private func failure(_ action: () async throws -> String) async -> String? {
        do {
            _ = try await action()
            XCTFail("The action should have failed")
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func testALaunchOffScreenConnectsToNothing() async throws {
        let model = launch()
        model.choose(mine)
        _ = try await connected(mine, in: model)
        model.appMovedToBackground()

        units = [:]
        let relaunched = launch(onScreen: false)
        XCTAssertEqual(relaunched.bedJet?.unit, mine.id)
        XCTAssertFalse(relaunched.status(of: try XCTUnwrap(relaunched.bedJet)).nightRunning)
        // Its link is set up for later, but nothing connects, and no other phone is asked for the BedJet.
        XCTAssertEqual(unit(mine.id).starts + unit(mine.id).listens + unit(mine.id).requests, 0)
    }

    func testAnActionWaitsOutTheBedJetsWiFi() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        let joining = Task { await bedJet.joinWiFi(network: "Home", password: "password1") }
        try await waitUntil("the BedJet to start on its Wi-Fi") { bedJet.wifiActivity?.isRunning == true }
        let message = await failure { try await model.perform(.lightRing(on: false), on: bedJet) }
        XCTAssertEqual(message, AutomationError.updating.errorDescription)
        await joining.value
        XCTAssertEqual(bedJet.wifiActivity?.failed, false)
    }

    /// Lull in the background with a BedJet that began installing an update, as after updating it at bedtime.
    private func installingAnUpdate() async throws -> (AppModel, BedJet) {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.joinWiFi(network: "Home", password: "password1")
        unit(mine.id).device.offerFirmwareUpdate()
        await bedJet.updateFirmware()
        XCTAssertEqual(bedJet.wifiActivity?.awaitsRestart, true)
        model.appMovedToBackground()
        try await waitUntil("\(mine.name) to be let go") { !bedJet.isConnected }
        return (model, bedJet)
    }

    func testAnActionIsRefusedWhileTheBedJetInstallsAnUpdate() async throws {
        let (model, bedJet) = try await installingAnUpdate()
        let listens = unit(mine.id).listens
        let message = await failure { try await model.perform(.air(AirSettings(mode: .heat)), on: bedJet) }
        XCTAssertEqual(message, AutomationError.updating.errorDescription)
        // It doesn't wait to reach a BedJet that is installing, or offer to go on in Lull.
        XCTAssertEqual(unit(mine.id).listens, listens)
    }

    func testAnActionGoesAheadOnceTheBedJetShouldBeBackFromAnUpdate() async throws {
        let (model, bedJet) = try await installingAnUpdate()
        // As when Lull wakes for the action long after the install, before its own timer has caught up.
        let patience = BedJet.restartPatience
        BedJet.restartPatience = 0
        defer { BedJet.restartPatience = patience }
        let done = try await model.perform(.air(AirSettings(mode: .heat)), on: bedJet)
        XCTAssertEqual(done, "Turned on Heat on BEDJET_3.")
    }

    func testTheBedJetStaysBusyWhileAnyCommandIsUnderWay() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        unit(mine.id).holdsSends = true
        let lightRing = Task { await bedJet.setLED(false) }
        try await waitUntil("the light ring to be on its way") { unit(mine.id).isHolding }
        // An action that sends nothing finishes while the light ring is still on its way, which keeps the BedJet busy.
        let done = try await model.perform(.turnOff, on: bedJet)
        XCTAssertEqual(done, "BEDJET_3 is already off.")
        XCTAssertTrue(bedJet.isBusy)
        unit(mine.id).release()
        await lightRing.value
        XCTAssertFalse(bedJet.isBusy)
    }

    func testStartNightSetToADeletedScheduleSaysSo() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        let message = await failure { try await model.perform(.startNight(schedule: UUID()), on: bedJet) }
        XCTAssertEqual(message, AutomationError.forgottenSchedule.errorDescription)
    }

    func testAnActionSetToAForgottenBedJetSaysSo() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        model.forget(bedJet)
        XCTAssertThrowsError(try model.automationTarget(bedJet.id)) { error in
            XCTAssertEqual(error as? AutomationError, .forgottenBedJet)
        }
        // Lull never picks another in its place.
        XCTAssertThrowsError(try model.automationTarget(nil)) { error in
            XCTAssertEqual(error as? AutomationError, .noBedJet)
        }
    }

    func testAnActionOffScreenReachesAFreeBedJetAndLetsGo() async throws {
        let model = launch()
        model.choose(mine)
        _ = try await connected(mine, in: model)
        model.appMovedToBackground()

        units = [:]
        let relaunched = launch(onScreen: false)
        let target = try relaunched.automationTarget(nil)
        let done = try await relaunched.perform(.startNight(schedule: nil), on: target)
        XCTAssertEqual(done, "Started “Cool Drift” on BEDJET_3.")
        XCTAssertNotNil(target.run)
        XCTAssertTrue(relaunched.status(of: target).nightRunning)
        // It never asked another phone for the BedJet, and let go of it once done.
        XCTAssertEqual(unit(mine.id).starts, 0)
        XCTAssertEqual(unit(mine.id).requests, 0)
        XCTAssertGreaterThan(unit(mine.id).listens, 0)
        XCTAssertGreaterThan(unit(mine.id).stops, 0)
        XCTAssertFalse(target.isConnected)
        XCTAssertNil(relaunched.errorMessage)
    }

    func testAnActionOffScreenGivesUpOnABedJetThatIsntFree() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        let sentBefore = unit(mine.id).sent
        unit(mine.id).answers = false
        model.reachPatience = 1
        do {
            _ = try await model.perform(.air(AirSettings(mode: .heat)), on: bedJet)
            XCTFail("The action should have asked to go on in Lull")
        } catch {
            XCTAssertEqual(error as? AutomationError, .needsLull)
        }
        XCTAssertEqual(unit(mine.id).sent, sentBefore)
        XCTAssertEqual(unit(mine.id).requests, 0)
        XCTAssertNil(model.errorMessage)
    }

    func testAnActionNeverAsksForABedJetBackThatWentToAnotherPhone() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        let link = unit(mine.id)
        let sentBefore = link.sent
        // Another phone takes the BedJet while the action waits for its first report.
        link.reports = false
        let action = Task { try await model.perform(.lightRing(on: true), on: bedJet) }
        try await waitUntil("the BedJet to connect") { bedJet.isConnected }
        try await Task.sleep(nanoseconds: 300_000_000)
        link.heldElsewhere = true
        link.onStateChange?(.yielded)
        link.reports = true

        let message = await failure { try await action.value }
        XCTAssertNotNil(message)
        // It reached the BedJet first, so it fails in the wait rather than by finding the BedJet already taken.
        XCTAssertNotEqual(message, AutomationError.needsLull.errorDescription)
        XCTAssertEqual(link.sent, sentBefore)
        XCTAssertEqual(link.requests, 0)
    }

    func testACancelledActionStopsWaitingForTheBedJetAtOnce() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        let link = unit(mine.id)
        link.reports = false
        let action = Task { try await model.perform(.lightRing(on: true), on: bedJet) }
        try await waitUntil("the BedJet to connect") { bedJet.isConnected }
        try await Task.sleep(nanoseconds: 300_000_000)

        let cancelled = Date()
        action.cancel()
        do {
            _ = try await action.value
            XCTFail("The action should have been cancelled")
        } catch {
            XCTAssertTrue(error is CancellationError, "\(error)")
        }
        // Not left polling until the wait for the report would have run out.
        XCTAssertLessThan(Date().timeIntervalSince(cancelled), 1)
    }

    func testAnActionOutOfBackgroundTimeDuringItsCommandSendsNothingMore() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        let link = unit(mine.id)
        link.holdsSends = true
        let action = Task { try await model.perform(.startNight(schedule: nil), on: bedJet) }
        try await waitUntil("tonight to be on its way to the BedJet") { link.isHolding }
        // iOS runs out of background time while the BedJet is being given tonight.
        BackgroundTime.runOut()
        // Lull is next opened, the BedJet connects again, and only then does the command in flight finish.
        model.appBecameActive()
        try await waitUntil("the BedJet to connect again") { bedJet.isConnected }
        link.release()

        do {
            _ = try await action.value
            XCTFail("The action should have been cancelled")
        } catch {
            XCTAssertTrue(error is CancellationError, "\(error)")
        }
        // Tonight was never started, long after Shortcuts gave up on it.
        XCTAssertNil(bedJet.run)
        XCTAssertEqual(link.device.programStep, 0)
    }

    func testAnAirActionOutOfBackgroundTimeSendsNothingAfterTheCommandInFlight() async throws {
        let model = launch()
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        // The air is on with a time picked on the Remote, so nothing waits between the action's commands.
        await bedJet.setMode(.heat)
        await bedJet.setRuntime(minutes: 60)
        model.appMovedToBackground()
        try await waitUntil("\(mine.name) to be let go") { !bedJet.isConnected }
        let link = unit(mine.id)
        link.holdsSends = true
        let action = Task { try await model.perform(.air(AirSettings(degrees: 30, fanPercent: 60)), on: bedJet) }
        try await waitUntil("the temperature to be on its way to the BedJet") { link.isHolding }
        let sent = link.sent.count
        // iOS runs out of background time while the temperature is on its way.
        BackgroundTime.runOut()
        // Lull is next opened, the BedJet connects again, and only then does the command in flight finish.
        model.appBecameActive()
        try await waitUntil("the BedJet to connect again") { bedJet.isConnected }
        link.release()

        do {
            _ = try await action.value
            XCTFail("The action should have been cancelled")
        } catch {
            XCTAssertTrue(error is CancellationError, "\(error)")
        }
        // Only the temperature in flight went out: the fan never did, long after Shortcuts gave up on it.
        XCTAssertEqual(link.sent.count, sent + 1)
        XCTAssertNotEqual(link.lastStatus?.fanPercent, 60)
    }

    func testComingOnScreenDuringAnActionKeepsTheBedJet() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        let stopsBefore = unit(mine.id).stops
        unit(mine.id).holdsSends = true
        let action = Task { try await model.perform(.lightRing(on: false), on: bedJet) }
        try await waitUntil("the action to be in flight") { unit(mine.id).isHolding }
        model.appBecameActive()
        unit(mine.id).release()
        let done = try await action.value
        XCTAssertEqual(done, "Turned off the light ring on BEDJET_3.")
        XCTAssertTrue(bedJet.isConnected)
        XCTAssertEqual(unit(mine.id).stops, stopsBefore)
    }

    func testStartNightLeavesARunningNightAlone() async throws {
        let model = launch()
        model.choose(mine)
        let first = try await connected(mine, in: model)
        await first.startTonight()
        model.appMovedToBackground()

        let sentBefore = unit(mine.id).sent
        let done = try await model.perform(.startNight(schedule: model.schedules[2].id), on: first)
        XCTAssertEqual(done, "“Cool Drift” is already running on BEDJET_3.")
        XCTAssertEqual(unit(mine.id).sent, sentBefore)
        XCTAssertEqual(first.run?.schedule.name, "Cool Drift")
    }

    func testFullSlotsAreAnErrorForAnAction() async throws {
        let theirs = StoredProgram(name: "Theirs", steps: [ProgramStep(mode: .cool, fanStep: 7, temperature: 40, hours: 2, minutes: 0)])
        setups[mine.id] = { $0.device = DemoLink(name: "BEDJET_3", slots: [theirs, theirs, theirs]) }
        let (model, bedJet) = try await backgrounded(mine)
        let message = await failure { try await model.perform(.startNight(schedule: nil), on: bedJet) }
        XCTAssertEqual(message, AutomationError.slotsFull.errorDescription)
        // Nobody is there to pick a slot, so none is asked for or replaced.
        XCTAssertFalse(bedJet.needsSlotChoice)
        XCTAssertNil(bedJet.run)
        XCTAssertEqual(unit(mine.id).device.slots, [theirs, theirs, theirs])
        XCTAssertNil(model.errorMessage)
    }

    func testFullSlotsAreAskedAboutOnceAnActionGoesOnInLull() async throws {
        let theirs = StoredProgram(name: "Theirs", steps: [ProgramStep(mode: .cool, fanStep: 7, temperature: 40, hours: 2, minutes: 0)])
        setups[mine.id] = { $0.device = DemoLink(name: "BEDJET_3", slots: [theirs, theirs, theirs]) }
        let (model, bedJet) = try await backgrounded(mine)
        let done = try await model.perform(.startNight(schedule: nil), on: bedJet, mayAsk: true)
        XCTAssertEqual(done, "Your BedJet's three slots are full. Pick one to reuse in Lull.")
        XCTAssertTrue(bedJet.needsSlotChoice)
        XCTAssertNil(bedJet.run)
    }

    func testActionsOneAfterAnotherEachReachTheBedJet() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        // Over Bluetooth, the connection one action lets go of takes a moment to drop, while the next starts at once.
        unit(mine.id).dropsSlowly = true
        _ = try await model.perform(.air(AirSettings(mode: .heat)), on: bedJet)
        let fan = try await model.perform(.air(AirSettings(fanPercent: 70)), on: bedJet)
        XCTAssertEqual(fan, "Set the fan on BEDJET_3 to 70%.")
        let runTime = try await model.perform(.air(AirSettings(minutes: 55)), on: bedJet)
        XCTAssertEqual(runTime, "BEDJET_3 turns off in 55 min.")
        XCTAssertTrue(unit(mine.id).sent.contains(Codec.setFan(step: 13)))
    }

    func testTurnOffEndsTheNightOrStopsTheAir() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        _ = try await model.perform(.startNight(schedule: nil), on: bedJet)
        var done = try await model.perform(.turnOff, on: bedJet)
        XCTAssertEqual(done, "Ended “Cool Drift” on BEDJET_3.")
        XCTAssertNil(bedJet.run)
        done = try await model.perform(.turnOff, on: bedJet)
        XCTAssertEqual(done, "BEDJET_3 is already off.")
        let airOff = await failure { try await model.perform(.air(AirSettings(fanPercent: 50)), on: bedJet) }
        XCTAssertEqual(airOff, AutomationError.airOff.errorDescription)

        done = try await model.perform(.air(AirSettings(mode: .cool)), on: bedJet)
        XCTAssertEqual(done, "Turned on Cool on BEDJET_3.")
        done = try await model.perform(.air(AirSettings(fanPercent: 52)), on: bedJet)
        XCTAssertEqual(done, "Set the fan on BEDJET_3 to 50%.")
        XCTAssertTrue(unit(mine.id).sent.contains(Codec.setFan(step: 9)))
        done = try await model.perform(.turnOff, on: bedJet)
        XCTAssertEqual(done, "Turned off BEDJET_3.")
        XCTAssertTrue(unit(mine.id).sent.contains(try XCTUnwrap(Codec.setMode(.standby))))
        XCTAssertNil(model.errorMessage)
    }

    func testAV2RefusesAModeItCantRunFromAnAction() async throws {
        setups[mine.id] = { $0.generation = .v2 }
        let (model, bedJet) = try await backgrounded(mine)
        let sentBefore = unit(mine.id).sent
        let message = await failure { try await model.perform(.air(AirSettings(mode: .dry)), on: bedJet) }
        XCTAssertEqual(message, "BEDJET_3 is a BedJet V2, which can't run Dry.")
        XCTAssertEqual(unit(mine.id).sent, sentBefore)
    }

    func testTurnOnRunsAsLongAsAllowedRatherThanToATimePickedEarlier() async throws {
        let model = launch()
        model.remoteTimerShowsTime = true
        model.choose(mine)
        let bedJet = try await connected(mine, in: model)
        await bedJet.setMode(.cool)
        await bedJet.setRuntime(until: Date().addingTimeInterval(30 * 60))
        XCTAssertTrue(bedJet.remoteTimer.isAhead)
        model.appMovedToBackground()
        try await waitUntil("\(mine.name) to be let go") { !bedJet.isConnected }

        _ = try await model.perform(.air(AirSettings(mode: .heat)), on: bedJet)
        XCTAssertEqual(bedJet.remoteTimer, .longest)
    }

    func testTheRunTimeIsLeftToARunningSchedule() async throws {
        let (model, bedJet) = try await backgrounded(mine)
        _ = try await model.perform(.startNight(schedule: nil), on: bedJet)
        let sentBefore = unit(mine.id).sent
        let message = await failure { try await model.perform(.air(AirSettings(minutes: 30)), on: bedJet) }
        XCTAssertEqual(message, AutomationError.scheduleSetsTiming.errorDescription)
        XCTAssertEqual(unit(mine.id).sent, sentBefore)
        XCTAssertNotNil(bedJet.run)
        XCTAssertNil(model.errorMessage)
    }
}
