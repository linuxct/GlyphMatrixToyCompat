# The `glyph.design` format

A single JSON file describing a still image or an animation for a Nothing Glyph Matrix, in
enough detail that another program can produce one and this app will play it.

This is deliberately an **interchange format**, not an app-private one: it is what Glyph
Matrix Toy Compat (GlyphWorks) writes to its own storage, what it exports, and what it accepts on
import — the same bytes in all three cases. There is no second internal representation that
could drift from this document.

This specification describes **format version 1**, as implemented by GlyphWorks 2.0.0. Everything
below is a statement about the code in
`app/src/main/kotlin/space/linuxct/glyphworks/core/design/`
(`Design.kt`, `DesignCodec.kt`, `DesignFrames.kt`); where this document and that code
disagree, the code is right.

## Contents

1. [At a glance](#1-at-a-glance)
2. [The envelope](#2-the-envelope)
3. [`variants` — keyed by Pokémon codename](#3-variants--keyed-by-pokémon-codename)
4. [`levels` — the palette](#4-levels--the-palette)
5. [`cells` — the pixel encoding](#5-cells--the-pixel-encoding)
6. [Timestamps](#6-timestamps)
7. [Validation — every rule a file must satisfy](#7-validation--every-rule-a-file-must-satisfy)
8. [A complete example](#8-a-complete-example)
9. [Writing your own exporter](#9-writing-your-own-exporter)
10. [Forward compatibility](#10-forward-compatibility)

---

## 1. At a glance

```json
{
  "format": "glyph.design",
  "formatVersion": 1,
  "id": "9f2c4a1e6b7d40f8a1c3e5d7b9f0a2c4",
  "name": "Slow Ember",
  "author": "linuxct",
  "createdAt": "2026-07-30T12:00:00Z",
  "modifiedAt": "2026-07-30T12:34:56Z",
  "createdWith": "GlyphWorks 2.0.0",
  "kind": "dynamic",
  "keyMode": "playPause",
  "loop": true,
  "levels": [0, 2048, 4095],
  "variants": {
    "bellsprout": { "frames": [ { "durationMs": 120, "cells": "0012…" } ] },
    "arbok":      { "frames": [] }
  }
}
```

The whole file is one JSON object. UTF-8, no BOM required, no comments (it is strict JSON).
The MIME type used for export and sharing is `application/json`, and the conventional
extension is `.json` — the format is meant to be posted in gists and read by people, so it
gets JSON's own type rather than a private one.

## 2. The envelope

Every field of the top-level object, in the order GlyphWorks writes them.

| Field | Type | Required | Meaning |
|---|---|---|---|
| `format` | string | **yes** | Magic string. Must be exactly `glyph.design`. A file without it is not a design file, whatever else it contains. |
| `formatVersion` | integer | no (default `1`) | The version of *this* specification the file is written to. |
| `id` | string | **yes** | Stable identity of the design. Becomes a filename, so it is restricted to `[A-Za-z0-9_-]`, 1–64 characters. GlyphWorks generates a 32-character lowercase hex UUID. |
| `name` | string | no (default `""`) | Human-readable title, ≤ 64 characters. Arbitrary Unicode; never used as a path. |
| `author` | string | no (default `""`) | Who made it, ≤ 64 characters. Set once, when the design is created, and never rewritten — GlyphWorks pins it back to the stored value on every save, so touching up somebody else's imported design does not put your name on their work. |
| `createdAt` | string | **yes** | ISO-8601 UTC instant. See [§6](#6-timestamps). |
| `modifiedAt` | string | **yes** | ISO-8601 UTC instant, restamped on every save. |
| `createdWith` | string | no (default `""`) | Free diagnostic text naming the producing program, ≤ 64 characters, e.g. `GlyphWorks 2.0.0`. Purely informational; put something useful in it, because it is what tells a maintainer which tool produced a file that misbehaves. |
| `kind` | `"static"` \| `"dynamic"` | no (default `"static"`) | The author's declaration of what this is. A `static` design plays **only its first frame**, even if more are stored. |
| `keyMode` | `"playOnce"` \| `"playPause"` | no (default `"playPause"`) | What one press of the Essential Key does. See below. |
| `loop` | boolean | no (default `false`) | Whether a `playPause` animation repeats. See below. |
| `levels` | array of integers | no (default `[0, 2048, 4095]`) | The brightness palette. See [§4](#4-levels--the-palette). |
| `variants` | object | **yes** | Artwork per device, keyed by Pokémon codename. See [§3](#3-variants--keyed-by-pokémon-codename). |

"Required" above means *required for the file to be accepted*. Every field has a default in
the model — a slightly wrong file must decode far enough to be rejected with a specific
reason rather than throwing — but `id`, `createdAt` and `modifiedAt` have defaults that
cannot pass validation, and an empty `variants` object is rejected outright, so in practice
those four must be present and well-formed.

### `kind`, `keyMode` and `loop` together

These three describe playback, and their interaction is not guessable, so it is spelled out
here exactly as `screens/CustomScreen.kt` implements it.

- **`kind: "static"`** — one frame is pushed to the matrix and nothing else happens.
  `keyMode` and `loop` are irrelevant, and the Essential Key does nothing.
- **`kind: "dynamic"` with a single frame** behaves identically to a static design: there is
  nowhere to advance to.
- **`keyMode: "playOnce"`** — the matrix rests on frame 0. A press plays the animation
  through once and *returns to frame 0*, which is drawn, so the return is visible. A press
  during a run restarts it from frame 0 rather than being ignored. **`loop` is not consulted
  in this mode.**
- **`keyMode: "playPause"`** — playback **starts on its own** when the toy is shown; it does
  not wait for a press. (A design that sat motionless on an always-on display, where nobody
  is pressing anything, would look broken.) A press pauses where it is; the next press
  resumes from there — except at the last frame of a non-looping design, where a press
  starts it over. At the end: with `loop: true` it restarts at frame 0 and keeps going;
  with `loop: false` it holds the last frame, so the design ends on the image its author
  ended it on.

## 3. `variants` — keyed by Pokémon codename

`variants` maps a **device codename** to that device's artwork. The codename is Nothing's
own internal name for the model, not a pixel count:

| Key | Device | Matrix | Cells per frame |
|---|---|---|---|
| `bellsprout` | Nothing Phone (4a) Pro | 13 × 13 | 169 |
| `arbok` | Nothing Phone (3) | 25 × 25 | 625 |

A value is an object with a single field:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `frames` | array of frame objects | no (default `[]`) | The artwork, in playback order. |

and a frame object is:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `durationMs` | integer | no (default `120`) | How long this frame is held, in milliseconds. Present on static designs too (and ignored there) so switching a design to dynamic never has to invent timing. |
| `cells` | string | **yes** | The pixels. See [§5](#5-cells--the-pixel-encoding). |

**Why the codename and not the size.** "13" is a measurement that a future device could
coincidentally share while behaving differently; `bellsprout` names one specific panel
forever. Keying by size would also make the file read like an implementation detail instead
of a device list.

**An unknown codename is ignored, not fatal.** A key that is not in the table above is
*dropped* — the variant is discarded and the rest of the design loads normally. This is the
property that lets the format survive a device that does not exist yet: a design authored on
some future Nothing phone, carrying `bellsprout` alongside a codename we have never heard
of, still loads and still plays on a Phone (4a) Pro. Note the consequence for validators:
because the variant is dropped *before* its frames are examined, a malformed frame under an
unknown codename is not an error either.

**An empty variant is legal.** `{"frames": []}` means "no artwork for this device yet" — the
second size starts as a blank canvas and nothing is ever auto-scaled between geometries, so
this is a normal state, not a broken file. It is enough to satisfy the "at least one known
variant" rule; GlyphWorks will render its "nothing to play" placeholder for that device.

**Ordering is irrelevant.** `variants` is a JSON object; a reader looks its own device's
codename up by key.

## 4. `levels` — the palette

`levels` is an array of raw panel brightnesses, `0` to `4095` (the matrix is 12-bit, white
only). A cell in `cells` does not carry a brightness — it carries an **index into this
array**.

```json
"levels": [0, 2048, 4095]
```

means index `0` is off, index `1` is 50 % grey, index `2` is white. That is the default
palette and what GlyphWorks's editor offers today.

**It is data, not a constant, on purpose.** An editor that later offers five or nine
brightness steps simply writes a longer list; old files keep meaning exactly what they
meant, and no format break is needed. It also makes re-palettising a whole design — dimming
every grey, say — a one-line edit to `levels` instead of a rewrite of every frame.

A palette may hold up to **36** entries, which is the number of distinct values one base36
character can address ([§5](#5-cells--the-pixel-encoding)).

This is the one field where a reader is *lenient*: an entry outside `0..4095` is clamped
into range rather than rejected. A brightness beyond the panel's range has exactly one
sensible interpretation and no structural consequence. (Contrast `durationMs`, which is also
a range but where a value of `0` would become a busy loop on the render scheduler — so that
one is rejected.)

## 5. `cells` — the pixel encoding

One character per cell. The character is the **palette index in base36**: `0`–`9` for 0–9,
then `a`–`z` for 10–35.

Cells run **row-major**, so the character at string position `y * size + x` is the cell at
column `x`, row `y`, with `(0, 0)` at the top-left. `cells.length` must be exactly `size²`
for the variant's codename: 169 for `bellsprout`, 625 for `arbok`.

Decoding is therefore:

```
for i in 0 until size*size:
    index = base36(cells[i])          // reject if not a base36 digit
    out[i] = levels[index]            // reject if index >= levels.length
```

and the resulting `out` is the brightness array pushed to the panel.

Notes that matter if you are writing a tool:

- **Case.** Readers accept upper-case `A`–`Z` as well as lower-case, because files get typed
  by hand and pasted through tools that change case. GlyphWorks only ever *writes* lower-case.
- **ASCII only.** Only ASCII `0`–`9`, `a`–`z`, `A`–`Z` are digits here. Non-ASCII decimal
  digits (Arabic-Indic, Devanagari, …) are rejected deliberately: two visually different
  files decoding to the same frame is exactly the kind of ambiguity a format meant for
  byte-level diffing should not have.
- **The panel is a disc.** The grid is square, but the physical matrix is circular, so the
  corner cells are masked off in hardware. They must still be present in the string (the
  length check is geometric), they simply will not light up. Keep your art centred.
- **The encoding is what makes the format diffable.** A frame reads as
  `0001110000…` in a pull request, and a wrong-length frame is caught by a length check
  rather than a parse.

## 6. Timestamps

`createdAt` and `modifiedAt` are **ISO-8601 UTC instants**, e.g. `2026-07-30T12:00:00Z`.
GlyphWorks writes them truncated to whole seconds, so they always have the compact `…T12:00:00Z`
shape — sub-second precision would add noise to a file people read and diff by hand.

Two reasons for strings rather than epoch millis:

1. A community format should be self-describing when a human opens it.
2. ISO-8601 UTC strings **sort lexicographically**, so the app's design list sorts by
   modification time without parsing a single timestamp.

A reader parses them with `java.time.Instant.parse`, which on current runtimes also accepts
an explicit offset (`2026-07-30T12:00:00+02:00`) and sub-second precision
(`2026-07-30T12:00:00.500Z`). Both are **accepted and normalised**: GlyphWorks rewrites every
timestamp into the canonical `yyyy-MM-ddTHH:mm:ssZ` form on decode, before the design
reaches storage or the list.

Normalisation is what makes reason 2 an *invariant* rather than a request. Left alone, both
alternative spellings sort wrongly as characters:

- `2026-07-30T12:00:00+02:00` denotes 10:00 UTC but sorts after `2026-07-30T11:00:00Z`,
  which is later.
- `…T12:00:00.500Z` sorts *before* `…T12:00:00Z`, because `.` is below `Z` in ASCII.

Parsing has already resolved the text to an absolute instant, so re-formatting it canonically
changes the spelling and nothing else. **Emit the `Z` form truncated to whole seconds
anyway** — it is what GlyphWorks writes, and it is what a re-export of your file will contain — but
a file that does not is imported correctly rather than refused.

The one timestamp shape that *is* refused is a year outside `0000`–`9999`
(`+12026-07-30T12:00:00Z`). A widened year field makes the string a different length, and a
variable-width prefix cannot be ordered by character comparison at all; there is no honest
normalisation of it.

## 7. Validation — every rule a file must satisfy

Every design file is treated as hostile input: they are meant to be shared, so a file
reaching the decoder is attacker-controlled by default. The decoder **never throws** — it
returns either the design or a complete, user-facing sentence explaining the refusal.

The table below is the exhaustive list of ways a file is refused, in the order the checks
run (the **first** failure wins), with the exact reason constant from
`DesignCodec` that a violation produces. If you are writing an exporter, satisfying all of
these is sufficient.

| # | Rule | Constant | Message |
|---|---|---|---|
| 1 | The file must be at most **1 MB** (`MAX_BYTES = 1048576`). A stream is read through a bounded reader that stops one byte past the limit; a string is refused if it exceeds 1 048 576 characters. The size is checked **before** anything is parsed — this is the defence against a JSON bomb. | `REASON_TOO_LARGE` | "This file is too large to be a Glyph design." |
| 2 | An unreadable stream (an I/O error while reading) is reported with the underlying message appended. | `REASON_UNREADABLE` | "This design file could not be read." |
| 3 | The bytes must parse as JSON. | `REASON_NOT_JSON` | "This file is not valid JSON." |
| 4 | The root must be a JSON **object**, and its `format` member must be a JSON **string** equal to `glyph.design`. Checked on the parse tree before the document is mapped onto the model, so `{}` cannot inherit the magic string from a default and claim to be one of ours. | `REASON_NOT_A_DESIGN` | "This is not a Glyph design file." |
| 5 | A field of the wrong JSON type (e.g. `"loop": "yes"`, `"levels": 4`) fails the mapping. | `REASON_NOT_JSON` | "This file is not valid JSON." |
| 6 | `formatVersion` must not be **greater than 1**. A future version may repurpose a field, and half-understanding someone's art is worse than declining it. | `REASON_NEWER_VERSION` | "This design was made with a newer version of the app." |
| 7 | `formatVersion` must not be **less than 1**. | `REASON_OLDER_VERSION` | "This design declares a format version this app cannot read." |
| 8 | `id` must match `[A-Za-z0-9_-]{1,64}` **in full**. No separators, no dots (so no `..`), no NUL, no spaces, no Unicode — it is the only value in the file that ever reaches the filesystem. An absent or empty `id` fails here. | `REASON_BAD_ID` | "This design has an unusable id." |
| 9 | `name` ≤ **64** characters. | `REASON_NAME_TOO_LONG` | "This design's name is too long." |
| 10 | `author` ≤ **64** characters. | `REASON_AUTHOR_TOO_LONG` | "This design's author name is too long." |
| 11 | `createdWith` ≤ **64** characters. | `REASON_CREATED_WITH_TOO_LONG` | "This design's originating app name is too long." |
| 12 | `createdAt` **and** `modifiedAt` must both parse as ISO-8601 instants (`java.time.Instant.parse`) whose canonical UTC form has a four-digit year. Absent — and therefore empty — fails here. An explicit offset and sub-second precision are both *accepted and normalised* to `yyyy-MM-ddTHH:mm:ssZ`; see [§6](#6-timestamps). | `REASON_BAD_TIMESTAMP` | "This design has an unreadable timestamp." |
| 13 | `levels` must not be empty. | `REASON_EMPTY_PALETTE` | "This design has no brightness levels." |
| 14 | `levels` must hold at most **36** entries. | `REASON_PALETTE_TOO_LONG` | "This design has too many brightness levels." |
| 15 | Each known variant must hold at most **240** frames. At the 20 ms floor that is still nearly five seconds of animation. | `REASON_TOO_MANY_FRAMES` | "This design has too many frames." |
| 16 | Every `durationMs` must be **20 ≤ d ≤ 60000**. 20 ms is one 50 Hz step — anything faster is invisible and just burns binder calls; a minute on one frame is a static image with extra steps. Out-of-range durations are **rejected, not clamped**. | `REASON_BAD_DURATION` | "This design has a frame duration outside 20 ms to 60 s." |
| 17 | Every `cells` string must be exactly **`size²`** characters for its variant's codename. Rejected, never padded or truncated — silently handing back art that is not what someone made is worse than telling them the file is broken. | `REASON_BAD_FRAME_SIZE` | "This design has a frame that is the wrong size for its device." |
| 18 | Every character of every `cells` string must be an ASCII base36 digit whose value is a valid index into `levels` (i.e. `< levels.length`). | `REASON_BAD_FRAME_CELL` | "This design has a frame using a brightness level it does not define." |
| 19 | After unknown codenames are dropped, **at least one** variant must remain. | `REASON_NO_VARIANTS` | "This design contains no artwork for any known device." |

Rules 15–18 are applied per variant, and only to variants whose codename is known.

Three things are deliberately **not** errors:

- **Unknown top-level or nested fields** are ignored. A field added in format version 2 must
  not stop a version-1 reader from reading the rest of the file.
- **An unrecognised enum value** — `"kind": "kaleidoscope"`, `"keyMode": "morse"` — degrades
  to that field's default (`static`, `playPause`) rather than making the file unopenable.
  A JSON `null` for any field does the same.
- **Palette entries outside `0..4095`** are clamped into range, as described in [§4](#4-levels--the-palette).

A file that is accepted is **normalised** on the way in: timestamps rewritten into canonical
UTC, palette entries clamped, unknown variants dropped. What GlyphWorks then stores is the
normalised design, so a re-export is not
guaranteed byte-identical to the file you imported — but it is guaranteed to mean the same
thing for every device the format knows about.

Finally, two rules that are about the *importing app* rather than the file, but which a tool
author should know:

- **An import always becomes a new design.** GlyphWorks reassigns `id` unconditionally on import,
  not merely on collision, so a file carrying the id of a design already on the phone can
  never overwrite it.
- **The export filename comes from `name`, sanitised** to letters and digits with everything
  else collapsed to hyphens, falling back to the `id`. It is not part of the format; do not
  encode meaning in it.

## 8. A complete example

A valid two-frame `bellsprout` design: a diamond that pulses out to a grey halo and back,
repeating until you pause it. Copy it as-is — it imports.

Frame 0 (`0`=off, `2`=white), 13 rows of 13:

```
0000000000000
0000000000000
0000000000000
0000000000000
0000002000000
0000022200000
0000222220000
0000022200000
0000002000000
0000000000000
0000000000000
0000000000000
0000000000000
```

Frame 1, the same diamond with a grey (`1`) halo around it:

```
0000000000000
0000000000000
0000001000000
0000011100000
0000112110000
0001122211000
0011222221100
0001122211000
0000112110000
0000011100000
0000001000000
0000000000000
0000000000000
```

Concatenated row by row into one string each, that is:

```json
{
  "format": "glyph.design",
  "formatVersion": 1,
  "id": "pulse0001",
  "name": "Pulse",
  "author": "example",
  "createdAt": "2026-07-30T12:00:00Z",
  "modifiedAt": "2026-07-30T12:00:00Z",
  "createdWith": "spec example",
  "kind": "dynamic",
  "keyMode": "playPause",
  "loop": true,
  "levels": [0, 2048, 4095],
  "variants": {
    "bellsprout": {
      "frames": [
        {
          "durationMs": 300,
          "cells": "0000000000000000000000000000000000000000000000000000000000200000000000222000000000222220000000002220000000000020000000000000000000000000000000000000000000000000000000000"
        },
        {
          "durationMs": 300,
          "cells": "0000000000000000000000000000000010000000000011100000000011211000000011222110000011222221100000112221100000001121100000000011100000000000100000000000000000000000000000000"
        }
      ]
    }
  }
}
```

It carries no `arbok` variant, which is legal: on a Phone (3) it renders the "nothing to
play" placeholder, and opening it in the editor gives you a blank 25 × 25 canvas to draw the
second size on.

## 9. Writing your own exporter

The minimum viable file is smaller than the example above, because most fields have
defaults. This is everything a producer strictly has to emit:

```json
{
  "format": "glyph.design",
  "id": "my-first-design",
  "createdAt": "2026-07-30T12:00:00Z",
  "modifiedAt": "2026-07-30T12:00:00Z",
  "variants": {
    "bellsprout": {
      "frames": [ { "cells": "…169 characters…" } ]
    }
  }
}
```

That is accepted as a **static** design (`kind` defaults to `static`) using the default
`[0, 2048, 4095]` palette, with `durationMs` defaulting to 120.

A checklist for a producer:

1. Emit `format` and a valid `id` (`[A-Za-z0-9_-]`, 1–64 characters). If you have nothing
   better, a UUID with the hyphens stripped is what GlyphWorks uses.
2. Emit both timestamps in the `2026-07-30T12:00:00Z` form. Another ISO-8601 spelling of the
   same instant will be normalised into it on import rather than refused, but emitting the
   canonical form means the file you shipped and the file GlyphWorks re-exports agree.
3. Emit at least one variant with a **known** codename — `bellsprout` or `arbok`.
4. Emit `cells` of exactly 169 (bellsprout) or 625 (arbok) characters, row-major, every
   character a base36 index that is `< levels.length`.
5. If you emit `levels`, keep it non-empty and ≤ 36 entries, and remember the values are raw
   0–4095 panel brightnesses, not percentages.
6. If you emit `durationMs`, keep it in 20…60000.
7. Set `createdWith` to something that identifies your tool. Nobody needs it until a file
   misbehaves, and then it is the first thing anyone asks for.
8. Stay under 1 MB.

For a **dynamic** design also set `"kind": "dynamic"`, decide `keyMode`, and set `loop` if
you want a `playPause` animation to repeat rather than hold its last frame ([§2](#kind-keymode-and-loop-together)).

## 10. Forward compatibility

The rules that keep this format usable as it changes, and the guarantees you can rely on:

- **`formatVersion` only rises when meaning changes.** Adding a field does not need a
  version bump, because unknown fields are ignored by every reader. A version rises when an
  existing field would be *misread* by an older reader — and an older reader then declines
  the file rather than half-understanding it.
- **New devices arrive as new `variants` keys.** No version bump: readers that do not know
  the codename drop that variant and play what they do know.
- **More brightness levels arrive as a longer `levels` array.** No version bump, up to the
  36-entry ceiling the one-character-per-cell encoding allows.
- **An unrecognised enum value degrades to a default** rather than failing, so a future
  `kind` or `keyMode` still opens.

Which means: if you write a version-1 file today, it stays readable; and if a version-1
reader meets a file from a later minor evolution of the format, it either reads it or says
so clearly.
