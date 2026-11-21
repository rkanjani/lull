import XCTest

extension XCUIApplication {
    /// Scrolls until the element can be tapped, down first and then back up.
    @discardableResult
    func reveal(_ element: XCUIElement) -> XCUIElement {
        func tappable() -> Bool {
            guard element.exists && element.isHittable else { return false }
            // In the editor, a row just under the bottom bar still counts as hittable, but a tap there lands on the bar.
            let bar = buttons["Use Tonight"]
            guard element.label != "Use Tonight", bar.exists, bar.isHittable else { return true }
            return element.frame.maxY < bar.frame.minY - 12
        }
        for _ in 0..<5 where !tappable() { swipeUp() }
        for _ in 0..<10 where !tappable() { swipeDown() }
        sleep(1)
        return element
    }
}
