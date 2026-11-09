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
    public var lastStatus: DeviceStatus? { state == .ready ? status : nil }
    /// The step of the program running on the unit, connected or not; 0 when none is.
    public var programStep: Int { status.extra?.programStep ?? 0 }

    private var status = DeviceStatus(
        remainingSeconds: 0, actualTemperature: 46, targetTemperature: 60, mode: .standby, fanStep: 7,
        maxRuntimeMinutes: 0, minTemperature: 38, maxTemperature: 80, ambientTemperature: 45, lastShutdownReason: 0,
        extra: .init(ledEnabled: true, beepsMuted: false, programStep: 0, notification: 0)
    )
    private var running: (slot: Int, step: Int, stepEnds: Date)?
    private var pendingSteps: [Int: [ProgramStep]] = [:]
    private var timer: Timer?

    public init(name: String = DemoLink.defaultName, slots: [StoredProgram?] = [nil, nil, nil]) {
        self.name = name
        self.slots = slots
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
        onStatus?(status)
    }

    public func query(_ request: UInt8) async throws -> Data {
        switch request {
        case Codec.Query.deviceName:
            return Data(name.utf8) + Data([0])
        case Codec.Query.firmware:
            return Data("V1.20 (demo)".utf8) + Data([0])
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
        if state == .connecting { state = .ready }
        if let run = running, Date() >= run.stepEnds { advanceProgram(from: run.stepEnds) }
        if status.mode != .standby, status.mode != .wait {
            status.remainingSeconds = max(0, status.remainingSeconds - 1)
            if status.remainingSeconds == 0, running == nil { enter(.standby, minutes: 0) }
        }
        let goal = status.mode == .standby || status.mode == .wait ? status.ambientTemperature : status.targetTemperature
        status.actualTemperature += (goal - status.actualTemperature).signum()
        onStatus?(status)
    }
}
