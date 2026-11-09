# What changed in this build

**Your HAT app — math correctness pass + test coverage.** Branch `audit/math-consistency`. Nothing has been pushed to GitHub.

## Real bugs found and fixed

| # | Bug | Where | What it did |
|---|-----|-------|-------------|
| 1 | **Integer overflow in duration thresholds** | `HeuristicEngine.kt` (2 sites), `DashboardViewModel.kt`, `Charts.kt`, `HeuristicQuotes.kt` | `minutes * 60 * 1000L` evaluates the first part as **Int**, then widens. Above ~35,000 minutes it silently overflowed, producing a garbage threshold. For the gap threshold this meant every gap was reclassified as major or minor. |
| 2 | **Event 26 treated as screen-off** | `UsageStatsEngine.kt` | Android event 26 is `DEVICE_SHUTDOWN`, not screen-off (that is event 16). Any interval crossing a reboot was wrongly closed and added to ghost time. |
| 3 | **Dashboard double-counted offline time** | `DashboardViewModel.kt` | It summed raw offline durations while every other offline path in the app subtracts app-usage overlap. The app+offline stacked chart could exceed the visible day. |
| 4 | **"N Selected" total didn't match the rows** | `ActivityDetailsScreen.kt` | The header summed `spanDuration` (wall span) while the rows below showed `activeDuration` (real usage). When a session cluster bridged an idle gap, the header read **larger** than the rows it summarised. |
| 5 | **Daily summary export had the same raw offline sum** | `SettingsScreen.kt` | Same as #3, in the exported text. Worse: the clamp below it absorbed the error silently, so "Total Tracked" was wrong with no visible symptom — and this is the text you copy and share. |

## Things that looked like bugs but are not

I tested these instead of "fixing" them, because changing them would have broken correct behaviour:

- **Unclamped offline intervals in `scanForGaps`** — I fuzzed 20,000 randomised cases. Zero differences. The scan starts its cursor *at* the window start, so an interval beginning earlier can never trigger a gap. Not a bug.
- **Hidden app time "disappearing"** — intentional. Hidden time is removed from both the app total and offline total, and `scanForGaps` flags the affected gaps with a "hidden footprint" marker. That is the design.
- **Per-app total exceeding the device screen total** — intentional. It is a per-app sum, so split-screen legitimately double-counts. Clamping it would hide real overlap.

## Warning cleanup

The whole Kotlin warning backlog is now zero. One warning turned out to be **correct**: `isGhost == true` already implies the activity is non-null, so the null check beside it was provably redundant. That is now an explicit `requireNotNull` with a message.

## Tests

**44 JVM unit tests, all passing.** No emulator needed, so they run in about 40 seconds on this machine. New continuous integration runs them on every push.

The tests cover: interval merge and subtraction, ghost replay including the reboot case, offline sharding, session clustering, and **three repo-wide guard tests** that read the actual source tree and fail the build if any of these bug patterns are ever reintroduced.

## What to check on your phone

Everything below is a behaviour that changed. Nothing else was touched.

1. **Open the app, go to the dashboard.** Check "Total Offline Time" against your labelled offline entries. It should now be **equal or lower** than the sum of the gaps you labelled, never higher. Before the fix it could over-count.
2. **Daily summary export** (Settings → Data Control). Copy it. The "Offline Time", "Screen Time" and "Total Tracked" figures should now be internally consistent. Before, "Total Tracked" could be quietly wrong.
3. **App usage details screen.** Long-press two sessions to select them, look at the total in the header, then compare it against adding up the individual session times by eye. They should now agree. Before, the header was higher.
4. **Reboot test for the ghost fix.** This one is worth doing: use your phone normally, then **restart it**, then open HAT and look at the app usage for that day. A block of time spanning the restart should **not** appear as an unexplained usage session.
5. **Check nothing else changed.** Scroll every screen. If anything looks different from what you are used to, that is a bug I introduced — tell me and I will fix it.

## What is NOT in this build

- The UI work you asked about earlier (double boxing, sticky headers, popup styling, dashboard widgets, unlabeled-gap figure) has **not** been done. Those are cosmetic and I deliberately left them out so that when you install this, anything that looks wrong is a maths bug and not a design change.
- **HAT Pro is built and packaged separately** — see `HAT_PRO_mathfix_APK_and_source_2026-09-26.zip`. It carries these same fixes plus two Pro-only ones: a sixth overflow site in `NudgeDeliveryWorker` (which controls which gaps notify you) and a second bad total in the ledger view.
- Nothing was pushed to GitHub. Your repo is untouched.
- The APK is a **debug build, unsigned by your release key**, so it will not install over your existing HAT. Uninstall the old one first; that deletes its saved data, so do the reboot test on a day you don't mind.
