import BedJetKit
import Foundation
import Observation
import UIKit
import WatchConnectivity
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
    /// Whether the Remote shows when air turns off as a time of day rather than a duration. It only changes what's shown:
    /// the BedJet counts down either way.
    var remoteTimerShowsTime: Bool {
        didSet { storage.defaults.set(remoteTimerShowsTime, forKey: "remoteTimerShowsTime") }
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

    /// Whether Lull is on screen. Off screen it holds no BedJet, so an errand from the watch connects, does what was
    /// asked and lets go again.
    private(set) var isOnScreen: Bool

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
    /// Hands the watch what the widgets show, and takes its errands.
    @ObservationIgnored private var watch: PhoneSession?
    /// How many errands from the watch are using each BedJet. Leaving the screen doesn't interrupt them, and the last
    /// to finish lets go of the BedJet.
    @ObservationIgnored private var errands: [BedJet.ID: Int] = [:]
    /// What went wrong during the errands on each BedJet, for the watch to say.
    @ObservationIgnored private var errandReports: [BedJet.ID: String] = [:]

    /// - Parameters:
    ///   - makeLink: Connects to a BedJet. By default a simulated one in demo mode and Bluetooth otherwise.
    ///   - discovery: Finds BedJets nearby, given whether demo mode is on.
    ///   - onScreen: Whether Lull is on screen as it starts. By default, whether iOS launched it in the foreground: the
    ///     watch can wake it in the background, where it has no business holding a BedJet.
    init(storage: Storage = .app, demoMode: Bool? = nil, makeLink: ((BedJet) -> BedJetLink)? = nil,
         discovery: ((Bool) -> BedJetDiscovery)? = nil, onScreen: Bool? = nil) {
        self.storage = storage
        makesLink = makeLink
        makesDiscovery = discovery ?? { demo in demo ? DemoDiscovery() as BedJetDiscovery : BLEDiscovery() }
        let defaults = storage.defaults
        useFahrenheit = defaults.object(forKey: "useFahrenheit") as? Bool ?? (Locale.current.measurementSystem == .us)
        remoteTimerShowsTime = defaults.bool(forKey: "remoteTimerShowsTime")
        #if targetEnvironment(simulator)
        self.demoMode = demoMode ?? true
        #else
        self.demoMode = demoMode ?? defaults.bool(forKey: "demoMode")
        #endif
        isOnScreen = onScreen ?? (UIApplication.shared.applicationState != .background)
        load()
        if storage.publishesWidgets, WCSession.isSupported() {
            let watch = PhoneSession(model: self)
            if let publishedWidgets { watch.send(publishedWidgets) }
            self.watch = watch
        }
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
        let message = hasSeveralBedJets ? "\(title(of: bedJet)): \(message)" : message
        // Off screen, an alert would wait for the next time Lull is opened; the watch says it now instead. A refresh,
        // which nothing answers, leaves it for then.
        if errands[bedJet.id] != nil {
            errandReports[bedJet.id] = message
            guard isOnScreen else { return }
        }
        errorMessage = message
    }

    func appMovedToBackground() {
        isOnScreen = false
        // An errand from the watch lets go of its BedJet itself, once it's done.
        for bedJet in bedJets where errands[bedJet.id] == nil { bedJet.pause() }
        discovery?.stop()
        // iOS can hold back a redraw asked for moments after another, and the BedJet changes in bursts, so the widgets
        // are redrawn once more on leaving, with what Lull last knew.
        publishWidgets(forced: true)
    }

    /// Lull came on screen. A model made on screen is already talking to its BedJets, and coming back from Notification
    /// Center never left it, so nothing is asked again.
    func appBecameActive() {
        guard !isOnScreen else { return }
        isOnScreen = true
        for bedJet in bedJets { bedJet.resume(asking: bedJet.id == selectedID) }
        if lookers > 0 { discovery?.start() }
    }

    // MARK: Tonight and the Remote, for the watch

    /// Starts tonight on a BedJet, as Start Night on the watch does, once its errand has reached it. A night already
    /// under way, from this phone or another, is left running. Widgets, controls and Siri use `startNight(on:)` with
    /// an ID instead, which only sets a BedJet that's free. Nothing is sent while another command of Lull's is under
    /// way on the BedJet, which this returns false for, so the watch can say so.
    @discardableResult
    func startNight(on bedJet: BedJet) async -> Bool {
        guard !bedJet.isBusy else { return false }
        guard bedJet.run == nil, bedJet.runningElsewhereStep == nil else { return true }
        await bedJet.startTonight()
        return true
    }

    /// Sets a BedJet as the watch's Remote asks. Like choosing the mode on the Remote, a schedule running on it ends.
    /// The watch's Remote names what was changed on it, and only that is set: the rest stays as the BedJet has it,
    /// which may have moved on since the watch last heard. Quick Remote buttons use `applyQuickRemote` instead.
    /// Nothing is sent while another command of Lull's is under way on the BedJet, which this returns false for.
    @discardableResult
    func applyRemote(_ preset: RemotePreset, to bedJet: BedJet, changed: Set<RemoteSetting>? = nil) async -> Bool {
        guard !bedJet.isBusy else { return false }
        // A watch can ask for anything, and an older or newer one may not know this BedJet's limits.
        let preset = preset.clamped
        // The Remote never offers a mode this kind of BedJet lacks, so a button set up for another kind does nothing.
        guard preset.mode == .standby || bedJet.generation.supports(preset.mode) else { return true }
        let changes = changed ?? Set(RemoteSetting.allCases)
        // After the first step, each only while the BedJet is still this phone's, as for a Quick Remote button: one
        // handed to another phone meanwhile isn't asked back, and one out of reach says so after one wait rather than
        // one for each setting, which together would outlast the watch's.
        var stepped = false
        func carriesOn() -> Bool {
            let carries = !stepped || bedJet.isConnected
            stepped = true
            return carries
        }
        if changes.contains(.mode), carriesOn() { await bedJet.setMode(preset.mode) }
        guard preset.mode != .standby else { return true }
        if changes.contains(.temperature), bedJet.adjustsTemperature(preset.mode), carriesOn() {
            await bedJet.setTemperature(preset.temperature)
        }
        if changes.contains(.fan), preset.mode.usesFan, carriesOn() { await bedJet.setFan(percent: preset.fanPercent) }
        // Last: choosing the mode starts it at the longest run the BedJet allows.
        if changes.contains(.time), carriesOn() { await bedJet.setRuntime(minutes: preset.minutes) }
        return true
    }

    // MARK: Errands from the watch

    /// Does what the watch asked, on the BedJet it names or the one on screen, and says how it went. The watch shows
    /// what Lull hands the widgets, so this hands them what changed. Off screen Lull holds no BedJet, so it connects,
    /// waits to hear what the BedJet is doing, and lets go again once it's done.
    func runErrand(_ command: WatchCommand) async -> WatchReply {
        if case .refresh = command {
            await refresh()
            return .done
        }
        let noBedJet = WatchReply.failed("Choose your BedJet in Lull on your iPhone first.")
        guard let bedJet = command.bedJet.map({ id in bedJets.first { $0.id == id } }) ?? self.bedJet else {
            return noBedJet
        }
        beginErrand(on: bedJet)
        // Done with the BedJet however the errand ends, once the reply below is read: being done clears what went wrong.
        defer {
            endErrand(on: bedJet)
            publishWidgets()
        }
        if !isOnScreen || !bedJet.isConnected {
            let asked = Date()
            bedJet.resume(asking: true)
            await bedJet.awaitReport(since: asked, timeout: Self.errandPatience)
        }
        // Forgotten while the errand waited for it, the BedJet is no longer this phone's to set.
        guard uses(bedJet) else { return noBedJet }
        // Out of background time while it waited, the errand was cancelled and Lull let go of the BedJet, which it doesn't
        // take back for an errand the watch has given up on, as on Android, where the errand's work is stopped.
        guard !Task.isCancelled else { return .failed(WatchMessages.tooLate) }
        // What the connection turned up before the errand's own command, such as a night that ended early, isn't how the
        // errand went: Lull says it once it's opened, as without the watch.
        if errands[bedJet.id] == 1, let found = errandReports.removeValue(forKey: bedJet.id) { errorMessage = found }
        // Another command of Lull's under way on the BedJet, such as a schedule being saved, leaves it alone: the watch says
        // so, rather than that it went, so the person can try again.
        var wasFree = true
        switch command {
        case .startNight: wasFree = await startNight(on: bedJet)
        case .endNight: await bedJet.endNight()
        case let .remote(preset, _, changed): wasFree = await applyRemote(preset, to: bedJet, changed: changed)
        case .refresh: break
        }
        // Cancelled partway, the errand sent nothing more, so the watch hears it ran out of time rather than that it went.
        guard !Task.isCancelled else { return .failed(WatchMessages.tooLate) }
        guard wasFree else { return .failed(WatchMessages.busy) }
        if bedJet.needsSlotChoice {
            errandReports[bedJet.id] = "Your BedJet's three slots are full. Open Lull on your iPhone to pick one to reuse."
        }
        return errandReports[bedJet.id].map(WatchReply.failed) ?? .done
    }

    /// Whether a BedJet is still one of this phone's, not forgotten or left behind by a switch to or from demo mode.
    private func uses(_ bedJet: BedJet) -> Bool { bedJets.contains { $0 === bedJet } }

    /// Holds on to a BedJet for an errand. The first errand on it starts with nothing gone wrong.
    private func beginErrand(on bedJet: BedJet) {
        if errands[bedJet.id] == nil { errandReports[bedJet.id] = nil }
        errands[bedJet.id, default: 0] += 1
    }

    /// Done with a BedJet. Off screen, the last errand on it lets it go, so an errand that started later isn't cut off.
    /// One forgotten meanwhile is let go on screen too, since nothing else would.
    private func endErrand(on bedJet: BedJet) {
        let left = (errands[bedJet.id] ?? 1) - 1
        errands[bedJet.id] = left > 0 ? left : nil
        guard left <= 0 else { return }
        errandReports[bedJet.id] = nil
        if !isOnScreen || !uses(bedJet) { bedJet.pause() }
        // One chosen again meanwhile is a new BedJet with the same ID, which leaving the screen skipped for this errand.
        if !isOnScreen, let current = bedJets.first(where: { $0.id == bedJet.id }), current !== bedJet { current.pause() }
    }

    /// Lets go of every BedJet an errand from the watch holds, when iOS is about to suspend Lull before the errands are
    /// done and they're cancelled, rather than the BedJet staying held by a phone that can't answer for it until a
    /// command in flight gives up.
    func letGoOfErrands() {
        for bedJet in bedJets where errands[bedJet.id] != nil { bedJet.pause() }
    }

    /// How long an errand waits to hear from a BedJet before getting on with it. Tests wait less.
    static var errandPatience: TimeInterval = 8

    /// Hears what every BedJet is doing now, for a watch that hasn't heard in a while. Off screen, only BedJets no
    /// other phone is using are asked, so a partner's night is never interrupted for a look.
    private func refresh() async {
        let asked = Date()
        let listening = isOnScreen ? [] : bedJets
        for bedJet in listening {
            beginErrand(on: bedJet)
            bedJet.resume(asking: false)
        }
        defer {
            for bedJet in listening { endRefresh(on: bedJet) }
            publishWidgets()
        }
        // One wait for them all: each gets what's left of it.
        let deadline = Date().addingTimeInterval(Self.errandPatience)
        for bedJet in listening { await bedJet.awaitReport(since: asked, timeout: deadline.timeIntervalSinceNow) }
    }

    /// Done with a BedJet a refresh held. Nothing answers a refresh, so once it's the last errand on the BedJet, what
    /// went wrong, such as a night that ended early, waits in Lull for the next time it's opened, as without the watch.
    private func endRefresh(on bedJet: BedJet) {
        if errands[bedJet.id] == 1, let report = errandReports[bedJet.id] { errorMessage = report }
        endErrand(on: bedJet)
    }

    func makeLink(for bedJet: BedJet) -> BedJetLink {
        if let makesLink { return makesLink(bedJet) }
        guard bedJet.isDemo else { return BLELink(unit: bedJet.unit) }
        let demo = DemoLink(name: bedJet.name ?? DemoLink.defaultName,
                            slots: [StoredProgram(name: "Summer nights", steps: Self.startingSchedules[2].program.steps), nil, nil])
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
        // Off screen, as when the watch wakes Lull, nothing connects, and above all no other phone is asked for its BedJet.
        for bedJet in bedJets { bedJet.connect(asking: bedJet.id == selectedID, resuming: isOnScreen) }
    }

    // MARK: From outside the app

    /// A Quick Remote button: sets the BedJet it was set to, or the one on screen, to its preset, if that BedJet is free.
    func applyQuickRemote(_ preset: RemotePreset, to id: BedJet.ID?) async {
        // From a widget set to one BedJet: that one, and none rather than another if Lull can't switch now.
        if let id, !select(id) { return }
        await bedJet?.applyPreset(preset)
    }

    /// Starts tonight from a widget, a control, Siri or Shortcuts: on the BedJet named, or the one on screen, if it's free.
    func startNight(on id: BedJet.ID?) async {
        // Started from a widget showing one BedJet: that one, and none rather than another if Lull can't switch now.
        if let id, !select(id) { return }
        await bedJet?.startTonightIfFree()
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
        if !schedules.contains(where: { $0.matches(imported: imported) }) {
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
    /// Launching with `-noWakeTimes` starts from the built-in schedules without their wake times. A wake time more than
    /// 16 hours away can't be reached, so a test that starts a night would otherwise pass or fail by the time of day.
    /// Model tests set it themselves.
    static var startsWithoutWakeTimes = ProcessInfo.processInfo.arguments.contains("-noWakeTimes")

    /// The schedules Lull starts with when it has none saved.
    static var startingSchedules: [Schedule] {
        guard startsWithoutWakeTimes else { return Schedule.templates }
        return Schedule.templates.map { var schedule = $0; schedule.wakeTime = nil; return schedule }
    }

    private func load() {
        let saved = storage.file("lull.json").flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONDecoder().decode(Saved.self, from: $0) }
        schedules = saved?.schedules ?? Self.startingSchedules
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
        if changed { watch?.send(snapshot) }
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
