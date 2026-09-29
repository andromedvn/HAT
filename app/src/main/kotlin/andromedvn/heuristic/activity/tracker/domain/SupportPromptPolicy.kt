package andromedvn.heuristic.activity.tracker.domain

/**
 * Policy for the one-time support prompt.
 *
 * These rules are the feature. The prompt is shown at most once per install,
 * and only when the user has deliberately reached theme customization — never on
 * launch, never on entering Settings, and never a second time. Any exit from the
 * dialog counts as "seen", so there is no nag surface anywhere else in the app.
 *
 * Pure and device-free so it can be unit-tested; see SupportPromptGateTest.
 */
fun shouldShowSupportPrompt(hasSeen: Boolean, reachedThemeControl: Boolean): Boolean =
    !hasSeen && reachedThemeControl
