# Dimmer

Darker than your brightness slider. Warm tints, schedule, no ads, no internet.

Dimmer makes your phone darker than its own brightness slider allows, for use in bed or in a dark room.

**Get it:** [Google Play](https://plainapps.dfdsfdsfsdf.workers.dev/apps/dimmer/) · [GitHub Releases](https://github.com/plainappsforever/dimmer/releases) ·
[Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/plainappsforever/dimmer)

**Status:** first store release pending.

## Our promise

**Free, no ads, no tracking, no accounts.** This app keeps your data on your phone. It has no analytics, no crash
reporter, no advertising SDK and no sign-in. We will not sell this app or this project to a company
that changes any of this.

Why we write it down: in 2023 a popular open-source Android suite was sold to an ad-funded app company and its store
listings started saying "Contains ads"; its community had to fork it to keep a clean version. The source code of
Dimmer is published under GPL-3.0, so if this promise were ever broken, anyone could continue the last
version that kept it.

## What it does

One slider, one scale. The first part lowers the real screen backlight to its minimum. The rest adds a dark layer on top, up to completely black. The layer also covers the status bar and the notification shade.

WHAT YOU GET
• Presets: Evening, Reading, Candlelight, Deep red and Ultra dim. Edit them or save your own (up to 8).
• Four tints (Neutral, Amber, Candle, Red) with adjustable strength.
• Schedule: dim at a clock time or at sunset, fade in slowly, end at a clock time or at sunrise. Sunset is worked out from your time zone, so Dimmer needs no location permission.
• Pause for 15, 30 or 60 minutes.
• Home-screen widget, two Quick Settings tiles, app shortcuts, and a quiet notification with Lighter, Darker and Pause.
• Optional extras, all off until you switch them on: adjust to room light (light sensor), keep the screen awake while dimming, let Tasker and similar apps control Dimmer.
• No ads, no account, no analytics and no internet permission. Everything stays on your phone.
• Available in 15 languages.

WHY DIMMER ASKS FOR ACCESSIBILITY ACCESS
Dimmer uses Android's Accessibility service for one job: to draw its dimming layer above all other screens, including the status bar and the notification shade. Dimmer does not read or record what is on your screen, does not see what you type or tap, and never taps for you. Android shows the same general warning for every accessibility service; Dimmer's service is set up so it cannot retrieve screen content. Before anything opens, the app shows a full explanation and asks for your consent. Dimmer is not an assistive tool for people with disabilities. It is a screen dimmer that needs this access to draw over system screens.

HOW TO TURN DIMMING OFF
• Tap the Dimmer tile in Quick Settings, or the widget.
• In the notification, tap Pause, then Off.
• Open the app and switch dimming off.
• Shake the phone twice (on by default; you can switch it off in Settings).
• Turn off "Use Dimmer" in Settings > Accessibility. The layer disappears at once.
• Restart the phone. Dimming is off after a restart unless you switched on "Resume after restart" for your schedule; then restart and shake the phone twice.
At the darkest setting the screen is completely black, with no text visible. If that happens, shake the phone twice, or restart the phone (if "Resume after restart" is on: restart, then shake).

GOOD TO KNOW
• Some phone makers' battery savers switch accessibility services off. Dimmer then shows a "Dimmer stopped" notice and the screen returns to normal.
• Brightness behaviour differs between phone models.
• Android's optional Advanced Protection mode blocks accessibility access for apps like Dimmer.
• Dimmer makes no medical claims and is not a medical product.

Questions or problems: write to the developer email shown on this page.

## Privacy

- All data is stored locally (SQLite in the app's private storage). Nothing is uploaded.
- Files leave the phone only when you share or export them yourself.
- Privacy policy: https://plainapps.dfdsfdsfsdf.workers.dev/apps/dimmer/privacy

## Build from source

A native Android app (Kotlin, Gradle). You need JDK 17 and the Android SDK (Android Studio installs
both; set `ANDROID_HOME`). Android only: there is no iOS version.

```sh
git clone https://github.com/plainappsforever/dimmer.git
cd dimmer
./gradlew test                 # unit tests
./gradlew assembleRelease      # APK in app/build/outputs/apk/release/
```

Builds made this way are signed with your key, not ours, so they cannot update an installed store version.

## Verifying a release

Every GitHub release lists SHA-256 checksums (`SHA256SUMS.txt`) and the APKs are signed with one key per app.
Certificate SHA-256 fingerprint: `not yet published on Android`

## How this app was made

Dimmer was written with substantial help from an AI coding assistant (Claude Code), directed, reviewed and
tested by the maintainer. We say this openly because some stores and users care about it.

## Licence

Copyright (C) 2026 Farija Retkoceri

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, version 3 (GPL-3.0-only). See [LICENSE](LICENSE).
Third-party components keep their own licences (see `LICENSES/` and the app's licences screen where present).
Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).
