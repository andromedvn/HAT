---
layout: default
title: Privacy Policy
subtitle: LEGAL & COMPLIANCE
heading: Privacy Policy
---

**HAT (Heuristic Activity Tracker)**
**Effective Date:** May 26, 2026

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

Under **Republic Act No. 10173 (Philippines Data Privacy Act 2012)** and its Implementing Rules and Regulations, personal information processed exclusively for personal, household, or journalistic purposes is similarly excluded from the law's full compliance regime. Your use of HAT to document your own daily schedule falls within this exclusion.

This does not mean your privacy is irrelevant — it means the legal relationship here is unusual. HAT is more like a local diary app than a service. Nobody reads your diary but you. This policy still matters because it sets clear expectations about how the software is built, what it touches on your device, and what would happen if those assumptions ever changed.

---

## 4. Data HAT Reads and Stores

### Permission 1 — Usage Access (`android.permission.PACKAGE_USAGE_STATS`)

HAT requires the **Usage Access** permission. This is a system-level, specially protected permission that Android requires you to grant manually through **Settings → Apps → Special app access → Usage access**. It is not granted automatically on install.

When granted, HAT reads the following from the Android operating system:

- Package names of installed applications
- Timestamps of when apps moved to the foreground and background
- Screen interactive events (screen turning on/off)
- Keyguard events (device locking/unlocking)

HAT does **not** read through this permission:

- The content of anything you did inside any app
- Notification content, messages, calls, or contacts
- Location data, clipboard contents, or media files
- Accessibility events or keystrokes

This data is used exclusively to reconstruct your daily timeline. It is stored in HAT's private SQLite database on your device and is never transmitted anywhere.

---

### Permission 2 — Foreground Service (`android.permission.FOREGROUND_SERVICE_DATA_SYNC`)

HAT declares the `FOREGROUND_SERVICE_DATA_SYNC` permission to allow its optional **background archive worker** to run as an Android foreground service. Android requires this declaration for any app that starts a data-sync-type foreground service targeting API 34+.

This permission does **not** grant network access. It does not enable HAT to send, receive, or sync data over the internet. Its sole function is to permit the archive worker to run persistently enough to read from `UsageStatsManager` and write to the local database before Android's event history window expires.

The foreground service runs on your device only, produces no network traffic, and terminates automatically after the archive operation completes.

---

### Permission 3 — Read External Storage (`android.permission.READ_EXTERNAL_STORAGE`, Android 8 only)

On devices running **Android 8.0 (API 26–28) only**, HAT declares `READ_EXTERNAL_STORAGE`. This permission is not requested on Android 9 or later, where scoped storage and the system file picker are used instead.

This permission is used solely to allow you to **import a previously exported vault backup** from your device's storage — for example, to restore your data after reinstalling HAT. HAT reads the `.zip` file you select and nothing else. It does not scan, index, or read any other files on your device.

---

### Data Stored Locally

Beyond the raw OS events read via permissions above, HAT creates and stores the following categories of data in its private app directory:

**Archived App Usage Intervals**
Android retains raw usage event data for a limited window (~14 days). HAT's optional background archive worker reads this data on a configurable schedule and saves it to a local SQLite database before the OS permanently deletes it.

**Offline Activity Logs**
When you manually label a gap in your timeline, HAT stores this entry in the local database with the title, timestamps, and chosen icon.

**Application Preferences and Settings**
HAT stores your preferences using Android's Jetpack DataStore library in a file within the app's private storage directory.

**Crash Logs and Diagnostic Events**
HAT includes a custom crash handler. When the app encounters an unhandled exception, it writes a crash report to a private file on your device. These files exist so that if you want to report a bug, you can share the relevant log voluntarily. The developer cannot access them without your active cooperation.

**Exported Vault Files**
HAT's "Backup Master Vault" feature exports a `.zip` file containing your databases and settings. Once you export this file, it is saved to whatever location you choose on your device or connected storage. At that point, the file is entirely under your control. The developer has no involvement in, or access to, its contents.

**Voluntary Communications (Support Email and GitHub)**
When you contact the developer voluntarily — by emailing andromedvn@proton.me or by opening an issue on GitHub — you necessarily disclose your email address or GitHub username as part of that communication.

> The following rules govern that information:
> - It is used solely to respond to your specific inquiry.
> - The developer does not maintain a contact database or CRM.
> - Your email address or GitHub username is not shared with, sold to, or disclosed to any third party.
> - Emails reside within the developer's ProtonMail inbox only for as long as reasonably necessary to address your inquiry.
> - If you attach a crash log, diagnostic export, or vault file, its contents are used only to diagnose and respond to the issue you reported. They are not retained after the inquiry is resolved.

---

## 5. Data HAT Does Not Collect

To be unambiguous:

- **No analytics data.** HAT contains no analytics SDK, event tracking, usage telemetry, or crash reporting service that transmits data externally.
- **No advertising identifiers.** HAT does not read the Android Advertising ID (AAID) or any equivalent identifier.
- **No network requests.** HAT does not declare `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE` in its manifest. Android enforces this at the OS level — any network call attempted by any component of the app would be blocked by the operating system regardless of intent.
- **No cloud sync.** There are no HAT servers. There is no HAT account system. There is nowhere for your data to go.
- **No location data.** HAT does not request or access GPS, network location, cell tower data, or IP-based geolocation.
- **No contacts, calendar, or camera access.** HAT does not read your contacts, calendar events, photos, microphone, or any sensor data.
- **No third-party data-gathering SDKs.** HAT's entire dependency tree consists of official Android Jetpack libraries and the Kotlin standard library. See Section 6 for the complete list.

**Technical note:** The absence of internet permission is not a policy choice that could be reversed by a software update without disclosing it — adding `android.permission.INTERNET` would require an explicit manifest change that would be visible in the app's source code, listed in any F-Droid build diff, and would trigger a new permission prompt during installation. You can verify HAT's current manifest at any time at [https://github.com/andromedvn/HAT](https://github.com/andromedvn/HAT).

---

## 6. Third-Party Libraries

HAT is built using the following open-source libraries. All are published by Google or JetBrains under the **Apache License 2.0** unless noted. None of these libraries transmit data in the context of HAT because the application lacks internet permission.

| Library | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Android Core utilities (Kotlin extensions) |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | Lifecycle-aware components |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.7.0 | ViewModel for Compose UI |
| `androidx.activity:activity-compose` | 1.8.2 | Activity integration for Compose |
| `androidx.compose:compose-bom` | 2024.02.00 | Compose version alignment |
| `androidx.compose.ui:ui` | (BOM-managed) | Jetpack Compose UI framework |
| `androidx.compose.ui:ui-graphics` | (BOM-managed) | Compose graphics |
| `androidx.compose.material3:material3` | (BOM-managed) | Material You UI components |
| `androidx.compose.material:material-icons-extended` | (BOM-managed) | Material icon set |
| `androidx.navigation:navigation-compose` | 2.7.7 | In-app navigation |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.6.3 | JSON serialization (Apache 2.0 / JetBrains) |
| `androidx.datastore:datastore-preferences` | 1.0.0 | Persistent key-value preferences storage |
| `androidx.work:work-runtime-ktx` | 2.9.0 | Background task scheduling (archive worker) |

The developer has reviewed each of these libraries and is not aware of any network activity, analytics collection, advertising identifiers, or remote data transmission performed by them in the context of this application. Because HAT lacks `android.permission.INTERNET`, any such attempt would fail at the OS level regardless.

---

## 7. Data Retention and Deletion

Your data is retained until you delete it. HAT provides the following deletion paths:

- **Delete individual entries** from within the timeline view.
- **Full database wipe** via Settings → Diagnostics → Factory Reset Engine.
- **Clear diagnostic logs** via Settings → Diagnostics → Clear Diagnostic Logs.
- **Uninstall the app** — Android permanently deletes the app's entire private sandbox, including all databases, preferences, and cached files, with no server-side copy remaining.

The developer retains no copy of your data at any time and therefore cannot fulfill remote deletion requests — there is nothing to delete on a server. All data control is yours, exercised through the app itself.

---

## 8. Android OS and Device Manufacturer Considerations

HAT's data completeness depends on the Android OS version and the device manufacturer's firmware. Some manufacturers (including Xiaomi, Samsung, Huawei, and OPPO) implement aggressive battery optimization that can kill background workers or affect how the OS records usage events.

These are platform-level limitations, not privacy gaps. They affect the completeness of your timeline. No data is exposed to third parties as a result of them. If you find that your archive worker is being killed or that your timeline has gaps, enabling "unrestricted" battery usage for HAT in your device's battery settings typically resolves this.

---

## 9. Your Rights

Because all of your data is stored locally on your device, you have direct, immediate, and complete control:

- **Access:** All data is visible within the app at all times.
- **Export:** Export your complete data archive at any time via Settings → Backup Master Vault.
- **Delete:** Use the deletion paths described in Section 7.
- **Correction:** Edit or relabel any activity log directly within the app.
- **Portability:** Exported vault files are standard SQLite databases inside a `.zip` archive — they are not proprietary formats.

**EU/EEA users:** Because the developer does not process your personal data, there is no data controller to address GDPR rights requests to regarding your usage data. You are the data controller. Your rights are exercised directly through the controls listed above.

**Philippines users:** The same applies under R.A. 10173. You are the data subject and the sole controller of your own HAT data.

---

## 10. Children's Privacy

HAT is not directed at children under the age of 13 (or the applicable minimum age in your jurisdiction). The developer does not knowingly collect personal information from children. Because HAT has no account system and no server infrastructure, there is no mechanism by which children's data could be separately identified or collected.

If you are a parent or guardian and believe HAT has been used on a minor's device in a way you have concerns about, please review the data deletion options in Section 7.

---

## 11. Changes to This Policy

If this policy materially changes, the developer will:

1. Update the **Effective Date** at the top of this document.
2. Announce the change in the release notes for the corresponding HAT version on [GitHub Releases](https://github.com/andromedvn/HAT/releases).

Past versions of this policy are available in the project's Git history at [https://github.com/andromedvn/HAT](https://github.com/andromedvn/HAT).

Continued use of HAT after a policy update constitutes acceptance of the revised policy. If you do not agree with a change, your recourse is to stop using the application and uninstall it — which, as noted above, permanently deletes all associated data from your device.

---

## 12. Contact

For questions, concerns, or feedback about this policy, contact the developer at:

**andromedvn**  
andromedvn@proton.me

Response times are not guaranteed. HAT is maintained on a volunteer basis with no commercial funding.

---

*This policy was written to be read by people. If something here is unclear or seems inconsistent with how the app actually behaves, that gap matters — please report it.*