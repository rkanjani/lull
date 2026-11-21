import BedJetKit
import Foundation

/// One stretch of the night: what the BedJet does and for how long.
struct Phase: Identifiable, Codable, Hashable {
    var id = UUID()
    var mode: Mode
    /// Half-degrees Celsius, the unit the BedJet stores.
    var temperature: Int
    var fanPercent: Int
    var minutes: Int

    init(_ mode: Mode, temperature: Int? = nil, fan: Int = 40, minutes: Int) {
        self.mode = mode
        self.temperature = temperature ?? mode.defaultTemperature
        self.fanPercent = fan
        self.minutes = minutes
    }

    /// Position on the night curve: -1 is the strongest cooling, +1 the strongest heat.
    var level: Double {
        switch mode {
        case .cool: return -(0.25 + 0.75 * Double(fanPercent) / 100)
        case .dry: return -0.15
        case .standby, .wait: return 0
        case .heat, .extendedHeat, .turbo: return 0.2 + 0.8 * Double(temperature - 38) / 48
        }
    }

    /// Longest this phase may run: the mode's own ceiling, and for Heat the BedJet's safety cut-off,
    /// which comes sooner the hotter and faster the air.
    var maxMinutes: Int {
        min(mode.maxMinutes, ProgramStep.longestMinutes(mode: mode, temperature: temperature, fanStep: fanPercent / 5 - 1))
    }

    var step: ProgramStep {
        ProgramStep(mode: mode, fanStep: fanPercent / 5 - 1, temperature: temperature, hours: minutes / 60, minutes: minutes % 60)
    }

    /// What this phase is set to on a kind of BedJet, such as "75° · Fan 40%", worded like the schedule editor.
    func setting(on generation: Generation, fahrenheit: Bool) -> String {
        Format.setting(mode: mode, temperature: generation.adjustsTemperature(mode) ? temperature : nil,
                       fanPercent: fanPercent, fahrenheit: fahrenheit)
    }
}

struct Schedule: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var phases: [Phase]
    /// Minutes after midnight; used to label the timeline. A schedule runs from whenever it is started.
    var bedtime = 22 * 60 + 30

    var totalMinutes: Int { phases.reduce(0) { $0 + $1.minutes } }
    var program: StoredProgram { StoredProgram(name: name, steps: phases.map(\.step)) }

    func startMinute(of phase: Phase) -> Int {
        bedtime + phases.prefix { $0.id != phase.id }.reduce(0) { $0 + $1.minutes }
    }
}

extension Schedule {
    init(importing program: StoredProgram, bedtime: Int = 22 * 60 + 30) {
        var clock = bedtime
        let phases = program.steps.map { step -> Phase in
            var minutes = step.hours * 60 + step.minutes
            // Steps that end at a time of day become durations, measured from the usual bedtime.
            if step.endsAtClockTime { minutes = ((minutes - clock) % 1440 + 1440) % 1440 }
            clock += minutes
            return Phase(step.mode, temperature: step.temperature, fan: (step.fanStep + 1) * 5, minutes: max(minutes, 1))
        }
        self.init(name: program.name.isEmpty ? "From BedJet" : program.name, phases: phases, bedtime: bedtime)
    }

    static let templates: [Schedule] = [
        Schedule(name: "Cool Drift", phases: [
            Phase(.cool, fan: 60, minutes: 45),
            Phase(.cool, fan: 30, minutes: 300),
            Phase(.cool, fan: 15, minutes: 90),
            Phase(.heat, temperature: 54, fan: 30, minutes: 30),
        ]),
        Schedule(name: "Warm Welcome", phases: [
            Phase(.turbo, fan: 100, minutes: 10),
            Phase(.heat, temperature: 68, fan: 40, minutes: 30),
            Phase(.cool, fan: 20, minutes: 360),
            Phase(.heat, temperature: 58, fan: 30, minutes: 40),
        ]),
        Schedule(name: "Hot Sleeper", phases: [
            Phase(.cool, fan: 100, minutes: 60),
            Phase(.cool, fan: 70, minutes: 240),
            Phase(.cool, fan: 40, minutes: 150),
        ]),
        Schedule(name: "Winter Cocoon", phases: [
            Phase(.heat, temperature: 72, fan: 50, minutes: 30),
            Phase(.extendedHeat, temperature: 62, fan: 30, minutes: 210),
            Phase(.extendedHeat, temperature: 58, fan: 20, minutes: 240),
        ]),
    ]
}

extension Mode {
    /// Modes a phase can use, in the order the editor offers them.
    static let phaseModes: [Mode] = [.cool, .heat, .extendedHeat, .turbo, .dry, .wait]
    static let remoteModes: [Mode] = [.cool, .heat, .extendedHeat, .turbo, .dry]

    var title: String {
        switch self {
        case .standby: return "Off"
        case .heat: return "Heat"
        case .turbo: return "Turbo"
        case .extendedHeat: return "Gentle heat"
        case .cool: return "Cool"
        case .dry: return "Dry"
        case .wait: return "Pause"
        }
    }

    var activity: String {
        switch self {
        case .standby: return "Off"
        case .heat: return "Heating"
        case .turbo: return "Turbo heating"
        case .extendedHeat: return "Gently heating"
        case .cool: return "Cooling"
        case .dry: return "Drying"
        case .wait: return "Paused"
        }
    }

    var symbol: String {
        switch self {
        case .standby: return "power"
        case .heat: return "flame"
        case .turbo: return "bolt.fill"
        case .extendedHeat: return "sun.min"
        case .cool: return "snowflake"
        case .dry: return "wind"
        case .wait: return "pause"
        }
    }

    var defaultTemperature: Int {
        switch self {
        case .heat: return 66
        case .extendedHeat: return 58
        case .cool: return 44
        case .dry: return 60
        default: return temperatureRange.lowerBound
        }
    }

    /// Longest a single phase may run. Turbo and Heat limits follow the unit's own safety timers.
    var maxMinutes: Int {
        switch self {
        case .turbo: return 10
        case .heat: return 240
        default: return Limits.maxStepMinutes
        }
    }
}

enum Format {
    static func clock(_ minuteOfDay: Int) -> String {
        let minute = ((minuteOfDay % 1440) + 1440) % 1440
        let date = Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
        return date.formatted(date: .omitted, time: .shortened)
    }

    static func duration(_ minutes: Int) -> String {
        let hours = minutes / 60, rest = minutes % 60
        if hours == 0 { return "\(rest) min" }
        return rest == 0 ? "\(hours) hr" : "\(hours) hr \(rest) min"
    }

    /// A duration in words, for VoiceOver: "2 hours, 30 minutes" rather than "2 hr 30 min".
    static func spokenDuration(_ minutes: Int) -> String {
        spoken.string(from: TimeInterval(minutes * 60)) ?? duration(minutes)
    }

    private static let spoken: DateComponentsFormatter = {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.hour, .minute]
        formatter.unitsStyle = .full
        return formatter
    }()

    static func degrees(_ halfCelsius: Int, fahrenheit: Bool) -> Int {
        let celsius = Double(halfCelsius) / 2
        return Int((fahrenheit ? celsius * 9 / 5 + 32 : celsius).rounded())
    }

    /// What a phase or the Remote is set to, such as "75° · Fan 40%", worded like the schedule editor. The temperature
    /// is nil for modes that don't let you set it.
    static func setting(mode: Mode, temperature: Int?, fanPercent: Int, fahrenheit: Bool) -> String {
        var parts: [String] = []
        if let temperature { parts.append("\(degrees(temperature, fahrenheit: fahrenheit))°") }
        if mode.usesFan { parts.append("Fan \(fanPercent)%") }
        return parts.isEmpty ? "Air off" : parts.joined(separator: " · ")
    }

    static func halfCelsius(fromDegrees degrees: Int, fahrenheit: Bool) -> Int {
        let celsius = fahrenheit ? Double(degrees - 32) * 5 / 9 : Double(degrees)
        return Int((celsius * 2).rounded())
    }
}
