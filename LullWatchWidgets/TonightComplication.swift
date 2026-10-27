import BedJetKit
import SwiftUI
import WidgetKit

/// Tonight on the watch face, drawn as on the iPhone's Lock Screen. It reads what the watch last heard from Lull on the
/// iPhone, and plays the night out by the clock.
struct TonightComplication: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "Tonight", intent: BedJetConfiguration.self, provider: NightProvider()) { entry in
            TonightAccessory(snapshot: entry.snapshot, now: entry.date)
                .widgetURL(entry.snapshot.link(to: .schedule))
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Tonight")
        .description("Tonight's schedule, and where the night is up to once it's running.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

#Preview("Running", as: .accessoryRectangular) {
    TonightComplication()
} timeline: {
    NightEntry(date: .now, snapshot: .preview())
}
