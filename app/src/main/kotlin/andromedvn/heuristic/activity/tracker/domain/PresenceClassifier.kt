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
 *   between, means playback continued through the quiet.
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
        val resolveTs: Long? = null
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

    /** Same-app resume this fast after screen-on, with no unlock between,
     *  is playback continuing. */
    const val QUICK_RESUME_MS = 3_000L

    /** Post-edge look-ahead for the resolving unlock. */
    const val POST_LOOKAHEAD_MS = 24L * 60 * 60_000L

    fun classify(events: List<RawEvent>, blockStart: Long, blockEnd: Long, screenOffTimeoutMs: Long): Verdict {
        val sorted = events.sortedBy { it.timestamp }
        val pre = sorted.filter { it.timestamp in (blockStart - PRE_WINDOW_MS) until blockStart }
        val inside = sorted.filter { it.timestamp in blockStart..blockEnd }
        val post = sorted.filter { it.timestamp in (blockEnd + 1)..(blockEnd + POST_LOOKAHEAD_MS) }

        // 1. An anchor inside the block: the phone was genuinely used.
        inside.firstOrNull {
            it.type == TYPE_KEYGUARD_HIDDEN || it.type == TYPE_USER_INTERACTION || it.type == TYPE_ACTIVITY_RESUMED
        }?.let { return Verdict(Kind.ANCHORED, it.timestamp) }

        // 2. The off-edge: did the screen go off because the user locked it?
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
                // 4. Media continuation: screen back on, same app resumed
                // fast, no unlock between - playback rode through the quiet.
                val screenOn = post.firstOrNull { it.type == TYPE_SCREEN_INTERACTIVE }
                if (screenOn != null) {
                    val resumed = post.firstOrNull { it.type == TYPE_ACTIVITY_RESUMED && it.timestamp <= screenOn.timestamp + QUICK_RESUME_MS }
                    val unlockedBetween = post.any { it.type == TYPE_KEYGUARD_HIDDEN && it.timestamp < resumed?.timestamp ?: Long.MAX_VALUE }
                    if (resumed != null && !unlockedBetween) return Verdict(Kind.MEDIA_CONTINUED, screenOn.timestamp)
                }
                return Verdict(Kind.TIMEOUT, screenOff.timestamp)
            }
        }

        // 5. The screen never went off at the block start (video / reading
        // case): only an immediate same-app resume without an unlock is
        // decidable; everything else is honestly unknown.
        val screenOn = post.firstOrNull { it.type == TYPE_SCREEN_INTERACTIVE }
        if (screenOn != null) {
            val resumed = post.firstOrNull { it.type == TYPE_ACTIVITY_RESUMED && it.timestamp <= screenOn.timestamp + QUICK_RESUME_MS }
            val unlockedBetween = post.any { it.type == TYPE_KEYGUARD_HIDDEN && it.timestamp < (resumed?.timestamp ?: Long.MAX_VALUE) }
            if (resumed != null && !unlockedBetween) return Verdict(Kind.MEDIA_CONTINUED, screenOn.timestamp)
        }
        return Verdict(Kind.UNDECIDED)
    }
}
