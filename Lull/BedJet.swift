import BedJetKit
import Foundation
import Observation

/// What is kept of a BedJet between launches.
struct SavedBedJet: Codable {
    var id = UUID()
    /// The Bluetooth identity of the unit this BedJet always connects to.
    var unit: UUID?
    var name: String?
    var nickname: String?
    /// Nil until the unit has said what it is.
    var generation: Generation?
    var tonightID: Schedule.ID?
    var run: ActiveRun?
    var slot: Int?
    /// How the Remote's timer was left, so a time picked there survives Lull being closed while the air runs.
    var longestEnds: Date?
    var timerEnds: Date?
    /// The Wi-Fi network Lull last gave a BedJet 3. Never the password.
    var wifiNetwork: String?
}

/// A BedJet 3 using its Wi-Fi because Lull asked: to join a network, or to check for newer firmware.
struct WiFiActivity: Equatable {
    enum Purpose { case joining, updating }

    var purpose: Purpose
    /// What the BedJet is doing, until it's over.
    var step = "Asking your BedJet…"
    /// How it went, once it's over.
    var outcome: String?
    var failed = false
    /// Installing new firmware, so the version is read again once the BedJet is back.
    var awaitsRestart = false
    /// Not back ten minutes after it began installing, so no longer said to be restarting.
    var restartOverdue = false
    /// Asked for from the BedJet's offer of an update rather than the BedJet sheet, so how it went is said the same
    /// way, until that's closed.
    var offered = false
    /// The newer firmware the BedJet came back on.
    var installed: String?
    /// When the BedJet was asked, which tells this activity from a later one.
    let startedAt = Date()

    var isRunning: Bool { outcome == nil }
}

/// One BedJet Lull knows: its connection, what it reports, the schedule running on it and what it did.
@MainActor
@Observable
final class BedJet: Identifiable {
    let id: UUID
    let isDemo: Bool
    /// The Bluetooth identity of the unit picked for this BedJet. Lull never connects it to another.
    let unit: UUID?
    /// The name the BedJet gives itself, kept so it can be shown while the BedJet is away.
    private(set) var name: String?
    /// A name given on this phone, such as "My side". The BedJet itself isn't renamed.
    var nickname: String? { didSet { app?.save() } }
    var displayName: String {
        if let nickname = nickname?.trimmingCharacters(in: .whitespaces), !nickname.isEmpty { return nickname }
        return name ?? "BedJet"
    }
    private(set) var linkState: LinkState = .searching
    private(set) var status: DeviceStatus?
    /// When `status` arrived. It is kept while another phone has the BedJet or Lull is off screen, so it ages.
    private var statusReceivedAt: Date?
    /// The BedJet's own settings from its latest full report: light ring, beeps, a running program. A report's
    /// second half arrives separately, and not at all for a moment after reconnecting, so this keeps the last one
    /// rather than letting what's shown from it flicker away.
    private(set) var settings: DeviceStatus.Extra?
    private(set) var firmware: String?
    /// The kind of BedJet, kept so schedules can be edited for it while it is away. Nil until it has said.
    private(set) var knownGeneration: Generation?
    var generation: Generation { knownGeneration ?? .v3 }
    /// What is saved in the BedJet's three program slots.
    private(set) var deviceSlots: [StoredProgram?] = []

    var tonightID: Schedule.ID? { didSet { app?.save() } }
    private(set) var run: ActiveRun? { didSet { app?.save() } }
    /// The BedJet slot this app keeps tonight's schedule in.
    private(set) var slot: Int? { didSet { app?.save() } }

    /// What the BedJet did, oldest first, so the morning after can be checked against how the night felt.
    private(set) var history: [HistoryEntry] = []
    var nights: [Night] { Night.group(history) }

    /// Set once Lull has looked for the BedJet for a while without reaching it, to suggest what to check.
    private(set) var isSlowToFind = false
    private(set) var isBusy = false
    /// A schedule is being written to the BedJet, which takes several steps.
    private(set) var isSavingSchedule = false
    /// Set when every slot on the BedJet is taken and the user has to pick one to reuse.
    var needsSlotChoice = false
    /// The BedJet using its Wi-Fi because Lull asked, and how that went. One at a time.
    private(set) var wifiActivity: WiFiActivity?
    /// Set while the BedJet says newer firmware is available.
    private(set) var updateAvailable = false
    /// Set once an offered update is put off or asked for, so it waits in the BedJet sheet instead of asking again
    /// until Lull is next opened.
    private(set) var firmwareUpdateDeferred = false
    /// The Wi-Fi network Lull last gave the BedJet, kept to show it: a BedJet can't be asked which network it has.
    private(set) var wifiNetwork: String? { didSet { app?.save() } }

    @ObservationIgnored private weak var app: AppModel?
    private var link: BedJetLink?
    private var lastRefresh = Date.distantPast
    /// When the longest run Lull gave the air from the Remote ends. Changing the temperature or fan keeps to it, and
    /// picking a time clears it, so the timer then counts down as it is.
    private var longestEnds: Date? { didSet { app?.save() } }
    /// When the BedJet's timer, as Lull last left it, runs out. A timer that no longer ends then was changed from
    /// another phone or the BedJet's own remote.
    private var timerEnds: Date? { didSet { app?.save() } }
    private var timePickedAt = Date.distantPast
    /// When a fan or temperature change from this phone cleared `timerEnds` to read it back.
    private var readingTimerBackSince = Date.distantPast
    private var searchTimer: Task<Void, Never>?
    /// What the history last recorded the BedJet doing, kept across launches to notice what changed while Lull was closed.
    private var lastSeen: Seen?
    /// The latest report, waiting to settle before it is compared with `lastSeen`.
    private var observed: Seen?
    private var settling: Task<Void, Never>?
    private var connectedAt = Date.distantPast
    private var lastCommandAt = Date.distantPast
    /// The Wi-Fi phase the BedJet last reported, and since when, to tell a step under way from one left stuck.
    private var lastWiFiPhase: Int?
    private var wifiPhaseSince = Date.distantPast
    /// The BedJet reported it's restarting to install firmware, and hasn't restarted since.
    private var restartsToInstall = false

    var saved: SavedBedJet {
        SavedBedJet(id: id, unit: unit, name: name, nickname: nickname, generation: knownGeneration, tonightID: tonightID, run: run, slot: slot,
                    longestEnds: longestEnds, timerEnds: timerEnds, wifiNetwork: wifiNetwork)
    }

    var tonight: Schedule? {
        guard let schedules = app?.schedules else { return nil }
        return schedules.first { $0.id == tonightID } ?? schedules.first
    }

    var isConnected: Bool { linkState == .ready }
    /// Controls stay live while connecting or while another phone has the BedJet: what is tapped
    /// is sent the moment the connection is up.
    /// Not while it uses its Wi-Fi, though: see `isUsingWiFi`.
    var canControl: Bool { linkState != .bluetoothOff && linkState != .unauthorized && !isUsingWiFi }
    /// A schedule is running on the BedJet that this phone didn't start, for example from a partner's phone.
    var runningElsewhereStep: Int? {
        guard run == nil, let step = status?.extra?.programStep, step > 0 else { return nil }
        return step
    }

    init(_ saved: SavedBedJet, isDemo: Bool, app: AppModel) {
        id = saved.id
        self.isDemo = isDemo
        unit = saved.unit
        name = saved.name
        nickname = saved.nickname
        knownGeneration = saved.generation
        tonightID = saved.tonightID
        run = saved.run.flatMap { $0.endsAt > Date() ? $0 : nil }
        slot = saved.slot
        // Only a timer Lull finished setting is kept to; one it was still reading back when Lull closed is left alone.
        timerEnds = Self.plausible(saved.timerEnds)
        longestEnds = timerEnds == nil ? nil : Self.plausible(saved.longestEnds)
        wifiNetwork = saved.wifiNetwork
        self.app = app
        loadHistory()
        if let ended = saved.run, run == nil {
            record(.scheduleEnded(name: ended.schedule.name, ending: .finished), at: ended.endsAt)
        }
    }

    // MARK: Connection

    /// Starts talking to the BedJet. Only the one on screen asks another phone to hand it over;
    /// the rest connect whenever they are free.
    func connect(asking: Bool) {
        link?.stop()
        status = nil
        settings = nil
        firmware = nil
        deviceSlots = []
        guard let app else { return }
        let link = app.makeLink(for: self)
        // Never hand the BedJet to another phone halfway through saving a schedule, while it uses its Wi-Fi for this
        // phone, or while this phone waits for it to restart into new firmware: the other phone would otherwise take it
        // as it comes back, and this one would never hear how the update went.
        (link as? BLELink)?.canYield = { [weak self] in
            guard let self else { return true }
            return !isBusy && wifiActivity?.isRunning != true && !expectsRestart
        }
        link.onStateChange = { [weak self] state in self?.linkStateChanged(state) }
        link.onStatus = { [weak self] status in self?.statusChanged(status) }
        self.link = link
        linkState = link.state
        lastRefresh = .distantPast
        resume(asking: asking)
    }

    /// The BedJet talks to one phone at a time, so it is released whenever Lull leaves the screen.
    /// Holding on to it would make coming back instant, but a phone that is off screen cannot be
    /// relied on to hear another phone asking for the BedJet.
    func pause() {
        link?.stop()
        // A change still settling would otherwise wait until Lull is opened again.
        if settling != nil { commitObserved() }
        saveHistory()
    }

    func resume(asking: Bool) {
        if !asking, let ble = link as? BLELink {
            ble.listen()
        } else {
            link?.start()
        }
        restartSearchTimer()
    }

    private func restartSearchTimer() {
        searchTimer?.cancel()
        searchTimer = nil
        isSlowToFind = false
        timeSearch()
    }

    /// Counts how long Lull has been looking, across searching and connecting, but not while
    /// another phone has the BedJet or Bluetooth is unavailable, which already say what is wrong.
    private func timeSearch() {
        guard linkState == .searching || linkState == .connecting else {
            searchTimer?.cancel()
            searchTimer = nil
            isSlowToFind = false
            return
        }
        guard searchTimer == nil else { return }
        searchTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            // A BedJet restarts to check for firmware and again to install it, which can take ten minutes, and the tips
            // would suggest unplugging it partway through, so they wait for as long as it's expected back.
            while self?.expectsRestart == true, !Task.isCancelled { try? await Task.sleep(for: .seconds(1)) }
            guard !Task.isCancelled else { return }
            self?.isSlowToFind = true
        }
    }

    private func linkStateChanged(_ state: LinkState) {
        linkState = state
        timeSearch()
        // Gone, so restarted, or about to come back from one.
        if state != .ready { restartsToInstall = false }
        guard state == .ready, let link else { return }
        connectedAt = Date()
        knownGeneration = link.generation
        app?.save()
        logConnection()
        // Phones take turns with the BedJet, so reconnecting is routine; what is saved on it
        // only needs re-reading now and then.
        guard Date().timeIntervalSince(lastRefresh) > 300 else { return }
        Task {
            // Anything the person tapped while connecting goes first.
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            // Setting the clock is a command, so it waits while the BedJet uses its Wi-Fi.
            while (isBusy || reportsWiFiUnderWay) && link.state == .ready { try? await Task.sleep(nanoseconds: 200_000_000) }
            guard link.state == .ready else { return }
            do {
                try await link.syncClock()
                name = try await link.deviceName()
                app?.save()
                let previous = firmware
                firmware = try await link.firmwareVersion()
                if wifiActivity?.awaitsRestart == true, let firmware { finishInstalling(on: firmware, from: previous) }
                try await refreshSlots()
                lastRefresh = Date()
            } catch {
                // Status keeps streaming without these; they are retried on the next connection.
            }
        }
    }

    private func statusChanged(_ status: DeviceStatus) {
        self.status = status
        statusReceivedAt = Date()
        if let extra = status.extra { settings = extra }
        observe(status)
        noticeWiFiPhase(status)
        noticeUpdate(status)
        guard let run else { return }
        let settled = Date().timeIntervalSince(run.startedAt) > 15
        let programStopped = status.mode == .standby && status.extra?.programStep == 0
        guard Date() >= run.endsAt || (settled && programStopped) else { return }
        self.run = nil
        // Lull lets go of the BedJet overnight, so it only learns about an early stop on the next connection.
        // Nothing on this phone asked for it, so say so and keep the unit's reason code for diagnosis.
        let minutesEarly = Int(run.endsAt.timeIntervalSinceNow / 60)
        guard !isBusy else { return }
        if minutesEarly > 2 {
            record(.scheduleEnded(name: run.schedule.name, ending: .early(minutes: minutesEarly)), noticedLater: isJustConnected)
        } else {
            record(.scheduleEnded(name: run.schedule.name, ending: .finished), at: min(run.endsAt, Date()))
        }
        if minutesEarly > 2 {
            app?.report("Your BedJet turned off \(Format.duration(minutesEarly)) before tonight's schedule ended "
                + "(BedJet reason \(status.lastShutdownReason)).", from: self)
        }
    }

    private func refreshSlots() async throws {
        guard let link else { return }
        deviceSlots = try await link.storedPrograms()
        // Keep a copy of programs made elsewhere so reusing a slot never loses them.
        for (index, program) in deviceSlots.enumerated() where index != slot {
            guard let program else { continue }
            app?.keepCopy(of: program)
        }
    }

    // MARK: Tonight

    func startTonight() async {
        guard let schedule = tonight, let link else { return }
        isSavingSchedule = true
        defer { isSavingSchedule = false }
        await perform {
            // Known for certain once connected, which `perform` waited for; checked before anything is written.
            if let problem = self.problem(running: schedule) { throw BedJetError.invalidProgram(problem) }
            // A V2 holds just the one sequence, which Lull always replaces.
            let slot: Int
            if link.generation == .v2 {
                slot = 0
            } else {
                if self.deviceSlots.isEmpty { try await self.refreshSlots() }
                guard let free = self.slot ?? self.deviceSlots.firstIndex(of: nil) else {
                    self.needsSlotChoice = true
                    return
                }
                slot = free
                self.slot = slot
            }
            try await link.store(schedule.program, slot: slot)
            try await link.startProgram(slot: slot)
            try await self.confirmStarted()
            self.run = ActiveRun(schedule: schedule, startedAt: Date())
            self.record(.scheduleStarted(name: schedule.name, phases: schedule.phases))
            if let demo = link as? DemoLink, AppModel.demoElapsedMinutes > 0 {
                demo.skipAhead(minutes: AppModel.demoElapsedMinutes)
                self.run?.startedAt = Date().addingTimeInterval(TimeInterval(-AppModel.demoElapsedMinutes * 60))
            }
            try? await self.refreshSlots()
        }
    }

    /// A BedJet that can't run a schedule stays off and raises a notification instead of starting it.
    private func confirmStarted() async throws {
        guard let link else { return }
        for _ in 0..<20 {
            try await Task.sleep(nanoseconds: 200_000_000)
            guard let extra = status?.extra else { continue }
            if extra.programStep > 0 { return }
            let refusal: BedJetError? = switch extra.notification {
            case Codec.Notification.programNeedsClock: .clockNotSet
            case Codec.Notification.programTooLong: .programTooLong
            default: nil
            }
            if let refusal {
                try? await link.acknowledgeNotification()
                throw refusal
            }
        }
    }

    func startTonight(replacingSlot slot: Int) async {
        self.slot = slot
        await startTonight()
    }

    func endNight() async {
        guard let link else { return }
        let name = run?.schedule.name
        await perform {
            try await link.setMode(.standby)
            self.run = nil
            self.forgetRemoteTimer()
            self.record(.scheduleEnded(name: name, ending: .byYou))
        }
    }

    // MARK: Remote

    func setMode(_ mode: Mode) async {
        guard let link else { return }
        // Captured first: the BedJet's report that the schedule stopped can clear `run` before this returns, and a
        // time picked while another phone hands the BedJet over comes after this tap.
        let endingSchedule = run?.schedule.name
        let asked = Date()
        await perform {
            // Until the new mode is given its own run, a fan or temperature change leaves the timer alone.
            self.forgetRemoteTimer()
            try await link.setMode(mode)
            self.run = nil
            if let endingSchedule { self.record(.scheduleEnded(name: endingSchedule, ending: .byYou)) }
            if mode != .standby { try await self.extendRuntime(entering: mode, askedAt: asked) }
        }
    }

    func setTemperature(_ halfCelsius: Int) async {
        guard let link else { return }
        let asked = Date()
        await perform {
            let keepsTimer = self.keepsTimer()
            if keepsTimer { self.readTimerBack() }
            try await link.setTemperature(halfCelsius)
            if keepsTimer { try await self.extendRuntime(askedAt: asked) }
        }
    }

    func setFan(percent: Int) async {
        guard let link else { return }
        let asked = Date()
        await perform {
            let keepsTimer = self.keepsTimer()
            if keepsTimer { self.readTimerBack() }
            try await link.setFan(step: percent / 5 - 1)
            if keepsTimer { try await self.extendRuntime(askedAt: asked) }
        }
    }

    func setRuntime(minutes: Int) async {
        guard let link else { return }
        // From now on the timer counts down as it is: nothing on this phone changes it until a mode is chosen.
        longestEnds = nil
        timePickedAt = Date()
        await perform { try await link.setRuntime(minutes: minutes) }
    }

    /// Whether changing the temperature or fan should keep the timer to the end Lull gave it. Asked before the change
    /// is sent, since in Heat a hotter setting can cut the BedJet's timer by itself. A timer set from another phone or
    /// the BedJet's own remote since Lull last set it is theirs, and Lull stops keeping to it from then on.
    ///
    /// While a change is sent, `timerEnds` is cleared: the BedJet may cut its own timer for it, so the end is read
    /// back afterwards. If a newer slider move replaced that change first, or two changes overlapped, the timer is
    /// still Lull's, and the next change reads it back.
    private func keepsTimer() -> Bool {
        guard longestEnds != nil else { return false }
        guard let timerEnds else {
            // A change cleared it long ago and never read it back, so where the timer is now isn't known.
            guard Date().timeIntervalSince(readingTimerBackSince) < 10 else {
                forgetRemoteTimer()
                return false
            }
            return true
        }
        // Only a report from this connection says where the timer is now.
        guard let status, status.isRunning, let statusReceivedAt, statusReceivedAt > connectedAt else { return false }
        let unitEnds = statusReceivedAt.addingTimeInterval(TimeInterval(status.remainingSeconds))
        guard abs(unitEnds.timeIntervalSince(timerEnds)) < 60 else {
            longestEnds = nil
            return false
        }
        return true
    }

    /// Air started from the Remote runs for as long as the BedJet allows, until a time is picked. Changing the
    /// temperature or fan keeps to the end the mode's longest run was given: it never adds time, and gives back
    /// time the BedJet cut short for a hotter setting once the setting allows. Schedules keep their own timing.
    /// `asked` is when the person made the change, so a time picked after it wins.
    private func extendRuntime(entering mode: Mode? = nil, askedAt asked: Date) async throws {
        // A schedule started from another phone times its own phases; leave its timer alone.
        guard run == nil, runningElsewhereStep == nil, let link else {
            forgetRemoteTimer()
            return
        }
        // The limit depends on mode, temperature and fan, and the unit keeps the previous timer when
        // the mode changes, so wait for it to report the new state before reading the limit.
        try await Task.sleep(nanoseconds: 600_000_000)
        for _ in 0..<20 {
            if let status, status.isRunning, status.maxRuntimeMinutes > 0, mode == nil || status.mode == mode {
                // A time picked while this waited wins.
                guard timePickedAt < asked else { return }
                let longest = Date().addingTimeInterval(TimeInterval(status.maxRuntimeMinutes * 60))
                if mode != nil { longestEnds = longest }
                guard let longestEnds else { return }
                let minutes = min(max(Int((min(longestEnds, longest).timeIntervalSinceNow / 60).rounded()), 1), status.maxRuntimeMinutes)
                var remaining = status.remainingSeconds
                if abs(remaining - minutes * 60) > 60 {
                    try await link.setRuntime(minutes: minutes)
                    remaining = minutes * 60
                }
                timerEnds = Date().addingTimeInterval(TimeInterval(remaining))
                return
            }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        // The BedJet never reported the air running, so where its timer is isn't known.
        forgetRemoteTimer()
    }

    /// Lets the BedJet move its timer for a fan or temperature change, to be read back once it reports the new limit.
    private func readTimerBack() {
        timerEnds = nil
        readingTimerBackSince = Date()
    }

    /// Stops keeping the Remote's timer to an end, until a mode is next chosen.
    private func forgetRemoteTimer() {
        longestEnds = nil
        timerEnds = nil
    }

    /// A saved end that Lull could have set: still ahead, and no further off than the BedJet's longest run, so a
    /// damaged file can't send a timer the BedJet doesn't allow.
    private static func plausible(_ end: Date?) -> Date? {
        guard let end, end > Date(), end < Date().addingTimeInterval(13 * 3600) else { return nil }
        return end
    }

    func setLED(_ on: Bool) async {
        guard let link else { return }
        await perform { try await link.setLED(on) }
    }

    func setMuted(_ muted: Bool) async {
        guard let link else { return }
        await perform { try await link.setMuted(muted) }
    }

    private func perform(_ work: () async throws -> Void) async {
        // Widgets, controls and Shortcuts reach here too, so this is checked here and not only by the buttons.
        guard !isUsingWiFi else {
            app?.report("Your BedJet is setting up Wi-Fi or updating its firmware. Try again once it's done.", from: self)
            return
        }
        isBusy = true
        defer {
            isBusy = false
            lastCommandAt = Date()
        }
        do {
            try await takeControl()
            try await work()
        } catch is CancellationError {
            // A newer slider move replaced this one; nothing went wrong.
        } catch {
            app?.report(error.localizedDescription, from: self)
        }
    }

    /// Using a control while another phone has the BedJet asks that phone to hand it over first.
    private func takeControl() async throws {
        guard let link, link.state != .ready else { return }
        let wasElsewhere = link.state == .yielded
        link.requestControl()
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
            if link.state == .ready { return }
        }
        throw wasElsewhere ? BedJetError.inUseElsewhere : BedJetError.notConnected
    }

    // MARK: Wi-Fi and firmware

    /// Connected with the air off and no schedule running, so restarting the BedJet interrupts nothing.
    var isIdle: Bool { isConnected && status?.mode == .standby && run == nil && runningElsewhereStep == nil }
    /// Updating restarts the BedJet, so it waits until that interrupts nothing.
    var canUpdateFirmware: Bool { isIdle && !isBusy && !isUsingWiFi }
    var canJoinWiFi: Bool { isConnected && !isUsingWiFi }
    /// The BedJet is away because it's checking for or installing firmware, and will be back.
    var expectsRestart: Bool {
        guard let wifiActivity, wifiActivity.purpose == .updating else { return false }
        return wifiActivity.isRunning || (wifiActivity.awaitsRestart && !wifiActivity.restartOverdue)
    }
    /// Away while it restarts, and only that: Bluetooth being off or another phone having it still say so.
    var isRestarting: Bool { expectsRestart && (linkState == .searching || linkState == .connecting) }
    /// The BedJet is joining Wi-Fi, checking for firmware or installing it, or is about to restart for it, whoever
    /// asked: this phone, another one, the official app or the BedJet itself. Nothing else is sent to it meanwhile:
    /// the restart would cut off a command or a schedule being saved, and a night started now would end with it,
    /// while a second check or network could restart it partway through installing.
    var isUsingWiFi: Bool { wifiActivity?.isRunning == true || expectsRestart || reportsWiFiUnderWay }

    /// What the BedJet's own report says. A step that hasn't moved on in ten minutes, the longest a check takes, is
    /// stuck rather than under way. Restarting to install only lasts until the BedJet does restart, since that
    /// result may still be showing once it's back.
    private var reportsWiFiUnderWay: Bool {
        guard let phase = settings?.wifiPhase, Date().timeIntervalSince(wifiPhaseSince) < 600 else { return false }
        if phase == Codec.WiFiPhase.installing { return restartsToInstall }
        return phase != Codec.WiFiPhase.idle && !Codec.WiFiPhase.isResult(phase)
    }

    private func noticeWiFiPhase(_ status: DeviceStatus) {
        guard let phase = status.extra?.wifiPhase, phase != lastWiFiPhase else { return }
        lastWiFiPhase = phase
        wifiPhaseSince = Date()
        restartsToInstall = phase == Codec.WiFiPhase.installing
    }

    /// Gives the BedJet the Wi-Fi network it fetches firmware over. Lull remembers the name, to show it, but never
    /// the password.
    func joinWiFi(network: String, password: String) async {
        guard canJoinWiFi else { return }
        await useWiFi(.joining) { link, progress in
            try await link.joinWiFi(network: network, password: password, progress: progress)
            self.wifiNetwork = network
            return "Your BedJet joined “\(network)” and can now check for firmware updates."
        }
    }

    /// Asks the BedJet to check for newer firmware, which it installs straight away. `offered` when the person
    /// accepted the BedJet's offer of an update, so how it went is said the same way once it's over.
    func updateFirmware(offered: Bool = false) async {
        // The air may have been turned on, or a schedule started, while the person was confirming.
        guard canUpdateFirmware else { return }
        // Asked once a launch: whether this works or not, the update waits in the BedJet sheet from now on.
        firmwareUpdateDeferred = true
        await useWiFi(.updating, offered: offered) { link, progress in
            switch try await link.updateFirmware(progress: progress) {
            case .upToDate:
                return "Your BedJet's firmware is up to date."
            case .installing:
                // Read again once it's back, to show the new version.
                self.lastRefresh = .distantPast
                self.wifiActivity?.awaitsRestart = true
                self.stopExpectingRestartLater()
                return "Your BedJet is installing the update and restarts when it's done. Keep it plugged in."
            case .updated(let version):
                self.firmware = version
                self.wifiActivity?.installed = version
                return "Your BedJet updated its firmware to \(version)."
            }
        }
    }

    private func useWiFi(_ purpose: WiFiActivity.Purpose, offered: Bool = false,
                         _ work: (BedJetLink, (Int) -> Void) async throws -> String) async {
        guard let link, wifiActivity?.isRunning != true else { return }
        wifiActivity = WiFiActivity(purpose: purpose, step: purpose == .updating ? "Asking your BedJet to check…" : "Asking your BedJet…",
                                    offered: offered)
        do {
            let outcome = try await work(link) { phase in
                if let step = Self.step(phase) { self.wifiActivity?.step = step }
            }
            wifiActivity?.outcome = outcome
        } catch {
            wifiActivity?.outcome = error.localizedDescription
            wifiActivity?.failed = true
            wifiActivity?.awaitsRestart = false
        }
    }

    /// The BedJet is back from restarting after it began installing, on this firmware.
    private func finishInstalling(on firmware: String, from previous: String?) {
        // Worded as when the update is over before the link is lost, if the version did change.
        if previous != nil, firmware != previous {
            wifiActivity?.outcome = "Your BedJet updated its firmware to \(firmware)."
            wifiActivity?.installed = firmware
        } else {
            wifiActivity?.outcome = "Your BedJet restarted with firmware \(firmware)."
        }
        wifiActivity?.awaitsRestart = false
    }

    /// A BedJet not back ten minutes after it began installing isn't restarting any more, so from then on it's
    /// said to be away like any other, with the tips for finding it. Its version is still read if it does come back.
    private func stopExpectingRestartLater() {
        guard let startedAt = wifiActivity?.startedAt else { return }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(600))
            guard let self, wifiActivity?.startedAt == startedAt, wifiActivity?.awaitsRestart == true else { return }
            wifiActivity?.restartOverdue = true
        }
    }

    /// What the BedJet is doing at each step it reports while it uses its Wi-Fi.
    private static func step(_ phase: Int) -> String? {
        switch phase {
        case Codec.WiFiPhase.starting: "Starting…"
        case Codec.WiFiPhase.joining: "Joining Wi-Fi…"
        case Codec.WiFiPhase.joined: "Connected to Wi-Fi…"
        case Codec.WiFiPhase.testing: "Testing the connection…"
        case Codec.WiFiPhase.checking: "Checking for an update…"
        case Codec.WiFiPhase.updating: "Installing the update…"
        case Codec.WiFiPhase.restarting: "Restarting your BedJet to check…"
        default: nil
        }
    }

    /// A BedJet 3 on Wi-Fi checks for firmware by itself now and then, and says when there's newer firmware until
    /// that's acknowledged. Lull leaves it set: the BedJet stops saying so once it's updated, and other phones and
    /// the official app may still want to show it.
    private func noticeUpdate(_ status: DeviceStatus) {
        switch status.extra?.notification {
        case Codec.Notification.updateAvailable?: updateAvailable = true
        case 0?: updateAvailable = false
        default: break
        }
    }

    enum FirmwarePrompt: Equatable {
        /// The BedJet says newer firmware is available.
        case available
        /// How an update the person accepted from the offer went.
        case finished(WiFiActivity)
    }

    /// What to ask or tell the person about firmware now. An offered update waits until restarting the BedJet would
    /// interrupt nothing, and is asked about once; put off, it waits in the BedJet sheet.
    var firmwarePrompt: FirmwarePrompt? {
        if let wifiActivity, wifiActivity.offered, !wifiActivity.isRunning, !wifiActivity.awaitsRestart { return .finished(wifiActivity) }
        guard generation == .v3, updateAvailable, !firmwareUpdateDeferred, canUpdateFirmware else { return nil }
        return .available
    }

    func deferFirmwareUpdate() {
        firmwareUpdateDeferred = true
    }

    /// Closes the report of how an offered update went; it stays in the BedJet sheet.
    func dismissFirmwareResult() {
        wifiActivity?.offered = false
    }

    /// Launching with `-logConnections` keeps a record of connection times, so they can be measured on a real phone.
    private static let logsConnections = ProcessInfo.processInfo.arguments.contains("-logConnections")

    private func logConnection() {
        guard Self.logsConnections, let timing = (link as? BLELink)?.lastConnection else { return }
        let line = String(format: "new connection: radio %.0f, link %.0f, services %.0f, usable %.0f ms, %@ (%@)\n",
                          timing.radioOn * 1000, timing.linkUp * 1000, timing.services * 1000, timing.usable * 1000,
                          timing.knownUnit ? "known unit" : "scanned", name ?? "unnamed")
        log(line)
    }

    private func log(_ line: String) {
        let file = URL.documentsDirectory.appending(path: "connections.txt")
        let existing = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        try? (existing + Date().formatted(date: .omitted, time: .standard) + " " + line).write(to: file, atomically: true, encoding: .utf8)
    }

    // MARK: History

    /// The settings worth remembering from a status report. The timer is carried along to tell when it ran out.
    private struct Seen: Codable, Equatable {
        var mode: Mode
        var temperature: Int
        var fanPercent: Int
        var programStep: Int
        var remainingSeconds: Int
        var at: Date

        init(_ status: DeviceStatus, programStep: Int) {
            mode = status.mode
            temperature = status.targetTemperature
            fanPercent = status.fanPercent
            self.programStep = programStep
            remainingSeconds = status.remainingSeconds
            at = Date()
        }

        func sameSettings(as other: Seen) -> Bool {
            mode == other.mode && fanPercent == other.fanPercent && programStep == other.programStep
                && (!mode.usesTemperature || temperature == other.temperature)
        }
    }

    /// The first reports after connecting describe the BedJet as it was left, not something that just happened.
    private var isJustConnected: Bool { Date().timeIntervalSince(connectedAt) < 10 }

    /// Waits for the BedJet to hold a setting for a few seconds, so dragging a slider is one entry, not twenty.
    private func observe(_ status: DeviceStatus) {
        // Whether a schedule is running arrives in the second half of the report; without it a phase
        // change can't be told from a change someone made.
        guard let step = status.extra?.programStep else { return }
        let seen = Seen(status, programStep: step)
        let isNew = observed.map { !seen.sameSettings(as: $0) } ?? true
        observed = seen
        if let lastSeen, seen.sameSettings(as: lastSeen) {
            settling?.cancel()
            settling = nil
            self.lastSeen = seen
            return
        }
        guard isNew || settling == nil else { return }
        settling?.cancel()
        settling = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.commitObserved()
        }
    }

    private func commitObserved() {
        settling?.cancel()
        settling = nil
        guard let now = observed else { return }
        // The first report ever only sets where the history starts from.
        guard let before = lastSeen else {
            lastSeen = now
            return
        }
        lastSeen = now
        let wasAway = before.at < connectedAt && isJustConnected
        switch (before.programStep, now.programStep) {
        case (0, 1...):
            // Started from this phone, it is recorded once the BedJet confirms it.
            if run == nil, !isBusy { record(.scheduleStarted(name: nil, phases: nil), noticedLater: wasAway) }
            return
        // The schedule moved on to its next phase, or ended; its start and end are recorded on their own.
        case (1..., 1...) where before.programStep != now.programStep: return
        case (1..., 0) where now.mode == .standby: return
        default: break
        }
        if now.mode == .standby {
            guard before.mode != .standby else { return }
            let timerEnds = before.at.addingTimeInterval(TimeInterval(before.remainingSeconds))
            let ranOut = lastCommandAt < before.at && (before.remainingSeconds < 120 || (wasAway && timerEnds < Date()))
            record(.off(timerRanOut: ranOut), at: ranOut ? min(timerEnds, Date()) : Date(), noticedLater: wasAway && !ranOut)
        } else {
            record(.air(mode: now.mode, temperature: now.temperature, fanPercent: now.fanPercent), noticedLater: wasAway)
        }
    }

    private func record(_ event: HistoryEntry.Event, at date: Date = Date(), noticedLater: Bool = false) {
        history.append(HistoryEntry(date: date, event: event, room: status?.ambientTemperature, noticedLater: noticedLater))
        if let previous = history.dropLast().last, previous.date > date { history.sort { $0.date < $1.date } }
        // Two months is plenty to compare one season's nights with the next.
        let cutoff = Date().addingTimeInterval(-60 * 86400)
        history.removeAll { $0.date < cutoff }
        saveHistory()
    }

    private struct SavedHistory: Codable {
        var entries: [HistoryEntry]
        var lastSeen: Seen?
    }

    /// Each BedJet keeps its own history, and demo BedJets are separate ones, so pretend nights never mix with real ones.
    private var historyFile: URL? { app?.storage.historyFile(for: id) }

    private func loadHistory() {
        let saved = historyFile.flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONDecoder().decode(SavedHistory.self, from: $0) }
        history = saved?.entries ?? (AppModel.seedsDemoHistory ? HistoryEntry.samples() : [])
        lastSeen = saved?.lastSeen
    }

    private func saveHistory() {
        app?.publishWidgets()
        guard let file = historyFile else { return }
        let saved = SavedHistory(entries: history, lastSeen: lastSeen)
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(saved).write(to: file, options: .atomic)
    }

    // MARK: Schedules

    /// Modes the Remote and the phase editor offer for this kind of BedJet.
    var remoteModes: [Mode] { Mode.remoteModes.filter(generation.supports) }
    var phaseModes: [Mode] { Mode.phaseModes.filter(generation.supports) }

    func adjustsTemperature(_ mode: Mode) -> Bool { generation.adjustsTemperature(mode) }

    /// Modes in a schedule that this BedJet can't run. Schedules are shared between BedJets, so one made for a
    /// BedJet 3 may not suit a V2; it is never changed to fit, only refused.
    func unsupportedModes(in schedule: Schedule) -> [Mode] {
        var modes: [Mode] = []
        for phase in schedule.phases where !generation.supports(phase.mode) && !modes.contains(phase.mode) { modes.append(phase.mode) }
        return modes
    }

    /// Why a schedule can't run on this BedJet, if it can't.
    func problem(running schedule: Schedule) -> String? {
        let modes = unsupportedModes(in: schedule)
        guard !modes.isEmpty else { return nil }
        let names = ListFormatter.localizedString(byJoining: modes.map(\.title))
        return "\(displayName) is a BedJet V2, which can't run \(names). Edit “\(schedule.name)” or pick another schedule for it."
    }
}
