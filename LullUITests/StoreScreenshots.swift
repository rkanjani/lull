import XCTest

/// Captures the screens used for the App Store listing. Runs only when a destination folder is given:
/// `TEST_RUNNER_STORE_SHOTS_DIR=<folder> xcodebuild test -only-testing:LullUITests/StoreScreenshots`
final class StoreScreenshots: XCTestCase {
    private let app = XCUIApplication()
    private var folder: URL!

    override func setUpWithError() throws {
        let path = ProcessInfo.processInfo.environment["STORE_SHOTS_DIR"]
        try XCTSkipUnless(path != nil, "No screenshot folder given")
        folder = URL(fileURLWithPath: path!)
        continueAfterFailure = false
        // A night that is already a few hours in shows more than one that has just begun.
        app.launchArguments = ["-ephemeral", "-noWakeTimes", "-demoElapsedMinutes", "200", "-demoLabelConnected"]
        app.launch()
    }

    private func capture(_ name: String) throws {
        sleep(1)
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent("\(name).png"))
    }

    func testCaptureListingScreens() throws {
        let heat = app.buttons["Heat"]
        XCTAssertTrue(heat.waitForExistence(timeout: 10))
        sleep(2)
        heat.tap()
        // A duration, the default, whatever an earlier run left chosen: the choice is kept even with `-ephemeral`.
        let timerOption = app.buttons["Timer"]
        if timerOption.waitForExistence(timeout: 5), !timerOption.isSelected { timerOption.tap() }
        let timer = app.sliders["Turns off in"]
        XCTAssertTrue(timer.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "value == '4 hours'"), evaluatedWith: timer)
        waitForExpectations(timeout: 5)
        sleep(6)
        try capture("01-remote")

        app.tabBars.buttons["Schedule"].tap()
        XCTAssertTrue(app.buttons["Start Night"].waitForExistence(timeout: 5))
        try capture("02-schedule")

        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Warm Welcome'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Add Phase"].waitForExistence(timeout: 5))
        try capture("03-editor")

        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Heat' AND label CONTAINS '30 minutes'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Remove Phase"].waitForExistence(timeout: 5))
        try capture("04-phase")
        app.buttons["Done"].tap()
        app.buttons["Cancel"].tap()

        let start = app.buttons["Start Night"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
        XCTAssertTrue(app.buttons["End Night"].waitForExistence(timeout: 10))
        sleep(2)
        try capture("05-running")
    }
}
