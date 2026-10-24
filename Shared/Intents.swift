import AppIntents
import BedJetKit
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

    /// The BedJet to start it on, such as the one a Tonight widget shows; the one on screen in Lull when left out.
    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    init() {}

    init(bedJet: BedJetEntity?) {
        self.bedJet = bedJet
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommands.send(.startNight(bedJet?.id))
        return .result()
    }
}

/// A BedJet Lull knows, picked by name for a widget to show or set. Listed from what the app last handed the widgets.
/// Once picked, a widget by each side of the bed always shows and sets its own side.
struct BedJetEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "BedJet"
    static let defaultQuery = BedJetQuery()

    var id: UUID
    var name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }

    init(id: UUID, name: String) {
        self.id = id
        self.name = name
    }

    init(_ bedJet: WidgetBedJet) {
        self.init(id: bedJet.id, name: bedJet.name)
    }
}

struct BedJetQuery: EntityQuery {
    func entities(for identifiers: [UUID]) async throws -> [BedJetEntity] {
        let known = (WidgetSnapshot.load()?.bedJets ?? []).map(BedJetEntity.init)
        // One forgotten since is still returned: left out, a widget set to it would quietly show another instead of
        // saying it has no BedJet, as it does on Android.
        return identifiers.map { id in known.first { $0.id == id } ?? BedJetEntity(id: id, name: "Forgotten BedJet") }
    }

    func suggestedEntities() async throws -> [BedJetEntity] {
        (WidgetSnapshot.load()?.bedJets ?? []).map(BedJetEntity.init)
    }

    // No default: a new widget is left unset, and shows the BedJet on screen in Lull until it's edited. iOS works a
    // default out once and hands it to widgets added long after, by when that BedJet may have been forgotten, leaving
    // a new widget with nothing to show.
}

/// Which BedJet a Tonight or Last Night widget shows, by name. With one on each side of the bed, StandBy's two
/// stacks can show one each.
struct BedJetConfiguration: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "BedJet"
    static let description = IntentDescription("Which BedJet this widget shows.")

    /// Empty until the widget is edited, when it shows the BedJet on screen in Lull.
    @Parameter(title: "BedJet", description: "Which BedJet this widget shows.")
    var bedJet: BedJetEntity?

    init() {}

    init(bedJet: BedJetEntity?) {
        self.bedJet = bedJet
    }
}

/// Which BedJet each half of a Both Sides widget shows, by name. Until it's edited, the left shows the BedJet on screen
/// in Lull and the right another.
struct BothSidesConfiguration: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "BedJets"
    static let description = IntentDescription("Which BedJet each side of this widget shows.")

    @Parameter(title: "Left side", description: "The BedJet on the left of this widget.")
    var left: BedJetEntity?

    @Parameter(title: "Right side", description: "The BedJet on the right of this widget.")
    var right: BedJetEntity?

    init() {}
}

/// Air from one Quick Remote button: a mode, how long, and the temperature and fan to run it at.
struct RemotePreset: Hashable {
    var mode: Mode
    var minutes: Int
    /// Half-degrees Celsius. Ignored for Off, and for modes the BedJet doesn't take a temperature for.
    var temperature: Int
    var fanPercent: Int

    /// Cool for 30 minutes, Heat for 15, and Off: the buttons a Quick Remote widget starts with.
    static let defaults = PresetChoice.defaults.map { $0.preset(fahrenheit: false) }

    /// "Cool · 30 min", as on the widget's button.
    var title: String {
        mode == .standby ? "Off" : "\(mode.title) · \(Format.duration(minutes))"
    }

    /// What else it sets, such as "72° · Fan 40%", read out after the button's title. Nil for Off.
    func setting(fahrenheit: Bool) -> String? {
        guard mode != .standby else { return nil }
        return Format.setting(mode: mode, temperature: mode.usesTemperature ? temperature : nil, fanPercent: fanPercent,
                              fahrenheit: fahrenheit)
    }

    /// Kept to what the BedJet takes: the mode's temperatures, the fan in steps of 5, and no longer than a phase in that
    /// mode may run, so Turbo stops at 10 minutes and hot, fast Heat at the BedJet's safety cut-off.
    var clamped: RemotePreset {
        var preset = self
        preset.temperature = min(max(temperature, mode.temperatureRange.lowerBound), mode.temperatureRange.upperBound)
        preset.fanPercent = min(max((fanPercent + 2) / 5 * 5, 5), 100)
        let longest = Phase(mode, temperature: preset.temperature, fan: preset.fanPercent, minutes: minutes).maxMinutes
        preset.minutes = min(max(minutes, 1), longest)
        return preset
    }
}

/// One Quick Remote button as set up when editing the widget. Its temperature is in the degrees Lull shows, as the
/// person typed it, or nil for the mode's usual temperature.
struct PresetChoice: Hashable {
    var mode: Mode
    var minutes: Int
    var degrees: Int?
    var fanPercent: Int

    static let defaults = [
        PresetChoice(mode: .cool, minutes: 30, degrees: nil, fanPercent: 40),
        PresetChoice(mode: .heat, minutes: 15, degrees: nil, fanPercent: 40),
        PresetChoice(mode: .standby, minutes: 30, degrees: nil, fanPercent: 40),
    ]

    /// What the button sets, in the BedJet's units and within what it takes.
    func preset(fahrenheit: Bool) -> RemotePreset {
        let temperature = degrees.map { Format.halfCelsius(fromDegrees: $0, fahrenheit: fahrenheit) } ?? mode.defaultTemperature
        return RemotePreset(mode: mode, minutes: minutes, temperature: temperature, fanPercent: fanPercent).clamped
    }
}

/// The modes a Quick Remote button can be set to, in the Remote's order, with Off.
enum RemoteMode: String, AppEnum {
    case cool, heat, gentleHeat, turbo, dry, off

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Mode"
    static let caseDisplayRepresentations: [RemoteMode: DisplayRepresentation] = [
        .cool: "Cool",
        .heat: "Heat",
        .gentleHeat: "Gentle heat",
        .turbo: "Turbo",
        .dry: "Dry",
        .off: "Off",
    ]

    var mode: Mode {
        switch self {
        case .cool: .cool
        case .heat: .heat
        case .gentleHeat: .extendedHeat
        case .turbo: .turbo
        case .dry: .dry
        case .off: .standby
        }
    }

    init(_ mode: Mode) {
        switch mode {
        case .cool: self = .cool
        case .heat: self = .heat
        case .extendedHeat: self = .gentleHeat
        case .turbo: self = .turbo
        case .dry: self = .dry
        case .standby, .wait: self = .off
        }
    }
}

/// What a Quick Remote widget's three buttons set the BedJet to, chosen by editing the widget.
struct QuickRemoteConfiguration: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Buttons"
    static let description = IntentDescription("What each button sets your BedJet to.")

    /// The BedJet the buttons set, picked by name, so with one on each side of the bed two widgets can each set one
    /// side. Empty until the widget is edited, when the buttons set the BedJet on screen in Lull.
    @Parameter(title: "BedJet", description: "Which BedJet this widget's buttons set.")
    var bedJet: BedJetEntity?

    @Parameter(title: "Button 1 mode", default: .cool)
    var mode1: RemoteMode
    @Parameter(title: "Button 1 duration", description: "In minutes.", default: 30, inclusiveRange: (1, 719))
    var minutes1: Int
    @Parameter(title: "Button 1 temperature", description: "In the degrees Lull shows. Leave empty for the mode's usual temperature.")
    var degrees1: Int?
    @Parameter(title: "Button 1 fan", description: "In percent.", default: 40, inclusiveRange: (5, 100))
    var fan1: Int

    @Parameter(title: "Button 2 mode", default: .heat)
    var mode2: RemoteMode
    @Parameter(title: "Button 2 duration", description: "In minutes.", default: 15, inclusiveRange: (1, 719))
    var minutes2: Int
    @Parameter(title: "Button 2 temperature", description: "In the degrees Lull shows. Leave empty for the mode's usual temperature.")
    var degrees2: Int?
    @Parameter(title: "Button 2 fan", description: "In percent.", default: 40, inclusiveRange: (5, 100))
    var fan2: Int

    @Parameter(title: "Button 3 mode", default: .off)
    var mode3: RemoteMode
    @Parameter(title: "Button 3 duration", description: "In minutes.", default: 30, inclusiveRange: (1, 719))
    var minutes3: Int
    @Parameter(title: "Button 3 temperature", description: "In the degrees Lull shows. Leave empty for the mode's usual temperature.")
    var degrees3: Int?
    @Parameter(title: "Button 3 fan", description: "In percent.", default: 40, inclusiveRange: (5, 100))
    var fan3: Int

    init() {}

    /// The three buttons as set up.
    var choices: [PresetChoice] {
        [
            PresetChoice(mode: mode1.mode, minutes: minutes1, degrees: degrees1, fanPercent: fan1),
            PresetChoice(mode: mode2.mode, minutes: minutes2, degrees: degrees2, fanPercent: fan2),
            PresetChoice(mode: mode3.mode, minutes: minutes3, degrees: degrees3, fanPercent: fan3),
        ]
    }

    func presets(fahrenheit: Bool) -> [RemotePreset] {
        choices.map { $0.preset(fahrenheit: fahrenheit) }
    }
}

/// Sets the BedJet to what a Quick Remote button holds. Lull needs to be open to reach the BedJet over Bluetooth,
/// so this opens it.
struct QuickRemoteIntent: AppIntent {
    static let title: LocalizedStringResource = "Quick Remote"
    static let description = IntentDescription("Opens Lull and sets your BedJet to one of a Quick Remote widget's buttons.")
    static let openAppWhenRun = true
    /// The widget works out its values, in the BedJet's own units, so it isn't offered in Shortcuts.
    static let isDiscoverable = false

    @Parameter(title: "Mode", default: .cool)
    var mode: RemoteMode
    @Parameter(title: "Duration", default: 30)
    var minutes: Int
    /// Half-degrees Celsius.
    @Parameter(title: "Temperature", default: 44)
    var temperature: Int
    @Parameter(title: "Fan", default: 40)
    var fanPercent: Int
    /// The BedJet the widget was set to; the one on screen in Lull when left out.
    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    init() {}

    init(preset: RemotePreset, bedJet: BedJetEntity? = nil) {
        mode = RemoteMode(preset.mode)
        minutes = preset.minutes
        temperature = preset.temperature
        fanPercent = preset.fanPercent
        self.bedJet = bedJet
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let preset = RemotePreset(mode: mode.mode, minutes: minutes, temperature: temperature, fanPercent: fanPercent)
        AppCommands.send(.remote(preset, bedJet?.id))
        return .result()
    }
}

/// Passes what an intent asked for to the app, which picks it up once it has launched. Intents that open the app
/// run in the app's process, but may run before its views are ready, so commands wait here until then.
@MainActor
enum AppCommands {
    enum Command: Equatable {
        case show(LullTab)
        /// Starts tonight on a BedJet, or on the one on screen when none is named.
        case startNight(UUID?)
        /// A Quick Remote button: sets a BedJet to a preset, or the one on screen when none is named.
        case remote(RemotePreset, UUID?)
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
