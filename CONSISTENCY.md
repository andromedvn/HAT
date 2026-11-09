# HAT Consistency Rules (owner rulings — read before touching HAT)

Standing rulings from consistency sweeps. The skill (consistent-codebase) is project-agnostic; these are HAT's concrete rules. New rulings get appended here after each sweep.

## Gap dialog (Label Activity)

- Ledger: receipt geometry — hug label column (measured, mono), values share one start-x, right-anchored block. One line per row, never two; each line is one complete fact.
- Rows auto-sort longest value first (stable: ties keep natural order, START before END). The merge dialog keeps its fixed MERGING/TOTAL/BETWEEN order.
- A suffix that cannot fit beside its base steps aside (the paren time drops — that instant is already on the START/END rows; a dropped suffix never shrinks the font). Base completes at full size; only an absurdly long name ellipsizes, and never its time.
- The font NEVER shrinks. A too-long label NAME ellipsizes on its own line; the time/parenthetical is never cut.
- DURATION carries seconds (`7h 16m 32s`); `(spans N days)` appears only when the gap crosses midnight.
- Day words exist only to explain a midnight crossing: same-day gaps show bare times. Vocabulary: today, yesterday, then the date (`Nov 1` style, year appended when it differs). No "2 days ago". Rendered as small muted annotations.
- Day words and the span note are muted small annotations; times and durations are full-style facts.
- Stats fillers (% of the day, rank, averages) were offered and REJECTED 2026-11-08 — duration stays duration + seconds + span note. Do not re-propose.

## Cross-screen linking (highlight)

- Long-press a chart bar → highlights the matching cards, on every screen that has both (dashboard, app details, offline activities, label gaps).
- Long-press a card → highlights its bars, EXCEPT the ratified job exceptions (function beats display — each gesture carries a job): gap cards on Label Gaps start the merge selection; offline activity cards on Offline Activities start delete-selection; session rows on an app's detail page start Convert-selection; session rows on an OFFLINE detail page highlight the session's bars (added 2026-11-09 — it had no job before).
- Tap always opens. One shared highlight state (DashboardViewModel.chartHighlight), not per-screen copies.
- Highlight visual: 2dp primary border, full opacity; selection keeps precedence where both apply.

## Names

- One entity, one name. The screen behind the "Offline Activity" stat card is "Offline Activities" (renamed from "Offline Time", 2026-11-08). Dashboard naming is the reference.
- Cut on purpose (do not resurface without asking): HIDDEN APPS count, SCREEN OFF/UNLOCKED rows, LOCKED/PHONE WOKE/SHUTDOWN evidence rows, LAST TOUCH. Facts live in the raw event log, not the dialog.
- Tour copy documents intended behavior — update it whenever dialog content changes (label-dialog step explains BEFORE/AFTER).
- Gap Minimum copy (official, owner words): in-app and README both read "Gaps shorter than this don't appear to declutter the timeline. A gap is time where you weren't on your phone." (README adds the presets list). No "stretches of silence", no "nothing ran at all".
- Screen-Off Activity stays its own card category with its two exits (name it / count as usage); it is NOT a row inside the gap dialog. The gap dialog references adjacent screen-off spans only through BEFORE/AFTER when one sits next to the gap.
- Session merge: a SETTING (owner call 2026-11-09, reversing the fixed-1-minute ruling). UserSettings.sessionMergeMinutes, presets 1/2/5 min + Custom (1-60) via DetailLevel.SESSION_MERGE_PRESETS, default 1. Shapes the details-screen session list only; gaps and charts unaffected.
- Where things live on screen: Screen-Off Activity spans are cards on the Label Gaps screen (15-minute floor) with the two-exit dialog; session clustering shows as "SESSIONS (N clustered)" on the detail pages; dismissed screen-off spans restore via the Label Gaps restore dialog.
- Contributions: pull requests are not accepted; issues for bugs/feature requests are. The README says so.
- Auto Gap Merge (absorb phone checks shorter than a tolerance into surrounding gaps, moving the check's seconds from app usage to gap time): DESIGNED, DEFERRED by owner 2026-11-09. Do not build or re-propose without asking. The design: a dial (Off/1/2/5/Custom, default 1) applied retroactively to all history via query-time recomputation, plus a per-gap override in the gap dialog showing absorbed checks as annotations. Session Merge (app sessions) is a separate, shipped dial and does not touch gaps.
- Origin claims: HAT started on-device (ACS) and is now built on desktop; README says that, not "built entirely on-device".

## Verification

- Every dialog/UI change: build + tests green, install, then pull a device screenshot of the changed surface AND the edge case where the new element shows (e.g. a midnight-crossing gap) before reporting done.

## Release signing (verified 2026-11-09)

- The permanent release key is the single `hat` alias in C:/Users/dvn/HAT-RELEASE-KEY-BACKUP/hat-release.jks.
- TRUE signing SHA-256: F6:55:2D:DB:2E:CD:02:47:C8:6A:EC:00:A4:0F:1C:97:41:6D:5D:F8:7C:CF:10:FA:00:52:00:97:61:96:42:44 (colon-less: F6552DDB2ECD0247C86AEC00A40F1C97416D5DF87CCF10FA0052009761964244).
- The A8:DA:E3:... fingerprint is the PRE-ROTATION key (rotated 2026-10-16). Never use it again. Always verify a built APK with apksigner against the keystore's own keytool output before publishing.

## Postponed features (owner's list, 2026-11-09)

Recorded so they are findable. None ships in 1.2.0; they are 1.2.1+ candidates. Do not start one without the owner's pick.

- **Auto Gap Merge** — designed and deferred (see the deferral note above).
- **Health Connect** — "design now, build later" from earlier planning: export labeled offline time (sleep, workouts) into Health Connect. Local API, no INTERNET needed. Biggest user-visible candidate.
- **Sleep auto-label** — engine-smarts priority: detect the obvious night silence and pre-suggest "Sleep" at labeling time. Suggest, never auto-claim (honesty rule).
- **Gap segmentation** — split one giant gap into sub-blocks at labeling time using the raw event stream (phone checks as seam markers).
- **Smart suggestions** — VERIFIED 2026-11-09, shipped and better than the old "top-3" priority: the label dialog chips surface up to 5 (engine take(5), circular-hour-proximity weighting over 30 days of labels; 3-5 static buckets on cold start). Every entry point passes the GAP's hour, never the clock at labeling time (dashboard load seeds the current hour as a sensible default). Keep 5. README line rewritten in plain words. The "top-3" postponed item is closed.
- Declined permanently (do not re-propose): the DURATION stat fillers (2026-11-08), idle detection and its dial (r9), sync Off.