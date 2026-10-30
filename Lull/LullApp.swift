import AppIntents
import SwiftUI

@main
struct LullApp: App {
    @State private var model: AppModel

    init() {
        let model = AppModel()
        _model = State(initialValue: model)
        // Shortcuts runs Lull's actions in this process, often with Lull off screen.
        AppDependencyManager.shared.add(dependency: model)
        withExtendedLifetime(LullActions.all) {}
        LullShortcuts.updateAppShortcutParameters()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(.white)
        }
    }
}
