import AppIntents
import BedJetKit
import Foundation

// Lull's actions in Shortcuts. They live in the app alone, since the widget extension can't reach the BedJet, and
// Shortcuts runs them in Lull's process, often with Lull off screen.

// MARK: Entities

// BedJets are picked with `BedJetEntity` (`Shared/Intents.swift`), which the widgets use too.

struct ScheduleEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Schedule"
    static let defaultQuery = ScheduleQuery()

    let id: Schedule.ID
    let name: String
    /// "7 hr 45 min", or "Until 6:30 AM" for a schedule with a wake time, as the schedule list says.
    let length: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(length)")
    }
}

struct ScheduleQuery: EnumerableEntityQuery {
    @Dependency private var model: AppModel

    @MainActor
    func allEntities() async throws -> [ScheduleEntity] {
        model.schedules.map { ScheduleEntity(id: $0.id, name: $0.name, length: $0.length) }
    }

    @MainActor
    func entities(for identifiers: [ScheduleEntity.ID]) async throws -> [ScheduleEntity] {
        try await allEntities().filter { identifiers.contains($0.id) }
    }
}

/// The modes the Remote turns on.
enum AirMode: String, AppEnum {
    case cool, heat, gentleHeat = "gentle_heat", turbo, dry

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Mode"
    // Literals, since Shortcuts reads them when Lull is built; tests keep the titles and icons the same as `Mode.title`
    // and `Mode.symbol`, which the Remote shows.
    static let caseDisplayRepresentations: [AirMode: DisplayRepresentation] = [
        .cool: DisplayRepresentation(title: "Cool", image: .init(systemName: "snowflake")),
        .heat: DisplayRepresentation(title: "Heat", image: .init(systemName: "flame")),
        .gentleHeat: DisplayRepresentation(title: "Gentle heat", image: .init(systemName: "sun.min")),
        .turbo: DisplayRepresentation(title: "Turbo", image: .init(systemName: "bolt.fill")),
        .dry: DisplayRepresentation(title: "Dry", image: .init(systemName: "wind")),
    ]

    var mode: Mode {
        switch self {
        case .cool: return .cool
        case .heat: return .heat
        case .gentleHeat: return .extendedHeat
        case .turbo: return .turbo
        case .dry: return .dry
        }
    }
}

/// Every mode a BedJet reports, for Get BedJet Status.
enum BedJetMode: String, AppEnum {
    case cool, heat, gentleHeat = "gentle_heat", turbo, dry, pause, off

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Mode"
    // Kept to `Mode.title` and `Mode.symbol` by the same tests as `AirMode`.
    static let caseDisplayRepresentations: [BedJetMode: DisplayRepresentation] = [
        .cool: DisplayRepresentation(title: "Cool", image: .init(systemName: "snowflake")),
        .heat: DisplayRepresentation(title: "Heat", image: .init(systemName: "flame")),
        .gentleHeat: DisplayRepresentation(title: "Gentle heat", image: .init(systemName: "sun.min")),
        .turbo: DisplayRepresentation(title: "Turbo", image: .init(systemName: "bolt.fill")),
        .dry: DisplayRepresentation(title: "Dry", image: .init(systemName: "wind")),
        .pause: DisplayRepresentation(title: "Pause", image: .init(systemName: "pause")),
        .off: DisplayRepresentation(title: "Off", image: .init(systemName: "power")),
    ]

    init(_ mode: Mode) {
        switch mode {
        case .cool: self = .cool
        case .heat: self = .heat
        case .extendedHeat: self = .gentleHeat
        case .turbo: self = .turbo
        case .dry: self = .dry
        case .wait: self = .pause
        case .standby: self = .off
        }
    }
}

/// What Get BedJet Status hands the next action, so a shortcut can decide what to do.
struct BedJetStatus: TransientAppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "BedJet Status"

    @Property(title: "BedJet") var bedJet: String
    @Property(title: "Night running") var nightRunning: Bool
    @Property(title: "Schedule") var schedule: String?
    @Property(title: "Night ends") var nightEnds: Date?
    @Property(title: "Mode") var mode: BedJetMode
    /// In the unit Lull shows, which `temperatureUnit` names.
    @Property(title: "Temperature") var temperature: Int?
    /// "°F" or "°C".
    @Property(title: "Temperature unit") var temperatureUnit: String
    @Property(title: "Fan") var fan: Int?
    @Property(title: "Last seen") var lastSeen: Date?
    var summary = ""

    init() {
        bedJet = ""
        nightRunning = false
        mode = .off
        temperatureUnit = "°F"
    }

    init(_ report: BedJetReport, of title: String, fahrenheit: Bool) {
        self.init()
        bedJet = title
        nightRunning = report.nightRunning
        schedule = report.schedule
        nightEnds = report.nightEnds
        mode = BedJetMode(report.mode)
        temperature = report.temperature.map { Format.degrees($0, fahrenheit: fahrenheit) }
        temperatureUnit = fahrenheit ? "°F" : "°C"
        fan = report.fanPercent
        lastSeen = report.lastSeen
        summary = report.summary(of: title, fahrenheit: fahrenheit)
    }

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(summary)") }
}

extension AutomationError: CustomLocalizedStringResourceConvertible {
    var localizedStringResource: LocalizedStringResource { "\(errorDescription ?? "")" }
}

extension AutomationFailure: CustomLocalizedStringResourceConvertible {
    var localizedStringResource: LocalizedStringResource { "\(message)" }
}

// MARK: Running an action

/// An action on a BedJet. It runs without opening Lull when the BedJet is free, so an automation works with the
/// phone locked. When it isn't free, the action asks to go on in Lull, which asks another phone for it as its
/// controls do.
protocol BedJetIntent: ForegroundContinuableIntent {
    var bedJet: BedJetEntity? { get }
    /// The tab that shows what the action did, should it go on in Lull.
    static var tab: LullTab { get }
}

extension BedJetIntent {
    @MainActor
    /// What the action did is its result, such as "Set the fan on My side to 70%.", for the next action to use, rather
    /// than a dialog: Shortcuts waits on a dialog until someone taps Done, which nobody does in an automation, so the
    /// actions after it would never run.
    func run(_ request: AutomationRequest, on model: AppModel) async throws -> some IntentResult & ReturnsValue<String> {
        let target = try model.automationTarget(bedJet?.id)
        do {
            return .result(value: try await model.perform(request, on: target))
        } catch AutomationError.needsLull {
            try await continueInLull()
            model.select(target.id)
            AppCommands.send(.show(Self.tab))
            return .result(value: try await model.perform(request, on: target, mayAsk: true))
        }
    }

    @MainActor
    private func continueInLull() async throws {
        let dialog: IntentDialog = "\(AutomationError.needsLull.errorDescription ?? "") Continue in Lull?"
        if #available(iOS 26.0, *) {
            try await continueInForeground(dialog)
        } else {
            try await requestToContinueInForeground(dialog) {}
        }
    }
}

// MARK: Actions

/// Every action Lull offers Shortcuts. Shortcuts runs an action by its type's name, which the optimiser can't see, so a
/// Release build leaves out any action nothing in Lull's code names, such as Set Fan, and running it in Shortcuts fails
/// with "an internal error occurred". LullApp keeps this list, and so every action, in the app.
enum LullActions {
    static let all: [any AppIntent.Type] = [
        StartNightIntent.self, TurnOnBedJetIntent.self, TurnOffBedJetIntent.self, SetTemperatureIntent.self,
        SetFanIntent.self, SetRunTimeIntent.self, SetLightRingIntent.self, MuteBeepsIntent.self, GetBedJetStatusIntent.self,
    ]
}

struct StartNightIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Start Night"
    static let description = IntentDescription(
        "Starts a schedule on your BedJet. Leave Schedule empty for tonight's, and BedJet empty for the one Lull shows. A night already running is left as it is."
    )
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.schedule

    @Parameter(title: "Schedule")
    var schedule: ScheduleEntity?

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Start \(\.$schedule) on \(\.$bedJet)")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.startNight(schedule: schedule?.id), on: model)
    }
}

struct TurnOnBedJetIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Turn On BedJet"
    static let description = IntentDescription("Turns on the air, like the Remote. Choosing a mode ends a schedule that's running.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "Mode", default: .heat)
    var mode: AirMode

    @Parameter(title: "Temperature", description: "In the unit Lull shows. Kept to what the mode allows; Turbo has its own.")
    var temperature: Int?

    @Parameter(title: "Fan", description: "5 to 100%, in steps of 5.")
    var fan: Int?

    @Parameter(title: "Turns off in", description: "Minutes. As long as the BedJet allows if left empty.")
    var minutes: Int?

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Turn on \(\.$mode) on \(\.$bedJet)") {
            \.$temperature
            \.$fan
            \.$minutes
        }
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.air(AirSettings(mode: mode.mode, degrees: temperature, fanPercent: fan, minutes: minutes)), on: model)
    }
}

struct TurnOffBedJetIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Turn Off BedJet"
    static let description = IntentDescription("Ends the night if a schedule is running, otherwise turns the air off.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Turn off \(\.$bedJet)")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.turnOff, on: model)
    }
}

struct SetTemperatureIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Set Temperature"
    static let description = IntentDescription("Sets the temperature of the air that's on, without changing the mode or ending a schedule.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "Temperature", description: "In the unit Lull shows. Kept to what the mode allows.")
    var temperature: Int

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Set \(\.$bedJet) to \(\.$temperature)°")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.air(AirSettings(degrees: temperature)), on: model)
    }
}

struct SetFanIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Set Fan"
    static let description = IntentDescription("Sets the fan of the air that's on, without changing the mode or ending a schedule.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "Fan", description: "5 to 100%, in steps of 5.", default: 40)
    var fan: Int

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Set the fan on \(\.$bedJet) to \(\.$fan)%")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.air(AirSettings(fanPercent: fan)), on: model)
    }
}

struct SetRunTimeIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Set Run Time"
    static let description = IntentDescription("Sets when the air turns off, like the Remote's timer. A running schedule sets its own.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "Minutes", description: "Kept to what the BedJet allows.", default: 60)
    var minutes: Int

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Turn off \(\.$bedJet) in \(\.$minutes) minutes")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.air(AirSettings(minutes: minutes)), on: model)
    }
}

struct SetLightRingIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Light Ring"
    static let description = IntentDescription("Turns the light ring on your BedJet on or off.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "Light ring", default: true, displayName: Bool.IntentDisplayName(true: "Turn on", false: "Turn off"))
    var on: Bool

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("\(\.$on) the light ring on \(\.$bedJet)")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.lightRing(on: on), on: model)
    }
}

struct MuteBeepsIntent: BedJetIntent {
    static let title: LocalizedStringResource = "Mute Beeps"
    static let description = IntentDescription("Mutes or unmutes the beeps your BedJet makes.")
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
    static let tab = LullTab.remote

    @Parameter(title: "Beeps", default: true, displayName: Bool.IntentDisplayName(true: "Mute", false: "Unmute"))
    var muted: Bool

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("\(\.$muted) beeps on \(\.$bedJet)")
    }

    @Dependency private var model: AppModel

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        try await run(.muteBeeps(muted), on: model)
    }
}

/// Never connects, so it is quick and leaves the BedJet to whichever phone has it.
struct GetBedJetStatusIntent: AppIntent {
    static let title: LocalizedStringResource = "Get BedJet Status"
    static let description = IntentDescription(
        "What Lull last saw your BedJet doing, without connecting to it. Use it to decide what a shortcut does next, such as skipping a preheat when a night is already running."
    )

    @Parameter(title: "BedJet")
    var bedJet: BedJetEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Get the status of \(\.$bedJet)")
    }

    @Dependency private var model: AppModel

    @MainActor
    // No dialog, as for the other actions: a shortcut that checks the status before a preheat would wait on it.
    func perform() async throws -> some IntentResult & ReturnsValue<BedJetStatus> {
        let target = try model.automationTarget(bedJet?.id)
        let status = BedJetStatus(model.status(of: target), of: model.title(of: target), fahrenheit: model.useFahrenheit)
        return .result(value: status)
    }
}
