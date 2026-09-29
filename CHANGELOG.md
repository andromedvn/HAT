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

**Numbers were wrong**

- A phone left unlocked but idle in a pocket counted as screen time. HAT only
  watched the screen turning off and never handled the lock event, so that time
  was never recognised as offline. Both signals are now handled.
- Setting the idle-detection threshold to 0 returned nothing at all, because 0
  was internally replaced with "the largest possible value". It now means "no
  minimum", which is what you asked for.
- A damaged or hand-edited vault file could add time that has not happened yet,
  or wrap a duration into a negative number, and inflate a day's total. Rows are
  now clamped to the present and checked for overflow at eight points in the
  code, not just the one.
- Comparing a habit at 23:00 against a profile entry at 01:00 treated them as 22
  hours apart instead of 2.

**History could be lost or withheld**

- Creating a database index could fail for a reason that had nothing to do with
  your data — a full disk, a briefly locked file — and the code's response was to
  delete history. That could happen on any app launch. It now logs the problem
  and carries on, because an index is a speed aid, not a requirement for
  correctness.
- A restore that failed partway still reported "Vault Restored" and restarted as
  though everything was fine. A partial restore is now reported as incomplete,
  and the message no longer blames your file.

**The app was doing avoidable work**

- The chart ran one never-ending animation per bar, but only the highlighted bar
  ever used it. A 24-hour chart animated 24 times over for no visible reason.
  There is now one animation per chart.
- Restoring a vault scanned your entire history once per restored entry. Against
  a year of data that is hundreds of millions of comparisons; the history is now
  read once.
- Background archiving caught up about five days per run against roughly a year
  of backlog, so a fresh install needed around 73 runs to fill in. It now
  backfills the whole gap in one pass.
- Your database tables were re-checked on every single open.

**Safety and privacy**

- Diagnostic and crash logs are written to a folder that was not excluded from
  Android's "transfer to new phone" feature. On Android 12 and later, changing
  phones would have copied those logs across even with cloud backup switched off.
- "Clear Diagnostic Logs" and "Simulate Fatal Crash" ran on a single tap with no
  confirmation, and neither can be undone. Both now ask first.
- Clearing a custom-value field saved 0 without telling you. For the archive
  interval that is not a harmless default but a broken setting. The field now
  rejects nonsense and says why.

Not yet tagged. This release corrects the time arithmetic, closes a crash, and
adds theme customization.

### Infrastructure

- `IntervalOverlapIndex` — an in-memory interval-overlap test used by the vault
  merge, replacing a per-row table scan. Its correctness rule (a prefix maximum
  of ends, not just the nearest neighbour) is pinned by tests.
- R8 keep rules for serialization, workers and the crash-recovery path, so
  shrinking cannot strip something reached only at runtime.
- A release build now fails outright if the signing key is missing, instead of
  quietly producing an artifact signed with the debug key.
- Resource shrinking enabled for release builds.

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
