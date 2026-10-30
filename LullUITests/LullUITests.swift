import XCTest

/// Walks the main flows against the simulated BedJet and keeps a screenshot of each screen.
final class LullUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launchArguments = ["-ephemeral"]
        app.launch()
    }

    private func capture(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let directory = ProcessInfo.processInfo.environment["SHOTS_DIR"] {
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: directory).appendingPathComponent("\(name).png"))
        }
    }

    /// Drags a slider's thumb from one point on its track to another, as fractions of the width.
    /// VoiceOver sees the slider and its title row as one element, so the track runs along the bottom of it.
    private func slide(_ slider: XCUIElement, from: CGFloat, to: CGFloat) {
        let start = slider.coordinate(withNormalizedOffset: CGVector(dx: from, dy: 0.78))
        start.press(forDuration: 0.2, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: to, dy: 0.78)))
    }

    func testOpensOnRemote() {
        XCTAssertTrue(app.staticTexts["Remote"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.tabBars.buttons.firstMatch.label, "Remote")
        XCTAssertTrue(app.tabBars.buttons["Remote"].isSelected)
    }

    func testStartAndEndNight() {
        app.tabBars.buttons["Schedule"].tap()
        let start = app.buttons["Start Night"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        // The button enables once the simulated BedJet connects.
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: start)
        waitForExpectations(timeout: 10)
        start.tap()

        let end = app.buttons["End Night"]
        XCTAssertTrue(end.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Phase 1 of'")).firstMatch.exists)
        sleep(2)
        capture("schedule-running")

        end.tap()
        XCTAssertTrue(start.waitForExistence(timeout: 10))
    }

    func testEditScheduleAndUseItTonight() {
        app.tabBars.buttons["Schedule"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Warm Welcome'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Add Phase"].waitForExistence(timeout: 5))
        capture("editor")

        // Remote stays loaded behind the editor and has its own Turbo and Gentle heat buttons,
        // so match the phase row by its duration and take the frontmost mode button.
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Turbo' AND label CONTAINS '10 minutes'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["Remove Phase"].waitForExistence(timeout: 5))
        let gentleHeat = app.buttons.matching(identifier: "Gentle heat")
        gentleHeat.element(boundBy: gentleHeat.count - 1).tap()
        sleep(1)
        capture("phase-editor")
        app.buttons["Done"].tap()

        // On taller screens Add Phase can sit behind the bottom bar until the list is scrolled up.
        app.swipeUp()
        app.buttons["Add Phase"].tap()
        XCTAssertTrue(app.buttons["Remove Phase"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        app.buttons["Use Tonight"].tap()

        // Warm Welcome is now the featured schedule, so Cool Drift moves down and offers the swap back.
        XCTAssertTrue(app.buttons["Use Cool Drift tonight"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Use Warm Welcome tonight"].exists)
        capture("schedule-planned")

        app.buttons["Use Cool Drift tonight"].tap()
        XCTAssertTrue(app.buttons["Use Warm Welcome tonight"].waitForExistence(timeout: 5))
    }

    func testHistoryKeepsTheNight() {
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["No nights yet"].waitForExistence(timeout: 10))

        app.tabBars.buttons["Remote"].tap()
        let heat = app.buttons["Heat"]
        XCTAssertTrue(heat.waitForExistence(timeout: 10))
        sleep(2)
        heat.tap()
        XCTAssertTrue(app.sliders["Turns off in"].waitForExistence(timeout: 5))
        // A setting is recorded once the BedJet has held it for a few seconds.
        sleep(4)
        app.buttons["Off"].tap()
        sleep(4)

        // A schedule's phases are its own; only its start and end are recorded.
        app.tabBars.buttons["Schedule"].tap()
        let start = app.buttons["Start Night"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()
        let end = app.buttons["End Night"]
        XCTAssertTrue(end.waitForExistence(timeout: 10))
        sleep(4)
        end.tap()
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        sleep(4)

        app.tabBars.buttons["History"].tap()
        // The night is one row named after the schedule that ran, and opens up to the whole night.
        let night = app.buttons["Night"]
        XCTAssertTrue(night.waitForExistence(timeout: 5))
        XCTAssertTrue(night.label.hasSuffix("Cool Drift"))
        XCTAssertFalse(app.staticTexts["Turned off"].exists)
        capture("history-collapsed")
        night.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Heat,'")).firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Turned off"].exists)
        XCTAssertTrue(app.staticTexts["Started Cool Drift"].exists)
        XCTAssertTrue(app.staticTexts["Ended Cool Drift"].exists)
        // Nothing else: the schedule's first phase and the BedJet turning off with it are not separate entries.
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Cool,'")).count, 0)
        sleep(1)
        capture("history-expanded")

        night.tap()
        XCTAssertTrue(app.staticTexts["Turned off"].waitForNonExistence(timeout: 5))
    }

    func testHistoryListsNightsAsRows() {
        app.terminate()
        app.launchArguments = ["-ephemeral", "-demoHistory"]
        app.launch()
        app.tabBars.buttons["History"].tap()

        let nights = app.buttons.matching(identifier: "Night")
        XCTAssertTrue(nights.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(nights.count, 3)
        // Newest first; a night without a schedule is summed up by its times instead.
        XCTAssertTrue(nights.element(boundBy: 0).label.hasSuffix("Cool Drift"))
        XCTAssertTrue(nights.element(boundBy: 1).label.contains(" to "))
        capture("history-rows")

        nights.element(boundBy: 1).tap()
        XCTAssertTrue(app.staticTexts["Turned off"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Timer ran out'")).firstMatch.exists)
        sleep(1)
        capture("history-row-expanded")
    }

    private func pill(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "BedJet, \(title),")).firstMatch
    }

    /// A row in a list of BedJets, by its name and any code after it.
    private func row(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(title),")).firstMatch
    }

    private func relaunch(_ arguments: [String]) {
        app.terminate()
        app.launchArguments = ["-ephemeral"] + arguments
        app.launch()
    }

    /// Opens the BedJet sheet's list of BedJets nearby and picks the second simulated one, which shares a name.
    private func addSecondBedJet(from sheetButton: XCUIElement) {
        sheetButton.tap()
        let add = app.buttons["Add Another BedJet"]
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        for _ in 0..<3 where !add.exists { app.swipeUp() }
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        let other = row("BEDJET-DEMO · 5F2C")
        XCTAssertTrue(other.waitForExistence(timeout: 5))
        capture("nearby-bedjets")
        other.tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
    }

    func testAddAndSwitchBedJets() {
        let heat = app.buttons["Heat"]
        XCTAssertTrue(heat.waitForExistence(timeout: 10))

        // With one BedJet the pill names it and says how it's connected; its settings offer to add another.
        addSecondBedJet(from: pill("BEDJET-DEMO"))

        // Two share a name, so each carries a code from its Bluetooth identity.
        let second = pill("BEDJET-DEMO · 5F2C")
        let first = pill("BEDJET-DEMO · D3A0")
        XCTAssertTrue(second.waitForExistence(timeout: 5))
        sleep(2)
        heat.tap()
        let timer = app.sliders["Turns off in"]
        XCTAssertTrue(timer.waitForExistence(timeout: 5))

        // The pill switches BedJets, and each keeps its own state.
        second.tap()
        XCTAssertTrue(app.buttons["BEDJET-DEMO · D3A0"].waitForExistence(timeout: 5))
        capture("bedjet-switcher")
        app.buttons["BEDJET-DEMO · D3A0"].tap()
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        XCTAssertTrue(timer.waitForNonExistence(timeout: 5))

        first.tap()
        app.buttons["BEDJET-DEMO · 5F2C"].tap()
        XCTAssertTrue(second.waitForExistence(timeout: 5))
        XCTAssertTrue(timer.waitForExistence(timeout: 5))
        app.buttons["Off"].tap()
    }

    func testChoosesABedJetBeforeAnything() {
        relaunch(["-demoChoose"])
        // Nothing is connected or controllable until a BedJet is picked.
        XCTAssertTrue(app.navigationBars["Choose Your BedJet"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.tabBars.firstMatch.exists)
        let partners = row("BEDJET-DEMO · 5F2C")
        XCTAssertTrue(partners.waitForExistence(timeout: 5))
        XCTAssertTrue(row("BEDJET-DEMO · D3A0").exists)
        capture("choose-first")
        partners.tap()

        XCTAssertTrue(pill("BEDJET-DEMO").waitForExistence(timeout: 5))
        XCTAssertTrue(app.tabBars.buttons["Remote"].exists)
    }

    func testForgettingTheBedJetOnScreenAsksWhichToShow() {
        XCTAssertTrue(app.buttons["Heat"].waitForExistence(timeout: 10))
        addSecondBedJet(from: pill("BEDJET-DEMO"))

        pill("BEDJET-DEMO · 5F2C").tap()
        app.buttons["BedJet Settings"].tap()
        let forget = app.buttons["Forget This BedJet"]
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        for _ in 0..<4 where !forget.isHittable { app.swipeUp() }
        forget.tap()

        // The other BedJet is offered rather than shown in its place.
        XCTAssertTrue(app.navigationBars["Choose Your BedJet"].waitForExistence(timeout: 5))
        let remaining = row("BEDJET-DEMO")
        XCTAssertTrue(remaining.waitForExistence(timeout: 5))
        XCTAssertTrue(row("BEDJET-DEMO · 5F2C").waitForExistence(timeout: 5), "the forgotten BedJet is nearby again")
        capture("after-forget")
        remaining.tap()
        XCTAssertTrue(pill("BEDJET-DEMO").waitForExistence(timeout: 5))
    }

    func testAnAbsentBedJetIsNotSwappedForAnother() {
        relaunch(["-demoFirstUnitAway"])
        // Lull keeps looking for the chosen BedJet, then says it can't find it, with others right there.
        let notFound = app.buttons["BedJet, BEDJET-DEMO, Not found"]
        XCTAssertTrue(notFound.waitForExistence(timeout: 25))
        notFound.tap()
        let another = app.buttons["Choose Another BedJet"]
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        for _ in 0..<3 where !another.exists { app.swipeUp() }
        XCTAssertTrue(another.waitForExistence(timeout: 5))
        capture("not-found")
        another.tap()
        let other = row("BEDJET-DEMO · 5F2C")
        XCTAssertTrue(other.waitForExistence(timeout: 5))
        other.tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()

        // Only because it was picked; the missing one is still there to go back to.
        let picked = pill("BEDJET-DEMO · 5F2C")
        XCTAssertTrue(picked.waitForExistence(timeout: 5))
        picked.tap()
        XCTAssertTrue(app.buttons["BEDJET-DEMO · D3A0"].waitForExistence(timeout: 5))
    }

    func testRemoteAndDevice() {
        let heat = app.buttons["Heat"]
        XCTAssertTrue(heat.waitForExistence(timeout: 10))
        sleep(2)
        heat.tap()
        // Turning a mode on asks for the longest run the simulated BedJet allows: 4 hours of heat.
        let timer = app.sliders["Turns off in"]
        XCTAssertTrue(timer.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "value == '4 hours'"), evaluatedWith: timer)
        waitForExpectations(timeout: 5)

        // A shorter time picked by hand sticks, and survives a fan change.
        slide(timer, from: 0.97, to: 0)
        sleep(3)
        XCTAssertEqual(timer.value as? String, "5 minutes")
        // Fan starts at 40%, a third of the way along.
        slide(app.sliders["Fan"], from: 0.37, to: 0.8)
        sleep(3)
        XCTAssertEqual(timer.value as? String, "5 minutes")

        // Switching mode starts over at that mode's longest run.
        app.buttons["Cool"].tap()
        expectation(for: NSPredicate(format: "value == '12 hours'"), evaluatedWith: timer)
        waitForExpectations(timeout: 5)
        app.buttons["Heat"].tap()
        expectation(for: NSPredicate(format: "value == '4 hours'"), evaluatedWith: timer)
        waitForExpectations(timeout: 5)
        capture("remote-heat")
        app.buttons["Off"].tap()

        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'BedJet'")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Saved on the BedJet"].waitForExistence(timeout: 5) || app.staticTexts["SAVED ON THE BEDJET"].waitForExistence(timeout: 2))
        capture("device")
    }

    func testWiFiAndFirmwareCheck() {
        XCTAssertTrue(app.buttons["Heat"].waitForExistence(timeout: 10))
        sleep(2)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'BedJet'")).firstMatch.tap()
        // The sheet's list only makes rows as they scroll into view.
        let wifi = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Network'")).firstMatch
        XCTAssertTrue(app.staticTexts["Kind, BedJet 3"].waitForExistence(timeout: 10))
        for _ in 0..<8 where !(wifi.exists && wifi.isHittable) { app.swipeUp() }
        wifi.tap()

        // A demo BedJet's phone is on a pretend network, offered first, and the simulated BedJet joins any network,
        // testing it one step a second.
        let home = app.buttons["Home, This iPhone's network"]
        XCTAssertTrue(home.waitForExistence(timeout: 5))
        XCTAssertTrue(home.isSelected)
        let password = app.textFields["Password"]
        password.tap()
        password.typeText("12345678\n")
        let join = app.buttons["Join Network"]
        join.tap()
        // What the BedJet is doing takes the button's title, and how it went goes below once the title is back.
        XCTAssertTrue(join.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Your BedJet joined “Home” and can now check for firmware updates."].waitForExistence(timeout: 15))
        XCTAssertTrue(join.exists)
        capture("wifi")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Home"].waitForExistence(timeout: 5))
        let check = app.buttons["Check for Update"]
        for _ in 0..<8 where !(check.exists && check.isHittable) { app.swipeUp() }
        check.tap()
        XCTAssertTrue(app.alerts.buttons["Check"].waitForExistence(timeout: 5))
        capture("firmware-confirm")
        app.alerts.buttons["Check"].tap()
        // Like a real BedJet 3, the simulated one restarts to check, and the sheet says so rather than losing it.
        XCTAssertTrue(app.buttons["Restarting your BedJet to check…"].waitForExistence(timeout: 10))
        XCTAssertFalse(check.exists)
        capture("firmware-restarting")
        XCTAssertTrue(app.staticTexts["Your BedJet's firmware is up to date."].waitForExistence(timeout: 15))
        XCTAssertTrue(check.exists)
        capture("firmware")
    }
}
