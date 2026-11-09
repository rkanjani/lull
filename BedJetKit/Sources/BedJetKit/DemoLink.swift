import Foundation

/// A simulated BedJet that understands the same bytes as the real one.
/// Used for previews, the iOS Simulator (which has no Bluetooth) and tests.
@MainActor
public final class DemoLink: BedJetLink {
    public private(set) var state: LinkState = .searching {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    public var onStateChange: ((LinkState) -> Void)?
    public var onStatus: ((DeviceStatus) -> Void)?

    public nonisolated static let defaultName = "BEDJET-DEMO"

    public let name: String
    /// False pretends the unit is switched off or out of range: it is looked for but never connects.
    public var answers = true
    public private(set) var slots: [StoredProgram?]
    public private(set) var clock: (hour: Int, minute: Int)?
    /// When the clock was set, so it can keep time from there.
    private var clockSetAt = Date()
    /// The Wi-Fi network the unit was given, which it needs to check for firmware.
    public private(set) var wifiNetwork: String?
    public private(set) var firmware = "V1.20 (demo)"
    public var lastStatus: DeviceStatus? { state == .ready ? reported : nil }
    /// The step of the program running on the unit, connected or not; 0 when none is.
    public var programStep: Int { status.extra?.programStep ?? 0 }

    private var status = DeviceStatus(
        remainingSeconds: 0, actualTemperature: 46, targetTemperature: 60, mode: .standby, fanStep: 7,
        maxRuntimeMinutes: 0, minTemperature: 38, maxTemperature: 80, ambientTemperature: 45, lastShutdownReason: 0,
        extra: .init(ledEnabled: true, beepsMuted: false, programStep: 0, notification: 0)
    )
    private var running: (slot: Int, step: Int, stepEnds: Date)?
    private var pendingSteps: [Int: [ProgramStep]] = [:]
    /// The Wi-Fi phases still to report, one a second, like a unit joining a network or checking for firmware.
    private var wifiSteps: [Int] = []
    /// Seconds left before the unit is back from restarting, as it does when asked to check for firmware.
    private var restarting = 0
    /// Back from restarting, the unit's first report comes without its second half, as a real one's does.
    private var firstHalfOnly = false
    /// Found newer firmware, so it restarts again to install it once it has said so.
    private var installsUpdate = false
    private var reported: DeviceStatus {
        var report = status
        if firstHalfOnly { report.extra = nil }
        return report
    }
    private var timer: Timer?

    public init(name: String = DemoLink.defaultName, slots: [StoredProgram?] = [nil, nil, nil], wifiNetwork: String? = nil) {
        self.name = name
        self.slots = slots
        self.wifiNetwork = wifiNetwork
    }

    public func start() {
        guard timer == nil, answers else { return }
        state = .connecting
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
        state = .searching
    }

    public func send(_ command: Data) async throws {
        let bytes = [UInt8](command)
        guard bytes.count >= 2 else { throw BedJetError.badResponse }
        switch bytes[0] {
        case 0x01: press(bytes[1])
        case 0x02 where bytes.count >= 3:
            status.remainingSeconds = min(Int(bytes[1]) * 60 + Int(bytes[2]), status.maxRuntimeMinutes) * 60
        case 0x03:
            status.targetTemperature = Int(bytes[1])
            limitHeat()
        case 0x07:
            status.fanStep = Int(bytes[1])
            limitHeat()
        case 0x08 where bytes.count >= 3:
            clock = (Int(bytes[1]), Int(bytes[2]))
            clockSetAt = Date()
        case 0x40: store(bytes)
        default: break
        }
        // A unit that is restarting says nothing until it's back.
        if restarting == 0 { onStatus?(reported) }
    }

    /// Pretends the network works whatever the password, then reports its firmware as current when asked to check.
    public func writeWiFi(network: Data, password: Data) async throws {
        wifiNetwork = String(decoding: network, as: UTF8.self)
        wifiSteps = [Codec.WiFiPhase.starting, Codec.WiFiPhase.joining, Codec.WiFiPhase.joined, Codec.WiFiPhase.testing, Codec.WiFiPhase.works]
    }

    public func query(_ request: UInt8) async throws -> Data {
        switch request {
        case Codec.Query.deviceName:
            return Data(name.utf8) + Data([0])
        case Codec.Query.firmware:
            return Data(firmware.utf8) + Data([0])
        case Codec.Query.programNames:
            return slots.reduce(into: Data()) { data, program in
                var slot = [UInt8](repeating: 0, count: 16)
                if let program {
                    for (i, byte) in program.name.utf8.prefix(Limits.nameLength).enumerated() { slot[i] = byte }
                } else {
                    slot[0] = 0x01
                }
                data.append(contentsOf: slot)
            }
        case 7..<19:
            let slot = Int(request - 7) / 4, index = Int(request - 7) % 4
            let steps = slots[slot]?.steps ?? []
            return Codec.writeProgram(slot: slot, steps: steps).indices.contains(index)
                ? Codec.writeProgram(slot: slot, steps: steps)[index].dropFirst(2)
                : Data([UInt8(steps.count)])
        default:
            throw BedJetError.badResponse
        }
    }

    private func press(_ button: UInt8) {
        switch button {
        case 0x01...0x06:
            running = nil
            status.extra?.programStep = 0
            let mode = Mode.allCases.first { $0.button == button } ?? .standby
            enter(mode, minutes: mode == .standby ? 0 : mode == .turbo ? 10 : 60)
            // Manual modes start at an hour and can be stretched; these ceilings are illustrative.
            status.maxRuntimeMinutes = mode == .standby ? 0 : mode == .turbo ? 10 : mode == .heat ? 240 : 720
            limitHeat()
        case 0x46, 0x47: status.extra?.ledEnabled = button == 0x46
        case 0x48, 0x49: status.extra?.beepsMuted = button == 0x48
        case 0x52: status.extra?.notification = 0
        case 0x43:
            // Like the real unit, restart, which turns off the air and drops the connection for a few seconds, and
            // check while away, so the result is there when it's back.
            running = nil
            status.extra?.programStep = 0
            enter(.standby, minutes: 0)
            restarting = 2
            state = .connecting
            if wifiNetwork == nil {
                wifiSteps = [Codec.WiFiPhase.noNetwork]
            } else if status.extra?.notification == Codec.Notification.updateAvailable {
                // Downloads the newer firmware once it's back, then restarts again to install it.
                wifiSteps = [Codec.WiFiPhase.checking, Codec.WiFiPhase.updating, Codec.WiFiPhase.updating, Codec.WiFiPhase.installing]
                installsUpdate = true
            } else {
                wifiSteps = [Codec.WiFiPhase.upToDate]
            }
        case 0x80...0x82:
            let slot = Int(button - 0x80)
            guard let program = slots[slot] else { return }
            // Like the real unit, refuse to start rather than stop partway through the night.
            if clock == nil {
                status.extra?.notification = Codec.Notification.programNeedsClock
                return
            }
            if !program.steps.allSatisfy(\.fitsUnitLimits) {
                status.extra?.notification = Codec.Notification.programTooLong
                return
            }
            running = (slot, -1, Date())
            advanceProgram(from: Date())
        case 0x83...0x85:
            slots[Int(button - 0x83)] = nil
        default: break
        }
    }

    private func enter(_ mode: Mode, minutes: Int) {
        status.mode = mode
        status.remainingSeconds = minutes * 60
        status.maxRuntimeMinutes = minutes
        // Like the real unit, report the limits of the mode it is in.
        status.minTemperature = mode.usesTemperature ? mode.temperatureRange.lowerBound : 20
        status.maxTemperature = mode.usesTemperature ? mode.temperatureRange.upperBound : 80
        if !mode.temperatureRange.contains(status.targetTemperature) {
            status.targetTemperature = mode.temperatureRange.lowerBound
        }
    }

    /// Hot air at a high fan is cut off sooner, so manual Heat's ceiling follows temperature and fan.
    private func limitHeat() {
        guard status.mode == .heat, running == nil else { return }
        let longest = ProgramStep.longestMinutes(mode: .heat, temperature: status.targetTemperature, fanStep: status.fanStep)
        status.maxRuntimeMinutes = min(240, longest)
        status.remainingSeconds = min(status.remainingSeconds, status.maxRuntimeMinutes * 60)
    }

    private func store(_ bytes: [UInt8]) {
        guard bytes.count >= 3 else { return }
        let target = Int(bytes[1])
        if (4...6).contains(target) {
            let slot = target - 4
            let name = Codec.parseText(Data(bytes.dropFirst(3)))
            if let steps = pendingSteps.removeValue(forKey: slot) {
                slots[slot] = StoredProgram(name: name, steps: steps)
            } else {
                slots[slot]?.name = name
            }
        } else if (7..<19).contains(target) {
            let slot = (target - 7) / 4, index = (target - 7) % 4
            guard let fragment = Codec.parseFragment(Data(bytes.dropFirst(2)), index: index) else { return }
            var steps = index == 0 ? [] : (pendingSteps[slot] ?? [])
            steps += fragment.steps
            pendingSteps[slot] = steps
            if steps.count >= fragment.total {
                slots[slot] = StoredProgram(name: slots[slot]?.name ?? "", steps: steps)
            }
        }
    }

    /// Says newer firmware is available, as the real unit does when its update server has some. Only a unit on Wi-Fi
    /// hears of it, so it has a network.
    public func offerFirmwareUpdate() {
        status.extra?.notification = Codec.Notification.updateAvailable
        if wifiNetwork == nil { wifiNetwork = "Home" }
    }

    /// Restarts into the newer firmware, as the real unit does after saying it's restarting to install it.
    private func installUpdate() {
        installsUpdate = false
        firmware = "V1.21 (demo)"
        status.extra?.notification = 0
        status.extra?.wifiPhase = Codec.WiFiPhase.idle
        restarting = 3
        state = .connecting
    }

    /// Pretends the running program started this much earlier, for demos that should show a night in progress.
    public func skipAhead(minutes: Int) {
        guard var run = running else { return }
        // A step that ends at a time of day ends then, however much earlier it started.
        if slots[run.slot]?.steps[run.step].endsAtClockTime != true {
            run.stepEnds = run.stepEnds.addingTimeInterval(TimeInterval(-minutes * 60))
        }
        running = run
        while let current = running, Date() >= current.stepEnds { advanceProgram(from: current.stepEnds) }
        if let current = running { status.remainingSeconds = max(0, Int(current.stepEnds.timeIntervalSinceNow)) }
        onStatus?(status)
    }

    private func advanceProgram(from start: Date) {
        guard var run = running, let program = slots[run.slot] else { return }
        run.step += 1
        guard run.step < program.steps.count else {
            running = nil
            status.extra?.programStep = 0
            enter(.standby, minutes: 0)
            return
        }
        let step = program.steps[run.step]
        var minutes = step.hours * 60 + step.minutes
        // Like the real unit, a step that ends at a time of day runs until its clock gets there, the next day if it has passed.
        if step.endsAtClockTime, let now = minuteOfDay(at: start) { minutes = ((minutes - now) % 1440 + 1440) % 1440 }
        run.stepEnds = start.addingTimeInterval(TimeInterval(minutes * 60))
        running = run
        status.fanStep = step.fanStep
        status.targetTemperature = step.temperature
        status.extra?.programStep = run.step + 1
        enter(step.mode, minutes: minutes)
    }

    /// The unit's time of day at `date`, in minutes after midnight, kept from when its clock was set.
    private func minuteOfDay(at date: Date) -> Int? {
        guard let clock else { return nil }
        let elapsed = Int((date.timeIntervalSince(clockSetAt) / 60).rounded(.down))
        return ((clock.hour * 60 + clock.minute + elapsed) % 1440 + 1440) % 1440
    }

    private func tick() {
        if restarting > 0 {
            restarting -= 1
            if restarting > 0 { return }
            firstHalfOnly = true
        } else {
            firstHalfOnly = false
        }
        if state == .connecting { state = .ready }
        if installsUpdate, wifiSteps.isEmpty, status.extra?.wifiPhase == Codec.WiFiPhase.installing { return installUpdate() }
        // A report without its second half has no Wi-Fi phase, so the next step waits for one that does.
        if !firstHalfOnly, !wifiSteps.isEmpty { status.extra?.wifiPhase = wifiSteps.removeFirst() }
        if let run = running, Date() >= run.stepEnds { advanceProgram(from: run.stepEnds) }
        if status.mode != .standby, status.mode != .wait {
            status.remainingSeconds = max(0, status.remainingSeconds - 1)
            if status.remainingSeconds == 0, running == nil { enter(.standby, minutes: 0) }
        }
        let goal = status.mode == .standby || status.mode == .wait ? status.ambientTemperature : status.targetTemperature
        status.actualTemperature += (goal - status.actualTemperature).signum()
        onStatus?(reported)
    }
}
