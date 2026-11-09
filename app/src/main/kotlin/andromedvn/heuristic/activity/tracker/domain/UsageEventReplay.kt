package andromedvn.heuristic.activity.tracker.domain

/**
 * The replay head-of-stream assumption: every caller collects events from
 * (window start - 24h) so the first event of the window has proven context
 * (screen state, lock state) BEFORE it. The projections' defaults
 * (screenDark = false, humanProven = true) are only sound under this contract.
 * Change the buffer, revisit the defaults.
 */
const val REPLAY_PRE_BUFFER_MILLIS: Long = 86_400_000L

/** A raw usage event, decoupled from the Android framework type. */
data class RawEvent(
    val type: Int,
    val timestamp: Long,
    val packageName: String,
    val className: String? = null
)

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
    if (darkFrom >= 0) close(maxOf(darkFrom, minOf(end, cap)))
    return out
}

/** Type codes from android.app.usage.UsageEvents.Event. */
object EventCode {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    /**
     * "A package was interacted with in some way by the user" (public since
     * API 23, value 7). This is the phone's own record of a touch landing in
     * an app, and it is the raw evidence behind unattended-gap detection:
     * an app left in front, screen on, unlocked, with no touch arriving for
     * a long stretch is unattended, and the LAST touch is the honest moment
     * the user stopped using it.
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
     * genuinely foregrounded; the elapsed stretch to the shutdown still counts
     * as real usage, not as silence.
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


