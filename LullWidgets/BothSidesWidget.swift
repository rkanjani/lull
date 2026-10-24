import AppIntents
import BedJetKit
import SwiftUI
import WidgetKit

struct BothSidesEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
    /// The BedJets picked for each half, or nil for the first two Lull knows.
    var left: UUID?
    var right: UUID?
}

/// Laid out ahead of time as the Tonight widget is, for each side's night and Remote timer.
struct BothSidesProvider: AppIntentTimelineProvider {
    var preview = WidgetSnapshot.preview()

    func placeholder(in context: Context) -> BothSidesEntry {
        BothSidesEntry(date: Date(), snapshot: preview)
    }

    func snapshot(for configuration: BothSidesConfiguration, in context: Context) async -> BothSidesEntry {
        // The gallery shows the sample BedJets, which real ones picked for the widget aren't among.
        if context.isPreview { return BothSidesEntry(date: Date(), snapshot: preview) }
        let snapshot = WidgetSnapshot.load() ?? WidgetSnapshot()
        return BothSidesEntry(date: Date(), snapshot: snapshot, left: configuration.left?.id, right: configuration.right?.id)
    }

    func timeline(for configuration: BothSidesConfiguration, in context: Context) async -> Timeline<BothSidesEntry> {
        let snapshot = WidgetSnapshot.load() ?? WidgetSnapshot()
        let entries = snapshot.redraws(from: Date()).map {
            BothSidesEntry(date: $0, snapshot: snapshot, left: configuration.left?.id, right: configuration.right?.id)
        }
        // The app redraws the widgets whenever what they show changes.
        return Timeline(entries: entries, policy: .never)
    }
}

/// Two BedJets, one on each side of the bed, side by side, each picked by editing the widget. In StandBy, which shows
/// only small widgets, the same is two Tonight widgets, each set to one side.
struct BothSidesWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "BothSides", intent: BothSidesConfiguration.self, provider: BothSidesProvider()) { entry in
            BothSidesView(entry: entry)
        }
        .configurationDisplayName("Both Sides")
        .description("Both BedJets at a glance, one on each side of the bed.")
        .supportedFamilies([.systemMedium])
    }
}

struct BothSidesView: View {
    let entry: BothSidesEntry
    @Environment(\.showsWidgetContainerBackground) private var showsBackground

    var body: some View {
        let sides = entry.snapshot.sides(left: entry.left, right: entry.right)
        HStack(spacing: 14) {
            side(sides.left)
            Rectangle()
                .fill(Theme.hairline)
                .frame(width: 1)
                .accessibilityHidden(true)
            side(sides.right)
        }
        .environment(\.colorScheme, .dark)
        .containerBackground(for: .widget) { WidgetBackground() }
    }

    @ViewBuilder
    private func side(_ bedJet: WidgetBedJet?) -> some View {
        if let bedJet {
            Link(destination: (bedJet.live(at: entry.date)?.tab ?? .schedule).url(showing: bedJet.id)) {
                BedJetSummary(bedJet: bedJet, snapshot: entry.snapshot, now: entry.date, isStandBy: !showsBackground)
            }
        } else {
            Link(destination: LullTab.remote.url) {
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: "plus.circle")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Theme.secondary)
                    Spacer(minLength: 0)
                    Text(emptySide)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .accessibilityElement(children: .combine)
            }
        }
    }

    /// What a side with no BedJet says: none known yet, only the one, or one set to a BedJet forgotten since.
    private var emptySide: String {
        switch entry.snapshot.bedJets.count {
        case 0: "Open Lull to choose your BedJet."
        case 1: "Add a second BedJet in Lull."
        default: "Edit the widget to choose a BedJet."
        }
    }
}

#Preview("Both", as: .systemMedium) {
    BothSidesWidget()
} timeline: {
    BothSidesEntry(date: .now, snapshot: .preview())
}
