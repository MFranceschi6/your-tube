# Update Channel

Self-hosted update distribution for YourTube via GitHub Pages.

This document describes what must be published to GitHub Pages for each release, the metadata contract, and signing expectations for Android.

---

## Android

### Metadata path

```
https://<github-user>.github.io/<repo-name>/android/update.json
```

The URL is set in `UpdateCheckRepository.DEFAULT_FEED_URL` in
`android/core/data/src/main/kotlin/com/yourtube/core/data/update/UpdateCheckRepository.kt`.
Replace the placeholder before the first public release.

### Metadata JSON schema

```json
{
  "versionName": "1.2.0",
  "versionCode": 12,
  "publishedAt": "2026-05-09T12:00:00Z",
  "notes": "Bug fixes and performance improvements.",
  "apkUrl": "https://<github-user>.github.io/<repo-name>/android/yourtube-1.2.0.apk",
  "minimumSupportedVersionCode": 5,
  "sha256": "abc123..."
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `versionName` | string | yes | Human-readable version (e.g. `"1.2.0"`). Android-only field name (iOS uses `version`). |
| `versionCode` | integer | yes | Android build number. Monotonically increasing. Used for `<`/`=` comparisons — NOT string comparison. Android-only field name (iOS uses `build`). |
| `publishedAt` | string | yes | ISO 8601 UTC timestamp. Field name shared with iOS. |
| `notes` | string | yes | Release notes shown to the user in the update prompt. Field name shared with iOS. |
| `apkUrl` | string | yes | Direct `.apk` download URL. Android-only field name (iOS uses `installUrl`). |
| `minimumSupportedVersionCode` | integer | yes | Builds below this code see a blocking update-required screen before the main experience. Android-only field name (iOS uses `minimumSupportedBuild`). |
| `sha256` | string | no | SHA-256 checksum of the `.apk` for integrity verification. Optional for MVP. Android-only field. |

### Update states

| Condition | State |
|-----------|-------|
| `versionCode > installedVersionCode` | `UpdateAvailable` — non-blocking prompt |
| `minimumSupportedVersionCode > installedVersionCode` | `UpdateRequired` — blocking screen, user cannot dismiss |
| All other cases | `UpToDate` — no prompt |

Network failure, malformed JSON, or HTTP error → `CheckFailed` (fail-safe, playback continues; Settings shows a dismissible error note).

### APK artifact

Publish the signed release `.apk` at the path referenced by `apkUrl`. Recommended convention:

```
/android/yourtube-<versionName>.apk
```

### Install handoff: browser handoff (MVP)

When the user taps "Update", the app opens `apkUrl` in the system browser via `Intent.ACTION_VIEW`. The browser handles the download; the user installs from the Downloads notification.

**Why browser handoff:**
- `REQUEST_INSTALL_PACKAGES` is required for in-app APK delivery. This permission is intentionally absent from the manifest for MVP to minimize permission surface.
- No `DownloadManager` or `FileProvider` plumbing is needed.
- Trade-off: the install experience is rougher than Play's in-app update. Acceptable for a small install base.

If the user leaves the browser without installing, the prompt reappears on next launch (no persistent "dismissed" state is stored).

**Known trade-off — offline sub-minimum builds:** If the device is offline and the installed build is below `minimumSupportedVersionCode`, the update check returns `CheckFailed` rather than `UpdateRequired`. The blocking gate is not shown and the user can enter the main experience. This is accepted for MVP: the gate is enforced on the next successful check when connectivity is restored. Caching the last successful `UpdateRequired` result in DataStore would close this gap post-MVP.

### Signing expectations

The published `.apk` **must be signed with the same certificate lineage** as the installed build. Android's package manager rejects upgrades signed with a different key. Key rotation via the `--lineage` option in `apksigner` is supported from Android 9+ if needed.

**Never commit keystores, passwords, or signing configs to the repository.**

The current `app/build.gradle.kts` uses debug signing for all variants. Before the first public release:
1. Generate a release keystore and store it securely (password manager, CI secret store).
2. Configure a `release` signing config referencing the keystore via environment variables or CI secrets.
3. Ensure all subsequent releases use the same keystore.

---

## iOS

The iOS mirror is tracked in YT-0250. Android and iOS use **separate JSON files** published to separate paths on GitHub Pages. Field names deliberately diverge:

| Concept | Android field | iOS field |
|---------|--------------|-----------|
| Human-readable version | `versionName` | `version` |
| Build number | `versionCode` | `build` |
| Release timestamp | `publishedAt` | `publishedAt` |
| Release notes | `notes` | `notes` |
| Install URL | `apkUrl` | `installUrl` |
| Minimum supported build | `minimumSupportedVersionCode` | `minimumSupportedBuild` |

**Why diverge:** iOS and Android have fundamentally different build numbering semantics (`versionCode` is an integer; `build` on iOS is typically a string like `"26.4.1"`). Forcing a single JSON would require the server to carry both fields anyway. Two separate files with platform-idiomatic naming is cleaner and keeps each client's deserialization model simple.
