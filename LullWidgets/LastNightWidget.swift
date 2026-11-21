import BedJetKit
import SwiftUI
import WidgetKit

struct LastNightWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "LastNight", intent: BedJetConfiguration.self, provider: NightProvider(preview: Self.preview)) { entry in
            LastNightView(entry: entry)
        }
        .configurationDisplayName("Last Night")
        .description("How your BedJet ran last night, and the room temperature through it.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }

    /// Sample nights with nothing running, so the gallery shows the latest of them.
    private static var preview: WidgetSnapshot {
        var snapshot = WidgetSnapshot.preview()
        snapshot.run = nil
        return snapshot
    }
}

struct LastNightView: View {
    let entry: NightEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let night = entry.snapshot.lastNight(at: entry.date), let start = night.entries.first?.date, let end = night.entries.last?.date {
                content(night, start: start, end: end)
            } else {
                empty
            }
        }
        .widgetURL(entry.snapshot.link(to: .history))
        .environment(\.colorScheme, .dark)
        .containerBackground(for: .widget) { WidgetBackground() }
    }

    @ViewBuilder
    private func content(_ night: Night, start: Date, end: Date) -> some View {
        let title = night.evening.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        let names = night.scheduleNames.joined(separator: ", ")
        let rooms = night.entries.compactMap(\.room)
        let room = rooms.min().flatMap { low in
            rooms.max().map { high in
                low == high ? entry.snapshot.degrees(low) : "\(Format.degrees(low, fahrenheit: entry.snapshot.useFahrenheit))–\(entry.snapshot.degrees(high))"
            }
        }
        let changes = night.entries.filter { if case .air = $0.event { true } else { false } }.count
        let times = "\(start.formatted(date: .omitted, time: .shortened)) – \(end.formatted(date: .omitted, time: .shortened))"
        let spoken = entry.snapshot.spoken([
            "Last night, \(title)",
            names.isEmpty ? nil : names,
            "\(start.formatted(date: .omitted, time: .shortened)) to \(end.formatted(date: .omitted, time: .shortened))",
            room.map { "room \($0.replacingOccurrences(of: "–", with: " to "))" },
            changes > 0 ? "\(changes) \(changes == 1 ? "change" : "changes")" : nil,
        ].compactMap { $0 }.joined(separator: ", "))

        Group {
            if family == .systemSmall {
                VStack(alignment: .leading, spacing: 0) {
                    // Stacked: a name and "Last night" don't fit across a small widget together.
                    if let name = entry.snapshot.headingName {
                        WidgetEyebrow(name).padding(.bottom, 3)
                    }
                    WidgetEyebrow("Last night")
                    Text(title)
                        .font(.system(size: 17, weight: .semibold))
                        .padding(.top, 4)
                    Text(names.isEmpty ? times : names)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Theme.secondary)
                    Spacer(minLength: 6)
                    NightCurve(phases: night.phases, lineWidth: 2.5)
                        .frame(height: 40)
                        .padding(.horizontal, -8)
                    Spacer(minLength: 6)
                    Text(names.isEmpty ? Format.duration(Int(end.timeIntervalSince(start) / 60)) : times)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Theme.secondary)
                        .monospacedDigit()
                        .minimumScaleFactor(0.8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .top, spacing: 8) {
                        VStack(alignment: .leading, spacing: 3) {
                            WidgetEyebrow(entry.snapshot.headed("Last night"))
                            Text(title).font(.system(size: 19, weight: .semibold))
                            if !names.isEmpty {
                                Text(names).font(.system(size: 12, weight: .medium)).foregroundStyle(Theme.secondary)
                            }
                        }
                        Spacer(minLength: 0)
                        VStack(alignment: .trailing, spacing: 3) {
                            if let room {
                                Label(room, systemImage: "thermometer.medium")
                                    .font(.system(size: 13, weight: .semibold))
                                    .monospacedDigit()
                            }
                            if changes > 0 {
                                Text("\(changes) \(changes == 1 ? "change" : "changes")")
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(Theme.secondary)
                            }
                        }
                    }
                    NightCurve(phases: night.phases)
                        .frame(maxHeight: .infinity)
                        .padding(.horizontal, -8)
                    TimeRow(start: start, end: end)
                }
            }
        }
        .lineLimit(1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 3) {
                if let name = entry.snapshot.headingName { WidgetEyebrow(name) }
                WidgetEyebrow("Last night")
            }
            Spacer(minLength: 0)
            Text("No nights yet").font(.system(size: 17, weight: .semibold))
            Text("Nights with your BedJet show up here the morning after.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
    }
}

#Preview(as: .systemMedium) {
    LastNightWidget()
} timeline: {
    NightEntry(date: .now, snapshot: .preview())
}
