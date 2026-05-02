import XCTest

final class YourTubeUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testLaunchShowsSearchPlaceholder() throws {
        let app = XCUIApplication()
        app.launch()
        // The Search tab is selected first; its placeholder text should be visible.
        let label = app.staticTexts["YourTube — Search"]
        XCTAssertTrue(label.waitForExistence(timeout: 5), "Search placeholder text should appear on launch")
    }
}
