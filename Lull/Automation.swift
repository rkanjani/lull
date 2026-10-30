import BedJetKit
import Foundation

/// What a Shortcuts action asks of a BedJet. Android's automation intents carry the same requests.
enum AutomationRequest: Equatable {
    /// Without a schedule, tonight's.
    case startNight(schedule: Schedule.ID?)
    /// Turn On sets a mode; Set Temperature, Set Fan and Set Run Time change the air that is already on.
    case air(AirSettings)
    case turnOff
    case lightRing(on: Bool)
    case muteBeeps(Bool)
}

/// Air for an action, as the Remote sets it. The temperature is in the unit Lull shows.
struct AirSettings: Equatable {
    var mode: Mode?
    var degrees: Int?
    var fanPercent: Int?
    var minutes: Int?
}

/// What was sent to the BedJet, once kept to what it allows. The temperature is in half-degrees Celsius.
struct AppliedAir: Equatable {
    var mode: Mode
    var temperature: Int?
    var fanPercent: Int?
    var minutes: Int?
}

enum Started: Equatable {
    case now
    /// Every slot was taken, and Lull is asking which to reuse.
    case choosingSlot
    /// The schedule's name, or nil for one started from another phone.
    case alreadyRunning(String?)
}

enum TurnedOff: Equatable {
    /// The schedule's name, or nil for one started from another phone.
    case endedNight(String?)
    case stoppedAir
    case alreadyOff
}

/// What the BedJet was doing when Lull last heard from it.
struct AirReading: Equatable {
    var mode: Mode
    /// Half-degrees Celsius.
    var temperature: Int
    var fanPercent: Int
    var programStep: Int
    var remainingSeconds: Int
    var at: Date
}

enum AutomationError: LocalizedError, Equatable {
    case noBedJet
    /// The action names a BedJet that was forgotten since.
    case forgottenBedJet
    case noSchedule
    /// The action names a schedule that was deleted since.
    case forgottenSchedule
    /// The BedJet couldn't be reached without Lull on screen: another phone has it, Bluetooth is off, or it is away.
    case needsLull
    case slotsFull
    case unsupportedMode(String)
    case airOff
    case fixedTemperature(Mode)
    case heatOnlyTemperature
    case scheduleSetsTiming
    /// The BedJet is using its Wi-Fi, to join a network or to check for or install firmware.
    case updating

    var errorDescription: String? {
        switch self {
        case .noBedJet: return "Choose a BedJet in Lull first."
        case .forgottenBedJet: return "Lull no longer knows the BedJet this action is set to. Pick another for it."
        case .noSchedule: return "Make a schedule in Lull first."
        case .forgottenSchedule: return "Lull no longer has the schedule this action is set to. Pick another for it."
        case .needsLull: return "Lull couldn't reach your BedJet in the background."
        case .slotsFull: return "Your BedJet's three slots are full. Start a night in Lull once and pick one to reuse."
        case let .unsupportedMode(why): return why
        case .airOff: return "Your BedJet is off. Turn it on first."
        case let .fixedTemperature(mode): return "\(mode.title) runs at its own temperature."
        case .heatOnlyTemperature: return "A BedJet V2 only takes a temperature for Heat."
        case .scheduleSetsTiming: return "A schedule is running and sets when the air turns off. End it or choose a mode first."
        case .updating: return "Your BedJet is busy with its Wi-Fi or a firmware update. Try again once it's done."
        }
    }
}

/// An action that didn't work, in the words Lull's alert would use.
struct AutomationFailure: LocalizedError, Equatable {
    var message: String
    var errorDescription: String? { message }
}

/// How an action's values are kept to what the BedJet allows, as the Remote's controls keep them.
enum Automation {
    /// A temperature in the unit Lull shows, kept to the range the BedJet reports for the mode, or else the mode's own.
    static func halfCelsius(degrees: Int, fahrenheit: Bool, mode: Mode, status: DeviceStatus?) -> Int {
        // Shortcuts passes any number. One far outside every mode's range is brought near it first, since converting
        // it as it is would overflow.
        let degrees = min(max(degrees, -1000), 1000)
        var limits = mode.temperatureRange
        if let status, status.mode == mode, status.minTemperature < status.maxTemperature {
            limits = status.minTemperature...status.maxTemperature
        }
        return min(max(Format.halfCelsius(fromDegrees: degrees, fahrenheit: fahrenheit), limits.lowerBound), limits.upperBound)
    }

    /// Why a temperature can't be set for air running in `mode`, if it can't.
    static func temperatureRefusal(mode: Mode, generation: Generation) -> AutomationError? {
        guard !generation.adjustsTemperature(mode) else { return nil }
        return generation == .v2 && mode.usesTemperature ? .heatOnlyTemperature : .fixedTemperature(mode)
    }

    /// 5 to 100%, in the steps of 5 the BedJet's fan runs at.
    static func fanPercent(_ percent: Int) -> Int {
        min(max(Int((Double(percent) / 5).rounded()) * 5, 5), 100)
    }

    /// Minutes until the air turns off, kept to the longest the BedJet allows for the air it is running.
    static func runTimeMinutes(_ minutes: Int, mode: Mode, status: DeviceStatus?) -> Int {
        var longest = mode.maxMinutes
        if let status, status.mode == mode, status.maxRuntimeMinutes > 0 { longest = status.maxRuntimeMinutes }
        return min(max(minutes, 1), longest)
    }

    /// What an air action did, such as "Turned on Heat on My side: 86°, fan 40%." or "Set the fan on My side to 40%."
    static func airDone(_ applied: AppliedAir, asked air: AirSettings, on title: String, fahrenheit: Bool) -> String {
        if air.mode != nil {
            // What was set besides the mode, as the BedJet took it, since any of it may have been kept to its limits.
            var set: [String] = []
            if let temperature = applied.temperature { set.append("\(Format.degrees(temperature, fahrenheit: fahrenheit))°") }
            if let fan = applied.fanPercent { set.append("fan \(fan)%") }
            if let minutes = applied.minutes { set.append("turns off in \(Format.duration(minutes))") }
            let details = set.isEmpty ? "" : ": " + set.joined(separator: ", ")
            return "Turned on \(applied.mode.title) on \(title)\(details)."
        }
        var said: [String] = []
        if let temperature = applied.temperature { said.append("Set \(title) to \(Format.degrees(temperature, fahrenheit: fahrenheit))°.") }
        if let fan = applied.fanPercent { said.append("Set the fan on \(title) to \(fan)%.") }
        if let minutes = applied.minutes { said.append("\(title) turns off in \(Format.duration(minutes)).") }
        return said.joined(separator: " ")
    }
}

/// What Get BedJet Status says, from what Lull knows without connecting.
struct BedJetReport: Equatable {
    var nightRunning = false
    /// Nil for a schedule started from another phone.
    var schedule: String?
    var nightEnds: Date?
    var mode = Mode.standby
    /// Half-degrees Celsius, for modes that have one.
    var temperature: Int?
    var fanPercent: Int?
    var lastSeen: Date?

    init(run: ActiveRun?, reading: AirReading?, generation: Generation, now: Date = Date()) {
        lastSeen = reading?.at
        // A night started from this phone is known phase by phase, connected or not.
        if let run, let phase = run.phase(at: now)?.phase {
            nightRunning = true
            schedule = run.schedule.name
            nightEnds = run.endsAt
            setAir(phase.mode, temperature: phase.temperature, fanPercent: phase.fanPercent, generation: generation)
            return
        }
        // Otherwise only until the BedJet's timer, or the phase another phone started, would have run out.
        guard let reading, reading.at.addingTimeInterval(TimeInterval(reading.remainingSeconds)) > now else { return }
        nightRunning = reading.programStep > 0
        setAir(reading.mode, temperature: reading.temperature, fanPercent: reading.fanPercent, generation: generation)
    }

    private mutating func setAir(_ mode: Mode, temperature: Int, fanPercent: Int, generation: Generation) {
        self.mode = mode
        self.temperature = generation.adjustsTemperature(mode) ? temperature : nil
        self.fanPercent = mode.usesFan ? fanPercent : nil
    }

    /// The report in a sentence, such as "My side is on: Heat, 86°, fan 40%."
    func summary(of title: String, fahrenheit: Bool) -> String {
        if nightRunning {
            return schedule.map { "“\($0)” is running on \(title)." } ?? "A schedule from another phone is running on \(title)."
        }
        guard lastSeen != nil else { return "Lull hasn't reached \(title) yet." }
        guard mode != .standby else { return "\(title) is off." }
        var parts = [mode.title]
        if let temperature { parts.append("\(Format.degrees(temperature, fahrenheit: fahrenheit))°") }
        if let fanPercent { parts.append("fan \(fanPercent)%") }
        return "\(title) is on: \(parts.joined(separator: ", "))."
    }
}

extension AppModel {
    /// The BedJet an action is for: the one it names, or the one Lull shows. Lull never picks one by itself.
    func automationTarget(_ id: BedJet.ID?) throws -> BedJet {
        if let id {
            guard let named = bedJets.first(where: { $0.id == id }) else { throw AutomationError.forgottenBedJet }
            return named
        }
        guard let bedJet else { throw AutomationError.noBedJet }
        return bedJet
    }

    /// Does what an action asked of a BedJet and says what happened. `mayAsk` is for an action that went on in
    /// Lull, which asks another phone for the BedJet as its controls do.
    func perform(_ request: AutomationRequest, on bedJet: BedJet, mayAsk: Bool = false) async throws -> String {
        let name = title(of: bedJet)
        do {
            // Restarting into new firmware, it can't be reached until the install is done, by Lull either, so going on in
            // Lull wouldn't help. Lull stops expecting it back ten minutes after it was asked to check.
            if bedJet.isInstallingUpdate { throw AutomationError.updating }
            switch request {
            case let .startNight(id):
                let schedule = id == nil ? bedJet.tonight : schedules.first { $0.id == id }
                guard let schedule else { throw id == nil ? AutomationError.noSchedule : .forgottenSchedule }
                // Lull's slot choice starts tonight's schedule on the BedJet on screen, so only that can wait on it.
                let choosingSlot = mayAsk && schedule.id == bedJet.tonight?.id && bedJet.id == self.bedJet?.id
                switch try await reaching(bedJet, mayAsk: mayAsk, { try await bedJet.start(schedule, choosingSlot: choosingSlot) }) {
                case .now: return "Started “\(schedule.name)” on \(name)."
                case .choosingSlot: return "Your BedJet's three slots are full. Pick one to reuse in Lull."
                case let .alreadyRunning(running?): return "“\(running)” is already running on \(name)."
                case .alreadyRunning(nil): return "A schedule from another phone is already running on \(name)."
                }
            case let .air(air):
                let applied = try await reaching(bedJet, mayAsk: mayAsk) { try await bedJet.apply(air, fahrenheit: self.useFahrenheit) }
                return Automation.airDone(applied, asked: air, on: name, fahrenheit: useFahrenheit)
            case .turnOff:
                switch try await reaching(bedJet, mayAsk: mayAsk, { try await bedJet.turnOff() }) {
                case let .endedNight(schedule?): return "Ended “\(schedule)” on \(name)."
                case .endedNight(nil): return "Ended the night on \(name)."
                case .stoppedAir: return "Turned off \(name)."
                case .alreadyOff: return "\(name) is already off."
                }
            case let .lightRing(on):
                try await reaching(bedJet, mayAsk: mayAsk) { try await bedJet.turnLightRing(on: on) }
                return on ? "Turned on the light ring on \(name)." : "Turned off the light ring on \(name)."
            case let .muteBeeps(muted):
                try await reaching(bedJet, mayAsk: mayAsk) { try await bedJet.muteBeeps(muted) }
                return muted ? "Muted beeps on \(name)." : "Unmuted beeps on \(name)."
            }
        } catch AutomationError.needsLull {
            // Left for the action to decide whether to go on in Lull.
            throw AutomationError.needsLull
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw AutomationFailure(message: attributed(error.localizedDescription, to: bedJet))
        }
    }

    /// What Get BedJet Status reports, from what Lull already knows. It never connects.
    func status(of bedJet: BedJet) -> BedJetReport {
        BedJetReport(run: bedJet.run, reading: bedJet.reading, generation: bedJet.generation)
    }
}
