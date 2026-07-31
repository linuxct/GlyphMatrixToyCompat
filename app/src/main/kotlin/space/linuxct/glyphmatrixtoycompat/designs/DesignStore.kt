package space.linuxct.glyphmatrixtoycompat.designs

import android.content.Context
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.newDesignId
import java.io.File
import java.io.FileOutputStream

/**
 * On-disk storage for user designs. This is the app's **first** app-owned file
 * I/O — everything else persists through SharedPreferences — so the choices are
 * documented here rather than assumed.
 *
 * **Device-protected storage.** Designs live under
 * `createDeviceProtectedStorageContext().filesDir/designs/`, not the default
 * credential-encrypted directory, for the same reason `util/AndroidPrefs` puts
 * settings there: `AodToyService` and `EssentialKeyService` are
 * `directBootAware` and run *before the first unlock after a reboot*. A design
 * stored credential-encrypted would be unreadable exactly when the always-on
 * display wants to draw it, and the toy would fall back to a placeholder until
 * the user typed their PIN. Nothing here is credential-sensitive: it is the
 * user's own pixel art, in a format designed to be shared.
 *
 * **The file *is* the export.** A stored design is a verbatim `glyph.design`
 * file, so exporting is a copy and importing is a validated copy — there is no
 * conversion step that could drift.
 *
 * **Atomic writes.** [save] writes `<id>.json.tmp`, flushes it to the platter,
 * and only then renames it over the target. A rename within a directory is
 * atomic, so a crash or a battery pull mid-save leaves either the previous
 * design or the new one, never a half-written file that fails to parse on the
 * next boot. Where a rename cannot replace an existing file, the old one is
 * moved **aside** rather than deleted — see [replaceViaBackup], which is the
 * whole of that reasoning — and an orphaned backup is adopted on the next read
 * by [recoverBackup].
 *
 * **Reads are bounded.** Every load goes through [DesignCodec.decode] on a
 * stream, which stops at 1 MB. Files in this directory are ours, but a restored
 * backup or a sideloaded file is not, and there is no reason for the read path
 * to be less careful than the import path.
 *
 * Thread-safety: all mutating operations and the cached index are guarded by
 * this object's monitor. Callers should still keep file I/O off the main thread.
 */
class DesignStore(context: Context) {

    private val dir: File =
        File(context.createDeviceProtectedStorageContext().filesDir, DIRECTORY_NAME)

    /**
     * Cached listing for the design list UI, so scrolling does not re-parse
     * every file. Invalidated wholesale on any write or delete — the collection
     * is small enough that a targeted update would be complexity for nothing.
     */
    private var index: List<Design>? = null

    /**
     * All valid designs, newest modification first.
     *
     * The sort is a plain string comparison: the format's timestamps are
     * ISO-8601 UTC, which sorts lexicographically, so the list orders correctly
     * without parsing a single date.
     *
     * Files that fail validation are skipped and logged rather than surfaced as
     * an error: one corrupt file must not make the whole list unopenable.
     */
    @Synchronized
    fun list(): List<Design> {
        index?.let { return it }
        // Before enumerating, put back anything a failed save left lying beside
        // the directory rather than in it. A design that only exists under its
        // backup name is invisible here otherwise — the filter below matches
        // `.json`, and `.json.bak` does not.
        recoverBackups()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: emptyArray()
        val designs = files.mapNotNull { readFile(it) }
            .sortedByDescending { it.modifiedAt }
        index = designs
        return designs
    }

    /** A single design, or null if it is missing or does not validate. */
    @Synchronized
    fun load(id: String): Design? {
        val file = fileFor(id) ?: return null
        // Targeted rather than a directory sweep: this runs on every activation
        // of the Custom screen, and the condition is false in every case but the
        // one [replaceViaBackup] cannot recover from by itself.
        if (!file.isFile) recoverBackup(file)
        return readFile(file)
    }

    /** True if a design file with this id already exists. */
    @Synchronized
    fun exists(id: String): Boolean = fileFor(id)?.isFile == true

    /**
     * Writes [design] atomically, replacing any design with the same id.
     *
     * **Overwrite is the contract, not an accident**: saving an edit must land
     * on the design being edited. It therefore follows that *the caller is
     * responsible for id collisions on import* — an imported file carries
     * whatever id its author's phone generated, and if that id already names a
     * design here, the importing code must call [allocateId] and save a copy
     * under the new id. This store will never silently rename a design for you,
     * and it will never refuse a save either; deciding whose art wins is not a
     * decision the storage layer can make.
     *
     * Returns false if the design does not validate or the write fails; a failed
     * write leaves the previous file untouched.
     */
    @Synchronized
    fun save(design: Design): Boolean {
        // Refusing to write a design we would refuse to read keeps the invariant
        // that everything in this directory is loadable.
        val validated = when (val result = DesignCodec.validate(design)) {
            is DesignCodec.Result.Ok -> result.design
            is DesignCodec.Result.Invalid -> {
                DebugLog.w(TAG, "refusing to save ${design.id}: ${result.reason}")
                return false
            }
        }
        val target = fileFor(validated.id) ?: return false
        val tmp = File(dir, validated.id + FILE_SUFFIX + TMP_SUFFIX)
        val backup = File(dir, target.name + BAK_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                DebugLog.w(TAG, "could not create $dir")
                return false
            }
            val bytes = DesignCodec.encode(validated).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // Without the fsync the rename can be durable while the contents
                // are not, which is the precise failure the temp file exists to
                // prevent.
                out.fd.sync()
            }
            if (!replaceViaBackup(tmp, target, backup, File::renameTo)) {
                DebugLog.w(TAG, "could not put ${tmp.name} in place of ${target.name}")
                // Only the temp goes: replaceViaBackup guarantees the previous
                // design is still readable, either at `target` or at `backup`.
                tmp.delete()
                return false
            }
            index = null
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "save ${validated.id} failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    /** Deletes a design. Returns true if a file was removed. */
    @Synchronized
    fun delete(id: String): Boolean {
        val file = fileFor(id) ?: return false
        val deleted = try {
            file.delete()
        } catch (e: Exception) {
            DebugLog.w(TAG, "delete $id failed: ${e.message}")
            false
        }
        if (deleted) index = null
        return deleted
    }

    /**
     * A fresh id that no stored design uses. This is what an importer calls when
     * an incoming design's id collides with one already here.
     */
    @Synchronized
    fun allocateId(): String {
        var id = newDesignId()
        while (exists(id)) id = newDesignId()
        return id
    }

    /** Drops the cached listing; the next [list] re-reads the directory. */
    @Synchronized
    fun invalidate() {
        index = null
    }

    /**
     * Puts a design back under its real name when [replaceViaBackup] could not.
     *
     * That path is vanishingly rare — it needs a *second* rename inside one
     * directory to fail — but "vanishingly rare" is not "never", and a design
     * sitting under `<id>.json.bak` is one nobody can open. Adopting it here is
     * what makes the store's promise ("a failed save always leaves a readable
     * design") true from the *user's* side rather than only on the platter.
     *
     * Never called with an existing [target]: a backup beside an intact target
     * is the superseded content, not the live one.
     */
    private fun recoverBackup(target: File) {
        val backup = File(dir, target.name + BAK_SUFFIX)
        if (!backup.isFile) return
        if (backup.renameTo(target)) {
            DebugLog.i(TAG, "recovered ${target.name} from its backup")
            index = null
        } else {
            DebugLog.w(TAG, "could not recover ${target.name} from ${backup.name}")
        }
    }

    /** [recoverBackup] over the whole directory, for the listing path. */
    private fun recoverBackups() {
        val suffix = FILE_SUFFIX + BAK_SUFFIX
        val backups = dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) } ?: return
        for (backup in backups) {
            val target = File(dir, backup.name.removeSuffix(BAK_SUFFIX))
            // A backup next to a live target is a save that landed but could not
            // tidy up after itself. The target is the newer of the two, so the
            // backup is simply stale and goes.
            if (target.isFile) backup.delete() else recoverBackup(target)
        }
    }

    private fun readFile(file: File): Design? = try {
        file.inputStream().use { input ->
            when (val result = DesignCodec.decode(input)) {
                is DesignCodec.Result.Ok -> result.design
                is DesignCodec.Result.Invalid -> {
                    DebugLog.w(TAG, "skipping ${file.name}: ${result.reason}")
                    null
                }
            }
        }
    } catch (e: Exception) {
        DebugLog.w(TAG, "could not read ${file.name}: ${e.message}")
        null
    }

    /**
     * The *only* place a path is built, and it is built from a validated token —
     * never from a name, an author, or anything else the user typed. An id that
     * does not match the codec's safe-token rule cannot address a file at all.
     */
    private fun fileFor(id: String): File? =
        if (DesignCodec.isSafeId(id)) File(dir, id + FILE_SUFFIX) else null

    private companion object {
        const val TAG = "DesignStore"
        const val DIRECTORY_NAME = "designs"
        const val FILE_SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"

        /** Where the outgoing file waits while its replacement lands. */
        const val BAK_SUFFIX = ".bak"
    }
}

/**
 * Puts [tmp] where [target] is, such that **no outcome of this function leaves
 * the user without a readable design**.
 *
 * The version this replaces did the opposite. It tried the rename, and on
 * failure deleted the target and tried again — so the only good copy on disk
 * was destroyed *before* the replacement was known to land, and if the second
 * rename failed too the temp was deleted as well. Two failed renames and the
 * artwork was gone, with `save` reporting nothing worse than false. Deleting
 * first does not narrow that window, it makes the loss unconditional inside it.
 *
 * So the old file is moved **aside**, never removed, and it is put back if the
 * replacement does not arrive:
 *
 *  1. `tmp -> target`. On POSIX this replaces atomically and is the only step
 *     that ever runs; the rest exists because `renameTo` is documented not to
 *     replace on every filesystem, and Android has shipped on several.
 *  2. If that failed and there is no target, there is nothing to protect and
 *     nothing to recover: the rename failed for some other reason (no space,
 *     no directory, a cross-device move) and the caller gets false.
 *  3. `target -> backup`. If *this* fails the target has not been touched, so
 *     the previous design is still exactly where it was.
 *  4. `tmp -> target` again, now that the name is free. On success the backup is
 *     redundant and is dropped.
 *  5. If it still fails, `backup -> target` puts the previous design back.
 *  6. And if even that fails, the previous design survives under the backup
 *     name — the one case where the recovery is deferred to the read path
 *     rather than completed here. See `DesignStore.recoverBackup`.
 *
 * Pure, and [rename] is injected, precisely so every one of those branches can
 * be provoked in a unit test — including the ones a real filesystem will not
 * produce on demand. It deletes no temp file: that is the caller's to clean up,
 * and keeping it out of here means the function's only destructive act is
 * dropping a backup it has just made redundant.
 */
internal fun replaceViaBackup(
    tmp: File,
    target: File,
    backup: File,
    rename: (File, File) -> Boolean,
): Boolean {
    if (rename(tmp, target)) return true
    if (!target.isFile) return false
    // A backup still here from an earlier save is superseded by the target,
    // which by definition exists. Clearing it stops a stale file from blocking
    // the move aside on filesystems that will not rename onto an existing name.
    backup.delete()
    if (!rename(target, backup)) return false
    if (rename(tmp, target)) {
        backup.delete()
        return true
    }
    rename(backup, target)
    return false
}
