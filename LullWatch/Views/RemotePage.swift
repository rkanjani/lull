import BedJetKit
import SwiftUI

/// Air right now rather than a schedule, as the iPhone's Remote: a mode, and the temperature, fan and timer to run it
/// at. Apply sends what was changed here; the rest stays as the BedJet has it.
struct RemotePage: View {
    @Environment(WatchModel.self) private var model
    @State private var mode = Mode.cool
    @State private var temperature = Mode.cool.defaultTemperature
    @State private var fan = 40
    @State private var minutes = 30
    /// What was changed here since the page last followed the BedJet. Only these are sent, so a setting left alone
    /// stays as the BedJet has it, even if it changed since the watch last heard.
    @State private var changed: Set<RemoteSetting> = []
    /// The BedJet wasn't running air from the Remote, so Apply turns it on with everything shown.
    @State private var startsAir = false
    /// The BedJet the page last followed, so coming back to the page keeps what was set here for it but not yet sent.
    @State private var followedID: UUID?
    @FocusState private var focused: RemoteSetting?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let bedJet = model.bedJet {
                    controls(bedJet)
                } else {
                    EmptyMessage(symbol: "bed.double", title: "No BedJet yet", message: "Choose your BedJet in Lull on your iPhone.")
                }
                if let unavailable = model.unavailable { Footnote(text: unavailable) }
            }
        }
        .navigationTitle("Remote")
        // Starts from what the BedJet is doing, and again for another BedJet. What was set here but not yet sent survives
        // a look at another page, as on Wear OS.
        .task(id: model.bedJet?.id) {
            if changed.isEmpty || model.bedJet?.id != followedID { follow(model.bedJet) }
        }
        // Keeps up with it until something here changes.
        .onChange(of: model.bedJet) { _, bedJet in
            if changed.isEmpty { follow(bedJet) }
        }
        // Once a change is sent the BedJet has it, so the page follows it again rather than sending it twice.
        .onChange(of: model.pending) { sent, pending in
            guard pending == nil, case .remote? = sent, model.errorMessage == nil else { return }
            follow(model.bedJet)
        }
        // What the BedJet is doing ends by the clock, which Lull on the iPhone may not be there to hear, so the page
        // plays it out too: once air from the Remote has run out, Apply turns it on again with everything shown.
        .task(id: endsAt) {
            guard let endsAt else { return }
            do { try await Task.sleep(for: .seconds(max(endsAt.timeIntervalSinceNow, 0))) } catch { return }
            if changed.isEmpty { follow(model.bedJet) } else { startsAir = true }
        }
        // Kept to what the mode may run at this temperature and fan, as the iPhone's phase editor does.
        .onChange(of: runtimeLimit.range, initial: true) { keepTimeInLimit() }
    }

    @ViewBuilder
    private func controls(_ bedJet: WidgetBedJet) -> some View {
        let modes = Mode.remoteModes.filter(bedJet.generation.supports) + [.standby]
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
            ForEach(modes, id: \.self) { option in
                modeButton(option)
            }
        }
        if mode != .standby {
            if bedJet.generation.adjustsTemperature(mode) {
                CrownRow(title: "Temperature", valueText: model.snapshot.degrees(temperature), value: degrees,
                         range: degreeRange, tint: mode.tint, focus: $focused, setting: .temperature)
            }
            if mode.usesFan {
                CrownRow(title: "Fan", valueText: "\(fan)%", value: Binding { Double(fan) } set: { change(&fan, to: Int($0), .fan) },
                         range: 5...100, step: 5, tint: mode.tint, focus: $focused, setting: .fan)
            }
            let limit = runtimeLimit
            CrownRow(title: "Turns off in", valueText: Format.duration(minutes), spokenValue: Format.spokenDuration(minutes),
                     value: Binding { Double(minutes) } set: { change(&minutes, to: Int($0), .time) }, range: limit.range, step: limit.step,
                     focus: $focused, setting: .time)
        }
        let preset = RemotePreset(mode: mode, minutes: minutes, temperature: temperature, fanPercent: fan).clamped
        let changes = startsAir ? Set(RemoteSetting.allCases) : changed
        ErrandButton(title: mode == .standby ? "Turn Off" : "Apply", busyTitle: "Sending…", isEnabled: !changes.isEmpty,
                     command: .remote(preset, bedJet.id, changed: changes))
        if case .night? = bedJet.live(at: Date()) {
            Footnote(text: "Choosing a mode here ends the schedule that's running.")
        }
    }

    private func modeButton(_ option: Mode) -> some View {
        let isSelected = option == mode
        return Button {
            choose(option)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: option.symbol).scaledFont(15, .semibold)
                Text(option.title).scaledFont(10, .semibold).lineLimit(2).multilineTextAlignment(.center).minimumScaleFactor(0.8)
            }
            .foregroundStyle(isSelected ? Color.black : option.tint)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(isSelected ? option.tint : Theme.raised, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(option.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func choose(_ option: Mode) {
        guard option != mode else { return }
        mode = option
        temperature = option.defaultTemperature
        changed.formUnion([.mode, .temperature])
        keepTimeInLimit()
    }

    /// Keeps the time to what the mode may run at this temperature and fan, on the step the row turns by.
    private func keepTimeInLimit() {
        let limit = runtimeLimit
        let step = Int(limit.step)
        if changed.contains(.mode), !changed.contains(.time), !startsAir {
            // As on the iPhone's Remote, another mode runs as long as it can unless a time is set here too.
            minutes = Int(limit.range.upperBound)
        } else {
            minutes = min(max((minutes + step / 2) / step * step, Int(limit.range.lowerBound)), Int(limit.range.upperBound))
        }
    }

    /// Sets one of the page's settings, noting it as changed if it is.
    private func change(_ value: inout Int, to newValue: Int, _ setting: RemoteSetting) {
        guard newValue != value else { return }
        value = newValue
        changed.insert(setting)
    }

    /// What the BedJet is doing from the Remote, or the Quick Remote's first button: Cool for 30 minutes.
    private func follow(_ bedJet: WidgetBedJet?) {
        followedID = bedJet?.id
        changed = []
        if case let .air(liveMode, liveTemperature, liveFan, endsAt)? = bedJet?.live(at: Date()) {
            startsAir = false
            mode = liveMode
            temperature = liveTemperature ?? liveMode.defaultTemperature
            fan = liveFan
            let step = Int(runtimeLimit.step)
            minutes = max(Int(endsAt.timeIntervalSinceNow / 60) / step * step, step)
        } else {
            startsAir = true
            let first = PresetChoice.defaults[0].preset(fahrenheit: model.snapshot.useFahrenheit)
            mode = first.mode
            temperature = first.temperature
            fan = first.fanPercent
            minutes = first.minutes
        }
    }

    /// The temperature in the degrees Lull shows, whole degrees at a time, as on the iPhone.
    private var degrees: Binding<Double> {
        let fahrenheit = model.snapshot.useFahrenheit
        return Binding {
            Double(Format.degrees(temperature, fahrenheit: fahrenheit))
        } set: { value in
            let halves = Format.halfCelsius(fromDegrees: Int(value.rounded()), fahrenheit: fahrenheit)
            change(&temperature, to: min(max(halves, mode.temperatureRange.lowerBound), mode.temperatureRange.upperBound), .temperature)
        }
    }

    private var degreeRange: ClosedRange<Double> {
        let fahrenheit = model.snapshot.useFahrenheit
        let low = Format.degrees(mode.temperatureRange.lowerBound, fahrenheit: fahrenheit)
        let high = max(Format.degrees(mode.temperatureRange.upperBound, fahrenheit: fahrenheit), low + 1)
        return Double(low)...Double(high)
    }

    /// When what the BedJet was last known to be doing ends. Not checked against the clock, so the page still catches up
    /// if it wasn't shown at the time.
    private var endsAt: Date? { model.bedJet?.live?.endsAt }

    /// How long the mode may run at this temperature and fan, with a step that suits the span, as on the iPhone.
    private var runtimeLimit: (range: ClosedRange<Double>, step: Double) {
        let longest = Phase(mode, temperature: temperature, fan: fan, minutes: 1).maxMinutes
        let step = longest <= 15 ? 1 : longest <= 240 ? 5 : 15
        return (Double(step)...Double(max(longest / step * step, step * 2)), Double(step))
    }
}
