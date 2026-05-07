import XCTest

/// YT-0032 AC5 — UI smoke for launch and the practical surface of the
/// search-to-player path.
///
/// The full search → resolve stream → MiniPlayer flow requires live
/// YouTube traffic and audio playback; we verify what's testable without
/// network (launch, tab navigation, search-field interaction) here, and
/// rely on manual device testing for the resolve + play step.
final class YourTubeUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Launch smoke — the app must come up on the Search tab with the
    /// search field reachable.
    func testLaunchShowsSearchTab() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Search"].waitForExistence(timeout: 5),
                      "Search tab bar button should exist on launch")
        XCTAssertTrue(app.textFields["Search"].waitForExistence(timeout: 5),
                      "Search text field should be visible in the Search tab")
    }

    /// Search-to-player path (no network): launch, focus the search field,
    /// type a query, confirm typed text propagates. The actual resolve →
    /// MiniPlayer step depends on live network and is verified manually on
    /// device — flagged in the suite header.
    func testSearchFieldAcceptsInputAfterLaunch() throws {
        let app = XCUIApplication()
        app.launch()

        let searchField = app.textFields["Search"]
        XCTAssertTrue(searchField.waitForExistence(timeout: 5),
                      "Search text field should be visible in the Search tab")

        searchField.tap()
        searchField.typeText("lofi")
        XCTAssertEqual(searchField.value as? String, "lofi",
                       "Search field should reflect the typed query")
    }

    /// Tab-bar navigation smoke — Search → Library → Settings → Search.
    /// Confirms the app shell wires every MVP destination and is reachable
    /// from a cold launch.
    func testTabBarNavigatesAcrossAllDestinations() throws {
        let app = XCUIApplication()
        app.launch()

        let tabBar = app.tabBars.element
        XCTAssertTrue(tabBar.waitForExistence(timeout: 5), "Tab bar should exist")

        let library = tabBar.buttons["Library"]
        XCTAssertTrue(library.waitForExistence(timeout: 2), "Library tab should exist")
        library.tap()

        let settings = tabBar.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 2), "Settings tab should exist")
        settings.tap()

        let search = tabBar.buttons["Search"]
        XCTAssertTrue(search.waitForExistence(timeout: 2), "Search tab should exist")
        search.tap()

        XCTAssertTrue(app.textFields["Search"].waitForExistence(timeout: 2),
                      "Search field should re-appear after returning to the Search tab")
    }
}
