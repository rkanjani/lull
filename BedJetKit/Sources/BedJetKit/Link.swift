import Foundation

/// A connection to one BedJet. `BLELink` talks to real hardware; `DemoLink` simulates a unit.
@MainActor
public protocol BedJetLink: AnyObject {
    var state: LinkState { get }
    /// Which protocol the connected unit speaks. Known once the link is ready.
    var generation: Generation { get }
    /// The most recent status the unit reported. Some V2 commands repeat parts of it.
    var lastStatus: DeviceStatus? { get }
    var onStateChange: ((LinkState) -> Void)? { get set }
    var onStatus: ((DeviceStatus) -> Void)? { get set }

    func start()
    /// Connects whenever the BedJet is free, without asking another phone that holds it to hand it over.
    func listen()
    func stop()
    /// Asks for the BedJet on the person's behalf. It accepts one phone at a time, so this also asks
    /// another phone that is holding it to hand it over.
    func requestControl()
    /// Writes a command and waits for the unit to acknowledge it.
    func send(_ command: Data) async throws
    /// Asks the unit for stored data and returns the reply payload.
    func query(_ request: UInt8) async throws -> Data
}

extension BedJetLink {
    public var generation: Generation { .v3 }

    public func requestControl() {}

    /// A link that never shares its BedJet with another phone has no one to ask, so it connects as it always does.
    public func listen() { start() }

    // MARK: Remote

    public func setMode(_ mode: Mode) async throws {
        switch generation {
        case .v3:
            guard let command = Codec.setMode(mode) else { return }
            try await send(command)
        case .v2:
            let current = lastStatus?.mode ?? .standby
            if mode == .standby, current == .standby, let step = lastStatus?.extra?.programStep, step > 0,
               let cool = CodecV2.setMode(.cool, from: .standby) {
                // A sequence that is waiting reports the air off but keeps going, and there is no off button.
                // Starting a mode takes over from the sequence, and pressing it again turns the air off.
                try await send(cool)
                try await Task.sleep(nanoseconds: 1_000_000_000)
                try await send(cool)
                return
            }
            guard let command = CodecV2.setMode(mode, from: current) else { return }
            try await send(command)
        }
    }

    public func setTemperature(_ halfDegrees: Int) async throws {
        switch generation {
        case .v3: try await send(Codec.setTemperature(halfDegrees))
        case .v2: try await send(CodecV2.setTemperature(halfDegrees, from: try currentStatus()))
        }
    }

    /// 0...19, where the fan runs at `(step + 1) * 5` percent.
    public func setFan(step: Int) async throws {
        switch generation {
        case .v3: try await send(Codec.setFan(step: step))
        case .v2: try await send(CodecV2.setFan(step: step, from: try currentStatus()))
        }
    }

    public func setRuntime(minutes: Int) async throws {
        switch generation {
        case .v3: try await send(Codec.setRuntime(minutes: minutes))
        case .v2: try await send(CodecV2.setRuntime(minutes: minutes, from: try currentStatus()))
        }
    }

    public func setLED(_ on: Bool) async throws {
        switch generation {
        case .v3: try await send(Codec.setLED(on))
        case .v2: try await send(CodecV2.setSettings(ledOn: on, muted: lastStatus?.extra?.beepsMuted == true))
        }
    }

    public func setMuted(_ muted: Bool) async throws {
        switch generation {
        case .v3: try await send(Codec.setMuted(muted))
        case .v2: try await send(CodecV2.setSettings(ledOn: lastStatus?.extra?.ledEnabled != false, muted: muted))
        }
    }

    /// Clears the notification the unit is raising. The V2 raises none.
    public func acknowledgeNotification() async throws {
        guard generation == .v3 else { return }
        try await send(Codec.acknowledgeNotification)
    }

    /// Several V2 commands set temperature, fan and timer at once, so they need the current values.
    private func currentStatus() throws -> DeviceStatus {
        guard let lastStatus else { throw BedJetError.notConnected }
        return lastStatus
    }

    // MARK: Device

    /// The V2 has no clock of its own; it is told the time when a sequence starts.
    public func syncClock(to date: Date = Date()) async throws {
        guard generation == .v3 else { return }
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        try await send(Codec.setClock(hour: parts.hour ?? 0, minute: parts.minute ?? 0))
    }

    public func deviceName() async throws -> String {
        switch generation {
        case .v3: return Codec.parseText(try await query(Codec.Query.deviceName))
        case .v2: return CodecV2.parseName(try await query(CodecV2.Query.deviceName))
        }
    }

    public func firmwareVersion() async throws -> String {
        switch generation {
        case .v3: return Codec.parseText(try await query(Codec.Query.firmware))
        case .v2: return "\(try await v2Info().firmware) (BedJet V2)"
        }
    }

    private func v2Info() async throws -> CodecV2.Info {
        guard let info = CodecV2.parseInfo(try await query(CodecV2.Query.info)) else { throw BedJetError.badResponse }
        return info
    }

    // MARK: Programs

    /// Everything saved on the unit, one entry per slot; `nil` where the slot is empty.
    /// Empty for a V2, whose single sequence has no name and isn't read back.
    public func storedPrograms() async throws -> [StoredProgram?] {
        guard generation == .v3 else { return [] }
        let names = Codec.parseNames(try await query(Codec.Query.programNames))
        var programs: [StoredProgram?] = []
        for slot in 0..<Limits.slots {
            guard slot < names.count, let name = names[slot] else {
                programs.append(nil)
                continue
            }
            let steps = try await programSteps(slot: slot)
            programs.append(steps.isEmpty ? nil : StoredProgram(name: name, steps: steps))
        }
        return programs
    }

    /// The steps saved in a BedJet 3 slot.
    public func programSteps(slot: Int) async throws -> [ProgramStep] {
        var steps: [ProgramStep] = []
        var index = 0
        while true {
            let payload = try await query(Codec.Query.fragment(slot: slot, index: index))
            guard let fragment = Codec.parseFragment(payload, index: index) else { throw BedJetError.badResponse }
            steps += fragment.steps
            index += 1
            if steps.count >= fragment.total || fragment.steps.isEmpty { return steps }
        }
    }

    /// Saves a program into a slot, replacing whatever was there, and confirms the unit kept it.
    /// A V2 has one sequence, so the slot is ignored.
    public func store(_ program: StoredProgram, slot: Int) async throws {
        guard !program.steps.isEmpty else { throw BedJetError.invalidProgram("Add at least one phase first.") }
        let most = generation == .v2 ? CodecV2.stepsPerSequence : Limits.stepsPerProgram
        guard program.steps.count <= most else {
            throw BedJetError.invalidProgram("A BedJet schedule can hold up to \(most) phases.")
        }
        guard program.steps.allSatisfy(\.fitsUnitLimits) else { throw BedJetError.programTooLong }
        guard generation == .v3 else { return try await storeSequence(program.steps) }
        for command in Codec.writeProgram(slot: slot, steps: program.steps) {
            try await send(command)
        }
        try await send(Codec.nameProgram(slot: slot, name: program.name))
        guard try await programSteps(slot: slot) == program.steps else { throw BedJetError.verifyFailed }
    }

    /// Replaces the V2's sequence. The unit's reply to reading a step back isn't known, so instead of
    /// verifying here, the start is confirmed by the unit reporting a running step.
    private func storeSequence(_ steps: [ProgramStep]) async throws {
        guard steps.allSatisfy({ generation.supports($0.mode) }) else {
            throw BedJetError.invalidProgram(CodecV2.unsupportedModes)
        }
        let info = try await v2Info()
        guard info.runsSequences else {
            throw BedJetError.invalidProgram("This BedJet V2 needs a firmware update before it can run schedules. Update it in the official BedJet app.")
        }
        // Steps can only last a duration, so ones that end at a time of day are measured from now.
        let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
        var clock = (now.hour ?? 0) * 60 + (now.minute ?? 0)
        let commands = try steps.enumerated().map { index, step in
            var minutes = step.hours * 60 + step.minutes
            if step.endsAtClockTime { minutes = ((minutes - clock) % 1440 + 1440) % 1440 }
            clock += minutes
            return try CodecV2.writeStep(step, index: index, minutes: minutes, extended: info.extendedSequences)
        }
        // The official app leaves the unit a moment between writes.
        for command in [CodecV2.clearSequence] + commands + [CodecV2.tagSequence((0..<10).map { _ in .random(in: 0...255) })] {
            try await send(command)
            try await Task.sleep(nanoseconds: 200_000_000)
        }
    }

    public func startProgram(slot: Int) async throws {
        switch generation {
        case .v3:
            try await syncClock()
            try await send(Codec.startProgram(slot: slot))
        case .v2:
            let info = try await v2Info()
            let now = Calendar.current.dateComponents([.hour, .minute, .second], from: Date())
            try await send(CodecV2.runSequence(at: now, extended: info.extendedSequences))
        }
    }
}
