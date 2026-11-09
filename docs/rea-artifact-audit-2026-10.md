# REA (Reverse Engineer Anything) — HAT Analysis Report

> Repo note: this is the session record of an artifact audit performed on the
> 1.1.1 (versionCode 2) release APK **before** the 1.1.2 fixes, kept because it
> documents how each shipped-binary claim was verified. The §2/§3 fixes are all
> in 1.1.2; §5 re-inspected the rebuilt artifact. Paths refer to the original
> workstation.

**Tool:** [morluto/rea](https://github.com/morluto/rea) `rea-agents@6.4.0` (CLI, JADX provider `jadx-headless-mcp-0.7.1-all.jar`, SHA-256 verified per REA's pinned metadata)
**Target:** `andromedvn/HAT` — Heuristic Activity Tracker 1.1.1 (versionCode 2) at the time of analysis
**Method:** Fresh `assembleDebug` + `assembleRelease` from the working tree (`feature/support-and-theme`), then REA `inspect-android-package` / `search-android-classes` / `inspect-android-class` / `inspect-android-method` on both APKs, cross-checked against `dexdump` bytecode ground truth and the Kotlin sources.

---

## 1. Verified claims (artifact evidence)

| README claim | Artifact verdict |
|---|---|
| No `INTERNET` / `ACCESS_NETWORK_STATE` | ✅ Confirmed — neither appears in the merged manifest of either build |
| Local-only, no network code path | ✅ Structural — no internet permission means the OS blocks sockets outright |
| `kotlinx.serialization` vault compat under R8 | ✅ `UserSettings$$serializer.<clinit>` retains all 13 field names in the minified release DEX |
| Interval algebra survives R8 | ✅ `subtractIntervals` bytecode traced at offset level (`add(src)` reachable only from the two no-overlap branches) — the alarming JADX Java view was a decompiler artifact, ground truth is correct |
| Debug build compiles source semantics faithfully | ✅ `replayGhostIntervals`, `replayUsageIntervals`, `mergeIntervals`, `subtractIntervals` decompile 1:1 with source |

## 2. Discrepancies found (source claims vs shipped artifact)

1. **`FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED` ship in the APK** although the app-level manifest comment asserted *"there is no library declaring it to strip"*. `androidx.work:work-runtime:2.10.5` declares both; the merged manifest proves it. Zero `setForeground()` call sites exist; the only BOOT consumer (`systemalarm.RescheduleReceiver`) is `enabled="false"` at minSdk 26.
   **Fix applied:** `tools:node="remove"` for both + corrected comment. Shipped binary now matches the documented "no foreground service" posture.
2. **`MultiInstanceInvalidationService` (Room) ships** via `work-runtime → room-runtime` (WorkManager uses Room for its own bookkeeping). Inert in a single-process app. Documented in the README FAQ; not stripped (would break if Room ever enables multi-instance invalidation).
3. **README misattributed `READ_EXTERNAL_STORAGE`** to vault import/export; its real consumer is `WallpaperColorExtractor` (wallpaper dominant-color for the pre-Android-12 theme fallback, runtime-checked, degrades gracefully). **Fix applied:** README corrected.
4. **`versionCode 2 / versionName 1.1.1`** while `CHANGELOG.md` stages `1.1.2`. **Fix applied:** bumped to 3 / 1.1.2.

## 3. Engine accuracy bugs found & fixed

1. **DST hour-chart walk** (`HeuristicEngine.buildChartPoints` + the dashboard gap chart's own copy): hardcoded 24 buckets duplicated hour 0 and dropped hour 2 on spring-forward days, never charted hour 23 on fall-back days, and labelled transition buckets with a wrong end hour (`(hour+1)%24`). **Fix:** new pure `domain/LocalDay.kt` `hourBucketsOfDay()` — 23/24/25 calendar-walked buckets, true end-hour labels; both walks now share it; `Charts.kt` hourly detection updated (`size in 23..25`). Pinned defects in `DstClockChangeTest` converted to regression tests of the real helper.
2. **`syncArchive` mixed arithmetic**: fixed `+86_400_000L` seed/window-end vs Calendar advance — the local 23:00–24:00 hour of every fall-back day was never archived, and the resumption anchor drifted off local midnight after each transition. **Fix:** `nextLocalMidnight()` for seed, window end, and day advance.
3. **`replayGhostIntervals` pending-ghost loss**: a resume while no human was proven overwrote `currentPkg/ghostStart` unconditionally — a locked screen cycling through >1 package (SystemUI → notification activity) surfaced only the last package's idle span, and a same-package re-resume truncated the head of the span. **Fix:** close the outgoing ghost at the switch instant; keep the original start on re-resume. New regression tests in `UsageEventReplayTest`.

## 4. Verification status

- Unit suite: `:app:testDebugUnitTest` (incl. new DST + ghost regression tests) — run after fixes.
- Final artifact re-inspection with REA (`inspect-android-package` on the rebuilt release APK): `FOREGROUND_SERVICE` / `RECEIVE_BOOT_COMPLETED` absent, `versionCode 3 / versionName 1.1.2` — see §5 below once filled.

## 5. Post-fix REA artifact verification

Re-inspected with `rea inspect-android-package` after rebuilding the release APK with all fixes:

- **`versionName "1.1.2"` / `versionCode 3`** — matches the staged changelog.
- **Permission set shipped:** `PACKAGE_USAGE_STATS`, `READ_EXTERNAL_STORAGE` (≤28), `WAKE_LOCK`, and the app's own signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. **`FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED` are gone.**
- Remaining manifest mentions of either string are component internals, not permission declarations: `SystemForegroundService`'s `enabled="@bool/…"` flag (dormant — no `setForeground()` call sites exist) and the BOOT intent-filter inside WorkManager's `systemalarm.RescheduleReceiver`, which is `enabled="false"` at minSdk 26. An auditor running `aapt dump permissions` now sees exactly the documented set.
- Unit suite: **280/280 green** (`:app:testDebugUnitTest`), including the previously-failing `KeyguardLockScreenReplayTest` pin and 4 new regression tests (`UsageEventReplayTest`) plus 3 rewritten DST tests (`DstClockChangeTest`) that now assert the fixed helpers directly.

