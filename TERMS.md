---
layout: default
title: Terms of Service - HAT
---

# Terms of Service & End-User License Agreement

**HAT (Heuristic Activity Tracker)**
**Effective Date:** May 26, 2026
**Last Reviewed:** May 26, 2026

---

## Preamble

This document governs your use of the HAT application (the compiled software). It is not the same as the software license. HAT's source code is licensed separately under the **GNU Affero General Public License version 3 (AGPL-3.0)**, the full text of which is included in the repository and governs what you may do with the source code.

These Terms of Service govern your use of the compiled, installed application — the experience of running HAT on your device. Both documents apply to you if you use HAT. Where they overlap, the AGPL governs source code matters and these Terms govern everything else.

Read this before using the app. Using HAT means you accept these terms. If you don't accept them, don't use the app.

---

## 1. Definitions

In this document, the following terms have specific meanings:

**"Application"** means the HAT software in its compiled form, as installed on your Android device, including all updates and versions.

**"Developer"** means andromedvn, the sole author and maintainer of HAT, reachable at andromedvn@proton.me.

**"You" or "User"** means the individual installing or using the Application.

**"Source Code"** means the Kotlin and resource files that constitute HAT's codebase, published at [https://github.com/andromedvn/HAT](https://github.com/andromedvn/HAT) under the AGPL-3.0 license.

**"Data"** means any information stored on your device by or through the Application, including app usage intervals, offline activity logs, preferences, and crash logs.

**"Vault"** means the HMAC-signed `.zip` archive exported by the Application's backup feature.

**"Usage Access"** means the Android system permission (`android.permission.PACKAGE_USAGE_STATS`) that allows the Application to query app usage events from the operating system.

**"OS Events"** means the raw usage event data provided to the Application by Android's `UsageStatsManager` API.

**"Third-Party Libraries"** means the open-source libraries the Application incorporates as dependencies, as listed in Section 9.

---

## 2. Acceptance of These Terms

By installing, downloading, or using the Application, you confirm that:

1. You have read and understood these Terms.
2. You have the legal capacity to enter into a binding agreement in your jurisdiction.
3. If you are using the Application on behalf of an organization, you have the authority to bind that organization to these Terms.

If you do not agree to these Terms, you must not install or use the Application. Since HAT is free and open-source, you are always free to stop using it and uninstall it at any time.

---

## 3. The License Grant

Subject to these Terms, the Developer grants you a limited, non-exclusive, non-transferable, revocable license to install and use the Application on Android devices you personally own or control, for your own personal, non-commercial purposes.

**This license does not include:**

- The right to sublicense the Application to others in compiled form
- The right to use the Application to build a commercial data-collection or screen-time monitoring service directed at third parties without their knowledge or consent
- The right to reverse-engineer the Application for purposes of creating a competing product that does not comply with the AGPL-3.0 obligations
- The right to remove or obscure any copyright notices, license notices, or attribution statements in the Application

**Source Code Rights:** Your rights with respect to the Application's source code are governed separately by the AGPL-3.0. If you fork, modify, or distribute the source code or binaries derived from it, AGPL-3.0 applies in full — including the requirement to make modified source code publicly available under the same license.

---

## 4. Permitted Use

You may:

- Install the Application on one or more Android devices you personally control
- Use the Application to track your own screen time and label your own offline activities
- Export your Data as a Vault for personal backup and device migration
- Share your Vault with yourself across devices
- Build on the Source Code under the terms of the AGPL-3.0

---

## 5. Prohibited Use

You may not use the Application to:

- **Monitor other people's devices without their knowledge and consent.** Installing or operating HAT on another person's device to track their activity without that person's informed consent is prohibited by these Terms and likely illegal in most jurisdictions under computer fraud, surveillance, and privacy laws.
- **Circumvent Android security mechanisms.** You may not use the Application or its Source Code to exploit vulnerabilities in the Android operating system or in other installed applications.
- **Process third-party data commercially.** You may not use the Application, or a modified version of it, to collect, aggregate, or monetize usage data from other people's devices without their explicit informed consent and in compliance with all applicable data protection laws.
- **Misrepresent origin.** You may not distribute modified versions of the Application that impersonate the original HAT or that falsely imply endorsement by the Developer.
- **Violate applicable law.** You may not use the Application in connection with any activity that violates the laws of your jurisdiction or the Philippines.

---

## 6. Privacy

HAT's data practices are described in full in the [Privacy Policy](https://andromedvn.github.io/HAT/PRIVACY.html). That document is incorporated into these Terms by reference. By using the Application, you also accept the Privacy Policy.

The short version: all data stays on your device. The Developer has no access to it. The Application has no internet permission and cannot transmit data to anyone.

---

## 7. Accuracy and OS Dependency Disclaimer

This section exists because HAT's output depends entirely on data provided by the Android operating system, and that data is imperfect. Understanding this limitation protects both you and the Developer.

### 7.1 OS Event Accuracy

The Application reconstructs your daily timeline by reading event data from Android's `UsageStatsManager` API. This API is maintained by Google and the Android platform, not by the Developer. Its accuracy depends on:

- Your device's Android version and manufacturer firmware
- Whether battery optimization settings interfere with event recording
- Whether your device experienced unexpected shutdowns or restarts during the tracked period
- The rate at which your device manufacturer's OS customizations record, delay, or omit events

**The Developer makes no representation that the timeline produced by the Application is complete or accurate.** The Application is a reconstruction tool, not an authoritative record. It produces a best-effort representation of your day based on available OS data.

### 7.2 OS Retention Window

Android permanently deletes usage event data on a rolling basis, typically retaining between two weeks and several months of history depending on the device and OS version. Once data has been deleted by the OS, it cannot be recovered. The Application's archive worker mitigates this by saving data to local storage before the window closes, but it cannot recover data that was deleted before the Application was installed or before the archive worker ran.

### 7.3 Device Manufacturer Behavior

Multiple Android device manufacturers implement battery optimization and background process management in ways that differ from Android's documented behavior. On devices made by Xiaomi, Samsung, Huawei, OPPO, Vivo, and others, background workers (including HAT's archive worker) may be delayed, skipped, or terminated regardless of their configured schedule. The Developer cannot control this behavior, cannot guarantee that the archive worker will run on any specific schedule on any specific device, and accepts no liability for data gaps caused by manufacturer-level process management.

### 7.4 No Reliance on Timeline Data for Legal or Medical Purposes

The timeline HAT produces is intended for personal insight and planning. It is not a verified record of activity. You must not rely on HAT's timeline data for legal proceedings, medical assessments, workplace time tracking, insurance claims, or any other context where an authoritative or legally binding record is required.

### 7.5 Official Distribution Channels; No Liability for Third-Party Clones

HAT is distributed exclusively through the following **Official Channels**:

- **GitHub Releases:** [https://github.com/andromedvn/HAT/releases](https://github.com/andromedvn/HAT/releases)
- **F-Droid:** The open-source Android application repository at [https://f-droid.org](https://f-droid.org) (submission pending)

Because HAT's source code is published under the AGPL-3.0, any person may legally compile and redistribute modified or unmodified versions of the Application. The Developer exercises no control over third-party compilations, unofficial mirrors, APK-hosting sites, app stores other than F-Droid, or any forks.

**The Developer assumes absolutely zero liability for any binary not obtained directly from an Official Channel.** Without limitation, this covers:

- APKs distributed on third-party hosting sites (including but not limited to APKPure, APKMirror, Aptoide, and similar services)
- Modified versions that introduce additional code, network permissions, tracking SDKs, malware, spyware, or any behavior not present in the Official Channel release
- Repackaged versions that appropriate HAT's name, icon, screenshots, or branding — with or without authorization — to distribute a materially different application
- Forks that remove cryptographic integrity checks, alter privacy protections, or reduce the security of the vault system

If you obtained HAT from a source other than an Official Channel, the Developer cannot verify the integrity, safety, or behavior of your installation. These Terms of Service do not apply to non-official binaries and create no warranty, representation, or liability with respect to them.

To verify authenticity of a GitHub Release APK, compare its SHA-256 hash against the hash published in the corresponding GitHub Release page. F-Droid independently builds all APKs from published source code and is the recommended installation method for users who do not compile from source.

---

## 8. As-Is Warranty Disclaimer

**THE APPLICATION IS PROVIDED "AS IS" AND "AS AVAILABLE," WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.**

**TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, THE DEVELOPER EXPRESSLY DISCLAIMS ALL WARRANTIES, INCLUDING BUT NOT LIMITED TO:**

- **WARRANTIES OF MERCHANTABILITY** — that the Application will meet your commercial needs or function as you expect
- **WARRANTIES OF FITNESS FOR A PARTICULAR PURPOSE** — that the Application is suited to any specific use case you have in mind
- **WARRANTIES OF NON-INFRINGEMENT** — that the Application does not infringe third-party intellectual property rights
- **WARRANTIES OF ACCURACY OR COMPLETENESS** — that the timeline, session data, or gap analysis produced by the Application is correct, complete, or representative of your actual device activity
- **WARRANTIES OF DATA PRESERVATION** — that your Data will not be lost, corrupted, or deleted due to software bugs, device failure, OS updates, or any other cause

This disclaimer applies regardless of whether the Developer was informed of the possibility of such defects.

If you are in a jurisdiction that does not permit implied warranties to be excluded, the above disclaimer applies to the fullest extent permitted by that jurisdiction's laws.

---

## 9. Limitation of Liability

**TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL THE DEVELOPER BE LIABLE FOR ANY:**

- **Direct, indirect, incidental, special, exemplary, or consequential damages**
- **Loss of Data, whether through software bugs, device failure, OS updates, improper vault handling, or any other cause**
- **Loss of profits, business, revenue, or anticipated savings**
- **Loss of goodwill or reputation**
- **Cost of substitute software or services**
- **Any claims arising from reliance on the accuracy or completeness of the Application's timeline data**

**THIS LIMITATION APPLIES REGARDLESS OF THE THEORY OF LIABILITY — WHETHER CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY, OR OTHERWISE — AND REGARDLESS OF WHETHER THE DEVELOPER HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.**

Because HAT is provided free of charge and the Developer receives no revenue from it, the Developer's maximum aggregate liability to you for any claim arising out of your use of the Application is **zero (₱0.00 / $0.00 USD / €0.00 EUR)** or the minimum amount required by mandatory applicable law, whichever is greater.

Some jurisdictions do not allow the exclusion or limitation of incidental or consequential damages. In such jurisdictions, liability is limited to the greatest extent the law permits.

---

## 10. Data Loss and Backup Responsibility

Because HAT has no cloud backup and the Developer cannot access your Data remotely, **you are solely responsible for preserving your Data.**

The Developer strongly recommends using the "Backup Master Vault" feature regularly, storing the exported vault in a safe location separate from your primary device, and verifying that vault files can be successfully imported before relying on them as a backup.

The Developer accepts no liability for Data loss caused by:

- Device failure, theft, or loss
- Accidental uninstallation of the Application
- Android OS updates that modify or clear application data
- User-initiated data deletion (Factory Reset Engine or clearing app data)
- Corruption of exported vault files
- Failure of the archive worker to execute due to device-level battery management

---

## 11. Third-Party Open-Source Components

The Application incorporates the following third-party libraries. Each is licensed separately from HAT itself:

| Library | Publisher | License |
|---|---|---|
| AndroidX Core KTX | Google / The Android Open Source Project | Apache 2.0 |
| Jetpack Compose (BOM) | Google / AOSP | Apache 2.0 |
| Jetpack Navigation Compose | Google / AOSP | Apache 2.0 |
| AndroidX Lifecycle ViewModel | Google / AOSP | Apache 2.0 |
| AndroidX DataStore Preferences | Google / AOSP | Apache 2.0 |
| AndroidX WorkManager | Google / AOSP | Apache 2.0 |
| Material Icons Extended | Google / AOSP | Apache 2.0 |
| kotlinx.serialization | JetBrains | Apache 2.0 |

Full license texts for these libraries are available within the Application under Settings → Open Source Licenses.

These libraries are provided by their respective publishers under their respective terms. The Developer makes no warranty regarding their behavior, accuracy, or fitness for any purpose.

---

## 12. Open-Source License Compliance (AGPL-3.0)

HAT's Source Code is published under the **GNU Affero General Public License version 3**. The key practical implications are:

1. **You may use, copy, and modify the Source Code freely.**
2. **If you distribute the Application (or a modified version of it) to others**, you must make the corresponding source code available under the AGPL-3.0.
3. **If you run a modified version of HAT as a service over a network** — even without distributing binaries — AGPL requires you to make the modified source code available.
4. The Developer retains copyright in the original Source Code. Contributing to the project via pull request does not transfer your copyright; contributors retain copyright in their contributions while granting the project a license to use them under AGPL-3.0.

The full text of the AGPL-3.0 is included in the repository at `LICENSE`. If your intended use is not clearly covered, contact the Developer before proceeding.

---

## 13. Children

The Application is not intended for children under the age of 13 (or under 18 in jurisdictions where that is the minimum age of digital consent). By using the Application, you confirm that you meet this age requirement, or that a parent or legal guardian has reviewed and consented to your use.

The Developer does not knowingly permit children under applicable age limits to use the Application and will take steps to restrict such use if it comes to the Developer's attention.

---

## 14. Export Compliance

The Application is developed in and distributed from the Philippines. As a software tool that contains standard cryptographic functionality (HMAC-SHA256 for vault integrity verification), it may be subject to export control regulations in various jurisdictions, including the **U.S. Export Administration Regulations (EAR)** and corresponding regulations in other countries.

You agree that you will not use or export the Application in violation of applicable export control laws. Specifically, you may not use the Application if:

- You are located in a country subject to a U.S. Government embargo or that has been designated as a "terrorist-supporting" country by the U.S. Government
- You are listed on any U.S. Government list of prohibited or restricted parties (including the Specially Designated Nationals list maintained by OFAC)

Because HMAC-SHA256 is a standard cryptographic algorithm widely available in public libraries, it is not expected to trigger export licensing requirements under EAR's License Exception ENC for open-source cryptographic components. If you are uncertain about your specific situation, consult legal counsel in your jurisdiction.

---

## 15. Modification and Termination

### 15.1 Changes to These Terms

The Developer may update these Terms at any time. Updated Terms will be published at [https://andromedvn.github.io/HAT/TERMS.html](https://andromedvn.github.io/HAT/TERMS.html), and the "Effective Date" will be updated. Significant changes will be noted in the Application's release notes.

Continued use of the Application after updated Terms have been published constitutes acceptance of the changes. If you do not accept updated Terms, stop using the Application and uninstall it.

### 15.2 Termination by You

You may stop using the Application and uninstall it at any time. There is no subscription to cancel, no account to close, and no data held remotely to request deletion of.

### 15.3 Termination by the Developer

The Developer may terminate this license (and thus your right to use the Application under these Terms) if you materially breach these Terms and fail to remedy the breach within 30 days of receiving notice. Termination does not affect any rights or remedies the Developer may have at law.

---

## 16. Indemnification

You agree to defend, indemnify, and hold harmless the Developer from and against any claims, damages, losses, liabilities, costs, and expenses (including reasonable legal fees) arising from:

- Your use of the Application in violation of these Terms
- Your violation of any applicable law or regulation in connection with use of the Application
- Your use of the Application to monitor another person's device without their knowledge or consent
- Any claim by a third party arising from your distribution of a modified version of the Application that fails to comply with the AGPL-3.0

---

## 17. Force Majeure

The Developer is not liable for any failure or delay in performing obligations under these Terms if such failure or delay arises from circumstances beyond reasonable control, including but not limited to: natural disasters, acts of government, changes in law or regulation, infrastructure failures (including the discontinuation of services on which HAT depends, such as the Android `UsageStatsManager` API), or other events that could not reasonably have been anticipated or prevented.

---

## 18. Governing Law and Dispute Resolution

### 18.1 Governing Law

These Terms are governed by and construed in accordance with the laws of the **Republic of the Philippines**, without regard to its conflict of law principles.

For users in the European Economic Area, this choice of governing law does not deprive you of any protection afforded by the mandatory provisions of the consumer protection laws in your country of residence.

For users in the United States, you also retain any rights available to you under the mandatory consumer protection laws of your state.

### 18.2 Informal Resolution

Before initiating any formal dispute process, you agree to contact the Developer at andromedvn@proton.me and give at least 30 days for the parties to attempt to resolve the dispute informally. Most issues can be resolved this way.

### 18.3 Dispute Resolution

If informal resolution fails, disputes arising out of or relating to these Terms or the Application shall be resolved as follows:

- **For users in the Philippines:** In the appropriate courts of the Republic of the Philippines, which shall have exclusive jurisdiction consistent with RA 10173 and other applicable Philippine law.
- **For users in the EU/EEA:** As required by mandatory EU consumer protection law, you may bring a claim before the courts of your country of habitual residence. The Developer accepts the jurisdiction of those courts for claims by EU/EEA consumers that cannot be contractually excluded.
- **For all other users, without exception:** The **exclusive jurisdiction** for any dispute shall be the competent courts of the **Republic of the Philippines**. You irrevocably submit to the personal jurisdiction of those courts, waive any objection to that venue on grounds of inconvenient forum, and acknowledge that you may be required to retain Philippine legal counsel and appear in Philippine proceedings to pursue any claim.

The Developer has designated the Philippines as the exclusive non-EU forum as a practical matter of access, cost, and fairness to a solo, zero-revenue developer. The effect is intentional: any party considering litigation over a free application with no commercial revenue should first weigh the practical cost and logistics of doing so in the Philippines. The Developer does not, however, waive the right to seek injunctive or other emergency equitable relief in any jurisdiction where necessary to prevent irreparable harm to intellectual property rights or to stop an ongoing violation of these Terms.

### 18.4 Class Action Waiver

To the maximum extent permitted by applicable law, you agree that any dispute resolution will be conducted on an individual basis only, and not as a class, collective, or representative action.

---

## 19. Severability

If any provision of these Terms is found to be unenforceable or invalid under applicable law, that provision is modified to the minimum extent necessary to make it enforceable, or severed if modification is not possible. The remaining provisions of these Terms remain in full force and effect.

---

## 20. Entire Agreement

These Terms, together with the Privacy Policy and the AGPL-3.0 (as applicable to source code), constitute the entire agreement between you and the Developer regarding the Application. They supersede all prior agreements, representations, and understandings, whether written or oral, regarding the Application.

No waiver by the Developer of any breach of these Terms shall constitute a waiver of any subsequent breach.

---

## 21. Contact

For questions, concerns, or notices under these Terms:

**Email:** andromedvn@proton.me
**GitHub:** [https://github.com/andromedvn/HAT/issues](https://github.com/andromedvn/HAT/issues)

---

> ⚖️ *These Terms were written to be clear and fair, not to trap you in legal language. If something here is ambiguous or seems unreasonable, raise it — contact information is above.*
