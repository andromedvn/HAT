# Releasing HAT

> **Read this before producing any uploadable artifact.** The signing key is
> permanent. A mistake here cannot be undone.

---

## The one thing that cannot be fixed later

Google Play binds your app to its signing certificate **forever**. The first
version you upload locks in that key. If you later lose the key, or sign with a
different one, every future upload is rejected as a *different app* that happens
to share a package name. There is no override.

> On 2026-09-25 the release build was signed with the **debug key**
> (`app/build.gradle.kts`). That was fixed before first upload. It is now driven
> by `key.properties`.

## One-time setup

**1. Keystore** — generated 2026-09-25, PKCS12, RSA 4096, valid until 2054-02-10.

| Item | Value |
|---|---|
| File | `hat-release.jks` (repo root, gitignored) |
| Alias | `hat` |
| Format | PKCS12 |
| Validity | 2026-09-25 → 2054-02-10 |
| Key + store password | in `key.properties` and in Proton Pass |

**2. Back up the keystore offline.** Proton Pass is the primary copy. Also keep
one copy on removable media or printed. A cloud-only copy does not protect you
from a compromised account.

**3. Record the SHA-256 fingerprint.** Google shows it in Play Console under
*Release → Setup → App signing*. The **app signing key** Google shows is Google's
own upload key, which differs from this one — that is expected and correct. Keep
your own fingerprint recorded anyway.

## Building

```bash
# Always set JAVA_HOME (JDK 17+) and ANDROID_HOME first
export JAVA_HOME="/c/Users/dvn/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1"
export ANDROID_HOME="C:/Users/dvn/AppData/Local/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew clean bundleRelease        # AAB for Play Console
./gradlew assembleRelease            # signed APK, for manual sideloading
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Verify before uploading — always

```bash
# Confirm the bundle is signed by YOUR key, not the debug key
jarsigner -verify -verbose:summary app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

Compare the `SHA256` fingerprint to the one in Proton Pass. **If it shows the
Android debug certificate (`61:ED:47:...`), stop.** `key.properties` was not
found, and uploading would permanently damage the app.

The build also prints a loud warning when `key.properties` is missing.

## Release checklist

- [ ] `./gradlew clean bundleRelease` succeeds
- [ ] `keytool -printcert` shows **your** fingerprint, not the debug one
- [ ] `versionCode` incremented in `app/build.gradle.kts` (currently 2)
- [ ] `versionName` set (currently 1.1.1)
- [ ] Privacy Policy and Terms published on GitHub Pages
- [ ] Fastlane metadata updated: title, short description, full description
- [ ] Screenshots are 1080p or higher
- [ ] CHANGELOG.md updated (note: currently gitignored — remove that line)
- [ ] APK/AAB tested on a real device from the release build, not debug

## Current blockers before first upload

1. **`targetSdk = 34`.** Play requires **API 36** since 2026-08-31.
2. **AGP 8.1.3 cannot compile against SDK 36** — toolchain must be upgraded
   (AGP 8.9+, Kotlin 2.0+, current Compose BOM, Gradle 8.11+).
3. **No CI** — builds are manual until a GitHub Actions workflow exists.
4. **No tests** — add at least basic coverage of `HeuristicEngine` and
   `TimeUtils` before relying on automated releases.

## About targetSdk

Google requires new apps *and updates* to target a recent API level. The
requirement steps up annually, so this needs revisiting yearly. Check
<https://developer.android.com/google/play/requirements/target-sdk>.

## Troubleshooting

**"keystore password was incorrect"** — `key.properties` has wrong values.
Verify with:
```bash
keytool -list -keystore hat-release.jks -storepass "<storePassword>"
```

**"Failed to read key"** — PKCS12 keystores use the same password for the key and
the store. `key.properties` must have identical `storePassword` and `keyPassword`.

**Build is debug-signed** — `key.properties` missing or unreadable. Check it sits
at the **repo root**, beside `settings.gradle.kts`.
