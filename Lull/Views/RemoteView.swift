import BedJetKit
import SwiftUI

/// Direct control, for when you want air right now rather than a schedule.
struct RemoteView: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var temperature = 60
    @State private var fan = 40.0
    @State private var runtime = 60.0
    /// When the air turns off, as a minute of the day, for the Remote showing it as a time.
    @State private var endMinute = 0
    /// While a slider is in use, and briefly after, it leads and the BedJet's reports are not applied to it.
    @State private var followAfter = Date.distantPast
    @State private var pending: [String: Task<Void, Never>] = [:]
    /// The mode just tapped, shown as selected until the BedJet reports it.
    @State private var chosenMode: Mode?

    var body: some View {
        let status = bedJet.status
        let mode = status?.mode ?? .standby
        Screen(title: "Remote") {
            ClimateOrb(status: status).frame(maxWidth: .infinity)

            if bedJet.linkState == .yielded {
                Text("In use on another phone. Touch a control to take over.")
                    .scaledFont(13)
                    .foregroundStyle(Theme.secondary)
                    .frame(maxWidth: .infinity)
            }

            ModePicker(modes: bedJet.remoteModes + [.standby], selection: chosenMode ?? status?.mode,
                       hint: bedJet.run != nil ? "Ends the schedule that's running" : nil) { mode in
                chosenMode = mode
                Task {
                    await bedJet.setMode(mode)
                    chosenMode = nil
                }
            }
            // Only the controls: the BedJet button stays usable, so Demo mode is reachable with Bluetooth off.
            .disabled(!bedJet.canControl)

            if let status, status.isRunning {
                Card(padding: 18) {
                    VStack(spacing: 16) {
                        if bedJet.adjustsTemperature(mode) {
                            TemperatureSlider(mode: mode, temperature: sending($temperature, "temperature") {
                                await bedJet.setTemperature(temperature)
                            }, limits: status.minTemperature < status.maxTemperature ? status.minTemperature...status.maxTemperature : nil)
                        }
                        LabeledSlider(title: "Fan", valueText: "\(Int(fan))%", value: sending($fan, "fan") {
                            await bedJet.setFan(percent: Int(fan))
                        }, range: 5...100, step: 5, tint: mode.tint)
                        if bedJet.run == nil {
                            turnsOff(status)
                        } else {
                            // A schedule sets its own timing, so this is read-only until it ends.
                            ValueHeader(title: "Phase ends in", value: Format.duration(minutesLeft(status)))
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel("Phase ends in")
                                .accessibilityValue(Format.spokenDuration(minutesLeft(status)))
                        }
                    }
                }
                .disabled(!bedJet.canControl)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            if bedJet.run != nil {
                Text("Choosing a mode here ends the schedule that's running.")
                    .scaledFont(13)
                    .foregroundStyle(Theme.tertiary)
                    .frame(maxWidth: .infinity)
            }
        }
        .animation(.smooth(duration: 0.3), value: status?.isRunning)
        .onChange(of: status, initial: true) { _, status in
            // Follow the unit, which can also be changed from its own remote, but never
            // while a slider is being moved or its new value is still on the way there.
            guard let status, !bedJet.isBusy, Date() > followAfter else { return }
            temperature = status.targetTemperature
            fan = Double(status.fanPercent)
            let limit = runtimeLimit(status)
            runtime = min(max(Double(minutesLeft(status)), limit.range.lowerBound), limit.range.upperBound)
            endMinute = Format.endMinute(after: status.remainingSeconds, from: Date())
        }
        .onChange(of: bedJet.id) {
            // Another BedJet's settings show straight away, and a slider change still on its way is dropped
            // rather than sent once this BedJet is no longer the one on screen.
            for task in pending.values { task.cancel() }
            pending = [:]
            followAfter = .distantPast
            chosenMode = nil
        }
    }

    /// A binding that forwards what the person sets to the BedJet shortly after they stop moving the slider.
    /// Only their input passes through the setter, so values applied from the BedJet are never echoed back.
    private func sending<Value>(_ value: Binding<Value>, _ name: String, send: @escaping () async -> Void) -> Binding<Value> {
        Binding {
            value.wrappedValue
        } set: { newValue in
            value.wrappedValue = newValue
            followAfter = Date().addingTimeInterval(1.5)
            pending[name]?.cancel()
            pending[name] = Task {
                try? await Task.sleep(nanoseconds: 350_000_000)
                guard !Task.isCancelled else { return }
                await send()
            }
        }
    }

    /// When the air turns off: after a duration, the default, or at a time of day. Choosing one only changes what's
    /// shown, since the BedJet counts down either way.
    @ViewBuilder private func turnsOff(_ status: DeviceStatus) -> some View {
        Picker("Turns off", selection: Binding { model.remoteTimerShowsTime } set: { model.remoteTimerShowsTime = $0 }) {
            Text("Timer").tag(false)
            Text("Time").tag(true)
        }
        .pickerStyle(.segmented)
        // Both are laid out, the one not chosen hidden, so the card keeps one height and nothing on the screen moves
        // when switching. The hidden one can't be seen, touched or read.
        let showsTime = model.remoteTimerShowsTime
        ZStack(alignment: .topLeading) {
            timerSlider(status)
                .opacity(showsTime ? 0 : 1)
                .allowsHitTesting(!showsTime)
                .accessibilityHidden(showsTime)
            timeRow(status)
                .opacity(showsTime ? 1 : 0)
                .allowsHitTesting(showsTime)
                .accessibilityHidden(!showsTime)
        }
    }

    private func timerSlider(_ status: DeviceStatus) -> some View {
        let limit = runtimeLimit(status)
        return LabeledSlider(title: "Turns off in", valueText: Format.duration(Int(runtime)),
                             spokenValue: Format.spokenDuration(Int(runtime)), value: sending($runtime, "runtime") {
            await bedJet.setRuntime(minutes: Int(runtime))
        }, range: limit.range, step: limit.step, headerMinHeight: timeRowHeight)
    }

    private func timeRow(_ status: DeviceStatus) -> some View {
        let picked = sending($endMinute, "runtime") {
            await bedJet.setRuntime(until: Format.date(atNext: endMinute, after: Date()))
        }
        // At the largest text sizes the time goes under its label rather than squeezing it into a sliver.
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout())
        return VStack(alignment: .leading, spacing: 8) {
            layout {
                // Read before the picker, which VoiceOver calls only "Time Picker".
                Text("Turns off at").scaledFont(15, .medium).foregroundStyle(Theme.secondary)
                Spacer(minLength: 0)
                DatePicker("Turns off at", selection: timeOfDayBinding(get: { picked.wrappedValue }, set: { picked.wrappedValue = $0 }),
                           displayedComponents: .hourAndMinute)
                    .labelsHidden()
            }
            // As tall as the timer's heading, so "Turns off" stays put when switching between them.
            .frame(minHeight: timeRowHeight)
            Text(turnsOffCaption(status))
                .scaledFont(13)
                .foregroundStyle(Theme.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// How long until the air turns off, or why it turns off sooner than the time picked.
    private func turnsOffCaption(_ status: DeviceStatus) -> String {
        let cutShort: Bool
        if case .until(let end) = bedJet.remoteTimer, bedJet.remoteTimer.isAhead {
            cutShort = end.timeIntervalSinceNow > Double(status.remainingSeconds + 60)
        } else {
            cutShort = false
        }
        guard cutShort else { return "In \(Format.duration(minutesLeft(status)))." }
        let longest = status.maxRuntimeMinutes > 0 ? status.maxRuntimeMinutes : status.mode.maxMinutes
        return "\(status.mode.title) runs for up to \(Format.duration(longest)), so it turns off at \(Format.clock(endMinute))."
    }

    private func minutesLeft(_ status: DeviceStatus) -> Int {
        max((status.remainingSeconds + 59) / 60, 1)
    }

    /// How long the current mode may run, as the BedJet reports it, with a step size that suits the span.
    private func runtimeLimit(_ status: DeviceStatus) -> (range: ClosedRange<Double>, step: Double) {
        let longest = status.maxRuntimeMinutes > 0 ? status.maxRuntimeMinutes : status.mode.maxMinutes
        let step = longest <= 15 ? 1 : longest <= 240 ? 5 : 15
        return (Double(step)...Double(max(longest / step * step, step * 2)), Double(step))
    }
}

/// The glowing live readout: what the BedJet is doing this second.
struct ClimateOrb: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    let status: DeviceStatus?
    /// Grows with the text inside it, up to what fits across a phone.
    @ScaledMetric(relativeTo: .largeTitle) private var diameter: CGFloat = 204

    var body: some View {
        let mode = status?.mode ?? .standby
        let isOn = status?.isRunning ?? false
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [mode.tint.opacity(isOn ? 0.55 : 0.12), .clear], center: .center, startRadius: 20, endRadius: 112))
                .blur(radius: 24)
            Circle()
                .strokeBorder(
                    AngularGradient(colors: [mode.tint.opacity(0.9), mode.tint.opacity(0.15), mode.tint.opacity(0.9)], center: .center),
                    lineWidth: isOn ? 2.5 : 1
                )
                .padding(8)
            VStack(spacing: 4) {
                if let status {
                    // White, unlike other labels, so it stays readable over the glow.
                    Eyebrow(mode.activity, color: .white)
                    Text(isOn && bedJet.adjustsTemperature(mode) ? model.degrees(status.targetTemperature) : isOn ? "\(status.fanPercent)%" : "Off")
                        .scaledFont(60, .light)
                        .monospacedDigit()
                        .contentTransition(.numericText())
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                    Text(isOn ? "Fan \(status.fanPercent)% · Air \(model.degrees(status.actualTemperature))"
                              : "Room \(model.degrees(status.ambientTemperature))")
                        .scaledFont(15, .medium)
                        .foregroundStyle(.white)
                        .monospacedDigit()
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.7)
                } else {
                    ProgressView()
                    Text("Looking for \(model.title(of: bedJet))")
                        .scaledFont(15, .medium)
                        .foregroundStyle(Theme.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                }
            }
            .padding(.horizontal, 28)
        }
        .frame(width: min(diameter, 320), height: min(diameter, 320))
        .animation(.smooth(duration: 0.6), value: mode)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenStatus)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private var spokenStatus: String {
        guard let status else { return "Looking for \(model.title(of: bedJet))" }
        guard status.isRunning else { return "Off, room \(model.degrees(status.ambientTemperature))" }
        var parts = [status.mode.activity]
        if bedJet.adjustsTemperature(status.mode) { parts.append("set to \(model.degrees(status.targetTemperature))") }
        parts.append("fan \(status.fanPercent)%")
        parts.append("air \(model.degrees(status.actualTemperature))")
        return parts.joined(separator: ", ")
    }
}
