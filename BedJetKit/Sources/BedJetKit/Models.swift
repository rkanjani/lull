import Foundation

/// Operating modes as the BedJet 3 reports them and stores them in program steps. `CodecV2` maps the
/// V2's own codes onto these.
public enum Mode: UInt8, Codable, CaseIterable, Sendable {
    case standby = 0
    case heat = 1
    case turbo = 2
    case extendedHeat = 3
    case cool = 4
    case dry = 5
    case wait = 6

    /// Button code that switches the unit into this mode right now.
    var button: UInt8? {
        switch self {
        case .standby: return 0x01
        case .cool: return 0x02
        case .heat: return 0x03
        case .turbo: return 0x04
        case .dry: return 0x05
        case .extendedHeat: return 0x06
        case .wait: return nil
        }
    }

    /// Target temperature limits in half-degrees Celsius, as enforced by the official app.
    public var temperatureRange: ClosedRange<Int> {
        switch self {
        case .heat: return 45...80
        case .turbo: return 86...86
        case .extendedHeat: return 38...67
        case .cool: return 38...62
        case .dry: return 40...80
        case .standby, .wait: return 80...80
        }
    }

    public var usesTemperature: Bool { temperatureRange.lowerBound != temperatureRange.upperBound }
    public var usesFan: Bool { self != .standby && self != .wait }
}

/// Which BedJet a link is talking to. Both run the same kind of program on the unit, but they speak
/// different protocols and the V2 has fewer modes.
public enum Generation: String, Codable, Sendable {
    case v2
    case v3

    public func supports(_ mode: Mode) -> Bool {
        switch self {
        case .v3: return true
        case .v2: return [.standby, .heat, .turbo, .cool, .wait].contains(mode)
        }
    }

    /// The V2 only takes a target temperature for Heat.
    public func adjustsTemperature(_ mode: Mode) -> Bool {
        self == .v2 ? mode == .heat : mode.usesTemperature
    }
}

/// One step of an on-device program ("biorhythm"). Five bytes on the wire.
public struct ProgramStep: Codable, Hashable, Sendable {
    public var mode: Mode
    /// 0...19, where the fan runs at `(fanStep + 1) * 5` percent.
    public var fanStep: Int
    /// Target temperature in half-degrees Celsius.
    public var temperature: Int
    public var hours: Int
    public var minutes: Int
    /// When set, hours/minutes are a wall-clock end time instead of a duration.
    public var endsAtClockTime: Bool

    public init(mode: Mode, fanStep: Int, temperature: Int, hours: Int, minutes: Int, endsAtClockTime: Bool = false) {
        self.mode = mode
        self.fanStep = fanStep
        self.temperature = temperature
        self.hours = hours
        self.minutes = minutes
        self.endsAtClockTime = endsAtClockTime
    }
}

extension ProgramStep {
    /// Longest the unit lets a step run, in minutes. Heat is cut off sooner the hotter and faster the air,
    /// following the table the official app enforces; the unit refuses or stops a step that runs past it.
    public static func longestMinutes(mode: Mode, temperature: Int, fanStep: Int) -> Int {
        guard mode == .heat, temperature >= 60 else { return Limits.maxStepMinutes }
        let row = heatHours[min(max(fanStep, 0), heatHours.count - 1)]
        let offset = temperature - 60
        let pair = row[min(offset >> 1, row.count - 1)]
        let hours = Int(offset & 1 == 0 ? pair >> 4 : pair & 0x0f)
        return min(hours * 60, Limits.maxStepMinutes)
    }

    public var longestMinutes: Int {
        Self.longestMinutes(mode: mode, temperature: temperature, fanStep: fanStep)
    }

    /// False when the unit would stop this step before it finishes. Clock-time steps end when they say.
    public var fitsUnitLimits: Bool {
        endsAtClockTime || hours * 60 + minutes <= longestMinutes
    }

    /// Heat hours allowed by fan step (rows) and target from 30 °C up in half-degree pairs (columns).
    /// Each byte holds two temperatures: the even half-degree in the high nibble, the odd one in the low.
    private static let heatHours: [[UInt8]] = {
        let quiet: [UInt8] = [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x66, 0x41]
        let strong: [UInt8] = [0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11]
        return Array(repeating: quiet, count: 5) + [
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x64, 0x44, 0x21],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x44, 0x44, 0x21],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x42, 0x22, 0x21],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x22, 0x21],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x11, 0x11],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x11, 0x11],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11],
            [0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11],
        ] + Array(repeating: strong, count: 6)
    }()
}

/// A program stored in one of the unit's three slots.
public struct StoredProgram: Equatable, Sendable {
    public var name: String
    public var steps: [ProgramStep]

    public init(name: String, steps: [ProgramStep]) {
        self.name = name
        self.steps = steps
    }
}

public struct DeviceStatus: Equatable, Sendable {
    public var remainingSeconds: Int
    public var actualTemperature: Int
    public var targetTemperature: Int
    public var mode: Mode
    public var fanStep: Int
    public var maxRuntimeMinutes: Int
    public var minTemperature: Int
    public var maxTemperature: Int
    public var ambientTemperature: Int
    /// Why the unit last turned itself off. The codes are undocumented; reported for diagnosis.
    public var lastShutdownReason: Int
    /// The second half of the status packet arrives separately and may lag the first.
    public var extra: Extra?

    public struct Extra: Equatable, Sendable {
        public var ledEnabled: Bool
        public var beepsMuted: Bool
        /// Non-zero while an on-device program is running.
        public var programStep: Int
        /// One of `Codec.Notification`. The unit repeats it until it is acknowledged.
        public var notification: Int
        /// One of `Codec.WiFiPhase`: how far a BedJet 3 is in joining Wi-Fi or checking for firmware, or how that last went.
        public var wifiPhase = 0
    }

    public var fanPercent: Int { (fanStep + 1) * 5 }
    public var isRunning: Bool { mode != .standby }
}

public enum LinkState: Equatable, Sendable {
    case bluetoothOff
    case unauthorized
    case searching
    case connecting
    case ready
    /// Handed over to another phone that asked for the BedJet. Reconnects by itself once that phone lets go.
    case yielded
}

/// How long the most recent connection took, measured from the moment the BedJet was asked for.
public struct ConnectionTiming: Equatable, Sendable {
    /// Until Bluetooth reported it was on. Zero when it already was.
    public var radioOn: TimeInterval
    /// Until the radio link was up. Includes any wait for another phone to hand the BedJet over.
    public var linkUp: TimeInterval
    /// Until the unit's service was found.
    public var services: TimeInterval
    /// Until commands could be sent.
    public var usable: TimeInterval
    /// False when the unit had to be found by scanning, as on a first connection.
    public var knownUnit: Bool
}

/// What a BedJet 3 found when asked to check for newer firmware.
public enum FirmwareUpdate: Equatable, Sendable {
    case upToDate
    /// Newer firmware was found. The unit installs it and restarts.
    case installing
    /// The unit came back from restarting with this firmware, newer than it had.
    case updated(String)
}

public enum BedJetError: LocalizedError, Equatable {
    case notConnected
    case inUseElsewhere
    case timeout
    case badResponse
    case verifyFailed
    case clockNotSet
    case programTooLong
    case invalidProgram(String)
    case invalidWiFi(String)
    /// The unit's Wi-Fi ended with this result, one of `Codec.WiFiPhase`.
    case wifi(Int)

    public var errorDescription: String? {
        switch self {
        case .notConnected: return "Couldn't reach your BedJet. Check that it's plugged in and nearby."
        case .inUseElsewhere: return "Another phone is using your BedJet right now. Try again in a moment."
        case .timeout: return "Your BedJet didn't respond. Move closer and try again."
        case .badResponse: return "Your BedJet sent something unexpected. Try again."
        case .verifyFailed: return "The schedule didn't save correctly. Try again."
        case .clockNotSet: return "Your BedJet's clock wasn't set, so it didn't start the schedule. Try again."
        case .programTooLong: return "Your BedJet won't run a phase this long. Shorten the Heat phases or use Gentle heat."
        case .invalidProgram(let why), .invalidWiFi(let why): return why
        case .wifi(let result): return Self.describe(wifi: result)
        }
    }

    /// For a BedJet V2, which has no Wi-Fi.
    public static let noWiFi = "Only a BedJet 3 has Wi-Fi. A BedJet V2's firmware can't be updated from a phone."
    /// For a BedJet 3 without the characteristics that take Wi-Fi details.
    public static let noWiFiSetup = "This BedJet doesn't take Wi-Fi details from a phone."

    private static func describe(wifi result: Int) -> String {
        switch result {
        case Codec.WiFiPhase.noNetwork:
            return "Your BedJet doesn't have a Wi-Fi network yet. Set up Wi-Fi first."
        case Codec.WiFiPhase.cannotJoin:
            return "Your BedJet couldn't join the Wi-Fi network. Check the password, and that it's a 2.4 GHz network: BedJet 3 can't use 5 GHz."
        case Codec.WiFiPhase.noAddress:
            return "Your BedJet found the Wi-Fi network, but the router didn't give it an address. Try again, or restart the router."
        case Codec.WiFiPhase.noServer:
            return "Your BedJet is on Wi-Fi but couldn't reach BedJet's update server. Check that the network is online, then try again."
        case Codec.WiFiPhase.testFailed:
            return "Your BedJet's Wi-Fi connection test failed. Try again."
        case Codec.WiFiPhase.radioOff:
            return "Your BedJet's Wi-Fi radio is off. Unplug the BedJet for 10 seconds, then try again."
        case Codec.WiFiPhase.updateFailed:
            return "The firmware update failed. Try again later."
        default:
            return "Your BedJet's Wi-Fi reported a problem (code \(result)). Try again."
        }
    }
}

public enum Limits {
    public static let slots = 3
    public static let stepsPerProgram = 32
    public static let stepsPerFragment = 8
    public static let nameLength = 15
    /// The unit refuses steps of 12 hours or longer.
    public static let maxStepMinutes = 11 * 60 + 59
}
