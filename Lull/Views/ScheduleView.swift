import BedJetKit
import SwiftUI

/// Plan the night and run it: tonight's schedule on top, the rest of the library below.
struct ScheduleView: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    @State private var editing: Schedule?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Screen(title: "Schedule", subtitle: Date().formatted(.dateTime.weekday(.wide).month().day())) {
            if let run = bedJet.run {
                TimelineView(.periodic(from: .now, by: 30)) { timeline in
                    running(run, now: timeline.date)
                }
            } else if let step = bedJet.runningElsewhereStep {
                runningElsewhere(step: step)
            } else if let schedule = bedJet.tonight {
                // Redrawn as time passes: with a wake time, how long the night runs, and whether it can, depend on when it starts.
                TimelineView(.periodic(from: .now, by: 30)) { timeline in
                    planned(schedule, now: timeline.date)
                }
            } else {
                Card {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("No schedules yet").scaledFont(22, .semibold)
                        Text("Create one to plan how your night feels.").scaledFont(15).foregroundStyle(Theme.secondary)
                    }
                }
            }

            let others = model.schedules.filter { $0.id != featuredID }
            if !others.isEmpty {
                Eyebrow("More schedules").padding(.top, 8).accessibilityAddTraits(.isHeader)
                ForEach(others) { schedule in row(schedule) }
            }
            Button {
                editing = Schedule.blank()
            } label: {
                Label("New Schedule", systemImage: "plus")
            }
            .buttonStyle(PrimaryButtonStyle(prominent: false))
        }
        .fullScreenCover(item: $editing) { ScheduleEditor(schedule: $0) }
        .animation(.smooth(duration: 0.35), value: featuredID)
    }

    /// The schedule shown in the large card: the one running, otherwise the one picked for tonight.
    private var featuredID: Schedule.ID? {
        bedJet.run?.schedule.id ?? bedJet.tonight?.id
    }

    private func planned(_ schedule: Schedule, now date: Date) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Eyebrow("Tonight")
                        Text(schedule.name).scaledFont(26, .semibold)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Edit") { editing = schedule }
                        .scaledFont(15, .semibold)
                        .foregroundStyle(Theme.secondary)
                        // A full-size target without moving the text.
                        .padding(12)
                        .contentShape(Rectangle())
                        .padding(-12)
                        .accessibilityLabel("Edit \(schedule.name)")
                        // VoiceOver lists the last action added first, so this puts Duplicate ahead of Delete.
                        .accessibilityAction(named: "Delete") { model.delete(schedule) }
                        .accessibilityAction(named: "Duplicate") { duplicate(schedule) }
                }
                // Started now: with a wake time, the last phase runs until it from now.
                let now = Format.minuteOfDay(date)
                NightTimeline(schedule: schedule.resolved(startingAt: now), startMinute: now)
                let problem = bedJet.problem(running: schedule, at: date)
                if let problem {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .scaledFont(13)
                        .foregroundStyle(Theme.secondary)
                }
                Button {
                    Task { await bedJet.startTonight() }
                } label: {
                    if bedJet.isBusy { ProgressView().tint(.black) } else { Text("Start Night") }
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityLabel("Start Night")
                .accessibilityValue(bedJet.isBusy ? "Starting" : "")
                .disabled(!bedJet.canControl || bedJet.isBusy || problem != nil)

                Text(startHint)
                    .scaledFont(13)
                    .foregroundStyle(Theme.tertiary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
        }
        .contextMenu { menu(schedule) }
    }

    private var startHint: String {
        switch bedJet.linkState {
        case .ready: return "Saved to your BedJet, which runs it all night. Your phone can sleep too."
        case .yielded: return "Your BedJet is in use on another phone. Starting here takes over."
        default: return "Waiting for your BedJet. Keep your phone within a few feet of it."
        }
    }

    /// Shown instead of the planned card when the BedJet is already running a schedule this phone didn't start.
    private func runningElsewhere(step: Int) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow("Running now")
                    Text("Phase \(step)").scaledFont(26, .semibold).accessibilityAddTraits(.isHeader)
                    if let status = bedJet.status {
                        let minutes = max((status.remainingSeconds + 59) / 60, 1)
                        Label("\(status.mode.activity) · \(Format.duration(minutes)) left in this phase", systemImage: status.mode.symbol)
                            .scaledFont(15, .medium)
                            .foregroundStyle(status.mode.tint)
                            .accessibilityLabel("\(status.mode.activity), \(Format.spokenDuration(minutes)) left in this phase")
                    }
                    Text("This schedule was started from another phone or the BedJet's own remote.")
                        .scaledFont(13)
                        .foregroundStyle(Theme.tertiary)
                }
                Button("End Night") { Task { await bedJet.endNight() } }
                    .buttonStyle(PrimaryButtonStyle(prominent: false))
                    .disabled(bedJet.isBusy)
            }
        }
    }

    private func running(_ run: ActiveRun, now: Date) -> some View {
        let current = run.phase(at: now)
        return Card {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow(current.map { "Phase \($0.index + 1) of \(run.schedule.phases.count)" } ?? "Finishing")
                    Text(run.schedule.name).scaledFont(26, .semibold).accessibilityAddTraits(.isHeader)
                    if let current {
                        Label("\(current.phase.mode.activity) · \(Format.duration(current.minutesLeft)) left", systemImage: current.phase.mode.symbol)
                            .scaledFont(15, .medium)
                            .foregroundStyle(current.phase.mode.tint)
                            .accessibilityLabel("\(current.phase.mode.activity), \(Format.spokenDuration(current.minutesLeft)) left")
                    }
                }
                NightTimeline(schedule: run.schedule, startMinute: Format.minuteOfDay(run.startedAt), progress: run.progress(at: now))
                Button("End Night") { Task { await bedJet.endNight() } }
                    .buttonStyle(PrimaryButtonStyle(prominent: false))
                    .disabled(bedJet.isBusy)
            }
        }
    }

    private func row(_ schedule: Schedule) -> some View {
        // Swapping schedules waits until the one that is running has ended.
        let canSwap = bedJet.run == nil
        // At the largest text sizes Use Tonight goes under the card rather than over its corner.
        let beneath = typeSize.isAccessibilitySize
        return VStack(alignment: .trailing, spacing: 8) {
            Button { editing = schedule } label: { ScheduleRow(schedule: schedule, reservesCorner: canSwap && !beneath) }
                .buttonStyle(.plain)
                .accessibilityLabel("\(schedule.name), \(schedule.spokenSummary)")
                .accessibilityHint("Edits this schedule")
                .accessibilityAction(named: "Delete") { model.delete(schedule) }
                .accessibilityAction(named: "Duplicate") { duplicate(schedule) }
                .overlay(alignment: .topTrailing) {
                    if canSwap && !beneath { useTonight(schedule).padding(4) }
                }
                .contextMenu { menu(schedule) }
            if canSwap && beneath { useTonight(schedule) }
        }
    }

    private func useTonight(_ schedule: Schedule) -> some View {
        Button { bedJet.tonightID = schedule.id } label: {
            Text("Use Tonight")
                .scaledFont(13, .semibold)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .frame(minHeight: 32)
                .background(Theme.raised, in: Capsule())
                .overlay(Capsule().strokeBorder(Theme.hairline))
                .padding(10)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Use \(schedule.name) tonight")
    }

    @ViewBuilder
    private func menu(_ schedule: Schedule) -> some View {
        Button("Edit", systemImage: "slider.horizontal.3") { editing = schedule }
        Button("Duplicate", systemImage: "plus.square.on.square") { duplicate(schedule) }
        Button("Delete", systemImage: "trash", role: .destructive) { model.delete(schedule) }
    }

    private func duplicate(_ schedule: Schedule) {
        var copy = schedule
        copy.id = UUID()
        copy.name = Codec.sanitize("\(schedule.name) copy")
        model.save(copy)
    }
}

private struct ScheduleRow: View {
    let schedule: Schedule
    /// Leaves room for the Use Tonight button laid over the top corner.
    var reservesCorner = true
    @ScaledMetric(relativeTo: .footnote) private var corner: CGFloat = 116

    var body: some View {
        Card(padding: 18) {
            VStack(alignment: .leading, spacing: 14) {
                Text(schedule.name)
                    .scaledFont(19, .semibold)
                    .padding(.trailing, reservesCorner ? corner : 0)
                NightCurve(phases: schedule.phases, lineWidth: 2.5).frame(height: 64)
                Text(schedule.summary)
                    .scaledFont(13, .medium)
                    .foregroundStyle(Theme.secondary)
            }
        }
        .contentShape(RoundedRectangle(cornerRadius: 28))
    }
}
