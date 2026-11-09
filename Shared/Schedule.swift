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
    /// True when this phase keeps its length while a wake time stretches or shrinks the rest of the night. Left out when
    /// false, so schedules read the same in versions of Lull before it.
    var fixedLength: Bool?

    init(_ mode: Mode, temperature: Int? = nil, fan: Int = 40, minutes: Int, fixedLength: Bool = false) {
        self.mode = mode
        self.temperature = temperature ?? mode.defaultTemperature
        self.fanPercent = fan
        self.minutes = minutes
        self.fixedLength = fixedLength ? true : nil
    }

    var hasFixedLength: Bool { fixedLength == true }

    /// How short and how long this phase may run once a wake time scales the night: its own length when it's fixed, or
    /// anything from BedJet's 5 minutes to its longest.
    var lengthLimits: ClosedRange<Int> { hasFixedLength ? minutes...minutes : 5...max(maxMinutes, 5) }

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

    /// The step a BedJet 3 runs until a time of day, minutes after midnight, rather than for a length of time.
    func step(endingAt minuteOfDay: Int) -> ProgramStep {
        let clock = (minuteOfDay % 1440 + 1440) % 1440
        var step = self.step
        (step.hours, step.minutes, step.endsAtClockTime) = (clock / 60, clock % 60, true)
        return step
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
    /// Minutes after midnight the night ends at, if it has a wake time. Whenever the night is started, its phases stretch or
    /// shrink in proportion to reach it. The lengths stored are a night from the usual bedtime, which the editor fits to end
    /// at it, and they're the proportions other nights are scaled by.
    var wakeTime: Int?

    var totalMinutes: Int { phases.reduce(0) { $0 + $1.minutes } }

    /// When the night ends if it runs from the usual bedtime for its set lengths. A new wake time starts here, so nothing moves.
    var usualWakeTime: Int { (bedtime + totalMinutes) % 1440 }

    /// What's saved to the BedJet. With a wake time the last step ends at that time of day, which a BedJet 3 keeps to by
    /// its own clock; a V2 is handed it as a length, measured from when it is stored.
    var program: StoredProgram {
        var steps = phases.map(\.step)
        if let wakeTime, let last = phases.last { steps[steps.count - 1] = last.step(endingAt: wakeTime) }
        return StoredProgram(name: name, steps: steps)
    }

    func startMinute(of phase: Phase) -> Int {
        bedtime + phases.prefix { $0.id != phase.id }.reduce(0) { $0 + $1.minutes }
    }

    /// Whether this is the schedule a program read from a BedJet was saved from or imported as, so it isn't kept twice.
    func matches(_ program: StoredProgram) -> Bool {
        matches(imported: Schedule(importing: program))
    }

    /// `matches(_:)` for a program already imported, so a list of schedules can be checked against it once.
    func matches(imported: Schedule) -> Bool {
        if let wakeTime, wakeTime == imported.wakeTime {
            // Its lengths are scaled afresh each night, so only what each phase does has to agree.
            return phases.count == imported.phases.count && zip(phases, imported.phases).allSatisfy {
                $0.mode == $1.mode && $0.temperature == $1.temperature && $0.fanPercent == $1.fanPercent
            }
        }
        // Copies kept before Lull knew wake times hold the same steps, all as lengths.
        return program.steps == imported.program.steps || (wakeTime == nil && phases.map(\.step) == imported.phases.map(\.step))
    }
}

// MARK: Wake time

extension Schedule {
    /// Longest Lull stretches a night to reach its wake time, so a start well after it isn't taken as a night nearly a day long.
    static let longestNight = 16 * 60

    /// How long a night started at `minuteOfDay` has until the wake time. Nil without one.
    func minutesToWake(startingAt minuteOfDay: Int) -> Int? {
        guard let wakeTime, !phases.isEmpty else { return nil }
        return ((wakeTime - minuteOfDay) % 1440 + 1440) % 1440
    }

    /// The night as it runs when started at `minuteOfDay`: with a wake time, every phase stretches or shrinks in
    /// proportion so the night ends then. A phase BedJet won't run longer or shorter stays at its limit, and the rest share
    /// the difference. Unchanged without a wake time, or while it's more than `longestNight` away, as during the day, so
    /// the timeline and widgets show the night as set rather than one stretched to most of a day.
    func resolved(startingAt minuteOfDay: Int) -> Schedule {
        guard let target = minutesToWake(startingAt: minuteOfDay), target <= Self.longestNight else { return self }
        let lengths = Schedule.scale(phases.map(\.minutes), within: phases.map(\.lengthLimits), to: target)
        var night = self
        for index in night.phases.indices { night.phases[index].minutes = lengths[index] }
        return night
    }

    /// With a wake time, how long the night from the usual bedtime runs to reach it: the night the editor shows. Nil
    /// without one.
    var usualNight: Int? { minutesToWake(startingAt: bedtime) }

    /// With a wake time, the night fitted to run from the usual bedtime to it, as Start Night would fit it then: the phases
    /// without a fixed length stretch or shrink in proportion, so the lengths the editor shows are the ones a usual night
    /// runs. `holding` keeps the length it was just given, within what leaves the others room. A night the phases can't
    /// fill or fit in is left as it is, for the caption to say why, so a time passed on the way to another doesn't squash
    /// them. Unchanged without a wake time.
    func fitted(holding id: Phase.ID? = nil) -> Schedule {
        guard let night = usualNight, night <= Self.longestNight else { return self }
        var schedule = self
        var limits = phases.map(\.lengthLimits)
        if let index = phases.firstIndex(where: { $0.id == id }), let choices = lengthChoices(of: phases[index]) {
            let minutes = min(max(phases[index].minutes, choices.lowerBound), choices.upperBound)
            schedule.phases[index].minutes = minutes
            limits[index] = minutes...minutes
        }
        guard limits.reduce(0, { $0 + $1.lowerBound }) <= night, night <= limits.reduce(0, { $0 + $1.upperBound }) else { return self }
        let lengths = Schedule.scale(schedule.phases.map(\.minutes), within: limits, to: night)
        for index in schedule.phases.indices { schedule.phases[index].minutes = lengths[index] }
        return schedule
    }

    /// The lengths the editor offers `phase`. With a wake time, the ones that leave the night from the usual bedtime room
    /// to end at it, since the other phases that stretch or shrink can't go shorter or longer than BedJet lets them. Nil
    /// when it's the only phase that stretches or shrinks, so its length is whatever the others leave.
    func lengthChoices(of phase: Phase) -> ClosedRange<Int>? {
        let longest = max(phase.maxMinutes, 5)
        guard let night = usualNight, night <= Self.longestNight else { return 5...longest }
        let others = phases.filter { $0.id != phase.id }
        let stretching = others.filter { !$0.hasFixedLength }
        // With nothing else to take up the difference, a phase that stretches is the rest of the night, and a fixed one
        // can't be fitted anyway.
        guard !stretching.isEmpty else { return phase.hasFixedLength ? 5...longest : nil }
        let left = night - others.filter(\.hasFixedLength).reduce(0) { $0 + $1.minutes }
        let shortest = max(left - stretching.reduce(0) { $0 + $1.lengthLimits.upperBound }, 5)
        let room = min(left - stretching.reduce(0) { $0 + $1.lengthLimits.lowerBound }, longest)
        // When no length would let the night fit, any will do: the caption says why it can't.
        return shortest <= room ? shortest...room : 5...longest
    }

    /// `lengthChoices(of:)` in BedJet's 5-minute steps, for the Duration slider. Nil when there's less than a step to choose
    /// from, so the sheet shows the length without a slider.
    func durationSteps(of phase: Phase) -> ClosedRange<Int>? {
        guard let choices = lengthChoices(of: phase) else { return nil }
        let (low, high) = ((choices.lowerBound + 4) / 5 * 5, choices.upperBound / 5 * 5)
        return low < high ? low...high : nil
    }

    /// Why the night from the usual bedtime can't be fitted to end at the wake time, if it can't.
    var usualNightProblem: String? {
        guard let wakeTime, let night = usualNight else { return nil }
        if night > Self.longestNight {
            return "\(Format.clock(wakeTime)) is more than \(Format.duration(Self.longestNight)) after \(Format.clock(bedtime)). "
                + "Set a later bedtime or an earlier wake time."
        }
        return wakeProblem(startingAt: bedtime)
    }

    /// Why a schedule with a wake time and only fixed phases can't stretch or shrink to reach it, if that's the case.
    var onlyFixedPhasesProblem: String? {
        guard let wakeTime, !phases.isEmpty, phases.allSatisfy(\.hasFixedLength) else { return nil }
        return "Every phase has a fixed length, so the night can't stretch or shrink to end at \(Format.clock(wakeTime)). Let a phase change length."
    }

    /// What the editor says about the phases: without a wake time, that they run for their set lengths and when the night
    /// ends; with one, what their lengths are for and how they reach it.
    var phasesCaption: String {
        guard let wakeTime else {
            return "Each phase runs for its set length, so the night ends around \(Format.clock(usualWakeTime))."
        }
        // When the night can't be fitted, say why rather than how it fits.
        if let problem = onlyFixedPhasesProblem ?? usualNightProblem { return problem }
        let wake = Format.clock(wakeTime)
        return "These lengths are for a night from \(Format.clock(bedtime)). Started earlier or later, the phases stretch or "
            + "shrink in proportion to end at \(wake), apart from any marked Fixed length."
    }

    /// With a wake time, whether `phase` stretches or shrinks with the night, or why it can't.
    func scalingNote(of phase: Phase) -> String? {
        guard let wakeTime else { return nil }
        if let onlyFixedPhasesProblem { return onlyFixedPhasesProblem }
        let wake = Format.clock(wakeTime)
        if phase.hasFixedLength {
            return "Stays \(Format.duration(phase.minutes)) while the rest of the night stretches or shrinks to end at \(wake)."
        }
        // The only phase that stretches or shrinks is whatever the rest leave, a phase on its own the whole night.
        if phases.allSatisfy({ $0.id == phase.id || $0.hasFixedLength }) {
            return phases.count == 1 ? "Fills the night, from \(Format.clock(bedtime)) to \(wake) on a usual night."
                : "Fills the rest of the night, so it's as long as the other phases leave before \(wake)."
        }
        return "Stretches or shrinks with the rest of the night to end at \(wake). Lengthening it shortens the others that aren't fixed."
    }

    /// Why the night can't end at its wake time when started at `minuteOfDay`, if it can't.
    func wakeProblem(startingAt minuteOfDay: Int) -> String? {
        guard let wakeTime, let target = minutesToWake(startingAt: minuteOfDay) else { return nil }
        let wake = Format.clock(wakeTime)
        if target > Self.longestNight {
            return "\(wake) is more than \(Format.duration(Self.longestNight)) away. Start the night nearer to it."
        }
        if target != totalMinutes, let onlyFixedPhasesProblem { return onlyFixedPhasesProblem }
        if target < phases.reduce(0, { $0 + $1.lengthLimits.lowerBound }) {
            return "Only \(Format.duration(target)) is left before \(wake), less than \(phases.count == 1 ? "a phase needs" : "these phases need"). "
                + "Set a later wake time."
        }
        let longest = phases.reduce(0) { $0 + $1.lengthLimits.upperBound }
        if target > longest {
            return "To end at \(wake), the night would run \(Format.duration(target)), but these phases can run for up to "
                + "\(Format.duration(longest)) in all. Add a phase or set an earlier wake time."
        }
        return nil
    }

    /// Whole minutes in proportion to `weights` that add up to `total`, each kept within its limits. Lengths at a limit are
    /// set aside and the rest share what's left; the minutes lost to rounding go to the lengths that lost most. When the
    /// limits can't add up to `total`, each length is as near it as its limits allow.
    static func scale(_ weights: [Int], within limits: [ClosedRange<Int>], to total: Int) -> [Int] {
        var fixed = [Int?](repeating: nil, count: weights.count)
        var ideal = [Double](repeating: 0, count: weights.count)
        while true {
            let free = weights.indices.filter { fixed[$0] == nil }
            guard !free.isEmpty else { break }
            let left = Double(total - fixed.compactMap { $0 }.reduce(0, +))
            let weight = Double(free.reduce(0) { $0 + max(weights[$1], 1) })
            for index in free { ideal[index] = left * Double(max(weights[index], 1)) / weight }
            let under = free.filter { ideal[$0] < Double(limits[$0].lowerBound) }
            let over = free.filter { ideal[$0] > Double(limits[$0].upperBound) }
            guard !under.isEmpty || !over.isEmpty else { break }
            // Only the side that's further out is held at its limits this round: raising the short ones leaves less for the
            // rest, which can bring the long ones back within theirs, and the other way round.
            let short = under.reduce(0.0) { $0 + Double(limits[$1].lowerBound) - ideal[$1] }
            let long = over.reduce(0.0) { $0 + ideal[$1] - Double(limits[$1].upperBound) }
            if short >= long { for index in under { fixed[index] = limits[index].lowerBound } }
            if long >= short { for index in over { fixed[index] = limits[index].upperBound } }
        }
        var lengths = weights.indices.map { fixed[$0] ?? Int(ideal[$0].rounded(.down)) }
        let free = weights.indices.filter { fixed[$0] == nil }
        var short = total - lengths.reduce(0, +)
        // Ties go to the earlier phase, as on Android, so both apps scale a night the same way.
        let order = free.sorted { a, b in
            let (lostA, lostB) = (ideal[a] - ideal[a].rounded(.down), ideal[b] - ideal[b].rounded(.down))
            return lostA != lostB ? lostA > lostB : a < b
        }
        for index in order where short > 0 {
            lengths[index] += 1
            short -= 1
        }
        return lengths
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
        // A last step that ends at a time of day is a wake time, as Lull saves one.
        let wakeTime = program.steps.last.flatMap { $0.endsAtClockTime ? $0.hours * 60 + $0.minutes : nil }
        self.init(name: program.name.isEmpty ? "From BedJet" : program.name, phases: phases, bedtime: bedtime, wakeTime: wakeTime)
    }

    /// What New Schedule starts from: one long cool phase, with a wake time where its night ends.
    static func blank() -> Schedule {
        var schedule = Schedule(name: "New schedule", phases: [Phase(.cool, fan: 40, minutes: 240)])
        schedule.wakeTime = schedule.usualWakeTime
        return schedule
    }

    /// The schedules Lull starts with. Each has a wake time where its night ends from the usual bedtime, so it plans the
    /// same night it always did, and stretches or shrinks when started earlier or later. Their short opening phases and
    /// closing warm-ups have a fixed length, so only the long middle of the night changes.
    static let templates: [Schedule] = [
        Schedule(name: "Cool Drift", phases: [
            Phase(.cool, fan: 60, minutes: 45, fixedLength: true),
            Phase(.cool, fan: 30, minutes: 300),
            Phase(.cool, fan: 15, minutes: 90),
            Phase(.heat, temperature: 54, fan: 30, minutes: 30, fixedLength: true),
        ], wakeTime: 6 * 60 + 15),
        Schedule(name: "Warm Welcome", phases: [
            Phase(.turbo, fan: 100, minutes: 10, fixedLength: true),
            Phase(.heat, temperature: 68, fan: 40, minutes: 30, fixedLength: true),
            Phase(.cool, fan: 20, minutes: 360),
            Phase(.heat, temperature: 58, fan: 30, minutes: 40, fixedLength: true),
        ], wakeTime: 5 * 60 + 50),
        Schedule(name: "Hot Sleeper", phases: [
            Phase(.cool, fan: 100, minutes: 60, fixedLength: true),
            Phase(.cool, fan: 70, minutes: 240),
            Phase(.cool, fan: 40, minutes: 150),
        ], wakeTime: 6 * 60),
        Schedule(name: "Winter Cocoon", phases: [
            Phase(.heat, temperature: 72, fan: 50, minutes: 30, fixedLength: true),
            Phase(.extendedHeat, temperature: 62, fan: 30, minutes: 210),
            Phase(.extendedHeat, temperature: 58, fan: 20, minutes: 240),
        ], wakeTime: 6 * 60 + 30),
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
    /// Minutes after midnight at `date`, in the phone's time zone.
    static func minuteOfDay(_ date: Date) -> Int {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    /// When the clock next reads `minuteOfDay` after `date`: the start of that minute, up to a day later. The minute
    /// `date` is in counts as tomorrow's.
    static func date(atNext minuteOfDay: Int, after date: Date) -> Date {
        let minute = ((minuteOfDay % 1440) + 1440) % 1440
        // By the calendar rather than a count of seconds, so a night that crosses a daylight-saving change still ends
        // at the time on the clock. A time the clocks skip moves on by the hour skipped, keeping its minutes, as on Android.
        let time = DateComponents(hour: minute / 60, minute: minute % 60, second: 0)
        return Calendar.current.nextDate(after: date, matching: time, matchingPolicy: .nextTimePreservingSmallerComponents) ?? date
    }

    /// Whole minutes from `date` until the clock next reads `minuteOfDay`, to the nearest minute, so a countdown of that
    /// long ends within half a minute of it.
    static func minutes(until minuteOfDay: Int, from date: Date) -> Int {
        Int((Self.date(atNext: minuteOfDay, after: date).timeIntervalSince(date) / 60).rounded())
    }

    /// The minute of the day a countdown of `seconds` from `date` ends, to the nearest minute, so a time worked out from
    /// the BedJet's seconds left holds still as they tick.
    static func endMinute(after seconds: Int, from date: Date) -> Int {
        minuteOfDay(date.addingTimeInterval(TimeInterval(seconds + 30)))
    }

    /// `minutes` from `date`, lengthened so the countdown ends on a multiple of `step` minutes: 6:30 or 6:45, not 6:37.
    static func minutesEndingOnStep(_ minutes: Int, step: Int, from date: Date) -> Int {
        let end = minuteOfDay(date) + minutes
        return minutes + (step - end % step) % step
    }

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
