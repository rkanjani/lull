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
    func stop()
    /// Asks for the BedJet on the person's behalf. It accepts one phone at a time, so this also asks
    /// another phone that is holding it to hand it over.
    func requestControl()
    /// Writes a command and waits for the unit to acknowledge it.
    func send(_ command: Data) async throws
    /// Asks the unit for stored data and returns the reply payload.
    func query(_ request: UInt8) async throws -> Data
    /// Gives a BedJet 3 the Wi-Fi network it fetches firmware over: the name, then the password, which starts a
    /// connection test.
    func writeWiFi(network: Data, password: Data) async throws
}

extension BedJetLink {
    public var generation: Generation { .v3 }

    public func requestControl() {}

    public func writeWiFi(network: Data, password: Data) async throws { throw BedJetError.invalidWiFi(BedJetError.noWiFiSetup) }

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

    // MARK: Wi-Fi and firmware

    /// Gives a BedJet 3 the Wi-Fi network it fetches firmware over, then follows the unit's test of it.
    /// `progress` hears each `Codec.WiFiPhase` step. Throws the unit's result unless it joined.
    public func joinWiFi(network: String, password: String, progress: (Int) -> Void = { _ in }) async throws {
        guard generation == .v3 else { throw BedJetError.invalidWiFi(BedJetError.noWiFi) }
        if let problem = Codec.wifiProblem(network: network, password: password) { throw BedJetError.invalidWiFi(problem) }
        let shown = lastStatus?.extra?.wifiPhase
        try await writeWiFi(network: Data(network.utf8), password: Data(password.utf8))
        let result = try await wifiResult(after: shown, restarts: false, progress: progress)
        guard result == Codec.WiFiPhase.works else {
            // It has a network now, so "no network" is only the old result still showing.
            throw result == Codec.WiFiPhase.noNetwork ? BedJetError.timeout : BedJetError.wifi(result)
        }
    }

    /// Asks a BedJet 3 to check BedJet's server for newer firmware, over its Wi-Fi. The unit can't only check:
    /// newer firmware is installed straight away, and the unit restarts, which drops the connection.
    public func updateFirmware(progress: (Int) -> Void = { _ in }) async throws -> FirmwareUpdate {
        guard generation == .v3 else { throw BedJetError.invalidWiFi(BedJetError.noWiFi) }
        let before = try? await firmwareVersion()
        let shown = lastStatus?.extra?.wifiPhase
        try await send(Codec.updateFirmware)
        switch try await wifiResult(after: shown, restarts: true, progress: progress) {
        case Codec.WiFiPhase.upToDate:
            // The unit checks, and installs, while it's restarting, so newer firmware may already be running.
            if let before, let after = try? await firmwareVersion(), after != before { return .updated(after) }
            return .upToDate
        case Codec.WiFiPhase.installing:
            // About to restart into the new firmware, so asking its version now would only wait out the restart.
            return .installing
        case let result:
            throw BedJetError.wifi(result)
        }
    }

    /// Follows the unit's Wi-Fi phase until it gives a result. The result of its last attempt stays in the status
    /// until it starts again, so a result only counts once the unit has moved on from the one `shown` when asked.
    ///
    /// Asked to check for firmware, a BedJet 3 restarts within a few seconds and does the whole check while it's
    /// away, so the result is there as soon as it's back (captured: 01 43 acknowledged, link lost 4 s later, back
    /// within a second reporting No update needed). The official app waits for it to reconnect too. The link comes
    /// back by itself, so a dropped link is waited out, except once the unit is installing: then the restart is
    /// the end, and it comes back with the new firmware, so the result is `installing`.
    ///
    /// Only a firmware check `restarts` the unit. Any other dropped link is just Bluetooth, and the result the
    /// unit shows once it's back may still be the one from before.
    private func wifiResult(after shown: Int?, restarts: Bool, progress: (Int) -> Void) async throws -> Int {
        var working = false
        var installing = false
        var restarted = false
        var last: Int?
        // Polled every 200 ms: 20 seconds to start, 10 minutes in all, which also bounds a restart. A link lost
        // for 20 seconds when nothing restarted the unit is lost, not waited for.
        var waited = 0
        var away = 0
        for _ in 0..<3000 {
            if state != .ready {
                if installing { return Codec.WiFiPhase.installing }
                if !restarts, away == 100 { throw BedJetError.notConnected }
                if away == 0 {
                    waited = 0
                    if restarts {
                        progress(Codec.WiFiPhase.restarting)
                        restarted = true
                        working = false
                        last = nil
                    }
                }
                away += 1
                try await Task.sleep(nanoseconds: 200_000_000)
                continue
            }
            away = 0
            if let phase = lastStatus?.extra?.wifiPhase {
                if !Codec.WiFiPhase.isResult(phase) {
                    // Restarting once it's installing is still installing, not restarting to check.
                    let restartsToInstall = installing && phase == Codec.WiFiPhase.restarting
                    if phase != Codec.WiFiPhase.idle, phase != last, !restartsToInstall { progress(phase) }
                    working = working || phase != Codec.WiFiPhase.idle
                    installing = installing || phase == Codec.WiFiPhase.updating
                } else if working || restarted || (shown != nil && phase != shown) {
                    return phase
                }
                last = phase
            }
            waited += 1
            if !working, waited == 100 {
                // A quick check can come and go between status reports, so a failure still showing is the answer.
                if let last, Codec.WiFiPhase.isResult(last), last != Codec.WiFiPhase.works, last != Codec.WiFiPhase.upToDate {
                    return last
                }
                throw BedJetError.timeout
            }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        // A slow download can outlast the wait.
        if installing { return Codec.WiFiPhase.installing }
        throw BedJetError.timeout
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
            throw BedJetError.invalidProgram("This BedJet V2's firmware is too old to run schedules, and neither BedJet app can update it. Ask BedJet support about an update.")
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
