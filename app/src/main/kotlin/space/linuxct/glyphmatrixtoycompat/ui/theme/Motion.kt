package space.linuxct.glyphmatrixtoycompat.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Material 3's **standard motion springs**, transcribed from material3 1.4.0's
 * own `androidx/compose/material3/tokens/StandardMotionTokens.kt`.
 *
 * ### Why these are hardcoded
 *
 * Material 3 serves these through `MotionScheme` / `MaterialTheme.motionScheme`,
 * but in material3 **1.4.0** (the version this app pins) both are `internal`:
 * `MotionScheme.kt:42` declares `internal interface MotionScheme` and
 * `MaterialTheme.kt:141` declares `internal val motionScheme`. App code cannot
 * read them, so the token values are copied here instead of being invented.
 *
 * When a future material3 makes that API public, delete this file and swap the
 * two call sites to `MaterialTheme.motionScheme.defaultSpatialSpec()` and
 * `defaultEffectsSpec()` — which is exactly what these two functions mirror.
 *
 * ### Why there are two of them
 *
 * The split is deliberate in MD3 and must be preserved:
 *
 *  - [spatial] — size, shape and position. Damping **0.9**, i.e. *under*-damped:
 *    it overshoots a little and settles back. That slight bounce is the MD3
 *    feel; Compose's own `spring()` default is `DampingRatioNoBouncy` (1.0),
 *    which by definition cannot overshoot at all.
 *  - [effects] — colour and alpha. Damping **1.0** (no bounce, ever — a
 *    bouncing colour reads as a flicker) and much stiffer, so tints and fills
 *    settle well before the geometry does.
 */
object Md3Motion {

    /** `StandardMotionTokens.SpringDefaultSpatialDamping`. */
    const val SPATIAL_DAMPING = 0.9f

    /** `StandardMotionTokens.SpringDefaultSpatialStiffness`. */
    const val SPATIAL_STIFFNESS = 700f

    /** `StandardMotionTokens.SpringDefaultEffectsDamping`. */
    const val EFFECTS_DAMPING = 1f

    /** `StandardMotionTokens.SpringDefaultEffectsStiffness`. */
    const val EFFECTS_STIFFNESS = 1600f

    /**
     * The default **spatial** spring: for anything that changes size, shape or
     * position. Pass the animated type's own `VisibilityThreshold` where one
     * exists (e.g. `IntSize.VisibilityThreshold`), as Compose's own defaults do.
     */
    fun <T> spatial(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, visibilityThreshold)

    /** The default **effects** spring: for colour and alpha only. */
    fun <T> effects(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS, visibilityThreshold)
}
