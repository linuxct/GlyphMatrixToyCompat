package space.linuxct.glyphworks.ui.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.matrix.PanelMask
import kotlin.math.roundToInt

/**
 * The phone-back illustration with a live Glyph Matrix on it, and the inverse
 * mapping that turns a touch on that matrix back into a cell.
 *
 * ## One model, two cameras
 *
 * There is exactly **one model of the device** — [DeviceBack] — expressed in
 * device-back coordinates and nothing else: where the body is, where the camera
 * island sits on it, and where every feature sits on the island. It has no idea
 * what it is being drawn into. Every caller sees the same object.
 *
 * There are then **two cameras** over that one model, and a camera is a
 * [Camera]: a zoom and a focus point, and nothing else.
 *
 * | caller | camera |
 * |---|---|
 * | `KeyTutorialDialog` | zoomed out — the whole phone, cropped by the illustration area |
 * | `DesignEditorActivity` | zoomed in until the matrix fills the drawing area, focused on the matrix |
 *
 * **Sharing the DRAWING is right; sharing the FRAMING was the bug.** One
 * implementation of the phone is what stopped the tutorial and the editor from
 * drifting into two different devices, and that has paid for itself repeatedly.
 * But the two also shared how the drawing was *placed*, through a `phoneMetrics`
 * that resolved the body against the canvas and a pile of per-layout nudges on top
 * of it — so every time the editor's framing was tuned, the tutorial silently
 * re-framed too. That broke the tutorial twice. Framing is therefore a per-caller
 * parameter: each caller constructs its own [Camera] and hands it in. Nothing in
 * this file knows which caller it is drawing for, and nothing in a caller knows
 * how the device is shaped.
 *
 * **There are no framing biases anywhere.** Not here and not in a caller. If a
 * framing looks wrong the model is wrong and the model gets fixed; a feature is
 * never moved, and a camera is never nudged, to make a particular canvas look
 * better. The editor used to carry a `DISC_LEFT_BIAS`, a `DISC_BOTTOM_GAP` and a
 * per-design-kind `discIsCentred`, all three of which existed to make a framing
 * acceptable by moving the subject rather than by drawing it correctly. They are
 * gone, and what the camera shows is now simply what is there.
 *
 * ## The colour split is deliberate and is not a bug
 *
 * The body, the camera island and the lenses derive from the caller's [Color]
 * (in practice `MaterialTheme.colorScheme.onSurface`, alpha-stepped), so the
 * illustration reads as part of whichever page it is on. The disc is the raw
 * literal [MATRIX_DISC_COLOR] and the LEDs are [Color.White], with no theme role
 * anywhere near them, because a real Glyph Matrix is white LEDs behind black
 * glass in a dark room and in a bright one. Theming those two would make the
 * preview lie about the hardware, which is the one thing this drawing exists to
 * avoid.
 *
 * [RECORDING_DOT_COLOR] is the third and last of those, and one of the three hues
 * an otherwise monochrome app allows itself — see its own KDoc for why it is
 * allowed to be one and why it must stay small.
 */

/**
 * Where the Glyph Matrix disc landed, in the coordinates of whoever placed it —
 * everything else the matrix needs (cell pitch, hit testing) is derived from these
 * two numbers, so they are the only handoff between drawing the phone and
 * drawing/painting the panel on it.
 *
 * Used twice over: once in [DeviceBack]'s device coordinates, where it is a fact
 * about the hardware, and once in canvas pixels, where it is what a [Camera] made
 * of that fact.
 */
data class MatrixDisc(val center: Offset, val radius: Float)

/**
 * This disc seen through a uniform zoom and pan — every point mapped
 * `p -> p * [scale] + [offset]`.
 *
 * The editor's pinch-zoom is *entirely* this function, and it returns a
 * `MatrixDisc` rather than a transform matrix on purpose. [drawMatrix] and
 * [matrixCellAt] derive everything they know — the pitch, the grid origin, the
 * circular cull — from a centre and a radius, and a uniform scale plus a
 * translation maps a disc to a disc. So a zoomed view is just *another disc*:
 * both functions keep working on it completely unchanged, and the inverse stays
 * exact instead of becoming "exact, modulo a transform applied twice in two
 * places that must agree".
 *
 * That the round trip survives is arithmetic rather than luck. The pitch is
 * linear in the radius and `g0` is linear in the centre, so `cell -> centre ->
 * cell` is invariant under it; and the *set* of cells the panel has is not
 * geometry at all but [PanelMask], which is indexed by cell and so cannot move
 * under a zoom even in principle. `GlyphCanvasTest` asserts both, over every cell
 * of both geometries at a spread of scales and offsets, because an off-by-one
 * under zoom would mean painting the wrong pixel and that is the failure that
 * makes a drawing tool feel broken rather than buggy.
 */
fun MatrixDisc.transformedBy(scale: Float, offset: Offset): MatrixDisc = MatrixDisc(
    center = Offset(center.x * scale + offset.x, center.y * scale + offset.y),
    radius = radius * scale,
)

/** The Glyph Matrix's glass. A raw literal, on purpose — see the file KDoc. */
val MATRIX_DISC_COLOR = Color(0xFF0E0E0E)

/**
 * The recording indicator beside the matrix: **one of the three hues in this
 * app**, and a deliberate, product-accurate exception rather than an oversight.
 *
 * The others are the Create tab's `+` FAB, painted in Nothing's brand red and
 * blue (see `ui/theme/NothingBrand.kt`), and the setup-attention badge on the nav
 * bar's Settings chip (`MainActivity`'s `AttentionBadge`). The three exceptions
 * are unrelated and none licenses the others: the first is branding, on a single
 * control; this one is a photograph; the third is the one place hue is allowed to
 * carry meaning, and it was asked for explicitly. The rule they are exceptions to
 * is unchanged and is stated in `Theme.kt` — the badge aside, no hue anywhere
 * that carries *meaning*, and even there the exclamation mark rather than the red
 * is what says it.
 *
 * This one does not carry meaning: it is a picture of a small red square that
 * exists on the back of the phone, in the same sense that [MATRIX_DISC_COLOR] is
 * a picture of black glass and the LEDs are pictures of white light. All three
 * are raw literals for the same reason — they depict physical objects, and an
 * object does not change colour because the page it is drawn on did. Rendering
 * this one grey "for consistency" would remove the single detail that tells a
 * viewer they are looking at *this* phone rather than at a generic rounded
 * rectangle, which is precisely the complaint this drawing was rebuilt to answer.
 *
 * **It stays small on purpose.** At `0.05` of the island's width it is a
 * fortieth of the matrix's area, so it reads as a detail on the hardware and
 * never as an accent colour the UI is using to say something. Anything larger,
 * or anywhere else, and the exception stops being one.
 */
val RECORDING_DOT_COLOR = Color(0xFFE0392C)

// ---------- the model ----------

/**
 * How dark a feature of the device is, against the caller's base colour.
 *
 * A tone rather than a `Color` because the model must not know what page it is on:
 * [drawDeviceBack] resolves these against whatever `onSurface` the caller passes,
 * and the three that depict physical objects rather than shading — the panel's
 * glass, the recording square, and nothing else — are raw literals resolved the
 * same way. See the file KDoc.
 */
internal enum class Tone {
    /** The phone's back. */
    BODY,

    /** The camera plate's fill. */
    PLATE,

    /** The plate's rim: a hairline that turns a tonal step into an EDGE. */
    PLATE_RIM,

    /** A lens barrel, a sensor, the pill: the hardware sitting on the plate. */
    LENS,

    /** The glass inside a lens barrel. */
    LENS_GLASS,

    /** The Essential Key, unpressed. */
    KEY,

    /** The Glyph Matrix's black glass — [MATRIX_DISC_COLOR]. */
    GLASS,

    /** The recording indicator — [RECORDING_DOT_COLOR]. */
    RECORDING,
}

/**
 * One feature of the device, in [DeviceBack]'s coordinates.
 *
 * The model is *data* rather than a sequence of draw calls, so that it can be
 * measured — by a test, by a camera, by anything that needs to know where the
 * plate's right edge is — without a `DrawScope` in the room. [drawDeviceBack] is
 * then a loop over this list and contains no geometry of its own, which is what
 * makes "one model of the device" a fact about the code rather than a comment.
 */
internal sealed interface DeviceShape {
    val tone: Tone

    data class Round(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val corner: Float,
        override val tone: Tone,
        /** Non-null for an outline: the stroke width, in device units. */
        val stroke: Float? = null,
    ) : DeviceShape

    data class Dot(
        val center: Offset,
        val radius: Float,
        override val tone: Tone,
    ) : DeviceShape
}

/**
 * **The one model of the device**, in device-back coordinates.
 *
 * ## The coordinate system
 *
 * `1.0` is the **body's width**, `(0, 0)` is the body's top-left corner, x runs
 * across the back and y runs down it. Both axes use the same unit, so a circle is
 * a circle and a camera is a scalar. Nothing in here mentions a canvas, a dp or a
 * caller.
 *
 * ## What is where, and why it is not negotiable
 *
 * The island sits in the **upper-left region of the back** — a small margin in
 * from the left and top edges, spanning the left two thirds of the width. It is
 * *not* centred on the back, and that single fact is what decides the editor's
 * framing: zoom in on the matrix and the plate's right edge, with a strip of body
 * beyond it, comes into view on one side while the lens cluster goes off the
 * other. Centring the plate would make both sides symmetric and both wrong.
 *
 * Every feature on the plate is placed in the plate's own 0..1 box, **both axes
 * scaled by the plate's WIDTH**, via [island]. That is what lets [ISLAND_ASPECT]
 * change the plate's height without moving one thing on it, and it is why a
 * radius given in that box is an honest radius rather than an ellipse waiting to
 * happen.
 *
 * ## The relationship that matters most
 *
 * **The matrix's diameter is about half the plate's width** (`0.24` radius against
 * a plate of `1.0`), and the lens cluster is an object of comparable visual weight
 * — a ringed main camera over a two-lens module, together `0.35` wide and `0.66`
 * tall against the matrix's `0.48` square. They are two features of one piece of
 * hardware. Earlier versions had the matrix at `0.26` and the cluster drawn small,
 * and the matrix dwarfed everything on the plate; the numbers below are read off
 * the device instead.
 */
internal object DeviceBack {

    /**
     * How long the body is, in body widths.
     *
     * The Phone (3a) Pro is 163.5 x 77.5 mm, which is 2.11. It matters only in
     * that it is far longer than any camera in this app frames, so the body's
     * lower edge and its rounded bottom corners are off-screen in every view —
     * the property [cropBelow] exists to guarantee even if this number were ever
     * wrong.
     */
    const val BODY_LENGTH = 2.11f

    /** The body's corner radius, as a fraction of its width. */
    const val BODY_CORNER = 0.17f

    /** The plate's inset from the body's left, right and top edges. */
    const val ISLAND_MARGIN = 0.045f

    /**
     * The plate's width: **nearly the whole back**, inset by [ISLAND_MARGIN] on
     * each side.
     *
     * Measured off a photograph of the device: the plate is 216 px across a
     * 234 px body, i.e. 0.92. It very nearly touches both side edges — there is
     * no wide strip of bare body beside it.
     *
     * This number was wrong for four revisions of this drawing, at 0.63 ("the
     * left two thirds"), and every complaint about the illustration's silhouette
     * was a downstream symptom of it: a plate that small leaves a third of the back
     * empty, which is what made the tutorial read as a card with a widget in the
     * corner. Do not shrink it again without re-measuring the device.
     *
     * Getting this right did NOT get the plate's contents right — those were
     * measured separately, later, and moved almost every feature on it. Width and
     * interior are independent measurements; confirming one says nothing about the
     * other.
     */
    const val ISLAND_WIDTH = 0.91f

    /**
     * The plate is wider than it is tall: **849 x 638 px measured, so 1.331 : 1**.
     *
     * The plate is a raised block with a bevelled wall, so a photograph gives two
     * defensible outlines — the outer edge of the bevel and the flat top face —
     * that differ by about 5 %, and everything expressed in plate widths scales
     * with whichever is chosen. This is the flat top face, fixed by agreement
     * between two independent references: the press photograph puts its left and
     * right edges at 578 and 1427, and a straight-on product render gives an aspect
     * of `180 / 136 = 1.324`; `849 / 1.331 = 638` then places the bottom edge at
     * 803, which is exactly where the photograph's bottom seam is.
     *
     * Getting this wrong does not look like a wrong plate — it looks like wrong
     * CONTENTS. A previous `1.294` made the plate too tall, and the visible symptom
     * was the Glyph Matrix reading as too small and sitting too low on it, because
     * every island coordinate is divided by this.
     */
    const val ISLAND_ASPECT = 1.331f

    /** The plate's height, in the same body-width units as everything else. */
    const val ISLAND_HEIGHT = ISLAND_WIDTH / ISLAND_ASPECT

    /**
     * The plate's corner radius, as a fraction of its own WIDTH — relative to the
     * plate and not to the body, because the corners are the most recognisable
     * thing about it: at `0.16` the top-right corner is a long shallow sweep
     * rather than a small fillet, which is what makes a crop of that corner read
     * as "a plate continues off-frame" instead of "a rectangle was drawn slightly
     * rounded".
     */
    const val ISLAND_CORNER = 0.16f

    /** The plate's edges, in device coordinates. */
    const val ISLAND_LEFT = ISLAND_MARGIN
    const val ISLAND_TOP = ISLAND_MARGIN
    const val ISLAND_RIGHT = ISLAND_LEFT + ISLAND_WIDTH
    const val ISLAND_BOTTOM = ISLAND_TOP + ISLAND_HEIGHT

    /** A point in the plate's own 0..1 box, resolved to device coordinates. */
    fun island(x: Float, y: Float): Offset =
        Offset(ISLAND_LEFT + ISLAND_WIDTH * x, ISLAND_TOP + ISLAND_WIDTH * y)

    /** A length in the plate's box, resolved to device units. */
    fun islandLength(v: Float): Float = ISLAND_WIDTH * v

    /**
     * **The Glyph Matrix**, high in the plate's right portion: centre
     * `(0.7332, 0.2833)` of the plate, radius `0.2173` of its width.
     *
     * Measured off a straight-on press photograph: the panel is 369 px across and
     * centred at `(1200, 406)` on an 849 px plate whose flat face begins at
     * `(578, 165)`. Its right rim is `0.05` of the plate's width clear of the
     * plate's right edge.
     *
     * The panel's size and position relative to the OTHER hardware were already
     * right before this centre was: matrix diameter over main-camera diameter is
     * 1.757 on the device and 1.754 here, and the offset between their centres
     * matches to three decimals. What was wrong was the plate they are all divided
     * by — see [ISLAND_ASPECT]. That is the failure mode to watch for: a feature
     * reported as mis-sized is usually correct relative to its neighbours and wrong
     * relative to the box, so check the box before touching the feature.
     *
     * How large the panel *appears* is the camera's job and is independent of this:
     * the editor zooms until the matrix fills a chosen fraction of its canvas, so
     * the cell pitch depends on that fraction and the canvas alone. Every revision
     * of this number has cost exactly zero editing resolution.
     */
    val matrix = MatrixDisc(center = island(0.7332f, 0.2833f), radius = islandLength(0.2173f))

    /**
     * The Essential Key: a nub on the body's right edge, just below the plate.
     *
     * Part of the model because it is part of the device. Only the tutorial's
     * camera can see it — under the editor's it is hundreds of pixels off the
     * right of the frame — but it is placed here, once, rather than in the caller
     * that happens to look at it.
     */
    const val KEY_WIDTH = 0.038f
    const val KEY_HEIGHT = 0.15f
    const val KEY_TOP = ISLAND_BOTTOM + 0.046f
    const val KEY_LEFT = 1f - KEY_WIDTH * 0.45f

    /** How far into the body the key sinks when pressed. */
    const val KEY_TRAVEL = 0.013f

    /**
     * Everything on the plate, plate included, in draw order.
     *
     * The body is not in this list because its lower edge is not a fixed number —
     * see [cropBelow] — and the Essential Key is not because its position depends
     * on whether it is being pressed. Both are drawn by [drawDeviceBack] from the
     * constants above.
     */
    val plate: List<DeviceShape> = listOf(
        // The plate, and its rim. A flat hairline rather than a bevel or a
        // shadow: it is what makes the top-right corner read as an EDGE rather
        // than as a tonal step, at the one alpha between the plate's fill and the
        // body's.
        islandRound(0f, 0f, 1f, ISLAND_HEIGHT / ISLAND_WIDTH, ISLAND_CORNER, Tone.PLATE),
        islandRound(
            0f, 0f, 1f, ISLAND_HEIGHT / ISLAND_WIDTH, ISLAND_CORNER, Tone.PLATE_RIM,
            stroke = 0.006f,
        ),
        // Every position below is measured off a straight-on press photograph, in
        // the plate's own box (x and y both in plate WIDTHS, per [island]). The
        // plate's flat face is at (578, 165) and 849 px wide in that photograph, so
        // a feature's island coordinate is (px - 578) / 849 across and
        // (px - 165) / 849 down. Each was checked by drawing it back over the
        // photograph as an outline and looking at the result.
        //
        // The main camera, upper-left: its black glass, with the lens barrel inside.
        // The glass's radius is what makes its LEFT EDGE land at 0.0554 — exactly
        // the module's left edge below it, which is how the device has them and is
        // the alignment a reader notices immediately when it is missing. An earlier
        // 0.138 barrel drawn around it broke that and made the camera look shoved
        // out to the left, so nothing here may be drawn wider than the glass.
        islandDot(0.1790f, 0.2002f, 0.1237f, Tone.LENS),
        islandDot(0.1790f, 0.2002f, 0.0843f, Tone.LENS_GLASS),
        // The two-camera module below it: a CAPSULE, nearly twice as wide as it is
        // tall (0.462 x 0.244), drawn WITHOUT the two lenses inside it.
        //
        // The empty capsule is deliberate and was asked for twice. What was wrong
        // when this was reported as "not what the two cameras look like" was the
        // housing's SHAPE — a squat 1.36-aspect rounded rectangle — not the absence
        // of its lenses. Drawing them back in was a misreading. The shape carries
        // the likeness; the lenses only add clutter at this size.
        islandRound(0.0554f, 0.4240f, 0.5171f, 0.6678f, 0.112f, Tone.LENS),
        // Sensor dots, in the gap between the cluster and the matrix. They are
        // STACKED — both at x ~= 0.415 — not set diagonally as a previous revision
        // had them.
        islandDot(0.4122f, 0.1366f, 0.0400f, Tone.LENS),
        islandDot(0.4193f, 0.2615f, 0.0436f, Tone.LENS),
        // The pill below the matrix.
        islandRound(0.6148f, 0.6231f, 0.8575f, 0.6855f, 0.031f, Tone.LENS),
        // The recording indicator, low and right of the matrix — the one hue in
        // the drawing and in the app. See [RECORDING_DOT_COLOR]. Centred on
        // (0.930, 0.518) with a side of 0.057.
        islandRound(0.9011f, 0.4900f, 0.9588f, 0.5465f, 0.013f, Tone.RECORDING),
        // The panel's glass. Its CONTENTS are not part of the device model —
        // see [drawMatrix], which paints them straight onto the mapped disc.
        DeviceShape.Dot(matrix.center, matrix.radius, Tone.GLASS),
    )

    private fun islandRound(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        corner: Float,
        tone: Tone,
        stroke: Float? = null,
    ): DeviceShape.Round {
        val a = island(left, top)
        val b = island(right, bottom)
        return DeviceShape.Round(a.x, a.y, b.x, b.y, islandLength(corner), tone, stroke?.let(::islandLength))
    }

    private fun islandDot(x: Float, y: Float, radius: Float, tone: Tone): DeviceShape.Dot =
        DeviceShape.Dot(island(x, y), islandLength(radius), tone)
}

// ---------- the camera ----------

/**
 * A view of [DeviceBack]: **how magnified, and which point of the device is at the
 * centre of the frame.** Nothing else.
 *
 * That is the entire framing vocabulary this app has, deliberately. Every previous
 * version of this drawing accumulated extra knobs — a left bias, a bottom gap, a
 * per-design-kind "centred" flag, a body-top clamp — each added to make one canvas
 * look acceptable, each of which then quietly re-framed the other caller. A camera
 * that is only a zoom and a focus point cannot do that: the two callers can pick
 * completely different values and still be looking at the same device.
 *
 * The canvas is not part of the camera. It is passed to the mapping functions
 * because a viewport is a property of where you are drawing, not of how you are
 * looking — which is what makes a camera comparable across canvases and testable
 * without one.
 */
data class Camera(val zoom: Float, val focus: Offset) {

    /** A device point in canvas pixels: `(p - focus) * zoom`, about the centre. */
    fun map(p: Offset, canvas: Size): Offset = Offset(
        (p.x - focus.x) * zoom + canvas.width / 2f,
        (p.y - focus.y) * zoom + canvas.height / 2f,
    )

    /** A device length in canvas pixels. */
    fun map(length: Float): Float = length * zoom

    /** Where the Glyph Matrix lands under this camera. */
    fun matrixDisc(canvas: Size): MatrixDisc =
        MatrixDisc(map(DeviceBack.matrix.center, canvas), DeviceBack.matrix.radius * zoom)

    /**
     * This camera with the user's pinch folded into it — the same camera looking
     * from `scale` times closer, at whatever the pan left under the fingers.
     *
     * The editor applies its pinch as a plain `p -> p * scale + offset` on canvas
     * pixels (see `CanvasTransform`), and the composition of that with a camera is
     * *another camera*: solving `((p - f) * z + c) * s + o = (p - f') * z' + c`
     * gives `z' = z * s` and the focus below. So a zoomed, panned editor is not a
     * special case anywhere — [drawDeviceBack] and [matrixDisc] see one camera and
     * cannot tell it has been pinched, exactly as [MatrixDisc.transformedBy] makes
     * a zoomed disc just a disc.
     */
    fun transformedBy(scale: Float, offset: Offset, canvas: Size): Camera {
        if (scale <= 0f || zoom <= 0f) return this
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        return Camera(
            zoom = zoom * scale,
            focus = Offset(
                focus.x + (cx * (1f - scale) - offset.x) / (zoom * scale),
                focus.y + (cy * (1f - scale) - offset.y) / (zoom * scale),
            ),
        )
    }
}

/**
 * How far down the phone **body** has to reach, in DEVICE coordinates, for its
 * lower edge to stay off the bottom of the canvas.
 *
 * The illustration only reads as *a phone* while the body's lower edge runs off
 * the frame. A body with visible bottom corners is a small object floating in a
 * box, which is what a caller reported the first time the editor's framing moved
 * under it: everything below the matrix shifted up and rounded corners arrived on
 * screen.
 *
 * The guard is a fact rather than a guess. The camera bottom's device y is
 * `focus.y + (height / 2) / zoom`, and one body corner radius past it puts the
 * curve itself out of frame too — the corner is the part that gives a closed shape
 * away. Derived from the camera actually in use, it cannot go stale when a framing
 * changes, which a fixed overhang did twice.
 *
 * **It is the BODY's guarantee and not the plate's.** The plate's bottom edge is a
 * real feature of the hardware and is framed on purpose wherever the camera has
 * the room; it is the wash behind it whose corners would give the illustration
 * away.
 */
internal fun cropBelow(canvas: Size, camera: Camera): Float {
    if (camera.zoom <= 0f) return DeviceBack.BODY_LENGTH
    return camera.focus.y + (canvas.height / 2f) / camera.zoom + DeviceBack.BODY_CORNER
}

// ---------- drawing ----------

/**
 * How visible an unlit LED is. Not zero: the physical panel's dark pixels are
 * still visible pits in the glass, and on a 13x13 grid they are most of the
 * picture — dropping them would leave lit cells floating in a void with no sense
 * of the panel they sit on. In the editor they double as the grid you aim at.
 */
private const val UNLIT_ALPHA = 0.10f

/**
 * Side of a square LED as a fraction of the cell pitch: 80 %, leaving a 20 % gap
 * between neighbours, so the grid reads as a dot-matrix panel.
 */
private const val PIXEL_FRACTION = 0.80f

private fun Tone.resolve(base: Color, keyPressed: Boolean): Color = when (this) {
    Tone.BODY -> base.copy(alpha = 0.26f)
    Tone.PLATE -> base.copy(alpha = 0.13f)
    Tone.PLATE_RIM -> base.copy(alpha = 0.22f)
    Tone.LENS -> base.copy(alpha = 0.34f)
    Tone.LENS_GLASS -> base.copy(alpha = 0.5f)
    Tone.KEY -> base.copy(alpha = if (keyPressed) 0.85f else 0.45f)
    Tone.GLASS -> MATRIX_DISC_COLOR
    Tone.RECORDING -> RECORDING_DOT_COLOR
}

/**
 * **The phone's back, seen through [camera].** Returns where the Glyph Matrix
 * landed so the caller can paint pixels on it.
 *
 * The disc itself is drawn here (it is part of the phone); its CONTENTS are not
 * (they are not) — see [drawMatrix].
 *
 * This function contains no geometry. It resolves [DeviceBack] through the camera
 * and draws what it is told, which is what makes the tutorial's phone and the
 * editor's phone the same object rather than two drawings that agree.
 *
 * The camera is applied as a `DrawScope` transform rather than by mapping each
 * shape's coordinates, so that stroke widths and corner radii scale with it
 * automatically — a rim that stayed one pixel wide as the plate grew would read as
 * a drawn outline instead of an edge.
 */
fun DrawScope.drawDeviceBack(base: Color, camera: Camera, keyPressed: Boolean = false): MatrixDisc {
    if (camera.zoom <= 0f) return camera.matrixDisc(size)
    // The body takes whichever is lower, its true extent or the crop floor, so a
    // camera that could see past its bottom edge never does. See [cropBelow].
    val bodyBottom = maxOf(DeviceBack.BODY_LENGTH, cropBelow(size, camera))
    withTransform({
        translate(
            size.width / 2f - camera.focus.x * camera.zoom,
            size.height / 2f - camera.focus.y * camera.zoom,
        )
        scale(camera.zoom, camera.zoom, pivot = Offset.Zero)
    }) {
        drawRoundRect(
            Tone.BODY.resolve(base, keyPressed),
            topLeft = Offset.Zero,
            size = Size(1f, bodyBottom),
            cornerRadius = CornerRadius(DeviceBack.BODY_CORNER),
        )
        // The Essential Key, before the plate: it hangs off the body's right edge
        // and nothing on the plate can reach it.
        val travel = if (keyPressed) DeviceBack.KEY_TRAVEL else 0f
        drawRoundRect(
            Tone.KEY.resolve(base, keyPressed),
            topLeft = Offset(DeviceBack.KEY_LEFT - travel, DeviceBack.KEY_TOP),
            size = Size(DeviceBack.KEY_WIDTH, DeviceBack.KEY_HEIGHT),
            cornerRadius = CornerRadius(DeviceBack.KEY_WIDTH / 2f),
        )
        for (shape in DeviceBack.plate) {
            val color = shape.tone.resolve(base, keyPressed)
            when (shape) {
                is DeviceShape.Dot -> drawCircle(color, radius = shape.radius, center = shape.center)
                is DeviceShape.Round -> drawRoundRect(
                    color,
                    topLeft = Offset(shape.left, shape.top),
                    size = Size(shape.right - shape.left, shape.bottom - shape.top),
                    cornerRadius = CornerRadius(shape.corner),
                    style = shape.stroke?.let { Stroke(width = it) } ?: Fill,
                )
            }
        }
    }
    return camera.matrixDisc(size)
}

/**
 * The pixels, on a disc at [center] / [radius] — a `size` x `size` grid read
 * row-major from [frame] (`y * size + x`, values 0..4095), exactly the layout
 * `MatrixCanvas` and the design format already use.
 *
 * Brightness maps straight to alpha, because on this panel it IS alpha: the
 * hardware has one white LED per cell and a 12-bit level, so `4095` is a fully
 * opaque white square and half of that is a half-opaque one. Unlit cells fall
 * back to [UNLIT_ALPHA] rather than vanishing.
 *
 * One square pixel size for lit and unlit alike — a hardware LED covers the same
 * area either way, only its brightness changes.
 *
 * Cells the panel does not have are skipped — [PanelMask], which is the same
 * object [matrixCellAt] asks, so a cell is paintable if and only if it is drawn.
 */
fun DrawScope.drawMatrix(
    center: Offset,
    radius: Float,
    size: Int,
    frame: IntArray,
    unlitAlpha: Float = UNLIT_ALPHA,
) {
    if (size <= 0 || radius <= 0f) return
    val cell = matrixCellPitch(radius, size)
    val g0 = gridOrigin(center, cell, size)
    val g0x = g0.x
    val g0y = g0.y
    val px = cell * PIXEL_FRACTION
    val pxSize = Size(px, px)
    val pxCorner = CornerRadius(px * 0.16f)
    for (r in 0 until size) {
        for (c in 0 until size) {
            if (!PanelMask.contains(c, r, size)) continue
            val cx = g0x + c * cell
            val cy = g0y + r * cell
            val index = r * size + c
            val value = if (index < frame.size) frame[index] else 0
            val alpha = if (value > 0) {
                (value / DesignFrames.MAX_BRIGHTNESS.toFloat()).coerceIn(0f, 1f)
            } else {
                unlitAlpha
            }
            drawRoundRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(cx - px / 2f, cy - px / 2f),
                size = pxSize,
                cornerRadius = pxCorner,
            )
        }
    }
}

/**
 * Diameter of an onion-skin dot as a fraction of the cell pitch.
 *
 * A real LED is a rounded square covering [PIXEL_FRACTION] (80 %) of the pitch.
 * A ghost is a *circle* covering a third of it — roughly a sixth of the area, and
 * a different shape. That difference is the whole point: an onion skin is a
 * reference for where the previous frame's art was, and a reference that could be
 * mistaken for content would have people drawing around pixels that are not
 * there. Low alpha alone would not do it, because a dim cell is a legal, ordinary
 * thing to paint.
 */
private const val GHOST_FRACTION = 0.34f

/** How bright a ghost dot is. Dimmer than the dimmest paintable level. */
private const val GHOST_ALPHA = 0.32f

/**
 * The previous frame, ghosted onto the same disc as a drawing reference.
 *
 * Drawn *after* [drawMatrix] rather than under it, because the unlit-cell wash
 * (`UNLIT_ALPHA` white) would otherwise sit on top and wash the ghost out. To
 * keep "after" from meaning "altering the picture", a dot is skipped wherever
 * [current] is already lit: a ghost therefore never adds brightness to a cell the
 * user actually painted, and only ever appears inside cells that are off.
 *
 * [ghost] and [current] are the same row-major `size * size` layout as everywhere
 * else; the circular cull is the draw loop's, unchanged.
 */
fun DrawScope.drawMatrixGhost(
    center: Offset,
    radius: Float,
    size: Int,
    ghost: IntArray,
    current: IntArray,
) {
    if (size <= 0 || radius <= 0f) return
    val cell = matrixCellPitch(radius, size)
    val g0 = gridOrigin(center, cell, size)
    val g0x = g0.x
    val g0y = g0.y
    val dot = cell * GHOST_FRACTION / 2f
    val tint = Color.White.copy(alpha = GHOST_ALPHA)
    for (r in 0 until size) {
        for (c in 0 until size) {
            val index = r * size + c
            if (index >= ghost.size || ghost[index] <= 0) continue
            if (index < current.size && current[index] > 0) continue
            if (!PanelMask.contains(c, r, size)) continue
            drawCircle(tint, radius = dot, center = Offset(g0x + c * cell, g0y + r * cell))
        }
    }
}

/**
 * The inverse of [drawMatrix]'s cell placement: the cell under [offset], or null
 * if there is none.
 *
 * This is the function that makes an editor possible, and it is the one place an
 * off-by-one would be invisible in review and obvious in the hand — you would
 * touch a pixel and a different pixel would light. So it is written as the
 * literal algebraic inverse of the loop above rather than as its own idea of
 * where the cells are:
 *
 * - [drawMatrix] centres cell `(c, r)` at `g0 + (c, r) * cell`. Solving for `c`
 *   gives `(offset.x - g0x) / cell`, and **rounding** that (rather than flooring
 *   it) picks the NEAREST centre — which is exactly the cell whose square the
 *   touch is closest to, and is exact at every cell centre by construction.
 * - `g0` and `cell` come from the same two expressions the draw loop uses, via
 *   [matrixCellPitch], so they cannot drift apart.
 * - Whether the panel HAS that cell is then asked of [PanelMask] — the same
 *   object, by the same index, as the draw loop. Note what is not tested: the
 *   touch point's own distance from the centre. The question is which cell you
 *   are nearest to and whether that cell exists, so a fingertip that overhangs
 *   the rim still paints the rim cell it is on. A cell the draw loop skipped can
 *   never be painted, and a cell it drew is always reachable — the round trip
 *   `cell -> centre -> cell` is the identity for every drawn cell at both
 *   geometries, which is what `GlyphCanvasTest` asserts.
 *
 * Returned as `IntOffset(x = column, y = row)`, so the caller indexes
 * `frame[y * size + x]` — the app's one and only cell order.
 */
fun matrixCellAt(offset: Offset, center: Offset, radius: Float, size: Int): IntOffset? {
    if (size <= 0 || radius <= 0f) return null
    val cell = matrixCellPitch(radius, size)
    if (cell <= 0f) return null
    val g0 = gridOrigin(center, cell, size)
    val column = ((offset.x - g0.x) / cell).roundToInt()
    val row = ((offset.y - g0.y) / cell).roundToInt()
    // The touch is nearest to this cell; whether that cell EXISTS is the draw
    // loop's question, asked of the draw loop's own oracle. `contains` bounds-
    // checks too, so a touch off the grid entirely falls out here.
    if (!PanelMask.contains(column, row, size)) return null
    return IntOffset(column, row)
}

/**
 * Distance between two neighbouring cell centres. Shared by the draw loop, the
 * hit test and the caller's stroke interpolation (which steps along a drag in
 * fractions of a cell so a fast flick cannot skip pixels).
 */
fun matrixCellPitch(radius: Float, size: Int): Float =
    if (size <= 0) 0f else radius * 2f * PanelMask.GRID_EXTENT / size

/**
 * Where cell (0, 0) is centred — the grid's origin, from which every other cell
 * is one [cell] pitch away per column and per row.
 *
 * Extracted because four functions need it and all four have to agree to the
 * pixel: the two draw loops place cells with it, [matrixCellAt] solves it for a
 * touch, and [matrixCellCenter] evaluates it forwards. It was written out four
 * times, and an edit to three of them would have shown up as painting the wrong
 * pixel rather than as anything a reader would notice.
 */
private fun gridOrigin(center: Offset, cell: Float, size: Int): Offset =
    Offset(center.x - cell * (size - 1) / 2f, center.y - cell * (size - 1) / 2f)

/**
 * Where cell ([x], [y]) is drawn, on a disc at [center] / [radius] — the exact
 * forward mapping [matrixCellAt] inverts.
 *
 * Nothing in the editor needs this: a finger arrives as a position and leaves as
 * a cell, which is the inverse direction. It exists for the guided demo, which
 * has to move a ghost finger *to* a cell it is about to paint, and it lives here
 * rather than there so that the demo's finger and the editor's hit test cannot
 * end up describing two different grids. `GlyphCanvasTest` asserts the round trip
 * in both directions.
 */
fun matrixCellCenter(center: Offset, radius: Float, size: Int, x: Int, y: Int): Offset {
    val cell = matrixCellPitch(radius, size)
    val g0 = gridOrigin(center, cell, size)
    return Offset(g0.x + x * cell, g0.y + y * cell)
}
