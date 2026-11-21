import BedJetKit
import Foundation
import UIKit
import WatchConnectivity

/// Lull on the phone, as the watch sees it. The watch has no Bluetooth link of its own to the BedJet, so this hands it
/// what the widgets show and runs the errands it asks for. Asking wakes Lull in the background when it isn't running,
/// which is the only time Lull reaches a BedJet while it's off screen, and it lets go again as soon as it's done.
@MainActor
final class PhoneSession: NSObject {
    private let session = WCSession.default
    private weak var model: AppModel?
    /// What the widgets show, encoded, handed over once the session is up and again whenever it changes.
    private var latest: Data?
    /// When `latest` was made, sent with it so the watch never trades it for something older.
    private var latestAt = Date()
    /// What the watch's complications last drew. The watch can only be woken for them so many times a day, so it's only
    /// woken when what they draw changes.
    private var complicationsShow: ComplicationsShow?

    init(model: AppModel) {
        self.model = model
        super.init()
        session.delegate = self
        session.activate()
    }

    func send(_ snapshot: WidgetSnapshot) {
        latest = try? JSONEncoder().encode(snapshot)
        latestAt = Date()
        handOver(ComplicationsShow(snapshot))
    }

    private func handOver(_ shown: ComplicationsShow? = nil) {
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled, let latest else { return }
        // The latest wins: a watch that was away gets what the widgets show now, not every change since.
        try? session.updateApplicationContext([WatchMessages.snapshot: latest, WatchMessages.snapshotAt: latestAt])
        guard let shown, shown != complicationsShow, session.isComplicationEnabled,
              session.remainingComplicationUserInfoTransfers > 0 else { return }
        complicationsShow = shown
        session.transferCurrentComplicationUserInfo([WatchMessages.snapshot: latest, WatchMessages.snapshotAt: latestAt])
    }

    /// Runs what the watch asked, then says how it went, with what the widgets show now so the watch never shows the
    /// moment before. A refresh only hands over what the widgets show.
    private func run(_ command: WatchCommand) async {
        guard let model else { return }
        // Woken by the watch, Lull is soon suspended: this keeps it running through the errand and while its answer is
        // handed to Watch Connectivity, though not until it arrives. Should iOS say time's up first, the errand is
        // cancelled and lets go of its BedJet, as Android's is when its work is stopped, so nothing it was partway
        // through goes on whenever Lull next runs, which could be hours later.
        let errand = Task { await model.runErrand(command) }
        let background = BackgroundTask(named: "Errand from the watch") { [weak model] in
            errand.cancel()
            model?.letGoOfErrands()
        }
        defer { background.end() }
        let reply = await errand.value
        var message: [String: Any] = [:]
        if command.isAnswered { message[WatchMessages.reply] = reply.data }
        if let latest {
            message[WatchMessages.snapshot] = latest
            message[WatchMessages.snapshotAt] = latestAt
        }
        // A refresh with nothing to hand over has nothing to send.
        guard !message.isEmpty else { return }
        // A watch lowered while it waited is no longer reachable; it gets the answer as soon as it's raised again.
        guard session.isReachable else {
            session.transferUserInfo(message)
            return
        }
        session.sendMessage(message, replyHandler: nil) { [session] _ in session.transferUserInfo(message) }
    }
}

extension PhoneSession: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.handOver() }
    }

    /// The watch app was just installed, or another watch was paired.
    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.handOver() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    /// Switching to another watch: carry on with that one.
    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    /// An errand from the watch. It's answered at once that Lull is on it, since reaching the BedJet can take a while,
    /// then again once it's done.
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                             replyHandler: @escaping ([String: Any]) -> Void) {
        guard let data = message[WatchMessages.command] as? Data, let command = WatchCommand(data: data) else {
            replyHandler([WatchMessages.reply: WatchReply.failed("Update Lull on your iPhone to use it from your watch.").data])
            return
        }
        replyHandler([:])
        Task { @MainActor in await self.run(command) }
    }
}

/// What the watch's complications draw: tonight's schedule and the night running on each BedJet, under its name once
/// there are several, for the BedJet picked on the watch or else the one on screen. They don't draw Remote timers, so
/// those don't spend the few times a day the watch can be woken for them.
private struct ComplicationsShow: Equatable {
    var onScreen: UUID?
    var run: ActiveRun?
    var bedJets: [UUID]
    var names: [String]
    var nights: [ActiveRun?]
    var tonight: [Schedule?]

    init(_ snapshot: WidgetSnapshot) {
        onScreen = snapshot.onScreen
        run = snapshot.run
        bedJets = snapshot.bedJets.map(\.id)
        names = snapshot.bedJets.map(\.name)
        nights = snapshot.bedJets.map { bedJet in if case let .night(run)? = bedJet.live { run } else { nil } }
        tonight = snapshot.bedJets.map(\.tonight)
    }
}

/// Keeps Lull running in the background for a while: until it's ended, or iOS says time's up, whichever comes first.
/// Should the time run out first, `onExpiry` lets go of what the work holds, so no BedJet is left held by a phone iOS is
/// about to suspend.
@MainActor
private final class BackgroundTask {
    private var id = UIBackgroundTaskIdentifier.invalid

    init(named name: String, onExpiry: @escaping @MainActor () -> Void) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            // iOS calls this on the main thread.
            MainActor.assumeIsolated {
                onExpiry()
                self?.end()
            }
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
