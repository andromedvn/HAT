package andromedvn.heuristic.activity.tracker.domain

/**
 * PresenceClassifier (2026-11-02, the presence arena's adopted spine, schools
 * A + H): every verdict is a FACT from the recorded event stream, never a
 * guess from silence. A silent block is classified by its edges:
 * - an anchor inside the block (unlock, touch event, app coming forward)
 *   means the phone was in use during it;
 * - keyguard shown within LOCK_CO_TIMESTAMP of screen-off means the user
 *   locked the phone deliberately;
 * - screen-off landing one screen-timeout after the last human event means
 *   the phone timed out on its own, and the next unlock says how long it sat;
 * - the same app resuming right after the screen comes back, with no unlock
 *   between, means media playback continued without a new touch.
 * Anything else is UNDECIDED, and an UNDECIDED block claims nothing.
 *
 * Constants are conservative on purpose: an UNDECIDED verdict is honest,
 * a wrong verdict is the exact failure the retired idle heuristic taught
 * this app to avoid.
 */
object PresenceClassifier {

    enum class Kind { ANCHORED, CHOSE_TO_STOP, TIMEOUT, MEDIA_CONTINUED, UNDECIDED }

    data class Verdict(
        val kind: Kind,
        val anchorTs: Long? = null,
        /** The edge that resolved the verdict: the returning unlock for
         *  TIMEOUT, the screen-on moment for MEDIA_CONTINUED. Null = the
         *  edge has not happened yet (an open gap) or does not exist. */
        val resolveTs: Long? = null,
        /** The package that proved a MEDIA_CONTINUED verdict (the app that
         *  resumed when the screen came back), so the copy can name it. */
        val pkg: String? = null
    )

    // Event type ints (public UsageEvents vocabulary; raw ints so minSdk 26
    // reads them without the API-29 constant names).
    private const val TYPE_ACTIVITY_RESUMED = 1
    private const val TYPE_USER_INTERACTION = 7
    private const val TYPE_SCREEN_INTERACTIVE = 15
    private const val TYPE_SCREEN_NON_INTERACTIVE = 16
    private const val TYPE_KEYGUARD_SHOWN = 17
    private const val TYPE_KEYGUARD_HIDDEN = 18

    /** How far before the block the pre-edge window reaches. */
    const val PRE_WINDOW_MS = 60_000L

    /** |keyguard - screen_off| at or under this = a deliberate lock. */
    const val LOCK_CO_TIMESTAMP_MS = 1_500L

    /** The screen-off timestamp must land within this fraction of the
     *  device's screen-timeout after the last human event to read as a
     *  timeout rather than something else. */
    const val TIMEOUT_TOLERANCE = 0.25

    /** An unlock this soon after the block ended means the human was close. */
    const val GRACE_MS = 60_000L

    /** Past this unlock latency the phone is treated as parked. */
    const val AWAY_MAX_MS = 30L * 60_000L

    /** Post-edge look-ahead for the resolving unlock. */
    const val POST_LOOKAHEAD_MS = 24L * 60 * 60_000L

    /** Events this close to the block edges are the block's own bookkeeping
     *  (the app opening that defines the block, the pause that closes it),
     *  not human activity inside it. The 2026-11-03 device test caught the
     *  opening RESUMED firing ANCHORED on every gap. */
    const val BOUNDARY_EPSILON_MS = 2_000L

    fun classify(events: List<RawEvent>, blockStart: Long, blockEnd: Long, screenOffTimeoutMs: Long): Verdict {
        val sorted = events.sortedBy { it.timestamp }
        val pre = sorted.filter { it.timestamp in (blockStart - PRE_WINDOW_MS) until blockStart }
        val inside = sorted.filter { it.timestamp in blockStart..blockEnd }
        val post = sorted.filter { it.timestamp in (blockEnd + 1)..(blockEnd + POST_LOOKAHEAD_MS) }

        // Court media rule (r12s, 6-0 verdict): this app's resume is the
        // FIRST foreground event after screen-on, and the log contains ZERO
        // KEYGUARD_HIDDEN events between the opening screen-off and the
        // resume - scanned across the WHOLE event list, not one window, so
        // the absence of an unlock is proof, not a timing heuristic. The
        // 1.5s window is permanently dead. The camera gesture still fires
        // this signature; the rendered wording states only the foreground
        // fact, never playback.
        fun mediaAfterScreenOn(): Verdict? {
            // The opening: the block's own edge screen-off (within 5s of the
            // start) or the last screen-off logged inside the block or after
            // it - the spec reads "the block contains" the sequence, so an
            // interior screen-off qualifies too.
            val opening = pre.lastOrNull { it.type == TYPE_SCREEN_NON_INTERACTIVE && it.timestamp >= blockStart - 5_000L }
                ?: (inside + post).lastOrNull { it.type == TYPE_SCREEN_NON_INTERACTIVE }
                ?: return null
            val screenOn = post.firstOrNull { it.type == TYPE_SCREEN_INTERACTIVE && it.timestamp > opening.timestamp } ?: return null
            val resumed = post.firstOrNull { it.type == TYPE_ACTIVITY_RESUMED && it.timestamp > screenOn.timestamp } ?: return null
            val unlockedBetween = sorted.any {
                it.type == TYPE_KEYGUARD_HIDDEN && it.timestamp > opening.timestamp && it.timestamp < resumed.timestamp
            }
            if (unlockedBetween) return null
            return Verdict(Kind.MEDIA_CONTINUED, opening.timestamp, pkg = resumed.packageName)
        }

        // Court precedence (r12s, 6-0 verdict): lock > timeout > media >
        // recorded-use. One provable line wins; silence when nothing does.

        // 1. The off-edge: did the screen go off because the user locked it?
        val screenOff = pre.lastOrNull { it.type == TYPE_SCREEN_NON_INTERACTIVE && it.timestamp >= blockStart - 5_000L }
        if (screenOff != null) {
            val keyguardShown = pre.lastOrNull { it.type == TYPE_KEYGUARD_SHOWN }
            if (keyguardShown != null && kotlin.math.abs(keyguardShown.timestamp - screenOff.timestamp) <= LOCK_CO_TIMESTAMP_MS) {
                return Verdict(Kind.CHOSE_TO_STOP, screenOff.timestamp)
            }
            // Timeout check: screen-off landed about one screen-timeout after
            // the last human event before it.
            val humanTypes = setOf(TYPE_ACTIVITY_RESUMED, TYPE_KEYGUARD_HIDDEN, TYPE_USER_INTERACTION, TYPE_SCREEN_INTERACTIVE)
            val lastHuman = pre.lastOrNull { it.type in humanTypes && it.timestamp < screenOff.timestamp }
            val gapFromHuman = screenOff.timestamp - (lastHuman?.timestamp ?: (screenOff.timestamp - screenOffTimeoutMs))
            val isTimeout = lastHuman == null || gapFromHuman <= screenOffTimeoutMs * (1.0 + TIMEOUT_TOLERANCE)
            if (isTimeout) {
                // 3. Resolve how long the phone sat by the next unlock.
                val unlock = post.firstOrNull { it.type == TYPE_KEYGUARD_HIDDEN }
                if (unlock != null) return Verdict(Kind.TIMEOUT, screenOff.timestamp, unlock.timestamp)
                mediaAfterScreenOn()?.let { return it }
                return Verdict(Kind.TIMEOUT, screenOff.timestamp)
            }

            // Not a timeout: the screen-off had a human cause the log cannot
            // name. The media signature still applies (court spec: the media
            // line renders when no lock/timeout line did).
        }
        mediaAfterScreenOn()?.let { return it }

        // 4. Recorded use (the anchor check, demoted per the court
        // precedence): an anchor INSIDE the block, excluding the block's own
        // boundary bookkeeping. Renders the generic fallback line; the
        // specific lines above always win.
        inside.firstOrNull {
            it.timestamp > blockStart + BOUNDARY_EPSILON_MS &&
                it.timestamp < blockEnd - BOUNDARY_EPSILON_MS &&
                (it.type == TYPE_KEYGUARD_HIDDEN || it.type == TYPE_USER_INTERACTION || it.type == TYPE_ACTIVITY_RESUMED)
        }?.let { return Verdict(Kind.ANCHORED, it.timestamp) }

        // 6. No screen-off edge at the block start and no anchors: honestly
        // unknown. The old branch-5 media classification is dead - without a
        // logged screen-off/lock opening, the "no unlock in between" claim
        // has no proven interval and renders nothing.
        return Verdict(Kind.UNDECIDED)
    }
}
