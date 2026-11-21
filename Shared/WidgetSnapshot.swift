import BedJetKit
import Foundation

/// What the widgets show, written by the app whenever any of it changes. Widgets can't reach the BedJet and don't need
/// to: a schedule plays out on the BedJet by the clock, so where it is at any moment follows from when it started.
struct WidgetSnapshot: Codable, Equatable {
    var tonight: Schedule?
    var run: ActiveRun?
    /// The last couple of days of history: enough to find last night, even while tonight is running.
    var recentHistory: [HistoryEntry] = []
    var useFahrenheit = Locale.current.measurementSystem == .us
    var generation = Generation.v3

    static let appGroup = "group.com.rohankanjani.lull"

    private static var file: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?.appending(path: "widgets.json")
    }

    static func load() -> WidgetSnapshot? {
        guard let file, let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// Saves the snapshot and says whether it differs from what was there, so widgets are only redrawn when it does.
    @discardableResult
    func save() -> Bool {
        guard let file = Self.file, let data = try? JSONEncoder().encode(self) else { return false }
        if let existing = try? Data(contentsOf: file), existing == data { return false }
        return (try? data.write(to: file, options: .atomic)) != nil
    }

    /// The schedule running at `date`. Lull lets go of the BedJet overnight, so one that stopped early still
    /// shows as running until Lull is opened again and notices.
    func run(at date: Date) -> ActiveRun? {
        run.flatMap { $0.endsAt > date ? $0 : nil }
    }

    /// When the widgets need redrawing: now, every few minutes through a running night to move it along,
    /// and the moment each phase begins and the night ends.
    func redraws(from date: Date, every interval: TimeInterval = 300) -> [Date] {
        guard let run = run(at: date) else { return [date] }
        let ticks = stride(from: date, to: run.endsAt, by: interval).map { $0 }
        return Set(ticks + run.phaseEnds.filter { $0 > date }).sorted()
    }

    /// The latest night that's over: not tonight's, which begins at noon, and not one still running past midnight.
    func lastNight(at date: Date, calendar: Calendar = .current) -> Night? {
        let tonight = Night.id(for: calendar.startOfDay(for: date), calendar: calendar)
        let running = run(at: date).map { Night.id(for: Night.evening(of: $0.startedAt, calendar: calendar), calendar: calendar) }
        return Night.group(recentHistory).first { $0.id != tonight && $0.id != running }
    }

    func degrees(_ halfCelsius: Int) -> String {
        "\(Format.degrees(halfCelsius, fahrenheit: useFahrenheit))°"
    }

    /// What a phase is set to, such as "75° · Fan 40%", worded like the schedule editor.
    func detail(of phase: Phase) -> String {
        phase.setting(on: generation, fahrenheit: useFahrenheit)
    }

    /// History worth keeping for the widgets: the last two days, which always covers last night.
    static func recent(_ history: [HistoryEntry], before date: Date = Date()) -> [HistoryEntry] {
        let cutoff = date.addingTimeInterval(-48 * 3600)
        return history.filter { $0.date >= cutoff }
    }
}

extension WidgetSnapshot {
    /// A night a few hours into Cool Drift, shown in the widget gallery and while a widget loads.
    static func preview(at date: Date = Date()) -> WidgetSnapshot {
        let schedule = Schedule.templates[0]
        return WidgetSnapshot(
            tonight: schedule,
            run: ActiveRun(schedule: schedule, startedAt: date.addingTimeInterval(-200 * 60)),
            recentHistory: recent(HistoryEntry.samples(before: date), before: date)
        )
    }
}
