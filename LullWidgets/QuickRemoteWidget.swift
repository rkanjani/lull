import AppIntents
import BedJetKit
import SwiftUI
import WidgetKit

struct QuickRemoteEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
    /// What this widget's buttons set, as chosen by editing it.
    var presets = RemotePreset.defaults
    /// The BedJet picked for this widget; nil until it's edited, when the buttons set the one on screen.
    var bedJet: BedJetEntity?
}

/// Redraws as the Tonight widget does, for the line above the buttons; the buttons come from editing the widget.
struct QuickRemoteProvider: AppIntentTimelineProvider {
    var preview = WidgetSnapshot.preview()

    func placeholder(in context: Context) -> QuickRemoteEntry {
        QuickRemoteEntry(date: Date(), snapshot: preview)
    }

    func snapshot(for configuration: QuickRemoteConfiguration, in context: Context) async -> QuickRemoteEntry {
        let snapshot = context.isPreview ? preview : WidgetSnapshot.load() ?? WidgetSnapshot()
        // The gallery shows the sample BedJets, which a real one picked for the widget isn't among.
        return QuickRemoteEntry(date: Date(), snapshot: snapshot, presets: configuration.presets(fahrenheit: snapshot.useFahrenheit),
                                bedJet: context.isPreview ? nil : configuration.bedJet)
    }

    func timeline(for configuration: QuickRemoteConfiguration, in context: Context) async -> Timeline<QuickRemoteEntry> {
        let snapshot = WidgetSnapshot.load() ?? WidgetSnapshot()
        let presets = configuration.presets(fahrenheit: snapshot.useFahrenheit)
        let entries = snapshot.redraws(from: Date()).map {
            QuickRemoteEntry(date: $0, snapshot: snapshot, presets: presets, bedJet: configuration.bedJet)
        }
        // The app redraws the widgets whenever what they show changes.
        return Timeline(entries: entries, policy: .never)
    }
}

/// A few taps for a hot or cold night: each button opens Lull and sets the widget's BedJet to it. Editing the widget
/// chooses the BedJet, by name, and what each button sets.
struct QuickRemoteWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "QuickRemote", intent: QuickRemoteConfiguration.self, provider: QuickRemoteProvider()) { entry in
            QuickRemoteView(entry: entry)
        }
        .configurationDisplayName("Quick Remote")
        .description("Three buttons that open Lull and set your BedJet. Edit the widget to choose each one's mode, time, temperature and fan.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct QuickRemoteView: View {
    let entry: QuickRemoteEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.widgetRenderingMode) private var rendering

    private var bedJet: WidgetBedJet? { entry.snapshot.bedJet(entry.bedJet?.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            status
            if family == .systemMedium {
                HStack(spacing: 8) { buttons }
            } else {
                VStack(spacing: 6) { buttons }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(entry.bedJet.map { LullTab.remote.url(showing: $0.id) } ?? LullTab.remote.url)
        .environment(\.colorScheme, .dark)
        .containerBackground(for: .widget) { WidgetBackground() }
    }

    /// What the BedJet is doing, so a tap is made knowing: "❄︎ Cooling  0:24:10", under its name once there's more than
    /// one to tell apart, as on the Tonight widget.
    @ViewBuilder
    private var status: some View {
        if let bedJet, let live = bedJet.live(at: entry.date) {
            let (mode, start): (Mode, Date) = switch live {
            case let .night(run): (run.phase(at: entry.date)?.phase.mode ?? .standby, run.startedAt)
            case let .air(mode, _, _, _): (mode, entry.date)
            }
            VStack(alignment: .leading, spacing: 3) {
                if entry.snapshot.hasSeveralBedJets { WidgetEyebrow(bedJet.name) }
                HStack(spacing: 6) {
                    // The small size has room for the mode's symbol beside the time, not its name.
                    if family == .systemSmall {
                        Image(systemName: mode.symbol)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(mode.widgetTint(rendering))
                            .accessibilityLabel(mode.activity)
                    } else {
                        ModeLabel(mode: mode, tint: mode.widgetTint(rendering))
                    }
                    Spacer(minLength: 4)
                    Countdown(start: start, end: live.endsAt, size: 13, tint: Theme.secondary, now: entry.date)
                }
            }
        } else {
            // A widget set to a BedJet that has since been forgotten says so; its buttons do nothing.
            WidgetEyebrow(bedJet?.name ?? (entry.bedJet == nil ? "BedJet Remote" : "Forgotten BedJet"))
        }
    }

    private var buttons: some View {
        // By position: two buttons may be set the same.
        ForEach(Array(entry.presets.enumerated()), id: \.offset) { _, preset in
            Button(intent: QuickRemoteIntent(preset: preset, bedJet: entry.bedJet)) {
                label(for: preset)
                    .foregroundStyle(preset.mode.widgetTint(rendering))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(preset.mode.tint.opacity(0.16), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(preset.mode == .standby ? "Turn off" : preset.title)
            .accessibilityValue(preset.setting(fahrenheit: entry.snapshot.useFahrenheit) ?? "")
        }
    }

    /// The medium size's buttons are tall and narrow, so the mode's symbol, its name and the time stack; the small
    /// size's are short and wide, so they read along one line: "Cool · 30 min".
    @ViewBuilder
    private func label(for preset: RemotePreset) -> some View {
        if family == .systemMedium {
            VStack(spacing: 4) {
                Image(systemName: preset.mode.symbol)
                    .font(.system(size: 18, weight: .semibold))
                Text(preset.mode.title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if preset.mode != .standby {
                    Text(Format.duration(preset.minutes))
                        .font(.system(size: 12, weight: .medium))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .opacity(0.75)
                }
            }
            .padding(.horizontal, 4)
        } else {
            Label(preset.title, systemImage: preset.mode.symbol)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
    }
}

#Preview("Small", as: .systemSmall) {
    QuickRemoteWidget()
} timeline: {
    QuickRemoteEntry(date: .now, snapshot: .preview())
}

#Preview("Medium, edited", as: .systemMedium) {
    QuickRemoteWidget()
} timeline: {
    QuickRemoteEntry(date: .now, snapshot: .preview(), presets: [
        RemotePreset(mode: .turbo, minutes: 10, temperature: 86, fanPercent: 100),
        RemotePreset(mode: .dry, minutes: 45, temperature: 60, fanPercent: 60),
        RemotePreset(mode: .extendedHeat, minutes: 90, temperature: 58, fanPercent: 20),
    ])
}
