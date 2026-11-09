# Contributing to HAT

## Licensing

HAT is free software, licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**.
See [LICENSE](LICENSE) for the full terms.

**By opening a pull request you agree to assign your contribution to the project
maintainer** under the same terms. This is a standard CLA-equivalent assignment,
and it is a condition of contribution.

Why: the project is dual-licensed. Without the assignment, every pull request
permanently locks that contribution to AGPL for everyone, which removes the
maintainer's ability to offer a commercial licence to companies that cannot
comply with AGPL. The assignment keeps that option open. It does not take the
code away from you, you keep your copyright, and you are always free to use your
own contribution however you like.

If you do not agree to the assignment, open an issue first to discuss. Small
patches are welcome with or without the assignment; anything substantial needs
the assignment or an issue first.

> **Note for the maintainer:** this clause is a best-effort plain-language
> template, not legal advice. Have it reviewed before relying on it in a real
> commercial licence. See the "Licensing for organisations" section of `README.md`.

## Before you open a pull request

1. `./gradlew :app:testDebugUnitTest`: all tests must pass. New behaviour needs
   new tests; a bug fix needs a test that fails without the fix.
2. One logical change per pull request. Unrelated cleanups make review harder and
   get the change delayed.
3. **Do not add permissions.** HAT's minimal permission surface is a core product
   feature, not an implementation detail; it is the product's central promise, and it
   is audited by the release lint in CI. If you believe a new permission is
   genuinely required, open an issue explaining the necessity first.
4. **Do not add network capability.** HAT declares no `INTERNET` permission and
   processes everything on-device. A change that needs the network is a change to
   the product's central promise; raise it as an issue, don't just ship it.
5. Time arithmetic must stay exact. Use `Long` milliseconds and integer maths
   throughout. No `Double` seconds, no rounding of intermediate values. Human
   formatting happens only at the point of display, and the remainder is
   preserved.

## Reporting bugs

Include your device model, Android version, and the exact steps to reproduce.

The most useful attachment is a **Master Vault export with personal data
redacted**; see [PRIVACY.md](PRIVACY.md) for what the vault contains. It lets a
reporter show real numbers without sharing real data.

## Development

The project targets `compileSdk 36` / `targetSdk 36` with JDK 17, AGP 8.13.2,
Gradle 8.13, and Kotlin 2.3.21. See [RELEASE.md](RELEASE.md) for the build,
signing, and tagging procedure.

Tests are plain JVM JUnit4 over pure functions. Robolectric is deliberately not
used so the suite stays fast and light enough to run on modest hardware. If you
are adding logic that would normally need a device, extract the decision into a
pure function and test that instead.
