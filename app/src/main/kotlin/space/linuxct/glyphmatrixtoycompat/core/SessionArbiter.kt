package space.linuxct.glyphmatrixtoycompat.core

/**
 * Decides when the single render session runs and on behalf of whom.
 * All sources drive the same ScreenManager, so ownership changes never
 * restart the session — the session runs while ANY source wants it:
 *
 *  - TOY:     the system bound our AOD toy service (highest authority)
 *  - DIRECT:  master toggle on + supported device (accessibility-driven mode)
 *  - PREVIEW: MainActivity is showing an in-app preview
 *
 * One shared GlyphLink lease is held while the session runs; GlyphLink's 3 s
 * teardown grace absorbs quick rebind cycles.
 */
class SessionArbiter(
    private val glyphLink: GlyphLink,
    private val scheduler: RenderScheduler,
    private val screenManager: ScreenManager,
    private val prefs: Prefs,
    private val onSessionChanged: (running: Boolean) -> Unit = {},
) {
    enum class Owner { NONE, TOY, DIRECT, PREVIEW }

    @Volatile
    var owner = Owner.NONE
        private set

    private var toyBound = false
    private var previewActive = false
    private var lease: GlyphLink.Lease? = null

    @Synchronized
    fun setToyBound(bound: Boolean) {
        toyBound = bound
        recompute()
    }

    @Synchronized
    fun setPreviewActive(active: Boolean) {
        previewActive = active
        recompute()
    }

    @Synchronized
    fun onMasterToggleChanged() {
        recompute()
    }

    /** Re-evaluates conditions; used by the key router to revive a dead session. */
    @Synchronized
    fun revive() {
        recompute()
    }

    val sessionShouldRun: Boolean
        get() = owner != Owner.NONE

    private fun masterOn() = prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)

    private fun recompute() {
        val newOwner = when {
            toyBound -> Owner.TOY
            masterOn() && glyphLink.isSupported -> Owner.DIRECT
            previewActive -> Owner.PREVIEW
            else -> Owner.NONE
        }
        val wasRunning = owner != Owner.NONE
        val shouldRun = newOwner != Owner.NONE
        if (newOwner != owner) {
            DebugLog.i(
                C,
                "owner $owner -> $newOwner (toyBound=$toyBound master=${masterOn()} " +
                    "preview=$previewActive supported=${glyphLink.isSupported})",
            )
        }
        owner = newOwner
        if (shouldRun && !wasRunning) {
            if (lease == null) lease = glyphLink.acquire("session")
            scheduler.run { screenManager.startSession() }
            onSessionChanged(true)
        } else if (!shouldRun && wasRunning) {
            scheduler.run { screenManager.stopSession() }
            lease?.release()
            lease = null
            onSessionChanged(false)
        }
    }

    private companion object {
        const val C = "Arbiter"
    }
}
