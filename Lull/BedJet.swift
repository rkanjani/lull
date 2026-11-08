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
    private(set) var statusReceivedAt: Date?
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
    /// Its Live Activity holds still while this is set, and moves on to the night as soon as it clears.
    private(set) var isSavingSchedule = false { didSet { app?.publishLiveActivities() } }
    /// Set when every slot on the BedJet is taken and the user has to pick one to reuse.
    var needsSlotChoice = false

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
    /// When this phone last turned the BedJet off, to tell a report sent just before that from one sent after.
    private var turnedOffAt: Date?
    /// How long after Off a report saying the BedJet is still running is taken to have been sent before it.
    static var staleReportWindow: TimeInterval = 2

    var saved: SavedBedJet {
        SavedBedJet(id: id, unit: unit, name: name, nickname: nickname, generation: knownGeneration, tonightID: tonightID, run: run, slot: slot,
                    longestEnds: longestEnds, timerEnds: timerEnds)
    }

    var tonight: Schedule? {
        guard let schedules = app?.schedules else { return nil }
        return schedules.first { $0.id == tonightID } ?? schedules.first
    }

    var isConnected: Bool { linkState == .ready }
    /// Controls stay live while connecting or while another phone has the BedJet: what is tapped
    /// is sent the moment the connection is up.
    var canControl: Bool { linkState != .bluetoothOff && linkState != .unauthorized }
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
        firmware = nil
        deviceSlots = []
        guard let app else { return }
        let link = app.makeLink(for: self)
        // Never hand the BedJet to another phone halfway through saving a schedule.
        (link as? BLELink)?.canYield = { [weak self] in self?.isBusy != true }
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
            guard !Task.isCancelled else { return }
            self?.isSlowToFind = true
        }
    }

    private func linkStateChanged(_ state: LinkState) {
        linkState = state
        timeSearch()
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
            while isBusy { try? await Task.sleep(nanoseconds: 200_000_000) }
            guard link.state == .ready else { return }
            do {
                try await link.syncClock()
                name = try await link.deviceName()
                app?.save()
                firmware = try await link.firmwareVersion()
                try await refreshSlots()
                lastRefresh = Date()
            } catch {
                // Status keeps streaming without these; they are retried on the next connection.
            }
        }
    }

    private func statusChanged(_ status: DeviceStatus) {
        // The BedJet reports every second, so a report it sent just before it took Off can arrive just after. Shown, it
        // would put the air back on outside Lull, where it stays if Lull lets go of the BedJet before the next report.
        if let turnedOffAt, status.isRunning, Date().timeIntervalSince(turnedOffAt) < Self.staleReportWindow { return }
        turnedOffAt = nil
        self.status = status
        statusReceivedAt = Date()
        observe(status)
        app?.publishLiveActivities()
        app?.publishWidgets()
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
            // The BedJet only says it's off in its next report, and Lull may have let go of it by then, leaving it shown
            // as running outside Lull. The BedJet took the command, so it is off already; setting `run` publishes it.
            if mode == .standby { self.status?.mode = .standby }
            self.turnedOffAt = mode == .standby ? Date() : nil
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

    // MARK: From outside the app

    /// How long a widget, control or Siri waits for the BedJet to connect by itself before leaving it to the person.
    static var freeWait: TimeInterval = 15

    /// Sets the BedJet to a Quick Remote button's preset, as choosing its mode, temperature, fan and time on the
    /// Remote would. Only if it's free (`reachesIfFree`); otherwise Lull, open on the Remote, leaves it to the person.
    func applyPreset(_ preset: RemotePreset) async {
        // A widget can be set to anything, and Lull may have been opened by something else claiming to be one.
        let preset = preset.clamped
        guard await reachesIfFree() else { return }
        // The Remote never offers a mode this kind of BedJet lacks, so a button set up for another kind does nothing.
        // Known for certain now that it's connected.
        guard preset.mode == .standby || generation.supports(preset.mode) else { return }
        // Like choosing the mode on the Remote: a schedule this phone started on this BedJet ends.
        await setMode(preset.mode)
        guard preset.mode != .standby else { return }
        // Each step only while the BedJet is still this phone's: one handed to another phone meanwhile isn't asked back.
        if adjustsTemperature(preset.mode), isConnected { await setTemperature(preset.temperature) }
        if preset.mode.usesFan, isConnected { await setFan(percent: preset.fanPercent) }
        // Last: the mode, temperature and fan each stretch the timer to the longest the BedJet allows.
        if isConnected { await setRuntime(minutes: preset.minutes) }
    }

    /// Starts tonight from outside the app, only if the BedJet is free (`reachesIfFree`). A night already under way,
    /// from this phone or another, is left running and shown.
    func startTonightIfFree() async {
        guard run == nil, await reachesIfFree(), run == nil else { return }
        await startTonight()
    }

    /// Whether a widget, control or Siri may set the BedJet. A tap on one is easily made by accident, on what the
    /// widget last showed, perhaps hours ago, so it never asks another phone to hand the BedJet over, as a control in
    /// Lull does: it waits for the BedJet to connect by itself and say what it's doing. One another phone has, or
    /// running a schedule another phone started, is left as it is, shown in Lull for the person to decide.
    private func reachesIfFree() async -> Bool {
        let deadline = Date().addingTimeInterval(Self.freeWait)
        while Date() < deadline, !Task.isCancelled {
            guard linkState != .yielded else { return false }
            // Only a report from this connection says what the BedJet is doing now.
            if isConnected, let statusReceivedAt, statusReceivedAt > connectedAt {
                return !isBusy && runningElsewhereStep == nil
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return false
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
