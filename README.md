<p align="center" width="100%">
  <img src="art/ic_launcher_512.png" alt="logo" width="192"><br/>
</p>

# Glyph Matrix Toy Compat (GMTC) <br/> [![Latest Version](https://img.shields.io/github/v/release/linuxct/GlyphMatrixToyCompat)](https://github.com/linuxct/GlyphMatrixToyCompat/releases/latest) ![Compatibility](https://img.shields.io/badge/compatible-Nothing%20Phone%204(a)%20Pro-black) ![Compatibility](https://img.shields.io/badge/compatible-Nothing%20Phone%203-white)

**Add support for Nothing Phone 3-like Glyph Toy actions to the Nothing Phone 4a Pro.**

> **Vibe-coded project notice**  
> This app was built entirely with AI assistance (Claude) from scratch — it is not a manually maintained codebase.

---

## What is this?

The Nothing Phone (4a) Pro has a 13×13 Glyph Matrix on its back, but no Glyph Button: the
official toy framework only supports a single always-on (AOD) toy there, and there is no
hardware way to interact with a toy or to switch between toys.

GMTC turns the **Essential Key** into that missing control. It ships a full catalogue of
Glyph Toys ("screens" internally) rendered through the official Glyph Matrix SDK — plus a
pixel editor for [drawing your own](#create--your-own-designs) — and lets you drive all of
them from the key: on the lock screen, on the Always-On Display, and while the phone is
unlocked:

| Essential Key | Action |
|---|---|
| Single press | Glyph Touch action on the current toy (roll the dice, start the timer, +1 the counter, …) |
| Double press | Switch to the next toy |
| Triple press | Jump back to the Ambient background toy |

### How the key works

- **Presses are grouped by timing.** Any presses within ~400 ms count as one gesture, so a
  *single* press's action fires ~400 ms after you release (the app waits to see if a second
  or third press follows). Each recognized press gives a short vibration.
- **Single press only does something on interactive toys** (Dice, Coin Flip, Dino Run,
  Spin the Bottle, Rock Paper Scissors, Counter, Breathing, Timer — the ✅ rows below).
  On passive toys it's a no-op; double/triple
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
| Eyes | – | A pair of eyes that wander and blink, drawn with a bright rim so the eye shape reads even with the pupil centred. |
| Download Speed | – | Live network download speed. |
| Battery | – | Battery gauge: the matrix fills to the charge level; charging adds a rising wave and a pulsing bolt. Optionally shows the charging wattage instead while plugged in. |
| Solar Path | – | The sun's position along its daily arc for your location (falls back to a 06:00/18:00 day without location access). |
| Moon Phase | – | The current lunar phase, rendered on a textured lunar surface (maria dim, highlands bright) with a soft terminator and faint earthshine on the dark side. |
| Dice | ✅ | D4/D6/D8/D12/D20 — press (or shake) to roll. |
| Coin Flip | ✅ | Press (or shake) to flip. Two designs: H/T letters, or a monarch's profile and a euro-style numeral "1". |
| Dino Run | ✅ | An endless runner. Press to start, press to jump, and the score is revealed blinking when you clip a cactus — one more press starts again. |
| Spin the Bottle | ✅ | Press to spin an outlined bottle about the matrix centre: ~3 s, four to five turns, a ratcheted stop at a random angle, then a pulsing diamond burst. |
| Rock Paper Scissors | ✅ | Press and the matrix throws: a "SET" banner, a shaking 3-2-1 countdown, then a fist, a flat hand or scissors — held until the next press. No opponent, no score; you throw with your real hand. |
| Counter | ✅ | Press to increment (wraps at 999), shake to reset. |
| Breathing | ✅ | Press to start/stop a guided-breathing pulse. |
| Timer | ✅ | Press to start the countdown; the matrix becomes an hourglass with no hourglass in it — grains fall from the top and the settled sand rises until every LED is lit at zero. Chimes when it runs out, survives screen switches and process death. |
| Compass | – | Sensor-fused compass needle with cardinal ring. |
| Level | – | Spirit level: a bubble that centres inside a target ring when the device lies flat and rolls toward the low edge as it tilts. The ring lights up within a few degrees of level. |
| Music Visualizer | – | FFT spectrum with log-spaced bands, three themes, adjustable response speed, and an always-on noise floor while audio plays. |
| Custom Design | ✅ | Plays a design **you** drew — see [Create](#create--your-own-designs). Its cog picks which one; a press plays, pauses or replays it depending on the design's key mode. |

Every toy can be toggled, reordered and configured from the app.

Brightness is **multiplicative for every toy**: the Glyph brightness setting scales each
frame rather than normalising it, so a 50 % grey stays a fixed fraction of white at every
level instead of being rescaled up to full white. Grey is a shade you can actually draw with.

## Create — your own designs

Every toy above is one this app draws. The **Create** tab hands that over: a pixel editor for
the Glyph Matrix, and the `Custom Design` toy that plays whichever design you point it at.

Start one with the **+** button beside the navigation pill and it opens in a full-screen
editor — the same face-down phone illustration the tutorials use, zoomed on the matrix. Pick
a shade (off, 50 % grey, white) and **drag across the disc to paint**; pinch to zoom in on
the small cells, undo/redo a stroke at a time, clear or fill the frame. A **live preview**
mirrors what you are drawing onto the real matrix as you draw it, with hard precedence over
the selected toy and over the charging and music-reactive overlays. Nothing is saved by hand:
every completed gesture schedules a write, and every way out of the editor flushes it first.

**Static or dynamic is chosen when the design is created, and cannot be changed afterwards.**
A static design is a single still frame. A dynamic one gets a timeline along the bottom —
add, duplicate, delete and drag frames into a new order, each with its own duration — plus
onion skin, the previous frame ghosted underneath the one you are drawing.

### Putting a design on the matrix

One tap, from either end. The **phone icon in the editor's top bar** — right where you have
just finished drawing — and **Show on Glyph Matrix** at the top of a design's ⋮ menu both do
the whole job: point the `Custom Design` toy at that design, make it the toy on the matrix,
and say so. No hunting through the toy list for a cog.

It is honest about what it cannot do. A design with no artwork for *this* phone's panel is
declined with the size you still need to draw, rather than selected into a placeholder
question mark; and if key capture is off, it says the design is set and what to switch on to
see it. From the editor the confirmation says it starts playing when you leave, because the
live preview owns the matrix until then.

The `Custom Design` toy's cog in the Toys tab still lists every design as a radio group, for
switching between them without opening one.

### What the Essential Key does

A dynamic design carries a **key mode**, which is what a single press does while that design
is the toy on the matrix:

| Key mode | At rest | A press |
|---|---|---|
| **Play once** | Holds frame 1 | Plays the animation through once and returns to frame 1 — the return is drawn, not just internal. A press mid-run starts it over rather than being ignored. |
| **Play / pause** | Starts playing on its own | Pauses where it is; the next press carries on from there (or restarts, if it was sitting on the last frame). |

**Repeat** belongs to play / pause: on, the animation loops until you pause it; off, it runs
to the end and holds the last frame, so the design ends on the image you ended it on. Play
once always returns to frame 1, so repeat does not apply to it — and the control is only
*shown* for play / pause, because a toggle that persists and changes nothing is worse than an
absent one. Switching key mode does not clear the setting, so switching back restores it.

Play / pause starts by itself deliberately — a design that sat motionless on the always-on
display, where nobody is pressing anything, would look broken.

### Two sizes in one file

The Phone (4a) Pro's matrix is 13×13 and the Phone (3)'s is 25×25, so **a design can carry a
separate drawing for each**. Nothing is scaled between the two: a 13×13 drawing blown up to
25×25 is a blocky approximation of somebody's art, not a translation of it, so a second size
starts as a blank canvas and stays yours to draw. Editing one never touches the other.

**Which sizes a design is for is chosen when you create it** — this phone, the other one, or
both — and it defaults to the phone in your hand, so owning one device means not answering
the question. The editor shows the size switcher only for a design that actually has more
than one drawing, and gives the row back to the canvas otherwise. It is not a trapdoor:
**Add Nothing Phone (3) artwork** (naming whichever size is missing) lives in the editor's
**Design settings**, and the switcher appears as soon as you use it. There is no matching
"remove" — adding a size creates an empty canvas, while removing one would delete frames
somebody drew.

A design with only one size filled in is a normal thing to have: it plays on that phone and
shows a placeholder on the other, and an imported design that carries both gets the switcher
with nothing special done to it — the sizes present in the file are the only thing anything
reads.

Both drawings live in the same file, keyed by the device's Pokémon codename (`bellsprout` for
the Phone (4a) Pro, `arbok` for the Phone (3)) rather than by pixel count, so a design you
post works on either phone and a codename this build has never heard of is ignored instead of
breaking the file.

### Sharing designs

A design is a single JSON file, and the export format **is** the storage format — the bytes
in the app's own storage are the bytes you post. From a design's ⋮ menu:

- **Export to a file** — the system file picker, saved wherever you like.
- **Share** — the standard share sheet, straight into a chat, a mail or an issue.

and from the top of the Create tab, **Import a design** picks a `.json` file and adds it as a
new design. Imported files are treated as hostile input: size-capped before parsing, checked
field by field, and refused with a specific sentence rather than a crash or a silent no-op.
An import always becomes a *new* design — the id is reassigned unconditionally — so a file
can never overwrite artwork you already had, and the original author's name is never
overwritten with yours when you touch a design up.

The format is documented in full — every field, every validation limit and the exact message
each violation produces, a worked example, and a "write your own exporter" section — in
**[`docs/glyph-design-format.md`](docs/glyph-design-format.md)**.

## First run — onboarding

On first launch the app opens a paged onboarding flow instead of the main screen. Each page
is headed by an animated replica of the Glyph Matrix itself — a circular disc of 489 LEDs
(a 25×25 grid under a circular mask) whose dots light up in pseudo-random order and shimmer
gently, drawing pixel art for the page (a key, the glyph ring, a padlock, a toggle, a pencil,
a smiley).

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
5. **Ready-made toys, or your own** — what to actually put on the matrix: the set of toys
   that ships with the app, the Create tab where you draw your own (still or animated), and
   the Tutorials tab where the guides for both live. It deliberately does *not* explain how
   the editor works — that is the guided demo's job — and it carries a **Take me to Create**
   button that ends onboarding and opens the app straight on that tab.
6. **Welcome** — a status recap of everything you set up, then into the app.

The flow re-probes system state every time you return from Settings, so the status lines
(and the conditional mode page) update live. Completing it sets a preference; MainActivity
redirects to onboarding until that happens.

## The app (interface)

A Jetpack Compose app styled to look native to Nothing OS, organized into four swipeable
tabs behind a floating pill navigation bar:

- **Glyph Toys** — every toy as a card. **Drag the handle to reorder** the cycle (takes
  effect on the next key press); the **Play** button *sets* that toy as the currently active
  one; the toy currently on the matrix is highlighted with a dot; a switch enables/disables
  each toy; a gear opens per-toy settings.
- **Create** — your own designs as cards (name, author, static/dynamic, frame count, which
  sizes are drawn, last edited), with show on matrix / duplicate / delete / export / share
  behind each ⋮ and an import action at the top. The **first time you land on this tab** it
  offers the guided demo below — once, ever, whichever way you answer. See
  [Create](#create--your-own-designs).
- **Settings** — the **Initial setup** checklist (accessibility service, always-on toy
  selection — verified via the system's actual toy binding — notifications, microphone,
  location, exact alarms; each row deep-links to the right place) followed by **App
  settings**: key capture master toggle, Menu mode, 12-hour clock, Glyph brightness, your
  creator name (stamped on designs you make), and the update checker (see below).
- **Tutorials** — short guides for the trickier parts:
  - **Essential Key tutorial** — an animated, fully Compose-drawn walkthrough (no image
    assets): a phone lying face-down with its camera island, Glyph Matrix and Essential Key,
    looping small timelines of what single, double and triple presses do — in both Regular
    and Menu mode, with the real blink cadence and the 5 s auto-set countdown.
  - **Create your own design** — a **guided demo**, not a page of text: it opens the real
    Create tab and the real editor over a throwaway in-memory design and plays each gesture
    itself — the `+` beside the nav pill, the new-design questions, a stroke being painted,
    undo, duplicate-and-nudge, adding a frame, holding one to drag it somewhere else,
    per-frame duration, and then **Design settings opened for real** so the two easily-missed
    controls in there get demonstrated rather than described: the key mode, and **repeat**
    (switched off and back on, with the sentence beside it changing) — with a spotlight and a
    one-line caption on whatever is moving. You step through it with Next / Back and can skip
    at any point. Nothing it does is saved, and it never touches the Glyph Matrix.
  - **Hand over the Essential Key** — the system-settings steps to stop Nothing OS acting
    on the key (see Setup below).
  - **Restricted settings** — the sideload unlock steps, with a button straight into App info.

Other UI notes:

- **Nothing-styled theme** — strictly monochrome (black / white / grays, no accent colour),
  sampled from Nothing OS Settings in both modes: a `#F2F2FA` page with pure-white cards in
  light mode, a pure-black page with `#191C20` cards (and near-black divider hairlines) in
  dark mode. The **NType82-Regular** headline serif is used for the title; that font is not
  bundled but loaded at runtime from the device's `/system/fonts`, so the title matches the
  system Settings headline exactly (and nothing proprietary lands in the repo).
- **Floating pill navigation** — an MD3-style capsule with an icon and a caption per tab
  (Toys / Create / Settings / Tutorial), its own theme colours so it stays a mid-grey pill in
  dark mode instead of a glaring near-white slab. On the Create tab a circular **+** button
  fades in beside the pill — a sibling of it, not a top-bar action — and the pill slides over
  to make room.
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
     first — both onboarding and the Tutorials tab walk through it.
   - **Select "Glyph Matrix Toy Compat" as the Always-on Glyph Toy** — deep-linked straight
     to the picker (Settings → Glyph Interface → Flip to Glyph) so the system keeps the
     matrix rendering during AOD. The checklist verifies the selection by the system's
     actual toy binding.
   - **Pick a key mode** — Regular or Menu mode (only offered once the listener is on).
   - Grant the optional permissions you want: microphone (music visualizer), location
     (solar path, compass declination), notifications + exact alarms (Timer).
3. **Hand the Essential Key over to GMTC** (manual system steps — also available as a guide
   in the Tutorials tab). Do **not** disable the Essential Space or Essential Recorder
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
│   └ design/  The glyph.design format: model, codec + validation, cell encoding
│              (pure Kotlin, JVM-tested — see docs/glyph-design-format.md)
├── designs/   Design file store (device-protected, atomic writes) + its port impl
├── matrix/    Pure-Kotlin drawing primitives + 3×5 dot font
├── screens/   All toys (+ ambient/ compositor with its backgrounds)
├── key/       Essential Key accessibility service, click counting, action routing,
│              Quick Settings tile
├── toy/       System Glyph Toy service, Timer alarm backstop
├── audio/     Shared FFT engine
├── sensors/   Shake / tilt / incline / compass / light
├── update/    GitHub Releases update checker + daily WorkManager job
└── ui/        Compose UI: tabbed main screen, first-run onboarding (animated glyph-disc
               pages), animated Essential Key tutorial, setup guides, design list +
               import/export, design/ (the pixel editor, canvas and timeline),
               theme/ (Nothing-styled monochrome, runtime NType82)
```
