import BedJetKit
import SwiftUI

/// How the BedJet ran last night, and the room temperature through it, as the Last Night widget shows.
struct LastNightPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        TimelineView(.everyMinute) { context in
            let shown = model.shown
            ScrollView {
                if let night = shown.lastNight(at: context.date), let start = night.entries.first?.date, let end = night.entries.last?.date {
                    content(night, in: shown, start: start, end: end)
                } else {
                    EmptyMessage(symbol: "clock.arrow.circlepath", title: "No nights yet",
                                 message: "Nights with your BedJet show up here the morning after.")
                }
            }
        }
        .navigationTitle("Last Night")
    }

    @ViewBuilder
    private func content(_ night: Night, in shown: WidgetSnapshot, start: Date, end: Date) -> some View {
        let title = night.evening.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        let names = night.scheduleNames.joined(separator: ", ")
        let rooms = night.entries.compactMap(\.room)
        let room = rooms.min().flatMap { low in
            rooms.max().map { high in
                low == high ? shown.degrees(low) : "\(Format.degrees(low, fahrenheit: shown.useFahrenheit))–\(shown.degrees(high))"
            }
        }
        let changes = night.entries.filter { if case .air = $0.event { true } else { false } }.count
        let spoken = [
            "Last night, \(title)",
            names.isEmpty ? nil : names,
            "\(start.formatted(date: .omitted, time: .shortened)) to \(end.formatted(date: .omitted, time: .shortened))",
            room.map { "room \($0.replacingOccurrences(of: "–", with: " to "))" },
            changes > 0 ? "\(changes) \(changes == 1 ? "change" : "changes")" : nil,
        ].compactMap { $0 }.joined(separator: ", ")

        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(17, .semibold)
                if !names.isEmpty {
                    Text(names).scaledFont(13, .medium).foregroundStyle(Theme.secondary).lineLimit(2)
                }
            }
            NightCurve(phases: night.phases, lineWidth: 2.5)
                .frame(height: 44)
                .padding(.horizontal, -8)
            TimeRow(start: start, end: end)
            HStack {
                if let room {
                    Label(room, systemImage: "thermometer.medium").scaledFont(13, .semibold).monospacedDigit()
                }
                Spacer(minLength: 4)
                if changes > 0 {
                    Text("\(changes) \(changes == 1 ? "change" : "changes")").scaledFont(12, .medium).foregroundStyle(Theme.secondary)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }
}
