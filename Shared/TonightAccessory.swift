import BedJetKit
import SwiftUI
import WidgetKit

/// Tonight on the Lock Screen and the watch face: where the night is up to once it's running, otherwise tonight's
/// schedule. The system draws these in one colour, so they say it with symbols and words rather than Lull's colours.
struct TonightAccessory: View {
    let snapshot: WidgetSnapshot
    let now: Date
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let run = snapshot.run(at: now) {
            running(run)
        } else if let schedule = snapshot.tonight {
            planned(schedule)
        } else {
            empty
        }
    }

    @ViewBuilder
    private func running(_ run: ActiveRun) -> some View {
        let current = run.phase(at: now)
        let mode = current?.phase.mode ?? .standby
        let minutesLeft = current?.minutesLeft ?? 0
        let progress = run.progress(at: now)
        let wake = run.endsAt.formatted(date: .omitted, time: .shortened)
        let spoken = snapshot.spoken([
            run.schedule.name,
            current.map { "phase \($0.index + 1) of \(run.schedule.phases.count)" },
            current.map { "\($0.phase.mode.activity), \(Format.spokenDuration($0.minutesLeft)) left" },
            "ends at \(wake)",
        ].compactMap { $0 }.joined(separator: ", "))

        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Text(run.schedule.name).font(.headline).widgetAccentable()
                // The mode shows as its symbol; there's no room for its name beside the time.
                Label("\(Format.shortDuration(minutesLeft)) left", systemImage: mode.symbol)
                Text("Ends \(wake)").foregroundStyle(.secondary)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
        case .accessoryInline:
            // The watch face's inline slot is narrower than the Lock Screen's, so the end time stays when the mode can't.
            ViewThatFits {
                Label("\(mode.activity) · ends \(wake)", systemImage: mode.symbol)
                Label("Ends \(wake)", systemImage: mode.symbol)
            }
        #if os(watchOS)
        case .accessoryCorner:
            Image(systemName: mode.symbol)
                .font(.system(size: 20, weight: .semibold))
                .widgetLabel {
                    Gauge(value: progress) {
                        Text(run.schedule.name)
                    } currentValueLabel: {
                        Text("\(Format.shortDuration(minutesLeft)) left")
                    }
                }
                .accessibilityLabel(spoken)
        #endif
        default:
            Gauge(value: progress) {
                Image(systemName: "moon.fill")
            } currentValueLabel: {
                Image(systemName: mode.symbol).font(.system(size: 16, weight: .semibold))
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .accessibilityLabel(spoken)
        }
    }

    @ViewBuilder
    private func planned(_ schedule: Schedule) -> some View {
        let spoken = snapshot.spoken("Tonight, \(schedule.name), \(schedule.spokenSummary)")
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label(snapshot.headed("Tonight"), systemImage: "moon.stars.fill").font(.caption).foregroundStyle(.secondary)
                Text(schedule.name).font(.headline).widgetAccentable()
                Text(schedule.summary)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
        case .accessoryInline:
            ViewThatFits {
                Label("Tonight: \(schedule.name)", systemImage: "moon.stars.fill")
                Label(schedule.name, systemImage: "moon.stars.fill")
            }
        #if os(watchOS)
        case .accessoryCorner:
            Image(systemName: "moon.stars.fill")
                .font(.system(size: 20, weight: .semibold))
                .widgetLabel(schedule.name)
                .accessibilityLabel(spoken)
        #endif
        default:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    Image(systemName: "moon.stars.fill").font(.system(size: 15, weight: .semibold))
                    Text(schedule.shortLength)
                        .font(.system(size: 11, weight: .semibold))
                        .minimumScaleFactor(0.7)
                }
                .padding(4)
            }
            .widgetAccentable()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
        }
    }

    @ViewBuilder
    private var empty: some View {
        switch family {
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text("Lull").font(.headline)
                Text("No schedule yet")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .accessoryInline:
            Label("No schedule yet", systemImage: "moon.zzz.fill")
        #if os(watchOS)
        case .accessoryCorner:
            Image(systemName: "moon.zzz.fill")
                .font(.system(size: 20, weight: .semibold))
                .widgetLabel("No schedule yet")
        #endif
        default:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "moon.zzz.fill").font(.system(size: 18, weight: .semibold))
            }
            .accessibilityLabel("Lull, no schedule yet")
        }
    }
}

extension WidgetFamily {
    /// The Lock Screen's and the watch face's, which the system draws in one colour.
    var isAccessory: Bool {
        #if os(watchOS)
        if self == .accessoryCorner { return true }
        #endif
        return self == .accessoryCircular || self == .accessoryRectangular || self == .accessoryInline
    }
}
