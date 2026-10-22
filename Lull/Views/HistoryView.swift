import BedJetKit
import SwiftUI

/// What the BedJet did each night, so the morning after you can see what you picked at 3 a.m.
struct HistoryView: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    @State private var expanded: Set<Night.ID> = []

    var body: some View {
        Screen(title: "History") {
            let nights = bedJet.nights
            if nights.isEmpty {
                Card {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("No nights yet").scaledFont(22, .semibold)
                        Text("Every change to your BedJet shows up here with the time and room temperature, so in the morning you can see what you picked overnight.")
                            .scaledFont(15)
                            .foregroundStyle(Theme.secondary)
                    }
                }
            }
            VStack(spacing: 10) {
                ForEach(nights) { night in
                    NightRow(night: night, isExpanded: expanded.contains(night.id)) {
                        if expanded.remove(night.id) == nil { expanded.insert(night.id) }
                    }
                }
            }
        }
        .animation(.smooth(duration: 0.35), value: expanded)
    }
}

/// One night at a glance, opening up to everything that happened in it.
private struct NightRow: View {
    let night: Night
    let isExpanded: Bool
    let toggle: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // At the largest text sizes the curve moves under the text so the date and schedule keep the width.
        let large = typeSize.isAccessibilitySize
        Card(padding: 18) {
            VStack(alignment: .leading, spacing: 20) {
                Button(action: toggle) {
                    HStack(spacing: 14) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(title).scaledFont(19, .semibold)
                            Text(summary(through: "–"))
                                .scaledFont(13, .medium)
                                .foregroundStyle(Theme.secondary)
                                .monospacedDigit()
                            if large { NightCurve(phases: night.phases, lineWidth: 2).frame(height: 40).padding(.top, 6) }
                        }
                        Spacer(minLength: 8)
                        if !large { NightCurve(phases: night.phases, lineWidth: 2).frame(width: 88, height: 40) }
                        Image(systemName: "chevron.down")
                            .scaledFont(13, .semibold)
                            .foregroundStyle(Theme.tertiary)
                            .rotationEffect(.degrees(isExpanded ? 180 : 0))
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title), \(summary(through: "to"))")
                .accessibilityValue(isExpanded ? "Expanded" : "Collapsed")
                .accessibilityHint(isExpanded ? "Hides this night" : "Shows everything that happened this night")
                .accessibilityIdentifier("Night")

                if isExpanded {
                    // Redrawn each minute, so a phase the schedule moves on to while History is open shows up.
                    TimelineView(.everyMinute) { context in
                        let timeline = night.timeline(until: context.date)
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(timeline.enumerated()), id: \.element.id) { index, entry in
                                EntryRow(entry: entry, isLast: index == timeline.count - 1)
                            }
                        }
                    }
                    .transition(.opacity)
                }
            }
        }
    }

    private var title: String {
        night.evening.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }

    /// The schedule that ran, or else when the first and last changes happened.
    private func summary(through separator: String) -> String {
        let names = night.scheduleNames
        if !names.isEmpty { return names.joined(separator: ", ") }
        guard let first = night.entries.first?.date, let last = night.entries.last?.date else { return "" }
        let start = first.formatted(date: .omitted, time: .shortened), end = last.formatted(date: .omitted, time: .shortened)
        return start == end ? start : "\(start) \(separator) \(end)"
    }
}

private struct EntryRow: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    let entry: HistoryEntry
    let isLast: Bool
    @ScaledMetric(relativeTo: .footnote) private var badge: CGFloat = 28
    @ScaledMetric(relativeTo: .footnote) private var timeWidth: CGFloat = 64
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // At the largest text sizes the time sits above what happened instead of in its own column.
        let large = typeSize.isAccessibilitySize
        HStack(alignment: .top, spacing: 12) {
            if !large {
                time
                    .frame(width: timeWidth, alignment: .trailing)
                    .padding(.top, 6)
            }
            VStack(spacing: 0) {
                Image(systemName: symbol)
                    .scaledFont(12, .semibold)
                    .foregroundStyle(tint)
                    .frame(width: badge, height: badge)
                    .background(tint.opacity(0.16), in: Circle())
                    .accessibilityHidden(true)
                // Joins the entries into one line through the night.
                if !isLast {
                    Rectangle().fill(Theme.hairline).frame(width: 1).frame(minHeight: 14)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                if large { time }
                Text(title(spoken: false)).scaledFont(16, .semibold).accessibilityLabel(title(spoken: true))
                if let detail = detail(spoken: false) {
                    Text(detail).scaledFont(13).foregroundStyle(Theme.secondary).accessibilityLabel(self.detail(spoken: true) ?? detail)
                }
            }
            .padding(.top, 4)
            .padding(.bottom, isLast ? 0 : 14)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    private var time: some View {
        Text(entry.date.formatted(date: .omitted, time: .shortened))
            .scaledFont(13, .medium)
            .monospacedDigit()
            .foregroundStyle(Theme.secondary)
    }

    /// Parts are joined with a dot on screen; VoiceOver gets commas and durations in words.
    private func title(spoken: Bool) -> String {
        switch entry.event {
        case let .air(mode, temperature, fan):
            return air(mode, temperature: temperature, fan: fan, spoken: spoken)
        case let .phaseStarted(_, _, phase):
            return air(phase.mode, temperature: phase.temperature, fan: phase.fanPercent, spoken: spoken)
        case .off: return "Turned off"
        case let .scheduleStarted(name, _): return "Started \(name ?? "a schedule")"
        case let .scheduleEnded(name, ending):
            let name = name ?? "Schedule"
            switch ending {
            case .byYou: return "Ended \(name)"
            case .finished: return "\(name) finished"
            case .early(let minutes):
                return "\(name) stopped \(spoken ? Format.spokenDuration(minutes) : Format.duration(minutes)) early"
            }
        }
    }

    private func air(_ mode: Mode, temperature: Int, fan: Int, spoken: Bool) -> String {
        // A pause in a schedule blows no air.
        guard mode != .wait else { return mode.title }
        let parts = bedJet.adjustsTemperature(mode) ? [mode.title, model.degrees(temperature), "fan \(fan)%"] : [mode.title, "fan \(fan)%"]
        return parts.joined(separator: spoken ? ", " : " · ")
    }

    private func detail(spoken: Bool) -> String? {
        var parts: [String] = []
        if case let .phaseStarted(number, count, _) = entry.event { parts.append("Phase \(number) of \(count)") }
        if case .off(timerRanOut: true) = entry.event { parts.append("Timer ran out") }
        if let room = entry.room { parts.append("Room \(model.degrees(room))") }
        if entry.noticedLater { parts.append("Seen when Lull reopened") }
        return parts.isEmpty ? nil : parts.joined(separator: spoken ? ", " : " · ")
    }

    private var symbol: String {
        switch entry.event {
        case let .air(mode, _, _): return mode.symbol
        case let .phaseStarted(_, _, phase): return phase.mode.symbol
        case .off: return "power"
        case .scheduleStarted: return "moon.stars.fill"
        case .scheduleEnded(_, .early): return "exclamationmark"
        case .scheduleEnded: return "sunrise.fill"
        }
    }

    private var tint: Color {
        switch entry.event {
        case let .air(mode, _, _): return mode.tint
        case let .phaseStarted(_, _, phase): return phase.mode.tint
        case .scheduleEnded(_, .early): return Theme.warm
        case .off: return Theme.neutral
        case .scheduleStarted, .scheduleEnded: return .white
        }
    }
}
