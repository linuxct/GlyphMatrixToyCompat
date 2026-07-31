package space.linuxct.glyphmatrixtoycompat.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.SessionArbiter
import space.linuxct.glyphmatrixtoycompat.core.design.DESIGN_FORMAT
import space.linuxct.glyphmatrixtoycompat.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.core.design.nowIsoUtc
import space.linuxct.glyphmatrixtoycompat.designs.DesignStore
import space.linuxct.glyphmatrixtoycompat.screens.CustomScreen
import space.linuxct.glyphmatrixtoycompat.ui.design.DemoTarget
import space.linuxct.glyphmatrixtoycompat.ui.design.DesignDemoActivity
import space.linuxct.glyphmatrixtoycompat.ui.design.DesignEditorActivity
import space.linuxct.glyphmatrixtoycompat.ui.design.LocalDemoTargets
import space.linuxct.glyphmatrixtoycompat.ui.design.demoTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The **Create** tab: everything the user owns, listed, with the `+` that makes
 * a new one.
 *
 * It lives in its own file rather than in `MainActivity.kt` (already ~2 000
 * lines) which means the shared building blocks it borrows — [SectionCard],
 * [HintText], [NAV_PILL_CLEARANCE] — had to be promoted from `private` to
 * `internal`. That is the established precedent in this package, not a new one:
 * `selectedRowColors`, `NoRipple` and `DIALOG_VERTICAL_MARGIN` were promoted the
 * same way.
 *
 * **The `+` is not here.** It is a sibling of the floating nav pill (see
 * `NavFab` in `MainActivity.kt`), which is a sibling of the Scaffold, which is
 * nowhere near this subtree. [CreateState] is the bridge: the button sets a flag
 * on it, this file watches the flag and puts up the dialog.
 *
 * ## Threading
 *
 * `DesignStore` is file I/O — the first app-owned file I/O in the project — and
 * none of it may happen on the main thread. Every call goes through
 * `withContext(Dispatchers.IO)` from a `rememberCoroutineScope`, and the list is
 * held in snapshot state. In particular the FIRST load is asynchronous too: the
 * tab renders its (empty) frame immediately and fills in when the directory has
 * been read, rather than blocking the frame that brings the page on screen.
 */
@Composable
internal fun CreateTab(
    innerPadding: PaddingValues,
    listState: LazyListState,
    state: CreateState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Core.designStore }

    // Loads once per process, not once per visit: [CreateState] is hoisted into
    // MainScreen and outlives this page being disposed off-window by the pager.
    LaunchedEffect(state) { state.loadIfNeeded(store) }

    // A trip through the editor changes a design's art AND its modifiedAt, and
    // this list is a cached index, so coming back to the foreground has to
    // re-read it or the row the user just edited keeps yesterday's summary.
    //
    // Counted in a plain IntArray rather than snapshot state on purpose: the very
    // first ON_RESUME is the one that arrives with the window, which the load
    // above already covers, and skipping it must not itself cost a recomposition.
    val resumes = remember { intArrayOf(0) }
    LifecycleResumeEffect(state) {
        if (resumes[0]++ > 0) scope.launch { state.refresh(store) }
        onPauseOrDispose { }
    }

    // Shared copies are the one thing this feature leaves on disk that the user
    // cannot see, so the cache is swept every time this page is first composed.
    // Off the main thread, and fire-and-forget: nothing on screen depends on it.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { pruneSharedCache(shareCacheDir(context), System.currentTimeMillis()) }
    }

    val designs = state.designs
    val saveFailed = stringResource(R.string.create_save_failed)
    val shareFailed = stringResource(R.string.create_shared_failed)
    val unnamed = stringResource(R.string.pref_custom_unnamed)
    // Resolved HERE, in the composable, and formatted later. The strings below are
    // used from coroutines and result callbacks, where `context.getString` is not
    // allowed (lint's LocalContextGetResourceValueCall: a Context read that way
    // does not follow configuration changes). `stringResource` on a template
    // returns the template, so the argument is substituted at use with `format`.
    val exportedTemplate = stringResource(R.string.create_exported)
    val exportFailed = stringResource(R.string.create_export_failed)
    val importedTemplate = stringResource(R.string.create_imported)
    val shareChooserTitle = stringResource(R.string.create_share_chooser)
    // Resolved here for the same reason, and `afterEditor = false`: nothing in
    // this tab holds the matrix, so a design selected from a card really is on
    // it by the time the toast appears.
    val showMessage = showOnMatrixMessage(afterEditor = false)

    // ---------- import / export / share ----------

    /**
     * The reason the last import was refused, shown in a dialog. Held as a
     * String (not a result object) so `rememberSaveable` can carry it through a
     * configuration change — the message must survive a rotation, because a
     * dialog that vanishes when the phone turns is indistinguishable from an
     * import that silently did nothing.
     */
    var importError by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Which design the open document-creation dialog belongs to.
     *
     * The **id**, not the design: SAF leaves this Activity while the picker is up,
     * so the process may be recreated before the result arrives. An id is a
     * String and therefore saveable, and re-reading the design from the store on
     * the way back also means the exported bytes are the current ones rather than
     * whatever this list was showing when the menu was tapped.
     */
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DESIGN_MIME),
    ) { uri: Uri? ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    val design = store.load(id) ?: return@withContext null
                    if (writeDesign(context, uri, design)) design.name else null
                }
                val message = if (name != null) {
                    exportedTemplate.format(name.ifBlank { unnamed })
                } else {
                    exportFailed
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                when (val outcome = state.import(context, store, uri)) {
                    is ImportOutcome.Ok -> {
                        Toast.makeText(
                            context,
                            importedTemplate.format(outcome.design.name.ifBlank { unnamed }),
                            Toast.LENGTH_SHORT,
                        ).show()
                        // The import has the newest modifiedAt, so it sorts to
                        // the top — this is "here is the design you just added".
                        listState.animateScrollToItem(0)
                    }
                    // DesignCodec's own sentence, verbatim. Collapsing "made with
                    // a newer version of the app" and "not a Glyph design file"
                    // into one message would throw away the only information the
                    // user has about what to do next.
                    is ImportOutcome.Failed -> importError = outcome.reason
                }
            }
        }
    }

    // The mime filter belongs on launch, not on the contract, so the picker shows
    // design files and greys out everything else.
    val onImport = { importLauncher.launch(arrayOf(DESIGN_MIME)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        // MANDATORY: the nav pill is an overlay, so without this the last card
        // sits underneath it and cannot be scrolled clear. Same arithmetic as
        // every other tab.
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE,
        ),
    ) {
        when {
            // Still reading the directory. Deliberately renders NOTHING rather
            // than a spinner or an empty state: the read takes a few
            // milliseconds off a handful of small files, and a spinner that
            // flashes for one frame is worse than a page that simply arrives.
            // The empty state below must never be shown to someone who does
            // have designs, which is exactly what a null-means-loading state
            // buys.
            designs == null -> Unit

            designs.isEmpty() -> item(key = "empty") {
                CreateEmptyState(
                    onStart = { state.newDesignRequested = true },
                    onImport = onImport,
                )
            }

            else -> {
                item(key = "hint") {
                    Column {
                        HintText(stringResource(R.string.create_hint))
                        // Import has to be reachable from the list itself, not
                        // only from the empty state: somebody who already has
                        // designs is exactly who gets sent one by a friend.
                        ImportButton(onImport)
                    }
                }
                // Already sorted newest-modified first by DesignStore.list(),
                // which is a plain string sort — the format's timestamps are
                // ISO-8601 UTC and therefore sort lexicographically.
                items(designs, key = { it.id }) { design ->
                    val copyName =
                        stringResource(R.string.create_copy_suffix, design.name.ifBlank { unnamed })
                    DesignCard(
                        design = design,
                        // Only the id travels. The editor re-reads the design
                        // itself, so it can never save a copy that went stale
                        // while this list was on screen.
                        onOpen = { context.startActivity(DesignEditorActivity.intent(context, design.id)) },
                        onShow = {
                            Toast.makeText(context, showMessage(showDesignOnMatrix(design)), Toast.LENGTH_SHORT)
                                .show()
                        },
                        onDuplicate = {
                            scope.launch {
                                val ok = state.duplicate(store, design, copyName)
                                if (!ok) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { state.pendingDelete = design },
                        onExport = {
                            // Remembered BEFORE the picker starts, because the
                            // result callback is all that comes back from it.
                            pendingExportId = design.id
                            exportLauncher.launch(designFileName(design))
                        },
                        onShare = {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { writeShareCopy(context, design) }
                                val shared = uri != null &&
                                    startShare(context, uri, design, shareChooserTitle)
                                if (!shared) {
                                    Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        placement = Modifier.animateItem(
                            // Same list-motion rules as ToysTab: the slide to a
                            // new slot is a POSITION change → spatial, while the
                            // fades are alpha → effects, which must never bounce.
                            // animateItem()'s own defaults are foundation's, not
                            // MD3's, so all three are passed explicitly.
                            fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        ),
                    )
                }
            }
        }
    }

    if (state.newDesignRequested) {
        NewDesignDialog(
            suggestedName = remember(designs) {
                generateDesignName(designs.orEmpty().mapTo(HashSet()) { it.name })
            },
            // Defaulted to the phone in the user's hand: the common case is one
            // device, and it should be answered by not reading the question.
            defaultTarget = remember { homeCodename() },
            onDismiss = { state.newDesignRequested = false },
            onCreate = { name, kind, targets ->
                state.newDesignRequested = false
                scope.launch {
                    val ok = state.create(context, store, name, kind, targets)
                    if (ok) {
                        // The new design sorts to the top (it has the newest
                        // modifiedAt), so this is "show me what I just made".
                        listState.animateScrollToItem(0)
                    } else {
                        Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    importError?.let { reason ->
        ImportFailedDialog(reason) { importError = null }
    }

    state.pendingDelete?.let { design ->
        DeleteDesignDialog(
            design = design,
            onDismiss = { state.pendingDelete = null },
            onConfirm = {
                state.pendingDelete = null
                scope.launch { state.delete(store, design) }
            },
        )
    }

    CreateTourOffer(state)
}

/**
 * The one-off "would you like to watch the tutorial?" offer, and the note that
 * follows a no.
 *
 * ## When it fires
 *
 * The first time the user actually **lands** on this tab — [CreateState.visited],
 * set by `MainScreen` when the pager settles here. Deliberately not "when this
 * composable first runs": the pager keeps one page composed either side of the
 * viewport, so `CreateTab` exists while the user is reading the Toys page, and
 * an offer keyed on composition would go up for a tab nobody had opened.
 *
 * ## Why the preference is written before the answer
 *
 * The key is stamped as the dialog goes up, not when a button is pressed. The
 * failure it rules out is a process death (or a task swipe) with the dialog on
 * screen: answered-only bookkeeping would put the same question back on the next
 * launch, and a question that comes back is indistinguishable from one that was
 * never asked. Nothing is lost by the early write — the tour it offers is a
 * permanent row in the Tutorials tab, which is exactly what the follow-up says.
 *
 * ## "Not now" answers; back does not
 *
 * The follow-up ("it is waiting in the Tutorials tab") is shown for the BUTTON
 * only. A back gesture or a tap outside is an instruction to go away, and
 * answering it with a second dialog would be arguing with the user — the
 * preference is already spent either way, so nothing is lost but a sentence they
 * did not ask for.
 *
 * ## Why it is guarded on the tour
 *
 * The guided demo drives the real Create tab, and a tutorial offering itself
 * inside a tutorial would be absurd. Today the demo composes `CreateEmptyState`
 * rather than this whole tab, so the guard is belt and braces — which is the
 * right way round for a guard whose failure mode is a dialog nobody can dismiss
 * (the demo swallows touches). [LocalDemoTargets] is the honest question: it is
 * non-null exactly while a tour is hosting these composables.
 */
@Composable
private fun CreateTourOffer(state: CreateState) {
    val context = LocalContext.current
    val inDemo = LocalDemoTargets.current != null
    // rememberSaveable: a rotation with the dialog open must not dismiss it, and
    // the preference is already spent by then, so nothing would put it back.
    var offering by rememberSaveable { mutableStateOf(false) }
    var declined by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.visited, inDemo) {
        val prompted = Core.prefs.getBoolean(
            PrefKeys.CREATE_TOUR_PROMPTED,
            PrefKeys.CREATE_TOUR_PROMPTED_DEF,
        )
        if (!shouldOfferCreateTour(visited = state.visited, prompted = prompted, inDemo = inDemo)) {
            return@LaunchedEffect
        }
        Core.prefs.putBoolean(PrefKeys.CREATE_TOUR_PROMPTED, true)
        offering = true
    }

    if (offering) {
        MotionDialog(onDismiss = { offering = false }) { dismiss ->
            TourOfferCard(
                title = stringResource(R.string.create_tour_title),
                body = stringResource(R.string.create_tour_body),
                confirmLabel = stringResource(R.string.create_tour_watch),
                onConfirm = {
                    // Closed OUTRIGHT rather than through `dismiss`, which is the
                    // one case where the exit animation must not be waited on:
                    // the tour covers the screen immediately, this window stops
                    // getting frames, and a transition that never idles would
                    // leave the offer still standing on the way back.
                    offering = false
                    context.startActivity(DesignDemoActivity.intent(context))
                },
                dismissLabel = stringResource(R.string.create_tour_skip),
                onDismiss = {
                    // The follow-up is armed BEFORE the exit animation finishes;
                    // MotionDialog only reports the dismissal once the card has
                    // scaled out, so the second card enters after the first has
                    // left rather than over it.
                    declined = true
                    dismiss()
                },
            )
        }
    } else if (declined) {
        MotionDialog(onDismiss = { declined = false }) { dismiss ->
            TourOfferCard(
                title = stringResource(R.string.create_tour_later_title),
                body = stringResource(R.string.create_tour_later_body),
                confirmLabel = null,
                onConfirm = {},
                dismissLabel = stringResource(R.string.create_tour_later_dismiss),
                onDismiss = dismiss,
            )
        }
    }
}

/**
 * Whether the offer should go up: the user has landed on Create, has never been
 * asked, and is not inside the guided demo.
 *
 * Pure, and split out for that reason — "shown once, ever" is the whole of this
 * feature's behaviour and it is a predicate over three Booleans, so it is the
 * part worth pinning down in a test rather than in a screenshot.
 */
internal fun shouldOfferCreateTour(visited: Boolean, prompted: Boolean, inDemo: Boolean): Boolean =
    visited && !prompted && !inDemo

/**
 * The card both of the offer's dialogs are drawn on: title, a short paragraph,
 * and one or two text buttons.
 *
 * Same surface, radius and padding as [KeyTutorialDialog] and
 * [TutorialInfoDialog], because these are the same kind of pop-up and there is
 * no reason for a third look. [confirmLabel] is null for the follow-up, which
 * has nothing to confirm.
 */
@Composable
private fun TourOfferCard(
    title: String,
    body: String,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                if (confirmLabel != null) {
                    TextButton(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}

// ---------- which device(s) a design is for ----------

/**
 * The panel this phone actually has, with a fallback for one the format does not
 * know.
 *
 * One expression, one place. It is asked by the new-design dialog (what to
 * default to), by [seedVariants] (which variant gets the first frame) and by the
 * editor (which variant to open on), and those three disagreeing would mean
 * creating a design for one size and opening it on another.
 *
 * The fallback is [PokemonCodename.BELLSPROUT] rather than a null that every
 * caller would have to invent an answer for: an unrecognised panel is a phone
 * this app cannot render on anyway, and 13x13 is the size its owner is most
 * likely to be drawing *for*.
 */
internal fun homeCodename(): PokemonCodename =
    PokemonCodename.ofSize(Core.glyphLink.size) ?: PokemonCodename.BELLSPROUT

/**
 * The answers the new-design dialog offers to "which phone is this for", in the
 * order it offers them: each device on its own, then all of them together.
 *
 * A `Set<PokemonCodename>` rather than a three-valued enum, because the *set* is
 * what [seedVariants] consumes and what the editor later re-derives from the
 * design's own keys. An enum would be a second spelling of the same fact, and
 * the one that could drift.
 *
 * The combined row is labelled "Both sizes" (`create_target_both`), which assumes
 * exactly two known devices. A third would need that string reworded — and would
 * also want more than one combination — so it is called out here rather than
 * discovered in a screenshot.
 */
internal fun designTargetOptions(): List<Set<PokemonCodename>> =
    PokemonCodename.entries.map { setOf(it) } + listOf(PokemonCodename.entries.toSet())

/**
 * The `variants` map a brand-new design starts life with: **only the devices the
 * user asked for**, and nothing else.
 *
 * ## Why the two seeds differ
 *
 * [home] — the panel this phone has — gets ONE blank frame, because it is the
 * variant the editor is about to open and draw on. Every other chosen device gets
 * an *empty* variant, which is the format's "a blank canvas is waiting for that
 * size" state: the Create tab renders it as "(empty)", `showDesignOnMatrix`
 * declines it with the size still to draw, and `CustomScreen` shows its
 * placeholder rather than a dark panel. That is exactly what is true of a size
 * somebody has asked for and not yet drawn, and it is byte-for-byte what "both"
 * produced before this choice existed — so the default path is unchanged.
 *
 * (The "other phone only" case therefore yields a design with a variant and no
 * frames at all. `DesignCodec` accepts it — it rejects only a design with no
 * *variants* — and the editor invents the blank frame to draw on when it opens,
 * exactly as it does for a second variant that has never been touched.)
 *
 * ## Why there is no "target devices" field
 *
 * The variants present ARE the answer. An imported design carrying both sizes
 * shows the editor's variant switcher with no special-casing, the format needs no
 * new field and no version bump, and there is no second source of truth to drift
 * from the artwork itself.
 *
 * An empty [targets] cannot come from the dialog (it is a single-choice control)
 * but is coerced to [home] anyway rather than trusted: `DesignCodec` rejects a
 * design with no variants, so the alternative is a design that silently refuses
 * to save.
 */
internal fun seedVariants(
    targets: Set<PokemonCodename>,
    home: PokemonCodename,
): Map<String, DesignVariant> {
    val chosen = targets.ifEmpty { setOf(home) }
    // Iterated in declaration order, not in the set's, so the JSON's key order is
    // the same for everybody regardless of which phone wrote the file.
    return PokemonCodename.entries.filter { it in chosen }.associate { codename ->
        codename.codename to if (codename == home) {
            DesignVariant(frames = listOf(DesignFrame(cells = DesignFrames.blank(codename))))
        } else {
            DesignVariant()
        }
    }
}

// ---------- state ----------

/**
 * The Create tab's data, hoisted out of the tab body by `MainScreen`.
 *
 * Hoisted for two reasons. The pager disposes pages that fall out of its window,
 * so a tab-local `remember` would re-read the designs directory off the disk
 * every time the page came back — and would blank the list to its loading state
 * while it did. And the `+` FAB is not in this subtree at all (it rides beside
 * the nav pill, a sibling of the Scaffold), so [newDesignRequested] is the only
 * channel between the button and the dialog it opens.
 *
 * Every method here suspends and does its I/O on [Dispatchers.IO]. None of them
 * may be called from a non-suspending context.
 *
 * The calling scope is `CreateTab`'s own `rememberCoroutineScope`, so a write
 * *in flight* when the page is disposed is cancelled. That window is bounded and
 * tiny by construction — the FAB and the cards only exist while this page is on
 * screen, and the pager keeps its neighbours composed, so the page cannot be
 * disposed until the user is two tabs away — and the write itself is atomic (see
 * `DesignStore`), so the worst possible outcome is a design that is correctly on
 * disk but missing from the in-memory list until the next process start.
 */
internal class CreateState {

    /**
     * The designs, newest modification first — or **null while the first read is
     * still in flight**, which is a different thing from "there are none". The
     * empty state is one of the loudest screens in the app; showing it for a
     * frame to someone with twenty designs would be a bug.
     */
    var designs by mutableStateOf<List<Design>?>(null)
        private set

    /** Set by the `+` FAB; consumed by `CreateTab`, which owns the dialog. */
    var newDesignRequested by mutableStateOf(false)

    /**
     * Whether the user has ever **landed** on this page, as opposed to this page
     * merely being composed.
     *
     * The distinction is the whole reason this is not a `LaunchedEffect(Unit)` in
     * the tab body: the pager keeps one page composed either side of the viewport
     * (`beyondViewportPageCount = 1`), so `CreateTab` is alive and laid out while
     * the user is still reading the Toys page. Set by `MainScreen` when the pager
     * SETTLES here, and read by the one-off tutorial offer.
     *
     * Not saved: it is derived from where the pager is, and the pager restores
     * itself. Not reset either — one arrival is all anything here asks about.
     */
    var visited by mutableStateOf(false)

    /** The design a delete has been asked for but not yet confirmed. */
    var pendingDelete by mutableStateOf<Design?>(null)

    /** Reads the directory once. Subsequent calls are no-ops. */
    suspend fun loadIfNeeded(store: DesignStore) {
        if (designs == null) reload(store)
    }

    /**
     * Re-reads the directory unconditionally, for when something OUTSIDE this
     * tab has changed a design — today that is exactly one thing, the editor.
     */
    suspend fun refresh(store: DesignStore) = reload(store)

    private suspend fun reload(store: DesignStore) {
        designs = withContext(Dispatchers.IO) { store.list() }
    }

    /**
     * Persists a new, blank design and puts it at the top of the list.
     *
     * [targets] is the answer to the dialog's third question — which phone(s) is
     * this for — and it decides which variants exist at all. See [seedVariants].
     */
    suspend fun create(
        context: Context,
        store: DesignStore,
        name: String,
        kind: DesignKind,
        targets: Set<PokemonCodename>,
    ): Boolean {
        val now = nowIsoUtc()
        val variants = seedVariants(targets, homeCodename())
        val design = Design(
            format = DESIGN_FORMAT,
            formatVersion = DESIGN_FORMAT_VERSION,
            id = withContext(Dispatchers.IO) { store.allocateId() },
            name = name.take(DesignCodec.MAX_NAME_LENGTH),
            // Read ONCE, here, at creation. See [saveRespectingAuthor].
            author = Core.prefs.getString(PrefKeys.CREATOR_NAME, PrefKeys.CREATOR_NAME_DEF)
                .take(DesignCodec.MAX_AUTHOR_LENGTH),
            createdAt = now,
            modifiedAt = now,
            createdWith = createdWith(context),
            kind = kind,
            levels = DEFAULT_LEVELS,
            variants = variants,
        )
        val saved = withContext(Dispatchers.IO) { saveRespectingAuthor(store, design) }
        if (saved) reload(store)
        return saved
    }

    /**
     * Copies a design under a fresh id.
     *
     * The **author is carried over unchanged**, deliberately: a duplicate of
     * somebody else's imported design is still their artwork, and re-stamping it
     * with this phone's creator name would quietly launder the credit. Only the
     * id, the name and `createdAt` are new.
     */
    suspend fun duplicate(store: DesignStore, design: Design, newName: String): Boolean {
        val now = nowIsoUtc()
        val copy = design.copy(
            id = withContext(Dispatchers.IO) { store.allocateId() },
            // Re-capped, because the caller's suffix pushes a maximum-length
            // name over the limit: "x".repeat(64) + " copy" is 69 characters and
            // the codec would refuse to save it.
            name = newName.take(DesignCodec.MAX_NAME_LENGTH),
            createdAt = now,
            modifiedAt = now,
        )
        val saved = withContext(Dispatchers.IO) { saveRespectingAuthor(store, copy) }
        if (saved) reload(store)
        return saved
    }

    suspend fun delete(store: DesignStore, design: Design) {
        withContext(Dispatchers.IO) { store.delete(design.id) }
        reload(store)
    }

    /**
     * Reads a file the user picked, validates it, and stores it as a **new**
     * design.
     *
     * The whole pipeline is `DesignCodec`'s: [readDesign] hands it the stream, so
     * the 1 MB cap is enforced by a bounded read *before* anything is parsed, and
     * a rejection comes back as the codec's own specific sentence, which this
     * returns untouched for the UI to show.
     *
     * The id is reassigned unconditionally — see [importedDesign] for why "only
     * on collision" is not good enough — and the design goes in through
     * [saveRespectingAuthor] like every other write, which for a freshly
     * allocated id finds nothing stored and therefore leaves the original
     * author's name exactly as their file spelled it.
     */
    suspend fun import(context: Context, store: DesignStore, uri: Uri): ImportOutcome {
        val outcome = withContext(Dispatchers.IO) {
            when (val result = readDesign(context, uri)) {
                is DesignCodec.Result.Invalid -> ImportOutcome.Failed(result.reason)
                is DesignCodec.Result.Ok -> {
                    val design = importedDesign(result.design, store.allocateId(), nowIsoUtc())
                    if (saveRespectingAuthor(store, design)) {
                        ImportOutcome.Ok(design)
                    } else {
                        // The file was fine; this phone could not write it. A
                        // different problem, and a different sentence.
                        ImportOutcome.Failed(context.getString(R.string.create_import_save_failed))
                    }
                }
            }
        }
        if (outcome is ImportOutcome.Ok) reload(store)
        return outcome
    }
}

/** What came of an import. [Failed.reason] is always a complete, user-facing sentence. */
internal sealed interface ImportOutcome {
    data class Ok(val design: Design) : ImportOutcome
    data class Failed(val reason: String) : ImportOutcome
}

/**
 * The single write path for designs, and the place `author` immutability is
 * enforced.
 *
 * **`author` is set once, when a design is created, and never again.** The
 * format has no notion of a second author and no way to express "edited by", so
 * silently rewriting the field when the current user's creator name differs
 * would take somebody else's name off their artwork the first time it was
 * opened. Changing `CREATOR_NAME` in Settings therefore affects the NEXT design
 * and none of the existing ones.
 *
 * The rule is enforced here, at save time, rather than by discipline at each
 * call site: it re-reads whatever is already stored under this id and pins the
 * author back to it. For a create or a duplicate the id is freshly allocated, so
 * the read finds nothing and the caller's value stands; for the editor's saves
 * (next phase) it is the stored value that wins, whatever the caller passed.
 *
 * Blocking file I/O. Callers must already be off the main thread.
 *
 * `internal` rather than `private` because the editor saves through it too — it
 * is *the* write path for designs, and a second one would be a second place for
 * the author rule to be forgotten.
 */
internal fun saveRespectingAuthor(store: DesignStore, design: Design): Boolean {
    val stored = store.load(design.id)?.author
    val safe = if (!stored.isNullOrEmpty()) design.copy(author = stored) else design
    return store.save(safe)
}

// ---------- putting a design on the matrix ----------

/**
 * What came of asking for a design to be shown on the Glyph Matrix.
 *
 * Three outcomes rather than a Boolean because the three are genuinely different
 * situations with different things for the user to do next, and the whole point
 * of this action is that it does not leave anybody guessing. "Nothing happened"
 * is not one of them.
 */
internal sealed interface ShowOnMatrix {

    /** It is the toy on the matrix, and the matrix is being driven. Done. */
    data object Shown : ShowOnMatrix

    /**
     * Selected and persisted, but nothing is currently driving the matrix — the
     * key-capture master toggle is off and the system has not bound our toy — so
     * it will appear when one of those changes rather than now. Saying so is the
     * difference between a setting that looks broken and one that is waiting.
     */
    data object ShownWhenEnabled : ShowOnMatrix

    /**
     * Refused: this design has no frames for this phone's panel, so there is
     * literally nothing to put on it. [codename] is the geometry we would have
     * needed (null if the panel is one the format does not know), because
     * "draw the bellsprout size first" is actionable and "it did not work" is
     * not.
     *
     * Refusing beats selecting: pointing the toy at art that cannot be rendered
     * would replace whatever was on the matrix with `CustomScreen`'s placeholder
     * question mark, which is a worse answer than declining and explaining.
     */
    data class NoArt(val codename: PokemonCodename?) : ShowOnMatrix
}

/**
 * Makes [design] the design the `Custom` toy plays **and** makes that toy the one
 * on the matrix — the single action that turns a finished drawing into something
 * the user can actually see.
 *
 * It exists because the two halves were separate and neither was signposted:
 * `CUSTOM_DESIGN_ID` was only reachable through the Custom toy's cog, and making
 * `custom` the current screen only through the toy list, so seeing your own first
 * design meant knowing to do both, in two different tabs, in the right order.
 * That is a route through a manual, not a product.
 *
 * The mechanism is deliberately **not new**: the second half is [selectToy],
 * exactly as the toy list calls it. Everything here is the first half plus the
 * three edge cases that the toy list never has to think about.
 *
 * ## The edge cases, and what is done about each
 *
 * - **No artwork for this panel.** Refused, with the codename that is missing.
 *   See [ShowOnMatrix.NoArt].
 * - **The `Custom` toy switched off in the toy list.** Switched back on. The
 *   request is unambiguous — somebody has just asked for this design to be shown
 *   — and leaving the toy disabled would give them a matrix showing a toy that
 *   the Toys tab claims is off, and that the Essential Key would cycle away from
 *   and never return to. Consistency here is worth writing one boolean the user
 *   did not explicitly ask for.
 * - **Nothing is driving the matrix** (capture off, no bound toy). Selected
 *   anyway — the choice is persisted and correct — and reported as
 *   [ShowOnMatrix.ShownWhenEnabled] rather than claimed as a success. Note that
 *   the arbiter's *owner* is not what is consulted: while the editor is open it
 *   is `PREVIEW`, which would make this look live on a phone where capture is
 *   off. The master toggle and a live toy binding are the honest conditions.
 *
 * Main thread. Prefs writes are `apply`-backed and the two scheduler hops are
 * posts, so nothing here blocks a click.
 */
internal fun showDesignOnMatrix(design: Design): ShowOnMatrix {
    val codename = PokemonCodename.ofSize(Core.glyphLink.size)
    val frames = codename?.let { design.variantFor(it)?.frames }.orEmpty()
    if (frames.isEmpty()) return ShowOnMatrix.NoArt(codename)

    Core.prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, design.id)
    Core.prefs.putBoolean(PrefKeys.screenEnabled(CustomScreen.ID), true)
    selectToy(CustomScreen.ID)
    // selectScreen only *switches*, and switching to the screen that is already
    // active is a no-op — so pointing the toy at a different design while
    // `custom` was already the toy on the matrix would change the pref and leave
    // the previous design playing. This is the same call the editor's save path
    // makes for the same reason: the design file is read in `onActivate`, so a
    // change to which file that is has to re-run it. It is a no-op while the
    // editor holds the matrix, where `endLivePreview` re-activates on the way
    // out instead.
    //
    // Kept even though `Core`'s pref listener now refreshes on a CUSTOM_DESIGN_ID
    // change of its own: the case this call exists for is *showing the same
    // design again* after editing it, where the id did not change and the
    // listener correctly stays quiet. When the id did change the two overlap and
    // the screen activates twice in one scheduler turn — a wasted activation, not
    // a wrong one, and cheaper than either path guessing about the other.
    Core.scheduler.run { Core.screenManager.refreshCurrentScreen() }

    val driven = Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF) ||
        Core.arbiter.owner == SessionArbiter.Owner.TOY
    return if (driven) ShowOnMatrix.Shown else ShowOnMatrix.ShownWhenEnabled
}

/**
 * The sentence each [ShowOnMatrix] deserves, resolved in a composable and usable
 * from a click callback.
 *
 * Same reason the export and import messages above are resolved this way: a
 * `Context.getString` from a callback does not follow configuration changes
 * (lint's `LocalContextGetResourceValueCall`), so the strings are read here and
 * only chosen between later.
 *
 * [afterEditor] picks the one wording that genuinely differs between the two
 * call sites. The editor holds the matrix with hard precedence while it is
 * resumed, so "now showing on the Glyph Matrix" would be a lie told to somebody
 * who is looking straight at the live preview instead; there, the truth is that
 * it starts playing when they leave.
 */
@Composable
internal fun showOnMatrixMessage(afterEditor: Boolean): (ShowOnMatrix) -> String {
    val shown = stringResource(
        if (afterEditor) R.string.create_shown_on_close else R.string.create_shown,
    )
    val whenEnabled = stringResource(R.string.create_shown_needs_capture)
    val noArt = stringResource(R.string.create_show_no_art)
    val noArtFor = stringResource(R.string.create_show_no_art_for)
    // The device NAME, not the format's codename — "draw the arbok artwork first"
    // names nothing the person holding the phone has ever heard of. Resolved for
    // both devices up front for the same reason every other string here is:
    // the callback below is not a composable and may not read resources.
    val deviceNames = PokemonCodename.entries.associateWith { stringResource(it.displayNameRes()) }
    return { result ->
        when (result) {
            is ShowOnMatrix.Shown -> shown
            is ShowOnMatrix.ShownWhenEnabled -> whenEnabled
            is ShowOnMatrix.NoArt ->
                result.codename?.let { noArtFor.format(deviceNames[it]) } ?: noArt
        }
    }
}

/** e.g. `GMTC 2.0.0`, for the format's diagnostic `createdWith` field. */
private fun createdWith(context: Context): String {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    return "GMTC $version".take(DesignCodec.MAX_CREATED_WITH_LENGTH)
}

// ---------- list ----------

/**
 * One design in the list: what it is called, what it is, and what it is made of.
 *
 * A [Card] with its own `onClick` rather than a `ListItem`, because the
 * supporting text here is two lines of different weight and the trailing
 * overflow has to sit beside both.
 */
@Composable
private fun DesignCard(
    design: Design,
    onOpen: () -> Unit,
    onShow: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    placement: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .then(placement)
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(20.dp),
        // Tonal elevation is a visual no-op in this theme (surfaceTint equals
        // the card colour on purpose), so lift, if it were ever wanted here,
        // would have to be shadow. The list is calm; it is not wanted.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    design.name.ifBlank { stringResource(R.string.pref_custom_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    designSummary(design),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    designProvenance(design),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.create_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // First item, above everything: this is the thing a design is
                    // FOR. The editor's app bar is where a first-time user meets
                    // this action (see `DesignEditorActivity`); this is where
                    // somebody who already has a list of designs reaches it for
                    // one they are not currently editing.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_show)) },
                        leadingIcon = { Icon(Icons.Default.Smartphone, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onShow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_duplicate)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        },
                    )
                    // Share sits above Export, and both sit above Delete: sharing
                    // is how a design format spreads at all, so it is a
                    // first-class action here rather than something buried under
                    // the destructive one.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_export)) },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_delete)) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** "Dynamic · 12 frames · by linuxct" — what the design IS. */
@Composable
private fun designSummary(design: Design): String {
    val kind = stringResource(
        if (design.kind == DesignKind.DYNAMIC) R.string.create_kind_dynamic else R.string.create_kind_static,
    )
    // The frame count of the RICHEST variant, not of this device's: a design
    // drawn on a Phone (3) and opened here should still say how much art is in
    // it, rather than reporting the zero frames of a variant nobody has filled.
    val frames = design.variants.values.maxOfOrNull { it.frames.size } ?: 0
    val parts = mutableListOf(kind, pluralStringResource(R.plurals.create_frame_count, frames, frames))
    if (design.author.isNotBlank()) parts += stringResource(R.string.create_by, design.author)
    return parts.joinToString(META_SEPARATOR)
}

/**
 * "Nothing Phone (4a) Pro · Nothing Phone (3) (empty) · 30 Jul 2026" — which
 * devices it has art for, and when it last changed.
 *
 * The variants are named by the **product name**, through
 * [displayNameRes]. They used to be named by their Pokémon codename, on the
 * argument that somebody comparing this row against a JSON file they were sent
 * needs the two to say the same thing — but this row is read by people deciding
 * whether a design will work on their phone, and "arbok" does not answer that
 * question for anybody. The codename stays in the file and in the format spec.
 *
 * An `(empty)` marker is the difference between "there is a blank canvas waiting
 * for that device" and "that device will show the placeholder", which is not
 * something the presence of a key alone can tell you.
 */
@Composable
private fun designProvenance(design: Design): String {
    val present = PokemonCodename.entries.mapNotNull { codename ->
        val variant = design.variantFor(codename) ?: return@mapNotNull null
        val name = stringResource(codename.displayNameRes())
        if (variant.frames.isEmpty()) {
            stringResource(R.string.create_variant_empty, name)
        } else {
            name
        }
    }
    val variants = if (present.isEmpty()) stringResource(R.string.create_no_art) else present.joinToString(META_SEPARATOR)
    return variants + META_SEPARATOR + formatTimestamp(design.modifiedAt)
}

/** Punctuation, not prose — kept out of `strings.xml`, which strips edge spaces. */
private const val META_SEPARATOR = " · "

/**
 * The format's ISO-8601 UTC timestamp as a local, localised date.
 *
 * Falls back to the raw date portion if the string somehow will not parse.
 * `DesignCodec` guarantees it will for anything that reached storage, but this
 * runs on data that came off a disk and a null-safe fallback is cheaper than
 * being wrong about that.
 */
private fun formatTimestamp(iso: String): String = try {
    DATE_FORMAT.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (e: Exception) {
    iso.take(10)
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

// ---------- empty state ----------

/**
 * What every user sees first.
 *
 * An empty design list is not an error and it is not a void — it is the moment
 * to explain what this page is for and hand over a way to start. So: a title, a
 * sentence that says what a design actually does, and a button that opens the
 * very same dialog the `+` does. The button is not redundant with the FAB; it is
 * the discoverable version of it, for the one screen where nobody yet knows what
 * that circle beside the navigation bar is.
 */
@Composable
internal fun CreateEmptyState(onStart: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        // One item, so [SectionCard] gives it all four outer corners — an empty
        // state is a single panel, not a group of rows.
        SectionCard {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.create_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.create_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onStart, colors = filledButtonColors()) {
                        Text(stringResource(R.string.create_empty_action))
                    }
                    // Importing somebody else's design is a perfectly normal
                    // FIRST action — it is how anyone who is handed a file gets
                    // started — so it has to be on the screen that has nothing
                    // on it yet.
                    //
                    // It was a TextButton, deliberately, so it would read as the
                    // quieter of the two ways in. The user reported the same
                    // thing about the list's copy of this button (see
                    // [ImportButton]): a bare label on a card does not look like
                    // a control at all. Both are filled `Button`s now, which is
                    // the only filled button this app uses; the hierarchy that
                    // was worth keeping is carried by the order and the wording,
                    // not by making one of them invisible.
                    //
                    // Both take their colours from [filledButtonColors], and
                    // that is load-bearing rather than tidiness: stacked with
                    // nothing between them, one near-white container above one
                    // grey one read as two unrelated buttons in dark mode. See
                    // that helper.
                    //
                    // This one needs no start inset, unlike [ImportButton]:
                    // this Column CENTRES its children, so the button lines up
                    // with the "start a design" button above it rather than with
                    // any left edge. Giving it padding would push it off that
                    // shared centre line.
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onImport, colors = filledButtonColors()) {
                        Icon(
                            Icons.Default.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.create_import))
                    }
                }
            }
        }
    }
}

/**
 * The list's own way in for a file somebody sent you.
 *
 * In the page rather than a top-bar action: the top bar belongs to
 * `MainActivity` and is shared by all four tabs, and a per-tab action there would
 * be one more thing that has to know which page is showing. Here it scrolls with
 * the content it belongs to.
 *
 * **A filled [Button], not the `TextButton` it was.** As a text button it was a
 * label with no container, floating over the page background above the first
 * design card, and the user reported exactly that: it did not read as a control.
 * `Button` is the only filled button this app uses (the empty state's "start a
 * design", onboarding's continue), so this borrows it rather than introducing a
 * tonal or outlined variant that would appear nowhere else.
 *
 * The start inset is **16 dp, matching [DesignCard]'s own `padding(horizontal =
 * 16.dp)`**, so this button's background edge and the cards' left edge are the
 * same line. It was 12 dp, and that was not wrong while this was a `TextButton`:
 * a text button paints no container, and its internal content padding pushed the
 * LABEL to roughly where the cards' text sits. The moment it gained a filled
 * background the container edge became the thing the eye lines up, and the 4 dp
 * showed. Nothing else contributes to the inset — this `Row` is the only padding
 * in the path (the `LazyColumn`'s `contentPadding` is vertical only, the `Column`
 * around this row is bare, and no modifier is passed to the `Button`), and
 * `Button`'s own `minimumInteractiveComponentSize` only centres a control
 * NARROWER than 48 dp, which this is not.
 */
@Composable
private fun ImportButton(onImport: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)) {
        Button(onClick = onImport, colors = filledButtonColors()) {
            Icon(
                Icons.Default.FileOpen,
                // The label says "Import a design"; describing the icon as well
                // would make a screen reader say it twice.
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.create_import))
        }
    }
}

/**
 * The Create tab's filled-button colours — **a light grey in dark mode, instead
 * of the near-white a default `Button` paints there.**
 *
 * `ButtonDefaults.buttonColors()` fills with `primary`, and this theme's dark
 * `primary` is `#EFF0F7` — the ink colour, deliberately, because in dark mode
 * "most prominent" means "brightest". That is fine for a word of text and wrong
 * for a ~165 × 48 dp slab: the user reported the import button as painful to
 * look at at night, which is exactly what a near-white rectangle on a
 * pure-black page is. (It only became one when the button gained a container; as
 * a `TextButton` that same `#EFF0F7` was three words of label.)
 *
 * So dark mode takes `secondary` / `onSecondary` — `#C5C6CC` on `#191C20`, an
 * existing pair of this scheme's roles rather than a new colour. It is the tone
 * this palette already calls "present but not ink" (section headers, nav
 * captions), about 15 L\* below white, and it still carries 10:1 against its own
 * label, so nothing is lost but the glare.
 *
 * **Light mode is untouched** and stays on the default `primary` — a near-black
 * button on a white page, which was never the complaint and is not a slab of
 * light. The `else` branch returns `ButtonDefaults.buttonColors()`, which is
 * verbatim the default value of `Button`'s own `colors` parameter, so a button
 * that adopts this helper renders identically in light mode to one that does
 * not. There is no single scheme role that is dark in one mode and mid-light in
 * the other, so the choice is made here rather than in `Theme.kt`; adding a role
 * to the theme for this would be the bigger change.
 *
 * ## Who uses it
 *
 * Every filled button on this tab: the list's [ImportButton], and BOTH of the
 * empty state's — "start your first design" as well as its copy of import.
 *
 * The two empty-state buttons are the reason this stopped being import-specific.
 * They sit one directly above the other with nothing between them, so the
 * moment only one of them stepped down the pair read as two different kinds of
 * button rather than two ways into the same page — which is what the user
 * reported. Whatever this returns, they must return the SAME thing; that is a
 * constraint of the layout, not a preference.
 *
 * Deliberately **not** applied app-wide. Onboarding's and the disclosure
 * screen's filled buttons have the same near-white container in dark mode, but
 * nobody has asked for those and they sit on different pages; this is scoped to
 * what was reported.
 */
@Composable
private fun filledButtonColors(): ButtonColors = if (isSystemInDarkTheme()) {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    )
} else {
    ButtonDefaults.buttonColors()
}

// ---------- dialogs ----------

/**
 * Name it, say whether it moves, and say which phone it is for.
 *
 * Three questions, and they are here because all three are awkward to answer
 * afterwards: the name is what the list is sorted and searched by;
 * static-vs-dynamic decides whether the editor shows a timeline at all and is
 * irreversible (`loop` and `keyMode` only mean anything for an animation); and
 * the device choice decides what the editor spends its scarcest resource — the
 * canvas's height — on. Everything else (loop, key mode, the palette) belongs to
 * the editor and is deliberately absent.
 *
 * ## Three decisions without three walls of controls
 *
 * The dialog stays readable because each question is asked with the control that
 * suits its *shape*, not with three of the same thing:
 *
 * - the name is a text field, pre-filled, so the fast path is one tap;
 * - static/dynamic is two mutually exclusive options of one word each — a
 *   segmented row, with a one-line hint underneath that changes with the choice;
 * - the device is three options whose labels are long product names ("Nothing
 *   Phone (4a) Pro"). A third segmented row would clip them or stack each label
 *   over three lines; radio rows hold them on one line each, wrap gracefully at
 *   large font scales, and are what the rest of the app already uses for
 *   pick-one-of-several ([ChoiceRow], as in every per-toy settings dialog). They
 *   are captioned rather than left to be inferred, because "Nothing Phone (3)"
 *   sitting under a Static/Dynamic switch would otherwise read as a third *kind*.
 *
 * It is also **defaulted rather than asked cold**: the selection arrives on the
 * phone the user is holding, so somebody who owns one device answers this
 * question by not reading it.
 *
 * The content scrolls. `AlertDialog`'s text slot is `weight(1f, fill = false)` and
 * adds no scroller of its own, so at the largest accessibility font scales the
 * three groups would be squeezed rather than reachable — and
 * [DIALOG_VERTICAL_MARGIN], which caps how tall the surface may grow, only helps
 * if what is inside it can scroll.
 */
@Composable
private fun NewDesignDialog(
    suggestedName: String,
    defaultTarget: PokemonCodename,
    onDismiss: () -> Unit,
    onCreate: (String, DesignKind, Set<PokemonCodename>) -> Unit,
) {
    // Pre-filled and editable. A generated two-word name means the fast path is
    // "tap Create" and the design still ends up with something you can pick out
    // of a list — which "Untitled 7" does not.
    var name by remember { mutableStateOf(suggestedName) }
    var dynamic by remember { mutableStateOf(false) }
    var target by remember(defaultTarget) { mutableStateOf(setOf(defaultTarget)) }
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_new)) },
        text = {
            NewDesignFields(
                name = name,
                onName = { name = it },
                dynamic = dynamic,
                onDynamic = { dynamic = it },
                target = target,
                onTarget = { target = it },
            )
        },
        confirmButton = {
            TextButton(
                // Disabled rather than silently substituting the suggestion: an
                // emptied field is a decision in progress, not a request for a
                // name we picked. (`colorScheme.error` is INK in this theme, so
                // there is no red-text alternative to reach for here anyway.)
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        name.trim(),
                        if (dynamic) DesignKind.DYNAMIC else DesignKind.STATIC,
                        target,
                    )
                },
            ) {
                Text(stringResource(R.string.create_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_cancel)) }
        },
    )
}

/**
 * The three questions themselves, hoisted out of [NewDesignDialog].
 *
 * Split out for the guided demo (`ui/design/DesignDemo.kt`), which shows these
 * controls in its own window rather than in a platform `Dialog` — a real dialog
 * is a separate window and would sit *above* the tour's spotlight, leaving the
 * captions stranded behind it. The controls are the real ones either way, which
 * is the part that matters: the tour must not grow a second static/dynamic
 * switch that can drift from this one.
 *
 * Fully hoisted state, so the dialog owns its answers and the demo owns its
 * script's.
 */
@Composable
internal fun NewDesignFields(
    name: String,
    onName: (String) -> Unit,
    dynamic: Boolean,
    onDynamic: (Boolean) -> Unit,
    target: Set<PokemonCodename>,
    onTarget: (Set<PokemonCodename>) -> Unit,
) {
    val targetOptions = remember { designTargetOptions() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = name,
            // Capped at the format's own limit rather than validated after the
            // fact: a field that will not accept a 65th character explains
            // itself; a dialog that refuses to close afterwards does not.
            onValueChange = { onName(it.replace('\n', ' ').take(DesignCodec.MAX_NAME_LENGTH)) },
            modifier = Modifier.fillMaxWidth().demoTarget(DemoTarget.DIALOG_NAME),
            label = { Text(stringResource(R.string.create_name_label)) },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        // Pick ONE of two — exactly what MD3 specifies segmented buttons for, and
        // the same control the key tutorial uses for its mode switch. It animates
        // its own selection, hence [NoRipple].
        NoRipple {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !dynamic,
                    onClick = { onDynamic(false) },
                    modifier = Modifier.demoTarget(DemoTarget.DIALOG_KIND, 0),
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.create_kind_static))
                }
                SegmentedButton(
                    selected = dynamic,
                    onClick = { onDynamic(true) },
                    modifier = Modifier.demoTarget(DemoTarget.DIALOG_KIND, 1),
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.create_kind_dynamic))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (dynamic) R.string.create_kind_hint_dynamic else R.string.create_kind_hint_static,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The third question. Captioned, because a row of device names with no
        // heading directly under a Static/Dynamic switch reads as more kinds
        // rather than as a different question.
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.create_target_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.demoTarget(DemoTarget.DIALOG_DEVICE)) {
            targetOptions.forEach { option ->
                ChoiceRow(
                    label = targetLabel(option),
                    selected = option == target,
                    onSelect = { onTarget(option) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Follows the choice, exactly as the kind hint above it does. Picking one
        // size gets the sentence that says the decision is not final — which is
        // what makes a three-way choice at creation time cheap to get wrong, and
        // the escape hatch it names is real (see `DesignSettings`). Picking both
        // gets the rule that still applies to them: the two drawings stay
        // independent.
        Text(
            stringResource(
                if (target.size > 1) {
                    R.string.create_target_hint_both
                } else {
                    R.string.create_target_hint_one
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What one row of [designTargetOptions] is called.
 *
 * A single device is named by its **product name** and never by its codename —
 * the same rule the design cards, the editor's variant switcher and the
 * "nothing to show yet" message follow; see `ui/DeviceNames.kt`. Anything
 * larger than one device is the combined row.
 */
@Composable
private fun targetLabel(option: Set<PokemonCodename>): String =
    option.singleOrNull()?.let { stringResource(it.displayNameRes()) }
        ?: stringResource(R.string.create_target_both)

/**
 * Deleting a design destroys artwork somebody drew by hand, there is no undo and
 * (until the export phase lands) no copy anywhere else. It gets a confirmation,
 * and the confirmation names the design so the wrong one cannot be agreed to by
 * reflex.
 */
@Composable
private fun DeleteDesignDialog(design: Design, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val unnamed = stringResource(R.string.pref_custom_unnamed)
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_delete_title, design.name.ifBlank { unnamed })) },
        text = { Text(stringResource(R.string.create_delete_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.create_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_cancel)) } },
    )
}

/**
 * Why a file the user chose was refused.
 *
 * A dialog, not a toast, and it carries [reason] verbatim from `DesignCodec`.
 * The reasons are genuinely different problems with genuinely different
 * responses — "This design was made with a newer version of the app." means
 * update; "This is not a Glyph design file." means you picked the wrong file;
 * "This design has a frame that is the wrong size for its device." means the
 * file is damaged — and a toast that flashes a truncated sentence would throw
 * away the one thing that makes the validation pipeline useful to a human.
 *
 * (There is no red here: `colorScheme.error` is ink in this theme, so a failure
 * is signalled by what the words say, which is where the work went.)
 */
@Composable
private fun ImportFailedDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_import_failed_title)) },
        text = { Text(reason) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_import_dismiss)) }
        },
    )
}

// ---------- name generator ----------

/**
 * A curated two-word name, e.g. "Slow Ember", "Quiet Comet".
 *
 * Deliberately not "Untitled 7". A counter produces names nobody can tell apart
 * in a list a week later, and it forces a decision at the exact moment the user
 * wants to start drawing. A word pair is memorable, pre-filled, and still
 * editable — and every word here is chosen to suit what this actually is:
 * something small and monochrome glowing on the back of a phone.
 *
 * [taken] holds the names already in use, so the suggestion does not collide
 * with a design sitting three rows down. With 20 x 20 pairs a few attempts is
 * plenty; if they all collide the last one stands, because a duplicate name is
 * legal (ids are what identify a design) and a dialog that failed to open would
 * not be.
 */
internal fun generateDesignName(taken: Set<String>): String {
    repeat(8) {
        val candidate = "${NAME_ADJECTIVES.random()} ${NAME_NOUNS.random()}"
        if (candidate !in taken) return candidate
    }
    return "${NAME_ADJECTIVES.random()} ${NAME_NOUNS.random()}"
}

private val NAME_ADJECTIVES = listOf(
    "Slow", "Quiet", "Soft", "Bright", "Late", "Deep", "Pale", "Warm", "Still", "Sharp",
    "Faint", "Idle", "Lone", "Calm", "Bold", "Low", "Half", "Near", "First", "Last",
)

private val NAME_NOUNS = listOf(
    "Ember", "Comet", "Signal", "Drift", "Echo", "Beacon", "Cinder", "Pulse", "Halo", "Orbit",
    "Spark", "Lantern", "Tide", "Vapour", "Marker", "Lattice", "Current", "Flare", "Shutter", "Relay",
)
