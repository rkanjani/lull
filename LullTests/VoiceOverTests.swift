import Accessibility
import UIKit
import XCTest

/// Uses the running app the way VoiceOver does, through the same accessibility interface: it reads each
/// element's label, value, hint and traits, double-taps by activating, swipes up and down on adjustable
/// elements, and runs what the Actions rotor offers. Runs inside the app on the simulator, against the
/// simulated BedJet.
@MainActor
final class VoiceOverTests: XCTestCase {
    private struct Missing: Error {}

    override func setUpWithError() throws {
        // iOS builds the accessibility interface only while an assistive technology is running;
        // this turns it on the way UI automation does.
        let library = dlopen("/usr/lib/libAccessibility.dylib", RTLD_NOW)
        let symbol = try XCTUnwrap(dlsym(library, "_AXSSetAutomationEnabled"))
        unsafeBitCast(symbol, to: (@convention(c) (Int32) -> Void).self)(1)
        try dismissPresented()
        try wait("the BedJet to connect") { self.element(prefix: "BedJet, ", in: self.window) }
    }

    // MARK: Screens

    func testRemote() throws {
        try showTab("Remote")
        XCTAssertTrue(try find("Remote").accessibilityTraits.contains(.header))
        XCTAssertEqual(try find(prefix: "BedJet, ").accessibilityHint, "Shows the connection, BedJet settings and help finding it")
        if let off = element("Off"), !off.accessibilityTraits.contains(.selected) { activate(off) }
        let orb = try wait("the orb to read Off") { self.element(prefix: "Off, room ") }
        XCTAssertTrue(orb.accessibilityTraits.contains(.updatesFrequently))

        activate(try find("Heat"))
        try wait("the orb to read Heating") { self.element(prefix: "Heating, set to ") }
        try wait("Heat to be selected") { self.element("Heat").flatMap { $0.accessibilityTraits.contains(.selected) ? $0 : nil } }

        // The timer starts as a duration, which is the default.
        if let timerOption = element("Timer"), !timerOption.accessibilityTraits.contains(.selected) { activate(timerOption) }
        // Swiping down and up on the timer moves it a step at a time, and each step is read in words.
        try wait("the timer to read 4 hours") { self.element("Turns off in").flatMap { $0.accessibilityValue == "4 hours" ? $0 : nil } }
        let timer = try find("Turns off in")
        XCTAssertTrue(timer.accessibilityTraits.contains(.adjustable))
        timer.accessibilityDecrement()
        try wait("the timer to read 3 hours, 55 minutes") { self.element("Turns off in").flatMap { $0.accessibilityValue == "3 hours, 55 minutes" ? $0 : nil } }
        try find("Turns off in").accessibilityIncrement()
        try wait("the timer to read 4 hours again") { self.element("Turns off in").flatMap { $0.accessibilityValue == "4 hours" ? $0 : nil } }

        // Shown as a time instead: its name is read before the picker, and then how long is left.
        activate(try find("Time"))
        try wait("Time to be selected") { self.element("Time").flatMap { $0.accessibilityTraits.contains(.selected) ? $0 : nil } }
        try wait("the time to show") { self.element("Turns off at") }
        XCTAssertNotNil(element(prefix: "In "))
        activate(try find("Timer"))
        try wait("the timer again") { self.element("Turns off in") }

        let fan = try find("Fan")
        XCTAssertTrue(fan.accessibilityTraits.contains(.adjustable))
        let percent = Int(fan.accessibilityValue?.dropLast() ?? "") ?? 0
        fan.accessibilityIncrement()
        try wait("the fan to go up 5%") { self.element("Fan").flatMap { $0.accessibilityValue == "\(percent + 5)%" ? $0 : nil } }

        let temperature = try find("Temperature")
        XCTAssertTrue(temperature.accessibilityTraits.contains(.adjustable))
        XCTAssertTrue(temperature.accessibilityValue?.hasSuffix("°") == true)

        activate(try find("Off"))
        try wait("the orb to read Off again") { self.element(prefix: "Off, room ") }
    }

    func testSchedule() throws {
        try showTab("Schedule")
        let tonight = try find(prefix: "Tonight, ")
        XCTAssertTrue(tonight.accessibilityTraits.contains(.header))
        let name = String(tonight.accessibilityLabel!.dropFirst("Tonight, ".count))

        // The long-press menu's choices are in the Actions rotor, on both the Edit button and each row.
        let edit = try find("Edit \(name)")
        XCTAssertEqual(edit.accessibilityCustomActions?.map(\.name), ["Duplicate", "Delete"])

        let timeline = try find(prefix: "Night timeline, ")
        XCTAssertTrue(timeline.accessibilityValue?.hasPrefix("Phase 1, ") == true)
        XCTAssertTrue(timeline.responds(to: NSSelectorFromString("accessibilityChartDescriptor")), "no audio graph")
        let chart = try XCTUnwrap(timeline.value(forKey: "accessibilityChartDescriptor") as? AXChartDescriptor, "no audio graph")
        XCTAssertEqual(chart.title, name)
        XCTAssertFalse(chart.series.first?.dataPoints.isEmpty ?? true)

        let rows = elements().filter { $0.accessibilityHint == "Edits this schedule" }
        let row = try XCTUnwrap(rows.first)
        let other = String(row.accessibilityLabel!.prefix { $0 != "," })
        XCTAssertEqual(row.accessibilityCustomActions?.map(\.name), ["Duplicate", "Delete"])
        // The copy goes at the end of the list, named within the BedJet's 15 characters.
        let copyName = String("\(other) copy".prefix(15))
        func copies() -> [NSObject] {
            scrollToEnd()
            return elements().filter { $0.accessibilityLabel?.hasPrefix("\(copyName), ") == true }
        }
        let existing = copies().count
        perform("Duplicate", on: row)
        let copy = try wait("the copy to appear") { copies().count == existing + 1 ? copies().last : nil }
        perform("Delete", on: copy)
        try wait("the copy to go") { copies().count == existing ? row : nil }
        scrollToTop()

        activate(try find("Use \(other) tonight"))
        try wait("\(other) to become tonight's") { self.element("Tonight, \(other)") }
        activate(try find("Use \(name) tonight"))
        try wait("\(name) to be tonight's again") { self.element("Tonight, \(name)") }

        activate(try find("Start Night"))
        try wait("End Night") { self.element("End Night") }
        let phase = try wait("the running phase") { self.element { $0.accessibilityLabel?.hasSuffix(" left") == true } }
        print("VO| running: \(phase.accessibilityLabel ?? "")")
        activate(try find("End Night"))
        try wait("Start Night again") { self.element("Start Night") }
    }

    func testEditor() throws {
        try showTab("Schedule")
        activate(try find { $0.accessibilityHint == "Edits this schedule" })
        let field = try wait("the editor") { self.element("Schedule name") }
        XCTAssertEqual(field.accessibilityHint, "Up to 15 characters")
        XCTAssertTrue(try find("Phases").accessibilityTraits.contains(.header))

        // Each phase reads in full, and can be removed from the Actions rotor.
        let phases = elements().filter { $0.accessibilityHint == "Edits this phase" }
        XCTAssertGreaterThan(phases.count, 1)
        XCTAssertTrue(phases[0].accessibilityLabel?.hasPrefix("Phase 1, ") == true)
        XCTAssertTrue(phases[0].accessibilityLabel?.contains(", starts ") == true)
        perform("Remove Phase", on: phases.last!)
        try wait("a phase to be removed") {
            self.elements().filter { $0.accessibilityHint == "Edits this phase" }.count == phases.count - 1 ? field : nil
        }

        activate(try find(prefix: "Phase 1, "))
        let duration = try wait("the phase sheet") { self.element("Duration") }
        // The editor still shows above the sheet, but VoiceOver stays inside the sheet.
        XCTAssertTrue(isModal(screen), "VoiceOver can leave the phase sheet")
        XCTAssertTrue(try find("Phase").accessibilityTraits.contains(.header))
        // The phase's mode reads as selected once the sheet has settled.
        try wait("the phase's mode to read as selected") {
            self.element { $0.accessibilityTraits.contains(.selected) && $0.accessibilityTraits.contains(.button) }
        }
        XCTAssertTrue(duration.accessibilityTraits.contains(.adjustable))
        // Swipe down a step, or up if it's already as short as it goes.
        let before = duration.accessibilityValue
        duration.accessibilityDecrement()
        RunLoop.main.run(until: Date().addingTimeInterval(1))
        if element("Duration")?.accessibilityValue == before { try find("Duration").accessibilityIncrement() }
        try wait("the duration to change") { self.element("Duration").flatMap { $0.accessibilityValue != before ? $0 : nil } }
        activate(try find("Done"))
        try wait("the editor again") { self.element("Schedule name") }

        // The night ends at a wake time or runs from the usual bedtime, chosen as the Remote chooses Timer or Time. With a
        // wake time the editor shows it under the usual bedtime, and the phases still read when they start on a usual night.
        XCTAssertTrue(try find("Bedtime").accessibilityTraits.contains(.selected))
        XCTAssertNotNil(element("Usual bedtime"))
        func wakeTimes() -> Int { elements().filter { $0.accessibilityLabel == "Wake time" }.count }
        XCTAssertEqual(wakeTimes(), 1, "only the switch's option")
        activate(try find("Wake time"))
        // The switch reads as chosen a moment before the card below it changes, so wait for the card.
        try wait("the wake time to show") { wakeTimes() == 2 ? field : nil }
        XCTAssertFalse(try find("Bedtime").accessibilityTraits.contains(.selected))
        XCTAssertNotNil(element("Usual bedtime"))
        let rows = elements().filter { $0.accessibilityHint == "Edits this phase" }
        XCTAssertTrue(rows.allSatisfy { $0.accessibilityLabel?.contains(", starts ") == true }, "\(rows.map(\.accessibilityLabel))")
        // The starter's opening phase keeps its length, read in its label.
        let first = try find(prefix: "Phase 1, ").accessibilityLabel ?? ""
        XCTAssertTrue(first.contains(", fixed length"), first)
        let last = try XCTUnwrap(rows.last)
        print("VO| last phase: \(last.accessibilityLabel ?? "")")
        // The last is the only phase that stretches or shrinks, so its sheet reads its length without a slider to change it.
        activate(last)
        let fixed = try wait("the Fixed length switch") { self.element("Fixed length") }
        let length = try find("Duration")
        XCTAssertFalse(length.accessibilityTraits.contains(.adjustable), "the rest of the night can't be set")
        XCTAssertNotNil(length.accessibilityValue)
        // Its switch to keep its length reads its new state when double-tapped.
        let was = fixed.accessibilityValue
        activate(fixed)
        try wait("the switch to turn on") { self.element("Fixed length").flatMap { $0.accessibilityValue != was ? $0 : nil } }
        activate(try find("Done"))
        try wait("the last phase to read as fixed") {
            self.element { $0.accessibilityHint == "Edits this phase" && $0.accessibilityLabel?.hasPrefix("Phase \(rows.count), ") == true
                && $0.accessibilityLabel?.contains(", fixed length") == true }
        }
        // Back on Bedtime, only the usual bedtime.
        activate(try find("Bedtime"))
        try wait("the wake time to go") { wakeTimes() == 1 ? field : nil }

        // Cancel drops the changes.
        if let cancel = element("Cancel"), cancel.accessibilityActivate() {
            try waitUntilGone("the editor") { self.element("Schedule name") }
        }
    }

    func testBedJetSheet() throws {
        try showTab("Remote")
        activate(try find(prefix: "BedJet, "))
        let ring = try wait("the BedJet sheet") { self.element("Light ring") }
        XCTAssertTrue(isModal(screen), "VoiceOver can leave the BedJet sheet")
        // Double-tapping a switch flips it and VoiceOver reads the new state.
        let was = ring.accessibilityValue
        activate(ring)
        try wait("the light ring to switch") { self.element("Light ring").flatMap { $0.accessibilityValue != was ? $0 : nil } }
        activate(try find("Light ring"))
        try wait("the light ring to switch back") { self.element("Light ring").flatMap { $0.accessibilityValue == was ? $0 : nil } }
        // The BedJet's three slots, the one Lull uses said in words rather than “Lull · name”. On a shorter
        // screen they're further down than the list has made rows for yet.
        func slots() -> [NSObject] { elements().filter { $0.accessibilityLabel?.hasPrefix("Slot ") == true } }
        try wait("the BedJet's slots to load") {
            if slots().count == 3 { return ring }
            self.scrollOn()
            return nil
        }
        XCTAssertFalse(slots().contains { $0.accessibilityLabel?.contains("·") == true })
        // The menu picker reads its choice as part of its label: “Temperature, Fahrenheit”.
        scrollToEnd()
        let units = try find(prefix: "Temperature")
        XCTAssertTrue(["Temperature, Fahrenheit", "Temperature, Celsius"].contains(units.accessibilityLabel ?? ""),
                      "units read as “\(units.accessibilityLabel ?? "")” “\(units.accessibilityValue ?? "")”")
        try dismissPresented()
    }

    func testHistory() throws {
        try showTab("History")
        XCTAssertTrue(try find("History").accessibilityTraits.contains(.header))
        guard let night = element({ $0.accessibilityValue == "Collapsed" }) else {
            XCTAssertNotNil(element("No nights yet"))
            return
        }
        let label = night.accessibilityLabel
        activate(night)
        try wait("the night to open") { self.element { $0.accessibilityLabel == label && $0.accessibilityValue == "Expanded" } }
    }

    // MARK: What VoiceOver does

    private var window: UIWindow {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow }!
    }

    /// What VoiceOver can reach: the frontmost sheet or screen, never what's beneath it.
    private var screen: UIView {
        var controller = window.rootViewController
        while let presented = controller?.presentedViewController, !presented.isBeingDismissed { controller = presented }
        return controller?.view ?? window
    }

    /// Every element VoiceOver lands on, in the order it reads them.
    private func elements(in root: NSObject? = nil) -> [NSObject] {
        var found: [NSObject] = []
        func collect(_ object: NSObject) {
            if object.accessibilityElementsHidden { return }
            if object.isAccessibilityElement { found.append(object); return }
            if let children = object.accessibilityElements as? [NSObject] { children.forEach(collect); return }
            (object as? UIView)?.subviews.filter { !$0.isHidden && $0.alpha > 0 }.forEach(collect)
        }
        collect(root ?? screen)
        return found
    }

    private func element(_ label: String, in root: NSObject? = nil) -> NSObject? {
        elements(in: root).first { $0.accessibilityLabel == label }
    }

    private func element(prefix: String, in root: NSObject? = nil) -> NSObject? {
        elements(in: root).first { $0.accessibilityLabel?.hasPrefix(prefix) == true }
    }

    private func element(_ matches: (NSObject) -> Bool) -> NSObject? {
        elements().first(where: matches)
    }

    private func find(_ label: String) throws -> NSObject {
        try XCTUnwrap(element(label), "VoiceOver can't find “\(label)”")
    }

    private func find(prefix: String) throws -> NSObject {
        try XCTUnwrap(element(prefix: prefix), "VoiceOver can't find anything starting “\(prefix)”")
    }

    private func find(_ matches: (NSObject) -> Bool) throws -> NSObject {
        try XCTUnwrap(element(matches), "VoiceOver can't find the element")
    }

    /// A double-tap.
    private func activate(_ element: NSObject, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.accessibilityActivate(), "double-tapping “\(element.accessibilityLabel ?? "")” did nothing", file: file, line: line)
    }

    /// Choosing an action from the Actions rotor.
    private func perform(_ name: String, on element: NSObject, file: StaticString = #filePath, line: UInt = #line) {
        guard let action = element.accessibilityCustomActions?.first(where: { $0.name == name }) else {
            return XCTFail("“\(element.accessibilityLabel ?? "")” has no \(name) action", file: file, line: line)
        }
        if let handler = action.actionHandler {
            XCTAssertTrue(handler(action), file: file, line: line)
        } else {
            _ = (action.target as AnyObject?)?.perform(action.selector, with: action)
        }
    }

    /// Three-finger swipes to the end of the screen; VoiceOver only finds rows once they're scrolled into view.
    private func scrollToEnd() {
        guard let scroll = scrollView else { return }
        let bottom = scroll.contentSize.height + scroll.adjustedContentInset.bottom - scroll.bounds.height
        scroll.setContentOffset(CGPoint(x: 0, y: max(bottom, -scroll.adjustedContentInset.top)), animated: false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
    }

    /// Scrolls half a screen further, and back to the top after the end, so rows the list hasn't made yet appear.
    private func scrollOn() {
        guard let scroll = scrollView else { return }
        let top = -scroll.adjustedContentInset.top
        let bottom = scroll.contentSize.height + scroll.adjustedContentInset.bottom - scroll.bounds.height
        let next = scroll.contentOffset.y + scroll.bounds.height / 2
        scroll.setContentOffset(CGPoint(x: 0, y: next > bottom + 1 ? top : min(next, max(bottom, top))), animated: false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
    }

    private func scrollToTop() {
        guard let scroll = scrollView else { return }
        scroll.setContentOffset(CGPoint(x: 0, y: -scroll.adjustedContentInset.top), animated: false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
    }

    private var scrollView: UIScrollView? {
        func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
        return views(screen).compactMap { $0 as? UIScrollView }.filter { $0.window != nil && !$0.isHidden }
            .max { $0.contentSize.height < $1.contentSize.height }
    }

    private func showTab(_ name: String) throws {
        let tab = try XCTUnwrap(element(name, in: window), "no \(name) tab")
        if !tab.accessibilityActivate() {
            // System tab buttons leave it to VoiceOver to tap them.
            let tabs = try XCTUnwrap(controllers(window.rootViewController).compactMap { $0 as? UITabBarController }.first)
            tabs.selectedIndex = ["Remote", "Schedule", "History"].firstIndex(of: name)!
        }
        try wait("the \(name) screen") { self.element(name).flatMap { $0.accessibilityTraits.contains(.header) ? $0 : nil } }
    }

    /// Whether VoiceOver is held inside this view, as it is for a sheet, instead of reaching what's behind it:
    /// either the view or one it sits in is modal, or the SwiftUI content inside it is.
    private func isModal(_ view: UIView) -> Bool {
        var current: UIView? = view
        while let candidate = current, !(candidate is UIWindow) {
            if candidate.accessibilityViewIsModal { return true }
            current = candidate.superview
        }
        func modalInside(_ object: NSObject) -> Bool {
            if object.accessibilityViewIsModal { return true }
            if object.isAccessibilityElement { return false }
            if let children = object.accessibilityElements as? [NSObject] { return children.contains(where: modalInside) }
            return (object as? UIView)?.subviews.contains(where: modalInside) ?? false
        }
        return modalInside(view)
    }

    private func controllers(_ root: UIViewController?) -> [UIViewController] {
        guard let root else { return [] }
        return [root] + root.children.flatMap(controllers)
    }

    private func dismissPresented() throws {
        window.rootViewController?.dismiss(animated: false)
        try waitUntilGone("the sheet to close") { self.window.rootViewController?.presentedViewController }
    }

    @discardableResult
    private func wait(_ what: String, timeout: TimeInterval = 10, _ find: () -> NSObject?) throws -> NSObject {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let found = find() { return found }
            RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        } while Date() < deadline
        XCTFail("Timed out waiting for \(what)")
        throw Missing()
    }

    private func waitUntilGone(_ what: String, timeout: TimeInterval = 10, _ find: () -> NSObject?) throws {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if find() == nil { return }
            RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        } while Date() < deadline
        XCTFail("Timed out waiting for \(what) to go")
        throw Missing()
    }
}
