---
layout: default
title: Privacy Policy - HAT
---

# Privacy Policy

**HAT (Heuristic Activity Tracker)**
**Effective Date:** May 26, 2026
**Last Reviewed:** May 26, 2026

---

## The Short Version

HAT works entirely on your device. Nothing you do in the app is sent anywhere. The developer cannot see your timeline, your offline logs, your settings, or anything else you create in HAT — not because of policy, but because the app has no internet permission and no server infrastructure to receive it.

This policy explains in plain terms exactly what data HAT reads and stores locally, what it does not do, and what rights you have under the laws that may apply to you.

---

## 1. Who This Policy Covers

This policy applies to anyone who installs and uses HAT, regardless of where you are in the world or how you obtained the application (GitHub, F-Droid, direct APK, or any other distribution channel).

If you are a developer who has forked the source code and is running a modified version of HAT, this policy may not accurately describe your version's behavior. Consult the privacy documentation for that fork.

---

## 2. About the Developer

HAT is developed and maintained by a single independent developer operating under the handle **andromedvn**.

- **Contact:** andromedvn@proton.me
- **Source Code:** [https://github.com/andromedvn/HAT](https://github.com/andromedvn/HAT)

The developer does not operate as a registered corporation. HAT is a free, open-source personal-use tool published under the GNU Affero General Public License version 3 (AGPL-3.0). The developer receives no revenue from HAT.

---

## 3. The Foundational Legal Position

Under **GDPR Article 2(2)(c)**, the regulation does not apply to processing of personal data by a natural person "in the course of a purely personal or household activity." Using HAT to track your own screen time and daily habits is exactly that — a personal household activity. The developer does not receive, access, or process any data you generate. You are the sole data controller of everything HAT produces on your device.

Under **Republic Act No. 10173 (Philippines Data Privacy Act 2012)**, and its Implementing Rules and Regulations, personal information processed exclusively for personal, household, or journalistic purposes is similarly excluded from the law's full compliance regime. Your use of HAT to document your own daily schedule falls within this exclusion.

This does not mean your privacy is irrelevant — it means the legal relationship here is unusual. HAT is more like a local diary app than a service. Nobody reads your diary but you. This policy still matters because it sets clear expectations about how the software is built, what it touches on your device, and what would happen if those assumptions ever changed.

---

## 4. Data HAT Reads and Stores

### 4.1 Usage Access Data (via Android's UsageStatsManager API)

HAT requires the **Usage Access** permission (`android.permission.PACKAGE_USAGE_STATS`). This is a system-level permission that Android requires you to grant manually through your device's Settings — it cannot be requested via a standard runtime permission dialog.

When this permission is granted, HAT reads the following categories of data from the Android operating system:

- **Package names** of installed applications (e.g., `com.instagram.android`). HAT reads these to label your timeline entries with app names and icons.
- **Timestamps** of when apps moved to the foreground (became active on screen) and background (were hidden or replaced).
- **Screen interactive events** — specifically, when your screen transitioned between interactive and non-interactive states.
- **Keyguard events** — when your device's lock screen was shown or dismissed.

HAT does **not** read:
- The content of anything you did inside any app
- Notification content
- Messages, calls, or contacts
- Location data
- Clipboard contents
- Photographs, media files, or any personal documents

This data is read from the OS on demand — when you open the app or navigate to a specific date — and is processed in memory to build your timeline. The raw OS event stream is not stored by HAT in any persistent form. Only derived data (the archived usage intervals described in Section 4.2) is saved to the local database.

### 4.2 Archived App Usage Intervals

Android retains raw usage event data for a limited window, typically between two weeks and several months depending on the device manufacturer and OS version. HAT's optional background archive worker reads this data on a configurable schedule and saves it to a local SQLite database before the OS permanently deletes it.

What is stored per entry: package name, session start time (Unix milliseconds), and session end time (Unix milliseconds). Nothing else.

This data never leaves your device. It is stored in the application's private internal storage (`/data/data/andromedvn.heuristic.activity.tracker/`), which other applications cannot read without root privileges.

### 4.3 Offline Activity Logs

When you manually label a gap in your timeline — recording that you were "Sleeping" from 11 PM to 7 AM, for example — HAT stores this entry in the local database with:

- The activity title you chose
- The start and end timestamps
- The duration in milliseconds
- The icon you selected

You wrote this. You own it. You can edit or delete it at any time from within the app.

### 4.4 Application Preferences and Settings

HAT stores your preferences using Android's **Jetpack DataStore** library, in a file within the app's private storage. These include:

- Theme choice (Dynamic or Static)
- Dashboard sort order
- Session clustering tolerance in minutes
- Actionable gap threshold in minutes
- Background archive sync interval in hours
- The list of applications you have hidden from your dashboard
- Timestamps of ghost sessions you have dismissed or acknowledged

None of this leaves your device.

### 4.5 Crash Logs and Diagnostic Events

HAT has a custom crash handler. When the app encounters an unhandled exception, it writes a crash report to a private file on your device. This report includes:

- The date and time of the crash
- Your device model and Android version
- A log of the last 50 internal diagnostic events (non-fatal logic errors, read/write anomalies, vault merge events)
- The full stack trace of the exception

These files are stored in the application's private storage and are not transmitted anywhere. They are readable only by you, through the Diagnostics screen in Settings, where you can export or delete them at any time.

These files exist so that if you want to report a bug, you can share the relevant log voluntarily. The developer cannot access them without your active cooperation.

### 4.6 Exported Vault Files

HAT's "Backup Master Vault" feature exports a `.zip` file containing:

- A JSON-encoded snapshot of your preferences, hidden apps, dismissed sessions, and acknowledged ghost sessions
- A copy of the raw SQLite database containing your archived usage intervals and offline activity logs
- An HMAC-SHA256 cryptographic signature verifying the integrity of the above two files

Once you export this file, it is saved to whatever location you choose on your device or connected storage. At that point, the file is entirely under your control. The developer has no involvement in, or access to, its contents. The vault is signed so that corrupt or tampered files can be detected on import — this protects you, not the developer.

### 4.7 Wallpaper Color (In-Memory, Not Stored)

When the "Dynamic Material" theme is active on Android 11 or earlier, HAT reads a compressed thumbnail of your current wallpaper to extract its dominant color. This extraction is performed entirely in memory. The wallpaper image is not stored to disk. The only thing that persists is the computed hex color value saved to your preferences — a small string like `#3A7CA5`.

On Android 12 and above, HAT uses the system's native `DynamicColors` API instead, which derives theme colors without any wallpaper access.

### 4.8 Voluntary Communications (Support Email and GitHub)

When you contact the developer voluntarily — by emailing andromedvn@proton.me or by opening an issue on GitHub — you necessarily disclose your email address or GitHub username as part of that communication. This is the only circumstance in which the developer receives any identifying information about a user.

The following rules govern that information:

- It is used **solely to respond to your specific inquiry.** It is not used for any other purpose.
- The developer does not maintain a contact database, CRM, mailing list, or any aggregated record of support contacts. Your identity is not added to any list.
- Your email address or GitHub username is not shared with, sold to, or disclosed to any third party.
- Emails reside within the developer's ProtonMail inbox only for as long as reasonably necessary to address the inquiry and any reasonable follow-up. The developer does not export or archive support correspondence to any secondary system. ProtonMail's own infrastructure governs server-level message handling — the developer cannot override their standard email delivery retention, but does not instruct ProtonMail to retain your communications for any purpose beyond normal email delivery.
- If you attach a crash log, diagnostic export, or vault file to a support communication, its contents are used only to diagnose and respond to the issue you reported. They are not analyzed for any other purpose and are not retained after the inquiry is resolved.

To request deletion of any support correspondence, email andromedvn@proton.me. The developer will make best efforts to delete stored communication within 30 days of such a request, subject to any legal obligation to retain it.

---

## 5. Data HAT Does Not Collect

To be explicit:

- **No analytics data.** HAT contains no analytics SDK, event tracking, usage telemetry, or crash reporting service that transmits data externally.
- **No advertising identifiers.** HAT does not read the Android Advertising ID (AAID) or any persistent device identifier used for cross-app tracking.
- **No network requests.** HAT does not declare `INTERNET` or `ACCESS_NETWORK_STATE` permissions in its manifest. Android enforces this at the OS level — the application cannot make network requests regardless of what any code attempts.
- **No cloud sync.** There are no HAT servers. There is no cloud infrastructure.
- **No account registration.** HAT does not require or offer accounts.
- **No content scanning.** HAT reads app package names and event timestamps. It does not read what you did inside any app.
- **No third-party data-gathering SDKs.** HAT's dependencies are standard Android Jetpack libraries (Compose, WorkManager, DataStore, Navigation), the Kotlin standard library, and `kotlinx.serialization`. None of these transmit data on HAT's behalf.

---

## 6. Data Security

The data HAT produces is protected by Android's application sandboxing:

- The SQLite database and DataStore preferences live in the app's private internal storage. No other app can read them without root access.
- HAT does not write anything to external or shared storage without your explicit action (vault export).
- Exported vault files are HMAC-signed with SHA-256. A vault modified after export, or corrupted during transfer, is rejected at import time.
- Crash logs are stored in private app storage with the same access restrictions.

HAT does not apply encryption-at-rest to the local database. The database is protected by the OS sandbox. If you are concerned about physical device access, Android's built-in file-based encryption (enabled by default on modern devices) provides an additional layer of protection independent of HAT.

---

## 7. Data Retention and Deletion

Your data is retained until you delete it. HAT offers several deletion paths:

- **Individual offline activity logs:** Delete from within the app.
- **All stored data:** Use "Factory Reset Engine" in the Diagnostics screen to wipe the database and reset preferences.
- **Crash and diagnostic logs:** Use "Clear Diagnostic Logs" in the Diagnostics screen.
- **Full deletion:** Uninstalling HAT removes all data in the app's private internal storage. On some Android versions, residual files may remain in shared storage if you exported files there — delete those manually.
- **Exported vault files:** HAT has no control over files you have saved outside the app. Deleting them is your responsibility.

---

## 8. Your Rights

### 8.1 All Users

Because all HAT data is local and you are the sole data controller, you exercise the following rights directly through the app without needing to contact anyone:

| Right | How to Exercise It in HAT |
|---|---|
| Access | All data is visible in the app. Vault export gives you a complete copy. |
| Portability | "Backup Master Vault" exports everything as a structured archive. |
| Erasure | "Factory Reset Engine" or uninstall. |
| Correction | Offline logs can be edited. Dismissed ghost sessions can be restored. |
| Restrict processing | Revoke Usage Access permission in Android Settings. |
| Stop background processing | Set Background History Sync to "Off" in Settings. |

### 8.2 Users in the EEA — GDPR

**Legal basis:** To the extent GDPR applies to self-directed personal use (see Section 3), HAT processes data on the basis of your explicit consent, given when you grant the Usage Access permission. You may withdraw consent at any time by revoking this permission in Android Settings.

**Data controller:** For purposes of any residual processing the developer is considered to have involvement in — which, under HAT's architecture, is none — contact: andromedvn@proton.me.

**Right to lodge a complaint:** You may file a complaint with the data protection supervisory authority in your EU member state.

**International transfers:** No data is transferred internationally. No data leaves your device.

**Right not to be subject to automated decision-making:** HAT's heuristic engine processes your usage data to build your timeline, but this is software running locally on your device, producing output for you, not decisions made about you by a third party.

### 8.3 California Residents — CCPA/CPRA

HAT does not sell personal information. HAT does not share personal information for cross-context behavioral advertising. The developer collects no personal information about you in any form.

All rights under CCPA/CPRA — access, deletion, correction, and the right to opt out of sale or sharing — are exercised through the app itself. No request to the developer is needed.

For any CCPA-related inquiry: andromedvn@proton.me.

### 8.4 Philippine Users — Republic Act No. 10173

HAT's developer operates from the Philippines. RA 10173 and its Implementing Rules are the primary governing framework for this policy.

Data subjects under RA 10173 have the following rights, exercisable as described:

| Right | How to Exercise |
|---|---|
| Right to be informed | This policy fulfills that obligation. |
| Right to access | Data is visible and exportable within the app. |
| Right to correction | Edit offline logs within the app. |
| Right to erasure or blocking | Factory Reset Engine or uninstall. |
| Right to data portability | Vault export. |
| Right to object | Revoke Usage Access in Android Settings. |
| Right to file a complaint | National Privacy Commission (NPC) at [privacy.gov.ph](https://privacy.gov.ph/) |

**Breach notification:** Under RA 10173, personal information controllers must notify the NPC and affected data subjects within 72 hours of a data breach. Because the developer does not hold any user data, a breach of the developer's systems would not expose HAT user data. A breach of your own device is outside the developer's control or knowledge. If a security vulnerability in HAT's code could expose stored data on a device, the developer commits to disclosing it publicly via the GitHub repository without undue delay.

---

## 9. Children's Privacy

HAT is not directed at children under 13 (or under 18 where that is the applicable threshold). The app does not include features designed for children, and the developer does not knowingly collect data from children.

Because HAT stores all data locally and the developer cannot access any of it, the developer cannot verify user ages. Parental supervision of device usage and app installation is the appropriate safeguard — not developer-side age verification for an app that transmits nothing.

Concerns about HAT's suitability for a minor: andromedvn@proton.me.

---

## 10. Android OS and Device Manufacturer Considerations

HAT's data completeness depends on the Android OS and the device manufacturer's firmware.

- Some manufacturers (including Xiaomi, Samsung, Huawei, and OPPO) implement aggressive battery optimization that can kill background workers, cause HAT's archive worker to fire later than scheduled, or affect how the OS records usage events.
- The OS's retention window for raw usage event data varies by device and Android version. HAT cannot reconstruct history the OS has already deleted.
- Inaccuracies in the timeline — missing sessions, ghost sessions caused by device-specific screen behavior — are products of the underlying OS, not of HAT's logic.

These are limitations of the platform, not gaps in privacy protection. They affect data completeness. No data is exposed to third parties because of them.

---

## 11. Changes to This Policy

If this policy changes in a way that reduces privacy protections — for example, if a future version of HAT added network connectivity — the updated policy will be published at [https://andromedvn.github.io/HAT/PRIVACY.html](https://andromedvn.github.io/HAT/PRIVACY.html), the "Effective Date" will be updated, and the change will be noted in the release notes for the corresponding app version.

Continued use of the app after an updated policy is published constitutes acceptance of the new terms. If you disagree with a change, uninstall the app.

---

## 12. Contact

**Email:** andromedvn@proton.me
**GitHub Issues:** [https://github.com/andromedvn/HAT/issues](https://github.com/andromedvn/HAT/issues)

Genuine privacy inquiries will receive a response within 30 days.

---

*This policy was written to be read by people. If something here is unclear or seems inconsistent with how the app actually behaves, that gap matters — please report it.*
