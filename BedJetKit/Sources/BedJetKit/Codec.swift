import Foundation

/// Byte-level encoding for the BedJet 3 GATT protocol.
///
/// Commands are written to characteristic 0x2004. Status arrives as notifications on 0x2000,
/// and replies to `query` requests are read from 0x2006.
public enum Codec {
    public enum Query {
        public static let deviceName: UInt8 = 0x00
        public static let programNames: UInt8 = 0x04
        public static let firmware: UInt8 = 0x20
        public static func fragment(slot: Int, index: Int) -> UInt8 { UInt8(7 + slot * 4 + index) }
    }

    /// Codes the unit raises in byte 29 of its status.
    public enum Notification {
        public static let cleanFilter = 1
        public static let updateAvailable = 2
        public static let updateFailed = 3
        /// A program was started but the unit's clock isn't set, so it didn't run.
        public static let programNeedsClock = 4
        /// A program was started with a step longer than the unit allows, so it didn't run.
        public static let programTooLong = 5
    }

    /// Where the unit is in using its Wi-Fi, reported in byte 26 of its status. Below 20 it is still at it; from 20
    /// up it has finished, and that result stays until it next starts. See `docs/BEDJET_3_FIRMWARE.md`.
    public enum WiFiPhase {
        public static let idle = 0
        public static let starting = 1
        public static let joining = 2
        public static let joined = 3
        public static let testing = 4
        public static let checking = 5
        public static let updating = 6
        public static let restarting = 7
        public static let noNetwork = 20
        public static let cannotJoin = 21
        public static let noAddress = 22
        public static let noServer = 23
        public static let works = 24
        public static let testFailed = 25
        public static let upToDate = 26
        public static let radioOff = 27
        /// Newer firmware was found, and the unit restarts to install it.
        public static let installing = 28
        public static let updateFailed = 29

        public static func isResult(_ phase: Int) -> Bool { phase >= noNetwork }
    }

    // MARK: Commands

    public static func setMode(_ mode: Mode) -> Data? { mode.button.map { Data([0x01, $0]) } }
    public static func setRuntime(minutes: Int) -> Data { Data([0x02, UInt8(minutes / 60), UInt8(minutes % 60)]) }
    public static func setTemperature(_ halfDegrees: Int) -> Data { Data([0x03, UInt8(clamping: halfDegrees)]) }
    public static func setFan(step: Int) -> Data { Data([0x07, UInt8(min(max(step, 0), 19))]) }
    public static func setClock(hour: Int, minute: Int) -> Data { Data([0x08, UInt8(hour), UInt8(minute)]) }
    public static func setLED(_ on: Bool) -> Data { Data([0x01, on ? 0x46 : 0x47]) }
    public static func setMuted(_ muted: Bool) -> Data { Data([0x01, muted ? 0x48 : 0x49]) }
    public static func startProgram(slot: Int) -> Data { Data([0x01, UInt8(0x80 + slot)]) }
    public static func deleteProgram(slot: Int) -> Data { Data([0x01, UInt8(0x83 + slot)]) }
    public static let acknowledgeNotification = Data([0x01, 0x52])
    /// Checks BedJet's server over the unit's Wi-Fi. Newer firmware is installed straight away, restarting the unit.
    public static let updateFirmware = Data([0x01, 0x43])
    public static func query(_ request: UInt8, tag: UInt8) -> Data { Data([0x41, request, tag]) }

    /// Why the unit can't be given this Wi-Fi network, if it can't. Network names are at most 32 bytes and WPA
    /// passwords 8 to 63; the official app only checks that the password is long enough.
    public static func wifiProblem(network: String, password: String) -> String? {
        let passwordBytes = password.utf8.count
        if network.isEmpty { return "Enter the name of your Wi-Fi network." }
        if network.utf8.count > 32 { return "Wi-Fi network names are at most 32 characters." }
        if passwordBytes < 8 { return "Wi-Fi passwords are at least 8 characters." }
        if passwordBytes > 63 { return "Wi-Fi passwords are at most 63 characters." }
        return nil
    }

    public static func nameProgram(slot: Int, name: String) -> Data {
        var bytes = [UInt8](repeating: 0, count: 19)
        bytes[0] = 0x40
        bytes[1] = UInt8(4 + slot)
        bytes[2] = 0x10
        for (i, byte) in sanitize(name).utf8.enumerated() { bytes[3 + i] = byte }
        return Data(bytes)
    }

    /// One write per 8 steps; every fragment repeats the program's total step count.
    public static func writeProgram(slot: Int, steps: [ProgramStep]) -> [Data] {
        let fragments = max(1, (steps.count + Limits.stepsPerFragment - 1) / Limits.stepsPerFragment)
        return (0..<fragments).map { index in
            var bytes = [UInt8](repeating: 0, count: 43)
            bytes[0] = 0x40
            bytes[1] = Query.fragment(slot: slot, index: index)
            bytes[2] = UInt8(steps.count)
            let chunk = steps.dropFirst(index * Limits.stepsPerFragment).prefix(Limits.stepsPerFragment)
            for (i, step) in chunk.enumerated() {
                let base = 3 + i * 5
                bytes[base] = step.mode.rawValue
                bytes[base + 1] = UInt8(min(max(step.fanStep, 0), 19))
                bytes[base + 2] = UInt8(clamping: step.temperature)
                bytes[base + 3] = UInt8(step.hours & 0x7f) | (step.endsAtClockTime ? 0x80 : 0)
                bytes[base + 4] = UInt8(step.minutes)
            }
            return Data(bytes)
        }
    }

    /// Names are 15 ASCII bytes on the unit.
    public static func sanitize(_ name: String) -> String {
        let ascii = name.unicodeScalars.filter { $0.value >= 32 && $0.value < 127 }
        return String(String(String.UnicodeScalarView(ascii)).prefix(Limits.nameLength))
    }

    // MARK: Replies

    /// Payload of a fragment reply (after the echoed request and tag bytes).
    public static func parseFragment(_ payload: Data, index: Int) -> (total: Int, steps: [ProgramStep])? {
        let bytes = [UInt8](payload)
        guard let total = bytes.first.map(Int.init), total <= Limits.stepsPerProgram else { return nil }
        let expected = min(Limits.stepsPerFragment, max(0, total - index * Limits.stepsPerFragment))
        guard bytes.count >= 1 + expected * 5 else { return nil }
        var steps: [ProgramStep] = []
        for i in 0..<expected {
            let base = 1 + i * 5
            guard let mode = Mode(rawValue: bytes[base]) else { return nil }
            steps.append(ProgramStep(
                mode: mode,
                fanStep: Int(bytes[base + 1]),
                temperature: Int(bytes[base + 2]),
                hours: Int(bytes[base + 3] & 0x7f),
                minutes: Int(bytes[base + 4]),
                endsAtClockTime: bytes[base + 3] & 0x80 != 0
            ))
        }
        return (total, steps)
    }

    /// 16-byte name slots. A slot starting with 0x01 has never been saved.
    public static func parseNames(_ payload: Data) -> [String?] {
        let bytes = [UInt8](payload)
        return stride(from: 0, to: bytes.count, by: 16).map { start in
            let slot = bytes[start..<min(start + 16, bytes.count)]
            if slot.first == 0x01 { return nil }
            return String(decoding: slot.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    public static func parseText(_ payload: Data) -> String {
        String(decoding: payload.prefix { $0 != 0 }, as: UTF8.self)
    }

    // MARK: Status

    /// True for a status notification, as opposed to the remainder served by a follow-up read.
    public static func isStatusStart(_ chunk: Data) -> Bool {
        chunk.count >= 20 && chunk[chunk.startIndex + 1] == 0x56 && chunk[chunk.startIndex + 3] == 0x01
    }

    public static func parseStatus(_ packet: Data) -> DeviceStatus? {
        let b = [UInt8](packet)
        guard b.count >= 20, let mode = Mode(rawValue: b[9]) else { return nil }
        var status = DeviceStatus(
            remainingSeconds: Int(b[4]) * 3600 + Int(b[5]) * 60 + Int(b[6]),
            actualTemperature: Int(b[7]),
            targetTemperature: Int(b[8]),
            mode: mode,
            fanStep: Int(b[10]),
            maxRuntimeMinutes: Int(b[11]) * 60 + Int(b[12]),
            minTemperature: Int(b[13]),
            maxTemperature: Int(b[14]),
            ambientTemperature: Int(b[17]),
            lastShutdownReason: Int(b[18]),
            extra: nil
        )
        if b.count >= 30 {
            status.extra = .init(
                ledEnabled: b[27] & 0x10 != 0,
                beepsMuted: b[27] & 0x01 != 0,
                programStep: Int(b[28]),
                notification: Int(b[29]),
                wifiPhase: Int(b[26])
            )
        }
        return status
    }
}
