import BedJetKit
import Foundation
import Observation
import WidgetKit

/// Where Lull keeps what it saves. UI tests (`-ephemeral`) and model tests keep nothing in the app's own place.
struct Storage {
    /// Nil keeps nothing between launches.
    var directory: URL?
    var defaults: UserDefaults
    /// Only the app itself hands the widgets what they show.
    var publishesWidgets = false

    static let app = Storage(directory: AppModel.isEphemeral ? nil : .applicationSupportDirectory, defaults: .standard, publishesWidgets: true)

    func file(_ name: String) -> URL? { directory?.appending(path: name) }
    func historyFile(for id: UUID) -> URL? { file("history-\(id.uuidString).json") }
}

@MainActor
@Observable
final class AppModel {
    /// The BedJets this phone was told to use, in the order they were chosen. Each stays connected, so a
    /// schedule or setting on one is never disturbed by looking at another.
    private(set) var bedJets: [BedJet] = []
    private(set) var selectedID: BedJet.ID?
    /// The BedJet every screen shows and controls. None until one is chosen, and Lull never picks one by itself.
    var bedJet: BedJet? { bedJets.first { $0.id == selectedID } }
    var hasSeveralBedJets: Bool { bedJets.count > 1 }
    /// Switching waits while a schedule is being saved to the BedJet on screen, so how that went is seen.
    var canSwitch: Bool { bedJet?.isSavingSchedule != true }

    /// BedJets advertising nearby that aren't already known, while a picker is looking.
    var nearby: [NearbyBedJet] { found.filter { unit in !bedJets.contains { $0.unit == unit.id } } }
    /// `.searching` while looking is possible, otherwise why it isn't.
    private(set) var lookingState: LinkState = .searching
    private var found: [NearbyBedJet] = []

    var schedules: [Schedule] = [] { didSet { save() } }

    var useFahrenheit: Bool {
        didSet {
            storage.defaults.set(useFahrenheit, forKey: "useFahrenheit")
            publishWidgets()
            publishLiveActivities()
        }
    }
    var demoMode: Bool {
        didSet {
            storage.defaults.set(demoMode, forKey: "demoMode")
            let leaving = fleet
            show(otherFleet ?? startingFleet(demo: demoMode))
            otherFleet = leaving
            restartLooking()
            save()
        }
    }

    var errorMessage: String?

    @ObservationIgnored let storage: Storage
    @ObservationIgnored private let makesLink: ((BedJet) -> BedJetLink)?
    @ObservationIgnored private let makesDiscovery: (Bool) -> BedJetDiscovery
    @ObservationIgnored private var discovery: BedJetDiscovery?
    @ObservationIgnored private var lookers = 0
    @ObservationIgnored private let liveActivities = LiveActivities()
    @ObservationIgnored private var publishedWidgets: WidgetSnapshot?
    private var isLoaded = false
    /// The BedJets of the mode not in use, kept as they were saved.
    private var otherFleet: Fleet?
    /// Forgotten units, with the history they had, so choosing one again brings its nights back.
    private var retired: [UUID: BedJet.ID] = [:]
    /// Set once the BedJet on screen was forgotten, so the next launch asks rather than picking another.
    private var needsChoice = false

    /// - Parameters:
    ///   - makeLink: Connects to a BedJet. By default a simulated one in demo mode and Bluetooth otherwise.
    ///   - discovery: Finds BedJets nearby, given whether demo mode is on.
    init(storage: Storage = .app, demoMode: Bool? = nil, makeLink: ((BedJet) -> BedJetLink)? = nil,
         discovery: ((Bool) -> BedJetDiscovery)? = nil) {
        self.storage = storage
        makesLink = makeLink
        makesDiscovery = discovery ?? { demo in demo ? DemoDiscovery() as BedJetDiscovery : BLEDiscovery() }
        let defaults = storage.defaults
        useFahrenheit = defaults.object(forKey: "useFahrenheit") as? Bool ?? (Locale.current.measurementSystem == .us)
        #if targetEnvironment(simulator)
        self.demoMode = demoMode ?? true
        #else
        self.demoMode = demoMode ?? defaults.bool(forKey: "demoMode")
        #endif
        load()
    }

    // MARK: BedJets

    /// Shows another known BedJet. It only asks another phone for the unit once a control is used.
    @discardableResult
    func select(_ id: BedJet.ID) -> Bool {
        guard id != selectedID else { return true }
        guard canSwitch, bedJets.contains(where: { $0.id == id }) else { return false }
        selectedID = id
        needsChoice = false
        save()
        return true
    }

    /// Adds a BedJet the person picked from those nearby and shows it.
    func choose(_ unit: NearbyBedJet) {
        if let known = bedJets.first(where: { $0.unit == unit.id }) {
            select(known.id)
            return
        }
        guard canSwitch else { return }
        var saved = SavedBedJet(id: retired.removeValue(forKey: unit.id) ?? UUID(), unit: unit.id, name: unit.name, generation: unit.generation)
        saved.tonightID = bedJet?.tonightID ?? schedules.first?.id
        let added = BedJet(saved, isDemo: demoMode, app: self)
        bedJets.append(added)
        selectedID = added.id
        needsChoice = false
        added.connect(asking: true)
        save()
    }

    /// Stops using a BedJet. The unit carries on as it is, its nights are kept should it be chosen again,
    /// and if it was on screen Lull asks which BedJet to show rather than moving on to another by itself.
    func forget(_ bedJet: BedJet) {
        bedJet.pause()
        if let unit = bedJet.unit {
            retired[unit] = bedJet.id
            if !bedJet.isDemo { BLELink.forget(unit) }
        }
        bedJets.removeAll { $0 === bedJet }
        if selectedID == bedJet.id {
            selectedID = nil
            needsChoice = true
        }
        save()
    }

    /// What a BedJet is called on screen: the nickname given here, or the name it gives itself, with a short
    /// code from its Bluetooth identity when another known BedJet goes by the same name.
    func title(of bedJet: BedJet) -> String {
        let name = bedJet.displayName
        guard bedJets.contains(where: { $0 !== bedJet && $0.displayName == name }), let unit = bedJet.unit else { return name }
        return "\(name) · \(Self.code(of: unit))"
    }

    /// A nearby unit's name, with its code when another BedJet nearby or known has the same name.
    func title(of unit: NearbyBedJet) -> String {
        let twins = found.contains { $0.id != unit.id && $0.name == unit.name } || bedJets.contains { $0.displayName == unit.name }
        return twins ? "\(unit.name) · \(Self.code(of: unit.id))" : unit.name
    }

    /// Four characters of the identifier this phone knows the unit by. They never change, unlike names.
    static func code(of unit: UUID) -> String { String(unit.uuidString.prefix(4)) }

    func report(_ message: String, from bedJet: BedJet) {
        errorMessage = hasSeveralBedJets ? "\(title(of: bedJet)): \(message)" : message
    }

    func appMovedToBackground() {
        for bedJet in bedJets { bedJet.pause() }
        discovery?.stop()
        // iOS can hold back a redraw asked for moments after another, and the BedJet changes in bursts, so the widgets
        // are redrawn once more on leaving, with what Lull last knew.
        publishWidgets(forced: true)
    }

    func appBecameActive() {
        for bedJet in bedJets { bedJet.resume(asking: bedJet.id == selectedID) }
        if lookers > 0 { discovery?.start() }
    }

    func makeLink(for bedJet: BedJet) -> BedJetLink {
        if let makesLink { return makesLink(bedJet) }
        guard bedJet.isDemo else { return BLELink(unit: bedJet.unit) }
        let demo = DemoLink(name: bedJet.name ?? DemoLink.defaultName,
                            slots: [StoredProgram(name: "Summer nights", steps: Schedule.templates[2].program.steps), nil, nil])
        demo.answers = !(Self.demoFirstUnitAway && bedJet.unit == DemoDiscovery.simulated[0].id)
        return demo
    }

    private func show(_ fleet: Fleet) {
        for bedJet in bedJets { bedJet.pause() }
        retired = fleet.retired ?? [:]
        needsChoice = fleet.needsChoice ?? false
        bedJets = fleet.bedJets.map { BedJet($0, isDemo: demoMode, app: self) }
        // Versions that knew several BedJets showed the first until another was picked.
        selectedID = needsChoice ? nil : fleet.selected.flatMap { id in bedJets.first { $0.id == id }?.id } ?? bedJets.first?.id
        for bedJet in bedJets { bedJet.connect(asking: bedJet.id == selectedID) }
    }

    // MARK: Looking for BedJets

    /// Starts looking for BedJets nearby, while a picker is open. Looking never connects or changes anything.
    func startLooking() {
        lookers += 1
        guard lookers == 1 else { return }
        if discovery == nil {
            let discovery = makesDiscovery(demoMode)
            discovery.onChange = { [weak self, weak discovery] in
                guard let self, let discovery else { return }
                self.found = discovery.nearby
                self.lookingState = discovery.state
            }
            self.discovery = discovery
        }
        discovery?.start()
    }

    func stopLooking() {
        lookers = max(lookers - 1, 0)
        guard lookers == 0 else { return }
        discovery?.stop()
        found = []
    }

    private func restartLooking() {
        discovery?.stop()
        discovery = nil
        found = []
        guard lookers > 0 else { return }
        lookers -= 1
        startLooking()
    }

    // MARK: Schedules

    /// Keeps a copy of a program made elsewhere, so reusing its slot never loses it.
    func keepCopy(of program: StoredProgram) {
        let imported = Schedule(importing: program)
        if !schedules.contains(where: { $0.phases.map(\.step) == imported.phases.map(\.step) }) {
            schedules.append(imported)
        }
    }

    func save(_ schedule: Schedule) {
        if let index = schedules.firstIndex(where: { $0.id == schedule.id }) {
            schedules[index] = schedule
        } else {
            schedules.append(schedule)
        }
    }

    func delete(_ schedule: Schedule) {
        schedules.removeAll { $0.id == schedule.id }
        for bedJet in bedJets where bedJet.tonightID == schedule.id { bedJet.tonightID = schedules.first?.id }
    }

    func degrees(_ halfCelsius: Int) -> String {
        "\(Format.degrees(halfCelsius, fahrenheit: useFahrenheit))°"
    }

    /// A phase in words for VoiceOver, such as "Heat, 75°, fan 40%, 30 minutes".
    func spokenSummary(of phase: Phase) -> String {
        var parts = [phase.mode.title]
        if bedJet?.adjustsTemperature(phase.mode) ?? phase.mode.usesTemperature { parts.append(degrees(phase.temperature)) }
        if phase.mode.usesFan { parts.append("fan \(phase.fanPercent)%") }
        parts.append(Format.spokenDuration(phase.minutes))
        return parts.joined(separator: ", ")
    }

    // MARK: Persistence

    /// The BedJets known in one mode. Demo mode keeps its own, so pretend units never mix with real ones.
    private struct Fleet: Codable {
        var bedJets: [SavedBedJet]
        var selected: BedJet.ID?
        var retired: [UUID: BedJet.ID]?
        var needsChoice: Bool?
    }

    private struct Saved: Codable {
        var schedules: [Schedule]
        var fleet: Fleet?
        var demoFleet: Fleet?
        // Kept by versions that knew a single BedJet.
        var tonightID: Schedule.ID?
        var run: ActiveRun?
        var slot: Int?
    }

    private var fleet: Fleet { Fleet(bedJets: bedJets.map(\.saved), selected: selectedID, retired: retired, needsChoice: needsChoice) }

    /// Launching with `-demoLabelConnected` shows the simulated BedJet as connected, for listing screenshots.
    static let labelsDemoAsConnected = ProcessInfo.processInfo.arguments.contains("-demoLabelConnected")
    /// Launching with `-demoElapsedMinutes 200` makes a night started in demo mode appear that far along.
    static let demoElapsedMinutes = UserDefaults.standard.integer(forKey: "demoElapsedMinutes")
    /// Launching with `-demoHistory` fills an empty History with a few sample nights, for listing screenshots.
    static let seedsDemoHistory = ProcessInfo.processInfo.arguments.contains("-demoHistory")
    /// UI tests launch with this flag so every run starts from the built-in schedules.
    static let isEphemeral = ProcessInfo.processInfo.arguments.contains("-ephemeral")
    /// Launching with `-demoChoose` starts demo mode with no BedJet chosen, as a first launch with real ones does.
    static let demoStartsUnchosen = ProcessInfo.processInfo.arguments.contains("-demoChoose")
    /// Launching with `-demoFirstUnitAway` makes the first simulated BedJet never answer, as if it were unplugged.
    static let demoFirstUnitAway = ProcessInfo.processInfo.arguments.contains("-demoFirstUnitAway")

    private func load() {
        let saved = storage.file("lull.json").flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONDecoder().decode(Saved.self, from: $0) }
        schedules = saved?.schedules ?? Schedule.templates
        otherFleet = (demoMode ? saved?.fleet : saved?.demoFleet).map { checked($0, demo: !demoMode) }
        if let fleet = demoMode ? saved?.demoFleet : saved?.fleet {
            show(checked(fleet, demo: demoMode))
        } else {
            show(startingFleet(demo: demoMode, legacy: saved))
        }
        isLoaded = true
        save()
    }

    /// A real BedJet is known by its unit. Ones saved without one, by a version that connected to whichever BedJet
    /// it found first, can't be told apart from a partner's, so they go and are chosen again; their history stays on disk.
    private func checked(_ fleet: Fleet, demo: Bool) -> Fleet {
        var fleet = fleet
        if demo {
            var free = DemoDiscovery.simulated.map(\.id).filter { id in !fleet.bedJets.contains { $0.unit == id } }
            for index in fleet.bedJets.indices where fleet.bedJets[index].unit == nil {
                fleet.bedJets[index].unit = free.isEmpty ? UUID() : free.removeFirst()
            }
        } else {
            fleet.bedJets.removeAll { $0.unit == nil }
        }
        if let selected = fleet.selected, !fleet.bedJets.contains(where: { $0.id == selected }) {
            fleet.selected = nil
            fleet.needsChoice = true
        }
        return fleet
    }

    /// What a mode starts with. A BedJet an earlier version of Lull remembered carries over with what it kept, since
    /// that was all about that one unit. Without one, nothing is assumed: the person picks.
    private func startingFleet(demo: Bool, legacy: Saved? = nil) -> Fleet {
        var first: SavedBedJet
        if demo {
            guard !Self.demoStartsUnchosen else { return Fleet(bedJets: []) }
            first = SavedBedJet(unit: DemoDiscovery.simulated[0].id, name: DemoLink.defaultName, generation: .v3)
        } else if let unit = BLELink.legacyUnit(in: storage.defaults) {
            first = SavedBedJet(unit: unit.unit, name: unit.name,
                                generation: storage.defaults.string(forKey: "generation").flatMap(Generation.init),
                                run: legacy?.run, slot: legacy?.slot)
        } else {
            return Fleet(bedJets: [])
        }
        first.tonightID = legacy?.tonightID ?? schedules.first?.id
        if let history = storage.file(demo ? "history-demo.json" : "history.json"), let destination = storage.historyFile(for: first.id) {
            try? FileManager.default.moveItem(at: history, to: destination)
        }
        return Fleet(bedJets: [first], selected: first.id)
    }

    func save() {
        guard isLoaded else { return }
        publishWidgets()
        publishLiveActivities()
        guard let file = storage.file("lull.json") else { return }
        let saved = Saved(schedules: schedules, fleet: demoMode ? otherFleet : fleet, demoFleet: demoMode ? fleet : otherFleet)
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(saved).write(to: file, options: .atomic)
    }

    // MARK: Widgets

    /// Hands the widgets what they show, for the BedJet on screen, and redraws them when it changed. Widgets can't
    /// reach the BedJet, so this is all they know until Lull is opened again. `forced` redraws them even if nothing changed.
    func publishWidgets(forced: Bool = false) {
        guard isLoaded, storage.publishesWidgets else { return }
        // Called with every report from a BedJet, so what was last handed over is kept rather than read back.
        let before = publishedWidgets ?? WidgetSnapshot.load()
        let snapshot = WidgetSnapshot(
            tonight: bedJet?.tonight,
            run: bedJet?.run,
            recentHistory: WidgetSnapshot.recent(bedJet?.history ?? []),
            useFahrenheit: useFahrenheit,
            generation: bedJet?.generation ?? .v3,
            bedJets: bedJets.map { bedJet in widgetBedJet(bedJet, before: before?.bedJets.first { $0.id == bedJet.id }) },
            onScreen: selectedID
        )
        let changed = snapshot != before
        publishedWidgets = snapshot
        let saved = changed && snapshot.save()
        guard saved || forced else { return }
        WidgetCenter.shared.reloadAllTimelines()
        ControlCenter.shared.reloadAllControls()
    }

    /// One BedJet as the widgets see it.
    private func widgetBedJet(_ bedJet: BedJet, before: WidgetBedJet?) -> WidgetBedJet {
        let now = Date()
        let current = LiveStatus.of(run: bedJet.run, status: bedJet.status, reportedAt: bedJet.statusReceivedAt,
                                    generation: bedJet.generation, at: now)
        let isWaiting = WidgetSnapshot.isWaiting(for: bedJet.status, isSavingSchedule: bedJet.isSavingSchedule)
        let live = WidgetSnapshot.live(current, after: before?.live, isWaiting: isWaiting, at: now)
        return WidgetBedJet(id: bedJet.id, name: title(of: bedJet), live: live, tonight: bedJet.tonight,
                            generation: bedJet.generation, recentHistory: WidgetSnapshot.recent(bedJet.history))
    }

    // MARK: Live Activities

    /// Shows the running BedJets in the Dynamic Island and on the Lock Screen, and takes out any that stopped, were
    /// forgotten or belong to the other mode.
    func publishLiveActivities() {
        // UI tests (`-ephemeral`) start nights too, and would leave Live Activities behind them.
        guard isLoaded, storage.publishesWidgets, !Self.isEphemeral else { return }
        var wanted: [(id: UUID, wanted: LiveActivities.Wanted)] = []
        for bedJet in bedJets {
            // Starting a night reports the BedJet's program before the night is known here; what's up stays until then
            // and moves straight on to the night, rather than ending and starting over.
            if bedJet.isSavingSchedule {
                wanted.append((bedJet.id, .keep))
            } else if let status = LiveStatus.of(run: bedJet.run, status: bedJet.status, reportedAt: bedJet.statusReceivedAt,
                                                 generation: bedJet.generation) {
                wanted.append((bedJet.id, .show(.init(id: bedJet.id, status: status, name: title(of: bedJet),
                                                       generation: bedJet.generation))))
            } else if bedJet.status == nil {
                wanted.append((bedJet.id, .keep))
            }
        }
        liveActivities.show(wanted, useFahrenheit: useFahrenheit)
    }
}
