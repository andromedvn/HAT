# Changelog

All notable changes to HAT are documented here.

HAT is released through **GitHub Releases only**, it is intentionally not
published to Google Play or F-Droid. See [RELEASE.md](RELEASE.md) for the
build, signing, and tagging procedure.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and HAT uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.2] - Unreleased

**Made by hand**

- The archive walk can no longer be wedged by empty days (2026-10-28). The
  walk restarted from the newest archived row, and because a day with zero
  usage writes no rows, five or more consecutive dead days (a dead-battery
  weekend, a flight) made every pass re-query the same empty days forever
  while the days after the hole aged out of Android's retention window. The
  archiver now keeps a cursor of ATTEMPTED days that advances on empty days
  too, and Diagnostics shows exactly how far the archive reaches.
- The archive's first line of defense is now a button (2026-10-28). Battery
  exemption was a paragraph of text in onboarding; aggressive battery
  managers then killed the archiver silently and the first symptom was the
  permanent-loss message weeks later. There is now a "Keep History Safe"
  button in onboarding and in Settings, and an archive-coverage line in
  Diagnostics, so the first symptom of failure is a status, not a loss.
- Returning users whose Usage Access dropped now land straight on the
  re-grant page (2026-10-28). `hasSeenOnboarding` was written but never
  read, so every launch replayed the full four-page deck; and the
  dashboard's permission card never re-checked on return, so a fresh grant
  looked like it never took.
- The idle detector finally does what it was built for (2026-10-21). The
  maintainer's original reason for the feature was knowing the exact minute
  they fell asleep while browsing, and the phone's own touch log can say
  it: Android records every touch attributed to the app you are using, and
  HAT now reads those events. An Idle Session card appears when an app
  stays in front, the screen is on, the phone is unlocked, and no touch
  has landed for at least your Minimum Duration. The span starts at the
  last touch, so labeling it "Sleep" names the minute you dropped off and
  the part you were really scrolling stays usage. A long watched movie can
  trigger the same card; the phone cannot tell watching from sleeping, so
  the card asks and one tap settles it.
- A splash screen's leftover event can no longer erase the anchor
  (2026-10-23, traced from the phone's own raw event export). The
  maintainer's Bitget night read 8:23 PM on the card while the session
  began 7:54 PM, and the export showed why: the app's splash activity
  reported stopped 1.6 seconds AFTER its replacement already resumed, and
  the replay, tracking the foreground by package alone, let that stale
  event close the whole night's candidate. Thirty minutes later a Wi-Fi
  flap opened a fresh candidate anchored at the flap itself. The replay now
  tracks the foreground ACTIVITY, and only the activity that resumed may
  close the candidate; stale lifecycle events from replaced activities are
  ignored. The raw export also proved the phone's millisecond order can
  differ from what system logs print, so no diagnosis was ever going to be
  certain from the outside.
- Two dials replace one (2026-10-23, the owner's call after living with
  the flood). A week of real use beat the one-number design: gap cards
  are stretches of total silence, a handful a day, while question cards
  (idle, lock-screen, screen-off) are born every single time the phone is
  set down with something on screen. One dial either flooded the
  questions or blinded the gaps. Gap Minimum (1, 5, 15 min, custom)
  carries silence; Idle Minimum (15, 30 min, 1h, custom) carries the
  questions, and everyone starts at 15 minutes. Both visible in Settings.
  Every selector now starts leftmost with its default: Recent first on
  sort, 12h first on sync.
- Background History Sync has no Off anymore (2026-10-23). The archive
  worker is the only thing keeping history alive before Android deletes
  it, and an off switch was a silent death sentence for the user's own
  past. Anyone who chose Off migrates to the 12 hour default. This also
  fixed a small lie: 12h was the app's default but was never on the dial.
- Naming an idle session now shows both clocks (2026-10-23). The details
  screen row showed a 2h 16m session while its dialog said "open for
  42m 19s", reading like a contradiction: the row shows the app's whole
  foreground stretch, the question is about the untouched tail. The
  dialog now names both: the session's range, and the moment the touches
  stopped.
- Answering a growing span sticks (2026-10-23). Pocket music checked at
  2:10 PM and answered kept growing, and the exact end-match rule let
  the new minutes resurface as a fresh question after every check.
  Answers now match (app, start): one answer settles a stretch however
  long it grows. A touch after the answer re-anchors the next stretch at
  a new start, which asks again honestly. Acknowledging a ghost at any
  duration, restoring it from the Dismissed panel, and dismissing
  screen-off spans follow the same rule.
- The invariant fuzzer (2026-10-23), the vaccine. Five hundred randomized
  event streams per run, asserting the boundaries that must hold for
  every stream: no span outside the window, no projection claiming a
  minute another owns, no self-overlap, no span crossing a terminal
  boundary. Its first run caught four real double-claim bugs before they
  reached any phone: untrusted head candidates emitted at screen-off,
  candidates opened after a pocket wake with no human proven, ghost
  claims surviving a touch that proved a human, and ghost candidates
  frozen across an unlock resurfacing over proven human time. All four
  fixed at the doctrine level and pinned by the suite.
- Long-press conversion works again on the details screen, and for the
  first time it knows idle sessions exist (2026-10-23). The functional
  sweep found two layers: the screen's ghost check only recognized
  lock-screen spans, so the new idle spans (the case the feature was
  built for) had no marker and their long-press dead-ended, and the
  batch Convert button has been a silent no-op since the first public
  release, setting a flag nothing rendered. Both fixed: idle spans show
  "Possible idle session" and long-press opens the same two-question
  dialog as the Gaps screen, the Convert button finally opens the label
  editor it always pointed at, and dismiss and convert now write the
  SPAN's bounds instead of the session's, so what you answer stays
  answered (session bounds only matched re-detection by luck, on the
  published version too).
- Naming a span now clears its card (2026-10-23). Conversion wrote the
  label but never suppressed the span, so it resurfaced on the next load
  and "convert" looked broken. Identical on the published version; wrong
  in both. Conversions on both screens now write the suppression with the
  detected span's exact bounds, so a named span stays named.
- The Restore door is reachable for screen-off dismissals (2026-10-23):
  the icon only checked idle dismissals, so dismissing only screen-off
  spans left no way back. It checks both now, and the dialog no longer
  closes early while screen-off rows remain.
- You can correct the label's start when the phone's evidence is wrong
  (2026-10-23). Naming an idle session now offers -5m, -1m, +1m, +5m
  steppers on the span's start, clamped to the span. Some manufacturers
  barely record touch events (the maintainer's itel logged a handful per
  week, none for a whole browsing session), so the span's anchor can miss
  the minute you actually stopped using the phone; your memory wins. Lock
  spans anchor at keyguard events, which every device records faithfully,
  so they stay fixed.
- Raw event suggestions on the details screen now load for the span's
  hour instead of whatever the last screen left behind.
- Export Raw Event Stream (2026-10-23), the ground-truth tool. A new
  Diagnostics item writes every usage event the OS still holds, last 3
  days, in the exact arrival order and millisecond precision HAT's
  replays receive. The replays break timestamp ties by arrival order,
  which no second-granularity log view can show, so when a span on a card
  disagrees with what you lived, this export lets the decision be traced
  line by line and reproduced exactly. Built to settle the Bitget anchor
  question for good: evidence, not inference.
- Dialog buttons stack full-width and can never wrap (2026-10-22). The
  owner caught "Yes, I was using it" sitting one line tall next to "No,
  name what it was" wrapped three lines high on a 720px screen: the
  side-by-side pair simply ran out of width. Both answers now stack
  full-width and read top to bottom like a sentence, in the idle, screen-
  off, and lock-screen dialogs on both screens.
- Minor gaps are gone, and the setting finally means what it says
  (2026-10-22). The owner caught the contradiction: setting Minimum Duration
  to one minute hid sub-minute gaps from the main list, and then a "MINOR
  GAPS" section listed exactly those millisecond blips anyway, the same
  ones the setting promised not to show. Anything shorter than your
  Minimum Duration now surfaces nowhere: not in a list, not in the Gap
  History chart (which used to bucket the blips and draw hour bars with
  no visible card to explain them). One filter, applied everywhere.
- System dialog churn cannot split a night (2026-10-22). The first real-
  world test found it twice over: a flapping Wi-Fi flashed the "no
  internet" dialog over Bitget every few minutes, and each flash fired a
  pause/resume burst. The first fix absorbed the pause-first order, but
  the phone's millisecond event order put the dialog's resume BEFORE the
  app's pause, so the switch still split the night at the exact dialog
  timestamp (the card read 8:23 PM, the first flap, instead of 7:54 PM
  when the app was opened). A package switch now stashes like a pause:
  both orders absorb, the original stillness anchor survives, and a real
  user switch expires at the ten second window and emits at the switch
  moment, exactly what the immediate emit produced. Internal activity
  transitions are absorbed the same way, nothing restores across a lock, a
  screen-off, or a reboot, and the flush order is chronological and
  deterministic.
- The locked-screen case, previously the only thing "idle session" meant,
  is now named what it is: Lock Screen Activity, app activity behind a
  locked phone with no unlock after. Same two questions, same Minimum
  Duration. Renaming freed "Idle Session" to mean what the maintainer built
  it to mean.
- One unaccounted timeline (2026-10-22, the owner's call). Gaps, possible
  idle sessions, lock-screen spans, and screen-off spans are all the same
  thing: time the day has not accounted for. Four sections became one
  chronological list, and each card states its evidence in plain words:
  "Nothing recorded between Chrome and Sleep", "Possible idle session:
  Bitget", "Behind the lock screen: Bitget", "Ran with the screen off:
  Bitget". Section membership never carried information, evidence does.
  Tapping each card still opens its own resolution flow, and long-press
  batch labeling still works on plain gaps.
- Settings learned to be honest. Three engine knobs that asked the same
  question in different words (Idle App Detection, Actionable Gap Minimum,
  Session Merge Tolerance) became one selector, and that selector now says
  what it does: Minimum Duration, with the numbers themselves as options
  (1 min, 5 min, 15 min) plus Custom for any value from 1 to 1440 minutes.
  The same number now governs gaps, idle sessions, and screen-off spans
  together. An earlier draft named the options Everything, Balanced, and
  Big Picture; those names sounded like they changed what you see, but every
  option showed the same categories and only moved one number, so they were
  dropped for telling the truth plainly. Session merging is hardcoded at one
  minute, correct for everyone, never surfaced. Old vaults still restore,
  and their stored gap threshold is carried into Minimum Duration on first
  read (2026-10-21), so upgrading changes nothing for gaps. Two things are
  new for upgraders, said plainly: idle detection now follows the same one
  number and can no longer be turned off, and because the detection rules
  also improved, a previously dismissed idle session may return once and
  ask again, if its stored answer no longer matches the newly drawn
  boundaries. Answer it again and it sticks.
- Every gap card now explains itself. Instead of saying only "Unlabeled Gap",
  each card names what the engine knows about the time around it: "Nothing
  recorded between Chrome and Sleep", "Nothing recorded after Instagram".
  The neighbors come from the scan the engine already runs; the evidence was
  computed and thrown away until now. A gap at a day boundary says so honestly
  instead of inventing a neighbor. The labeling dialog shows the same
  neighbors above the time range, so the answer you reach for ("oh right, I
  went for a walk after lunch") is visible where you name the gap, not only
  on the card.
- The custom-color picker's preview keeps its original four pieces, chart
  bars, calendar circle, range switcher, label card, with the last two swapped
  in position so the switcher reads third and the card last. All four pieces
  now sit in 36dp boxes on one midline so the row reads as aligned. The range
  switcher piece renders as the real dashboard's Day/Week/Month selector in
  miniature: full words, the selected segment filled and fully bordered, and
  unselected labels at the same 60% alpha the app uses, not the abstracted
  single letters of earlier drafts. The calendar circle shows 22. Two
  redesigns of those pieces were built and rolled back; the original
  proportions looked right the first time.
- The whole Gaps screen learned to talk. Idle cards say "Screen was on, but
  nobody seemed to be using it" instead of "Idle Session Detected". The
  resolution dialogs ask like a person ("Were you actually using it?") and the
  buttons answer the question ("Yes, I was using it" / "No, name what it was")
  instead of naming database actions ("Dismiss" / "Label"). The midnight
  cascade dialog, the dismissed-sessions panel, and the editor context lines
  all speak the same way.
- First-run guide: a new "How HAT Works" page explains the three kinds of
  time (Apps Usage, Screen-Off Activity, Gaps) in plain words before asking
  for anything, and warns that Android deletes old history, so exempting HAT
  from battery optimization is what keeps the past alive.
- The dashboard's "Total Apps Usage" number now counts exactly what the
  dashboard lists. It used to include hidden apps (raw device foreground)
  while the list and the stacked chart under it excluded them, and the
  App Usage screen showed a third number for the same concept. Card, list,
  chart, and App Usage screen now agree, and hidden apps stay erased
  everywhere, as the README always promised.
- Every dash-as-punctuation is gone from the app's own sentences. Copy now
  uses periods, commas, and colons, the way people write.
- The "last update today at" line no longer claims "today" when it might
  not be.
- A fourth time category: Screen-Off Activity. Apps that re-bind while the
  screen is off (a playlist in your pocket, a lock-screen app,
  background music mode) now get their own card on the dashboard instead of
  silently counting as screen time. Every span shows the app, the exact
  hours, and two ways out: name it (it becomes an offline label) or count it
  back as usage. Nothing is auto-decided. Counting one back as usage is no
  longer permanent either: the Dismissed Sessions panel now restores
  screen-off dismissals the same way it always restored idle ones.

**The paperwork learned to talk**

- The Terms of Use were 24 KB of legalese for a free, offline app with no
  accounts and no data collection. They are now about 5 KB of plain English:
  the AGPL governs the code, the Terms carry the disclaimers, and that's it.
  The governing-law, venue, class-action, export-control, indemnification, and
  force-majeure machinery is gone: these terms select no jurisdiction at all,
  and your local consumer and data-protection rights apply to you wherever you
  live.
- The Privacy Policy no longer names a country. Same protections, same rights,
  same commitments, including the "we'll disclose any flaw publicly" promise,
  without telling you where the developer lives.
- The README now tells you how to verify HAT yourself: the exact command to
  dump the permission list, the signing-certificate fingerprint to check every
  release against, and an open invitation to decompile the APK and look. The
  GitHub Sponsors badge is gone; there is a Ko-fi instead, and the FAQ says
  plainly that paying is optional and gates nothing.
- Store plans are settled for now: HAT ships through GitHub Releases only. The
  F-Droid request was closed rather than left hanging, and the store listing
  text stays in the repository in case that ever changes.

**Numbers were wrong**

- Screen-Off Activity cards were flooding with millisecond-long spans.
  Android fires constant background micro-resume/pause events while the
  screen is off, and every one of them became a card. A 47ms blip is a sync
  service breathing, not a human activity. Spans shorter than your Minimum
  Duration never surface, and the filter is per span, so a noisy app whose
  blips merely sum past the floor can no longer flood the section either.
- The Gaps screen had three different sort orders on it: idle sessions most
  recent first, screen-off items biggest first, gaps chronological. Every
  timeline section now reads chronologically, top of the list is the
  earliest thing that happened, matching how you experienced the day.
- The App Usage and Offline Stats screens ignored the "Global Timeline
  Sort" setting and always sorted by duration. They now respect whatever
  you chose in Settings, like the dashboard always did.

- Three hours of your sleep counted as phone usage. An app that re-binds
  while the screen is off (music mode, lock-screen apps, background players)
  stayed "in use" until the next screen-off, which for a phone sleeping
  overnight meant morning. The user's own archive held single apps "running"
  199 minutes straight between 1 AM and 6 AM. The usage replay now refuses a
  resume that arrives while the screen is off; that time lands in the new
  Screen-Off Activity category instead, and if the screen comes on and the
  device unlocks with the app still in front, usage starts from the unlock.
- Tapping a label whose name contains "/" crashed the app. Titles ride the
  `details/{type}/{id}` route, and one of the three callers forgot to encode
  them, so a label like "Work/Study" split the route in four and Navigation
  found no destination. All label and app routes now go through one shared,
  fuzz-tested encoder, any title, any characters, always one segment.
- A phone left unlocked but idle in a pocket counted as screen time. HAT only
  watched the screen turning off and never handled the lock event, so that time
  was never recognised as offline. Both signals are now handled.
- A label that crossed midnight lost the hours belonging to the second day
  entirely. Rows were looked up by "did this start inside the window", and a
  "Sleep" label running 23:00-07:00 is stamped with its 23:00 start, so the day-2
  window never saw it. Lookups now match on overlap and each row is clamped to
  the window before it is counted, so a label is counted once in each day for
  exactly the slice that belongs to it.
- That same "starts inside" test also made a midnight-crossing label
  half-editable: it was VISIBLE on day 2 (the list reads use overlap), but
  tapping it did nothing, the detail, rename and delete paths all looked the
  row up by its start. All three now use the same overlap predicate, scoped to
  the title, so a label can be opened, renamed or deleted from whichever day
  shows it. Deleting it from day 2 removes the whole label, exactly as
  deleting it from day 1 always has.
- The database upgrade path had never run: the schema version was still 1, so
  no existing install had ever reached the migration that builds the unique
  indexes and removes legacy duplicate rows, and without a unique index,
  `CONFLICT_IGNORE` is a no-op, so restores and archive syncs could stack
  duplicates instead of merging. The version is now 3: version 2 rebuilds the
  unique indexes, version 3 adds the `screen_off_dismissed` table. The
  migration is additive and idempotent, and it removes only exact duplicates
  (keeping the lowest id per group), never labels.
- Comparing a habit at 23:00 against a profile entry at 01:00 treated them as 22
  hours apart instead of 2. The clock is circular, and the comparison now wraps.
- On a daylight-saving day the hour chart was wrong twice over: a 23-hour
  spring-forward day charted a duplicate "12 AM" bucket and one hour of the next
  day, and a 25-hour fall-back day never charted 11 PM at all. The same fixed
  arithmetic mislabelled the buckets either side of the transition (a bucket
  labelled "1 AM - 2 AM" could cover real 1 AM to 3 AM). Buckets are now walked
  by the local calendar, so the chart has exactly the hours the wall clock
  really has, 23, 24, or 25, each labelled with its true bounds. The
  dashboard's gap chart used its own copy of the old walk and is fixed the same
  way.
- A locked screen that cycled through more than one package while you were away
  (the lock screen, then a notification's activity) reported only the last
  package's time as a "Suspicious Idle Session"; the earlier package's idle
  span was silently discarded, and a package that re-resumed mid-idle lost the
  head of its span. Spans that began after the device was proven idle are now
  kept and split at the instant the foreground moved; a span that began on a
  bare resume before any lock or screen-off still re-anchors, because its head
  may cover real usage.

**History could be lost or withheld**

- Creating a database index could fail for a reason that had nothing to do with
  your data, a full disk, a briefly locked file, and the code's response was to
  delete history. That could happen on any app launch. It now logs the problem
  and carries on, because an index is a speed aid, not a requirement for
  correctness.
- A restore that failed partway still reported "Vault Restored" and restarted as
  though everything was fine. A partial restore is now reported as incomplete,
  and the message no longer blames your file.
- Background archiving measured each day as a fixed 24 hours while advancing by
  the calendar. On a fall-back daylight-saving day the last local hour
  (23:00-24:00) fell outside the fixed window and was never archived by any
  later pass either, and the resumption anchor drifted off local midnight after
  every transition. Both the window and the anchor now follow the calendar.

**The app was doing avoidable work**

- The chart ran one never-ending animation per bar, but only the highlighted bar
  ever used it. A 24-hour chart animated 24 times over for no visible reason.
  There is now one animation per chart.
- Restoring a vault scanned your entire history once per restored entry. Against
  a year of data that is hundreds of millions of comparisons; the history is now
  read once.
- A damaged or hand-edited vault file could add time that has not happened yet,
  or wrap a duration into a negative number, and inflate a day's total. Rows are
  now clamped to the present and checked for overflow at eight points in the
  code, not just the one. A corrupted row could also stretch a label into a
  block several weeks long, which you were then offered to label.
- Background archiving caught up about five days per run against roughly a year
  of backlog, so a fresh install needed around 73 runs to fill in. It now
  backfills the whole gap in one pass.
- Your database tables were re-checked on every single open.

**Safety and privacy**

- The signing key was rotated before the first properly-signed release ever
  shipped. The old certificate's Distinguished Name named the developer's
  city and country, printed by `keytool` on every APK it signed. Since all
  published GitHub releases were built with the debug key (by design at the
  time), the release key had never reached a single user, so replacing it
  now costs nothing and the new certificate says only `CN=HAT,
  OU=andromedvn, O=HAT`. The published fingerprint in the README is the new
  one; treat any APK signed with anything else as not ours.
- The shipped binary declared `FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED`,
  merged in from `androidx.work`, even though HAT never runs a foreground
  worker and its only boot receiver is disabled at HAT's minimum Android
  version. Both are now stripped from the merged manifest, so `aapt dump
  permissions` on a release build reports neither, the artifact finally
  matches the documented "no foreground service" posture. The README also
  previously misattributed the (retained) `READ_EXTERNAL_STORAGE` to vault
  import/export; its real consumer is the pre-Android-12 wallpaper colour
  extraction, and the docs now say so.
- Diagnostic and crash logs are written to a folder that was not excluded from
  Android's "transfer to new phone" feature. On Android 12 and later, changing
  phones would have copied those logs across even with cloud backup switched off.
- "Clear Diagnostic Logs" and "Simulate Fatal Crash" ran on a single tap with no
  confirmation, and neither can be undone. Both now ask first.
- Clearing a custom-value field saved 0 without telling you. For the archive
  interval that is not a harmless default but a broken setting. The field now
  rejects nonsense and says why.

Not yet tagged. This release fixes a crash, corrects the time arithmetic,
and adds theme customization, the Minimum Duration setting, Screen-Off
Activity, and gap explainers.

### Known issue (pre-existing, not introduced here)

- A day window ends at 23:59:59.999 **inclusive** while every duration is
  computed as `end - start`, so a day spans 86,400,000 distinct milliseconds but
  measures 86,399,999. The result is a 1 ms under-report per day. It is invisible
  at the resolution the app displays, and it errs in the safe direction. HAT
  under-reports rather than exceeding the day. Found while writing
  `CrossMidnightContributionTest`, which now pins the midnight split as lossless
  apart from this known millisecond so the behaviour cannot silently change.

### Infrastructure

- `IntervalOverlapIndex`, an in-memory interval-overlap test used by the vault
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
  single non-blocking dialog explains that HAT is maintained by one person
  going full-time and offers a way to support that. It is shown at most once
  per install, never on launch, and any
  way of dismissing it is permanent. It hands off to the browser via
  `Intent.ACTION_VIEW`, so HAT still makes no network request and still
  declares no `INTERNET` permission.
- **316 JVM unit tests** covering day-boundary arithmetic, DST transitions,
  event replay (usage, screen-off, idle), corrupt-interval handling, clustering
  thresholds, navigation route encoding, gap explainers, and integer width.

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

- **A release build now fails** if `key.properties` is missing, instead of
  quietly producing a debug-signed artifact. A debug-signed release can never
  be replaced by a signed one, and the key is not recoverable. CI passes
  `-PallowDebugSignedRelease=true` for throwaway verification builds.
- `app/proguard-rules.pro` is no longer empty. The release build sets
  `isMinifyEnabled = true`, so R8 was running with no keep rules for
  `kotlinx.serialization`, WorkManager workers, and ViewModels.
- CI now builds a release APK, runs `lintRelease`, and **fails if `INTERNET`
  ever appears in a release manifest**, the privacy claim is asserted, not
  assumed.

### Added (documentation)

- `CONTRIBUTING.md`, including the AGPL §5 contribution-assignment clause.
  Without it, every merged pull request would permanently lock that
  contributor's work to AGPL.
- This changelog, which was previously gitignored.

## [1.1.1]

- Idle App Detection setting exposed in the UI.
- Clipboard plain-text summary export and a persistent background ledger.
- Zip-Slip path traversal fixed in vault extraction.
- Manifest backup rules hardened and vault import exceptions tightened.
- Gradle wrapper secured with a SHA256 checksum.

## [1.1.0]

- Initial public release of the rewritten timeline UI.

[1.1.2]: https://github.com/andromedvn/HAT/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/andromedvn/HAT/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/andromedvn/HAT/releases/tag/v1.1.0
