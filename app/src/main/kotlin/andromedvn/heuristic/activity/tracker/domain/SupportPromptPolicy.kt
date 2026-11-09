package andromedvn.heuristic.activity.tracker.domain

/**
 * Policy for the one-time support prompt. The cross-module rules (any exit
 * counts as "seen"; no second nag surface anywhere else in the app) are the
 * part other files must honor:
 *
 * Pure and device-free so it can be unit-tested; see SupportPromptGateTest.
 */
fun shouldShowSupportPrompt(hasSeen: Boolean, reachedThemeControl: Boolean): Boolean =
    !hasSeen && reachedThemeControl
