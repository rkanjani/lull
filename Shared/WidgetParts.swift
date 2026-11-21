import BedJetKit
import SwiftUI
import WidgetKit

/// The app's near-black, lifted slightly at the top so the widget reads as a card on the Home Screen.
struct WidgetBackground: View {
    var body: some View {
        LinearGradient(colors: [Theme.raised, Theme.background], startPoint: .top, endPoint: .bottom)
    }
}

/// The small all-caps label above a widget's title, like `Eyebrow` in the app but at a fixed size.
struct WidgetEyebrow: View {
    let text: String
    var color = Theme.secondary

    init(_ text: String, color: Color = Theme.secondary) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .semibold))
            .tracking(1.4)
            .foregroundStyle(color)
            .lineLimit(1)
    }
}

/// What the BedJet is doing, in the mode's colour: "❄︎ Cooling".
struct ModeLabel: View {
    let mode: Mode
    var size: CGFloat = 11
    /// The mode's own colour unless given another, such as plain white for StandBy at night.
    var tint: Color?

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: mode.symbol)
            Text(mode.activity.uppercased()).tracking(1.2)
        }
        .font(.system(size: size, weight: .semibold))
        .foregroundStyle(tint ?? mode.tint)
        .lineLimit(1)
        .widgetAccentable()
    }
}

/// Start, length and end of a night along one line, as under the curve in the app.
struct TimeRow: View {
    let start: Date
    let end: Date

    var body: some View {
        HStack {
            label(start, "moon.fill")
            Spacer(minLength: 4)
            Text(Format.duration(Int(end.timeIntervalSince(start) / 60)))
            Spacer(minLength: 4)
            label(end, "sun.max.fill")
        }
        .font(.system(size: 11, weight: .medium))
        .foregroundStyle(Theme.secondary)
        .monospacedDigit()
        .lineLimit(1)
    }

    private func label(_ date: Date, _ symbol: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol).font(.system(size: 9))
            Text(date.formatted(date: .omitted, time: .shortened))
        }
    }
}

/// One phase of a schedule in a list: its mode, settings, length and when it starts.
struct PhaseLine: View {
    let phase: Phase
    let detail: String
    let startsAt: Date
    var isCurrent = false
    var isDone = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: phase.mode.symbol)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(phase.mode.tint)
                .frame(width: 26, height: 26)
                .background(phase.mode.tint.opacity(0.16), in: Circle())
                .widgetAccentable()
            VStack(alignment: .leading, spacing: 1) {
                Text(phase.mode.title).font(.system(size: 13, weight: .semibold))
                Text(detail).font(.system(size: 11)).foregroundStyle(Theme.secondary)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 1) {
                Text(Format.duration(phase.minutes)).font(.system(size: 12, weight: .medium))
                Text(startsAt.formatted(date: .omitted, time: .shortened)).font(.system(size: 11)).foregroundStyle(Theme.secondary)
            }
            .monospacedDigit()
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(isCurrent ? Theme.hairline : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .opacity(isDone ? 0.45 : 1)
    }
}

extension Schedule {
    /// How long the night runs, "7 hr 45 min", or "Until 6:30 AM" with a wake time, since then that depends on when it starts.
    var length: String { wakeTime.map { "Until \(Format.clock($0))" } ?? Format.duration(totalMinutes) }

    var spokenLength: String { wakeTime.map { "until \(Format.clock($0))" } ?? Format.spokenDuration(totalMinutes) }

    /// Short enough for the Lock Screen: "7h 45m", or the wake time.
    var shortLength: String { wakeTime.map(Format.clock) ?? Format.shortDuration(totalMinutes) }

    /// "7 hr 45 min · 4 phases", or "Until 6:30 AM · 4 phases"
    var summary: String { "\(length) · \(phases.count) \(phases.count == 1 ? "phase" : "phases")" }

    var spokenSummary: String { "\(spokenLength), \(phases.count) \(phases.count == 1 ? "phase" : "phases")" }
}

extension Format {
    /// A duration short enough for the Lock Screen: "2h 25m", "45m", "7h".
    static func shortDuration(_ minutes: Int) -> String {
        let hours = minutes / 60, rest = minutes % 60
        if hours == 0 { return "\(rest)m" }
        return rest == 0 ? "\(hours)h" : "\(hours)h \(rest)m"
    }
}

// MARK: Running

/// Time left, counted down by the system so it stays right while Lull is closed. A timer takes all the width it's
/// given, which crowds the compact Dynamic Island and small widgets, so it is laid over a hidden template of the widest
/// it will read from now on ("00:00:00" with ten hours or more left). Its seconds stay: the system's live formats
/// either show them or spell out "7 hours, 44 minutes".
struct Countdown: View {
    let start: Date
    let end: Date
    let size: CGFloat
    var tint: Color = .white
    /// When this is drawn, to size the template.
    var now = Date()

    var body: some View {
        Text(Self.template(secondsLeft: end.timeIntervalSince(now)))
            .hidden()
            .overlay(alignment: .trailing) {
                Text(timerInterval: min(start, end)...end, countsDown: true)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(tint)
            }
            .font(.system(size: size, weight: .semibold))
            .monospacedDigit()
    }

    static func template(secondsLeft: TimeInterval) -> String {
        switch secondsLeft {
        case 36000...: "00:00:00"
        case 3600...: "0:00:00"
        default: "00:00"
        }
    }
}

/// The night's curve, lit as far as the night has got, with a dot where it is now, as the widgets have. Lull can't redraw
/// it while it's closed, but the system moves a timer's progress bar along by itself, so the lit copy is masked by one
/// stretched to the curve's height, and the dot is one of a row along the curve that timers show one at a time.
struct LiveNightCurve: View {
    let run: ActiveRun
    /// Nil fills the height it's given.
    var height: CGFloat?
    var lineWidth: CGFloat = 2

    var body: some View {
        NightCurve(phases: run.schedule.phases, lineWidth: lineWidth)
            .opacity(0.3)
            .overlay {
                NightCurve(phases: run.schedule.phases, lineWidth: lineWidth)
                    .mask {
                        ProgressView(timerInterval: run.startedAt...run.endsAt, countsDown: false) {
                            EmptyView()
                        } currentValueLabel: {
                            EmptyView()
                        }
                        .tint(.white)
                        // The bar is a few points thick; stretched well past any curve's height, it covers it.
                        .scaleEffect(x: 1, y: 200, anchor: .center)
                        // NightCurve insets its line this much at each end.
                        .padding(.horizontal, 8)
                    }
            }
            .overlay { NowDot(run: run, lineWidth: lineWidth) }
            .frame(height: height)
            .frame(maxHeight: height == nil ? .infinity : nil)
    }
}

/// The dot on a LiveNightCurve at where the night is now. A dot can't be moved without redrawing, so there is one for each
/// short stretch of the night, each switched on as its stretch begins and off as it ends by two timer progress bars.
private struct NowDot: View {
    let run: ActiveRun
    let lineWidth: CGFloat

    var body: some View {
        GeometryReader { bounds in
            let phases = run.schedule.phases
            let total = Double(max(run.schedule.totalMinutes, 1))
            // Laid out as NightCurve lays out its line: inset 8 at each end, levels around the middle.
            let width = max(bounds.size.width - 16, 1)
            let mid = bounds.size.height / 2
            let amplitude = bounds.size.height * 0.42
            let size = min(max(lineWidth * 4, 8), 12)
            // A step every few points: as often as the widgets move theirs, every few minutes through the night.
            let steps = max(Int(width / 5), 1)
            let length = run.endsAt.timeIntervalSince(run.startedAt) / Double(steps)
            ForEach(0..<steps, id: \.self) { step in
                let fraction = (Double(step) + 0.5) / Double(steps)
                let from = run.startedAt.addingTimeInterval(length * Double(step))
                Circle()
                    .fill(.white)
                    .shadow(color: .white.opacity(0.7), radius: size / 3)
                    .frame(width: size, height: size)
                    .mask { Switch(at: from, on: true).frame(width: size * 2, height: size * 2) }
                    .mask { Switch(at: from.addingTimeInterval(length), on: false).frame(width: size * 2, height: size * 2) }
                    .position(x: 8 + width * fraction,
                              y: mid - NightCurve.level(at: fraction * total, in: phases) * amplitude)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Fills its frame from a moment on (`on`), or until it (`on: false`), by the system's clock: a timer's progress bar that
/// fills, or empties, in a second, stretched to cover the frame. As a mask, the bar's faint track would still let some
/// through, so it is drawn on black and only what's bright, the filled part, counts.
private struct Switch: View {
    let at: Date
    let on: Bool

    var body: some View {
        ZStack {
            Color.black
            ProgressView(timerInterval: at...at.addingTimeInterval(1), countsDown: !on) {
                EmptyView()
            } currentValueLabel: {
                EmptyView()
            }
            .tint(.white)
            .scaleEffect(x: 1.5, y: 40, anchor: .center)
        }
        .compositingGroup()
        .contrast(4)
        .luminanceToAlpha()
    }
}

extension Mode {
    /// The mode's colour, except where iOS draws widgets in one colour (StandBy at night, the Lock Screen), where a
    /// colour would only make the text fainter.
    func widgetTint(_ rendering: WidgetRenderingMode) -> Color {
        rendering == .fullColor ? tint : .primary
    }
}

extension LiveStatus {
    /// "Ends 6:30 AM" through a night, "Turns off at 1:30 AM" for air from the Remote.
    var ending: String {
        let time = endsAt.formatted(date: .omitted, time: .shortened)
        if case .night = self { return "Ends \(time)" }
        return "Turns off at \(time)"
    }

    /// Where a tap takes Lull: the Schedule for a night, the Remote for air.
    var tab: LullTab {
        if case .night = self { return .schedule }
        return .remote
    }
}

