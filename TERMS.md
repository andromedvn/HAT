---
layout: default
title: Terms of Use - HAT
---

# Terms of Use

HAT is free, open-source software. The license that actually governs the code is the [GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html). It covers copying, modifying, and sharing, and it applies to you automatically. This document is not that license. It exists to state, in plain terms, the disclaimers and the few use rules that the license alone doesn't cover.

If you only read two sections, make them **No warranty** and **Liability**. The rest is shorter than it looks.

## Who made this

HAT is built by one person, published under the handle **andromedvn**, and distributed at no charge. There is no company behind it. The developer never receives your data: the app declares no internet permission, so it cannot send anything anywhere. The full details are in the [Privacy Policy](https://andromedvn.github.io/HAT/PRIVACY.html).

## What you may do

Anything AGPL-3.0 permits. Install it on your own devices, track your own time, export your data, fork the code, build your own version. Two things sit outside the license:

- **Don't use HAT to monitor someone else.** Installing it on another person's device to track them without their knowledge and consent is prohibited by these terms, and it is a crime in most jurisdictions.
- **Don't disguise your build as the original.** You may distribute modified versions under the AGPL, but you may not present them as HAT or imply that the developer endorses them.

## How accurate it is

HAT reconstructs your day from events recorded by Android's `UsageStatsManager`. That data is good, but it is not a sworn witness:

- Manufacturers (Xiaomi, Samsung, Huawei, OPPO, Vivo, and others) aggressively delay or kill background work, which can leave gaps in the archive regardless of the schedule you configured.
- Android itself deletes usage events on a rolling basis, typically within weeks to months. Once the OS deletes them, nothing can bring them back.
- The timeline is a best-effort reconstruction, not an authoritative record. Do not rely on it for legal proceedings, medical assessments, workplace time tracking, insurance claims, or anything where a binding record matters.

## Where the official copies live

HAT is distributed through [GitHub Releases](https://github.com/andromedvn/HAT/releases) only. The source is public under the AGPL, so anyone may compile and redistribute the app, and the developer has no control over those copies. **The developer assumes zero liability for any binary that did not come from the official GitHub Releases page**: third-party APK sites, modified builds with added permissions or tracking SDKs, or anything using HAT's name. To check an official copy, compare its SHA-256 hash against the one published in its release notes.

## No warranty

**THE APPLICATION IS PROVIDED "AS IS" AND "AS AVAILABLE", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED**, including merchantability, fitness for a particular purpose, non-infringement, accuracy, completeness, and data preservation. Some jurisdictions do not allow some of these exclusions; in that case they apply to the fullest extent your local law permits.

## Liability

**TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, THE DEVELOPER IS NOT LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, OR CONSEQUENTIAL DAMAGES**, including loss of data, lost profits, or device malfunction, arising from your use of the Application. Because HAT is distributed at no charge, the developer receives no consideration from you, and the developer's maximum aggregate liability to you is **$0.00 (zero)** or the minimum amount required by mandatory applicable law in your jurisdiction, whichever is greater.

## Your data, your backups

Everything HAT stores lives on your device and dies with it. The Master Vault export exists precisely because the developer cannot recover a lost database for you. Back up before you uninstall, factory-reset, or switch devices. Deleting something in HAT is permanent; there is no cloud copy.

## Support and contributions

If you support development through the Ko-fi link in the app, that is a voluntary gift. It is not payment and grants no additional rights. It gates nothing: HAT is not trialware, not freemium, and every feature, present and future, is available to everyone whether they contribute or not. Contributions are processed entirely by Ko-fi under its own terms; the developer sees a payment notification and nothing else, and no contribution can be linked to your HAT installation, your data, or your device.

## Changes

HAT is under active development, and this document will change with it. The current version is always the one published in the repository. If you continue to use HAT after a change, the changed terms apply. You can stop at any time by uninstalling; the app is entirely offline and nothing of yours is kept anywhere else.

## Questions

Write to andromedvn@proton.me.

---

*These terms select no governing law and no venue. AGPL-3.0 governs the software. Your local mandatory consumer-protection and data-protection rights apply to you wherever you live, and nothing here tries to take them away.*
