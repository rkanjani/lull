import XCTest

/// Puts Lull's widgets on the simulator's Home Screen and Lock Screen through the system's own galleries, checks what
/// they say and where tapping them goes, and saves a screenshot of each layout. It rearranges the simulator, so it runs
/// only when given a folder, and expects a freshly erased simulator:
/// `TEST_RUNNER_WIDGET_SHOTS_DIR=<folder> xcodebuild test -only-testing:LullUITests/HomeScreenWidgetTests`
final class HomeScreenWidgetTests: XCTestCase {
    private let app = XCUIApplication()
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private var folder: URL!

    /// The widgets in the order the gallery pages through them.
    private enum Gallery: Int {
        case tonightSmall = 1, tonightMedium, tonightLarge, lastNightSmall, lastNightMedium
        case bothSidesMedium, quickRemoteSmall, quickRemoteMedium

        static let pages = 8
    }

    override func setUpWithError() throws {
        let path = ProcessInfo.processInfo.environment["WIDGET_SHOTS_DIR"]
        try XCTSkipUnless(path != nil, "No screenshot folder given")
        folder = URL(fileURLWithPath: path!)
        continueAfterFailure = false
    }

    func testWidgetsOnTheHomeScreen() throws {
        // A night a few hours in, with a few nights before it in History.
        app.launchArguments = ["-demoHistory", "-demoElapsedMinutes", "200"]
        app.launch()
        endNightIfRunning()
        startNight()

        goHome()
        editHomeScreen()
        removeWidgets()
        add(.lastNightMedium)
        add(.tonightLarge)
        finishEditing()
        XCTAssertTrue(widget("Cool Drift, phase 2 of 4, Cooling").waitForExistence(timeout: 10))
        XCTAssertTrue(widget("Last night, ").exists)
        try capture("01-running-large")

        editHomeScreen()
        removeWidgets()
        add(.lastNightSmall)
        add(.tonightSmall)
        add(.tonightMedium)
        finishEditing()
        try capture("02-running")

        // Tapping Last Night opens History.
        widget("Last night, ").tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["History"].isSelected)

        // With the night ended, Tonight offers to start it, and starting it from the widget opens Lull and does so.
        endNightIfRunning()
        goHome()
        XCTAssertTrue(widget("Tonight, Cool Drift").waitForExistence(timeout: 10))
        try capture("03-planned")
        springboard.buttons["Start Night"].firstMatch.tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(app.buttons["End Night"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.tabBars.buttons["Schedule"].isSelected)
    }

    func testTonightAndQuickRemote() throws {
        app.launchArguments = ["-demoHistory", "-demoElapsedMinutes", "200"]
        app.launch()
        endNightIfRunning()
        startNight()

        goHome()
        editHomeScreen()
        removeWidgets()
        add(.tonightSmall)
        add(.quickRemoteSmall)
        add(.bothSidesMedium)
        finishEditing()
        XCTAssertTrue(widget("Cool Drift, phase 2 of 4, Cooling").waitForExistence(timeout: 10))
        XCTAssertTrue(springboard.buttons["Cool · 30 min"].exists)
        try capture("05-tonight-night")

        // A Quick Remote button opens Lull on the Remote and sets the BedJet going, and Tonight follows.
        springboard.buttons["Heat · 15 min"].firstMatch.tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Remote"].isSelected)
        XCTAssertTrue(app.staticTexts["Heating"].waitForExistence(timeout: 15))
        goHome()
        XCTAssertTrue(widget("Heating").waitForExistence(timeout: 15))
        try capture("06-tonight-air")
    }

    func testWidgetsOnTheLockScreen() throws {
        app.launchArguments = ["-demoHistory", "-demoElapsedMinutes", "200"]
        app.launch()
        app.tabBars.buttons["Schedule"].tap()
        if !app.buttons["End Night"].waitForExistence(timeout: 3) { startNight() }

        XCUIDevice.shared.press(.home)
        sleep(1)
        // XCUIDevice has no lock button of its own; the simulator answers to this one.
        XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
        sleep(2)
        XCUIDevice.shared.press(.home)
        sleep(2)
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 2)
        let customize = springboard.buttons["Customize"]
        XCTAssertTrue(customize.waitForExistence(timeout: 5))
        customize.tap()
        sleep(5)

        // The circular and rectangular widgets go in the row under the clock.
        let row = springboard.buttons.matching(identifier: "grouped-widgets-reticle-view").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let lull = springboard.cells["Lull"]
        XCTAssertTrue(lull.waitForExistence(timeout: 5))
        lull.tap()
        for shape in ["Circular", "Rectangular"] {
            let widget = springboard.buttons.matching(NSPredicate(format: "label == 'Lull, Tonight' AND value ENDSWITH %@", shape)).firstMatch
            XCTAssertTrue(widget.waitForExistence(timeout: 5))
            widget.tap()
            sleep(1)
        }
        springboard.buttons["close"].firstMatch.tap()
        sleep(1)
        springboard.buttons["Sheet Grabber"].firstMatch.swipeDown()
        sleep(2)

        // The inline widget sits beside the date.
        springboard.buttons["inline-widget-reticle-view"].tap()
        let inline = springboard.cells["Lull, Tonight, Top Widget"]
        XCTAssertTrue(inline.waitForExistence(timeout: 5))
        inline.tap()
        sleep(1)
        if springboard.buttons["Sheet Grabber"].exists { springboard.buttons["Sheet Grabber"].firstMatch.swipeDown() }
        sleep(2)

        // Start Night replaces the flashlight in the bottom corner.
        let flashlight = springboard.buttons["Remove Flashlight"]
        if flashlight.exists { flashlight.tap() }
        springboard.buttons["Add Quick Action"].firstMatch.tap()
        let start = springboard.buttons["com.rohankanjani.lull.start-night"]
        XCTAssertTrue(springboard.searchFields["Search Controls"].waitForExistence(timeout: 5))
        for _ in 0..<8 where !start.isHittable {
            springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
                .press(forDuration: 0.05, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)))
            sleep(1)
        }
        XCTAssertTrue(springboard.buttons["com.rohankanjani.lull.remote"].exists)
        start.tap()
        sleep(2)
        springboard.buttons["editing-done"].firstMatch.tap()
        sleep(3)
        // Back from the wallpaper picker to the Lock Screen itself.
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        sleep(3)

        XCTAssertTrue(widget("Cool Drift, phase 2 of 4, Cooling").exists)
        try capture("04-lock-screen")
    }

    // MARK: Lull

    private func startNight() {
        app.tabBars.buttons["Schedule"].tap()
        let start = app.buttons["Start Night"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: start)
        waitForExpectations(timeout: 10)
        start.tap()
        XCTAssertTrue(app.buttons["End Night"].waitForExistence(timeout: 10))
    }

    private func endNightIfRunning() {
        app.activate()
        app.tabBars.buttons["Schedule"].tap()
        let end = app.buttons["End Night"]
        guard end.waitForExistence(timeout: 3) else { return }
        end.tap()
        XCTAssertTrue(app.buttons["Start Night"].waitForExistence(timeout: 10))
    }

    // MARK: Home Screen

    /// A widget on the Home Screen, found by the start of what VoiceOver reads for it.
    private func widget(_ label: String) -> XCUIElement {
        springboard.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
    }

    private func goHome() {
        XCUIDevice.shared.press(.home)
        sleep(2)
        dismissTips()
    }

    /// A first-run tip about the keyboard can cover the bottom of the screen.
    private func dismissTips() {
        let tip = springboard.buttons["Continue"]
        if tip.exists { tip.tap() }
    }

    private func editHomeScreen() {
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.84)).press(forDuration: 1.5)
        XCTAssertTrue(springboard.buttons["Edit"].waitForExistence(timeout: 5))
        dismissTips()
    }

    private func finishEditing() {
        springboard.buttons["Done"].firstMatch.tap()
        sleep(3)
    }

    /// Clears the first page's widgets, so the ones added next sit at its top.
    private func removeWidgets() {
        let widgets = springboard.icons.matching(NSPredicate(format: "value BEGINSWITH 'Widget'"))
        while widgets.firstMatch.exists {
            // A large widget pushes the apps onto a page of their own, which editing can open on; go to the widget's page.
            for _ in 0..<3 {
                let x = widgets.firstMatch.frame.midX
                if x < 0 { springboard.swipeRight() } else if x > springboard.frame.maxX { springboard.swipeLeft() } else { break }
                sleep(1)
            }
            widgets.firstMatch.buttons["DeleteButton"].tap()
            let remove = springboard.buttons["Remove"]
            XCTAssertTrue(remove.waitForExistence(timeout: 5))
            remove.tap()
            sleep(1)
        }
    }

    private func add(_ widget: Gallery) {
        springboard.buttons["Edit"].tap()
        springboard.buttons["Add Widget"].tap()
        let search = springboard.searchFields["Search Widgets"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("Lull")
        let lull = springboard.cells["Lull"]
        XCTAssertTrue(lull.waitForExistence(timeout: 5))
        lull.tap()
        // The gallery's own dots, not the Home Screen's ("Page 1 of 3") behind it.
        let pages = springboard.pageIndicators.matching(NSPredicate(format: "value BEGINSWITH 'page '")).firstMatch
        XCTAssertTrue(pages.waitForExistence(timeout: 5))
        for _ in 1..<widget.rawValue {
            springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Lull, '")).firstMatch.swipeLeft()
            sleep(1)
        }
        XCTAssertEqual(pages.value as? String, "page \(widget.rawValue) of \(Gallery.pages)")
        springboard.buttons[" Add Widget"].tap()
        sleep(2)
    }

    private func capture(_ name: String) throws {
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent("\(name).png"))
        try springboard.debugDescription.write(to: folder.appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8)
    }
}
