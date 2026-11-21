import XCTest
@testable import BedJetKit

/// Fixtures marked "captured" are packets the official iOS app exchanged with a BedJet V2, as published in
/// github.com/roycamp/bedjet-protocol. The rest follow the formats in the official Android app.
final class CodecV2Tests: XCTestCase {
    private func data(_ hex: String) -> Data {
        Data(hex.split(separator: " ").map { UInt8($0, radix: 16)! })
    }

    private func status(_ mode: Mode, fanStep: Int, target: Int, seconds: Int, muted: Bool = true) -> DeviceStatus {
        DeviceStatus(
            remainingSeconds: seconds, actualTemperature: 50, targetTemperature: target, mode: mode, fanStep: fanStep,
            maxRuntimeMinutes: 720, minTemperature: 44, maxTemperature: 80, ambientTemperature: 50, lastShutdownReason: 0,
            extra: .init(ledEnabled: true, beepsMuted: muted, programStep: 0, notification: 0)
        )
    }

    func testCommandsMatchCapturedBytes() {
        XCTAssertEqual(CodecV2.request(CodecV2.Query.info), data("58 01 0b 9b"))
        XCTAssertEqual(CodecV2.request(CodecV2.Query.deviceName), data("58 03 06 02 10 8c"))
        XCTAssertEqual(CodecV2.setMode(.heat, from: .standby), data("58 02 01 02 a2"))
        XCTAssertEqual(CodecV2.setMode(.turbo, from: .cool), data("58 02 01 01 a3"))
        XCTAssertEqual(CodecV2.setSettings(ledOn: true, muted: true), data("58 02 11 01 93"))
        XCTAssertEqual(CodecV2.setSettings(ledOn: false, muted: false), data("58 02 11 02 92"))
    }

    func testModesAreButtonsThatToggle() {
        XCTAssertEqual(CodecV2.setMode(.standby, from: .cool), data("58 02 01 03 a1"))
        XCTAssertNil(CodecV2.setMode(.cool, from: .cool))
        XCTAssertNil(CodecV2.setMode(.standby, from: .standby))
        XCTAssertNil(CodecV2.setMode(.dry, from: .standby))
    }

    func testSceneMatchesCapturedTimerCommand() {
        let heat = status(.heat, fanStep: 16, target: 76, seconds: 3 * 60)
        XCTAssertEqual(CodecV2.setRuntime(minutes: 20, from: heat), data("58 07 0e 02 11 cc 00 14 00 9f"))
    }

    func testSceneKeepsTimerWhenChangingFan() {
        let cool = status(.cool, fanStep: 14, target: 54, seconds: 9 * 3600 + 59 * 60 + 55)
        XCTAssertEqual(CodecV2.setFan(step: 11, from: cool), data("58 07 0e 03 0c b6 09 3b 37 52"))
    }

    func testParsesCapturedOffStatus() throws {
        let off = try XCTUnwrap(CodecV2.parseStatus(data("59 0b 02 a7 0c 00 00 41 00 00 00 00 00 a5")))
        XCTAssertEqual(off.mode, .standby)
        XCTAssertEqual(off.actualTemperature, 0x27)
        XCTAssertEqual(off.targetTemperature, 0x41)
        XCTAssertEqual(off.remainingSeconds, 0)
        XCTAssertEqual(off.extra?.ledEnabled, false)
        XCTAssertEqual(off.extra?.beepsMuted, false)
        XCTAssertEqual(off.extra?.programStep, 0)
        XCTAssertFalse(off.isRunning)
    }

    func testParsesRunningStatus() throws {
        let cool = try XCTUnwrap(CodecV2.parseStatus(data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26")))
        XCTAssertEqual(cool.mode, .cool)
        XCTAssertEqual(cool.fanPercent, 60)
        XCTAssertEqual(cool.remainingSeconds, 9 * 3600 + 59 * 60 + 52)
        XCTAssertEqual(cool.targetTemperature, 0x36)
        XCTAssertEqual(cool.extra?.beepsMuted, true)
        XCTAssertEqual(cool.extra?.ledEnabled, true)
        XCTAssertEqual(cool.extra?.programStep, 1)
        XCTAssertEqual(cool.lastShutdownReason, 3)
        XCTAssertEqual(cool.maxRuntimeMinutes, 720)
    }

    func testReassemblesPacketsAcrossNotifications() {
        var buffer = Data()
        buffer.append(data("00 ff 59 0b 02 34 6c 9e f4"))
        XCTAssertTrue(CodecV2.takePackets(from: &buffer).isEmpty)
        buffer.append(data("b6 80 00 08 00 03 26 59 12 05 02 42 65 64"))
        let packets = CodecV2.takePackets(from: &buffer)
        XCTAssertEqual(packets, [data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26")])
        buffer.append(data("72 6f 6f 6d 20 20 20 20 20 20 20 20 20 a5"))
        let name = try? XCTUnwrap(CodecV2.takePackets(from: &buffer).first)
        XCTAssertEqual(name.flatMap { CodecV2.reply(to: CodecV2.Query.deviceName, in: $0) }.map(CodecV2.parseName), "Bedroom")
        XCTAssertTrue(buffer.isEmpty)
    }

    func testDropsPacketsWithBadChecksums() {
        var buffer = data("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 27")
        XCTAssertTrue(CodecV2.takePackets(from: &buffer).isEmpty)
        XCTAssertLessThan(buffer.count, 14)
    }

    func testParsesInfo() throws {
        let packet = data("59 0c 01 02 35 00 00 00 00 00 01 00 00 00 61")
        let info = try XCTUnwrap(CodecV2.reply(to: CodecV2.Query.info, in: packet).flatMap(CodecV2.parseInfo))
        XCTAssertEqual(info, CodecV2.Info(protocolVersion: 2, firmware: 53, extendedSequences: true))
        XCTAssertNil(CodecV2.reply(to: CodecV2.Query.deviceName, in: packet))
    }

    func testWritesSequenceSteps() throws {
        let heat = ProgramStep(mode: .heat, fanStep: 7, temperature: 68, hours: 0, minutes: 30)
        XCTAssertEqual(try CodecV2.writeStep(heat, index: 1, minutes: 30, extended: true), data("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"))
        let cool = ProgramStep(mode: .cool, fanStep: 12, temperature: 44, hours: 6, minutes: 0)
        XCTAssertEqual(try CodecV2.writeStep(cool, index: 0, minutes: 360, extended: false), data("58 09 05 80 06 03 0d 06 00 00 28 d5"))
        XCTAssertThrowsError(try CodecV2.writeStep(ProgramStep(mode: .dry, fanStep: 5, temperature: 60, hours: 1, minutes: 0),
                                                   index: 0, minutes: 60, extended: true))
    }

    func testRunsSequence() {
        let time = DateComponents(hour: 23, minute: 5, second: 9)
        XCTAssertEqual(CodecV2.runSequence(at: time, extended: true), data("58 04 0d 17 05 09 71"))
        XCTAssertEqual(CodecV2.runSequence(at: time, extended: false), data("58 01 0d 99"))
    }

    @MainActor
    func testStoringAndStartingSendsTheOfficialAppsSequence() async throws {
        let link = RecordingV2Link()
        let program = StoredProgram(name: "Tonight", steps: [
            ProgramStep(mode: .turbo, fanStep: 19, temperature: 86, hours: 0, minutes: 10),
            ProgramStep(mode: .heat, fanStep: 7, temperature: 68, hours: 0, minutes: 30),
            ProgramStep(mode: .cool, fanStep: 12, temperature: 44, hours: 6, minutes: 0),
        ])
        try await link.store(program, slot: 2)
        try await link.startProgram(slot: 2)
        XCTAssertEqual(link.sent.count, 6)
        XCTAssertEqual(link.sent[0], data("58 01 0c 9a"))
        XCTAssertEqual(link.sent[1], data("58 0a 05 80 07 01 14 00 0a 00 50 00 a2"))
        XCTAssertEqual(link.sent[2], data("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"))
        XCTAssertEqual(link.sent[3], data("58 0a 05 82 07 03 0d 06 00 00 28 00 d1"))
        XCTAssertEqual(link.sent[4].prefix(5), data("58 0d 05 03 0a"))
        XCTAssertEqual(link.sent[5].prefix(3), data("58 04 0d"))
    }

    @MainActor
    func testClockTimeStepsBecomeDurationsFromNow() async throws {
        // A V2 only runs steps for a length of time, so one that ends at a time of day is measured from when it's stored.
        let link = RecordingV2Link()
        let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let end = ((now.hour ?? 0) * 60 + (now.minute ?? 0) + 90) % 1440
        let program = StoredProgram(name: "Wake", steps: [
            ProgramStep(mode: .cool, fanStep: 12, temperature: 44, hours: end / 60, minutes: end % 60, endsAtClockTime: true),
        ])
        try await link.store(program, slot: 0)
        let minutes = Int(link.sent[1][7]) * 60 + Int(link.sent[1][8])
        XCTAssertTrue((89...90).contains(minutes), "an hour and a half, or a minute less if the clock ticked over: \(minutes)")
    }

    @MainActor
    func testRefusesModesTheV2Lacks() async {
        let link = RecordingV2Link()
        let program = StoredProgram(name: "Cocoon", steps: [ProgramStep(mode: .extendedHeat, fanStep: 5, temperature: 60, hours: 2, minutes: 0)])
        do {
            try await link.store(program, slot: 0)
            XCTFail("stored a mode the V2 doesn't have")
        } catch {
            XCTAssertEqual(error as? BedJetError, .invalidProgram(CodecV2.unsupportedModes))
        }
        XCTAssertTrue(link.sent.isEmpty)
    }
}

/// Answers like a V2 on firmware 53 and records every command.
@MainActor
private final class RecordingV2Link: BedJetLink {
    var state: LinkState = .ready
    var generation: Generation { .v2 }
    var lastStatus: DeviceStatus?
    var onStateChange: ((LinkState) -> Void)?
    var onStatus: ((DeviceStatus) -> Void)?
    var sent: [Data] = []

    func start() {}
    func stop() {}
    func send(_ command: Data) async throws { sent.append(command) }

    func query(_ request: UInt8) async throws -> Data {
        guard request == CodecV2.Query.info else { throw BedJetError.badResponse }
        return Data([2, 53, 0, 0, 0, 0, 0, 1, 0, 0, 0])
    }
}
