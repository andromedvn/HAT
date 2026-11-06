package andromedvn.heuristic.activity.tracker.domain

/** A raw usage event, decoupled from the Android framework type. */
data class RawEvent(
    val type: Int,
    val timestamp: Long,
    val packageName: String,
    val className: String? = null
)

data class GhostSpan(val packageName: String, val start: Long, val end: Long)

/** Type codes from android.app.usage.UsageEvents.Event. */
object EventCode {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    /**
     * "A package was interacted with in some way by the user" (public since
     * API 23, value 7). This is the phone's own record of a touch landing in
     * an app, and it is the evidence the Idle Session feature is built on:
     * an app left in front, screen on, unlocked, with no touch arriving for
     * a long stretch is unattended, and the LAST touch is the honest moment
     * the user stopped using it. Sleep, walked away, forgot it playing:
     * the phone cannot tell those apart and does not try; it surfaces the
     * evidence and asks.
     */
    const val USER_INTERACTION = 7
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    /** Human-readable name for diagnostics exports; unknown codes pass through. */
    fun nameOf(type: Int): String = when (type) {
        ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        USER_INTERACTION -> "USER_INTERACTION"
        SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
        else -> "TYPE_$type"
    }

    /**
     * Events that terminate every in-flight resumed interval.
     *
     * DEVICE_SHUTDOWN (26) is included because after a reboot no app is
     * genuinely foregrounded - but see [replayGhostIntervals], where it is
     * explicitly excluded from ghost detection.
     *
     * KEYGUARD_SHOWN (17) is included for the same reason SCREEN_NON_INTERACTIVE
     * (16) is: a locked device is not being used, so an app left "resumed"
     * behind the lock screen is not screen time. The two are NOT
     * interchangeable, which is why both are listed:
     *
     *  - The lock screen is itself interactive, so Android does not emit
     *    SCREEN_NON_INTERACTIVE when the device merely locks. The screen-off
     *    event can be tens of minutes away (long screen timeout, always-on
     *    display) or may never come at all. Without this terminator the whole
     *    locked stretch was replayed as foreground app time.
     *  - The reverse gap exists too: SCREEN_NON_INTERACTIVE with no
     *    KEYGUARD_HIDDEN afterwards (device in a pocket, screen off, never
     *    unlocked) was already covered.
     *
     * KEYGUARD_HIDDEN (18) is deliberately absent: unlocking does not end an
     * app session, it is the moment the session becomes legitimately visible
     * again. Treating it as a terminator would truncate every session on every
     * unlock.
     *
     * The three are mutually exclusive within a lock cycle
     * (SHOWN -> HIDDEN, SHOWN -> screen off), and a terminator is idempotent:
     * the first one closes the open sessions and clears the map, so a second
     * terminator in the same millisecond finds nothing to close. No event can
     * therefore be counted twice, and no session can be left open across a
     * terminator.
     */
    val SESSION_TERMINATORS = setOf(SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN)
}

/**
 * Replays a usage event stream into per-package foreground intervals.
 * Pure: no Context, no clock. The caller supplies [cap] for trailing sessions.
 *
 * A resume that arrives while the screen is PROVABLY dark (a
 * SCREEN_NON_INTERACTIVE has been seen with no SCREEN_INTERACTIVE since) does
 * not open a usage interval here: nobody can use an app on a dark screen, and
 * overnight app re-binds (music mode, lock-screen apps, always-on displays)
 * were silently inflating screen-time totals. Those spans are collected by
 * [replayScreenOffIntervals] instead. If the screen later comes on and the
 * device is unlocked with the app still resumed, the interval converts to
 * usage from the unlock instant, because that is the first moment a human
 * could actually be using it. A stream that has shown no screen event at all
 * is treated as not-dark, which preserves the behaviour every older test
 * pinned: an ordinary session in a windowed stream still counts from its
 * resume.
 */
fun replayUsageIntervals(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long = Long.MAX_VALUE
): Map<String, List<Interval>> = replayCore(events, start, end, cap).usage

/**
 * The same replay, projected onto the spans in which an app was resumed while
 * the screen was provably dark. These are the fourth time category: real app
 * activity (a playlist, a background re-bind) without a human in front of the
 * glass. They are neither screen time nor idle guesses; the user decides what
 * each one was.
 *
 * A span ends at the earliest of: the app pausing or stopping, the screen
 * coming back on (the span's time is up; a later unlock may convert the still
 * resumed app into usage), or the device shutting down. Another package
 * resuming while the screen is dark closes the previous span at that instant,
 * mirroring the ghost replay's switch rule; a same-package re-resume keeps the
 * original start.
 */
/**
 * Device-level dark windows: every stretch between SCREEN_NON_INTERACTIVE
 * and the next SCREEN_INTERACTIVE or DEVICE_SHUTDOWN, per device rather than
 * per app. Answers "was the screen dark between t1 and t2" without naming an
 * app, which the per-app projection cannot do (no app may have resumed).
 * Pure: the same event list the other projections consume.
 */
fun replayDeviceScreenOffIntervals(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long = Long.MAX_VALUE
): List<Interval> {
    if (events.isEmpty()) return emptyList()
    val out = mutableListOf<Interval>()
    var darkFrom = -1L
    fun close(at: Long) {
        if (darkFrom >= 0 && at > darkFrom && at - darkFrom <= cap) out.add(darkFrom to at)
        darkFrom = -1
    }
    for (event in events.sortedBy { it.timestamp }) {
        when (event.type) {
            EventCode.SCREEN_NON_INTERACTIVE -> if (darkFrom < 0) darkFrom = event.timestamp
            EventCode.SCREEN_INTERACTIVE -> close(event.timestamp)
            EventCode.DEVICE_SHUTDOWN -> close(event.timestamp)
        }
    }
    // An unclosed dark window runs to the end of the requested window.
    if (darkFrom >= 0) close(maxOf(darkFrom, minOf(end, cap)))
    return out
}

fun replayScreenOffIntervals(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long = Long.MAX_VALUE
): Map<String, List<Interval>> = replayCore(events, start, end, cap).screenOff

/** Both projections of one walk, so the two can never disagree. */
private data class ReplayResult(
    val usage: Map<String, List<Interval>>,
    val screenOff: Map<String, List<Interval>>
)

private fun replayCore(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long
): ReplayResult {
    val intervals = mutableMapOf<String, MutableList<Interval>>()
    val screenOffSpans = mutableMapOf<String, MutableList<Interval>>()
    val resumed = mutableMapOf<String, Pair<String, Long>>() // class -> (pkg, start)
    // pkg -> span start. Sessions opened by a resume while the screen was
    // provably dark. They are never usage until an unlock converts them.
    val darkSessions = mutableMapOf<String, Long>()
    // pkg -> span start. Dark sessions whose span ended at SCREEN_INTERACTIVE
    // with the app still resumed: hidden behind the lock screen, convertible
    // to usage the moment KEYGUARD_HIDDEN proves a human could see them.
    val lockedSessions = mutableMapOf<String, Long>()
    // False until a SCREEN_NON_INTERACTIVE proves otherwise: a windowed stream
    // with no screen events yet behaves exactly as it always has.
    var screenDark = false

    fun closeDarkSpan(pkg: String, at: Long, from: Long) {
        if (at > from) screenOffSpans.getOrPut(pkg) { mutableListOf() }.add(from to at)
    }

    for (event in events.sortedBy { it.timestamp }) {
        // Screen-state bookkeeping first. None of these three carry a package
        // name, so they fall through to the terminator/empty-package checks
        // below, which is what still closes usage at a screen-off.
        when (event.type) {
            EventCode.SCREEN_INTERACTIVE -> {
                screenDark = false
                // The dark time is over: the span ends here, and a still-resumed
                // app becomes a lock-screen resident until an unlock proves it
                // visible. Its span was already emitted, so a later unlock must
                // not emit it again.
                for ((pkg, from) in darkSessions) {
                    closeDarkSpan(pkg, event.timestamp, from)
                    lockedSessions[pkg] = from
                }
                darkSessions.clear()
            }
            EventCode.SCREEN_NON_INTERACTIVE -> {
                screenDark = true
                // Apps still resumed from a locked stretch go dark again from
                // here; their usage was already closed by the terminator below
                // on the previous screen-off, so no span is double-opened.
                for ((pkg, _) in lockedSessions) darkSessions[pkg] = event.timestamp
                lockedSessions.clear()
            }
            EventCode.KEYGUARD_HIDDEN -> {
                // The lock screen lifts. A still-resumed app is finally visible,
                // so its usage starts now, from the unlock. Keyed by package:
                // no bright-started session can be open for a dark-resumed
                // package, so there is nothing to collide with, and a later
                // PAUSED for the package closes it through the normal path.
                //
                // Dark sessions that never saw a SCREEN_INTERACTIVE end here
                // (their span closes at the unlock). Locked sessions already
                // had their span closed when the screen woke, so only the usage
                // conversion happens for them.
                for ((pkg, from) in darkSessions) {
                    closeDarkSpan(pkg, event.timestamp, from)
                    resumed[pkg] = pkg to event.timestamp
                }
                darkSessions.clear()
                for ((pkg, _) in lockedSessions) {
                    resumed[pkg] = pkg to event.timestamp
                }
                lockedSessions.clear()
            }
        }

        if (event.type in EventCode.SESSION_TERMINATORS) {
            for ((_, state) in resumed) {
                if (state.second < event.timestamp) {
                    intervals.getOrPut(state.first) { mutableListOf() }
                        .add(state.second to event.timestamp)
                }
            }
            resumed.clear()
            // A shutdown ends every dark span; the other terminators (screen
            // off, lock shown) only end USAGE, and the screen-off/lock
            // transitions above already moved the dark sessions accordingly.
            if (event.type == EventCode.DEVICE_SHUTDOWN) {
                for ((pkg, from) in darkSessions) closeDarkSpan(pkg, event.timestamp, from)
                darkSessions.clear()
                lockedSessions.clear()
            }
            continue
        }

        if (event.packageName.isEmpty()) continue
        val clazz = event.className ?: event.packageName

        if (event.type == EventCode.ACTIVITY_RESUMED) {
            if (screenDark) {
                // Provably dark: not usage. A same-package re-resume keeps the
                // original start; a different package taking the foreground
                // closes the previous span at the switch instant.
                if (!darkSessions.containsKey(event.packageName)) {
                    darkSessions[event.packageName] = event.timestamp
                }
                val otherPkg = darkSessions.keys.firstOrNull { it != event.packageName }
                if (otherPkg != null) {
                    val otherFrom = darkSessions[otherPkg]!!
                    closeDarkSpan(otherPkg, event.timestamp, otherFrom)
                    darkSessions.remove(otherPkg)
                    lockedSessions.remove(otherPkg)
                }
                lockedSessions.remove(event.packageName)
            } else {
                resumed[clazz]?.let { old ->
                    if (old.second < event.timestamp) {
                        intervals.getOrPut(old.first) { mutableListOf() }
                            .add(old.second to (event.timestamp - 1))
                    }
                }
                resumed[clazz] = event.packageName to event.timestamp
            }
        } else if (event.type == EventCode.ACTIVITY_PAUSED || event.type == EventCode.ACTIVITY_STOPPED) {
            resumed.remove(clazz)?.let { state ->
                if (state.second < event.timestamp) {
                    intervals.getOrPut(state.first) { mutableListOf() }
                        .add(state.second to event.timestamp)
                }
            }
            // The app leaves the foreground: close its dark span, if any.
            darkSessions.remove(event.packageName)?.let { from ->
                closeDarkSpan(event.packageName, event.timestamp, from)
            }
            lockedSessions.remove(event.packageName)
        }
    }

    for ((_, state) in resumed) {
        if (state.second < cap) {
            intervals.getOrPut(state.first) { mutableListOf() }
                .add(state.second to cap)
        }
    }
    for ((pkg, from) in darkSessions) {
        closeDarkSpan(pkg, minOf(cap, end), from)
    }

    fun clamp(map: Map<String, List<Interval>>): Map<String, List<Interval>> {
        val out = mutableMapOf<String, List<Interval>>()
        for ((pkg, ivs) in map) {
            val valid = ivs.mapNotNull { (s, e) ->
                val cS = maxOf(s, start)
                val cE = minOf(e, end)
                if (cE > cS) cS to cE else null
            }
            if (valid.isNotEmpty()) out[pkg] = valid
        }
        return out
    }

    return ReplayResult(clamp(intervals), clamp(screenOffSpans))
}

/**
 * Replays a usage event stream into "ghost" spans - app time when the device was
 * demonstrably not being touched by a human.
 *
 * DEVICE_SHUTDOWN deliberately does NOT close a ghost: a reboot tells us nothing
 * about whether a human was present, so it must not manufacture idle time.
 * Before this distinction existed, DEVICE_SHUTDOWN (26) shared a branch with
 * SCREEN_NON_INTERACTIVE (16), so an app left resumed across a reboot was
 * recorded as idle "ghost" time - inflating idle estimates and understating
 * real usage.
 */
fun replayGhostIntervals(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long = Long.MAX_VALUE
): List<GhostSpan> {
    var isHuman = false
    var currentPkg: String? = null
    var ghostStart = -1L
    // Has the stream shown the device DEFINITIVELY not in use at least once
    // (a screen-off or a lock)? A candidate opened before any such signal -
    // a bare resume at the head of the stream - has an unproven start: it may
    // cover real usage from before the lock, so it must never be extended or
    // emitted as-is; see the RESUMED branch below.
    var idleObserved = false
    // Whether the pending candidate was opened AFTER `idleObserved` became
    // true. Only then does [ghostStart, ...) describe proven idle time.
    var ghostTrusted = false
    // Whether the screen was PROVABLY dark at the last event. A resume during
    // proven darkness belongs to the Screen-Off category, not to ghosts: the
    // two must never both claim it. Ghosts keep the ambiguous cases, where the
    // screen was on but no unlock proves a human was watching.
    var screenDark = false
    val ghosts = mutableListOf<GhostSpan>()

    for (event in events.sortedBy { it.timestamp }) {
        when (event.type) {
            EventCode.SCREEN_INTERACTIVE -> { isHuman = false; screenDark = false }
            EventCode.KEYGUARD_HIDDEN -> {
                // An unlock ENDS the ambiguity (2026-10-23, the fuzzer's
                // fourth catch): the pre-unlock stretch stays a question
                // (emitted, trusted candidates only), and everything after
                // belongs to the human who just proved themselves. The old
                // behavior FROZE the pending candidate across the unlock,
                // and any later un-proving event resumed claiming from a
                // start that predates proven human use, a phantom generator
                // the invariant suite caught as an overlap with the
                // touch/unlock-anchored unattended spans.
                if (currentPkg != null && ghostStart != -1L && ghostStart < event.timestamp) {
                    if (ghostTrusted) ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                }
                currentPkg = null
                ghostStart = -1L
                isHuman = true
            }
            EventCode.USER_INTERACTION -> {
                // A touch is the strongest human proof (2026-10-23, the
                // fuzzer's third catch): the ghost doctrine itself counted
                // "no user-facing events" among its conditions, but the
                // replay ignored touches, so a touch landing mid-candidate
                // let BOTH projections claim the post-touch stretch. The
                // touch splits the claim: the pre-touch ambiguity is emitted
                // as a question (trusted candidates only, per doctrine), and
                // everything after belongs to the touch-anchored unattended
                // projection. A SystemUI shade pull behaves identically:
                // the human was present; the app-behind-glass question ends
                // at their touch either way.
                if (currentPkg != null && ghostStart != -1L && ghostStart < event.timestamp) {
                    if (ghostTrusted) ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                }
                currentPkg = null
                ghostStart = -1L
                isHuman = true
            }
            EventCode.KEYGUARD_SHOWN -> {
                // A locked device is not being used, so `isHuman` is false from
                // here on - the same conclusion SCREEN_NON_INTERACTIVE reaches,
                // reached earlier and without waiting for the screen to go
                // dark. Anything resumed from now on while locked is a ghost
                // candidate, which is what makes the locked stretch convertible
                // to an offline label instead of silently counting as screen
                // time.
                //
                // A pending ghost is discarded ONLY when `isHuman` is already
                // true, i.e. when an unlock happened after that ghost started.
                // KEYGUARD_HIDDEN sets `isHuman = true` without clearing the
                // pending ghost, so such a ghost is frozen rather than gone and
                // its start predates proven human use; re-arming it would report
                // a phantom. E.g. screen off 0, resume 2s, unlock 3s, resume 4s,
                // pause 5s, lock 6s, screen off 7s would claim 5s of "idle"
                // covering the 3s-5s the user was present for.
                //
                // When `isHuman` is already false the lock carries no new
                // information - the device was already untouched - so a ghost
                // running across it stays valid idle time and is closed
                // normally by the pause that follows.
                if (isHuman) {
                    currentPkg = null
                    ghostStart = -1L
                }
                isHuman = false
                // A lock is definitive proof the device is not being touched.
                idleObserved = true
            }
            EventCode.SCREEN_NON_INTERACTIVE -> {
                // Emit only TRUSTED candidates (2026-10-23, the fuzzer's
                // first catch): an untrusted pending, one opened on a bare
                // resume at the stream head, may cover real usage from
                // before the window; emitting it here let the ghost and the
                // unattended projections claim the identical head span.
                // The doctrine already dropped untrusted candidates at
                // package switches; every emit path now honors it.
                if (!isHuman && currentPkg != null && ghostStart != -1L && ghostTrusted) {
                    ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                }
                isHuman = false
                screenDark = true
                currentPkg = null
                ghostStart = -1L
                idleObserved = true
            }
            EventCode.DEVICE_SHUTDOWN -> {
                // A reboot proves nothing about human presence. Abandon any
                // pending ghost rather than closing it, and do NOT set
                // isHuman - a shutdown is not evidence that a person was there.
                currentPkg = null
                ghostStart = -1L
            }
            EventCode.ACTIVITY_RESUMED -> {
                // A resume while the screen is provably dark is Screen-Off
                // Activity, collected by replayScreenOffIntervals. The ghost
                // replay ignores it entirely, so no minute is ever both.
                if (!isHuman && !screenDark) {
                    // A resume ends whatever was foreground before it. If a
                    // TRUSTED ghost is pending - one opened after a proven
                    // idle signal (screen-off or lock) - the span from its
                    // start to this instant was screen-on, no-human time and
                    // is closed here instead of being silently dropped. A
                    // locked screen that cycled through more than one package
                    // (SystemUI, then a notification's activity) used to
                    // surface only the last package's span.
                    //
                    // An UNTRUSTED pending ghost - one opened on a bare resume
                    // before any idle signal, whose head may cover real usage
                    // from before a later lock - is dropped rather than
                    // emitted, matching the re-anchor the old code applied to
                    // every re-resume. Emitting it would prompt the user to
                    // label time they demonstrably spent using the app.
                    if (currentPkg != null && ghostStart != -1L && currentPkg != event.packageName && ghostStart < event.timestamp) {
                        if (ghostTrusted) {
                            ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                        }
                        currentPkg = null
                        ghostStart = -1L
                    }
                    // A re-resume of the SAME package during PROVEN idle keeps
                    // the original ghostStart: resetting it would silently
                    // shorten the candidate by everything before this event.
                    // During unproven time the old re-anchor applies instead.
                    if (currentPkg != event.packageName || ghostStart == -1L || !ghostTrusted) {
                        currentPkg = event.packageName
                        ghostStart = event.timestamp
                        ghostTrusted = idleObserved
                    }
                }
            }
            EventCode.ACTIVITY_PAUSED, EventCode.ACTIVITY_STOPPED -> {
                // Trusted-only emit (2026-10-23): the doctrine drops
                // untrusted head candidates everywhere, not just at
                // switches; a pause is an emit path too.
                if (!isHuman && currentPkg != null && currentPkg == event.packageName && ghostStart != -1L) {
                    if (ghostTrusted) {
                        ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                    }
                    currentPkg = null
                    ghostStart = -1L
                }
            }
        }
    }

    val endCap = minOf(end, cap)
    if (!isHuman && currentPkg != null && ghostStart != -1L && ghostStart < endCap && ghostTrusted) {
        ghosts.add(GhostSpan(currentPkg, ghostStart, endCap))
    }

    return ghosts.mapNotNull {
        val cS = maxOf(it.start, start)
        val cE = minOf(it.end, end)
        if (cE > cS) GhostSpan(it.packageName, cS, cE) else null
    }
}

/**
 * The Idle Session projection (2026-10-21): app still in front, screen on,
 * phone unlocked, and no touch for a long stretch.
 *
 * This is the feature the maintainer originally built the idle detector
 * for: falling asleep while browsing, with the screen staying awake on
 * autoplay, used to be recorded as pure usage because nothing in the event
 * stream contradicted it. The old app's rule was duration-only (any session
 * past a threshold was flagged), which caught the sleep case by accident
 * but also flagged every long actively-watched session, and never said
 * WHEN the user stopped, only how long the session ran. USER_INTERACTION
 * events (7) are the missing evidence: the phone already records every
 * touch attributed to the app being used.
 *
 * The emitted span starts at the LAST touch (or the resume moment when the
 * app was never touched, e.g. a movie opened and left playing), so labeling
 * it "Sleep" names the exact minute the user dropped off, and the touched
 * part of the session stays usage.
 *
 * Boundaries, each of which hands over to the category that owns the next
 * stretch rather than double-claiming it:
 *  - KEYGUARD_SHOWN closes the span at the lock instant: from there the
 *    locked projection ([replayGhostIntervals]) owns the time.
 *  - SCREEN_NON_INTERACTIVE closes it at the moment the screen went off:
 *    from there Screen-Off Activity owns it.
 *  - KEYGUARD_HIDDEN (an unlock, a human acting) re-anchors attendance for
 *    an app still in front, so a span can never reach back before the
 *    unlock into time the locked projection already owns.
 *  - DEVICE_SHUTDOWN abandons the candidate: a reboot timestamp is a
 *    persistence artifact, not evidence either way.
 *
 * Any USER_INTERACTION while a candidate is open re-anchors it, including
 * a touch attributed to another package (transitions fire touches before
 * the pause of the previous app lands; a touch anywhere proves a human is
 * attending the device). A same-package re-resume (internal activity
 * transitions) neither closes nor re-anchors: stillness is measured from
 * the last touch across them, so one autoplay night is one span, not one
 * card per transition.
 *
 * The replay emits every non-empty stillness span; duration thresholds and
 * acknowledgement filtering are the engine's job, identical to the ghost
 * path, so one setting (Minimum Duration) governs both.
 */
fun replayUnattendedIntervals(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long = Long.MAX_VALUE
): List<GhostSpan> {
    var currentPkg: String? = null
    // The attendance anchor: the last touch, the last unlock with the app
    // in front, or the resume that opened the session. -1 = no candidate.
    var stillnessStart = -1L
    // A candidate may only exist while the screen is on AND the phone is
    // unlocked: a resume behind a lock belongs to the lock-screen
    // projection, a resume while the screen is off belongs to Screen-Off
    // Activity, and the stillness clock may never claim their minutes.
    // Without the lock guard, a lock-screen media resume opened a candidate
    // here too and the same minute was claimed twice (caught by
    // UnattendedReplayTest, 2026-10-21).
    var screenOff = false
    var locked = false
    // Human proven since the last state change (2026-10-23, the fuzzer's
    // second catch): SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE, and
    // KEYGUARD_SHOWN each un-prove the human (a pocket wake, a dark screen,
    // a locked phone prove nothing), and only an unlock or a touch proves
    // one again. Without this gate, a resume right after a pocket wake let
    // this projection open a candidate on the same event the ghost
    // projection claimed, and both claimed the stretch. Defaults true at
    // the stream head: the query runs with a 24h buffer before the day, so
    // the head is mid-history where usage was being counted anyway.
    var humanProven = true
    // The foreground ACTIVITY, not just the app (2026-10-23, the raw-event
    // trace). An app's old activity can report ACTIVITY_STOPPED seconds
    // after its replacement already resumed: Bitget's splash reported
    // stopped 1.6s after MainActivity took over, and because the replay
    // keyed on package alone, that stale event closed the whole night's
    // candidate at 19:54:11.998. The wifi flap 29 minutes later then
    // opened a fresh candidate anchored at the flap itself, and the card
    // read 8:23 PM instead of 7:54 PM. A pause or stop now only counts
    // when its class matches the class that resumed.
    var currentClass: String? = null
    val out = mutableListOf<GhostSpan>()

    // Churn absorption (2026-10-22), born from the maintainer's own phone:
    // a Wi-Fi "no internet" dialog flashed a settings activity into the
    // foreground every few minutes while Bitget sat untouched, and each
    // flash fired a pause/resume burst that reset the stillness clock and
    // carved one honest night into four cards with false attendance
    // timestamps. Four rules fix it:
    //
    //  1. A pause does not emit: it stashes a PENDING close. If the same
    //     package resumes within the grace window, the original candidate
    //     simply continues with its ORIGINAL anchor. Internal activity
    //     transitions (an app pausing MainActivity and resuming
    //     DetailActivity) are absorbed the same way.
    //  1b. A package SWITCH also stashes instead of emitting (same night,
    //     second try): the phone's millisecond event order can put the
    //     dialog's resume BEFORE the foreground app's pause, so "switch
    //     emits, pause absorbs" split the night at the dialog timestamp
    //     anyway. Stashing makes both orders behave identically: the
    //     original app resuming within grace reclaims its anchor; a real
    //     user switch expires at grace and emits at the switch moment,
    //     exactly what the immediate emit produced, just ten seconds
    //     later in wall-clock terms.
    //  1c. A DEFERRED app that pauses stashes with its deferred anchor,
    //     so a quick real switch does not lose its own stillness start.
    //  2. An app resumed while SOMEONE ELSE's pending is live does not
    //     open a candidate either: its resume moment is DEFERRED. When the
    //     pending it is waiting for resolves (restored or expired), the
    //     deferred app opens at its own resume moment, and a touch in the
    //     meantime would anchor it even better. This keeps the dialog flash
    //     from leaving 50ms junk spans AND keeps a real quick app switch
    //     from losing its candidate.
    //  3. Terminal boundaries (lock, screen-off, shutdown) and the end of
    //     the stream flush every pending at its own pause moment; nothing
    //     may restore across a terminal boundary.
    val graceMillis = 10_000L
    val pending = HashMap<String, Pair<Long, Long>>() // pkg -> (anchor, closedAt)
    var deferredAnchor = -1L
    var deferredPkg: String? = null

    fun closeAt(ts: Long) {
        val pkg = currentPkg
        if (pkg != null && stillnessStart != -1L && stillnessStart < ts) {
            out.add(GhostSpan(pkg, stillnessStart, ts))
        }
        stillnessStart = -1L
    }

    fun activateDeferredIfResolved() {
        if (pending.isEmpty() && deferredPkg != null && stillnessStart == -1L && currentPkg == deferredPkg && !screenOff && !locked) {
            stillnessStart = deferredAnchor
        }
        if (pending.isEmpty()) {
            deferredPkg = null
            deferredAnchor = -1L
        }
    }

    fun flushAllPending(clearDeferral: Boolean) {
        // Chronological, deterministic: a HashMap's iteration order must
        // never decide what order spans appear in.
        for ((pkg, anchorAndClose) in pending.toList().sortedBy { it.second.second }) {
            val (anchor, closedAt) = anchorAndClose
            if (anchor < closedAt) out.add(GhostSpan(pkg, anchor, closedAt))
        }
        pending.clear()
        if (clearDeferral) {
            deferredPkg = null
            deferredAnchor = -1L
        }
    }

    fun flushExpiredPending(now: Long) {
        val expired = mutableListOf<Triple<String, Long, Long>>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.second > graceMillis) {
                val (anchor, closedAt) = entry.value
                if (anchor < closedAt) expired.add(Triple(entry.key, anchor, closedAt))
                iterator.remove()
            }
        }
        for ((pkg, anchor, closedAt) in expired.sortedBy { it.third }) {
            out.add(GhostSpan(pkg, anchor, closedAt))
        }
        activateDeferredIfResolved()
    }

    for (event in events.sortedBy { it.timestamp }) {
        // The first event past the grace window proves the pending close
        // was a real boundary: flush it before processing anything else.
        flushExpiredPending(event.timestamp)
        when (event.type) {
            EventCode.ACTIVITY_RESUMED -> {
                if (currentPkg != event.packageName) {
                    // A switch to another app STASHES the open candidate
                    // instead of emitting (2026-10-22): the maintainer's
                    // Bitget night proved that a system dialog can flash
                    // over the foreground app in either event order, and
                    // emitting at the switch split one untouched night into
                    // two cards at the exact dialog timestamp. With a stash,
                    // the dialog-first order is absorbed exactly like the
                    // pause-first order: if the original app resumes within
                    // the grace window, its anchor survives; if not, the
                    // pending expires and emits at the switch moment, which
                    // is the same result the immediate emit produced.
                    if (currentPkg != null && stillnessStart != -1L && stillnessStart < event.timestamp) {
                        pending[currentPkg] = stillnessStart to event.timestamp
                    }
                    stillnessStart = -1L
                    currentPkg = event.packageName
                    currentClass = event.className
                    val prior = pending.remove(event.packageName)
                    if (prior != null) {
                        // Churn return: the pause was a flash, not a
                        // boundary. The original anchor survives.
                        stillnessStart = if (screenOff || locked || !humanProven) -1L else prior.first
                        deferredPkg = null
                        deferredAnchor = -1L
                    } else if (screenOff || locked || !humanProven) {
                        stillnessStart = -1L
                    } else if (pending.isNotEmpty()) {
                        // Someone else's pending is live: this resume might
                        // be churn (a dialog flash). Defer.
                        stillnessStart = -1L
                        deferredPkg = event.packageName
                        deferredAnchor = event.timestamp
                    } else {
                        stillnessStart = event.timestamp
                    }
                } else if (stillnessStart == -1L && deferredPkg != event.packageName && !screenOff && !locked && humanProven) {
                    stillnessStart = event.timestamp
                } else {
                    // Same package over an OPEN candidate: an internal
                    // activity transition. The stillness clock keeps
                    // running; the foreground class becomes the new one so
                    // only ITS pause/stop can close the candidate, never a
                    // stale event from the activity it replaced.
                    currentClass = event.className
                }
            }
            EventCode.USER_INTERACTION -> {
                // Any touch while an app is in front proves a person is
                // attending the phone, so it opens the stillness clock when
                // none is running and re-anchors it when one is. A touch
                // also supersedes any deferral: the touch is the honest
                // anchor. Attribution goes to the app being touched, but a
                // touch landing in ANY package during a transition counts
                // too: the point is the human, not which app received the
                // tap. Lock-screen taps (SystemUI) land while locked and
                // prove nothing about the app behind the glass.
                if (currentPkg != null && !screenOff && !locked) {
                    humanProven = true
                    stillnessStart = event.timestamp
                    deferredPkg = null
                    deferredAnchor = -1L
                }
            }
            EventCode.KEYGUARD_HIDDEN -> {
                // Unlocking is a human acting. An app still in front from
                // before the lock gets its attendance re-anchored here, so
                // the span can never claim the locked stretch that the
                // lock-screen projection owns. Unlocking implies the screen
                // is on; the guard keeps the invariant explicit anyway.
                locked = false
                humanProven = true
                if (currentPkg != null && !screenOff) {
                    stillnessStart = event.timestamp
                    deferredPkg = null
                    deferredAnchor = -1L
                }
            }
            EventCode.KEYGUARD_SHOWN -> {
                // A lock is terminal for churn: pendings close at their own
                // pause moment, the open candidate closes here, and nothing
                // may be restored across the boundary.
                flushAllPending(clearDeferral = true)
                closeAt(event.timestamp)
                locked = true
                humanProven = false
            }
            EventCode.SCREEN_NON_INTERACTIVE -> {
                flushAllPending(clearDeferral = true)
                closeAt(event.timestamp)
                screenOff = true
                humanProven = false
            }
            // A screen wake proves nothing (pocket wakes, notification
            // glows): the human stays un-proven until a touch or an unlock,
            // and an unattended candidate ENDS here (2026-10-23, the fuzzer's
            // follow-up catch): a touch-proven candidate that survives the
            // wake kept running until the next screen-off and overlapped the
            // ghost projection's post-wake stretch. An unattended candidate
            // exists only while a human is continuously proven; every
            // un-proving event ends it.
            EventCode.SCREEN_INTERACTIVE -> {
                closeAt(event.timestamp)
                screenOff = false
                humanProven = false
            }
            EventCode.DEVICE_SHUTDOWN -> {
                // Pendings closed before the shutdown timestamp are
                // pre-shutdown evidence and flush normally; the OPEN
                // candidate is abandoned, matching the ghost replay: a
                // reboot timestamp is a persistence artifact.
                flushAllPending(clearDeferral = true)
                currentPkg = null
                currentClass = null
                stillnessStart = -1L
                humanProven = false
            }
            EventCode.ACTIVITY_PAUSED, EventCode.ACTIVITY_STOPPED -> {
                // Class guard (2026-10-23): a stale lifecycle event from the
                // activity a replacement already replaced must not close
                // the foreground candidate. Bitget's splash reported STOPPED
                // 1.6s after MainActivity resumed; keying on package alone
                // let it erase the night's real anchor. Only the class that
                // resumed may pause or stop the candidate.
                if (currentPkg == event.packageName && (event.className == null || event.className == currentClass || currentClass == null)) {
                    // Do not emit on a pause: the next event decides
                    // whether this was churn or a real close. A DEFERRED
                    // app stashes with its deferred anchor, so a quick
                    // real switch does not lose its own stillness start
                    // (2026-10-22); an app with no candidate at all stashes
                    // zero-length and flushes to nothing.
                    val stashedAnchor = when {
                        stillnessStart != -1L -> stillnessStart
                        deferredPkg == event.packageName && deferredAnchor != -1L -> deferredAnchor
                        else -> event.timestamp
                    }
                    pending[event.packageName] = stashedAnchor to event.timestamp
                    if (deferredPkg == event.packageName) {
                        deferredPkg = null
                        deferredAnchor = -1L
                    }
                    stillnessStart = -1L
                    currentPkg = null
                    currentClass = null
                }
            }
        }
    }

    // End of stream: flush pendings at their own pause moments, then give
    // a still-deferred app its honest span from its resume moment to the
    // cap, then close any open candidate at the cap.
    flushAllPending(clearDeferral = false)
    if (deferredPkg != null && deferredPkg == currentPkg && stillnessStart == -1L && !screenOff && !locked) {
        stillnessStart = deferredAnchor
    }
    deferredPkg = null
    deferredAnchor = -1L
    closeAt(minOf(end, cap))

    return out.mapNotNull {
        val cS = maxOf(it.start, start)
        val cE = minOf(it.end, end)
        if (cE > cS) GhostSpan(it.packageName, cS, cE) else null
    }
}
