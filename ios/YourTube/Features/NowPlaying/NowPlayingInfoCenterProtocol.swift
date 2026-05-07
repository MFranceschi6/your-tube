import Foundation
import MediaPlayer

// MARK: - NowPlayingInfoCenterProtocol

/// Thin protocol over `MPNowPlayingInfoCenter` so unit tests can drive the
/// audio engine without depending on the global singleton (which leaks state
/// between test cases and across `xcodebuild` runs on a real simulator).
///
/// The default conformance is `MPNowPlayingInfoCenter` itself, so the
/// production wiring is unchanged.
protocol NowPlayingInfoCenterProtocol: AnyObject {
    var nowPlayingInfo: [String: Any]? { get set }
}

extension MPNowPlayingInfoCenter: NowPlayingInfoCenterProtocol {}

// MARK: - FakeNowPlayingInfoCenter (test seam)

/// In-memory stand-in used by `AudioEngineTests` and YT-0027 system-parity
/// tests. Defined alongside the protocol so it ships in the same compile
/// unit as the production type and stays co-evolved.
final class FakeNowPlayingInfoCenter: NowPlayingInfoCenterProtocol, @unchecked Sendable {
    private var _info: [String: Any]?

    /// Number of times `nowPlayingInfo` was assigned. Useful for asserting
    /// that the engine pushes an update on every state transition.
    private(set) var setCount: Int = 0

    var nowPlayingInfo: [String: Any]? {
        get { _info }
        set {
            _info = newValue
            setCount += 1
        }
    }

    init() {}

    func reset() {
        _info = nil
        setCount = 0
    }
}
