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

## 1. Who This Policy Covers

This policy applies to anyone who installs and uses HAT, regardless of where you are in the world or how you obtained the application (GitHub, F-Droid, direct APK, or any other distribution channel).

If you are a developer who has forked the source code and is running a modified version of HAT, this policy may not accurately describe your version's behavior. Consult the privacy documentation for that fork.

## 2. About the Developer

HAT is developed and maintained by a single independent developer operating under the handle **andromedvn**.

- **Contact:** andromedvn@proton.me
- **Source Code:** [https://github.com/andromedvn/HAT](https://github.com/andromedvn/HAT)

The developer does not operate as a registered corporation. HAT is a free, open-source personal-use tool published under the GNU Affero General Public License version 3 (AGPL-3.0). The developer receives no revenue from HAT.

## 3. The Foundational Legal Position

Under **GDPR Article 2(2)(c)**, the regulation does not apply to processing of personal data by a natural person "in the course of a purely personal or household activity." Using HAT to track your own screen time and daily habits is exactly that — a personal household activity. The developer does not receive, access, or process any data you generate. You are the sole data controller of everything HAT produces on your device.

Under **Republic Act No. 10173 (Philippines Data Privacy Act 2012)**, and its Implementing Rules and Regulations, personal information processed exclusively for personal, household, or journalistic purposes is similarly excluded from the law's full compliance regime. Your use of HAT to document your own daily schedule falls within this exclusion.

This does not mean your privacy is irrelevant — it means the legal relationship here is unusual. HAT is more like a local diary app than a service. Nobody reads your diary but you. This policy still matters because it sets clear expectations about how the software is built, what it touches on your device, and what would happen if those assumptions ever changed.

## 4. Data HAT Reads and Stores

**Usage Access Data (via Android's UsageStatsManager API)**  
HAT requires the **Usage Access** permission (`android.permission.PACKAGE_USAGE_STATS`). This is a system-level permission that Android requires you to grant manually through your device's Settings.

When this permission is granted, HAT reads the following categories of data from the Android operating system:
- Package names of installed applications
- Timestamps of when apps moved to the foreground and background
- Screen interactive events (screen turning on/off)
- Keyguard events (device locking/unlocking)

HAT does **not** read:
- The content of anything you did inside any app
- Notification content, messages, calls, or contacts
- Location data, clipboard contents, or media files

**Archived App Usage Intervals**  
Android retains raw usage event data for a limited window. HAT's optional background archive worker reads this data on a configurable schedule and saves it to a local SQLite database before the OS permanently deletes it.

**Offline Activity Logs**  
When you manually label a gap in your timeline, HAT stores this entry in the local database with the title, timestamps, and chosen icon.

**Application Preferences and Settings**  
HAT stores your preferences using Android's Jetpack DataStore library in a file within the app's private storage.

**Crash Logs and Diagnostic Events**  
HAT has a custom crash handler. When the app encounters an unhandled exception, it writes a crash report to a private file on your device. These files exist so that if you want to report a bug, you can share the relevant log voluntarily. The developer cannot access them without your active cooperation.

**Exported Vault Files**  
HAT's "Backup Master Vault" feature exports a `.zip` file containing your databases and settings. Once you export this file, it is saved to whatever location you choose on your device or connected storage. At that point, the file is entirely under your control. The developer has no involvement in, or access to, its contents.

**Voluntary Communications (Support Email and GitHub)**  
When you contact the developer voluntarily — by emailing andromedvn@proton.me or by opening an issue on GitHub — you necessarily disclose your email address or GitHub username as part of that communication. 

```text
The following rules govern that information:
- It is used solely to respond to your specific inquiry.
- The developer does not maintain a contact database or CRM.
- Your email address or GitHub username is not shared with, sold to, or disclosed to any third party.
- Emails reside within the developer's ProtonMail inbox only for as long as reasonably necessary.
- If you attach a crash log, diagnostic export, or vault file, its contents are used only to diagnose and respond to the issue you reported. They are not retained after the inquiry is resolved.
```

## 5. Data HAT Does Not Collect

To be explicit:
- **No analytics data.** HAT contains no analytics SDK, event tracking, usage telemetry, or crash reporting service that transmits data externally.
- **No advertising identifiers.** HAT does not read the Android Advertising ID (AAID).
- **No network requests.** HAT does not declare `INTERNET` or `ACCESS_NETWORK_STATE` permissions in its manifest. Android enforces this at the OS level.
- **No cloud sync.** There are no HAT servers.
- **No third-party data-gathering SDKs.** HAT's dependencies are standard Android Jetpack libraries and the Kotlin standard library.

## 6. Data Retention and Deletion

Your data is retained until you delete it. HAT offers several deletion paths:
- Delete individual offline activity logs from within the app.
- Wipe the database entirely using the "Factory Reset Engine" option in the diagnostics menu.
- Clear diagnostic logs using "Clear Diagnostic Logs" in the diagnostics menu.
- Uninstall the application from your device, which instructs the Android OS to permanently wipe the app's private sandbox.

## 7. Android OS and Device Manufacturer Considerations

HAT's data completeness depends on the Android OS and the device manufacturer's firmware. Some manufacturers (including Xiaomi, Samsung, Huawei, and OPPO) implement aggressive battery optimization that can kill background workers or affect how the OS records usage events. These are limitations of the platform, not gaps in privacy protection. They affect data completeness. No data is exposed to third parties because of them.
