import BedJetKit
import XCTest
@testable import LullWatch

/// The watch app, against a pretend iPhone. The watch only ever asks Lull on the iPhone, which reaches the BedJet, so
/// this is everything the watch decides for itself. The Wear OS app's `WatchModelTest` is the twin.
@MainActor
final class WatchModelTests: XCTestCase {
    private struct Missing: Error {}

    private var defaults: UserDefaults!
    private var phone: DemoPhone!
    private let sample = WidgetSnapshot.preview()
    private var mine: WidgetBedJet { sample.bedJets[0] }
    private var theirs: WidgetBedJet { sample.bedJets[1] }

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: "WatchModelTests-\(UUID().uuidString)")
        phone = DemoPhone(snapshot: sample)
        phone.delay = nil
        WatchModel.patience = 30
    }

    private func launch() -> WatchModel {
        WatchModel(phone: phone, defaults: defaults, saves: false)
    }

    private func waitUntil(_ what: String, timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else {
                XCTFail("Timed out waiting for \(what)")
                throw Missing()
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func testShowsWhatTheIPhoneHandsOver() {
        let model = launch()
        XCTAssertEqual(model.snapshot, sample)
        // Nothing picked here: the BedJet on screen on the iPhone.
        XCTAssertEqual(model.bedJet?.id, mine.id)
        XCTAssertEqual(model.shown.run(at: Date())?.schedule.name, "Cool Drift")
    }

    func testAnErrandWaitsForTheIPhoneThenShowsWhatChanged() async throws {
        let model = launch()
        await model.send(.endNight(mine.id))
        XCTAssertEqual(model.pending, .endNight(mine.id))
        XCTAssertEqual(phone.received, [.endNight(mine.id)])
        // One at a time.
        await model.send(.startNight(mine.id))
        XCTAssertEqual(phone.received, [.endNight(mine.id)])

        phone.finish()
        XCTAssertNil(model.pending)
        XCTAssertNil(model.shown.run(at: Date()))
        XCTAssertNil(model.errorMessage)
    }

    func testAnErrandStaysBusyWhileItsSettingsChange() async {
        let model = launch()
        let cool = RemotePreset(mode: .cool, minutes: 30, temperature: 44, fanPercent: 40)
        await model.send(.remote(cool, mine.id))
        // The Remote's Apply, rebuilt as its settings are turned.
        XCTAssertTrue(model.isSending(.remote(RemotePreset(mode: .heat, minutes: 60, temperature: 70, fanPercent: 50), mine.id)))
        XCTAssertFalse(model.isSending(.remote(cool, theirs.id)))
        XCTAssertFalse(model.isSending(.startNight(mine.id)))
        phone.finish()
        XCTAssertFalse(model.isSending(.remote(cool, mine.id)))
    }

    func testThePretendIPhoneSetsOnlyWhatWasChanged() async throws {
        let model = launch()
        await model.send(.remote(RemotePreset(mode: .cool, minutes: 30, temperature: 44, fanPercent: 40), mine.id))
        phone.finish()
        let endsAt = try XCTUnwrap(liveAir(mine.id, in: model)?.endsAt)

        await model.send(.remote(RemotePreset(mode: .cool, minutes: 90, temperature: 50, fanPercent: 60), mine.id, changed: [.fan]))
        phone.finish()
        let air = try XCTUnwrap(liveAir(mine.id, in: model))
        XCTAssertEqual(air.fanPercent, 60)
        XCTAssertEqual(air.temperature, 44)
        XCTAssertEqual(air.endsAt, endsAt)
    }

    private func liveAir(_ id: UUID, in model: WatchModel) -> (temperature: Int?, fanPercent: Int, endsAt: Date)? {
        guard case let .air(_, temperature, fanPercent, endsAt)? = model.snapshot.bedJets.first(where: { $0.id == id })?.live(at: Date()) else {
            return nil
        }
        return (temperature, fanPercent, endsAt)
    }

    func testWhatWentWrongOnTheIPhoneIsSaid() async {
        let model = launch()
        phone.failure = BedJetError.notConnected.errorDescription
        await model.send(.startNight(mine.id))
        phone.finish()
        XCTAssertNil(model.pending)
        XCTAssertEqual(model.errorMessage, BedJetError.notConnected.errorDescription)
    }

    func testWithTheIPhoneAwayNothingIsSentAndItSaysWhy() async {
        phone.isReachable = false
        let model = launch()
        XCTAssertEqual(model.unavailable, "Your iPhone isn't nearby. Lull reaches your BedJet through it.")
        await model.send(.startNight(mine.id))
        XCTAssertTrue(phone.received.isEmpty)
        XCTAssertNil(model.pending)
        // What was last handed over still plays out by the clock.
        XCTAssertNotNil(model.shown.run(at: Date()))
    }

    func testAnErrandTheIPhoneNeverAnswersGivesUp() async throws {
        WatchModel.patience = 0.2
        let model = launch()
        await model.send(.startNight(mine.id))
        try await waitUntil("it to give up") { model.pending == nil }
        XCTAssertEqual(model.errorMessage, "Lull on your iPhone didn't answer in time. Open it there to check on your BedJet.")
    }

    func testAsksForAnUpdateAtMostEveryQuarterHour() async throws {
        let model = launch()
        model.refreshIfStale()
        try await waitUntil("a refresh") { phone.received == [.refresh] }
        // Asked quietly: nothing waits for it.
        XCTAssertNil(model.pending)
        // What the iPhone hands over on its own doesn't count as an answer, since it may be old.
        model.receive(sample)
        model.refreshIfStale(at: Date().addingTimeInterval(10 * 60))
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(phone.received, [.refresh])

        model.refreshIfStale(at: Date().addingTimeInterval(20 * 60))
        try await waitUntil("another refresh") { phone.received == [.refresh, .refresh] }
    }

    func testARefreshDoesntAnswerAnErrandSentWhileItRan() async throws {
        let model = launch()
        model.refreshIfStale()
        try await waitUntil("a refresh") { phone.received == [.refresh] }
        await model.send(.startNight(mine.id))
        phone.finish(1)
        XCTAssertEqual(model.pending, .startNight(mine.id))
        phone.finish()
        XCTAssertNil(model.pending)
    }

    func testARefreshThatDidntGoIsTriedAgain() async throws {
        let model = launch()
        phone.delivers = false
        model.refreshIfStale()
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(phone.received.isEmpty)

        phone.delivers = true
        model.refreshIfStale()
        try await waitUntil("a refresh") { phone.received == [.refresh] }
    }

    func testAsksForAnUpdateOnceTheIPhoneIsReachable() async throws {
        phone.isReachable = false
        let model = launch()
        model.refreshIfStale()
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(phone.received.isEmpty)

        phone.isReachable = true
        try await waitUntil("a refresh") { phone.received == [.refresh] }
        XCTAssertTrue(model.isReachable)
    }

    func testAWaitThatRunsOutOnlyGivesUpOnItsOwnErrand() async throws {
        WatchModel.patience = 1
        let model = launch()
        await model.send(.startNight(mine.id))
        phone.finish()
        // The same errand again, before the first one's wait runs out, and checked once it has. These waits are real,
        // so the check comes a quarter of a second after the first runs out and as long before the second does.
        try await Task.sleep(nanoseconds: 500_000_000)
        await model.send(.startNight(mine.id))
        try await Task.sleep(nanoseconds: 750_000_000)
        XCTAssertEqual(model.pending, .startNight(mine.id))
        XCTAssertNil(model.errorMessage)
    }

    func testALateAnswerToAnErrandGivenUpOnIsIgnored() async throws {
        WatchModel.patience = 0.1
        let model = launch()
        phone.failure = BedJetError.notConnected.errorDescription
        await model.send(.startNight(mine.id))
        try await waitUntil("it to give up") { model.pending == nil }
        model.errorMessage = nil
        phone.finish()
        XCTAssertNil(model.errorMessage)
    }

    func testThePickedBedJetSurvivesARelaunch() {
        let model = launch()
        model.pickedID = theirs.id
        XCTAssertEqual(model.bedJet?.id, theirs.id)
        XCTAssertEqual(model.shown.tonight?.name, theirs.tonight?.name)
        XCTAssertNil(model.shown.run(at: Date()))

        let relaunched = launch()
        XCTAssertEqual(relaunched.bedJet?.id, theirs.id)
    }

    func testAPickedBedJetForgottenSinceGoesBackToTheOneOnScreen() {
        let model = launch()
        model.pickedID = UUID()
        // As the complications do, rather than a blank watch with nothing to pick when only one BedJet is left.
        XCTAssertEqual(model.bedJet?.id, mine.id)
        XCTAssertEqual(model.shown.tonight?.name, mine.tonight?.name)
    }

    func testComplicationsShowTheBedJetPickedHere() throws {
        // Followed by the phone: the one on screen there, as the watch shows it.
        XCTAssertEqual(WatchModel.forComplications(sample, picked: nil), sample.showing(mine.id))
        // Picked here: that BedJet's own tonight and night, not the one on screen on the phone.
        let saved = WatchModel.forComplications(sample, picked: theirs.id)
        XCTAssertEqual(saved.onScreen, theirs.id)
        XCTAssertNil(saved.run(at: Date()))
        XCTAssertEqual(saved.tonight, theirs.tonight)
        XCTAssertEqual(saved.headed("Tonight"), "Other side · Tonight")
        // Forgotten since: the one on screen on the phone, as the watch goes back to.
        XCTAssertEqual(WatchModel.forComplications(sample, picked: UUID()), sample.showing(mine.id))
        // None on screen on the phone, as once it forgets the one that was: the first it knows, as the watch shows.
        var noneOnScreen = sample
        noneOnScreen.onScreen = nil
        noneOnScreen.tonight = nil
        noneOnScreen.run = nil
        let first = WatchModel.forComplications(noneOnScreen, picked: nil)
        XCTAssertEqual(first.onScreen, mine.id)
        XCTAssertEqual(first.tonight, mine.tonight)
        XCTAssertEqual(first.run(at: Date())?.schedule.name, "Cool Drift")
        XCTAssertEqual(first.headed("Tonight"), "My side · Tonight")
    }

    // The watch face offers a complication for each BedJet by name. Wear OS offers Lull's one, so this has no twin.
    func testTheWatchFaceIsOfferedItsComplicationsAgainOnlyWhenTheBedJetsChange() {
        XCTAssertTrue(WatchModel.offersChanged(from: WidgetSnapshot(), to: sample))
        var running = sample
        running.bedJets[1].live = nil
        XCTAssertFalse(WatchModel.offersChanged(from: sample, to: running))
        var renamed = sample
        renamed.bedJets[1].name = "Guest side"
        XCTAssertTrue(WatchModel.offersChanged(from: sample, to: renamed))
        var forgotten = sample
        forgotten.bedJets.removeLast()
        XCTAssertTrue(WatchModel.offersChanged(from: sample, to: forgotten))
    }

    // What the iPhone hands over, as Watch Connectivity brings it. Wear OS messages arrive in order or not at all, so
    // these have no twin.
    private func message(_ snapshot: WidgetSnapshot, madeAt: Date?, reply: WatchReply? = nil) throws -> [String: Any] {
        var message: [String: Any] = try [WatchMessages.snapshot: JSONEncoder().encode(snapshot)]
        if let madeAt { message[WatchMessages.snapshotAt] = madeAt }
        if let reply { message[WatchMessages.reply] = reply.data }
        return message
    }

    func testASnapshotOlderThanTheOneShownIsDroppedButItsAnswerStillCounts() throws {
        let link = PhoneLink()
        var shown: [UUID?] = []
        var replies: [WatchReply] = []
        link.onSnapshot = { shown.append($0.onScreen) }
        link.onReply = { replies.append($0) }
        var other = sample
        other.onScreen = theirs.id
        let now = Date()
        link.receive(try message(sample, madeAt: now))
        // A transfer queued while the watch was away, arriving after something newer.
        link.receive(try message(other, madeAt: now.addingTimeInterval(-60), reply: .done))
        XCTAssertEqual(shown, [mine.id])
        XCTAssertEqual(replies, [.done])
    }

    func testANewerSnapshotOrOneWithNoTimeIsShown() throws {
        let link = PhoneLink()
        var shown: [UUID?] = []
        link.onSnapshot = { shown.append($0.onScreen) }
        var other = sample
        other.onScreen = theirs.id
        let now = Date()
        link.receive(try message(sample, madeAt: now))
        link.receive(try message(other, madeAt: now.addingTimeInterval(60)))
        link.receive(try message(sample, madeAt: nil))
        XCTAssertEqual(shown, [mine.id, theirs.id, mine.id])
    }

    func testStartNightFromSiriWaitsForTheIPhoneToBeFound() async throws {
        // Opened by Siri: the iPhone is found a moment after the app starts.
        phone.isReachable = false
        let model = launch()
        model.handle(.startNight(nil))
        try await Task.sleep(nanoseconds: 300_000_000)
        phone.isReachable = true
        try await waitUntil("the errand") { phone.received.contains(.startNight(mine.id)) }
        XCTAssertNil(model.errorMessage)
    }

    func testStartNightFromSiriGoesToTheBedJetShownHere() async throws {
        let model = launch()
        model.pickedID = theirs.id
        model.handle(.startNight(nil))
        try await waitUntil("the errand") { phone.received == [.startNight(theirs.id)] }
    }
}
