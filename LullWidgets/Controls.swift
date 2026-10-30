import AppIntents
import SwiftUI
import WidgetKit

/// Starts tonight's schedule from Control Center, the Lock Screen or the Action button.
struct StartNightControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.rohankanjani.lull.start-night", provider: Provider()) { state in
            ControlWidgetButton(action: StartNightInLullIntent()) {
                Label(state.isRunning ? "Night Running" : "Start Night", systemImage: "moon.stars.fill")
                Text(state.name)
            }
        }
        .displayName("Start Night")
        .description("Opens Lull and starts tonight's schedule on your BedJet.")
    }

    struct State {
        var name: String
        var isRunning: Bool
    }

    struct Provider: ControlValueProvider {
        var previewValue: State { State(name: Schedule.templates[0].name, isRunning: false) }

        func currentValue() async throws -> State {
            let snapshot = WidgetSnapshot.load() ?? WidgetSnapshot()
            if let run = snapshot.run(at: Date()) { return State(name: run.schedule.name, isRunning: true) }
            return State(name: snapshot.tonight?.name ?? "No schedule", isRunning: false)
        }
    }
}

/// Opens straight to the Remote.
struct RemoteControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.rohankanjani.lull.remote") {
            ControlWidgetButton(action: OpenLullIntent(tab: .remote)) {
                Label("BedJet Remote", systemImage: "dial.medium.fill")
            }
        }
        .displayName("BedJet Remote")
        .description("Opens Lull's remote for your BedJet.")
    }
}
