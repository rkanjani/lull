import AppIntents
import BedJetKit
import SwiftUI
import WidgetKit

/// What one BedJet is doing: the Tonight widget in StandBy and while the Remote runs, and each side of Both Sides.
struct BedJetSummary: View {
    let bedJet: WidgetBedJet
    let snapshot: WidgetSnapshot
    let now: Date
    /// In StandBy: no card behind it, and read from across the room.
    var isStandBy = false
    var isMedium = false
    @Environment(\.widgetRenderingMode) private var rendering
    @Environment(\.isLuminanceReduced) private var isDimmed

    /// The BedJet's name heads it once there's more than one to tell apart.
    private var name: String? { snapshot.hasSeveralBedJets ? bedJet.name : nil }
    private var countdownSize: CGFloat { isStandBy ? 34 : 26 }

    var body: some View {
        switch bedJet.live(at: now) {
        case let .night(run)?: night(run)
        case let .air(mode, temperature, fan, endsAt)?: air(mode, temperature: temperature, fan: fan, endsAt: endsAt)
        case nil: idle
        }
    }

    private func night(_ run: ActiveRun) -> some View {
        let current = run.phase(at: now)
        let mode = current?.phase.mode ?? .standby
        let spoken = [name, run.schedule.name,
                      current.map { "phase \($0.index + 1) of \(run.schedule.phases.count)" },
                      current.map { $0.phase.mode.activity }, LiveStatus.night(run).ending]
        return VStack(alignment: .leading, spacing: 0) {
            heading(mode: mode)
            Text(run.schedule.name)
                .font(.system(size: isStandBy ? 17 : 15, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.top, 2)
            if isMedium, let current {
                Text(current.phase.setting(on: bedJet.generation, fahrenheit: snapshot.useFahrenheit))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.secondary)
            }
            Spacer(minLength: 4)
            Countdown(start: run.startedAt, end: run.endsAt, size: countdownSize, tint: mode.widgetTint(rendering), now: now)
            // Lit as far as the night has got, and moved along by the system.
            LiveNightCurve(run: run, height: isMedium ? 26 : isStandBy ? 40 : 34)
                .opacity(isDimmed ? 0.5 : 1)
                .padding(.horizontal, -8)
            if isMedium {
                TimeRow(start: run.startedAt, end: run.endsAt)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(spoken.compactMap { $0 }.joined(separator: ", "))
    }

    private func air(_ mode: Mode, temperature: Int?, fan: Int, endsAt: Date) -> some View {
        let live = LiveStatus.air(mode: mode, temperature: temperature, fanPercent: fan, endsAt: endsAt)
        let setting = Format.setting(mode: mode, temperature: temperature, fanPercent: fan, fahrenheit: snapshot.useFahrenheit)
        return VStack(alignment: .leading, spacing: 0) {
            heading(mode: mode)
            Text(setting)
                .font(.system(size: isStandBy ? 17 : 15, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.top, 2)
            Spacer(minLength: 4)
            Countdown(start: now, end: endsAt, size: countdownSize, tint: mode.widgetTint(rendering), now: now)
            Text(live.ending)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Theme.secondary)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel([name, mode.activity, setting, live.ending].compactMap { $0 }.joined(separator: ", "))
    }

    /// Nothing running from this phone. It doesn't say "Off": with Lull closed it can't see the BedJet, which another
    /// phone or the BedJet's own remote may have turned on.
    @ViewBuilder
    private var idle: some View {
        if let schedule = bedJet.tonight {
            VStack(alignment: .leading, spacing: 0) {
                WidgetEyebrow(name.map { "\($0) · Tonight" } ?? "Tonight")
                Text(schedule.name)
                    .font(.system(size: isStandBy ? 19 : 17, weight: .semibold))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .padding(.top, 4)
                Spacer(minLength: 6)
                if isMedium {
                    NightCurve(phases: schedule.phases, lineWidth: 2)
                        .frame(height: 30)
                        .padding(.horizontal, -8)
                        .accessibilityHidden(true)
                }
                HStack(alignment: .center) {
                    Text(schedule.length)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Theme.secondary)
                        .monospacedDigit()
                    Spacer(minLength: 4)
                    StartButton(bedJet: bedJet)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .accessibilityElement(children: .contain)
            .accessibilityLabel([name, "Tonight", schedule.name, schedule.spokenSummary].compactMap { $0 }.joined(separator: ", "))
        } else {
            BedJetUnknown(title: "No schedule yet", message: "Open Lull to plan how your night feels.")
        }
    }

    private func heading(mode: Mode) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            if let name { WidgetEyebrow(name) }
            ModeLabel(mode: mode, size: isStandBy ? 13 : 11, tint: mode.widgetTint(rendering))
        }
    }
}

/// Starts tonight's schedule on this BedJet, which opens Lull to reach it.
struct StartButton: View {
    let bedJet: WidgetBedJet

    var body: some View {
        Button(intent: StartNightIntent(bedJet: BedJetEntity(id: bedJet.id, name: bedJet.name))) {
            Text("Start")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.black)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(.white, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Start Night")
    }
}

/// No BedJet to show yet: Lull hasn't been opened since one was chosen, or this one was forgotten.
struct BedJetUnknown: View {
    var title = "No BedJet yet"
    var message = "Open Lull to choose your BedJet."

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: "moon.zzz.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Theme.secondary)
            Spacer(minLength: 0)
            Text(title).font(.system(size: 17, weight: .semibold))
            Text(message)
                .font(.system(size: 12))
                .foregroundStyle(Theme.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
    }
}

// StandBy can't be entered in the simulator, and whether a widget is in it can't be set from a preview, so these
// draw the StandBy layout directly, by day and at night when StandBy turns everything red.
#Preview("StandBy") {
    let snapshot = WidgetSnapshot.preview()
    return BedJetSummary(bedJet: snapshot.bedJets[0], snapshot: snapshot, now: .now, isStandBy: true)
        .padding()
        .frame(width: 170, height: 170)
        .background(.black)
        .environment(\.colorScheme, .dark)
}

#Preview("StandBy at night") {
    let snapshot = WidgetSnapshot.preview()
    return BedJetSummary(bedJet: snapshot.bedJets[1], snapshot: snapshot, now: .now, isStandBy: true)
        .padding()
        .frame(width: 170, height: 170)
        .background(.black)
        .environment(\.colorScheme, .dark)
        .environment(\.widgetRenderingMode, .vibrant)
}
