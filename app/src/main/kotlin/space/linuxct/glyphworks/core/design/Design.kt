package space.linuxct.glyphworks.core.design

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The `glyph.design` interchange format.
 *
 * This is simultaneously the export format *and* the on-disk storage format —
 * there is no second, "internal" representation to keep in sync. A design file
 * someone posts in a gist is byte-for-byte what this app writes to
 * device-protected storage, which is the only way a community format stays
 * honest over time.
 *
 * Everything in this package is deliberately pure Kotlin (java.* is fine,
 * `android.*` is not) so the format, its decoder and every validation rule are
 * exercised by plain JVM unit tests. The Android-side file I/O lives in
 * `designs/DesignStore`.
 *
 * ```json
 * {
 *   "format": "glyph.design",
 *   "formatVersion": 1,
 *   "id": "9f2c...",
 *   "name": "Slow Ember",
 *   "author": "linuxct",
 *   "createdAt": "2026-07-30T12:00:00Z",
 *   "modifiedAt": "2026-07-30T12:34:56Z",
 *   "createdWith": "GlyphWorks 2.0.0",
 *   "kind": "dynamic",
 *   "keyMode": "playPause",
 *   "loop": true,
 *   "levels": [0, 2048, 4095],
 *   "variants": {
 *     "bellsprout": { "frames": [ { "durationMs": 120, "cells": "0012..." } ] },
 *     "arbok":      { "frames": [] }
 *   }
 * }
 * ```
 */

/** Magic string every design file must carry as its `format`. */
const val DESIGN_FORMAT = "glyph.design"

/**
 * The newest format this build can read. A file declaring a *higher* version is
 * rejected with a "made with a newer version" message rather than parsed
 * optimistically: a future version may repurpose a field, and half-understanding
 * someone's art is worse than declining it.
 */
const val DESIGN_FORMAT_VERSION = 1

/**
 * The default three-entry palette: off / 50 % grey / white.
 *
 * `levels` is *data*, not a constant, precisely so an editor that later offers
 * five or nine brightness steps just writes a longer list — no format break, and
 * old files keep meaning exactly what they meant. See [DesignFrames] for how a
 * cell character indexes into it.
 */
val DEFAULT_LEVELS: List<Int> = listOf(0, 2048, 4095)

/**
 * The matrix geometries this app knows, identified by the device's Pokémon
 * codename rather than by pixel count.
 *
 * The codename is the stable identity: "13" is a measurement that a future
 * device could coincidentally share while behaving differently, whereas
 * `bellsprout` names one specific panel forever. Keying variants by size would
 * also make the JSON read like an implementation detail instead of a device
 * list. The field is `pokemonCodename` everywhere it is named.
 */
enum class PokemonCodename(val codename: String, val size: Int) {
    /** Nothing Phone (4a) Pro — 13x13. */
    BELLSPROUT("bellsprout", 13),

    /** Nothing Phone (3) — 25x25. */
    ARBOK("arbok", 25);

    /** Cells in one frame for this geometry. */
    val cellCount: Int get() = size * size

    companion object {
        /**
         * Resolves a codename as written in a file. Nullable on purpose: an
         * unknown codename must be *survivable*, so callers drop that variant
         * and keep the rest of the design rather than failing the import.
         */
        fun ofCodename(codename: String): PokemonCodename? =
            entries.firstOrNull { it.codename == codename }

        /**
         * Resolves this device's geometry from `Core.glyphLink.size`. Nullable
         * so an unrecognised panel renders a placeholder instead of guessing.
         */
        fun ofSize(size: Int): PokemonCodename? = entries.firstOrNull { it.size == size }
    }
}

/** Whether a design is a single frame or an animation. */
@Serializable
enum class DesignKind {
    @SerialName("static")
    STATIC,

    @SerialName("dynamic")
    DYNAMIC,
}

/**
 * What a single Essential-Key press does while this design is on screen. Only
 * one gesture ever reaches a screen (double and triple presses belong to the
 * carousel), so two modes is the whole vocabulary.
 */
@Serializable
enum class KeyMode {
    /** Rests on frame 0; a press plays the animation through once and returns. */
    @SerialName("playOnce")
    PLAY_ONCE,

    /** A press toggles between playing and paused. */
    @SerialName("playPause")
    PLAY_PAUSE,
}

/**
 * One frame: how long it is shown, and its pixels.
 *
 * [cells] is one character per cell, the character being the palette index in
 * base36 — see [DesignFrames]. [durationMs] is present on static designs too
 * (and ignored there) so that switching a design from static to dynamic never
 * has to invent timing.
 */
@Serializable
data class DesignFrame(
    val durationMs: Int = DEFAULT_FRAME_DURATION_MS,
    val cells: String = "",
)

/** Default frame duration for a newly drawn frame — ~8 fps, comfortably visible. */
const val DEFAULT_FRAME_DURATION_MS = 120

/**
 * The frames for one device geometry. A design may legitimately carry an empty
 * variant: the second size starts as a blank canvas and nothing is ever
 * auto-scaled between geometries, so "no frames yet for arbok" is a normal,
 * expressible state rather than an error.
 */
@Serializable
data class DesignVariant(
    val frames: List<DesignFrame> = emptyList(),
)

/**
 * A complete design file.
 *
 * Every field has a default so that a truncated-but-well-formed file decodes
 * into something the validator can then reject with a *specific* reason,
 * instead of kotlinx throwing a "field is required" exception whose message we
 * would have to show a user.
 *
 * [variants] is keyed by [PokemonCodename.codename]. It is a `Map<String, _>`
 * rather than a `Map<PokemonCodename, _>` because kotlinx would throw on an
 * unknown enum key, and an unknown codename must be ignored, not fatal.
 * [DesignCodec] guarantees that every key present in a decoded design *is* a
 * known codename; use [variantFor] to read one.
 */
@Serializable
data class Design(
    val format: String = DESIGN_FORMAT,
    val formatVersion: Int = DESIGN_FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val author: String = "",
    /**
     * ISO-8601 UTC, e.g. `2026-07-30T12:00:00Z` — not epoch millis. Two reasons:
     * a community format should be self-describing when read by a human, and
     * ISO-8601 UTC strings sort lexicographically, so the design list sorts
     * correctly without parsing a single timestamp.
     *
     * A design that has come through [DesignCodec] carries this **exact** form
     * and no other spelling of it: any other ISO-8601 instant a producer emits —
     * an explicit offset, sub-second precision — is normalised into it on decode,
     * which is what makes that sort correct by construction rather than by asking
     * producers to be careful. See `DesignCodec.normalisedInstant`.
     */
    val createdAt: String = "",
    val modifiedAt: String = "",
    /** e.g. `GlyphWorks 2.0.0`. Invaluable when debugging a file another build produced. */
    val createdWith: String = "",
    val kind: DesignKind = DesignKind.STATIC,
    val keyMode: KeyMode = KeyMode.PLAY_PAUSE,
    val loop: Boolean = false,
    val levels: List<Int> = DEFAULT_LEVELS,
    val variants: Map<String, DesignVariant> = emptyMap(),
) {
    /** The frames for [codename], or null if this design has no art for that device. */
    fun variantFor(codename: PokemonCodename): DesignVariant? = variants[codename.codename]

    /** The frames for the panel this device actually has, or null. */
    fun variantForSize(size: Int): DesignVariant? =
        PokemonCodename.ofSize(size)?.let { variantFor(it) }
}

/**
 * A fresh design id: 32 lowercase hex characters, which satisfies the safe-token
 * rule in [DesignCodec] by construction. The id becomes a filename, so it is
 * generated here and never derived from user-typed text.
 */
fun newDesignId(): String = UUID.randomUUID().toString().replace("-", "")

/**
 * The current instant as the format's timestamp form. Truncated to whole
 * seconds so the string is always the compact `...T12:00:00Z` shape — sub-second
 * precision would add noise to a file people read and diff by hand.
 */
fun nowIsoUtc(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
