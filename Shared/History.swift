import BedJetKit
import Foundation

/// One thing the BedJet did, kept so the morning after can be checked against how the night felt.
struct HistoryEntry: Identifiable, Codable, Hashable {
    var id = UUID()
    var date: Date
    var event: Event
    /// What the BedJet measured in the room at the time, in half-degrees Celsius.
    var room: Int?
    /// Lull lets go of the BedJet when it leaves the screen, so a change made meanwhile, on the BedJet's
    /// own remote or another phone, is only seen when Lull is opened again and is dated then.
    var noticedLater = false

    enum Event: Codable, Hashable {
        /// The air the BedJet settled on, whichever phone or remote chose it.
        case air(mode: Mode, temperature: Int, fanPercent: Int)
        case off(timerRanOut: Bool)
        /// Names and phases are missing for schedules started from another phone.
        case scheduleStarted(name: String?, phases: [Phase]?)
        case scheduleEnded(name: String?, ending: Ending)
        /// A schedule moving on to its next phase, counted from one. Never recorded: the BedJet plays a schedule
        /// on its own while Lull is closed, so these are worked out from when it started, see `Night.timeline`.
        case phaseStarted(number: Int, of: Int, phase: Phase)
    }

    enum Ending: Codable, Hashable {
        case byYou
        case finished
        case early(minutes: Int)
    }
}

/// Everything recorded between one noon and the next, so 3 a.m. belongs with the evening before.
struct Night: Identifiable {
    /// The evening the night began, as `yyyy-MM-dd`.
    let id: String
    let evening: Date
    /// Oldest first, so the night reads in the order it happened.
    var entries: [HistoryEntry]

    static func group(_ entries: [HistoryEntry]) -> [Night] {
        Dictionary(grouping: entries) { evening(of: $0.date) }
            .map { evening, entries in Night(id: id(for: evening), evening: evening, entries: entries.sorted { $0.date < $1.date }) }
            .sorted { $0.evening > $1.evening }
    }

    static func evening(of date: Date, calendar: Calendar = .current) -> Date {
        calendar.startOfDay(for: date.addingTimeInterval(-12 * 3600))
    }

    static func id(for evening: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: evening)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// Schedules started this night, in order, for naming the night at a glance.
    var scheduleNames: [String] {
        entries.compactMap { entry in
            guard case let .scheduleStarted(name, _) = entry.event else { return nil }
            return name ?? "A schedule"
        }
    }

    /// The night redrawn as phases, so it can be drawn like a schedule. Each entry lasts until the next. A schedule
    /// plays out its own phases, and a change made during one lasts until the schedule moves to its next phase.
    var phases: [Phase] {
        var phases: [Phase] = []
        var schedule: (startedAt: Date, phases: [Phase])?
        for (index, entry) in entries.enumerated() {
            let next = entries.indices.contains(index + 1) ? entries[index + 1].date : nil
            switch entry.event {
            case let .scheduleStarted(_, schedulePhases?):
                schedule = (entry.date, schedulePhases)
                let end = next ?? entry.date.addingTimeInterval(TimeInterval(schedulePhases.reduce(0) { $0 + $1.minutes } * 60))
                phases += Self.play(schedulePhases, startedAt: entry.date, from: entry.date, to: end)
            case let .air(mode, temperature, fan):
                // How long the last setting lasted is unknown, so it gets a short stretch to be visible.
                let end = next ?? entry.date.addingTimeInterval(30 * 60)
                let tweak = { (until: Date) in Phase(mode, temperature: temperature, fan: fan, minutes: Self.minutes(from: entry.date, to: until)) }
                guard let schedule, let phaseEnds = Self.phaseEnd(of: schedule.phases, startedAt: schedule.startedAt, after: entry.date), phaseEnds < end else {
                    phases.append(tweak(end))
                    continue
                }
                phases.append(tweak(phaseEnds))
                phases += Self.play(schedule.phases, startedAt: schedule.startedAt, from: phaseEnds, to: end)
            case .off, .scheduleStarted, .scheduleEnded:
                schedule = nil
                if let next { phases.append(Phase(.wait, minutes: Self.minutes(from: entry.date, to: next))) }
            case .phaseStarted:
                continue
            }
        }
        return phases.filter { $0.minutes > 0 }
    }

    /// The night's entries with each phase a schedule moved on to slotted in, up to `now`. A schedule runs until
    /// something other than a change of air ends it, as `phases` draws it.
    func timeline(until now: Date = Date()) -> [HistoryEntry] {
        var timeline = entries
        for (index, entry) in entries.enumerated() {
            guard case let .scheduleStarted(_, schedulePhases?) = entry.event else { continue }
            let ended = entries[(index + 1)...].first { if case .air = $0.event { false } else { true } }?.date ?? now
            var phaseStart = entry.date
            for (number, phase) in zip(1..., schedulePhases) {
                if number > 1 {
                    guard phaseStart < min(ended, now) else { break }
                    // Kept the same from one drawing to the next, so the list doesn't see a new row each time.
                    var id = entry.id.uuid
                    id.15 &+= UInt8(truncatingIfNeeded: number)
                    timeline.append(HistoryEntry(id: UUID(uuid: id), date: phaseStart,
                                                 event: .phaseStarted(number: number, of: schedulePhases.count, phase: phase)))
                }
                phaseStart.addTimeInterval(TimeInterval(phase.minutes * 60))
            }
        }
        return timeline.sorted { $0.date < $1.date }
    }

    /// The stretch of a schedule between two times, idle once the schedule is over.
    private static func play(_ schedule: [Phase], startedAt: Date, from: Date, to: Date) -> [Phase] {
        var played: [Phase] = []
        var phaseStart = startedAt
        for phase in schedule {
            let phaseEnd = phaseStart.addingTimeInterval(TimeInterval(phase.minutes * 60))
            defer { phaseStart = phaseEnd }
            guard phaseEnd > from, phaseStart < to else { continue }
            var slice = phase
            slice.minutes = minutes(from: max(phaseStart, from), to: min(phaseEnd, to))
            played.append(slice)
        }
        if phaseStart < to { played.append(Phase(.wait, minutes: minutes(from: max(phaseStart, from), to: to))) }
        return played
    }

    private static func phaseEnd(of schedule: [Phase], startedAt: Date, after date: Date) -> Date? {
        var phaseEnd = startedAt
        for phase in schedule {
            phaseEnd = phaseEnd.addingTimeInterval(TimeInterval(phase.minutes * 60))
            if phaseEnd > date { return phaseEnd }
        }
        return nil
    }

    private static func minutes(from start: Date, to end: Date) -> Int {
        max(Int((end.timeIntervalSince(start) / 60).rounded()), 0)
    }
}

extension HistoryEntry {
    /// A few believable nights ending last night, for screenshots and trying History without a BedJet.
    static func samples(before now: Date = Date(), calendar: Calendar = .current) -> [HistoryEntry] {
        let today = calendar.startOfDay(for: now)
        func at(_ nightsAgo: Int, _ hour: Int, _ minute: Int, _ event: Event, room: Int) -> HistoryEntry {
            // Hours past midnight belong to the morning after the evening.
            let day = calendar.date(byAdding: .day, value: hour < 12 ? 1 - nightsAgo : -nightsAgo, to: today) ?? today
            let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
            return HistoryEntry(date: date, event: event, room: room)
        }
        let coolDrift = Schedule.templates[0], warmWelcome = Schedule.templates[1]
        return [
            at(3, 22, 45, .scheduleStarted(name: warmWelcome.name, phases: warmWelcome.phases), room: 40),
            at(3, 7, 5, .scheduleEnded(name: warmWelcome.name, ending: .finished), room: 39),

            at(2, 22, 15, .air(mode: .heat, temperature: 70, fanPercent: 50), room: 41),
            at(2, 23, 0, .air(mode: .cool, temperature: 44, fanPercent: 40), room: 43),
            at(2, 2, 30, .air(mode: .cool, temperature: 44, fanPercent: 20), room: 42),
            at(2, 5, 45, .air(mode: .heat, temperature: 58, fanPercent: 30), room: 40),
            at(2, 6, 30, .off(timerRanOut: true), room: 41),

            at(1, 22, 30, .scheduleStarted(name: coolDrift.name, phases: coolDrift.phases), room: 46),
            at(1, 1, 50, .air(mode: .cool, temperature: 44, fanPercent: 70), room: 47),
            at(1, 7, 15, .scheduleEnded(name: coolDrift.name, ending: .finished), room: 44),
        ]
    }
}
