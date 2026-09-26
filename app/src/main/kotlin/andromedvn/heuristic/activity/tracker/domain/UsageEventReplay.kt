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
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    /**
     * Events that terminate every in-flight resumed interval.
     *
     * DEVICE_SHUTDOWN (26) is included because after a reboot no app is
     * genuinely foregrounded — but see [replayGhostIntervals], where it is
     * explicitly excluded from ghost detection.
     */
    val SESSION_TERMINATORS = setOf(SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN)
}

/**
 * Replays a usage event stream into per-package foreground intervals.
 * Pure: no Context, no clock. The caller supplies [cap] for trailing sessions.
 */
fun replayUsageIntervals(
    events: List<RawEvent>,
    start: Long,
    end: Long,
    cap: Long = Long.MAX_VALUE
): Map<String, List<Interval>> {
    val intervals = mutableMapOf<String, MutableList<Interval>>()
    val resumed = mutableMapOf<String, Pair<String, Long>>() // class -> (pkg, start)

    for (event in events.sortedBy { it.timestamp }) {
        if (event.type in EventCode.SESSION_TERMINATORS) {
            for ((_, state) in resumed) {
                if (state.second < event.timestamp) {
                    intervals.getOrPut(state.first) { mutableListOf() }
                        .add(state.second to event.timestamp)
                }
            }
            resumed.clear()
            continue
        }

        if (event.packageName.isEmpty()) continue
        val clazz = event.className ?: event.packageName

        if (event.type == EventCode.ACTIVITY_RESUMED) {
            resumed[clazz]?.let { old ->
                if (old.second < event.timestamp) {
                    intervals.getOrPut(old.first) { mutableListOf() }
                        .add(old.second to (event.timestamp - 1))
                }
            }
            resumed[clazz] = event.packageName to event.timestamp
        } else if (event.type == EventCode.ACTIVITY_PAUSED || event.type == EventCode.ACTIVITY_STOPPED) {
            resumed.remove(clazz)?.let { state ->
                if (state.second < event.timestamp) {
                    intervals.getOrPut(state.first) { mutableListOf() }
                        .add(state.second to event.timestamp)
                }
            }
        }
    }

    for ((_, state) in resumed) {
        if (state.second < cap) {
            intervals.getOrPut(state.first) { mutableListOf() }
                .add(state.second to cap)
        }
    }

    val out = mutableMapOf<String, List<Interval>>()
    for ((pkg, ivs) in intervals) {
        val valid = ivs.mapNotNull { (s, e) ->
            val cS = maxOf(s, start)
            val cE = minOf(e, end)
            if (cE > cS) cS to cE else null
        }
        if (valid.isNotEmpty()) out[pkg] = valid
    }
    return out
}

/**
 * Replays a usage event stream into "ghost" spans — app time when the device was
 * demonstrably not being touched by a human.
 *
 * DEVICE_SHUTDOWN deliberately does NOT close a ghost: a reboot tells us nothing
 * about whether a human was present, so it must not manufacture idle time.
 * Before this distinction existed, DEVICE_SHUTDOWN (26) shared a branch with
 * SCREEN_NON_INTERACTIVE (16), so an app left resumed across a reboot was
 * recorded as idle "ghost" time — inflating idle estimates and understating
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
    val ghosts = mutableListOf<GhostSpan>()

    for (event in events.sortedBy { it.timestamp }) {
        when (event.type) {
            EventCode.SCREEN_INTERACTIVE -> isHuman = false
            EventCode.KEYGUARD_HIDDEN -> isHuman = true
            EventCode.SCREEN_NON_INTERACTIVE -> {
                if (!isHuman && currentPkg != null && ghostStart != -1L) {
                    ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                }
                isHuman = false
                currentPkg = null
                ghostStart = -1L
            }
            EventCode.DEVICE_SHUTDOWN -> {
                // A reboot proves nothing about human presence. Abandon any
                // pending ghost rather than closing it, and do NOT set
                // isHuman — a shutdown is not evidence that a person was there.
                currentPkg = null
                ghostStart = -1L
            }
            EventCode.ACTIVITY_RESUMED -> {
                if (!isHuman) {
                    currentPkg = event.packageName
                    ghostStart = event.timestamp
                }
            }
            EventCode.ACTIVITY_PAUSED, EventCode.ACTIVITY_STOPPED -> {
                if (!isHuman && currentPkg != null && currentPkg == event.packageName && ghostStart != -1L) {
                    ghosts.add(GhostSpan(currentPkg, ghostStart, event.timestamp))
                    currentPkg = null
                    ghostStart = -1L
                }
            }
        }
    }

    val endCap = minOf(end, cap)
    if (!isHuman && currentPkg != null && ghostStart != -1L && ghostStart < endCap) {
        ghosts.add(GhostSpan(currentPkg, ghostStart, endCap))
    }

    return ghosts.mapNotNull {
        val cS = maxOf(it.start, start)
        val cE = minOf(it.end, end)
        if (cE > cS) GhostSpan(it.packageName, cS, cE) else null
    }
}
