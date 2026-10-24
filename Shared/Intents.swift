import AppIntents
import Foundation

/// The app's tabs, which widgets, controls and Shortcuts open to.
enum LullTab: String, AppEnum {
    case remote, schedule, history

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Lull Tab"
    static let caseDisplayRepresentations: [LullTab: DisplayRepresentation] = [
        .remote: "Remote",
        .schedule: "Schedule",
        .history: "History",
    ]

    /// Opens Lull on this tab when a widget is tapped.
    var url: URL { URL(string: "lull://\(rawValue)")! }

    /// Opens Lull on this tab showing one BedJet, from something outside Lull that shows it.
    func url(showing bedJet: UUID) -> URL { URL(string: "lull://\(rawValue)?bedjet=\(bedJet.uuidString)")! }

    init?(url: URL) {
        guard url.scheme == "lull", let tab = url.host().flatMap(LullTab.init(rawValue:)) else { return nil }
        self = tab
    }

    /// The BedJet a link asks to show, if it names one.
    static func bedJet(in url: URL) -> UUID? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "bedjet" }?.value.flatMap(UUID.init)
    }
}

/// Opens Lull on one of its tabs.
struct OpenLullIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Lull"
    static let description = IntentDescription("Opens Lull on the Remote, Schedule or History.")
    static let openAppWhenRun = true

    @Parameter(title: "Tab", default: .remote)
    var tab: LullTab

    init() {}

    init(tab: LullTab) {
        self.tab = tab
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommands.send(.show(tab))
        return .result()
    }
}

/// Starts tonight's schedule. Lull needs to be open to reach the BedJet over Bluetooth, so this opens it.
struct StartNightIntent: AppIntent {
    static let title: LocalizedStringResource = "Start Night"
    static let description = IntentDescription("Opens Lull and starts tonight's schedule on your BedJet.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommands.send(.startNight)
        return .result()
    }
}

/// Passes what an intent asked for to the app, which picks it up once it has launched. Intents that open the app
/// run in the app's process, but may run before its views are ready, so commands wait here until then.
@MainActor
enum AppCommands {
    enum Command: Equatable {
        case show(LullTab)
        case startNight
    }

    static var handler: ((Command) -> Void)? {
        didSet { deliver() }
    }

    private static var pending: [Command] = []

    static func send(_ command: Command) {
        pending.append(command)
        deliver()
    }

    private static func deliver() {
        guard let handler else { return }
        let commands = pending
        pending = []
        commands.forEach(handler)
    }
}
