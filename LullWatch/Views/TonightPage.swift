import BedJetKit
import SwiftUI

/// Tonight's schedule and Start Night, or where the night is up to once it's running, as the Tonight widget shows.
struct TonightPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        // Played out by the clock between handovers from the iPhone, as the widgets are.
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = context.date
            let shown = model.shown
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if model.snapshot.bedJets.isEmpty {
                        EmptyMessage(symbol: "bed.double", title: "No BedJet yet", message: "Choose your BedJet in Lull on your iPhone.")
                    } else if let run = shown.run(at: now), let bedJet = model.bedJet {
                        running(run, on: bedJet, now: now)
                    } else {
                        if let bedJet = model.bedJet, case let .air(mode, temperature, fan, _)? = bedJet.live(at: now), let live = bedJet.live {
                            air(mode: mode, temperature: temperature, fan: fan, live: live)
                        }
                        if let schedule = shown.tonight, let bedJet = model.bedJet {
                            planned(schedule, on: bedJet)
                        } else if model.bedJet != nil {
                            EmptyMessage(symbol: "moon.zzz.fill", title: "No schedule yet", message: "Open Lull to plan how your night feels.")
                        }
                    }
                    if let unavailable = model.unavailable { Footnote(text: unavailable) }
                }
            }
        }
        .navigationTitle("Tonight")
    }

    @ViewBuilder
    private func running(_ run: ActiveRun, on bedJet: WidgetBedJet, now: Date) -> some View {
        let current = run.phase(at: now)
        let mode = current?.phase.mode ?? .standby
        let minutesLeft = current?.minutesLeft ?? 0
        VStack(alignment: .leading, spacing: 3) {
            Eyebrow(current.map { "Phase \($0.index + 1) of \(run.schedule.phases.count)" } ?? "Finishing")
            Text(run.schedule.name).scaledFont(17, .semibold).lineLimit(2)
            // Side by side, so End Night fits on the smallest watches without scrolling.
            HStack {
                ModeLabel(mode: mode)
                Spacer(minLength: 4)
                Text("\(Format.shortDuration(minutesLeft)) left").scaledFont(13, .semibold).monospacedDigit()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([
            run.schedule.name,
            current.map { "phase \($0.index + 1) of \(run.schedule.phases.count)" },
            current.map { "\($0.phase.mode.activity), \(Format.spokenDuration($0.minutesLeft)) left" },
            "ends at \(run.endsAt.formatted(date: .omitted, time: .shortened))",
        ].compactMap { $0 }.joined(separator: ", "))
        VStack(alignment: .leading, spacing: 2) {
            NightCurve(phases: run.schedule.phases, progress: run.progress(at: now), lineWidth: 2.5)
                .frame(height: 34)
                .padding(.horizontal, -8)
            Text(LiveStatus.night(run).ending).scaledFont(12).foregroundStyle(Theme.secondary).monospacedDigit()
        }
        .accessibilityHidden(true)
        ErrandButton(title: "End Night", busyTitle: "Ending…", prominent: false, command: .endNight(bedJet.id))
    }

    @ViewBuilder
    private func planned(_ schedule: Schedule, on bedJet: WidgetBedJet) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(schedule.name).scaledFont(17, .semibold).lineLimit(2)
            Text(schedule.summary).scaledFont(13).foregroundStyle(Theme.secondary).monospacedDigit()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Tonight, \(schedule.name), \(schedule.spokenSummary)")
        NightCurve(phases: schedule.phases, lineWidth: 2.5)
            .frame(height: 34)
            .padding(.horizontal, -8)
        ErrandButton(title: "Start Night", busyTitle: "Starting…", command: .startNight(bedJet.id))
    }

    /// Air from the Remote, running until its timer runs out.
    private func air(mode: Mode, temperature: Int?, fan: Int, live: LiveStatus) -> some View {
        let setting = Format.setting(mode: mode, temperature: temperature, fanPercent: fan, fahrenheit: model.snapshot.useFahrenheit)
        return VStack(alignment: .leading, spacing: 2) {
            ModeLabel(mode: mode)
            Text(setting).scaledFont(13, .medium).monospacedDigit()
            Text(live.ending).scaledFont(12).foregroundStyle(Theme.secondary).monospacedDigit()
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(mode.activity), \(setting), \(live.ending)")
    }
}
