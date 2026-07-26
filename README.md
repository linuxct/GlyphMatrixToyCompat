<p align="center" width="100%">
  <img src="art/ic_launcher_512.png" alt="logo" width="192"><br/>
</p>

# Glyph Matrix Toy Compat (GMTC) [![Latest Version](https://img.shields.io/github/v/release/linuxct/GlyphMatrixToyCompat)](https://github.com/linuxct/GlyphMatrixToyCompat/releases/latest) ![Compatibility](https://img.shields.io/badge/compatible-Nothing%20Phone%204(a)%20Pro-black) ![Compatibility](https://img.shields.io/badge/compatible-Nothing%20Phone%203-white)

**Add support for Nothing Phone 3-like Glyph Toys to the Nothing Phone 4a Pro.**

> **Vibe-coded project notice**  
> This app was built entirely with AI assistance (Claude) from scratch — it is not a manually maintained codebase.

---

## What is this?

The Nothing Phone (4a) Pro has a 13×13 Glyph Matrix on its back, but no Glyph Button: the
official toy framework only supports a single always-on (AOD) toy there, and there is no
hardware way to interact with a toy or to switch between toys.

GMTC turns the **Essential Key** into that missing control. It ships a full catalogue of
Glyph Toys ("screens" internally) rendered through the official Glyph Matrix SDK, and lets
you drive all of them from the key — on the lock screen, on the Always-On Display, and
while the phone is unlocked:

| Essential Key | Action |
|---|---|
| Single press | Glyph Touch action on the current toy (roll the dice, start the tea timer, +1 the counter, …) |
| Double press | Switch to the next toy |
| Triple press | Jump back to the Ambient background toy |

### How the key works

- **Presses are grouped by timing.** Any presses within ~400 ms count as one gesture, so a
  *single* press's action fires ~400 ms after you release (the app waits to see if a second
  or third press follows). Each recognized press gives a short vibration.
- **Single press only does something on interactive toys** (Dice, Coin Flip, Counter,
  Breathing, Tea Time — the ✅ rows below). On passive toys it's a no-op; double/triple
  press still switch and jump home from any toy.
- **Capture is on/off.** While on (the master toggle / Quick Settings tile), each press is
  consumed so Essential Space never sees it. Turn it off and the key behaves completely
  normally again — no interception.

### Menu mode (optional)

By default the mapping above is "blind" — a double press jumps straight to the next toy.
**Menu mode** is a separate, opt-in alternative (chosen during onboarding or from the app's
settings, **off by default**) that turns the double press into an on-matrix picker instead:

- **Double press** opens the selector: the current toy is shown on the matrix and **blinks**.
- **Single press** cycles the blinking preview to the next toy (while the picker is open it
  does *not* fire the toy's Glyph Touch action).
- **Double press** again **sets** the previewed toy and closes the picker — it stops blinking.
- **Triple press** leaves the picker and jumps back to the Ambient background.
- **Wait ~5 seconds** and the previewed toy is set automatically; every press resets that timer.

Outside the picker, Menu mode changes nothing else: a **single press still triggers the Glyph
Touch action** on interactive toys, and a triple press still jumps home — only the double press
is repurposed (next toy → open picker). Leave the toggle off for the classic behaviour above.

Phone (3) is also supported (25×25 rendering paths exist for every screen, and its real
Glyph Button feeds the same action pipeline), but the 4a Pro is the primary target.

## Toys

| Toy | Interactive | Description |
|---|---|---|
| Ambient (background) | – | The home screen: a compositor with 10 selectable backgrounds (digital/analog clock, connection status, battery %, download speed, tilt ball, themed pixel clock, battery gauge, solar path, moon phase), a charging indicator layer (4 styles) and a music-reactive layer that takes over while audio plays. Night and shake-to-show gating included. |
| Pixel Clock | – | Stacked HH/MM pixel clock; themes add a battery bar or battery ring. |
| Eyes | – | A pair of eyes that wander and blink. |
| Download Speed | – | Live network download speed. |
| Battery | – | Battery gauge: the matrix fills to the charge level; charging adds a rising wave and a pulsing bolt. |
| Solar Path | – | The sun's position along its daily arc for your location (falls back to a 06:00/18:00 day without location access). |
| Moon Phase | – | The current lunar phase, rendered on a textured lunar surface (maria dim, highlands bright) with a soft terminator and faint earthshine on the dark side. |
| Dice | ✅ | D4/D6/D8/D12/D20 — press (or shake) to roll. |
| Coin Flip | ✅ | Press (or shake) to flip. |
| Counter | ✅ | Press to increment (wraps at 999), shake to reset. |
| Breathing | ✅ | Press to start/stop a guided-breathing pulse. |
| Tea Time | ✅ | Press to start a steep timer with a progress ring; chimes when ready, survives screen switches and process death. |
| Compass | – | Sensor-fused compass needle with cardinal ring. |
| Music Visualizer | – | FFT spectrum with log-spaced bands, three themes, adjustable response speed, and an always-on noise floor while audio plays. |

Every toy can be toggled, reordered and configured from the app.

## First run — onboarding

On first launch the app opens a paged onboarding flow instead of the main screen. Each page
is headed by an animated replica of the Glyph Matrix itself — a circular disc of 489 LEDs
(a 25×25 grid under a circular mask) whose dots light up in pseudo-random order and shimmer
gently, drawing pixel art for the page (a key, the glyph ring, a padlock, a toggle, a smiley).

The pages, in order — every step is skippable with **Next** and everything can be revisited
later from the main screen:

1. **Take over the Essential Key** — explains what the accessibility service does (and
   explicitly what it does *not* do), with a live status line, a button into Accessibility
   settings, and a dedicated card for sideloaded installs: Android's "Restricted setting"
   block and the App info → ⋮ → *Allow restricted settings* dance, with a direct App info
   button.
2. **Put GMTC on the matrix** — explains the always-on Glyph Toy concept and deep-links to
   the system toy picker (the same deeplink the main screen uses).
3. **Permissions** — all optional runtime permissions in one card (notifications, microphone,
   location, exact alarms), each with a plain-language explanation of the single feature it
   powers. States refresh live as you grant them.
4. **Key mode** — *only appears if the listener was actually enabled*: choose between
   Regular mode and Menu mode (two selectable cards explaining the behaviour difference),
   with a **"How do they work?"** button that opens the same animated Essential Key tutorial
   as the main screen.
5. **Welcome** — a status recap of everything you set up, then into the app.

The flow re-probes system state every time you return from Settings, so the status lines
(and the conditional mode page) update live. Completing it sets a preference; MainActivity
redirects to onboarding until that happens.

## The app (interface)

A Jetpack Compose app styled to look native to Nothing OS, organized into three tabs behind
a floating pill navigation bar:

- **Glyph Toys** — every toy as a card. **Drag the handle to reorder** the cycle (takes
  effect on the next key press); the **Play** button *sets* that toy as the currently active
  one; the toy currently on the matrix is highlighted with a dot; a switch enables/disables
  each toy; a gear opens per-toy settings.
- **Settings** — the **Initial setup** checklist (accessibility service, always-on toy
  selection — verified via the system's actual toy binding — notifications, microphone,
  location, exact alarms; each row deep-links to the right place) followed by **App
  settings**: key capture master toggle, Menu mode, 12-hour clock, Glyph brightness, and
  the update checker (see below).
- **How it works** — short guides for the trickier parts:
  - **Essential Key tutorial** — an animated, fully Compose-drawn walkthrough (no image
    assets): a phone lying face-down with its camera island, Glyph Matrix and Essential Key,
    looping small timelines of what single, double and triple presses do — in both Regular
    and Menu mode, with the real blink cadence and the 5 s auto-set countdown.
  - **Hand over the Essential Key** — the system-settings steps to stop Nothing OS acting
    on the key (see Setup below).
  - **Restricted settings** — the sideload unlock steps, with a button straight into App info.

Other UI notes:

- **Nothing-styled theme** — strictly monochrome (black / white / grays, no accent colour), a
  `#F2F2FA` page background with pure-white cards, and the **NType82-Regular** headline serif
  used for the title. That font is not bundled: it's loaded at runtime from the device's
  `/system/fonts`, so the title matches the system Settings headline exactly (and nothing
  proprietary lands in the repo).
- **Quick Settings tile** — a "Capture Essential Key" toggle to turn key capture on/off from
  the notification shade (works on the lock screen too).

## Updates

The app checks GitHub Releases of this repository for new versions — its only network
activity (the sole reason for the `INTERNET` permission):

- **Once a day** in the background (WorkManager, network-constrained, survives reboots).
  A newer release posts a notification — once per version — that opens the release page.
- **On demand** from Settings → "Check for updates", which shows the installed version and
  turns into a download link when an update is found.

The check is a single unauthenticated GET to the GitHub API; nothing is sent beyond the
request itself.

## Setup

> **Supported devices:** Nothing Phone (3) and Phone (4a) Pro only. The manifest
> requires Nothing's custom `com.nothing.feature` system feature so stores filter
> the app from other devices, and — since sideloads ignore `uses-feature`, and
> Nothing OS declares no shared library that could hard-block installation — the
> app additionally refuses to run on hardware without a Glyph Matrix.

1. Install the APK and open the app — **onboarding walks you through everything below**.
2. What it sets up (all revisitable from the Settings tab checklist):
   - **Enable the accessibility service** (this is what captures the Essential Key —
     including on the lock screen and before the first unlock after a reboot; it never
     reads screen content). Sideloaded installs may need *Allow restricted settings*
     first — both onboarding and the How it works tab walk through it.
   - **Select "Glyph Matrix Toy Compat" as the Always-on Glyph Toy** — deep-linked straight
     to the picker (Settings → Glyph Interface → Flip to Glyph) so the system keeps the
     matrix rendering during AOD. The checklist verifies the selection by the system's
     actual toy binding.
   - **Pick a key mode** — Regular or Menu mode (only offered once the listener is on).
   - Grant the optional permissions you want: microphone (music visualizer), location
     (solar path, compass declination), notifications + exact alarms (Tea Time).
3. **Hand the Essential Key over to GMTC** (manual system steps — also available as a guide
   in the How it works tab). Do **not** disable the Essential Space or Essential Recorder
   apps. Instead:
   1. Settings → Intelligence Toolkit → **Essential Key Settings** → enable
      *"Activate with single tap before use"*.
   2. Settings → Intelligence Toolkit → **Essential Voice** → disable
      *"Activate via Essential Key"*.

   This stops the system from acting on the key directly; if a pop-up still slips
   through on some firmware, GMTC dismisses it automatically.
4. Press the Essential Key twice to start cycling.

The accessibility service survives reboots automatically — no re-enabling needed.

## Building

Requirements: JDK 17 and an Android SDK with platform 37. Toolchain: AGP 9.3.0, Kotlin
2.2.10 (+ Compose compiler plugin), Gradle 9.5.0 wrapper, minSdk 33 / target & compileSdk 37.

```sh
./gradlew :app:assembleDebug          # debug build
./gradlew :app:assembleRelease        # release build (R8 shrink)
./gradlew :app:testDebugUnitTest      # run the JVM test suite
./gradlew :app:lintDebug              # lint
```

Point the build at your SDK with `local.properties` (`sdk.dir=…`). The official Glyph
Matrix SDK is bundled at `app/libs/glyph-matrix-sdk-2.0.aar`.

The **release** build runs R8 (shrinking + resource stripping) — this trims the Compose
runtime down by an order of magnitude. R8 never removes logging (that only happens under an
`-assumenosideeffects` rule, which `app/proguard-rules.pro` deliberately forbids), and the
proguard file keeps the Glyph SDK, the frozen component names, and `DebugLog`.

### CI / releases

Two GitHub Actions workflows live in `.github/workflows/`:

- **CI** (`ci.yaml`) — builds a debug APK on every push/PR and uploads it as an artifact.
- **Release** (`release.yaml`) — manual dispatch: decodes the signing keystore from repo
  secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), builds a
  signed release APK, and publishes a GitHub release tagged `v<versionName>` with
  commit-derived release notes. Signing config is driven by a repo-root
  `keystore.properties`; without it, local release builds are simply unsigned.

### Tests and ASCII goldens

All rendering is pure Kotlin behind data ports, so every toy is unit-tested on the JVM
at both 13×13 and 25×25 against **ASCII golden files** (`app/src/test/resources/goldens/`)
— human-reviewable snapshots of actual frames. Regenerate them after intentional visual
changes with:

```sh
./gradlew :app:testDebugUnitTest -DupdateGoldens=true
```

## Debugging

The whole pipeline (key capture, click routing, screen switching, Glyph service binding)
logs under a single tag, in release builds too:

```sh
adb logcat -s GlyphToyCompat
```

Unrecognized hardware keys are logged with their scan code — the Essential Key's scan
code varies between firmware revisions (250 and 304 seen so far), so if your unit uses a
new one, the log will show it and it can be added to `KNOWN_SCAN_CODES`.

Replay the onboarding at any time (resets the completed flag and routes through the real
first-launch path):

```sh
adb shell am start -S -n space.linuxct.glyphmatrixtoycompat/.ui.MainActivity --ez restart_onboarding true
```

### Essential Key coexistence

The accessibility service watches window events from the Essential Space / Essential
Recorder packages: on firmware where the system reacts to the key before the key filter can
consume it, GMTC dismisses the resulting pop-up automatically (BACK when unlocked, HOME when
locked). The clean solution is still the system-side hand-off in the Setup steps above —
keep those apps enabled.

## Project layout

```
app/src/main/kotlin/space/linuxct/glyphmatrixtoycompat/
├── core/      GlyphLink (SDK binding + self-healing), ScreenManager, SessionArbiter,
│              scheduler, prefs (device-protected storage), ports
├── matrix/    Pure-Kotlin drawing primitives + 3×5 dot font
├── screens/   All toys (+ ambient/ compositor with its backgrounds)
├── key/       Essential Key accessibility service, click counting, action routing,
│              Quick Settings tile
├── toy/       System Glyph Toy service, Tea Time alarm backstop
├── audio/     Shared FFT engine
├── sensors/   Shake / tilt / compass
├── update/    GitHub Releases update checker + daily WorkManager job
└── ui/        Compose UI: tabbed main screen, first-run onboarding (animated glyph-disc
               pages), animated Essential Key tutorial, setup guides,
               theme/ (Nothing-styled monochrome, runtime NType82)
```
