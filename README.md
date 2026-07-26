# Glyph Matrix Toy Compat (GMTC)

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
**Menu mode** is a separate, opt-in alternative (a switch in the app's settings, **off by
default**) that turns the double press into an on-matrix picker instead:

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

## The app (interface)

A single Jetpack Compose screen, styled to look native to Nothing OS:

- **Nothing-styled theme** — strictly monochrome (black / white / grays, no accent colour), a
  `#F2F2FA` page background with pure-white cards, and the **NType82-Regular** headline serif
  used for the title. That font is not bundled: it's loaded at runtime from the device's
  `/system/fonts`, so the title matches the system Settings headline exactly (and nothing
  proprietary lands in the repo).
- **Glyph Toys list** — every toy as a card. **Drag the handle to reorder** the cycle (takes
  effect on the next key press); the **Play** button *sets* that toy as the currently active
  one; the toy currently on the matrix is highlighted with a dot; a switch enables/disables
  each toy; a gear opens per-toy settings.
- **Quick Settings tile** — a "Capture Essential Key" toggle to turn key capture on/off from
  the notification shade (works on the lock screen too).

## Setup

1. Install the APK and open the app.
2. Follow the in-app checklist:
   - **Enable the accessibility service** (this is what captures the Essential Key —
     including on the lock screen and before the first unlock after a reboot; it never
     reads screen content).
   - **Select "Glyph Matrix Toy Compat" as the Always-on Glyph Toy** — the checklist row
     deep-links straight to the picker (Settings → Glyph Interface → Flip to Glyph) so the
     system keeps the matrix rendering during AOD.
   - **Hand the Essential Key over to GMTC** — do **not** disable the Essential Space or
     Essential Recorder apps. Instead:
     1. Settings → Intelligence Toolkit → **Essential Key Settings** → enable
        *"Activate with single tap before use"*.
     2. Settings → Intelligence Toolkit → **Essential Voice** → disable
        *"Activate via Essential Key"*.

     This stops the system from acting on the key directly; if a pop-up still slips
     through on some firmware, GMTC dismisses it automatically.
   - Grant the optional permissions you want: microphone (music visualizer), location
     (solar path, compass declination), notifications + exact alarms (Tea Time).
3. Press the Essential Key twice to start cycling.

The accessibility service survives reboots automatically — no re-enabling needed.

## Building

Requirements: JDK 17 and an Android SDK with platform 35. Toolchain: AGP 8.9.3, Kotlin
2.1.21 (+ Compose compiler plugin), Gradle 8.11.1 wrapper, minSdk 33 / compileSdk 35.

```sh
./gradlew :app:assembleDebug          # debug build
./gradlew :app:assembleRelease        # release build (R8 shrink, ~3.5 MB)
./gradlew :app:testDebugUnitTest      # run the JVM test suite
./gradlew :app:lintDebug              # lint
```

Point the build at your SDK with `local.properties` (`sdk.dir=…`). The official Glyph
Matrix SDK is bundled at `app/libs/glyph-matrix-sdk-2.0.aar`.

The **release** build runs R8 (shrinking + resource stripping) — this trims the Compose
runtime down from ~26 MB to ~3.5 MB. R8 never removes logging (that only happens under an
`-assumenosideeffects` rule, which `app/proguard-rules.pro` deliberately forbids), and the
proguard file keeps the Glyph SDK, the frozen component names, and `DebugLog`.

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
└── ui/        Compose setup checklist + settings, theme/ (Nothing-styled monochrome)
```
