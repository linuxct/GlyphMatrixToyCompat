# Privacy Policy — GlyphWorks for Nothing

Applies to **GlyphWorks for Nothing** (`space.linuxct.glyphworks`), version 3.0.0 and later, as
distributed on Google Play. Last updated: 2026-08-08.

This document describes the Google Play build only. The build published on GitHub contains two
additional features — an AI design assistant and an update checker — that do use the network; if
you installed the app from anywhere other than Google Play, the section [Other
builds](#other-builds) applies to you instead.

## No data collected, no data shared

**This app collects no data and shares no data.** That is the same answer given on the Play Data
Safety form, and it is meant literally: nothing about you or your device leaves your phone,
because the app has no means to send it.

There are no accounts, no analytics, no crash reporting, no advertising, and no third-party SDK
that contacts a server.

## Why that is checkable rather than a promise

The Play build does not request `android.permission.INTERNET`. Android grants network access only
to packages that hold it, so every socket this process could try to open is refused by the
platform. A privacy claim of this shape can be verified from the outside, on the exact file you
installed:

```
aapt2 dump permissions base.apk
```

`android.permission.INTERNET` does not appear in the output. The app is also open source
(AGPL-3.0), so the code behind every statement below can be read.

## What the app touches, and where it stays

### Microphone

The Music Visualizer and the music-reactive ambient layer read the **output mix** through
Android's `android.media.audiofx.Visualizer`, at a capture size of 256 samples, and take an FFT of
it. What comes back is a set of frequency magnitudes, which become the heights of the bars on the
Glyph Matrix.

No audio is recorded. Nothing is written to a file, kept in memory beyond the current frame, or
transmitted — the app could not transmit it if it wanted to. The capture engine releases itself
after five seconds without a poll, so nothing is being captured once a toy that needs audio stops
being drawn. Deny the permission and the visualizer simply shows its idle pattern.

**Why this asks for the microphone at all, and why your phone may say it is in use.** Two
permissions are involved and neither is a choice the app makes. Android requires
`RECORD_AUDIO` for any use of `Visualizer`, whatever it is attached to, and requires
`MODIFY_AUDIO_SETTINGS` to attach to the output mix specifically — the app changes no audio
setting with it. Android also accounts for this under the microphone app-op, so the privacy
indicator can light up and background restrictions apply while the visualizer runs.

What is actually being read is the mix your phone is already playing, not the room. The app
contains no `AudioRecord` and no `MediaRecorder` — the only audio APIs in it are
`android.media.audiofx.Visualizer` and `android.media.RingtoneManager` for the Timer chime —
so recording the microphone is not something it declines to do, it is something it has no code
to do. That is checkable in the source linked at the bottom of this page.

### Location

Two toys ask about your position: the **Compass**, to correct magnetic north to true north, and
the **Solar Path**, to place the sun on its daily arc where you actually are.

Only `ACCESS_COARSE_LOCATION` is requested. The app never requests a fix and never starts location
updates: it reads `LocationManager.getLastKnownLocation` — a position some other app already
asked for — and caches the result for ten minutes so that a toy redrawn many times a second does
not re-query it. The coordinates are handed to `android.hardware.GeomagneticField` for the
declination angle, and to the on-device solar maths for sunrise and sunset. They are never
stored to disk and never leave the device. Deny the permission and the Compass points at magnetic
north while the Solar Path falls back to a nominal 06:00/18:00 day.

### The Essential Key, and the accessibility service

The Glyph Matrix toys are driven by the Essential Key, and an accessibility service is the only
mechanism by which an ordinary app can observe a hardware key — including while the phone is
locked or on the Always-On Display. The service exists for that and nothing else.

It is declared `android:canRetrieveWindowContent="false"`, which means the platform does not give
it your screen contents: it cannot read what is on screen, in this app or any other. It receives
key events, and one single event type — `typeWindowStateChanged` — scoped to two packages
(`com.nothing.ntessentialspace`, `com.nothing.ntessentialrecorder`), used only to close Essential
Space when the system reacted to a press the app had already consumed. It is declared
`android:isAccessibilityTool="false"`, because it is not an assistive tool and does not pretend to
be one.

Key presses are counted in memory and turned into a toy action. They are not logged, not
persisted, and not transmitted.

### Designs you draw

Designs made in the Create tab are `glyph.design` JSON files under the app's own
device-protected storage. They hold your artwork, a name, and — if you set a creator name in
Settings — that name. All of it is text you typed.

The app never uploads a design. Sharing is an act you perform: the ⋮ menu hands **one** file to
Android's share sheet through a `FileProvider` that is not exported, and whichever app you choose
receives it. Where it goes after that is between you and that app. Copies staged for sharing live
in the cache and are cleaned up after a day.

### Settings

Preferences (which toys are enabled, their order, brightness, creator name) are stored in
device-protected `SharedPreferences` on this phone. Uninstalling removes them.

### Notifications and alarms

The Timer schedules an exact alarm as a backstop so the chime lands even if the app's process has
been killed, and posts a notification when it fires. Nothing about a timer leaves the device.

## Permissions in the Play build

| Permission | Why |
|---|---|
| `com.nothing.ketchum.permission.ENABLE` | Draw on the Glyph Matrix through Nothing's Glyph SDK. |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Observe Essential Key presses. Cannot read screen content. |
| `RECORD_AUDIO` | On-device FFT for the visualizer. Optional. |
| `MODIFY_AUDIO_SETTINGS` | Required alongside it: the visualizer attaches to the output mix, and Android gates that on this permission. It changes no audio setting. |
| `ACCESS_COARSE_LOCATION` | Compass declination and solar path. Optional. |
| `POST_NOTIFICATIONS` | The Timer chime notification. Optional. |
| `SCHEDULE_EXACT_ALARM` | The Timer's backstop alarm. Optional. |
| `VIBRATE` | Haptic feedback on each recognised key press. |
| `ACCESS_NETWORK_STATE` | Reads the connection *type* (Wi-Fi / cellular / none) for the connection-status background. It grants no network access. |

There is no `INTERNET` permission, no foreground service, and no background network work of any
kind in this build. The authoritative list for the version you installed is whatever `aapt2 dump
permissions` prints for it.

## Children

The app has no accounts, no user-to-user features, no ads and no data collection, so there is
nothing here that is collected from anyone of any age.

## Other builds

The GitHub release of this app is a different package configuration: it holds `INTERNET` and adds
an optional AI design assistant (which sends the prompts you type, and the design being edited, to
OpenAI under your own account, over HTTPS) and an update checker (an unauthenticated GET to the
GitHub Releases API, once a day). Neither exists in the Google Play build — the code is excluded
from it, not merely switched off. The privacy policy for those features ships with that build.

## Changes

Material changes to this policy will be published here, with the date at the top updated. The
history of this file is public in the repository below.

## Contact and source

- Email: **glyphworks@linuxct.space**
- Source code, AGPL-3.0: **https://github.com/linuxct/glyphworks**

GlyphWorks is an independent project. It is not affiliated with, endorsed by, or connected to
Nothing Technology Limited.
