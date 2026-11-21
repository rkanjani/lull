import XCTest

/// A running BedJet shows in a Live Activity in the Dynamic Island, and goes when it stops. The twin of
/// LiveUpdateTest.kt. Live Activities are left out of `-ephemeral` launches, so the flows elsewhere don't leave them
/// behind; these launch as Lull normally does, and turn the BedJet off again before they finish.
final class LiveActivityTests: XCTestCase {
    private let app = XCUIApplication()
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() {
        continueAfterFailure = false
        app.launch()
    }

    /// Whether the Dynamic Island reads as one of `words`, found by what VoiceOver reads for the Live Activity in it.
    /// Only the top of the screen counts, so a Lull widget on the Home Screen saying the same isn't taken for it.
    private func island(reads words: [String]) -> Bool {
        let pattern = ".*\\b(\(words.joined(separator: "|")))\\b.*"
        return springboard.descendants(matching: .any).matching(NSPredicate(format: "label MATCHES %@", pattern))
            .allElementsBoundByIndex.contains { $0.frame.minY < 80 && $0.frame.height < 80 }
    }

    private func wait(timeout: TimeInterval = 10, until condition: @escaping () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            sleep(1)
        }
        return condition()
    }

    func testAirFromTheRemoteShowsUntilTurnedOff() {
        let heat = app.buttons["Heat"]
        XCTAssertTrue(heat.waitForExistence(timeout: 10))
        sleep(2)
        heat.tap()
        XCTAssertTrue(app.sliders["Turns off in"].waitForExistence(timeout: 5))

        XCUIDevice.shared.press(.home)
        XCTAssertTrue(wait { self.island(reads: ["Heating"]) })

        // Back in Lull, it reconnects to the BedJet, as at launch, before it can turn it off.
        app.activate()
        sleep(2)
        app.buttons["Off"].tap()
        // Lull lets go of the BedJet once it's in the background, so wait for the BedJet to take the command first.
        let turnsOff = app.sliders["Turns off in"]
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: turnsOff)
        waitForExpectations(timeout: 10)
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(wait { !self.island(reads: ["Heating"]) })
    }

    func testANightShowsUntilItEnds() {
        app.tabBars.buttons["Schedule"].tap()
        let start = app.buttons["Start Night"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: start)
        waitForExpectations(timeout: 10)
        start.tap()
        XCTAssertTrue(app.buttons["End Night"].waitForExistence(timeout: 10))

        // It reads as what the first phase is doing, whichever schedule is tonight's.
        let phases = ["Cooling", "Heating", "Turbo heating", "Gently heating", "Drying", "Paused"]
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(wait { self.island(reads: phases) })

        app.activate()
        app.buttons["End Night"].tap()
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(wait { !self.island(reads: phases) })
    }
}
