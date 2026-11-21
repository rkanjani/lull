import AppIntents

/// Lull's actions in Siri, Spotlight and the Shortcuts app, on the iPhone and the watch. Each opens Lull, which on the
/// watch asks Lull on the iPhone. The widget extensions leave this out: only an app offers App Shortcuts.
struct LullShortcuts: AppShortcutsProvider {
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
}
