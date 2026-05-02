EPIC-0: Product Contracts & Baseline

YT-001 Story: Shared contracts. Task: allineare docs/api-contracts.md e docs/design-system.md al MVP. AC: schema playlist v1, error/conflict import, stati UI e a11y documentati. Dep: none. Rischi: drift Android/iOS. Files: docs/*. Val: DOC.
YT-002 Story: Android baseline. Task: stabilizzare moduli/deps minime per MVP. AC: app builda, moduli coerenti, niente feature reale. Dep: YT-001. Rischi: versioni Gradle/JitPack. Files: android/settings.gradle.kts, android/gradle/libs.versions.toml, android/*/build.gradle.kts. Val: A-BUILD.
YT-003 Story: iOS baseline. Task: confermare target iOS 17, scheme, SPM YouTubeKit, app shell. AC: build pulita su iPhone 16 sim. Dep: YT-001. Rischi: SPM pin. Files: ios/YourTube.xcodeproj, ios/YourTube/App/*. Val: I-BUILD.
EPIC-1: Domain & Persistence

YT-010 Story: Android domain. Task: modelli Track, SearchResult, Playlist, error model. AC: mapping e equality testati. Dep: YT-002. Rischi: duplicazione con DB entities. Files: android/core/common o nuovo core/model. Val: A-UNIT.
YT-011 Story: iOS domain. Task: modelli Swift equivalenti e codable playlist export. AC: JSON round-trip conforme docs. Dep: YT-003. Rischi: date ISO8601 inconsistenti. Files: ios/YourTube/Core/Models/*. Val: I-TEST.
YT-012 Story: Android storage. Task: Room entities/DAO per tracks, playlists, history. AC: CRUD, reorder, conflict basics testati in-memory. Dep: YT-010. Rischi: schema migration precoce. Files: android/core/database/*. Val: A-UNIT.
YT-013 Story: iOS storage. Task: SwiftData models/store per playlists, tracks, history. AC: CRUD, reorder, in-memory tests. Dep: YT-011. Rischi: relationship SwiftData fragili. Files: ios/YourTube/Core/Persistence/*, ios/YourTubeTests/*. Val: I-TEST.
YT-014 Story: Settings storage. Task: audio quality pref e cache-clear abstraction per platform. AC: default leggibile/scrivibile, test unitari. Dep: YT-010, YT-011. Rischi: Android DataStore vs iOS UserDefaults parity. Files: android/core/data, ios/YourTube/Features/Settings. Val: A-UNIT, I-TEST in PR separate.
EPIC-2: YouTube Extraction

YT-020 Story: Android search/streams. Task: NewPipeExtractor wrapper search() e streams(videoId). AC: IO dispatcher, error mapping, no URL persistence. Dep: YT-010. Rischi: extractor breakage, bot challenge. Files: android/core/network o core/youtube. Val: A-UNIT.
YT-021 Story: iOS search/streams. Task: YouTubeKit wrapper async equivalente. AC: search results e stream audio ordinati per qualità. Dep: YT-011. Rischi: API YouTubeKit, stream muxed fallback. Files: ios/YourTube/Core/YouTube/*. Val: I-TEST.
YT-022 Story: Extractor fixtures. Task: fixture/error tests condivisi concettualmente. AC: unavailable/network/unknown coperti su entrambe. Dep: YT-020, YT-021. Rischi: fixture obsolete. Files: test resources Android/iOS. Val: A-UNIT, I-TEST.
EPIC-3: Playback, Queue, Background

YT-030 Story: Android playback service. Task: sostituire stub con Media3 MediaSessionService. AC: foreground media playback, notification, manifest ok. Dep: YT-020. Rischi: permessi Android 13+, lifecycle service. Files: android/core/player/*, android/app/src/main/AndroidManifest.xml. Val: A-BUILD.
YT-031 Story: Android player state. Task: controller/repository queue, play/pause/seek/next/prev. AC: fake controller tests idle -> loading -> playing -> paused. Dep: YT-030, YT-012. Rischi: MediaController async. Files: android/core/player, android/core/data. Val: A-UNIT.
YT-032 Story: iOS audio engine. Task: AVAudioSession .playback, AVPlayer wrapper, queue. AC: play/pause/seek/next/prev testabili con fake. Dep: YT-021. Rischi: main-thread AVFoundation. Files: ios/YourTube/Core/Audio/*, Info.plist. Val: I-TEST.
YT-033 Story: iOS lockscreen controls. Task: MPNowPlayingInfoCenter e MPRemoteCommandCenter. AC: metadata, artwork, elapsed/rate aggiornati. Dep: YT-032. Rischi: timer leaks, interruption handling. Files: ios/YourTube/Core/Audio/*. Val: I-TEST.
EPIC-4: Core UI

YT-040 Story: Android shell/design system. Task: Material 3 theme, nav tabs, TrackRow, MiniPlayer skeleton. AC: Search/Library/Settings raggiungibili, a11y labels base. Dep: YT-002. Rischi: premature UI coupling. Files: android/app, android/core/designsystem, android/core/ui. Val: A-BUILD, A-LINT.
YT-041 Story: iOS shell/design system. Task: TabView, tokens, TrackRow, MiniPlayer skeleton. AC: Search/Library/Settings raggiungibili, Dynamic Type rispettato. Dep: YT-003. Rischi: view troppo grandi. Files: ios/YourTube/App, DesignSystem, Features/*. Val: I-BUILD.
YT-042 Story: Android search UX. Task: SearchScreen + ViewModel debounce/loading/error/results/add queue. AC: stati completi, retry, tests ViewModel. Dep: YT-020, YT-040. Rischi: network cancellation. Files: android/feature/search. Val: A-UNIT, A-BUILD.
YT-043 Story: iOS search UX. Task: SearchView + observable model. AC: stessi stati Android, tap result avvia player. Dep: YT-021, YT-041. Rischi: task cancellation SwiftUI. Files: ios/YourTube/Features/Search. Val: I-TEST.
YT-044 Story: Android player UI. Task: Now Playing, scrubber, queue sheet. AC: controls accessibili, progress rendering, mini player tap. Dep: YT-031, YT-040. Rischi: state sync. Files: android/feature/player. Val: A-BUILD, A-UNIT.
YT-045 Story: iOS player UI. Task: Now Playing sheet, scrubber, queue. AC: VoiceOver labels, detents, progress. Dep: YT-032, YT-041. Rischi: sheet/navigation state. Files: ios/YourTube/Features/Player. Val: I-BUILD, I-TEST.
EPIC-5: Library, History, Sharing, Settings

YT-050 Story: Android playlists. Task: create/rename/delete/add/remove/reorder. AC: repository + ViewModel tests. Dep: YT-012, YT-040. Rischi: ordering bugs. Files: android/feature/library, android/core/data. Val: A-UNIT.
YT-051 Story: iOS playlists. Task: stessa feature con SwiftData. AC: CRUD e reorder testati. Dep: YT-013, YT-041. Rischi: SwiftData live updates. Files: ios/YourTube/Features/Library. Val: I-TEST.
YT-052 Story: Android history. Task: append on playback, list, swipe delete. AC: no duplicates noisy, clear works. Dep: YT-031, YT-012. Rischi: race playback/storage. Files: android/feature/history o feature/library, core/data. Val: A-UNIT.
YT-053 Story: iOS history. Task: equivalent history list. AC: append/delete/clear tested. Dep: YT-032, YT-013. Rischi: background writes. Files: ios/YourTube/Features/History. Val: I-TEST.
YT-054 Story: Android playlist export/import. Task: .ytplaylist.json via FileProvider/intent. AC: exported JSON imports back, conflict by updatedAt. Dep: YT-050. Rischi: URI permissions. Files: android/core/sharing o core/data, manifest. Val: A-UNIT, A-BUILD.
YT-055 Story: iOS playlist export/import. Task: UTType, share sheet, .onOpenURL. AC: JSON round-trip and higher schema rejected. Dep: YT-051. Rischi: document type config. Files: ios/YourTube/Core/Sharing, Info.plist. Val: I-TEST, I-BUILD.
YT-056 Story: Android settings. Task: clear cache, audio quality, about. AC: preference persists, cache action safe. Dep: YT-014. Rischi: cache not yet real. Files: android/feature/settings or current settings module creation. Val: A-BUILD, A-UNIT.
YT-057 Story: iOS settings. Task: equivalent settings screen. AC: persists pref, about visible, clear safe. Dep: YT-014. Rischi: parity drift. Files: ios/YourTube/Features/Settings. Val: I-BUILD, I-TEST.
EPIC-6: Cross-Platform QA & Release Readiness

YT-060 Story: Export parity. Task: golden .ytplaylist.json fixtures Android <-> iOS. AC: same fixture imports on both, schema mismatch tested. Dep: YT-054, YT-055. Rischi: date/timezone mismatch. Files: docs, test fixtures. Val: A-UNIT, I-TEST.
YT-061 Story: Manual MVP smoke. Task: checklist for search, play, lockscreen, playlist export/import. AC: Pixel 8 API 35 + iPhone 16 sim/device notes captured. Dep: all MVP feature tasks. Rischi: background audio sim limits. Files: docs/ or README.md. Val: manual plus A-BUILD, I-BUILD.
YT-062 Story: Known risks/readiness. Task: document extractor/legal/background limitations before v0.1.0. AC: user-facing known issues, no store-distribution claims. Dep: YT-061. Rischi: unclear scope creep. Files: README.md, docs/. Val: DOC