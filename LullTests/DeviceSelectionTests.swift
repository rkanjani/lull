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
        directory = FileManager.default.temporaryDirectory.appending(path: "DeviceSelectionTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defaults = UserDefaults(suiteName: "DeviceSelectionTests-\(UUID().uuidString)")
        discovery = DemoDiscovery(units: [mine, partners, guest])
        units = [:]
        setups = [:]
    }

    override func tearDown() async throws {
        for unit in units.values { unit.stop() }
        try? FileManager.default.removeItem(at: directory)
    }

    private func launch(demo: Bool = false) -> AppModel {
        AppModel(storage: Storage(directory: directory, defaults: defaults), demoMode: demo,
                 makeLink: { [unowned self] bedJet in self.unit(bedJet.unit!) },
                 discovery: { [unowned self] _ in self.discovery })
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
}

/// A BedJet for tests. It speaks like the simulated one, but connects only when allowed, records every command,
/// can hold a command in flight, and keeps its state between connections like a real unit.
@MainActor
final class FakeLink: BedJetLink {
    var device: DemoLink { didSet { attach() } }
    var generation: Generation = .v3
    var answers = true
    var holdsSends = false
    private(set) var sent: [Data] = []
    private(set) var stops = 0
    private var held: [CheckedContinuation<Void, Never>] = []
    var isHolding: Bool { !held.isEmpty }

    var onStateChange: ((LinkState) -> Void)?
    var onStatus: ((DeviceStatus) -> Void)?
    var state: LinkState { device.state }
    var lastStatus: DeviceStatus? { device.lastStatus }

    init(name: String) {
        device = DemoLink(name: name)
        attach()
    }

    private func attach() {
        device.onStateChange = { [weak self] in self?.onStateChange?($0) }
        device.onStatus = { [weak self] in self?.onStatus?($0) }
    }

    func start() {
        device.answers = answers
        device.start()
    }

    func stop() {
        stops += 1
        device.stop()
    }

    func requestControl() { start() }

    func send(_ command: Data) async throws {
        if holdsSends { await withCheckedContinuation { held.append($0) } }
        guard state == .ready else { throw BedJetError.notConnected }
        sent.append(command)
        try await device.send(command)
    }

    func query(_ request: UInt8) async throws -> Data {
        guard state == .ready else { throw BedJetError.notConnected }
        return try await device.query(request)
    }

    func release() {
        holdsSends = false
        let waiting = held
        held = []
        for continuation in waiting { continuation.resume() }
    }

    /// A status report, however late it arrives.
    func report(_ status: DeviceStatus) { onStatus?(status) }
}
