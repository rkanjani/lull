import ActivityKit
import BedJetKit
import SwiftUI
import WidgetKit

/// The running BedJets in the Dynamic Island and on the Lock Screen: one, or one on each side of the bed. Lull can't
/// update it while it's closed, so everything here either counts by itself or is hidden once it may be out of date.
struct BedJetLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: BedJetActivityAttributes.self) { context in
            let lives = Live.all(context)
            LiveLockScreen(lives: lives)
                .activityBackgroundTint(Theme.background)
                .activitySystemActionForegroundColor(.white)
                .widgetURL(lives.first?.link)
                .environment(\.colorScheme, .dark)
        } dynamicIsland: { context in
            let lives = Live.all(context)
            let isPair = lives.count > 1
            return DynamicIsland {
                // Inset from the island's rounded top corners, which otherwise clip the first letters.
                DynamicIslandExpandedRegion(.leading) {
                    if !isPair, let live = lives.first { LiveTitle(live: live).padding(.leading, 10).padding(.top, 8) }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if !isPair, let live = lives.first { LiveCountdown(live: live, size: 20).padding(.trailing, 10).padding(.top, 8) }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if isPair {
                        LivePair(lives: lives, countdownSize: 16, curveHeight: 14, isCompact: true).padding(.horizontal, 12).padding(.top, 4)
                    } else if let live = lives.first {
                        LiveDetails(live: live, isCompact: true).padding(.horizontal, 12)
                    }
                }
            } compactLeading: {
                if isPair { LivePairSymbol(lives: lives) } else if let live = lives.first { LiveSymbol(live: live) }
            } compactTrailing: {
                if isPair { LivePairCountdowns(lives: lives) } else if let live = lives.first { LiveCountdown(live: live, size: 14) }
            } minimal: {
                if isPair { LivePairSymbol(lives: lives) } else if let live = lives.first { LiveSymbol(live: live) }
            }
            .widgetURL(lives.first?.link)
            .keylineTint(lives.first?.tint)
        }
    }
}

/// What one BedJet in the Live Activity says, worked out once for every place it's drawn.
struct Live: Identifiable {
    let side: BedJetActivityAttributes.Side
    let useFahrenheit: Bool
    let link: URL
    /// The mode the BedJet is in, unless a night has moved on to a phase Lull hasn't seen.
    let mode: Mode?
    /// The phase the night is in, nil for air from the Remote and once it may be out of date.
    let current: (phase: Phase, index: Int, minutesLeft: Int)?
    let now: Date

    /// Each BedJet in a Live Activity, in the order Lull lists them.
    static func all(_ context: ActivityViewContext<BedJetActivityAttributes>, at now: Date = Date()) -> [Live] {
        context.state.sides.map {
            Live(side: $0, useFahrenheit: context.state.useFahrenheit, isStale: context.isStale, at: now)
        }
    }

    init(side: BedJetActivityAttributes.Side, useFahrenheit: Bool, isStale: Bool, at now: Date = Date()) {
        self.side = side
        self.useFahrenheit = useFahrenheit
        self.now = now
        switch side.status {
        case let .night(run):
            current = isStale ? nil : run.phase(at: now)
            mode = current?.phase.mode
            link = LullTab.schedule.url(showing: side.id)
        case let .air(mode, _, _, _):
            current = nil
            self.mode = mode
            link = LullTab.remote.url(showing: side.id)
        }
    }

    var id: UUID { side.id }
    var symbol: String { mode?.symbol ?? "moon.fill" }
    var tint: Color { mode?.tint ?? .white }

    var run: ActiveRun? {
        if case let .night(run) = side.status { return run }
        return nil
    }

    /// The schedule's name through a night, and what the air is doing from the Remote: "Cooling".
    var title: String { run?.schedule.name ?? mode?.activity ?? "" }

    /// What the BedJet is set to right now: "Fan 30%", "68° · Fan 40%".
    var detail: String? {
        switch side.status {
        case .night: current.map { $0.phase.setting(on: side.generation, fahrenheit: useFahrenheit) }
        case let .air(mode, temperature, fan, _):
            Format.setting(mode: mode, temperature: temperature, fanPercent: fan, fahrenheit: useFahrenheit)
        }
    }

    var ending: String { side.status.ending }

    /// Counted down by the system, so it stays right while Lull is closed.
    var countdown: ClosedRange<Date> {
        let end = side.status.endsAt
        return min(run?.startedAt ?? now, end)...end
    }

    /// Read by VoiceOver for the title: "My side, Cool Drift".
    var spokenTitle: String { "\(side.name), \(title)" }

    /// Read by VoiceOver for the details under it: "phase 2 of 4, Cooling, Fan 30%, Ends 1:18 AM".
    var spokenDetails: String {
        var parts: [String] = []
        if let run, let current {
            parts.append("phase \(current.index + 1) of \(run.schedule.phases.count)")
            parts.append(current.phase.mode.activity)
        }
        if let detail { parts.append(detail) }
        parts.append(ending)
        return parts.joined(separator: ", ")
    }
}

/// The BedJet's name above the schedule or what the air is doing.
struct LiveTitle: View {
    let live: Live

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            WidgetEyebrow(live.side.name)
            Text(live.title)
                .font(.system(size: 16, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(live.spokenTitle)
    }
}

/// Time left: to the end of the night, or until the Remote's timer turns the air off.
struct LiveCountdown: View {
    let live: Live
    let size: CGFloat

    var body: some View {
        Countdown(start: live.countdown.lowerBound, end: live.countdown.upperBound, size: size, tint: live.tint, now: live.now)
    }
}

struct LiveSymbol: View {
    let live: Live
    var size: CGFloat = 14

    var body: some View {
        Image(systemName: live.symbol)
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(live.tint)
            .accessibilityLabel(live.mode?.activity ?? live.title)
    }
}

/// The current setting, then the night's shape and progress, or when the air turns off.
struct LiveDetails: View {
    let live: Live
    /// The expanded Dynamic Island has less height than the Lock Screen, so the night's curve is drawn shorter there.
    var isCompact = false

    var body: some View {
        VStack(alignment: .leading, spacing: isCompact ? 5 : 8) {
            if let mode = live.mode {
                HStack(spacing: 8) {
                    // Air from the Remote is already titled by what it's doing.
                    if live.run != nil { ModeLabel(mode: mode) }
                    if let detail = live.detail {
                        Text(detail)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Theme.secondary)
                            .lineLimit(1)
                    }
                }
            }
            if let run = live.run {
                LiveNightCurve(run: run, height: isCompact ? 24 : 34)
                TimeRow(start: run.startedAt, end: run.endsAt)
            } else {
                Text(live.ending)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.secondary)
                    .monospacedDigit()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(live.spokenDetails)
    }
}

/// Two BedJets in one icon: each side's mode, left and right as on the bed, split by a line. The Android app's
/// Live Update draws the same for its status bar.
struct LivePairSymbol: View {
    let lives: [Live]

    var body: some View {
        HStack(spacing: 2) {
            LiveSymbol(live: lives[0], size: 12)
            Capsule().fill(.white.opacity(0.35)).frame(width: 1, height: 13)
            LiveSymbol(live: lives[1], size: 12)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(lives.map(\.spokenTitle).joined(separator: "; "))
    }
}

/// Each BedJet's time left, one above the other in the order of the icon beside them, each in its mode's colour.
struct LivePairCountdowns: View {
    let lives: [Live]

    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            ForEach(lives) { LiveCountdown(live: $0, size: 11) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(lives.map(\.ending).joined(separator: "; "))
    }
}

/// Two BedJets, one row each: what each is doing and its time left, then a night's curve or when the air turns off.
/// Each row opens Lull on its own BedJet.
struct LivePair: View {
    let lives: [Live]
    let countdownSize: CGFloat
    let curveHeight: CGFloat
    /// The expanded Dynamic Island is short, so each row there puts the curve or setting beside the title.
    var isCompact = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(lives.enumerated()), id: \.element.id) { index, live in
                if index > 0 {
                    Rectangle().fill(Theme.hairline).frame(height: 1).accessibilityHidden(true)
                }
                Link(destination: live.link) { row(live) }
            }
        }
    }

    @ViewBuilder
    private func row(_ live: Live) -> some View {
        if isCompact {
            VStack(alignment: .leading, spacing: 2) {
                WidgetEyebrow(live.side.name)
                HStack(spacing: 8) {
                    Text(live.title)
                        .font(.system(size: 15, weight: .semibold))
                        .lineLimit(1)
                        .layoutPriority(1)
                    // The countdown beside it says when the air turns off.
                    summary(live, ending: false)
                    LiveCountdown(live: live, size: countdownSize)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(live.spokenTitle), \(live.spokenDetails)")
        } else {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top) {
                    LiveTitle(live: live)
                    Spacer(minLength: 8)
                    LiveCountdown(live: live, size: countdownSize)
                }
                HStack(spacing: 8) {
                    if live.run != nil, let mode = live.mode { ModeLabel(mode: mode) }
                    summary(live)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(live.spokenDetails)
            }
        }
    }

    /// A night's curve, lit up to now, or the air's setting and when it turns off.
    @ViewBuilder
    private func summary(_ live: Live, ending: Bool = true) -> some View {
        if let run = live.run {
            LiveNightCurve(run: run, height: curveHeight, lineWidth: 1.5)
        } else {
            Text([live.detail, ending ? live.ending : nil].compactMap { $0 }.joined(separator: " · "))
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Theme.secondary)
                .monospacedDigit()
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct LiveLockScreen: View {
    let lives: [Live]
    /// Set in StandBy, where the Live Activity fills the screen of a phone on its charger.
    @Environment(\.isActivityFullscreen) private var isFullscreen

    var body: some View {
        if isFullscreen {
            HStack(spacing: 0) {
                ForEach(Array(lives.enumerated()), id: \.element.id) { index, live in
                    if index > 0 {
                        Rectangle().fill(Theme.hairline).frame(width: 1).padding(.vertical, 20).accessibilityHidden(true)
                    }
                    LiveStandBy(live: live, isPair: lives.count > 1)
                }
            }
        } else if lives.count > 1 {
            LivePair(lives: lives, countdownSize: 20, curveHeight: 16).padding(14)
        } else if let live = lives.first {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    LiveTitle(live: live)
                    Spacer(minLength: 8)
                    LiveCountdown(live: live, size: 22)
                }
                LiveDetails(live: live)
            }
            .padding(16)
        }
    }
}

/// The Live Activity in StandBy, full screen on the nightstand, to be read from across the room: the time left large,
/// with the night's curve or the air's setting under it. Two BedJets share the screen, one on each half.
struct LiveStandBy: View {
    let live: Live
    var isPair = false
    @Environment(\.isLuminanceReduced) private var isDimmed

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    WidgetEyebrow(live.side.name)
                    Text(live.title)
                        .font(.system(size: isPair ? 20 : 26, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: 12)
                if let mode = live.mode, !isPair {
                    ModeLabel(mode: mode, size: 15)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(live.spokenTitle)
            Spacer(minLength: 0)
            Countdown(start: live.countdown.lowerBound, end: live.countdown.upperBound, size: isPair ? 48 : 76, tint: live.tint, now: live.now)
            Spacer(minLength: 0)
            Group {
                if let run = live.run {
                    LiveNightCurve(run: run, height: isPair ? 32 : 44)
                        .opacity(isDimmed ? 0.5 : 1)
                    TimeRow(start: run.startedAt, end: run.endsAt)
                } else {
                    Text([live.detail, live.ending].compactMap { $0 }.joined(separator: " · "))
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Theme.secondary)
                        .monospacedDigit()
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(live.spokenDetails)
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private extension BedJetActivityAttributes.Side {
    static let night = Self(id: UUID(), status: .night(WidgetSnapshot.preview().run!), name: "My side", generation: .v3)
    static let air = Self(id: UUID(), status: .air(mode: .heat, temperature: 66, fanPercent: 40,
                                                   endsAt: Date().addingTimeInterval(2700)),
                          name: "Other side", generation: .v3)
}

#Preview("One and two", as: .content, using: BedJetActivityAttributes()) {
    BedJetLiveActivity()
} contentStates: {
    BedJetActivityAttributes.ContentState(sides: [.night], useFahrenheit: true)
    BedJetActivityAttributes.ContentState(sides: [.air], useFahrenheit: true)
    BedJetActivityAttributes.ContentState(sides: [.night, .air], useFahrenheit: true)
}

#Preview("Island, two", as: .dynamicIsland(.compact), using: BedJetActivityAttributes()) {
    BedJetLiveActivity()
} contentStates: {
    BedJetActivityAttributes.ContentState(sides: [.night, .air], useFahrenheit: true)
}

// In StandBy the Live Activity fills the screen; whether it does can't be set from a preview, so this draws it directly.
#Preview("StandBy, two") {
    let lives = [BedJetActivityAttributes.Side.night, .air].map { Live(side: $0, useFahrenheit: true, isStale: false) }
    return HStack(spacing: 0) {
        LiveStandBy(live: lives[0], isPair: true)
        LiveStandBy(live: lives[1], isPair: true)
    }
    .frame(width: 852, height: 393)
    .background(.black)
    .environment(\.colorScheme, .dark)
}
