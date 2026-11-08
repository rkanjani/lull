import AppIntents
import BedJetKit
import SwiftUI
import WidgetKit

struct NightEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

/// Reads what the app last saved and lays out the night ahead of time: the BedJet keeps to its schedule by the
/// clock, so every phase change can be drawn without waking the app. Draws the BedJet the widget is set to.
struct NightProvider: AppIntentTimelineProvider {
    var preview = WidgetSnapshot.preview()

    func placeholder(in context: Context) -> NightEntry {
        NightEntry(date: Date(), snapshot: preview)
    }

    func snapshot(for configuration: BedJetConfiguration, in context: Context) async -> NightEntry {
        // The gallery shows the sample BedJets, which a real one picked for the widget isn't among.
        if context.isPreview { return NightEntry(date: Date(), snapshot: preview) }
        let snapshot = WidgetSnapshot.load() ?? WidgetSnapshot()
        return NightEntry(date: Date(), snapshot: snapshot.showing(configuration.bedJet?.id))
    }

    func timeline(for configuration: BedJetConfiguration, in context: Context) async -> Timeline<NightEntry> {
        let snapshot = (WidgetSnapshot.load() ?? WidgetSnapshot()).showing(configuration.bedJet?.id)
        let now = Date()
        let entries = snapshot.redraws(from: now).map { NightEntry(date: $0, snapshot: snapshot) }
        // The app redraws the widgets whenever what they show changes. Last night also moves on at midnight.
        let midnight = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: now)) ?? now
        return Timeline(entries: entries, policy: .after(max(midnight, entries.last?.date ?? now)))
    }
}

struct TonightWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "Tonight", intent: BedJetConfiguration.self, provider: NightProvider()) { entry in
            TonightView(entry: entry)
        }
        .configurationDisplayName("Tonight")
        .description("Tonight's schedule, where the night is up to once it's running, and the Remote's timer. Made for StandBy too.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct TonightView: View {
    let entry: NightEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.showsWidgetContainerBackground) private var showsBackground

    private var snapshot: WidgetSnapshot { entry.snapshot }
    private var now: Date { entry.date }
    /// The BedJet the widget is set to, and what it's doing.
    private var bedJet: WidgetBedJet? { snapshot.bedJet(nil) }
    private var live: LiveStatus? { bedJet?.live(at: now) }

    var body: some View {
        Group {
            if !family.isAccessory, !showsBackground, let bedJet {
                // StandBy: no card behind it, and read from across the room.
                BedJetSummary(bedJet: bedJet, snapshot: snapshot, now: now, isStandBy: true)
            } else if let run = snapshot.run(at: now) {
                running(run)
            } else if !family.isAccessory, case .air? = live, let bedJet {
                // Air from the Remote, with its timer. The Lock Screen keeps to the night.
                BedJetSummary(bedJet: bedJet, snapshot: snapshot, now: now, isMedium: family != .systemSmall)
            } else if let schedule = snapshot.tonight {
                planned(schedule)
            } else {
                empty
            }
        }
        .widgetURL(snapshot.link(to: family.isAccessory ? .schedule : live?.tab ?? .schedule))
        // Lull is always dark, whatever the Home Screen's appearance; Lock Screen widgets take the system's.
        .environment(\.colorScheme, family.isAccessory ? colorScheme : .dark)
        .containerBackground(for: .widget) {
            if family.isAccessory { Color.clear } else { WidgetBackground() }
        }
    }

    // MARK: Running

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
        case .accessoryCircular:
            Gauge(value: progress) {
                Image(systemName: "moon.fill")
            } currentValueLabel: {
                Image(systemName: mode.symbol).font(.system(size: 16, weight: .semibold))
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .accessibilityLabel(spoken)
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
            Label("\(mode.activity) · ends \(wake)", systemImage: mode.symbol)
        case .systemSmall:
            VStack(alignment: .leading, spacing: 0) {
                if let name = snapshot.headingName {
                    WidgetEyebrow(name).padding(.bottom, 3)
                }
                ModeLabel(mode: mode)
                Text(run.schedule.name)
                    .font(.system(size: 17, weight: .semibold))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .padding(.top, 4)
                Spacer(minLength: 6)
                NightCurve(phases: run.schedule.phases, progress: progress, lineWidth: 2.5)
                    .frame(height: 40)
                    .padding(.horizontal, -8)
                Spacer(minLength: 6)
                Text("\(Format.duration(minutesLeft)) left")
                    .font(.system(size: 13, weight: .semibold))
                    .monospacedDigit()
                Text("Ends \(wake)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.secondary)
                    .monospacedDigit()
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
        default:
            VStack(alignment: .leading, spacing: 10) {
                header(eyebrow: snapshot.headed(current.map { "Phase \($0.index + 1) of \(run.schedule.phases.count)" } ?? "Finishing"),
                       title: run.schedule.name) {
                    VStack(alignment: .trailing, spacing: 3) {
                        ModeLabel(mode: mode)
                        Text("\(Format.duration(minutesLeft)) left")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Theme.secondary)
                            .monospacedDigit()
                    }
                }
                NightCurve(phases: run.schedule.phases, progress: progress)
                    .frame(maxHeight: family == .systemLarge ? 90 : .infinity)
                    .padding(.horizontal, -8)
                TimeRow(start: run.startedAt, end: run.endsAt)
                if family == .systemLarge {
                    phaseList(run.schedule, startedAt: run.startedAt, current: current?.index)
                    Spacer(minLength: 0)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
        }
    }

    // MARK: Planned

    @ViewBuilder
    private func planned(_ schedule: Schedule) -> some View {
        let spoken = snapshot.spoken("Tonight, \(schedule.name), \(schedule.spokenSummary)")
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    Image(systemName: "moon.stars.fill").font(.system(size: 15, weight: .semibold))
                    Text(Format.shortDuration(schedule.totalMinutes))
                        .font(.system(size: 11, weight: .semibold))
                        .minimumScaleFactor(0.7)
                }
                .padding(4)
            }
            .widgetAccentable()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
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
            Label("Tonight: \(schedule.name)", systemImage: "moon.stars.fill")
        case .systemSmall:
            VStack(alignment: .leading, spacing: 0) {
                // Stacked: a name and "Tonight" don't fit across a small widget together.
                if let name = snapshot.headingName {
                    WidgetEyebrow(name).padding(.bottom, 3)
                }
                WidgetEyebrow("Tonight")
                Text(schedule.name)
                    .font(.system(size: 17, weight: .semibold))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .padding(.top, 4)
                Spacer(minLength: 6)
                NightCurve(phases: schedule.phases, lineWidth: 2.5)
                    .frame(height: 40)
                    .padding(.horizontal, -8)
                Spacer(minLength: 6)
                Text(Format.duration(schedule.totalMinutes))
                    .font(.system(size: 13, weight: .semibold))
                    .monospacedDigit()
                Text("Tap to start")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.secondary)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(spoken)
        default:
            VStack(alignment: .leading, spacing: 10) {
                header(eyebrow: snapshot.headed("Tonight"), title: schedule.name) {
                    // Started on the BedJet the widget shows, which may not be the one on screen in Lull.
                    Button(intent: StartNightIntent(bedJet: snapshot.bedJet(nil).map(BedJetEntity.init))) {
                        Text("Start")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 7)
                            .background(.white, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Start Night")
                }
                .accessibilityElement(children: .contain)
                NightCurve(phases: schedule.phases)
                    .frame(maxHeight: family == .systemLarge ? 90 : .infinity)
                    .padding(.horizontal, -8)
                    .accessibilityLabel(spoken)
                Text(schedule.summary)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.secondary)
                    .monospacedDigit()
                    .accessibilityHidden(true)
                if family == .systemLarge {
                    phaseList(schedule, startedAt: now, current: nil)
                    Spacer(minLength: 0)
                }
            }
        }
    }

    // MARK: Empty

    @ViewBuilder
    private var empty: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "moon.zzz.fill").font(.system(size: 18, weight: .semibold))
            }
            .accessibilityLabel("Lull, no schedule yet")
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text("Lull").font(.headline)
                Text("No schedule yet")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .accessoryInline:
            Label("No schedule yet", systemImage: "moon.zzz.fill")
        default:
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "moon.zzz.fill")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Theme.secondary)
                Spacer(minLength: 0)
                Text("No schedule yet").font(.system(size: 17, weight: .semibold))
                Text("Open Lull to plan how your night feels.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .accessibilityElement(children: .combine)
        }
    }

    // MARK: Parts

    private func header(eyebrow: String, title: String, @ViewBuilder trailing: () -> some View) -> some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                WidgetEyebrow(eyebrow)
                Text(title)
                    .font(.system(size: 19, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Spacer(minLength: 0)
            trailing()
        }
    }

    /// Up to four phases, keeping the one running and the next in view.
    private func phaseList(_ schedule: Schedule, startedAt: Date, current: Int?) -> some View {
        let shown = 4
        let first = max(0, min((current ?? 0) - 1, schedule.phases.count - shown))
        var starts: [Date] = []
        var start = startedAt
        for phase in schedule.phases {
            starts.append(start)
            start = start.addingTimeInterval(TimeInterval(phase.minutes * 60))
        }
        return VStack(spacing: 2) {
            ForEach(Array(schedule.phases.enumerated().dropFirst(first).prefix(shown)), id: \.element.id) { index, phase in
                PhaseLine(
                    phase: phase,
                    detail: snapshot.detail(of: phase),
                    startsAt: starts[index],
                    isCurrent: index == current,
                    isDone: current.map { index < $0 } ?? false
                )
            }
        }
        .padding(.horizontal, -8)
    }
}

extension WidgetFamily {
    var isAccessory: Bool {
        self == .accessoryCircular || self == .accessoryRectangular || self == .accessoryInline
    }
}

#Preview("Running", as: .systemMedium) {
    TonightWidget()
} timeline: {
    NightEntry(date: .now, snapshot: .preview())
}
