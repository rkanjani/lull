import BedJetKit
import Foundation
import Observation
import WatchConnectivity
import WidgetKit

/// What the watch shows and asks for. Everything comes from Lull on the iPhone: what the widgets show, handed over
/// whenever it changes, and the errands it runs. Between handovers the watch plays the night out by the clock, as the
/// widgets do.
@MainActor
@Observable
final class WatchModel {
    /// What Lull on the iPhone last handed over.
    private(set) var snapshot: WidgetSnapshot
    /// The BedJet picked on the watch, by each side of the bed. None picked follows the one on screen on the iPhone.
    var pickedID: UUID? {
        didSet {
            defaults.set(pickedID?.uuidString, forKey: Self.pickedKey)
            save()
        }
    }
    private(set) var isReachable = false
    /// The errand on its way, until the iPhone says how it went.
    private(set) var pending: WatchCommand?
    var errorMessage: String?

    @ObservationIgnored let phone: Phone
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let saves: Bool
    /// When the watch last asked the iPhone what the BedJets are doing.
    @ObservationIgnored private var askedAt: Date?
    /// Counts errands sent, so a wait that runs out only gives up on the errand it was for.
    @ObservationIgnored private var sent = 0
    @ObservationIgnored private var isRefreshing = false

    /// How long to wait for an errand before saying the iPhone didn't answer. Reaching the BedJet can take a while.
    static var patience: TimeInterval = 30
    /// How long Siri or a shortcut that opened the app waits for the iPhone to be found, which takes a moment.
    static var findingPhone: TimeInterval = 5
    private static let pickedKey = "bedJet"
    private static let askedKey = "askedAt"

    /// - Parameter saves: Off for tests and `-ephemeral`, which keep nothing between launches.
    init(phone: Phone, defaults: UserDefaults = .standard, saves: Bool = true) {
        self.phone = phone
        self.defaults = defaults
        self.saves = saves
        snapshot = (saves ? WidgetSnapshot.load() : nil) ?? WidgetSnapshot()
        pickedID = defaults.string(forKey: Self.pickedKey).flatMap(UUID.init)
        askedAt = defaults.object(forKey: Self.askedKey) as? Date
        phone.onSnapshot = { [weak self] in self?.receive($0) }
        phone.onReply = { [weak self] in self?.receive($0) }
        phone.onReachabilityChange = { [weak self] in self?.reachabilityChanged() }
        phone.activate()
        isReachable = phone.isReachable
    }

    /// Launch flags match the iPhone app's: `-demo` uses a pretend iPhone with the sample BedJets, `-ephemeral` keeps
    /// nothing, and `-demoElapsedMinutes 200` makes a night started in demo appear that far along.
    static func launch(arguments: [String] = ProcessInfo.processInfo.arguments, defaults: UserDefaults = .standard) -> WatchModel {
        let ephemeral = arguments.contains("-ephemeral")
        guard arguments.contains("-demo") else { return WatchModel(phone: PhoneLink(), defaults: defaults, saves: !ephemeral) }
        var sample = WidgetSnapshot.preview()
        if !arguments.contains("-demoHistory") {
            sample.bedJets = sample.bedJets.map { var bedJet = $0; bedJet.recentHistory = []; return bedJet }
            sample.recentHistory = []
        }
        let phone = DemoPhone(snapshot: sample)
        phone.elapsedMinutes = defaults.integer(forKey: "demoElapsedMinutes")
        return WatchModel(phone: phone, defaults: defaults, saves: !ephemeral)
    }

    // MARK: What's shown

    /// The BedJet the watch shows: the one picked here, or else the one on screen on the iPhone, or the first Lull
    /// knows when none is. One picked and forgotten in Lull since goes back to the one on screen, as the complications
    /// do, rather than leaving the watch blank with nothing to pick.
    var bedJet: WidgetBedJet? { Self.bedJet(in: snapshot, picked: pickedID) }

    /// Shared with the complications, so they show the BedJet the watch does.
    static func bedJet(in snapshot: WidgetSnapshot, picked: UUID?) -> WidgetBedJet? {
        picked.flatMap { id in snapshot.bedJets.first { $0.id == id } } ?? snapshot.usualBedJet
    }

    /// What the pages show: the BedJet's tonight, night and history.
    var shown: WidgetSnapshot {
        guard let bedJet else { return snapshot }
        return snapshot.showing(bedJet.id)
    }

    var hasSeveralBedJets: Bool { snapshot.hasSeveralBedJets }

    /// Why the controls can't be used just now, if they can't.
    var unavailable: String? {
        isReachable ? nil : "Your iPhone isn't nearby. Lull reaches your BedJet through it."
    }

    // MARK: Errands

    /// Hands the iPhone an errand, and waits to hear how it went.
    func send(_ command: WatchCommand) async {
        guard pending == nil else { return }
        if let unavailable {
            errorMessage = unavailable
            return
        }
        pending = command
        errorMessage = nil
        sent += 1
        let errand = sent
        do {
            if let refused = try await phone.send(command) {
                receive(refused)
                return
            }
        } catch {
            pending = nil
            errorMessage = "Couldn't reach Lull on your iPhone. Try again."
            return
        }
        Task {
            try? await Task.sleep(nanoseconds: UInt64(Self.patience * 1_000_000_000))
            guard self.sent == errand, self.pending != nil else { return }
            self.pending = nil
            self.errorMessage = WatchMessages.tooLate
        }
    }

    /// Whether an errand like this one, for the same BedJet, is on its way, whatever it's set to: the Remote's Apply
    /// stays busy while its settings are turned.
    func isSending(_ command: WatchCommand) -> Bool {
        guard let pending, pending.bedJet == command.bedJet else { return false }
        switch (pending, command) {
        case (.startNight, .startNight), (.endNight, .endNight), (.remote, .remote), (.refresh, .refresh): return true
        default: return false
        }
    }

    /// Asks the iPhone what the BedJets are doing, at most every quarter of an hour. Nothing is shown for it. What the
    /// iPhone hands over on its own can't stand in for this: Watch Connectivity hands back the last of it on every
    /// launch, however old.
    func refreshIfStale(at now: Date = Date()) {
        guard isReachable, pending == nil, !isRefreshing else { return }
        if let askedAt, now.timeIntervalSince(askedAt) < 15 * 60 { return }
        isRefreshing = true
        Task {
            defer { isRefreshing = false }
            // Only a refresh that went counts: one that couldn't is tried again the next time the iPhone is reachable.
            do { _ = try await phone.send(.refresh) } catch { return }
            askedAt = now
            defaults.set(now, forKey: Self.askedKey)
        }
    }

    /// The iPhone usually becomes reachable a moment after the watch app opens, which is when a refresh can go.
    private func reachabilityChanged() {
        isReachable = phone.isReachable
        refreshIfStale()
    }

    func receive(_ snapshot: WidgetSnapshot) {
        let reoffers = Self.offersChanged(from: self.snapshot, to: snapshot)
        self.snapshot = snapshot
        save()
        // The watch face keeps the complications it was offered, one for each BedJet by name, until told they changed.
        if saves, reoffers { WidgetCenter.shared.invalidateConfigurationRecommendations() }
    }

    /// Whether the complications offered for the watch face change: once a BedJet is added, renamed or forgotten.
    static func offersChanged(from old: WidgetSnapshot, to new: WidgetSnapshot) -> Bool {
        old.bedJets.map(\.id) != new.bedJets.map(\.id) || old.bedJets.map(\.name) != new.bedJets.map(\.name)
    }

    func receive(_ reply: WatchReply) {
        // A late answer to an errand already given up on: what changed shows in what the iPhone hands over.
        guard pending != nil else { return }
        pending = nil
        if !reply.ok { errorMessage = reply.message ?? "Something went wrong." }
    }

    /// What Siri, a complication or a shortcut asked for. Nothing named means the BedJet the watch shows. They usually
    /// open the app, so the iPhone may not be found yet: they wait a moment for it rather than saying it isn't nearby.
    func handle(_ command: AppCommands.Command) {
        switch command {
        case .show:
            break
        case let .startNight(id):
            Task {
                await waitForPhone()
                await send(.startNight(id ?? bedJet?.id))
            }
        case let .remote(preset, id):
            Task {
                await waitForPhone()
                await send(.remote(preset, id ?? bedJet?.id))
            }
        }
    }

    private func waitForPhone() async {
        let deadline = Date().addingTimeInterval(Self.findingPhone)
        while !isReachable, Date() < deadline {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    /// Kept for the complications, which show the BedJet the watch shows unless set to another.
    private func save() {
        guard saves else { return }
        if Self.forComplications(snapshot, picked: pickedID).save() { WidgetCenter.shared.reloadAllTimelines() }
    }

    /// What the complications are handed: the BedJet the watch shows as the one on screen, with its own night,
    /// schedule and history, as a widget set to it would draw.
    static func forComplications(_ snapshot: WidgetSnapshot, picked: UUID?) -> WidgetSnapshot {
        snapshot.showing(bedJet(in: snapshot, picked: picked)?.id)
    }
}
