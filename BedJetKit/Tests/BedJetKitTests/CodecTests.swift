import XCTest
@testable import BedJetKit

/// Byte fixtures below were captured from a BedJet 3 running firmware V1.20.
final class CodecTests: XCTestCase {
    private func data(_ hex: String) -> Data {
        Data(hex.split(separator: " ").map { UInt8($0, radix: 16)! })
    }

    func testParsesStatusCapturedFromDevice() throws {
        let packet = data("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 35 00 01 53")
        let status = try XCTUnwrap(Codec.parseStatus(packet))
        XCTAssertEqual(status.mode, .standby)
        XCTAssertEqual(status.actualTemperature, 46)
        XCTAssertEqual(status.targetTemperature, 79)
        XCTAssertEqual(status.fanPercent, 100)
        XCTAssertEqual(status.ambientTemperature, 45)
        XCTAssertEqual(status.extra?.programStep, 0)
        XCTAssertEqual(status.extra?.ledEnabled, true)
        XCTAssertEqual(status.extra?.beepsMuted, true)
        XCTAssertEqual(status.lastShutdownReason, 0)
        XCTAssertEqual(status.extra?.notification, Codec.Notification.cleanFilter)
        // The result of an earlier connection test, kept until the unit next uses its Wi-Fi.
        XCTAssertEqual(status.extra?.wifiPhase, Codec.WiFiPhase.cannotJoin)
    }

    func testFirstChunkAloneStillParses() throws {
        let head = data("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12")
        XCTAssertTrue(Codec.isStatusStart(head))
        XCTAssertNil(try XCTUnwrap(Codec.parseStatus(head)).extra)
    }

    func testParsesProgramCapturedFromDevice() throws {
        let reply = data("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        let fragment = try XCTUnwrap(Codec.parseFragment(reply.dropFirst(2), index: 0))
        XCTAssertEqual(fragment.total, 4)
        XCTAssertEqual(fragment.steps, [
            ProgramStep(mode: .cool, fanStep: 9, temperature: 44, hours: 1, minutes: 0),
            ProgramStep(mode: .cool, fanStep: 13, temperature: 49, hours: 3, minutes: 0),
            ProgramStep(mode: .cool, fanStep: 9, temperature: 46, hours: 1, minutes: 15),
            ProgramStep(mode: .heat, fanStep: 4, temperature: 47, hours: 1, minutes: 0),
        ])
    }

    func testWritingReproducesTheBytesTheDeviceReturned() {
        let reply = data("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        let steps = Codec.parseFragment(reply.dropFirst(2), index: 0)!.steps
        let commands = Codec.writeProgram(slot: 0, steps: steps)
        XCTAssertEqual(commands.count, 1)
        XCTAssertEqual(commands[0].prefix(2), Data([0x40, 0x07]))
        XCTAssertEqual(commands[0].dropFirst(2), reply.dropFirst(2))
    }

    func testParsesNamesCapturedFromDevice() {
        let reply = data("04 05 63 68 6f 74 65 20 6d 6f 74 65 7a 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 73 6c 65 65 65 65 70 00 00 00 00 00 00 00 00 00")
        XCTAssertEqual(Codec.parseNames(reply.dropFirst(2)), ["chote motez", nil, "sleeeep"])
    }

    func testLongProgramsSplitIntoFragments() {
        let steps = (0..<19).map { ProgramStep(mode: .cool, fanStep: $0, temperature: 40, hours: 0, minutes: 30) }
        let commands = Codec.writeProgram(slot: 2, steps: steps)
        XCTAssertEqual(commands.map { $0[1] }, [15, 16, 17])
        XCTAssertTrue(commands.allSatisfy { $0.count == 43 && $0[2] == 19 })
        XCTAssertEqual(Codec.parseFragment(commands[2].dropFirst(2), index: 2)?.steps, Array(steps[16...]))
    }

    func testClockTimeFlagSharesTheHoursByte() {
        let step = ProgramStep(mode: .cool, fanStep: 14, temperature: 36, hours: 6, minutes: 30, endsAtClockTime: true)
        let command = Codec.writeProgram(slot: 0, steps: [step])[0]
        XCTAssertEqual([UInt8](command[3..<8]), [0x04, 0x0e, 0x24, 0x86, 0x1e])
        XCTAssertEqual(Codec.parseFragment(command.dropFirst(2), index: 0)?.steps, [step])
    }

    func testNameCommandIsPaddedAndTrimmed() {
        let command = Codec.nameProgram(slot: 1, name: "A very long schedule name ✨")
        XCTAssertEqual(command.count, 19)
        XCTAssertEqual([UInt8](command.prefix(3)), [0x40, 0x05, 0x10])
        XCTAssertEqual(Codec.parseText(command.dropFirst(3)), "A very long sch")
    }

    @MainActor
    func testHandOverRequestsAreSpecificToOneBedJet() {
        let mine = BLELink.handoffUUID(forDeviceNamed: "BEDJET-3B04")
        XCTAssertEqual(mine, BLELink.handoffUUID(forDeviceNamed: "BEDJET-3B04"))
        XCTAssertNotEqual(mine, BLELink.handoffUUID(forDeviceNamed: "BEDJET-3B05"))
        XCTAssertEqual(mine.data.count, 16)
    }

    @MainActor
    func testSimulatedBedJetsGoByTheirOwnNames() async throws {
        let first = try await DemoLink().deviceName()
        let second = try await DemoLink(name: "BEDJET-DEMO-2").deviceName()
        XCTAssertEqual(first, DemoLink.defaultName)
        XCTAssertEqual(second, "BEDJET-DEMO-2")
    }

    func testRuntimeCommandSplitsHoursAndMinutes() {
        XCTAssertEqual([UInt8](Codec.setRuntime(minutes: 150)), [0x02, 2, 30])
    }

    @MainActor
    func testRuntimeChangesTheShutOffTimeAndStopsAtTheModeLimit() async throws {
        let link = DemoLink()
        var latest: DeviceStatus?
        link.onStatus = { latest = $0 }
        try await link.setMode(.heat)
        XCTAssertEqual(latest?.remainingSeconds, 60 * 60)
        try await link.send(Codec.setRuntime(minutes: 150))
        XCTAssertEqual(latest?.remainingSeconds, 150 * 60)
        try await link.send(Codec.setRuntime(minutes: 600))
        XCTAssertEqual(latest?.remainingSeconds, 240 * 60)
    }

    @MainActor
    func testStoreAndReadBackThroughSimulatedDevice() async throws {
        let link = DemoLink(slots: [StoredProgram(name: "old", steps: [ProgramStep(mode: .dry, fanStep: 1, temperature: 60, hours: 1, minutes: 0)]), nil, nil])
        let steps = (0..<11).map { ProgramStep(mode: $0 % 2 == 0 ? .cool : .heat, fanStep: $0, temperature: 50, hours: 0, minutes: 20 + $0) }
        try await link.store(StoredProgram(name: "Deep Sleep", steps: steps), slot: 1)
        let stored = try await link.storedPrograms()
        XCTAssertEqual(stored[0]?.name, "old")
        XCTAssertEqual(stored[1], StoredProgram(name: "Deep Sleep", steps: steps))
        XCTAssertNil(stored[2])
    }

    @MainActor
    func testStartingAProgramSyncsTheClockAndRunsTheFirstStep() async throws {
        let link = DemoLink()
        var latest: DeviceStatus?
        link.onStatus = { latest = $0 }
        try await link.store(StoredProgram(name: "Night", steps: [ProgramStep(mode: .cool, fanStep: 5, temperature: 44, hours: 2, minutes: 0)]), slot: 0)
        try await link.startProgram(slot: 0)
        XCTAssertNotNil(link.clock)
        XCTAssertEqual(latest?.mode, .cool)
        XCTAssertEqual(latest?.fanStep, 5)
        XCTAssertEqual(latest?.extra?.programStep, 1)
    }

    /// Values follow the heat table in the official BedJet app (v1.0.6), which keeps the unit's safety cut-off.
    func testHeatIsCutOffSoonerWhenHotterAndFaster() {
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .heat, temperature: 80, fanStep: 19), 60)
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .heat, temperature: 75, fanStep: 19), 120)
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .heat, temperature: 80, fanStep: 0), 240)
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .heat, temperature: 81, fanStep: 0), 60)
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .heat, temperature: 66, fanStep: 19), Limits.maxStepMinutes)
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .heat, temperature: 50, fanStep: 19), Limits.maxStepMinutes)
        XCTAssertEqual(ProgramStep.longestMinutes(mode: .cool, temperature: 80, fanStep: 19), Limits.maxStepMinutes)
        XCTAssertFalse(ProgramStep(mode: .heat, fanStep: 19, temperature: 80, hours: 2, minutes: 0).fitsUnitLimits)
        XCTAssertTrue(ProgramStep(mode: .heat, fanStep: 19, temperature: 80, hours: 1, minutes: 0).fitsUnitLimits)
        XCTAssertTrue(ProgramStep(mode: .heat, fanStep: 19, temperature: 80, hours: 6, minutes: 0, endsAtClockTime: true).fitsUnitLimits)
    }

    @MainActor
    func testProgramsPastTheHeatCutOffAreNotSaved() async {
        let link = DemoLink()
        let program = StoredProgram(name: "too hot", steps: [ProgramStep(mode: .heat, fanStep: 19, temperature: 80, hours: 3, minutes: 0)])
        do {
            try await link.store(program, slot: 0)
            XCTFail("stored a step the unit would cut off")
        } catch {
            XCTAssertEqual(error as? BedJetError, .programTooLong)
        }
        XCTAssertNil(link.slots[0])
    }

    @MainActor
    func testUnitRefusesAProgramItCannotFinishUntilAcknowledged() async throws {
        let tooHot = ProgramStep(mode: .heat, fanStep: 19, temperature: 80, hours: 3, minutes: 0)
        let link = DemoLink(slots: [StoredProgram(name: "too hot", steps: [tooHot]), nil, nil])
        var latest: DeviceStatus?
        link.onStatus = { latest = $0 }
        try await link.startProgram(slot: 0)
        XCTAssertEqual(latest?.mode, .standby)
        XCTAssertEqual(latest?.extra?.programStep, 0)
        XCTAssertEqual(latest?.extra?.notification, Codec.Notification.programTooLong)
        try await link.send(Codec.acknowledgeNotification)
        XCTAssertEqual(latest?.extra?.notification, 0)
    }

    // MARK: Wi-Fi and firmware

    /// `MAGIC_UPDATE` in the official app.
    func testCheckingForFirmwareIsTheOfficialAppsButton() {
        XCTAssertEqual(Codec.updateFirmware, Data([0x01, 0x43]))
    }

    func testWiFiDetailsMustSuitTheUnit() {
        XCTAssertNil(Codec.wifiProblem(network: "Home", password: "12345678"))
        XCTAssertNotNil(Codec.wifiProblem(network: "", password: "12345678"))
        XCTAssertNotNil(Codec.wifiProblem(network: String(repeating: "n", count: 33), password: "12345678"))
        XCTAssertNotNil(Codec.wifiProblem(network: "Home", password: "1234567"))
        XCTAssertNotNil(Codec.wifiProblem(network: "Home", password: String(repeating: "p", count: 64)))
    }

    @MainActor
    func testJoiningWiFiSendsTheNameThenPasswordAndFollowsTheTest() async throws {
        let link = ScriptedWiFiLink([21, 1, 2, 3, 4, 24])
        var steps: [Int] = []
        try await link.joinWiFi(network: "Home", password: "correct horse") { steps.append($0) }
        XCTAssertEqual(link.wifi?.network, Data("Home".utf8))
        XCTAssertEqual(link.wifi?.password, Data("correct horse".utf8))
        XCTAssertEqual(steps, [1, 2, 3, 4])
    }

    @MainActor
    func testAFailedJoinCountsEvenWhenItRepeatsTheLastResult() async {
        let link = ScriptedWiFiLink([21, 21, 1, 2, 21])
        do {
            try await link.joinWiFi(network: "Home", password: "wrong password")
            XCTFail("joined")
        } catch {
            XCTAssertEqual(error as? BedJetError, .wifi(Codec.WiFiPhase.cannotJoin))
        }
    }

    @MainActor
    func testAFailureThatComesAndGoesUnseenIsStillReported() async {
        // The unit tried and failed again between two reports, so all that's seen is the same failure.
        let link = ScriptedWiFiLink([21])
        do {
            try await link.joinWiFi(network: "Home", password: "wrong password")
            XCTFail("joined")
        } catch {
            XCTAssertEqual(error as? BedJetError, .wifi(Codec.WiFiPhase.cannotJoin))
        }
    }

    @MainActor
    func testAJoinThatLosesTheLinkWaitsPastTheResultLeftFromBefore() async {
        // Joined before; the link drops for a moment during this test, which doesn't restart the unit.
        let link = ScriptedWiFiLink([24, nil, 24, 21])
        var steps: [Int] = []
        do {
            try await link.joinWiFi(network: "Home", password: "wrong password") { steps.append($0) }
            XCTFail("joined")
        } catch {
            XCTAssertEqual(error as? BedJetError, .wifi(Codec.WiFiPhase.cannotJoin))
        }
        XCTAssertEqual(steps, [])
    }

    @MainActor
    func testAJoinGivesUpOnALinkThatStaysLost() async {
        // Unplugged partway through: nothing restarts the unit when it joins, so it isn't coming back by itself.
        let link = ScriptedWiFiLink([0, 1, nil])
        do {
            try await link.joinWiFi(network: "Home", password: "12345678")
            XCTFail("joined")
        } catch {
            XCTAssertEqual(error as? BedJetError, .notConnected)
        }
    }

    @MainActor
    func testAFirmwareCheckFindsItUpToDate() async throws {
        let link = ScriptedWiFiLink([21, 1, 2, 3, 5, 26])
        let found = try await link.updateFirmware()
        XCTAssertEqual(link.sent, [Codec.updateFirmware])
        XCTAssertEqual(found, .upToDate)
    }

    @MainActor
    func testAFirmwareCheckWaitsForTheBedJetToComeBackFromRestarting() async throws {
        // Asked to check, a BedJet 3 restarts first, dropping the link; the check carries on once it's back.
        let link = ScriptedWiFiLink([26, nil, 0, 1, 2, 3, 5, 26])
        var steps: [Int] = []
        let found = try await link.updateFirmware { steps.append($0) }
        XCTAssertEqual(found, .upToDate)
        XCTAssertEqual(steps, [Codec.WiFiPhase.restarting, 1, 2, 3, 5])
    }

    /// Captured from a BedJet 3 on V1.20 (407): it acknowledged 01 43, dropped the link 4 s later, and was back within
    /// a second reporting No update needed, with no steps in between.
    @MainActor
    func testABedJetThatChecksWhileRestartingIsBelievedOnceBack() async throws {
        let back = data("01 56 1b 01 00 00 00 2a 42 00 0d 00 00 14 50 00 00 2b 00 12 03 0a 01 10 13 01 1a 35 00 00 f2")
        XCTAssertEqual(Codec.parseStatus(back)?.extra?.wifiPhase, Codec.WiFiPhase.upToDate)
        let link = ScriptedWiFiLink([26, 26, nil, 26])
        let found = try await link.updateFirmware()
        XCTAssertEqual(found, .upToDate)
    }

    @MainActor
    func testFirmwareInstalledWhileRestartingIsReported() async throws {
        let link = ScriptedWiFiLink([26, nil, 26], firmware: ["V1.20 (407)", "V1.21 (412)"])
        let found = try await link.updateFirmware()
        XCTAssertEqual(found, .updated("V1.21 (412)"))
    }

    @MainActor
    func testAFirmwareCheckWaitsPastTheResultLeftFromBefore() async throws {
        // Up to date from an earlier check; this one finds newer firmware and restarts into it, dropping the link.
        let link = ScriptedWiFiLink([26, 26, 1, 5, 6, 7, nil])
        var steps: [Int] = []
        let found = try await link.updateFirmware { steps.append($0) }
        XCTAssertEqual(found, .installing)
        // Its restart to install is still installing.
        XCTAssertEqual(steps, [1, 5, 6])
    }

    @MainActor
    func testSimulatedBedJetNeedsWiFiToCheckForFirmware() async throws {
        let link = DemoLink()
        link.start()
        defer { link.stop() }
        try await Task.sleep(nanoseconds: 1_200_000_000)
        do {
            _ = try await link.updateFirmware()
            XCTFail("checked without Wi-Fi")
        } catch {
            XCTAssertEqual(error as? BedJetError, .wifi(Codec.WiFiPhase.noNetwork))
        }
        try await link.joinWiFi(network: "Home", password: "12345678")
        XCTAssertEqual(link.wifiNetwork, "Home")
        let found = try await link.updateFirmware()
        XCTAssertEqual(found, .upToDate)
    }
}

/// Reports the next of `phases` each time its status is read, as a BedJet 3 does over successive reports.
/// A nil phase drops the connection for a moment, as the unit does when it restarts, or for good when it ends the script.
@MainActor
private final class ScriptedWiFiLink: BedJetLink {
    private var dropped = false
    var state: LinkState {
        defer { if phases != [nil] { dropped = false } }
        return dropped ? .searching : .ready
    }
    var onStateChange: ((LinkState) -> Void)?
    var onStatus: ((DeviceStatus) -> Void)?
    private(set) var sent: [Data] = []
    private(set) var wifi: (network: Data, password: Data)?
    private var phases: [Int?]
    /// What the unit says its firmware is, one answer per question.
    private var firmware: [String]

    init(_ phases: [Int?], firmware: [String] = []) {
        self.phases = phases
        self.firmware = firmware
    }

    var lastStatus: DeviceStatus? {
        let phase = phases.count > 1 ? phases.removeFirst() : phases.first ?? nil
        guard let phase else {
            dropped = true
            return nil
        }
        return DeviceStatus(
            remainingSeconds: 0, actualTemperature: 40, targetTemperature: 60, mode: .standby, fanStep: 7,
            maxRuntimeMinutes: 0, minTemperature: 38, maxTemperature: 80, ambientTemperature: 40, lastShutdownReason: 0,
            extra: .init(ledEnabled: true, beepsMuted: false, programStep: 0, notification: 0, wifiPhase: phase)
        )
    }

    func start() {}
    func stop() {}
    func send(_ command: Data) async throws { sent.append(command) }
    func query(_ request: UInt8) async throws -> Data {
        guard request == Codec.Query.firmware, !firmware.isEmpty else { throw BedJetError.badResponse }
        return Data(firmware.removeFirst().utf8)
    }
    func writeWiFi(network: Data, password: Data) async throws { wifi = (network, password) }
}
