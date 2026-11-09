import AppIntents

/// Lull's actions in Siri, Spotlight and the Shortcuts app, on the iPhone and the watch. On the iPhone they reach the
/// BedJet without opening Lull; on the watch each opens Lull, which asks Lull on the iPhone. The widget extensions
/// leave this out: only an app offers App Shortcuts.
struct LullShortcuts: AppShortcutsProvider {
    #if os(watchOS)
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartNightIntent(),
            phrases: ["Start my night in \(.applicationName)", "Start \(.applicationName)"],
            shortTitle: "Start Night",
            systemImageName: "moon.stars.fill"
        )
        AppShortcut(
            intent: OpenLullIntent(tab: .remote),
            phrases: ["Open the \(.applicationName) remote"],
            shortTitle: "Remote",
            systemImageName: "dial.medium.fill"
        )
    }
    #else
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartNightIntent(),
            phrases: ["Start my night in \(.applicationName)", "Start \(.applicationName)", "Start \(\.$schedule) in \(.applicationName)"],
            shortTitle: "Start Night",
            systemImageName: "moon.stars.fill"
        )
        AppShortcut(
            intent: TurnOnBedJetIntent(),
            phrases: ["Turn on my BedJet with \(.applicationName)", "Turn on \(\.$mode) in \(.applicationName)"],
            shortTitle: "Turn On",
            systemImageName: "power"
        )
        AppShortcut(
            intent: TurnOffBedJetIntent(),
            phrases: ["Turn off my BedJet with \(.applicationName)", "End my night in \(.applicationName)"],
            shortTitle: "Turn Off",
            systemImageName: "moon.zzz"
        )
        AppShortcut(
            intent: GetBedJetStatusIntent(),
            phrases: ["Is my night running in \(.applicationName)", "Check my BedJet in \(.applicationName)"],
            shortTitle: "BedJet Status",
            systemImageName: "info.circle"
        )
        AppShortcut(
            intent: OpenLullIntent(tab: .remote),
            phrases: ["Open the \(.applicationName) remote"],
            shortTitle: "Remote",
            systemImageName: "dial.medium.fill"
        )
    }
    #endif
}
