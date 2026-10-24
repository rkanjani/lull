import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var selectedTab = LullTab.remote
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        @Bindable var model = model
        Group {
            if let bedJet = model.bedJet {
                tabs(bedJet)
            } else {
                // Lull only ever controls a BedJet the person picked.
                ChooseBedJetView()
            }
        }
        // Widgets open the tab they show; controls and Shortcuts send commands.
        .onOpenURL { url in
            // A link can name its BedJet, so tapping one side of the bed opens that side.
            if let id = LullTab.bedJet(in: url) { model.select(id) }
            if let tab = LullTab(url: url) { selectedTab = tab }
        }
        .onAppear { AppCommands.handler = handle }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background: model.appMovedToBackground()
            case .active: model.appBecameActive()
            default: break
            }
        }
        .onChange(of: model.selectedID) { old, _ in
            // Said once, when the person switches; scanning and background BedJets stay quiet.
            guard old != nil, let bedJet = model.bedJet else { return }
            announce("Showing \(model.title(of: bedJet)), \(bedJet.spokenConnectionLabel)")
        }
        .alert("Something went wrong", isPresented: .init(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }

    private func tabs(_ bedJet: BedJet) -> some View {
        @Bindable var bedJet = bedJet
        return TabView(selection: $selectedTab) {
            Tab("Remote", systemImage: "dial.medium.fill", value: LullTab.remote) { RemoteView() }
            Tab("Schedule", systemImage: "moon.stars.fill", value: LullTab.schedule) { ScheduleView() }
            Tab("History", systemImage: "clock.arrow.circlepath", value: LullTab.history) { HistoryView() }
        }
        .environment(bedJet)
        // Remade for each BedJet, so switching to another isn't announced as its connection or night changing.
        .background { Announcements(bedJet: bedJet).id(bedJet.id) }
        .confirmationDialog("Your BedJet's three slots are full", isPresented: $bedJet.needsSlotChoice, titleVisibility: .visible) {
            ForEach(Array(bedJet.deviceSlots.enumerated()), id: \.offset) { index, program in
                Button("Replace “\(program?.name ?? "Slot \(index + 1)")”") {
                    Task { await bedJet.startTonight(replacingSlot: index) }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Lull keeps tonight's schedule in one slot on the BedJet. Pick one to reuse. Everything on the BedJet is already copied into your schedules.")
        }
    }

    private func handle(_ command: AppCommands.Command) {
        switch command {
        case let .show(tab):
            selectedTab = tab
        case .startNight:
            selectedTab = .schedule
            // A night already under way, from this phone or another, is left running and shown.
            guard let bedJet = model.bedJet, bedJet.run == nil, bedJet.runningElsewhereStep == nil, !bedJet.isBusy else { return }
            Task { await bedJet.startTonight() }
        }
    }
}

func announce(_ message: String) {
    AccessibilityNotification.Announcement(message).post()
}

/// Changes VoiceOver users would otherwise only notice by exploring the screen again.
private struct Announcements: View {
    let bedJet: BedJet

    var body: some View {
        Color.clear
            .accessibilityHidden(true)
            .onChange(of: bedJet.linkState) { old, state in
                switch state {
                case .ready: announce("BedJet connected")
                case .yielded: announce("BedJet in use on another phone")
                case .bluetoothOff: announce("Bluetooth is off")
                case .unauthorized: announce("Bluetooth not allowed for Lull")
                case .searching, .connecting: if old == .ready { announce("BedJet disconnected") }
                }
            }
            .onChange(of: bedJet.isSlowToFind) { _, slow in
                if slow { announce("BedJet not found. The BedJet button at the top has tips.") }
            }
            .onChange(of: bedJet.run?.schedule.name) { old, name in
                if let name { announce("Started \(name)") } else if old != nil { announce("Night ended") }
            }
    }

}

/// Screen scaffold shared by the tabs: large title on the left, the BedJet on screen and its connection on the right.
struct Screen<Content: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var content: Content
    @State private var showsDevice = false
    @State private var showsChooser = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // At the largest text sizes the title takes the full width and the connection sits beneath it.
        let header = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(alignment: .top))
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header {
                    VStack(alignment: .leading, spacing: 4) {
                        if let subtitle { Eyebrow(subtitle) }
                        Text(title)
                            .scaledFont(34, .bold)
                            .accessibilityAddTraits(.isHeader)
                    }
                    // A long BedJet name wraps rather than the screen's title.
                    .layoutPriority(1)
                    Spacer(minLength: 0)
                    ConnectionPill(showsSettings: $showsDevice, showsChooser: $showsChooser)
                }
                .padding(.top, 8)
                content
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .background(Theme.background)
        .sheet(isPresented: $showsDevice) { DeviceSheet() }
        .sheet(isPresented: $showsChooser) {
            NavigationStack {
                NearbyBedJets().toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showsChooser = false } }
                }
            }
        }
    }
}

/// Names the BedJet on screen and says how its connection is going, in words as well as the dot. Opens its
/// settings; with several BedJets it switches between them.
struct ConnectionPill: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    // Bindings rather than closures, so the open menu isn't rebuilt each time the screen beneath it updates.
    @Binding var showsSettings: Bool
    @Binding var showsChooser: Bool

    var body: some View {
        let title = model.title(of: bedJet)
        if model.hasSeveralBedJets {
            Menu {
                // Picking another is refused until then, so say why.
                if !model.canSwitch { Text("Switching waits until the schedule finishes saving to \(title).") }
                Picker("BedJets", selection: Binding(get: { bedJet.id }, set: { model.select($0) })) {
                    ForEach(model.bedJets) { bedJet in
                        Text(model.title(of: bedJet)).tag(bedJet.id)
                    }
                }
                .pickerStyle(.inline)
                Section {
                    Button("Add Another BedJet", systemImage: "plus") { showsChooser = true }.disabled(!model.canSwitch)
                    Button("BedJet Settings", systemImage: "gearshape") { showsSettings = true }
                }
            } label: {
                pill(title, state: bedJet.connectionLabel, isConnected: bedJet.isConnected, switches: true)
            }
            .menuOrder(.fixed)
            .sensoryFeedback(.selection, trigger: bedJet.id)
            .accessibilityLabel("BedJet, \(title), \(bedJet.spokenConnectionLabel)")
            .accessibilityHint("Switches BedJet, adds another, or opens BedJet settings")
        } else {
            Button { showsSettings = true } label: { pill(title, state: bedJet.connectionLabel, isConnected: bedJet.isConnected) }
                .buttonStyle(.plain)
                .accessibilityLabel("BedJet, \(title), \(bedJet.spokenConnectionLabel)")
                .accessibilityHint("Shows the connection, BedJet settings and help finding it")
        }
    }

    private func pill(_ title: String, state: String, isConnected: Bool, switches: Bool = false) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(isConnected ? Color.green : Theme.neutral)
                .frame(width: 8, height: 8)
                .symbolEffect(.pulse, isActive: !isConnected)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).scaledFont(13, .semibold)
                Text(state).scaledFont(12, .medium).foregroundStyle(Theme.secondary)
            }
            if switches {
                Image(systemName: "chevron.up.chevron.down")
                    .scaledFont(10, .bold)
                    .foregroundStyle(Theme.secondary)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .frame(minHeight: 44)
        .background(Theme.raised, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.hairline))
    }
}

extension BedJet {
    /// A word or two on how the connection is going, short enough for the pill.
    var connectionLabel: String {
        // Invites a tap through to the things worth checking.
        if isSlowToFind { return "Not found" }
        switch linkState {
        case .ready: return isDemo && !AppModel.labelsDemoAsConnected ? "Demo" : "Connected"
        case .connecting: return "Connecting"
        case .searching: return "Searching"
        case .yielded: return "Other phone"
        case .bluetoothOff: return "Bluetooth off"
        case .unauthorized: return "No access"
        }
    }

    var spokenConnectionLabel: String {
        linkState == .yielded && !isSlowToFind ? "in use on another phone" : connectionLabel
    }
}
