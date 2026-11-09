import XCTest

/// The Remote's settings turn with the Digital Crown once tapped, and stop at the ends of what the BedJet takes, as Wear
/// OS's do with the rotating side button or bezel. The twin of `CrownRowTest.kt`.
final class CrownRowUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        // A pretend iPhone with the sample BedJets, and nothing kept.
        app.launchArguments = ["-demo", "-ephemeral"]
        app.launch()
    }

    func testTheCrownTurnsATappedSetting() throws {
        // The Remote is the page after Tonight, past the end of Tonight's own scrolling. Its Fan row is only there once
        // the page is.
        let fan = app.descendants(matching: .any).matching(identifier: "Fan").firstMatch
        for _ in 0..<4 where !fan.waitForExistence(timeout: 2) { app.swipeUp() }
        // Temperature, the first setting, is in full view without scrolling. Once the page scrolls, the positions
        // XCUITest reports on the watch lag behind where the rows are, so touches miss.
        let temperature = app.descendants(matching: .any).matching(identifier: "Temperature").firstMatch
        XCTAssertTrue(temperature.isHittable)
        let start = try XCTUnwrap(temperature.value as? String)

        temperature.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).press(forDuration: 0.3)
        XCUIDevice.shared.rotateDigitalCrown(delta: 0.5)
        XCTAssertNotEqual(temperature.value as? String, start)

        // No lower than the BedJet takes: turning further down changes nothing.
        XCUIDevice.shared.rotateDigitalCrown(delta: -5)
        let lowest = try XCTUnwrap(temperature.value as? String)
        XCUIDevice.shared.rotateDigitalCrown(delta: -1)
        XCTAssertEqual(temperature.value as? String, lowest)
        XCTAssertNotEqual(lowest, start)
    }
}
