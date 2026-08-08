package space.linuxct.glyphworks.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.core.design.nowIsoUtc
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.screens.CustomScreen
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.DesignDemoActivity
import space.linuxct.glyphworks.ui.design.DesignEditorActivity
import space.linuxct.glyphworks.ui.design.LocalDemoTargets
import space.linuxct.glyphworks.ui.design.demoTarget
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
 *
 * ## It is a grid, and [listState] is no longer the scroller
 *
 * The designs are laid out in a `LazyVerticalGrid` (three columns on a phone; see
 * [designGridColumns]), which needs a `LazyGridState` and cannot take the
 * `LazyListState` `MainScreen` hoists for every page. The grid's own state lives on
 * [CreateState.gridState] instead — the same hoisting, one object further in, and
 * the one this file owns.
 *
 * [listState] is therefore **unused** and kept only because changing this
 * signature means changing `MainActivity.kt`. The one thing there that read the
 * old state — the settled-page collector deciding whether to spring the collapsing
 * header back open — now reads `createState.gridState`, so a swipe onto a
 * *scrolled* Create tab leaves the header collapsed as it always did. Anything
 * else that ever wants this page's scroll position wants that state, not this
 * parameter, which can only ever answer zero.
 */
@Composable
internal fun CreateTab(
    innerPadding: PaddingValues,
    @Suppress("UNUSED_PARAMETER") listState: LazyListState,
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
    // Snapshot state, unlike the counter beside it, because the preview clock
    // below has to STOP when the app is paused — a grid of looping discs is
    // exactly the sort of thing that must not keep asking for frames behind a
    // lock screen.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(state) {
        if (resumes[0]++ > 0) scope.launch { state.refresh(store) }
        resumed = true
        onPauseOrDispose { resumed = false }
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
                        state.gridState.animateScrollToItem(0)
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

    // ---------- the preview clock ----------

    // ONE clock for the whole grid. See ui/DesignPreview.kt for why that is not
    // negotiable and for the three other things that keep this cheap.
    val clock = remember { PreviewClock() }

    /**
     * Whether this page is actually on screen.
     *
     * It has to be asked, because "composed" is not "visible" here: the pager
     * keeps one page composed either side of the viewport
     * (`beyondViewportPageCount = 1`), so this whole grid — cells, players and
     * all — is alive and laid out while the user is reading the Toys tab. Without
     * this the previews would loop for a page nobody can see.
     *
     * Answered from the layout itself rather than from a flag `MainScreen` would
     * have to set: `boundsInWindow()` is already clipped by every ancestor, and
     * the pager clips its viewport, so a page parked outside it reports an empty
     * rectangle. One lambda on one node, run when the grid is placed.
     */
    var onScreen by remember { mutableStateOf(false) }

    // The frame loop, and the only one on this tab. It exists exactly while all
    // three conditions hold — the app is resumed, the page is on screen, and at
    // least one visible card has more than one frame — and `collectLatest`
    // cancels it the instant any of them stops being true. A tab of static
    // designs therefore issues no frames at all, which is the same guarantee the
    // editor makes about an idle canvas.
    LaunchedEffect(clock) {
        snapshotFlow { resumed && onScreen && clock.animating }.collectLatest { run ->
            if (!run) return@collectLatest
            while (true) {
                withFrameMillis { clock.advance(it) }
            }
        }
    }

    // Three on a phone, more on a tablet. See [designGridColumns].
    val columns = designGridColumns(LocalWindowInfo.current.containerDpSize.width)

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onScreen = !it.boundsInWindow().isEmpty },
        state = state.gridState,
        // Horizontally ZERO, deliberately. The full-width items below — the hint,
        // the import button, the empty state — carry their own insets and are
        // shared with other tabs, so a content padding here would double theirs.
        // The cells inset themselves instead ([designCellPadding]), which is also
        // the only way to make the outer margin and the gutter differ.
        //
        // The bottom is MANDATORY: the nav pill is an overlay, so without it the
        // last row sits underneath it and cannot be scrolled clear. Same
        // arithmetic as every other tab.
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE,
        ),
        verticalArrangement = Arrangement.spacedBy(DESIGN_GRID_GUTTER),
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

            designs.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                CreateEmptyState(
                    onStart = { state.newDesignRequested = true },
                    onImport = onImport,
                )
            }

            else -> {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
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
                //
                // Indexed because a cell has to know WHICH COLUMN it is in to
                // inset itself; see [designCellPadding]. The hint above spans a
                // whole line, so the designs start on a fresh one and the column
                // is simply the index modulo the count.
                itemsIndexed(designs, key = { _, design -> design.id }) { index, design ->
                    val copyName =
                        stringResource(R.string.create_copy_suffix, design.name.ifBlank { unnamed })
                    // Keyed on the id AND the timestamp: a design that comes back
                    // from the editor with new art, or from a rename with a new
                    // stamp, must re-sample and re-decode rather than keep
                    // yesterday's pixels.
                    val art = remember(design.id, design.modifiedAt) {
                        designPreviewArt(design, PokemonCodename.ofSize(Core.glyphLink.size))
                    }
                    val player = rememberPreviewPlayer(art, clock)
                    DesignCard(
                        design = design,
                        art = art,
                        player = player,
                        onRename = { state.pendingRename = design },
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
                        placement = Modifier
                            .animateItem(
                                // Same list-motion rules as ToysTab: the slide to
                                // a new slot is a POSITION change → spatial, while
                                // the fades are alpha → effects, which must never
                                // bounce. animateItem()'s own defaults are
                                // foundation's, not MD3's, so all three are passed
                                // explicitly.
                                fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            )
                            .padding(designCellPadding(index % columns, columns)),
                        // The other half of the same arithmetic: the width this
                        // column did NOT hand back to the grid is handed to the
                        // disc's margins instead, so every card is the same
                        // height. See [designDiscSideInset].
                        discSideInset = designDiscSideInset(index % columns, columns),
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
                        state.gridState.animateScrollToItem(0)
                    } else {
                        Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    state.pendingRename?.let { design ->
        RenameDesignDialog(
            design = design,
            onDismiss = { state.pendingRename = null },
            onRename = { newName ->
                state.pendingRename = null
                scope.launch {
                    val ok = state.rename(store, design.id, newName)
                    if (!ok) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
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

    /**
     * The grid's scroll position, hoisted here for exactly the reason the tab's
     * old `LazyListState` was hoisted into `MainScreen`: the pager destroys a page
     * that falls out of its window, and a cell-local state would send the tab back
     * to the top every time the user came back from two tabs away.
     *
     * It lives on this object rather than beside `MainScreen`'s other scroll
     * states because `LazyVerticalGrid` needs a [LazyGridState] and the tab owns
     * the decision to be a grid at all. The `listState` parameter `CreateTab`
     * still takes is `MainScreen`'s, and is now unused by the list itself — see
     * that function.
     *
     * Constructed rather than `rememberSaveable`d, which costs this one thing: the
     * position is carried across a page disposal and a process-wide re-entry to
     * the tab, but not across a configuration change. The column count changes on
     * a rotation anyway, so a restored *item index* would land somewhere different
     * regardless.
     */
    val gridState = LazyGridState()

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

    /** The design the rename dialog is open for, or null when it is closed. */
    var pendingRename by mutableStateOf<Design?>(null)

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

    /**
     * Gives a design a new name and nothing else.
     *
     * **The stored design is re-read rather than the list's copy being written
     * back**, which is the same discipline the export path follows and for the
     * same reason: this list is a cached index, and a design that was edited since
     * the index was built would otherwise have its artwork rolled back by a
     * rename. The only fields that change are `name` and `modifiedAt`; `id`,
     * `createdAt` and `kind` come from the file untouched, and `author` is pinned
     * by [saveRespectingAuthor] whatever anybody passes.
     *
     * `modifiedAt` IS restamped, deliberately. The name is part of the design, the
     * list is sorted by that field, and a rename that left the design where it was
     * would be the one edit in this app that does not surface.
     *
     * [name] is expected to have been through [renamedName] already; it is capped
     * again here because the format's limit is the codec's rule, not the dialog's.
     */
    suspend fun rename(store: DesignStore, id: String, name: String): Boolean {
        val saved = withContext(Dispatchers.IO) {
            val stored = store.load(id) ?: return@withContext false
            val renamed = stored.copy(
                name = name.take(DesignCodec.MAX_NAME_LENGTH),
                modifiedAt = nowIsoUtc(),
            )
            saveRespectingAuthor(store, renamed)
        }
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

/** e.g. `GlyphWorks 2.0.0`, for the format's diagnostic `createdWith` field. */
private fun createdWith(context: Context): String {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    return "GlyphWorks $version".take(DesignCodec.MAX_CREATED_WITH_LENGTH)
}

// ---------- grid ----------

/**
 * One cell's own inset, given which column it landed in: the full margin on
 * whichever side faces the window edge, and half a gutter on any side that faces
 * another cell.
 *
 * Applied by the cells themselves rather than by the grid's `contentPadding` and
 * `horizontalArrangement`, so that the full-width rows above them — the hint, the
 * import button, the empty state, all of which carry their own insets and are
 * shared with other tabs — keep the exact margins they had as a list.
 *
 * The numbers, and the correction that stops the resulting difference in cell
 * widths from becoming a difference in card *heights*, are in `DesignPreview.kt`;
 * see [designDiscSideInset].
 */
private fun designCellPadding(column: Int, columns: Int): PaddingValues = PaddingValues(
    start = if (column == 0) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2,
    end = if (column == columns - 1) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2,
)

/**
 * A design card's corner radius — and, doubled, the diameter of the overflow
 * button's state layer.
 *
 * The two are one number on purpose. A circle of radius r, tangent to both edges
 * of a corner rounded to r, has its centre exactly on that corner's arc centre and
 * traces the arc itself: the pressed state layer *is* the card's corner. Written
 * as two constants they would drift the first time either was adjusted, and the
 * failure would be a state layer that misses the corner by a couple of dp — the
 * kind of thing nobody reports and everybody sees. See `DesignCard`.
 */
private val DESIGN_CARD_CORNER = 20.dp
private val DESIGN_CARD_MENU_BUTTON = DESIGN_CARD_CORNER * 2

/**
 * The overflow's touch target: the minimum, flush with the card's corner so that
 * all of it lands inside the card, which clips.
 */
private val DESIGN_CARD_MENU_TARGET = 48.dp

/**
 * One design in the grid: **its artwork, moving**, its name, and two lines of
 * supporting text.
 *
 * ## What a three-up card can and cannot say
 *
 * The grid is three columns on a phone, which leaves a cell about 106 dp wide on a
 * 360 dp window. That is the whole design constraint here, and it is not
 * negotiable by shrinking type: the row this used to be gave the text ~300 dp and
 * three lines, and none of `designSummary` ("Dynamic · 12 frames · by linuxct") or
 * `designProvenance` ("Nothing Phone (4a) Pro · Nothing Phone (3) (empty) ·
 * 31 Jul 2026") fits across a third of a phone without wrapping into a paragraph.
 * A wall of wrapped grey text under every card would defeat the point of showing
 * the artwork at all.
 *
 * So the card carries four things, in this order of importance:
 *
 * 1. **The preview**, which is the reason the grid exists and is deliberately the
 *    dominant element — a circle the full width of the cell less its inset, so it
 *    grows with the column count rather than sitting at a fixed size.
 * 2. **The name**, on ONE line with an ellipsis. Wrapping it would let a long name
 *    push every card in the row taller for no gain; the full name is a tap away in
 *    the editor's title, and is read out in full by a screen reader (below).
 * 3. **How much art is in it** — the frame count, or "no artwork yet" when there is
 *    none to count. Both are existing strings (`create_frame_count`,
 *    `create_no_art`) used with their existing wording. See [designMeta].
 * 4. **Whose it is, or when it last changed** — the one fact that differs between
 *    two cards that are otherwise the same sentence. See [designCredit].
 *
 * Three single-line texts, and no more, because the card's height is fixed by
 * construction and every one of them is a line that could unfix it; see
 * [designDiscSideInset], which is the other half of that guarantee.
 *
 * ## What was dropped, and why each is affordable
 *
 * - **Static / Dynamic** — the preview *is* the answer now. A card that is moving
 *   is a dynamic design; a card that is still is a static one. It was worth a word
 *   in a list of text rows and is redundant beside a running animation.
 * - **The device list and the `(empty)` marker** — the preview covers the case
 *   that actually confuses people (a design with no art for this phone draws a
 *   bare disc, and the supporting line then reads "no artwork yet"), and the full
 *   list is one line in Design settings.
 * - **The date, on a design that has an author** — the two share line four and the
 *   author wins it, because a date is recoverable from the sort order and a name
 *   is not recoverable from anything on screen.
 *
 * **Nothing is dropped for a screen reader.** The text block is given
 * `clearAndSetSemantics` with the name, [designSummary] and [designProvenance]
 * spoken in full, so the helpers stay live, stay the single source of that wording,
 * and TalkBack still hears everything the row used to show.
 *
 * ## Where the overflow went, and why it is not beside the text
 *
 * Top-right of the cell, over the corner a circle inscribed in a square leaves
 * empty — **not** in the text block below, which is the other place it could
 * plausibly live and which was measured before it was ruled out.
 *
 * The text block is the card less 12 dp of padding either side: 97 dp on an outer
 * card of the 411 dp phone's three-column grid (121 dp wide), 105 dp in the middle
 * column. That is already narrow enough to ellipsise a name like "Pokémon Bells…"
 * at fourteen characters. A 48 dp touch target in that row costs 48 dp of *layout*
 * width however small the glyph inside it is drawn — `minimumInteractiveComponentSize`
 * measures, it does not overlap — so even with the end padding cut from 12 dp to 4
 * the name would be left **57 dp**, about eight characters of `titleSmall`. Losing
 * 41% of the name to move a button is not a trade this card can make, and it does
 * not improve on a wider window: the button is a fixed dp and the cell is not.
 *
 * ## How the button is built, and why it is not an `IconButton`
 *
 * The reason is geometric. `IconButton` is a 40 dp state layer centred in the
 * 48 dp box `minimumInteractiveComponentSize` reserves for it, so flush in the
 * card's corner it spends its 4 dp of slack on the corner side and leaves the
 * glyph 24 dp in from each edge — as close to the disc as the button can put it.
 * The same two sizes with the state layer pushed *into* the corner leave the glyph
 * 20 dp in, and 4 dp on each axis is 5.7 dp diagonally: two thirds of the
 * clearance this card gained. It cannot be had by offsetting an `IconButton`
 * instead, because the `Card` clips — a target hanging 4 dp over the edge is 44 dp
 * of live target, not 48, and the touch target is not the thing to spend here.
 *
 * So the two jobs are split across two nodes, which is what `Modifier.indication`
 * exists for. The outer 48 dp box is flush with the card's corner and carries the
 * click: every one of its 2 304 dp² is inside the card and hittable. The inner
 * 40 dp box carries the ripple and the glyph, aligned to that corner — and at
 * 20 dp of radius it is *exactly* the arc of the card's own 20 dp corner, tangent
 * to both edges, so a press lights that corner up as a quarter-round rather than
 * as a circle floating near it. That is why both sizes are written as one
 * constant, [DESIGN_CARD_CORNER], and not as two loose numbers that could drift.
 *
 * What the two halves come to, glyph ink to circle edge, at three columns:
 * **0.4 dp before, 8.5 dp after** on the 411 dp phone — 5.6 dp of that from the
 * corner and 2.5 dp from the disc's top inset — and 11.6 dp in the middle column,
 * which insets its disc 22 dp and was never the crowded one. On a 360 dp window
 * the same two changes take it from -3.0 dp, an actual overlap, to 5.0 dp.
 */
@Composable
private fun DesignCard(
    design: Design,
    art: DesignPreviewArt,
    player: PreviewPlayer,
    onOpen: () -> Unit,
    onShow: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    placement: Modifier,
    discSideInset: Dp,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = design.name.ifBlank { stringResource(R.string.pref_custom_unnamed) }
    // All three lines at once, built once per card. See [rememberDesignCardText]:
    // the two visible ones and the spoken one share a timestamp that costs an
    // `Instant.parse` and a localised formatter, and they used to be computed
    // separately on every composition.
    val text = rememberDesignCardText(design, name)
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .then(placement),
        shape = RoundedCornerShape(DESIGN_CARD_CORNER),
        // Tonal elevation is a visual no-op in this theme (surfaceTint equals
        // the card colour on purpose), so lift, if it were ever wanted here,
        // would have to be shadow. The grid is calm; it is not wanted.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            DesignPreviewDisc(
                art = art,
                player = player,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Inset first, THEN filled and squared: the disc is whatever
                    // the cell is wide less this margin, so it scales with the
                    // column count instead of being a number that is right on one
                    // window and wrong on the next. 68 dp on a 360 dp phone,
                    // 85 dp on the 411 dp one this app is built for, and larger
                    // again on anything wider.
                    //
                    // [discSideInset] is 18 dp plus this column's correction, and
                    // is what makes every card in the grid exactly as tall as
                    // every other; see [designDiscSideInset] for what goes wrong
                    // without it.
                    //
                    // The inset is also half of what keeps the overflow button
                    // below off the artwork — the top one was 14 dp and is 18,
                    // which is where 2.5 dp of the card's ~8.5 dp of diagonal
                    // clearance comes from. The other 5.6 dp is the button
                    // sitting in the corner rather than 4 dp inside it. Both
                    // numbers, and the measurement that produced them, are in
                    // [DESIGN_DISC_TOP_INSET].
                    .padding(
                        start = discSideInset,
                        end = discSideInset,
                        top = DESIGN_DISC_TOP_INSET,
                    )
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            // The touch target and the thing the user can see are two different
            // rectangles here, and they have to be, because they want opposite
            // things: the target wants to be 48 dp and entirely inside a card that
            // clips, and the glyph wants to be as far from the disc as the corner
            // allows. One `IconButton` can only satisfy them by centring the
            // second in the first, which is the arrangement that put the dots
            // 0.4 dp off the artwork. See this composable's KDoc for the
            // arithmetic and for what the split buys.
            //
            // One MutableInteractionSource per card, allocated in `remember` and
            // therefore not on the recomposition path — the same object
            // `IconButton` would have created lazily inside `clickable`.
            val menuInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(DESIGN_CARD_MENU_TARGET)
                    .clickable(
                        interactionSource = menuInteraction,
                        // The press is drawn by the smaller box below, not here:
                        // a bounded ripple on this one would be a 48 dp square.
                        indication = null,
                        role = Role.Button,
                        onClick = { menuOpen = true },
                    ),
                // The glyph's 24 dp of icon and the ripple's 40 dp of circle both
                // ride into the corner; the 8 dp of slack this leaves is on the
                // disc's side, which is the side that needed it.
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(DESIGN_CARD_MENU_BUTTON)
                        .clip(CircleShape)
                        // `ripple()` rather than an inherited indication, so this
                        // still answers [NoRipple] — which the app applies to
                        // toggles and not to icon buttons, so here it stays on.
                        .indication(menuInteraction, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.create_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    // **16 dp, because 4 dp is a stale token and not a decision.**
                    //
                    // `MenuDefaults.shape` resolves to `MenuTokens.ContainerShape`
                    // = `CornerExtraSmall` = 4 dp, and that token file is stamped
                    // `VERSION: v0_210` — the pre-expressive baseline. The current
                    // one ships in the SAME library: `SegmentedMenuTokens`
                    // (`VERSION: 24.1.2`) puts a menu container at `CornerLarge` =
                    // 16 dp, and as of material3 1.5.0-alpha23 nothing wires it to
                    // `DropdownMenu`. So this is not a bigger radius because
                    // bigger looks nicer; it is the value the spec already holds,
                    // applied by hand because the default has not caught up.
                    //
                    // 4 dp on a 280 dp-wide, 300 dp-tall slab is visually a square
                    // — which is exactly the "sharp edges" this drew — while every
                    // other floating surface in this app is 20 dp or more (the
                    // card behind it, the nav pill, the dialogs). A menu at 4 dp
                    // does not read as a member of the same set of objects.
                    shape = MaterialTheme.shapes.large,
                ) {
                    // First item, above everything: this is the thing a design is
                    // FOR. The editor's app bar is where a first-time user meets
                    // this action (see `DesignEditorActivity`); this is where
                    // somebody who already has a list of designs reaches it for
                    // one they are not currently editing.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_show)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Smartphone,
                                contentDescription = null,
                                // 20 dp, the size `SegmentedMenuTokens` gives a
                                // menu item's leading icon and the size
                                // `MenuDefaults.LeadingIconSize` exposes —
                                // `DropdownMenuItem` reserves the 24 dp box but
                                // never sizes what goes in it, so an `Icon` left
                                // alone draws its own 24 dp default and every
                                // item in the list runs one step heavy.
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onShow()
                        },
                    )
                    // Second, and above Duplicate: renaming is the one edit here
                    // that is not destructive and not a copy, and it is the answer
                    // to a name that a three-up card has to ellipsise.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_rename)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DriveFileRenameOutline,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_duplicate)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
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
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_export)) },
                        // `Download`, not `SaveAlt`. Both are an arrow going
                        // down, but `SaveAlt` is the legacy set's *alternative*
                        // save glyph — the arrow drops into a three-sided tray,
                        // which is the 2014 "save to device" pictogram. The
                        // current one is a plain arrow onto a baseline, which is
                        // what every Android surface that writes a file now shows.
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                // One node, one sentence: the two visible lines are an
                // abbreviation forced by the cell width, and a screen reader has
                // no cell width. See this composable's KDoc.
                .clearAndSetSemantics { contentDescription = text.spoken },
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text.meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text.credit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------- a card's words ----------

/**
 * Everything one card says: the two supporting lines it shows, and the sentence a
 * screen reader hears instead of them.
 *
 * The three are one object because they are computed together — see
 * [designCardText], where the timestamp two of them need is parsed once rather
 * than twice — and because that makes the memo in [rememberDesignCardText] one
 * `remember` rather than three.
 */
@Immutable
internal class DesignCardText(
    /** How much art is in the design, or that there is none. */
    val meta: String,
    /** Who made it, or when it last changed. */
    val credit: String,
    /** The name, the summary and the provenance, in full, for TalkBack. */
    val spoken: String,
)

/**
 * The handful of strings a card's text is assembled from, already resolved.
 *
 * ## Why the lookups are parameters rather than `stringResource` calls
 *
 * Every one of the four helpers below used to be `@Composable` purely so that it
 * could call `stringResource`, which meant the whole block of text had to be
 * rebuilt inside every composition of every card — and *this* text is expensive in
 * a way text usually is not: two of the four reach [formatTimestamp], which is an
 * `Instant.parse` and a localised `DateTimeFormatter`. Fifteen cards composing
 * during a swipe onto the tab paid thirty of those.
 *
 * Taking the strings as data instead does three things at once. The assembly
 * becomes a pure function, so it can go inside a `remember` and stop running on
 * recomposition at all. It becomes reachable from a plain JVM test, which is what
 * lets `DesignCardTextTest` assert that the memoised wording is the wording the
 * card always had. And the resource *lookups* are hoisted to once per card rather
 * than once per composition.
 *
 * The wording itself is untouched: every field below is the exact resource the
 * corresponding `stringResource` call used, so there is still one spelling of each
 * of these facts in the app.
 */
internal class DesignCardStrings(
    val noArt: String,
    val kindStatic: String,
    val kindDynamic: String,
    val frameCount: (Int) -> String,
    val by: (String) -> String,
    val variantEmpty: (String) -> String,
    val deviceName: (PokemonCodename) -> String,
)

/** The app's own resources behind [DesignCardStrings], resolved once per card. */
@Composable
private fun rememberDesignCardStrings(): DesignCardStrings {
    // The Resources instance is the key as well as the source: a configuration
    // change hands out a new one, which is what re-resolves the wording. This is
    // the very local `stringResource` reads, so the memo invalidates exactly when
    // a `stringResource` call would have recomposed.
    val resources = LocalResources.current
    return remember(resources) {
        DesignCardStrings(
            noArt = resources.getString(R.string.create_no_art),
            kindStatic = resources.getString(R.string.create_kind_static),
            kindDynamic = resources.getString(R.string.create_kind_dynamic),
            frameCount = { n -> resources.getQuantityString(R.plurals.create_frame_count, n, n) },
            by = { author -> resources.getString(R.string.create_by, author) },
            variantEmpty = { name -> resources.getString(R.string.create_variant_empty, name) },
            deviceName = { codename -> resources.getString(codename.displayNameRes()) },
        )
    }
}

/**
 * One card's [DesignCardText], built once and kept until the design changes.
 *
 * **Keyed on the id, the timestamp and the displayed name**, which is the key the
 * card's artwork already uses one screen up (see the grid's `itemsIndexed`): a
 * design that comes back from the editor, or from a rename, carries a new
 * `modifiedAt`, and anything else that could change these words — the author, the
 * kind, the frame counts — can only change through an edit that stamps it. The
 * name is in the key as well because a blank one falls back to a localised string
 * rather than to anything the design carries.
 */
@Composable
private fun rememberDesignCardText(design: Design, name: String): DesignCardText {
    val strings = rememberDesignCardStrings()
    return remember(design.id, design.modifiedAt, name, strings) {
        designCardText(design, name, strings)
    }
}

/**
 * All three of a card's strings, from a design and the resolved wording.
 *
 * Pure, and the one place the card's words are assembled. The **timestamp is
 * formatted once** here and shared between the credit line and the provenance
 * sentence; computed separately, as they were, a card with no author paid for two
 * `Instant.parse` calls and two localised formats to print the same date twice.
 */
internal fun designCardText(design: Design, name: String, strings: DesignCardStrings): DesignCardText {
    val frames = designFrameCount(design)
    val date = formatTimestamp(design.modifiedAt)
    return DesignCardText(
        meta = designMeta(frames, strings),
        credit = designCredit(design, date, strings),
        // Everything the cell had to leave out, kept whole for anybody who is not
        // reading it. Built from the very same helpers the row used, so there is
        // no second spelling of a design's summary to drift.
        spoken = name + META_SEPARATOR + designSummary(design, frames, strings) +
            META_SEPARATOR + designProvenance(design, date, strings),
    )
}

/**
 * How much art is in a design: the frame count of the **richest** variant.
 *
 * A design drawn on a Phone (3) and opened here should still say how much art is
 * in it, rather than reporting the zero frames of a variant nobody has filled.
 * Zero everywhere is the case where the card is showing an empty disc.
 */
internal fun designFrameCount(design: Design): Int =
    design.variants.values.maxOfOrNull { it.frames.size } ?: 0

/**
 * The one line of supporting text a three-up cell has room for: how much art is
 * in this design, or that there is none.
 *
 * The frame count is the RICHEST variant's, exactly as [designSummary] reports it
 * and for the same reason — see [designFrameCount]. Zero everywhere is the case
 * where the card is showing an empty disc, and `create_no_art` is the sentence
 * this app already uses for it.
 */
private fun designMeta(frames: Int, strings: DesignCardStrings): String =
    if (frames == 0) strings.noArt else strings.frameCount(frames)

/**
 * The card's second supporting line: **who made it, or when it last changed**.
 *
 * ## Why there is a second line at all
 *
 * The first pass at this card carried the name and the frame count and nothing
 * else, on the argument that a three-up cell has room for one line and the author
 * was never what anybody scanned a grid for. The first person to use it disagreed:
 * beside a wall of small discs, "Dynamic · 46 frames" is the same sentence on every
 * card, and the thing that tells two of them apart — who sent you this one, or when
 * you last touched it — had been dropped. A card with no distinguishing fact on it
 * is not a calmer card, it is a less useful one.
 *
 * ## What it says
 *
 * - **The author, when the design has one** — `create_by`, the very string
 *   [designSummary] ends with, so "by linuxct" is spelled one way in this app. It
 *   is only ever set on an imported or duplicated design, which is exactly the case
 *   where it is the most useful fact on the card: it is what distinguishes a design
 *   somebody sent you from one you drew.
 * - **The modified date otherwise** — [formatTimestamp], the same localised medium
 *   date [designProvenance] ends with. The grid is sorted by it, so it is not new
 *   information so much as a *scale*: "3 Jul" against "yesterday's date" says how
 *   far down the list you have scrolled, which position alone cannot.
 *
 * One line, `maxLines = 1`, ellipsised, like the two above it — the card's height
 * is fixed by construction ([designDiscSideInset]) and this must not be what
 * unfixes it. And nothing here is new wording: both halves are helpers the row
 * layout already used, so there is still exactly one spelling of each of these
 * facts in the app.
 *
 * [date] is passed in rather than formatted here so that the one card that needs
 * this date twice — in this line and in the spoken provenance — parses it once;
 * see [designCardText].
 */
private fun designCredit(design: Design, date: String, strings: DesignCardStrings): String =
    if (design.author.isNotBlank()) strings.by(design.author) else date

/** "Dynamic · 12 frames · by linuxct" — what the design IS. */
private fun designSummary(design: Design, frames: Int, strings: DesignCardStrings): String {
    val kind = if (design.kind == DesignKind.DYNAMIC) strings.kindDynamic else strings.kindStatic
    // The frame count is the RICHEST variant's, not this device's — see
    // [designFrameCount].
    val parts = mutableListOf(kind, strings.frameCount(frames))
    if (design.author.isNotBlank()) parts += strings.by(design.author)
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
private fun designProvenance(design: Design, date: String, strings: DesignCardStrings): String {
    val present = PokemonCodename.entries.mapNotNull { codename ->
        val variant = design.variantFor(codename) ?: return@mapNotNull null
        val name = strings.deviceName(codename)
        if (variant.frames.isEmpty()) strings.variantEmpty(name) else name
    }
    val variants = if (present.isEmpty()) strings.noArt else present.joinToString(META_SEPARATOR)
    return variants + META_SEPARATOR + date
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
 *
 * **This is the expensive line on a design card**, and the reason [designCardText]
 * exists: an `Instant.parse` plus a localised `DateTimeFormatter` is tens of
 * microseconds, it used to run twice per card per composition, and a swipe onto
 * the Create tab composes a whole screenful of cards at once.
 */
internal fun formatTimestamp(iso: String): String = try {
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
                            Icons.Outlined.FileOpen,
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
                Icons.Outlined.FileOpen,
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
        DesignNameField(
            name = name,
            onName = onName,
            modifier = Modifier.fillMaxWidth(),
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
        Column {
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
 * The field a design is named in — **one field, used by both dialogs that name
 * one**, and by the guided tour through [NewDesignFields].
 *
 * It exists because rename came second. Writing a second `OutlinedTextField` for
 * it would have meant two places holding the same three rules (no newlines,
 * `DesignCodec.MAX_NAME_LENGTH`, single line) and two places to forget one of them
 * the next time the format's limit moves.
 *
 * The cap is applied *as the user types* rather than validated afterwards: a field
 * that will not accept a 65th character explains itself; a dialog that refuses to
 * close does not.
 */
@Composable
private fun DesignNameField(name: String, onName: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = name,
        onValueChange = { onName(it.replace('\n', ' ').take(DesignCodec.MAX_NAME_LENGTH)) },
        modifier = modifier,
        label = { Text(stringResource(R.string.create_name_label)) },
        singleLine = true,
    )
}

/**
 * Rename, and only rename.
 *
 * Modelled on [NewDesignDialog] and sharing its field ([DesignNameField]) rather
 * than restating it, because the two are asking the same question — what is this
 * design called — and the answer has to obey the same rules in both. The other two
 * questions the new-design dialog asks are deliberately absent: static-vs-dynamic
 * is irreversible and belongs to creation, and which panels a design is drawn for
 * is answered by Design settings inside the editor.
 *
 * The confirm button is driven entirely by [renamedName], so the enabled state and
 * the value that gets saved can never disagree — a blank field, a field holding
 * only spaces, and a field still holding the name it started with are all the same
 * thing here: nothing to save.
 */
@Composable
private fun RenameDesignDialog(design: Design, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    // Keyed on the id so that the dialog reopening for a DIFFERENT design starts
    // from that design's name rather than from the last one's.
    var typed by remember(design.id) { mutableStateOf(design.name) }
    val renamed = renamedName(design.name, typed)
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_rename_title)) },
        text = {
            DesignNameField(
                name = typed,
                onName = { typed = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = renamed != null, onClick = { renamed?.let(onRename) }) {
                Text(stringResource(R.string.create_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.create_cancel)) }
        },
    )
}

/**
 * The name a rename would actually save, or **null when there is nothing to
 * save**.
 *
 * One function for the whole of rename's validation, because "is this legal" and
 * "what gets written" are the same question and answering them in two places is
 * how a disabled button and a save path drift apart.
 *
 * The order of the three operations is load-bearing. Newlines go first (a paste
 * can carry one even though the field is `singleLine`); the cap goes second, so
 * the result is guaranteed to satisfy `DesignCodec.MAX_NAME_LENGTH` however long
 * the paste was; the trim goes last, so a cap that lands mid-space cannot leave a
 * trailing one — and so that a field holding nothing but spaces collapses to empty
 * rather than to a design named "   ".
 *
 * Refusing an *unchanged* name is not pedantry: the save restamps `modifiedAt`,
 * which reorders the grid, so a rename that changed nothing would still visibly
 * move the design to the top.
 */
internal fun renamedName(current: String, typed: String): String? {
    val cleaned = typed.replace('\n', ' ').take(DesignCodec.MAX_NAME_LENGTH).trim()
    if (cleaned.isEmpty()) return null
    if (cleaned == current) return null
    return cleaned
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
