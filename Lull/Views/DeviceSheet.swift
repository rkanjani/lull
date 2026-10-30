import BedJetKit
import SwiftUI

struct DeviceSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(BedJet.self) private var bedJet
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                if model.hasSeveralBedJets {
                    KnownBedJets(footer: "Every screen shows the ticked BedJet. Switch from the top of any screen too.")
                }

                Section {
                    LabeledContent("Nickname") {
                        // The example would clip at the largest text sizes, where the field is narrow.
                        TextField("Nickname", text: nickname, prompt: typeSize.isAccessibilitySize ? nil : Text("Such as My side"))
                            .multilineTextAlignment(.trailing)
                            .submitLabel(.done)
                    }
                    LabeledContent("Status", value: statusText)
                    if let name = bedJet.name { LabeledContent("Name", value: name) }
                    LabeledContent("Kind", value: bedJet.knownGeneration?.title ?? "Known once it connects")
                    if let firmware = bedJet.firmware { LabeledContent("Firmware", value: firmware) }
                } header: {
                    Text(model.title(of: bedJet))
                } footer: {
                    Text(help)
                }

                if bedJet.isSlowToFind {
                    Section {
                        Label("Lull works with BedJet 3 and BedJet V2. The original BedJet isn't supported.", systemImage: "bed.double")
                        Label("Force-quit the BedJet app: swipe up from the bottom of the screen, then swipe the app away.", systemImage: "xmark.app")
                        Label("Close the BedJet app on any other phone or tablet nearby.", systemImage: "iphone")
                        Label("Unplug the BedJet for 10 seconds, then plug it back in.", systemImage: "powerplug")
                        NavigationLink { NearbyBedJets() } label: { Label("Choose Another BedJet", systemImage: "list.bullet") }
                            .disabled(!model.canSwitch)
                    } header: {
                        Text("Can't find \(model.title(of: bedJet))?")
                    } footer: {
                        Text("Lull keeps looking for this BedJet while it's open, and never connects to a different one by itself.")
                    }
                }

                // Kept, but off, while the BedJet restarts to check for firmware, so the sheet doesn't jump about.
                if bedJet.isConnected || bedJet.isRestarting, let extra = bedJet.settings {
                    Section("BedJet") {
                        Toggle("Light ring", isOn: .init(get: { extra.ledEnabled }, set: { on in Task { await bedJet.setLED(on) } }))
                        Toggle("Mute beeps", isOn: .init(get: { extra.beepsMuted }, set: { muted in Task { await bedJet.setMuted(muted) } }))
                    }
                    .disabled(!bedJet.isConnected)
                }

                if !bedJet.deviceSlots.isEmpty {
                    Section {
                        ForEach(Array(bedJet.deviceSlots.enumerated()), id: \.offset) { index, program in
                            LabeledContent("Slot \(index + 1)") {
                                Text(index == bedJet.slot ? "Lull · \(program?.name ?? "Empty")" : program?.name ?? "Empty")
                                    .accessibilityLabel(index == bedJet.slot ? "Used by Lull, \(program?.name ?? "empty")" : program?.name ?? "Empty")
                            }
                        }
                    } header: {
                        Text("Saved on the BedJet")
                    } footer: {
                        Text("Lull keeps tonight's schedule in one slot and leaves the others alone.")
                    }
                }

                // A BedJet V2's firmware can't be updated from a phone.
                if bedJet.knownGeneration == .v3 {
                    FirmwareSection()
                    WiFiSection()
                }

                if !model.hasSeveralBedJets {
                    Section {
                        NavigationLink { NearbyBedJets() } label: { Label("Add Another BedJet", systemImage: "plus") }
                            .disabled(!model.canSwitch)
                    } footer: {
                        Text("Have a BedJet on each side of the bed? Add both, then switch between them from the top of any screen.")
                    }
                }

                Section {
                    Button("Forget This BedJet", role: .destructive) {
                        dismiss()
                        model.forget(bedJet)
                    }
                } footer: {
                    Text("The BedJet keeps running as it is, and its history stays on this phone in case you choose it again.")
                }

                Section("App") {
                    Picker("Temperature", selection: $model.useFahrenheit) {
                        Text("°F").tag(true).accessibilityLabel("Fahrenheit")
                        Text("°C").tag(false).accessibilityLabel("Celsius")
                    }
                    Toggle("Demo mode", isOn: $model.demoMode)
                }
            }
            .tint(.green)
            .navigationTitle(model.hasSeveralBedJets ? "BedJets" : "BedJet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    /// Kept on this phone only; the BedJet's own name is left alone.
    private var nickname: Binding<String> {
        Binding { bedJet.nickname ?? "" } set: { bedJet.nickname = $0.isEmpty ? nil : String($0.prefix(30)) }
    }

    private var statusText: String {
        // Restarting to check for firmware drops the connection for a few seconds; it's expected, not lost.
        if bedJet.isRestarting { return "Restarting…" }
        switch bedJet.linkState {
        case .ready: return model.demoMode ? "Demo BedJet" : "Connected"
        case .connecting: return "Connecting…"
        case .searching: return bedJet.isSlowToFind ? "Not found" : "Searching…"
        case .yielded: return "In use on another phone"
        case .bluetoothOff: return "Bluetooth is off"
        case .unauthorized: return "Bluetooth not allowed"
        }
    }

    private var help: String {
        switch bedJet.isRestarting ? .ready : bedJet.linkState {
        case .ready: return model.demoMode ? "Demo mode uses a pretend BedJet so you can explore the app." : "Everything stays between this phone and your BedJet. No account, no cloud."
        case .connecting, .searching:
            return "Stand near your BedJet and force-quit the official BedJet app. The BedJet talks to one phone at a time; phones with Lull hand it over to each other automatically."
        case .yielded: return "The BedJet talks to one phone at a time, and another phone with Lull asked for it. Use any control here to take it back."
        case .bluetoothOff: return "Turn on Bluetooth in Control Center to connect."
        case .unauthorized: return "Allow Bluetooth for Lull in Settings to connect."
        }
    }
}

/// A BedJet 3 downloads its own firmware over Wi-Fi. Lull gives it a network and asks it to check.
private struct FirmwareSection: View {
    @Environment(BedJet.self) private var bedJet
    @State private var confirming = false

    var body: some View {
        Section {
            if bedJet.updateAvailable {
                Label("Your BedJet says newer firmware is available.", systemImage: "arrow.down.circle")
            }
            let activity = bedJet.wifiActivity?.purpose == .updating ? bedJet.wifiActivity : nil
            WiFiButton(title: "Check for Update", activity: activity) { confirming = true }
                .disabled(!bedJet.canUpdateFirmware)
            if let activity, let outcome = activity.outcome { WiFiOutcomeRow(outcome: outcome, failed: activity.failed) }
        } header: {
            Text("Firmware")
        } footer: {
            if bedJet.updateWaitsForSchedule {
                Text("A schedule is running. Check once it has finished, since updating restarts your BedJet.")
            } else if !bedJet.isConnected, !bedJet.isRestarting {
                Text("Lull checks for updates while it's connected to your BedJet.")
            } else {
                Text("Your BedJet checks for updates itself, over Wi-Fi, and restarts to do it, which turns off the air for a few seconds. If there's a newer version, it installs it straight away.")
            }
        }
        .alert("Check for a firmware update?", isPresented: $confirming) {
            Button("Cancel", role: .cancel) {}
            Button("Check") { Task { await bedJet.updateFirmware() } }
        } message: {
            Text("Your BedJet restarts to check, which turns off the air for a few seconds. If there's a newer version, it installs it straight away. Keep it plugged in until it's back.")
        }
    }
}

/// The network a BedJet 3 downloads its firmware over, and setting it.
private struct WiFiSection: View {
    @Environment(BedJet.self) private var bedJet

    var body: some View {
        Section {
            NavigationLink { WiFiSetup() } label: {
                LabeledContent {
                    if let network = bedJet.wifiNetwork { Text(network) }
                } label: {
                    Label("Network", systemImage: "wifi")
                }
            }
        } header: {
            Text("Wi-Fi")
        } footer: {
            Text("Your BedJet uses Wi-Fi only to download firmware updates.")
        }
    }
}

/// A button that says what the BedJet is doing in place of its title while it's at what the button asked for.
private struct WiFiButton: View {
    let title: String
    let activity: WiFiActivity?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if let activity, activity.isRunning {
                HStack(spacing: 12) {
                    // The words say it's under way, so VoiceOver reads just those.
                    ProgressView().accessibilityHidden(true)
                    Text(activity.step).foregroundStyle(Theme.secondary)
                }
            } else {
                Text(title)
            }
        }
    }
}

/// How joining Wi-Fi or checking for firmware went, below the button that asked.
private struct WiFiOutcomeRow: View {
    let outcome: String
    let failed: Bool

    var body: some View {
        Label {
            Text(outcome)
        } icon: {
            Image(systemName: failed ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .foregroundStyle(failed ? Theme.warm : .green)
        }
    }
}

/// Gives a BedJet 3 the Wi-Fi network it fetches firmware over: the one this iPhone is on, or any name, such as a
/// hidden network's. Lull remembers the name it gave, never the password.
struct WiFiSetup: View {
    @Environment(BedJet.self) private var bedJet
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.openURL) private var openURL
    @State private var networks: WiFiNetworks?
    /// The network picked from the list; nil while the name is typed in.
    @State private var picked: String?
    @State private var typed = ""
    @State private var password = ""
    @FocusState private var field: Field?

    private enum Field { case name, password }

    private var network: String { picked ?? typed }

    var body: some View {
        let problem = Codec.wifiProblem(network: network, password: password)
        Form {
            Section {
                if let current = networks?.current {
                    NetworkRow(name: current, detail: "This iPhone's network", isPicked: picked == current) {
                        picked = current
                        field = .password
                    }
                }
                NetworkRow(name: "Other Network…", isPicked: picked == nil) {
                    picked = nil
                    field = .name
                }
                if picked == nil {
                    LabeledContent("Name") {
                        // The example would clip at the largest text sizes, where the field is narrow.
                        TextField("Name", text: $typed, prompt: typeSize.isAccessibilitySize ? nil : Text("Network name"))
                            .focused($field, equals: .name)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.next)
                            .onSubmit { field = .password }
                    }
                }
                if networks?.access == .denied || networks?.access == .approximate {
                    Button("Open Settings") {
                        if let settings = URL(string: UIApplication.openSettingsURLString) { openURL(settings) }
                    }
                }
            } header: {
                Text("Network")
            } footer: {
                Text(networkHelp)
            }

            Section {
                // Shown as typed, to check a long Wi-Fi password, and so the phone doesn't offer to save it for Lull.
                LabeledContent("Password") {
                    TextField("Password", text: $password)
                        .focused($field, equals: .password)
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                }
            } footer: {
                Text("BedJet 3 can only join 2.4 GHz networks, not 5 GHz ones, whose names often end in “5G”. Lull hands the name and password to your BedJet, and remembers the name but never the password.")
            }

            Section {
                let activity = bedJet.wifiActivity?.purpose == .joining ? bedJet.wifiActivity : nil
                WiFiButton(title: "Join Network", activity: activity) {
                    Task { await bedJet.joinWiFi(network: network, password: password) }
                }
                .disabled(!bedJet.canJoinWiFi || problem != nil)
                if let activity, let outcome = activity.outcome { WiFiOutcomeRow(outcome: outcome, failed: activity.failed) }
            } footer: {
                if let problem, !network.isEmpty || !password.isEmpty {
                    Text(problem)
                } else {
                    Text("Your BedJet tries the network straight away and says how it went.")
                }
            }
        }
        .tint(.green)
        .navigationTitle("Wi-Fi")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if typed.isEmpty { typed = bedJet.wifiNetwork ?? "" }
            let networks = WiFiNetworks(isDemo: bedJet.isDemo)
            self.networks = networks
            networks.look()
        }
        .onChange(of: networks?.current) { _, current in
            // A network the iPhone has left stays the one joined, typed in where it can be seen.
            if let gone = picked, gone != current {
                typed = gone
                picked = nil
            }
            // Offer this iPhone's network, unless another name is already there.
            guard let current, picked == nil, typed.isEmpty || typed == current else { return }
            picked = current
            typed = ""
        }
    }

    private var networkHelp: String {
        switch networks?.access {
        case .allowed?:
            return "An iPhone can only see the network it's on. For another one, or a hidden one, choose Other Network."
        case .approximate?:
            return "To show the network this iPhone is on, turn on Precise Location for Lull in Settings. Or choose Other Network."
        case .denied?:
            return "To show the network this iPhone is on, allow location access for Lull in Settings. Lull uses it only here. Or choose Other Network."
        default:
            return "Lull asks for location access only to see the network this iPhone is on. Or choose Other Network."
        }
    }
}

/// A Wi-Fi network to pick, ticked when it's the one picked.
private struct NetworkRow: View {
    let name: String
    var detail: String?
    let isPicked: Bool
    let pick: () -> Void

    var body: some View {
        Button(action: pick) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).foregroundStyle(.white)
                    if let detail {
                        Text(detail)
                            .font(.footnote)
                            .foregroundStyle(Theme.secondary)
                    }
                }
                Spacer()
                if isPicked { Image(systemName: "checkmark").font(.body.weight(.semibold)) }
            }
            .contentShape(Rectangle())
        }
        .accessibilityLabel(detail.map { "\(name), \($0)" } ?? name)
        .accessibilityAddTraits(isPicked ? .isSelected : [])
    }
}

/// Every BedJet this phone knows, to switch between. Ticked is the one on screen; each says how it's connected.
struct KnownBedJets: View {
    @Environment(AppModel.self) private var model
    var footer: String
    var offersAdding = true

    var body: some View {
        Section {
            ForEach(model.bedJets) { bedJet in
                let isShown = bedJet.id == model.selectedID
                let title = model.title(of: bedJet)
                Button { model.select(bedJet.id) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).foregroundStyle(.white)
                            Text([bedJet.knownGeneration?.title, bedJet.connectionLabel].compactMap { $0 }.joined(separator: " · "))
                                .font(.footnote)
                                .foregroundStyle(Theme.secondary)
                        }
                        Spacer()
                        if isShown { Image(systemName: "checkmark").font(.body.weight(.semibold)) }
                    }
                    .contentShape(Rectangle())
                }
                .disabled(!isShown && !model.canSwitch)
                .accessibilityLabel("\(title), \(bedJet.spokenConnectionLabel)")
                .accessibilityAddTraits(isShown ? .isSelected : [])
            }
            if offersAdding {
                NavigationLink { NearbyBedJets() } label: { Label("Add Another BedJet", systemImage: "plus") }
                    .disabled(!model.canSwitch)
            }
        } header: {
            Text("Your BedJets")
        } footer: {
            if !model.canSwitch, let bedJet = model.bedJet {
                Text("Switching waits until the schedule finishes saving to \(model.title(of: bedJet)).")
            } else {
                Text(footer)
            }
        }
    }
}

/// BedJets advertising nearby, to pick one. Looking never connects to any of them or changes anything.
struct NearbyBedJets: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// Part of a larger list rather than a screen of its own.
    var embedded = false

    var body: some View {
        if embedded {
            nearby
        } else {
            Form { nearby }
                .tint(.green)
                .navigationTitle("Add a BedJet")
                .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var nearby: some View {
        Section {
            ForEach(model.nearby) { unit in
                let title = model.title(of: unit)
                Button {
                    model.choose(unit)
                    if !embedded { dismiss() }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).foregroundStyle(.white)
                            Text("\(unit.generation?.title ?? "BedJet") · \(unit.signal.rawValue) signal")
                                .font(.footnote)
                                .foregroundStyle(Theme.secondary)
                        }
                        Spacer()
                        Image(systemName: "cellularbars", variableValue: unit.signal == .strong ? 1 : unit.signal == .fair ? 0.6 : 0.3)
                            .foregroundStyle(Theme.secondary)
                            .accessibilityHidden(true)
                    }
                    .contentShape(Rectangle())
                }
                .disabled(!model.canSwitch)
                .accessibilityLabel("\(title), \(unit.generation?.title ?? "BedJet"), \(unit.signal.rawValue.lowercased()) signal")
                .accessibilityHint("Adds this BedJet and shows it")
            }
            looking
        } header: {
            Text("Nearby")
        } footer: {
            Text("A BedJet shows here while no phone is connected to it. If yours is missing, close the BedJet app, and Lull on other phones, then wait a moment. "
                 + "Signal is a hint, not proof: the strongest isn't always the closest. A code after a name tells apart BedJets with the same name; give yours a nickname in BedJet settings.")
        }
        .onAppear { model.startLooking() }
        .onDisappear { model.stopLooking() }
    }

    @ViewBuilder
    private var looking: some View {
        switch model.lookingState {
        case .bluetoothOff:
            Label("Turn on Bluetooth in Control Center to look for BedJets.", systemImage: "antenna.radiowaves.left.and.right.slash")
        case .unauthorized:
            Label("Allow Bluetooth for Lull in Settings to look for BedJets.", systemImage: "lock")
        default:
            HStack(spacing: 12) {
                ProgressView()
                Text(model.nearby.isEmpty ? "Looking for BedJets…" : "Looking for more…").foregroundStyle(Theme.secondary)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// Shown until a BedJet is chosen: on first launch, and after forgetting the one on screen.
struct ChooseBedJetView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section {
                    Text("Pick the BedJet this phone should control. Lull only ever connects to BedJets you pick, so a partner's stays theirs.")
                        .foregroundStyle(Theme.secondary)
                }
                if !model.bedJets.isEmpty {
                    KnownBedJets(footer: "Pick one to show, or add another below.", offersAdding: false)
                }
                NearbyBedJets(embedded: true)
                Section {
                    Toggle("Demo mode", isOn: $model.demoMode)
                } footer: {
                    Text("No BedJet nearby? Demo mode uses pretend ones so you can explore the app.")
                }
            }
            .tint(.green)
            .navigationTitle("Choose Your BedJet")
        }
    }
}

extension Generation {
    var title: String {
        switch self {
        case .v3: "BedJet 3"
        case .v2: "BedJet V2"
        }
    }
}
