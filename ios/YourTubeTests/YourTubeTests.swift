import Testing
@testable import YourTube

@Suite("YourTube smoke")
struct YourTubeTests {
    @Test func appBuilds() {
        #expect(true)
    }
}
