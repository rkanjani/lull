import BedJetKit
import SwiftUI

struct ScheduleEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    @Environment(\.dismiss) private var dismiss
    @State private var schedule: Schedule
    @State private var selection: Phase.ID?
    /// A wake time set in this editor, kept while Bedtime is chosen so choosing Wake time again brings it back.
    @State private var setWakeTime: Int?
    @FocusState private var nameFocused: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    /// With a wake time, opens on the night fitted to run from the usual bedtime to it, which one saved before Lull fitted it
    /// may not be.
    init(schedule: Schedule) {
        _schedule = State(initialValue: schedule.fitted())
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Wraps rather than clipping a long name at large text sizes; Return still finishes editing.
                    TextField("Name", text: $schedule.name, axis: .vertical)
                        .scaledFont(30, .bold)
                        .focused($nameFocused)
                        .submitLabel(.done)
                        .accessibilityLabel("Schedule name")
                        .accessibilityHint("Up to \(Limits.nameLength) characters")
                        .onChange(of: schedule.name) { _, name in
                            if name.contains("\n") {
                                schedule.name = name.replacingOccurrences(of: "\n", with: "")
                                nameFocused = false
                                return
                            }
                            // The BedJet stores 15 characters.
                            if name.count > Limits.nameLength { schedule.name = String(name.prefix(Limits.nameLength)) }
                        }

                    Card {
                        NightTimeline(schedule: schedule, selection: selection, height: 170)
                            .animation(.smooth(duration: 0.35), value: schedule.phases)
                    }

                    nightCard

                    VStack(alignment: .leading, spacing: 8) {
                        Eyebrow("Phases").accessibilityAddTraits(.isHeader)
                        // What the lengths below mean, so it sits with them rather than with the night's times.
                        Text(schedule.phasesCaption)
                            .scaledFont(13)
                            .foregroundStyle(Theme.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    VStack(spacing: 10) {
                        // Each phase starts where the one before it ends, from the usual bedtime: with a wake time, on a usual night.
                        ForEach(Array(schedule.phases.enumerated()), id: \.element.id) { index, phase in
                            PhaseRow(phase: phase, number: index + 1, startsAt: schedule.startMinute(of: phase),
                                     isFixed: schedule.wakeTime != nil && phase.hasFixedLength,
                                     onDelete: schedule.phases.count > 1 ? { remove(phase.id) } : nil) {
                                selection = phase.id
                            }
                        }
                    }
                    if schedule.phases.count < Limits.stepsPerProgram {
                        Button {
                            // With a wake time it takes its hour from the phases that stretch or shrink, as far as they have it.
                            let phase = Phase(.cool, fan: 30, minutes: 60)
                            schedule.phases.append(phase)
                            schedule = schedule.fitted(holding: phase.id)
                            selection = phase.id
                        } label: {
                            Label("Add Phase", systemImage: "plus")
                        }
                        .buttonStyle(PrimaryButtonStyle(prominent: false))
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 120)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.immediately)
            .background(Theme.background)
            .safeAreaInset(edge: .bottom) {
                Button("Use Tonight") {
                    model.save(schedule)
                    bedJet.tonightID = schedule.id
                    dismiss()
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!isValid)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(.ultraThinMaterial)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.save(schedule)
                        dismiss()
                    }
                    .disabled(!isValid)
                }
            }
            .sheet(isPresented: .init(get: { selectedIndex != nil }, set: { if !$0 { selection = nil } })) {
                if let index = selectedIndex {
                    PhaseEditor(phase: phase(at: index), canDelete: schedule.phases.count > 1,
                                durationSteps: schedule.durationSteps(of: schedule.phases[index]),
                                scaling: schedule.scalingNote(of: schedule.phases[index])) {
                        remove(schedule.phases[index].id)
                    }
                }
            }
        }
    }

    private var isValid: Bool {
        !schedule.name.trimmingCharacters(in: .whitespaces).isEmpty && !schedule.phases.isEmpty
    }

    /// A phase as its sheet edits it. With a wake time the night still ends at it: the phase keeps the length it's given,
    /// and the phases that stretch or shrink share the rest.
    private func phase(at index: Int) -> Binding<Phase> {
        Binding { schedule.phases[index] } set: { phase in
            schedule.phases[index] = phase
            schedule = schedule.fitted(holding: phase.id)
        }
    }

    /// With a wake time the phases that stretch or shrink take up the time a removed one leaves.
    private func remove(_ id: Phase.ID) {
        schedule.phases.removeAll { $0.id == id }
        schedule = schedule.fitted()
    }

    /// When the night starts and ends. Without a wake time it runs from the usual bedtime for as long as its phases. With one
    /// the phases are fitted to run from the usual bedtime to the wake time, and stretch or shrink to end at it whenever
    /// Start Night is pressed.
    private var nightCard: some View {
        // At the largest text sizes each time goes under its label rather than squeezing it into a sliver.
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout())
        return Card(padding: 16) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Night").scaledFont(15, .medium).foregroundStyle(Theme.secondary).accessibilityAddTraits(.isHeader)
                // A wake time or the usual bedtime alone, chosen as the Remote chooses Timer or Time. Choosing Bedtime keeps the
                // wake time set here, so choosing Wake time again brings it back until the schedule is saved or cancelled.
                Picker("Night ends", selection: Binding { schedule.wakeTime != nil } set: { endsAtWakeTime in
                    withAnimation(.smooth(duration: 0.25)) {
                        if endsAtWakeTime {
                            schedule.wakeTime = setWakeTime ?? schedule.usualWakeTime
                            schedule = schedule.fitted()
                        } else {
                            setWakeTime = schedule.wakeTime
                            schedule.wakeTime = nil
                        }
                    }
                }) {
                    Text("Wake time").tag(true)
                    Text("Bedtime").tag(false)
                }
                .pickerStyle(.segmented)
                // The usual bedtime stays where it is, the wake time under it when there is one.
                VStack(alignment: .leading, spacing: 0) {
                    timeRow("Usual bedtime", selection: time({ schedule.bedtime }, { schedule.bedtime = $0 }), layout: layout)
                    if schedule.wakeTime != nil {
                        timeRow("Wake time", selection: time({ schedule.wakeTime ?? schedule.usualWakeTime }, { schedule.wakeTime = $0 }), layout: layout)
                            .transition(.opacity)
                    }
                }
            }
        }
    }

    /// A setting's name with its time of day after it, as tall as the Remote's time row and Android's.
    private func timeRow(_ title: String, selection: Binding<Date>, layout: AnyLayout) -> some View {
        layout {
            // Read before the picker, which VoiceOver calls only "Time Picker".
            Text(title).scaledFont(17, .medium)
            Spacer(minLength: 0)
            DatePicker(title, selection: selection, displayedComponents: .hourAndMinute)
                .labelsHidden()
        }
        .frame(minHeight: timeRowHeight)
    }

    /// The usual bedtime or the wake time, the phases fitted afresh to the night between them when either changes.
    private func time(_ get: @escaping () -> Int, _ set: @escaping (Int) -> Void) -> Binding<Date> {
        timeOfDayBinding(get: get, set: { minute in
            set(minute)
            schedule = schedule.fitted()
        })
    }

    private var selectedIndex: Int? {
        schedule.phases.firstIndex { $0.id == selection }
    }
}

private struct PhaseRow: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    let phase: Phase
    let number: Int
    let startsAt: Int
    /// Keeps its length while a wake time scales the rest of the night.
    let isFixed: Bool
    /// Nil when this is the only phase, which can't be removed.
    let onDelete: (() -> Void)?
    let action: () -> Void
    @ScaledMetric(relativeTo: .body) private var badge: CGFloat = 40
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // At the largest text sizes the timing moves under the mode instead of beside it.
        let large = typeSize.isAccessibilitySize
        let content = large ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 14))
        Button(action: action) {
            HStack(spacing: 14) {
                content {
                    HStack(spacing: 14) {
                        Image(systemName: phase.mode.symbol)
                            .scaledFont(16, .semibold)
                            .foregroundStyle(phase.mode.tint)
                            .frame(width: min(badge, 56), height: min(badge, 56))
                            .background(phase.mode.tint.opacity(0.16), in: Circle())
                        VStack(alignment: .leading, spacing: 3) {
                            Text(phase.mode.title).scaledFont(17, .semibold)
                            Text(detail).scaledFont(13).foregroundStyle(Theme.secondary)
                        }
                    }
                    if !large { Spacer() }
                    VStack(alignment: large ? .leading : .trailing, spacing: 3) {
                        HStack(spacing: 4) {
                            if isFixed { Image(systemName: "lock.fill").scaledFont(11, .semibold).foregroundStyle(Theme.secondary) }
                            Text(Format.duration(phase.minutes)).scaledFont(15, .medium)
                        }
                        Text(Format.clock(startsAt)).scaledFont(13).foregroundStyle(Theme.secondary)
                    }
                    .monospacedDigit()
                }
                if large { Spacer(minLength: 0) }
                Image(systemName: "chevron.right").scaledFont(12, .semibold).foregroundStyle(Theme.tertiary)
            }
            .padding(14)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Theme.hairline))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Phase \(number), \(model.spokenSummary(of: phase))\(isFixed ? ", fixed length" : ""), starts \(Format.clock(startsAt))")
        .accessibilityHint("Edits this phase")
        .accessibilityActions {
            if let onDelete { Button("Remove Phase", role: .destructive, action: onDelete) }
        }
    }

    private var detail: String {
        var parts: [String] = []
        if bedJet.adjustsTemperature(phase.mode) { parts.append(model.degrees(phase.temperature)) }
        if phase.mode.usesFan { parts.append("Fan \(phase.fanPercent)%") }
        return parts.isEmpty ? "Air off" : parts.joined(separator: " · ")
    }
}

private struct PhaseEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    @Environment(\.dismiss) private var dismiss
    @Binding var phase: Phase
    let canDelete: Bool
    /// The lengths the Duration slider offers. Nil when there's nothing to choose, as for the only phase that stretches or
    /// shrinks to fill the night.
    let durationSteps: ClosedRange<Int>?
    /// With a wake time, whether this phase stretches or shrinks with the night, or why the night can't.
    let scaling: String?
    let onDelete: () -> Void
    @State private var contentHeight: CGFloat = 450

    var body: some View {
        // The sheet fits what's in it at any text size, scrolling once that's taller than the screen.
        ScrollView {
            content
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        // A sheet shorter than the screen doesn't hold VoiceOver by itself; this keeps it off the editor beneath.
        .accessibilityAddTraits(.isModal)
        .presentationDetents([.height(contentHeight)])
        .presentationBackground(Theme.surface)
        .animation(.smooth(duration: 0.25), value: phase.mode)
        .onChange(of: phase.maxMinutes, initial: true) { _, longest in
            phase.minutes = min(phase.minutes, longest)
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack {
                Text("Phase").scaledFont(22, .bold).accessibilityAddTraits(.isHeader)
                Spacer()
                // Tappable across the 44 points Apple asks for, not just its text, as Android's 48dp buttons are.
                Button { dismiss() } label: {
                    Text("Done").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .scaledFont(17, .semibold)
            }
            ModePicker(modes: bedJet.phaseModes, selection: phase.mode) { mode in
                phase.mode = mode
                if !mode.temperatureRange.contains(phase.temperature) { phase.temperature = mode.defaultTemperature }
            }
            if bedJet.adjustsTemperature(phase.mode) {
                TemperatureSlider(mode: phase.mode, temperature: $phase.temperature)
            }
            if phase.mode.usesFan {
                LabeledSlider(title: "Fan", valueText: "\(phase.fanPercent)%", value: fan, range: 5...100, step: 5, tint: phase.mode.tint)
            }
            if let durationSteps {
                LabeledSlider(title: "Duration", valueText: Format.duration(phase.minutes), spokenValue: Format.spokenDuration(phase.minutes),
                              value: minutes, range: Double(durationSteps.lowerBound)...Double(durationSteps.upperBound), step: 5, tint: .white)
            } else {
                ValueHeader(title: "Duration", value: Format.duration(phase.minutes))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Duration")
                    .accessibilityValue(Format.spokenDuration(phase.minutes))
            }
            if scaling != nil {
                Toggle("Fixed length", isOn: fixedLength)
                    .scaledFont(17, .medium)
                    .tint(.green)
            }
            if let scaling {
                Text(scaling)
                    .scaledFont(13)
                    .foregroundStyle(Theme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if isHeatLimited {
                Text("At this temperature and fan, BedJet heats for up to \(Format.duration(phase.maxMinutes)) before its safety cut-off."
                     + (bedJet.generation.supports(.extendedHeat) ? " Use Gentle heat to run longer." : ""))
                    .scaledFont(13)
                    .foregroundStyle(Theme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if canDelete {
                Button(role: .destructive) { onDelete() } label: {
                    Text("Remove Phase").frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
                }
                .scaledFont(17, .medium)
            }
        }
        .padding(24)
        .padding(.top, 8)
    }

    private var isHeatLimited: Bool { phase.maxMinutes < phase.mode.maxMinutes }

    private var fixedLength: Binding<Bool> {
        Binding { phase.hasFixedLength } set: { phase.fixedLength = $0 ? true : nil }
    }

    private var fan: Binding<Double> {
        Binding { Double(phase.fanPercent) } set: { phase.fanPercent = Int($0) }
    }

    private var minutes: Binding<Double> {
        Binding { Double(phase.minutes) } set: { phase.minutes = Int($0) }
    }
}

struct TemperatureSlider: View {
    @Environment(AppModel.self) private var model
    let mode: Mode
    @Binding var temperature: Int
    /// Limits in half-degrees Celsius. The Remote passes what the BedJet reports for the mode it is in,
    /// which can be narrower than the mode's general range.
    var limits: ClosedRange<Int>?

    var body: some View {
        let low = Format.degrees(bounds.lowerBound, fahrenheit: model.useFahrenheit)
        let high = max(Format.degrees(bounds.upperBound, fahrenheit: model.useFahrenheit), low + 1)
        LabeledSlider(title: "Temperature", valueText: model.degrees(temperature), value: degrees,
                      range: Double(low)...Double(high), tint: mode.tint)
    }

    private var bounds: ClosedRange<Int> { limits ?? mode.temperatureRange }

    private var degrees: Binding<Double> {
        Binding {
            Double(Format.degrees(temperature, fahrenheit: model.useFahrenheit))
        } set: { value in
            let halves = Format.halfCelsius(fromDegrees: Int(value), fahrenheit: model.useFahrenheit)
            temperature = min(max(halves, bounds.lowerBound), bounds.upperBound)
        }
    }
}

struct ModePicker: View {
    let modes: [Mode]
    let selection: Mode?
    var hint: String?
    let onSelect: (Mode) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // Large text gets a column of full-width buttons with the icon beside the name.
        let large = typeSize.isAccessibilitySize
        let label = large ? AnyLayout(HStackLayout(spacing: 12)) : AnyLayout(VStackLayout(spacing: 6))
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: large ? 1 : 3), spacing: 10) {
            ForEach(modes, id: \.self) { mode in
                let isSelected = mode == selection
                Button { onSelect(mode) } label: {
                    label {
                        Image(systemName: mode.symbol).scaledFont(18, .semibold)
                        Text(mode.title)
                            .scaledFont(13, .semibold)
                            .multilineTextAlignment(.center)
                            // The name gets the room it needs before the icon does, and wraps rather than truncating.
                            .layoutPriority(1)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 8)
                    .foregroundStyle(isSelected ? Color.black : mode.tint)
                    .frame(maxWidth: .infinity, minHeight: 62)
                    .background(isSelected ? mode.tint : Theme.raised, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(mode.title)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
                .accessibilityHint(isSelected ? "" : hint ?? "")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Mode")
        .sensoryFeedback(.selection, trigger: selection)
    }
}
