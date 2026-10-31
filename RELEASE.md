# Releasing HAT

> **Read this before producing any uploadable artifact.** The signing key is
> permanent. A mistake here cannot be undone.

---

## The one thing that cannot be fixed later

Android binds an installed app to its signing certificate **forever**. Installing
an update signed with a different key fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and there is no override. The distribution
channel does not change this: a GitHub Releases APK behaves exactly the way a
store upload does, because the check lives in the package manager, not in the
store.

> On 2026-09-25 a release build was produced with the **debug key**. No signed
> release had been published at that point, so nothing shipped broken. Signing is
> now driven by `key.properties`, and a release build without it fails outright
> rather than silently falling back.

## One-time setup

**1. Keystore**, generated 2026-10-16 (rotated once: the first key of 2026-09-25 carried a
location in its certificate Distinguished Name and was destroyed before anything it signed
ever shipped, every GitHub release before 1.1.2 was built with the debug key).

| Item | Value |
|---|---|
| File | `hat-release.jks` (repo root, gitignored) |
| Alias | `hat` |
| Format | PKCS12 |
| DN | `CN=HAT, OU=andromedvn, O=HAT` (no location) |
| Validity | 2026-10-16 → ~2054-03 |
| SHA-256 | `F6:55:2D:DB:2E:CD:02:47:C8:6A:EC:00:A4:0F:1C:97:41:6D:5D:F8:7C:CF:10:FA:00:52:00:97:61:96:42:44` |
| Key + store password | in `key.properties` and in Proton Pass |

**2. Back up the keystore offline.** Proton Pass is the primary copy. Also keep
one copy on removable media or printed. A cloud-only copy does not protect you
from a compromised account.

**3. Record the SHA-256 fingerprint.** Keep it in Proton Pass next to the key.
`keytool -printcert -jarfile <apk>` prints the fingerprint of any built
artifact, so it can always be checked against that record before publishing.

## Building

```bash
# Always set JAVA_HOME (JDK 17+) and ANDROID_HOME first
export JAVA_HOME="/c/Users/dvn/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1"
export ANDROID_HOME="C:/Users/dvn/AppData/Local/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew assembleRelease            # the release artifact: a signed APK
```

HAT ships as an APK, not an AAB; there is no store to generate an app bundle
for. GitHub Releases is the delivery channel.

Output: `app/build/outputs/apk/release/app-release.apk`

## Verify before uploading, always

```bash
# Confirm the APK is signed by YOUR key, not the debug key
jarsigner -verify -verbose:summary app/build/outputs/apk/release/app-release.apk
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

Compare the `SHA256` fingerprint to the one in Proton Pass. **If it shows the
Android debug certificate (`61:ED:47:...`), stop.** `key.properties` was not
found, and uploading would permanently damage the app.

The build **fails** if `key.properties` is missing for a release task, rather
than producing a debug-signed artifact. To compile-check a release locally
without the key, pass `-PallowDebugSignedRelease=true`, never publish that
output.

## Release checklist

- [ ] `./gradlew assembleRelease` succeeds
- [ ] `keytool -printcert` shows **your** fingerprint, not the debug one
- [ ] `versionCode` incremented in `app/build.gradle.kts`
- [ ] `versionName` set, and the `[1.1.2] - Unreleased` section of CHANGELOG.md folded
      into a version heading (currently 1.1.2)
- [ ] Privacy Policy and Terms published on GitHub Pages
- [ ] CHANGELOG.md updated
- [ ] APK tested on a real device **from the release build**, not debug; this is
      the only step that proves the R8 keep rules are correct
- [ ] Release tag pushed, APK attached to the GitHub Release

## Distribution

HAT is distributed through GitHub Releases only. It is intentionally **not**
published to Google Play or F-Droid. That is a decision, not a missing step: do not add a Play Console or F-Droid submission to the release procedure.

## Known gaps

Both previously-listed blockers are now closed:

1. **`app/proguard-rules.pro` was empty while `isMinifyEnabled = true`.** R8 was
   running with no keep rules, so `kotlinx.serialization` serializers,
   WorkManager workers, and ViewModels could be stripped, with the failure
   appearing only at runtime on a user's device. The file now carries keep
   rules for each, and CI builds a release APK so a regression fails the build
   rather than a device.
2. **A release build fell back to the debug signing key when `key.properties`
   was missing.** The build succeeded and only warned. It now throws, unless
   `-PallowDebugSignedRelease=true` is passed explicitly. Confirm the
   fingerprint with `keytool` before distributing anything (see *Verify before
   uploading* above).

What remains genuinely unverified: **R8 correctness is checked by building, not
by running the minified APK.** `kotlinx.serialization` and WorkManager are
covered by keep rules, but the first real release should be smoke-tested on a
device before it is published.

Everything else is verified working: JDK 17, AGP 8.13.2, Gradle 8.13, Kotlin
2.3.21, `compileSdk`/`targetSdk` 36, and the full JVM unit test suite (run by
`.github/workflows/unit-tests.yml` on every push; read the current count from
the test report rather than this document, which would otherwise go stale).

## About targetSdk

Google requires new apps *and updates* to target a recent API level. The
requirement steps up annually, so this needs revisiting yearly. Check
<https://developer.android.com/google/play/requirements/target-sdk>.

## Troubleshooting

**"keystore password was incorrect"**: `key.properties` has wrong values.
Verify with:
```bash
keytool -list -keystore hat-release.jks -storepass "<storePassword>"
```

**"Failed to read key"**. PKCS12 keystores use the same password for the key and
the store. `key.properties` must have identical `storePassword` and `keyPassword`.

**Build is debug-signed**: `key.properties` missing or unreadable. Check it sits
at the **repo root**, beside `settings.gradle.kts`.
