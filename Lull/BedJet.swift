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
    var remoteTimer: RemoteTimer?
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
    private(set) var statusReceivedAt: Date?
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
    /// Commands under way. More than one can be, such as a Remote change while an action from Shortcuts runs.
    private var commandsUnderWay = 0
    var isBusy: Bool { commandsUnderWay > 0 }
    /// Commands under way that end the night, which then record it as ended by the person (`endingTheNight`).
    @ObservationIgnored private var nightsBeingEnded = 0
    /// Set as Lull lets go of a connected BedJet, until the link says it has. Over Bluetooth that takes a moment, and until
    /// then the link still says it's ready, though nothing sent on it arrives.
    @ObservationIgnored private var isLettingGo = false
    /// A schedule is being written to the BedJet, which takes several steps.
    /// Its Live Activity holds still while this is set, and moves on to the night as soon as it clears.
    private(set) var isSavingSchedule = false { didSet { app?.publishLiveActivities() } }
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
    /// How air from the Remote is timed. Schedules keep their own timing. Saved, so a time picked there holds after the
    /// phone closes Lull while the air runs.
    private(set) var remoteTimer = RemoteTimer.longest { didSet { if remoteTimer != oldValue { app?.save() } } }
    /// When the longest run Lull gave the air from the Remote ends, which changing the temperature or fan keeps to.
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
    /// The Wi-Fi phase the BedJet last reported, and since when, to tell a step under way from one left stuck.
    private var lastWiFiPhase: Int?
    private var wifiPhaseSince = Date.distantPast
    /// The BedJet reported it's restarting to install firmware, and hasn't restarted since.
    private var restartsToInstall = false
    /// The connection the BedJet began installing firmware on, which ends with its restart.
    private var installBeganOn = Date.distantPast
    /// How long a BedJet installing an update is expected back from restarting. Tests wait less.
    static var restartPatience: TimeInterval = 600

    var saved: SavedBedJet {
        SavedBedJet(id: id, unit: unit, name: name, nickname: nickname, generation: knownGeneration, tonightID: tonightID, run: run, slot: slot,
                    remoteTimer: remoteTimer, longestEnds: longestEnds, timerEnds: timerEnds, wifiNetwork: wifiNetwork)
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
        remoteTimer = saved.remoteTimer ?? .longest
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
    /// the rest connect whenever they are free. Off screen (`resuming: false`) the link is only set up, to start once
    /// Lull comes on screen or an errand from the watch needs it.
    func connect(asking: Bool, resuming: Bool = true) {
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
        if resuming { resume(asking: asking) }
    }

    /// The BedJet talks to one phone at a time, so it is released whenever Lull leaves the screen, and as soon as
    /// an errand or action that reached it from the background is done. Holding on to it would make coming back
    /// instant, but a phone that is off screen cannot be relied on to hear another phone asking for the BedJet.
    func pause() {
        if linkState == .ready { isLettingGo = true }
        link?.stop()
        // A change still settling would otherwise wait until Lull is opened again.
        if settling != nil { commitObserved() }
        saveHistory()
    }

    func resume(asking: Bool) {
        if asking { link?.start() } else { link?.listen() }
        restartSearchTimer()
    }

    private func restartSearchTimer() {
        searchTimer?.cancel()
        searchTimer = nil
        isSlowToFind = false
        timeSearch()
    }

    /// Whether the BedJet has reported everything it does since `date`, both halves of its report, so what it's doing
    /// now is known rather than what Lull last saw.
    func hasReported(since date: Date) -> Bool {
        guard let statusReceivedAt, statusReceivedAt >= date else { return false }
        return status?.extra != nil
    }

    /// Waits for the BedJet to say what it's doing, so an errand from the watch acts on that. Gives up after `timeout`,
    /// leaving the errand's own commands to say what's wrong, or as soon as the errand is cancelled.
    func awaitReport(since date: Date, timeout: TimeInterval = 10) async {
        let deadline = Date().addingTimeInterval(timeout)
        // A cancelled sleep returns at once, so without the check a cancelled wait would spin until the deadline.
        while Date() < deadline, !hasReported(since: date), !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
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
        if state != .ready { isLettingGo = false }
        timeSearch()
        // Gone, so restarted, or about to come back from one.
        if state != .ready { restartsToInstall = false }
        guard state == .ready, let link else { return }
        connectedAt = Date()
        knownGeneration = link.generation
        app?.save()
        logConnection()
        // Phones take turns with the BedJet, so reconnecting is routine; what is saved on it
        // only needs re-reading now and then, or once it's back from restarting into new firmware.
        guard Date().timeIntervalSince(lastRefresh) > 300 || wifiActivity?.awaitsRestart == true else { return }
        Task {
            // Anything the person tapped while connecting goes first.
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            // Setting the clock is a command, so it waits while the BedJet uses its Wi-Fi.
            while (isBusy || reportsWiFiUnderWay || wifiActivity?.isRunning == true) && link.state == .ready {
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
            // Still on the connection the install began on, so the version is read once it's back from restarting.
            guard link.state == .ready, wifiActivity?.awaitsRestart != true || connectedAt != installBeganOn else { return }
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
        // The BedJet reports every second, so a report it sent just before it took Off can arrive just after. Shown, it
        // would put the air back on outside Lull, where it stays if Lull lets go of the BedJet before the next report.
        if let turnedOffAt, status.isRunning, Date().timeIntervalSince(turnedOffAt) < Self.staleReportWindow { return }
        turnedOffAt = nil
        self.status = status
        statusReceivedAt = Date()
        if let extra = status.extra { settings = extra }
        observe(status)
        noticeWiFiPhase(status)
        noticeUpdate(status)
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
        // Lull ending the night records that itself. Any other command under way, such as an action's or a Remote change
        // made as Lull connects, leaves the night to end as the BedJet says.
        guard nightsBeingEnded == 0 else { return }
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
        // One night at a time: a schedule already being written, as by an action, isn't written over.
        guard let schedule = tonight, link != nil, !isSavingSchedule else { return }
        isSavingSchedule = true
        defer { isSavingSchedule = false }
        await perform { try await self.startSchedule(schedule, choosingSlot: true) }
    }

    /// Saves a schedule into Lull's slot on the BedJet and starts it. When every slot is taken, the person picks
    /// one to reuse; without anyone there to pick, as in an automation, that is an error instead.
    private func startSchedule(_ schedule: Schedule, choosingSlot: Bool) async throws {
        let link = try connectedLink()
        // Demos that show a night in progress pretend it started earlier.
        let elapsed = link is DemoLink ? AppModel.demoElapsedMinutes : 0
        let startedAt = Date().addingTimeInterval(TimeInterval(-elapsed * 60))
        // Known for certain once connected, which `carryOut` waited for; checked before anything is written.
        if let problem = problem(running: schedule, at: startedAt) { throw BedJetError.invalidProgram(problem) }
        // With a wake time, every phase is scaled to end at it from now. A BedJet 3 is still given the wake time itself for
        // the last step, so it keeps to it by its own clock.
        let tonight = schedule.resolved(startingAt: Format.minuteOfDay(startedAt))
        // A V2 holds just the one sequence, which Lull always replaces.
        let slot: Int
        if link.generation == .v2 {
            slot = 0
        } else {
            if deviceSlots.isEmpty { try await refreshSlots() }
            guard let free = self.slot ?? deviceSlots.firstIndex(of: nil) else {
                guard choosingSlot else { throw AutomationError.slotsFull }
                needsSlotChoice = true
                return
            }
            slot = free
            self.slot = slot
        }
        // A command in flight finishes even once it's cancelled, so what comes after it is checked for: an errand
        // iOS ran out of background time for, which may only carry on hours later, writes nothing more.
        try Task.checkCancellation()
        try await link.store(tonight.program, slot: slot)
        try Task.checkCancellation()
        try await link.startProgram(slot: slot)
        try await confirmStarted()
        run = ActiveRun(schedule: tonight, startedAt: startedAt)
        remoteTimer = .longest
        record(.scheduleStarted(name: schedule.name, phases: tonight.phases))
        if elapsed > 0, let demo = link as? DemoLink { demo.skipAhead(minutes: elapsed) }
        try? await refreshSlots()
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
        guard link != nil else { return }
        await perform { try await self.stopNight() }
    }

    private func stopNight() async throws {
        let link = try connectedLink()
        let name = run?.schedule.name
        try await endingTheNight { try await link.setMode(.standby) }
        run = nil
        forgetRemoteTimer()
        record(.scheduleEnded(name: name, ending: .byYou))
    }

    // MARK: Remote

    func setMode(_ mode: Mode) async {
        guard link != nil else { return }
        // Captured first: a time picked while another phone hands the BedJet over comes after this tap.
        let asked = Date()
        // Shown as a time, the end on screen holds across modes: a time picked here, or else the BedJet's own countdown,
        // such as a duration picked here or a timer set on another phone.
        if app?.remoteTimerShowsTime == true, mode != .standby, !remoteTimer.isAhead, run == nil, runningElsewhereStep == nil,
           let status, status.isRunning, status.remainingSeconds > 60 {
            // Counted from when the BedJet said it, so a report from a while ago doesn't move the end later.
            remoteTimer = .until((statusReceivedAt ?? Date()).addingTimeInterval(TimeInterval(status.remainingSeconds)))
        }
        await perform { try await self.changeMode(to: mode, askedAt: asked) }
    }

    func setTemperature(_ halfCelsius: Int) async {
        guard link != nil else { return }
        let asked = Date()
        await perform { try await self.changeTemperature(to: halfCelsius, askedAt: asked) }
    }

    func setFan(percent: Int) async {
        guard link != nil else { return }
        let asked = Date()
        await perform { try await self.changeFan(to: percent, askedAt: asked) }
    }

    func setRuntime(minutes: Int) async {
        guard link != nil else { return }
        await perform { try await self.changeRuntime(to: minutes) }
    }

    private func changeMode(to mode: Mode, askedAt asked: Date) async throws {
        let link = try connectedLink()
        // Captured first: the BedJet's report that the schedule stopped can clear `run` before this returns.
        let endingSchedule = run?.schedule.name
        // Until the new mode is given its own run, a fan or temperature change leaves the timer alone.
        forgetRemoteTimer()
        try await endingTheNight { try await link.setMode(mode) }
        // The BedJet only says it's off in its next report, and Lull may have let go of it by then, leaving it shown
        // as running outside Lull. The BedJet took the command, so it is off already; setting `run` publishes it.
        if mode == .standby { status?.mode = .standby }
        turnedOffAt = mode == .standby ? Date() : nil
        run = nil
        if let endingSchedule { record(.scheduleEnded(name: endingSchedule, ending: .byYou)) }
        // A time picked on the Remote holds across modes until it passes, while the Remote shows the time. Otherwise each
        // mode starts at its longest run, as the timer always has.
        if mode == .standby || !remoteTimer.isAhead || app?.remoteTimerShowsTime != true { remoteTimer = .longest }
        if mode != .standby { try await extendRuntime(entering: mode, askedAt: asked) }
    }

    private func changeTemperature(to halfCelsius: Int, askedAt asked: Date) async throws {
        let link = try connectedLink()
        let keepsTimer = self.keepsTimer()
        if keepsTimer { readTimerBack() }
        try await link.setTemperature(halfCelsius)
        if keepsTimer { try await extendRuntime(askedAt: asked) }
    }

    private func changeFan(to percent: Int, askedAt asked: Date) async throws {
        let link = try connectedLink()
        let keepsTimer = self.keepsTimer()
        if keepsTimer { readTimerBack() }
        try await link.setFan(step: percent / 5 - 1)
        if keepsTimer { try await extendRuntime(askedAt: asked) }
    }

    private func changeRuntime(to minutes: Int) async throws {
        // From now on the timer counts down as it is: nothing on this phone changes it until a mode is chosen.
        remoteTimer = .picked
        timePickedAt = Date()
        try await connectedLink().setRuntime(minutes: minutes)
    }

    /// Turns the air off at `end`, or as near it as the mode allows. Lull keeps aiming at it as the temperature and fan
    /// change, since each changes how long the BedJet lets the air run, and as the mode changes while the Remote shows
    /// the time.
    func setRuntime(until end: Date) async {
        guard let link else { return }
        remoteTimer = .until(end)
        let asked = Date()
        timePickedAt = asked
        await perform {
            // Sent at once, as far as the BedJet last said the mode may run. A hotter setting still on its way can cut
            // that short, so like a fan or temperature change, the timer is read back from the BedJet's next report and
            // aimed again, rather than taken to be what was sent.
            self.readTimerBack()
            let limit = self.status.map { $0.maxRuntimeMinutes > 0 ? $0.maxRuntimeMinutes : $0.mode.maxMinutes } ?? Limits.maxStepMinutes
            try await link.setRuntime(minutes: self.minutes(until: end, limit: limit))
            // Sent, the time is the BedJet's, so moving on from the Remote (switching BedJet, a newer pick) doesn't stop it
            // being read back. Left unread, the next change couldn't tell it from another phone's timer.
            try await Task { try await self.extendRuntime(askedAt: asked) }.value
        }
    }

    /// Minutes from now until `end`, to the nearest minute, as far as `limit` allows.
    private func minutes(until end: Date, limit: Int) -> Int {
        min(max(Int((end.timeIntervalSinceNow / 60).rounded()), 1), limit)
    }

    /// Whether changing the temperature or fan should keep the timer to its end. Asked before the change is sent, since
    /// in Heat a hotter setting can cut the BedJet's timer by itself. A timer set from another phone or the BedJet's own
    /// remote since Lull last set it is theirs, and Lull stops keeping to it from then on.
    ///
    /// While a change is sent, `timerEnds` is cleared: the BedJet may cut its own timer for it, so the end is read
    /// back afterwards. If a newer slider move replaced that change first, or two changes overlapped, the timer is
    /// still Lull's, and the next change reads it back.
    private func keepsTimer() -> Bool {
        guard remoteTimer != .picked else { return false }
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
            remoteTimer = .picked
            return false
        }
        return true
    }

    /// Air started from the Remote runs for as long as the BedJet allows, or until the time picked there, until a
    /// duration is picked instead. Changing the temperature or fan keeps to the end that gives: it never adds time, and
    /// gives back time the BedJet cut short for a hotter setting once the setting allows. Schedules keep their own timing.
    /// Called just after the change is sent. `asked` is when the person made it, so a duration picked after it wins.
    private func extendRuntime(entering mode: Mode? = nil, askedAt asked: Date) async throws {
        // A schedule started from another phone times its own phases; leave its timer alone.
        guard remoteTimer != .picked, run == nil, runningElsewhereStep == nil, let link else {
            forgetRemoteTimer()
            return
        }
        let sent = Date()
        // The limit depends on mode, temperature and fan, and the unit keeps the previous timer when
        // the mode changes, so wait for it to report the new state before reading the limit. Only a report it sent
        // after the change says what it allows now: one from before would aim at a limit it no longer has.
        try await Task.sleep(nanoseconds: 600_000_000)
        for _ in 0..<20 {
            if let status, let statusReceivedAt, statusReceivedAt > sent, status.isRunning, status.maxRuntimeMinutes > 0,
               mode == nil || status.mode == mode {
                // A duration picked while this waited wins. So does a time, which this keeps to as well: it may have
                // cleared the timer that time read back. One picked in the same millisecond as the change came before it,
                // as a phone's clock may not tell them apart.
                guard timePickedAt <= asked || remoteTimer.isAhead else { return }
                if mode != nil, remoteTimer == .longest {
                    longestEnds = Date().addingTimeInterval(TimeInterval(status.maxRuntimeMinutes * 60))
                }
                let end: Date? = switch remoteTimer {
                case .longest: longestEnds
                case .until(let end): end
                case .picked: nil
                }
                guard let end, end > Date() else { return }
                // Capped at the mode's longest run, so a damaged save can't send a timer the BedJet doesn't allow.
                let minutes = minutes(until: end, limit: status.maxRuntimeMinutes)
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

    /// Stops keeping the Remote's timer to an end, until a mode is next chosen or a time picked to turn off at.
    private func forgetRemoteTimer() {
        longestEnds = nil
        timerEnds = nil
        // A time picked to turn off at outlives this, so a change still reading the timer back mustn't aim at it.
        readingTimerBackSince = .distantPast
    }

    /// A saved end that Lull could have set: still ahead, and no further off than the BedJet's longest run, so a
    /// damaged file can't send a timer the BedJet doesn't allow.
    private static func plausible(_ end: Date?) -> Date? {
        guard let end, end > Date(), end < Date().addingTimeInterval(13 * 3600) else { return nil }
        return end
    }

    func setLED(_ on: Bool) async {
        guard link != nil else { return }
        await perform { try await self.connectedLink().setLED(on) }
    }

    func setMuted(_ muted: Bool) async {
        guard link != nil else { return }
        await perform { try await self.connectedLink().setMuted(muted) }
    }

    /// Does what a control asked for, showing anything that goes wrong.
    private func perform(_ work: () async throws -> Void) async {
        // Widgets, controls and the watch reach here too, so this is checked here and not only by the buttons. Actions
        // from Shortcuts check the same in `carryOutAction`, to say so in their result.
        guard !isUsingWiFi else {
            app?.report("Your BedJet is setting up Wi-Fi or updating its firmware. Try again once it's done.", from: self)
            return
        }
        do {
            try await carryOut(work)
        } catch is CancellationError {
            // A newer slider move replaced this one, or the errand it was for ran out of time; nothing went wrong.
        } catch {
            app?.report(error.localizedDescription, from: self)
        }
    }

    /// Takes control of the BedJet and does the work, one thing at a time. What goes wrong is thrown to the caller.
    private func carryOut<Result>(_ work: () async throws -> Result) async throws -> Result {
        try await underWay {
            try await self.takeControl()
            return try await work()
        }
    }

    /// Counts the work as a command under way while it runs, so the BedJet shows as busy.
    private func underWay<Result>(_ work: () async throws -> Result) async throws -> Result {
        commandsUnderWay += 1
        defer {
            commandsUnderWay -= 1
            lastCommandAt = Date()
        }
        return try await work()
    }

    /// Sends what ends any night running. The BedJet's report that its schedule stopped can arrive before the command
    /// returns; that is this command's doing, which the caller records as ended by the person, not an early stop.
    private func endingTheNight(_ send: () async throws -> Void) async throws {
        nightsBeingEnded += 1
        defer { nightsBeingEnded -= 1 }
        try await send()
    }

    /// The link for the next command. A cancelled one sends nothing, such as the fan after the temperature in an action
    /// that ran out of time: a command in flight finishes even once it's cancelled, so each one after it checks.
    private func connectedLink() throws -> BedJetLink {
        try Task.checkCancellation()
        guard let link else { throw BedJetError.notConnected }
        return link
    }

    /// Using a control while another phone has the BedJet asks that phone to hand it over first. A cancelled command,
    /// such as the next step of an errand that ran out of time, sends nothing and asks for nothing.
    private func takeControl() async throws {
        try Task.checkCancellation()
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

    // MARK: Automation

    /// Restarting into new firmware. `expectsRestart` goes by the clock too, so an action that wakes Lull long after an
    /// install doesn't find it still restarting.
    var isInstallingUpdate: Bool { isRestarting }

    /// Waits, for up to `patience`, until a BedJet Lull just let go of has dropped the connection. Shortcuts runs an
    /// action straight after the one before, which let go of the BedJet as it finished; reaching for it before the
    /// connection drops would find the link still ready, and what's sent on it would be lost.
    func waitUntilLetGo(within patience: TimeInterval) async throws {
        let deadline = Date().addingTimeInterval(patience)
        while isLettingGo, Date() < deadline { try await Task.sleep(nanoseconds: 50_000_000) }
    }

    /// Reaches the BedJet for an action while Lull is off screen, but only if it is free: an iPhone can't ask another
    /// phone for it from the background, and an automation nobody is watching shouldn't take it from a partner
    /// anyway. Gives up before the link would scan for the unit, which finds nothing in the background.
    func reachIfFree(within patience: TimeInterval) async throws {
        resume(asking: false)
        let deadline = Date().addingTimeInterval(patience)
        while Date() < deadline {
            switch linkState {
            case .ready: return
            case .yielded, .bluetoothOff, .unauthorized: throw AutomationError.needsLull
            case .searching, .connecting: try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        throw AutomationError.needsLull
    }

    /// Starts a schedule for an action. A night already under way, from this phone or another, is left running. With every
    /// slot taken, an action that went on in Lull asks which to reuse (`choosingSlot`), as Start Night there does.
    func start(_ schedule: Schedule, choosingSlot: Bool = false) async throws -> Started {
        try await carryOutAction {
            if let run = self.run { return .alreadyRunning(run.schedule.name) }
            if self.runningElsewhereStep != nil { return .alreadyRunning(nil) }
            // Only while it is written, so a night left running or the wait for the BedJet doesn't hold anything still.
            self.isSavingSchedule = true
            defer { self.isSavingSchedule = false }
            try await self.startSchedule(schedule, choosingSlot: choosingSlot)
            // Not started yet: Lull is asking which slot to reuse.
            return self.run == nil ? .choosingSlot : .now
        }
    }

    /// Sets the air as the Remote would. With a mode it turns the air on in that mode, ending a schedule that is
    /// running; without one it changes the air that is already on. Everything is checked before anything is sent.
    func apply(_ air: AirSettings, fahrenheit: Bool) async throws -> AppliedAir {
        // Captured first: a time picked while the action reaches the BedJet comes after it.
        let asked = Date()
        return try await carryOutAction {
            let mode = air.mode ?? self.status?.mode ?? .standby
            if let problem = air.mode.flatMap(self.problem(using:)) { throw AutomationError.unsupportedMode(problem) }
            if air.mode == nil {
                guard mode != .standby else { throw AutomationError.airOff }
                if air.degrees != nil, let refusal = Automation.temperatureRefusal(mode: mode, generation: self.generation) { throw refusal }
                // The Remote hides its timer while a schedule runs, since the schedule times its own phases.
                if air.minutes != nil, self.run != nil || self.runningElsewhereStep != nil { throw AutomationError.scheduleSetsTiming }
            }
            var applied = AppliedAir(mode: mode)
            if let wanted = air.mode {
                // Without a run time, Turn On runs as long as the BedJet allows, not until a time once picked on the Remote.
                if air.minutes == nil { self.remoteTimer = .longest }
                try await self.changeMode(to: wanted, askedAt: asked)
            }
            if let degrees = air.degrees, self.adjustsTemperature(mode) {
                let halfCelsius = Automation.halfCelsius(degrees: degrees, fahrenheit: fahrenheit, mode: mode, status: self.status)
                try await self.changeTemperature(to: halfCelsius, askedAt: asked)
                applied.temperature = halfCelsius
            }
            if let fan = air.fanPercent {
                let percent = Automation.fanPercent(fan)
                try await self.changeFan(to: percent, askedAt: asked)
                applied.fanPercent = percent
            }
            if let minutes = air.minutes {
                let runTime = Automation.runTimeMinutes(minutes, mode: mode, status: self.status)
                try await self.changeRuntime(to: runTime)
                applied.minutes = runTime
            }
            return applied
        }
    }

    /// Ends the night if a schedule is running, otherwise turns the air off.
    func turnOff() async throws -> TurnedOff {
        try await carryOutAction {
            if let run = self.run {
                try await self.stopNight()
                return .endedNight(run.schedule.name)
            }
            if self.runningElsewhereStep != nil {
                try await self.stopNight()
                return .endedNight(nil)
            }
            guard self.status?.isRunning == true else { return .alreadyOff }
            try await self.changeMode(to: .standby, askedAt: Date())
            return .stoppedAir
        }
    }

    func turnLightRing(on: Bool) async throws {
        // A V2 sets the light and the beeps together, so it needs to know the other one first, which the status says.
        try await carryOutAction { try await self.connectedLink().setLED(on) }
    }

    func muteBeeps(_ muted: Bool) async throws {
        try await carryOutAction { try await self.connectedLink().setMuted(muted) }
    }

    /// Does an action's work once the BedJet has said what it is doing now. Its commands would go in among the Wi-Fi
    /// setup's or the update's, so it is refused while the BedJet uses its Wi-Fi (`isUsingWiFi`), as the controls are.
    /// That is checked once the BedJet has reported, since one may have started while the action was reaching it, or on
    /// another phone, and none starts after. Once the BedJet is installing and away, the action finds it unreachable as
    /// usual, and an update that finished while Lull was closed doesn't hold it up. A schedule Lull is writing finishes first.
    private func carryOutAction<Result>(_ work: () async throws -> Result) async throws -> Result {
        // Under way from the start, so while the action waits for the BedJet nothing else on this phone starts a night or
        // sets it, and it isn't handed to another phone. Control is taken only here: should the BedJet go to another phone
        // anyway, the action fails rather than asking for it back.
        try await underWay {
            try await self.takeControl()
            try await self.awaitStatus()
            // A night Lull is already starting, from its Start Night button or the watch, goes first, and is then the one
            // running: sent in among its steps, another schedule could leave the BedJet with parts of both, and Off could
            // come before the night it meant to end.
            while self.isSavingSchedule { try await Task.sleep(nanoseconds: 100_000_000) }
            if self.isUsingWiFi { throw AutomationError.updating }
            return try await work()
        }
    }

    /// Waits for the BedJet's first full report since it connected, which says what it is doing now rather than what Lull
    /// last saw before it let go.
    private func awaitStatus() async throws {
        await awaitReport(since: connectedAt, timeout: 3)
        try Task.checkCancellation()
        guard hasReported(since: connectedAt) else { throw BedJetError.timeout }
    }

    /// What the BedJet is doing as far as Lull knows: live while connected, otherwise as it was last seen.
    var reading: AirReading? {
        if isConnected, let status, let step = status.extra?.programStep {
            return AirReading(mode: status.mode, temperature: status.targetTemperature, fanPercent: status.fanPercent,
                              programStep: step, remainingSeconds: status.remainingSeconds, at: Date())
        }
        return lastSeen.map {
            AirReading(mode: $0.mode, temperature: $0.temperature, fanPercent: $0.fanPercent,
                       programStep: $0.programStep, remainingSeconds: $0.remainingSeconds, at: $0.at)
        }
    }

    // MARK: Wi-Fi and firmware

    /// Connected with the air off and no schedule running, so restarting the BedJet interrupts nothing.
    var isIdle: Bool { isConnected && status?.mode == .standby && run == nil && runningElsewhereStep == nil }
    /// Updating restarts the BedJet, so it waits until that interrupts nothing.
    var canUpdateFirmware: Bool { isIdle && !isBusy && !isUsingWiFi }
    var canJoinWiFi: Bool { isConnected && !isUsingWiFi }
    /// The BedJet is away because it's checking for or installing firmware, and will be back. Going by the clock too,
    /// until `restartPatience` after it was asked to check: the timer that marks it overdue only runs while Lull does, so
    /// Lull woken long after, as by an action, would otherwise still be holding everything for it.
    var expectsRestart: Bool {
        guard let wifiActivity, wifiActivity.purpose == .updating else { return false }
        let overdue = wifiActivity.restartOverdue || Date().timeIntervalSince(wifiActivity.startedAt) >= Self.restartPatience
        return wifiActivity.isRunning || (wifiActivity.awaitsRestart && !overdue)
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
            // Known before asking, even when this comes straight after connecting, to tell newer firmware once it's back.
            if self.firmware == nil { self.firmware = try? await link.firmwareVersion() }
            switch try await link.updateFirmware(progress: progress) {
            case .upToDate:
                return "Your BedJet's firmware is up to date."
            case .installing:
                // Read again once it's back, to show the new version.
                self.lastRefresh = .distantPast
                self.installBeganOn = self.connectedAt
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
        // A command already on its way, such as one from a Shortcuts action, finishes first so the two don't interleave.
        // Actions that come after are refused while this runs.
        while isBusy { try? await Task.sleep(nanoseconds: 200_000_000) }
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
            try? await Task.sleep(for: .seconds(Self.restartPatience))
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
            // Started from this phone, while its schedule is saved, it is recorded once the BedJet confirms it.
            if run == nil, !isSavingSchedule { record(.scheduleStarted(name: nil, phases: nil), noticedLater: wasAway) }
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

    /// Why this BedJet can't use a mode, if it can't.
    func problem(using mode: Mode) -> String? {
        guard !generation.supports(mode) else { return nil }
        return "\(displayName) is a BedJet V2, which can't run \(mode.title)."
    }

    /// Why a schedule can't run on this BedJet when started at `date`, if it can't.
    func problem(running schedule: Schedule, at date: Date = Date()) -> String? {
        let modes = unsupportedModes(in: schedule)
        if !modes.isEmpty {
            let names = ListFormatter.localizedString(byJoining: modes.map(\.title))
            return "\(displayName) is a BedJet V2, which can't run \(names). Edit “\(schedule.name)” or pick another schedule for it."
        }
        // Started then, the last phase has to reach the wake time within what BedJet lets it run.
        return schedule.wakeProblem(startingAt: Format.minuteOfDay(date))
    }
}

/// How air started from the Remote is timed.
enum RemoteTimer: Codable, Equatable {
    /// As long as the BedJet allows, which depends on the mode, temperature and fan.
    case longest
    /// For a duration picked on the Remote, or one set on another phone or the BedJet's own remote: it counts down as it is.
    case picked
    /// Until a time picked on the Remote, as far as the mode allows.
    case until(Date)

    /// A time picked on the Remote that hasn't come yet.
    var isAhead: Bool {
        if case .until(let end) = self { return end > Date() }
        return false
    }
}
