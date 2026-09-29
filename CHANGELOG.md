# Changelog

All notable changes to HAT are documented here.

HAT is released through **GitHub Releases only** — it is intentionally not
published to Google Play or F-Droid. See [RELEASE.md](RELEASE.md) for the
build, signing, and tagging procedure.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and HAT uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [1.1.2] - Unreleased

Not yet tagged. This release corrects the time arithmetic, closes a crash, and
adds theme customization.

### Added

- **Custom accent colour picker.** Settings → THEME ENGINE now offers a live
  preview and a hex field; the chosen colour drives the whole app's palette.
  Requires Android 12+ for wallpaper-derived colours; below that, the custom
  accent is used for both theme modes.
- **One-time support ask.** The first time a user opens theme customization, a
  single non-blocking dialog explains that HAT is maintained by one person and
  offers a tip. It is shown at most once per install, never on launch, and any
  way of dismissing it is permanent. It hands off to the browser via
  `Intent.ACTION_VIEW`, so HAT still makes no network request and still
  declares no `INTERNET` permission.
- **68 JVM unit tests** covering day-boundary arithmetic, DST transitions,
  corrupt-interval handling, clustering thresholds, and integer width.

### Fixed

- **Crash:** an offline-activity label containing `/` (e.g. `Work/Study`)
  produced an unresolvable navigation route and closed the app. Titles are now
  URI-encoded into the route and decoded on the way back.
- **Wrong totals:** the dashboard, the daily summary export, and the
  background ledger each summed labelled offline time without subtracting the
  app usage it overlapped, so a day could report more time than it contained.
  Offline time is now sharded against app time in all three.
- **Wrong event handling:** Android event 26 (`DEVICE_SHUTDOWN`) was treated as
  screen-off. Screen-off is event 16; 26 now means what it says.
- **Integer overflow** in five duration-threshold calculations, from
  multiplying before widening to `Long`. A repo-wide guard and a CI check were
  added.
- **Session totals** showed the wall-clock span between the first and last
  event rather than the sum of active time, inflating every multi-session total.
- **Crash recovery screen** rendered in the app accent instead of red.
  `Theme.kt` was discarding the stored accent colour entirely, so no custom
  colour could ever take effect.
- **`hasSeenTutorialPrompt`** was written to settings and read by nothing; the
  field has been removed.

### Changed

- `README.md`, `TERMS.md`, `RELEASE.md`, and the Fastlane metadata no longer
  describe the Master Vault as "encrypted". The export is an HMAC-SHA256
  signature for tamper detection over plain zip entries. `PRIVACY.md` and
  `TERMS.md` already said so correctly.
- `README.md` and `RELEASE.md` state API level 36, matching the actual toolchain.
- `RELEASE.md`'s "current blockers" list no longer claims there is no CI and no
  test suite.
- `TERMS.md` and the in-app Open Source Licenses screen now list every
  dependency actually declared in `app/build.gradle.kts`, satisfying AGPL §5/§6.

### Security

- The unused `FOREGROUND_SERVICE_DATA_SYNC` permission is stripped from the
  merged manifest. HAT runs no foreground service.
- **A release build now fails** if `key.properties` is missing, instead of
  quietly producing a debug-signed artifact. A debug-signed release can never
  be replaced by a signed one, and the key is not recoverable. CI passes
  `-PallowDebugSignedRelease=true` for throwaway verification builds.
- `app/proguard-rules.pro` is no longer empty. The release build sets
  `isMinifyEnabled = true`, so R8 was running with no keep rules for
  `kotlinx.serialization`, WorkManager workers, and ViewModels.
- CI now builds a release APK, runs `lintRelease`, and **fails if `INTERNET`
  ever appears in a release manifest** — the privacy claim is asserted, not
  assumed.

### Added (documentation)

- `CONTRIBUTING.md`, including the AGPL §5 contribution-assignment clause.
  Without it, every merged pull request would permanently lock that
  contributor's work to AGPL.
- This changelog, which was previously gitignored.

## [1.1.1]

- Idle App Detection setting exposed in the UI.
- Clipboard Markdown export and a persistent background ledger.
- Zip-Slip path traversal fixed in vault extraction.
- Manifest backup rules hardened and vault import exceptions tightened.
- Gradle wrapper secured with a SHA256 checksum.

## [1.1.0]

- Initial public release of the rewritten timeline UI.

[Unreleased]: https://github.com/andromedvn/HAT/compare/v1.1.1...HEAD
[1.1.2]: https://github.com/andromedvn/HAT/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/andromedvn/HAT/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/andromedvn/HAT/releases/tag/v1.1.0
