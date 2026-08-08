package space.linuxct.glyphworks.ui

/**
 * The **Initial setup** checklist, as six answered questions.
 *
 * ## Why this is a type and not six `remember`s in the Settings page
 *
 * Two different parts of the app now have to know whether setup is finished: the
 * checklist rows, which say so item by item, and the navigation bar's badge on
 * the Settings chip, which says so as one bit from another corner of the screen.
 * Those two live in different composables that do not share a subtree — the pill
 * is a sibling of the pager, the checklist is inside it — and the obvious way to
 * give the badge its bit is to re-ask the system the same six questions where the
 * pill is drawn. That is exactly the arrangement that rots: the day a seventh item
 * is added to the checklist, or the day one of the six changes what "OK" means,
 * the badge keeps answering the old question and starts lying about a page it is
 * pointing at.
 *
 * So the probe happens ONCE, in `MainActivity`'s `probeSetup`, and lands here;
 * the rows read the fields and the badge reads [needsAttention]. Adding an item
 * means adding a field, which the compiler then demands at the single probe site
 * and which [needsAttention] picks up for free.
 *
 * ## Why it is plain Kotlin
 *
 * Nothing in here touches `android.*`, so the rule that decides whether a badge
 * appears is reachable from an ordinary JUnit test (`SetupStatusTest`). The
 * Android-shaped half — "is this permission granted", "is that service enabled" —
 * stays in `MainActivity`, where it is a handful of one-line system calls with no
 * logic in them worth testing.
 *
 * ## What counts as needing attention
 *
 * Every row that does not show a check mark, which is the same rule the user sees.
 * That deliberately includes the two rows whose copy softens them:
 *
 * - **Location** is captioned "optional" because the compass merely loses its
 *   magnetic-declination correction without it — but the row still renders as an
 *   unsatisfied item, and a badge that disagreed with a visible question mark
 *   would be a worse defect than a badge over an optional item.
 * - **The always-on toy** cannot be read back from the system at all and is
 *   latched from the first time the toy is bound (see the row's own comment), so
 *   before that latch trips it is *unverified* rather than known-bad. It is still
 *   a thing the user has to go and do, and it is the item most likely to be
 *   missed, so it counts.
 */
internal data class SetupStatus(
    /** The Essential Key accessibility service is enabled. */
    val accessibility: Boolean,
    /** GlyphWorks has been selected as the always-on Glyph Toy (latched; see the KDoc). */
    val alwaysOnToy: Boolean,
    /** `POST_NOTIFICATIONS` — the Timer chime. */
    val notifications: Boolean,
    /** `RECORD_AUDIO` — the music visualizer. */
    val microphone: Boolean,
    /** Coarse or fine location — the compass's declination correction. */
    val location: Boolean,
    /** `SCHEDULE_EXACT_ALARM` — the Timer's backstop. */
    val exactAlarms: Boolean,
) {
    /**
     * True when at least one item is not satisfied — the ONE bit that opens the
     * Initial setup section on arrival and puts the badge on the Settings chip.
     *
     * Spelled out field by field rather than as a list of booleans so that adding
     * a field to the class is a compile error here rather than a silently
     * unchecked item.
     */
    val needsAttention: Boolean
        get() = !accessibility ||
            !alwaysOnToy ||
            !notifications ||
            !microphone ||
            !location ||
            !exactAlarms

    companion object {
        /**
         * Everything done. The only [SetupStatus] with no badge, and the base the
         * tests `copy()` a single `false` into.
         */
        val COMPLETE = SetupStatus(
            accessibility = true,
            alwaysOnToy = true,
            notifications = true,
            microphone = true,
            location = true,
            exactAlarms = true,
        )
    }
}
