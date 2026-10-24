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
    /// Every BedJet Lull knows and what each is doing, for the Tonight, Both Sides and Quick Remote widgets.
    var bedJets: [WidgetBedJet] = []
    /// The BedJet on screen in Lull, which those widgets show unless set to another.
    var onScreen: UUID?

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

    /// When the widgets need redrawing: now, every few minutes while any BedJet runs to move it along, and the
    /// moment each phase begins, each night ends and each Remote timer runs out.
    func redraws(from date: Date, every interval: TimeInterval = 300) -> [Date] {
        let running = bedJets.compactMap { $0.live(at: date) } + (run(at: date).map { [LiveStatus.night($0)] } ?? [])
        guard let last = running.map(\.endsAt).max() else { return [date] }
        let ticks = stride(from: date, to: last, by: interval).map { $0 }
        let changes = running.flatMap { live -> [Date] in
            switch live {
            case let .night(run): run.phaseEnds
            case let .air(_, _, _, endsAt): [endsAt]
            }
        }
        return Set(ticks + changes.filter { $0 > date }).sorted()
    }

    /// The BedJet a widget shows: the one picked for it, or the one on screen.
    func bedJet(_ id: UUID?) -> WidgetBedJet? {
        bedJets.first { $0.id == (id ?? onScreen) }
    }

    /// Whether widgets name the BedJet they show: once Lull knows more than one, so each side of the bed's can be told
    /// apart.
    var hasSeveralBedJets: Bool { bedJets.count > 1 }

    /// The name a widget heads itself with once Lull knows more than one BedJet, as the Tonight widget does. Nil with one.
    var headingName: String? {
        hasSeveralBedJets ? bedJet(nil)?.name : nil
    }

    /// A widget's heading, after the BedJet's name once there's more than one: "My side · Tonight".
    func headed(_ heading: String) -> String {
        headingName.map { "\($0) · \(heading)" } ?? heading
    }

    /// What VoiceOver reads for a widget, after the BedJet's name once there's more than one.
    func spoken(_ text: String) -> String {
        headingName.map { "\($0), \(text)" } ?? text
    }

    /// Opens Lull on a tab showing the BedJet this snapshot shows, so tapping a widget set to one side opens that side.
    func link(to tab: LullTab) -> URL {
        onScreen.map { tab.url(showing: $0) } ?? tab.url
    }

    /// The BedJet a widget not yet set to one shows: the one on screen in Lull, or the first Lull knows.
    var usualBedJet: WidgetBedJet? {
        bedJet(nil) ?? bedJets.first
    }

    /// What a widget set to one BedJet shows: that BedJet's tonight, running night and history in place of those of the
    /// one on screen, so the Tonight and Last Night widgets draw any side of the bed as they do the one on screen. A
    /// BedJet forgotten since leaves nothing to show. Nil is the one on screen.
    func showing(_ id: UUID?) -> WidgetSnapshot {
        guard let id else { return self }
        let bedJet = bedJets.first { $0.id == id }
        var shown = self
        shown.tonight = bedJet?.tonight
        shown.run = if case let .night(run)? = bedJet?.live { run } else { nil }
        shown.recentHistory = bedJet?.recentHistory ?? []
        shown.generation = bedJet?.generation ?? generation
        shown.onScreen = id
        return shown
    }

    /// The two BedJets a Both Sides widget shows: as picked, or else the one on screen on the left and another on the
    /// right. Set to the same one twice, the right shows the other. A side set to a BedJet forgotten since is empty.
    func sides(left: UUID?, right: UUID?) -> (left: WidgetBedJet?, right: WidgetBedJet?) {
        let leftSide = if let left { bedJets.first { $0.id == left } } else { usualBedJet }
        let rightSide = if let right, right != leftSide?.id {
            bedJets.first { $0.id == right }
        } else {
            bedJets.first { $0.id != leftSide?.id }
        }
        return (leftSide, rightSide)
    }

    /// What to save for a BedJet that just reported. The BedJet reports every second and its timer drifts against
    /// the phone's clock, so what was saved stays unless it is really different; otherwise the widgets would be
    /// redrawn every second, which iOS soon stops allowing.
    static func steadied(_ new: LiveStatus?, after old: LiveStatus?) -> LiveStatus? {
        guard let new, let old else { return new }
        return new.differsEnough(from: old) ? new : old
    }

    /// What a BedJet's widgets show after it reports. What was saved stays while Lull is still waiting to hear what it
    /// is doing (see `isWaiting(for:isSavingSchedule:)`), until it runs out.
    static func live(_ current: LiveStatus?, after old: LiveStatus?, isWaiting: Bool, at now: Date) -> LiveStatus? {
        if current == nil, isWaiting { return old.flatMap { $0.endsAt > now ? $0 : nil } }
        return steadied(current, after: old)
    }

    /// Whether Lull is still waiting to hear what a BedJet is doing: since launching, while a night is being saved to it,
    /// or while it runs before the second half of its report, which tells air from a schedule, has arrived.
    static func isWaiting(for status: DeviceStatus?, isSavingSchedule: Bool) -> Bool {
        guard let status else { return true }
        return isSavingSchedule || status.isRunning && status.extra == nil
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
    /// Reads what any version of Lull saved. Fields added since are filled in, so updating Lull doesn't blank the
    /// widgets before it is next opened.
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let empty = WidgetSnapshot()
        tonight = try values.decodeIfPresent(Schedule.self, forKey: .tonight)
        run = try values.decodeIfPresent(ActiveRun.self, forKey: .run)
        recentHistory = try values.decodeIfPresent([HistoryEntry].self, forKey: .recentHistory) ?? empty.recentHistory
        useFahrenheit = try values.decodeIfPresent(Bool.self, forKey: .useFahrenheit) ?? empty.useFahrenheit
        generation = try values.decodeIfPresent(Generation.self, forKey: .generation) ?? empty.generation
        bedJets = try values.decodeIfPresent([WidgetBedJet].self, forKey: .bedJets) ?? empty.bedJets
        onScreen = try values.decodeIfPresent(UUID.self, forKey: .onScreen)
    }
}

/// One BedJet as the widgets see it.
struct WidgetBedJet: Codable, Equatable, Identifiable {
    var id: UUID
    /// What the BedJet is called on screen, with its code when another known BedJet has the same name.
    var name: String
    /// What it was last known to be doing: a night started from this phone, or air from the Remote.
    var live: LiveStatus?
    var tonight: Schedule?
    var generation = Generation.v3
    /// The last two days of its history, for a Last Night widget set to it.
    var recentHistory: [HistoryEntry] = []

    /// What it is doing at `date`. Lull lets go of the BedJet when it leaves the screen, so this plays out by the clock.
    func live(at date: Date) -> LiveStatus? {
        live.flatMap { $0.endsAt > date ? $0 : nil }
    }
}

extension WidgetBedJet {
    /// Reads what any version of Lull saved, filling in fields added since, as the snapshot does.
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(UUID.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        live = try values.decodeIfPresent(LiveStatus.self, forKey: .live)
        tonight = try values.decodeIfPresent(Schedule.self, forKey: .tonight)
        generation = try values.decodeIfPresent(Generation.self, forKey: .generation) ?? .v3
        recentHistory = try values.decodeIfPresent([HistoryEntry].self, forKey: .recentHistory) ?? []
    }
}

extension WidgetSnapshot {
    /// A night a few hours into Cool Drift, shown in the widget gallery and while a widget loads.
    /// One side of the bed has it running too, cooled from the Remote, for the Tonight and Both Sides widgets.
    static func preview(at date: Date = Date()) -> WidgetSnapshot {
        let schedule = Schedule.templates[0]
        let run = ActiveRun(schedule: schedule, startedAt: date.addingTimeInterval(-200 * 60))
        let mine = WidgetBedJet(id: UUID(uuidString: "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10")!, name: "My side",
                                live: .night(run), tonight: schedule,
                                recentHistory: recent(HistoryEntry.samples(before: date), before: date))
        let theirs = WidgetBedJet(id: UUID(uuidString: "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D11")!, name: "Other side",
                                  live: .air(mode: .cool, temperature: 44, fanPercent: 40, endsAt: date.addingTimeInterval(45 * 60)),
                                  tonight: schedule)
        return WidgetSnapshot(
            tonight: schedule,
            run: run,
            recentHistory: recent(HistoryEntry.samples(before: date), before: date),
            bedJets: [mine, theirs],
            onScreen: mine.id
        )
    }
}
