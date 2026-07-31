package space.linuxct.glyphmatrixtoycompat.designs

import space.linuxct.glyphmatrixtoycompat.core.DesignPort
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.Prefs
import space.linuxct.glyphmatrixtoycompat.core.design.Design

/**
 * Resolves [PrefKeys.CUSTOM_DESIGN_ID] to the design the Custom screen should
 * play, reading it through [DesignStore].
 *
 * **Direct Boot.** Every input this needs — the pref store and the design
 * directory — is device-protected, so this is constructible and usable before
 * the first unlock after a reboot. That is not incidental: the AOD toy service
 * is `directBootAware` and `Core.init` runs on process start, so anything
 * credential-encrypted reached from here would fail exactly when the always-on
 * display wants to draw. It is the same reason `App` defers WorkManager.
 *
 * **The fallback is deliberate.** A selected id that no longer names a stored
 * design — the design was deleted, or a restored backup brought files whose ids
 * this phone's pref never knew — resolves to the first design instead of null.
 * The alternative is a placeholder on a phone that visibly has designs on it,
 * which reads as a bug. The settings dialog highlights the same fallback row, so
 * what the matrix plays and what the dialog shows selected never disagree.
 */
class AndroidDesignPort(
    private val prefs: Prefs,
    private val store: DesignStore,
) : DesignPort {

    /**
     * File I/O. Called from `CustomScreen.onActivate` on the scheduler thread —
     * see [DesignPort].
     */
    override fun selected(): Design? {
        val id = prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)
        if (id.isNotEmpty()) store.load(id)?.let { return it }
        // The listing is cached, and the editor in a later phase writes designs
        // from the same process; dropping the cache here costs one directory
        // scan per activation and removes any chance of the toy playing art the
        // user has already replaced.
        store.invalidate()
        return store.list().firstOrNull()
    }
}
