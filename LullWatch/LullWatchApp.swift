import SwiftUI

@main
struct LullWatchApp: App {
    @State private var model = WatchModel.launch()

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environment(model)
                .tint(.white)
        }
        // The iPhone wakes the watch when what the complications show changes.
        .backgroundTask(.watchConnectivity) {
            await (model.phone as? PhoneLink)?.drain()
        }
    }
}
