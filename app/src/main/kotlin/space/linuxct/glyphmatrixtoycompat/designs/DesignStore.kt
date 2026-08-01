package space.linuxct.glyphmatrixtoycompat.designs

import android.content.Context
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.newDesignId
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

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
     * Everything else that belongs to a design and must go when it does.
     *
     * **The hook is deliberately here and not at the call sites.** Deleting a
     * design can be a two-part act — the artwork, and whatever else is keyed by
     * its id — and the parts must not be able to drift. Today `ui/CreateTab`
     * funnels both of its delete buttons through one `store.delete(design.id)`;
     * tomorrow somebody adds a third, or a bulk delete, or a "clear all" in
     * settings, and nothing about writing that code would suggest there is a
     * second file to remove. Putting it in the one function that removes the
     * design makes forgetting it impossible rather than merely unlikely.
     *
     * **What it must not be is an import.** This field used to hold an
     * `ai/ChatStore` directly, which had `designs/` — a storage layer that
     * `AodToyService` depends on before the first unlock — depending on the AI
     * feature, which is explicitly unpublishable and may one day be absent from a
     * build entirely. A listener inverts that: `ai/` knows about designs, and
     * `designs/` knows only that *something* may care. See
     * `ai/DesignChatCleanup` for the one registration, and [DesignDeletionHooks]
     * for why a failing listener cannot cost the caller its delete.
     */
    private val hooks = DesignDeletionHooks()

    /**
     * Registers [listener] to be told, by id, about every design this store
     * deletes. Called once per process, from the composition root.
     *
     * There is no removal, on purpose: the only registrant is the object graph
     * itself, which lives as long as the process, and a listener that could be
     * dropped is a deletion that could be missed.
     *
     * [listener] runs **inside** [delete], on the caller's thread and while this
     * store's monitor is held. That is what lets it call back into this store —
     * `storedIds()`, say — without deadlocking, and it means the work it does
     * should be the modest file I/O the caller already expected of `delete`.
     */
    fun addDeletionListener(listener: (id: String) -> Unit) {
        hooks.add(listener)
    }

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

    /**
     * Deletes a design **and everything registered as belonging to it**. Returns
     * true if the design file was removed.
     *
     * The listeners run unconditionally, not only when the design file was there
     * to remove: a design already gone from disk can still have left something
     * behind, and an orphan would be adopted by whatever design next carried that
     * id ([allocateId] checks design files, and nothing else). See [hooks].
     *
     * The index is dropped **before** the listeners run, so a listener that asks
     * this store what still exists — as the chat cleanup does — sees the design
     * gone rather than reading a cache that still lists it.
     */
    @Synchronized
    fun delete(id: String): Boolean {
        val file = fileFor(id) ?: return false
        // The index goes first, and unconditionally: a listener may ask this
        // store what still exists, and a cache that still lists the design would
        // have it keep something it should be taking with it.
        index = null
        return deleteDesignFile(file, id, hooks)
    }

    /**
     * The id of every design file present, whether or not it parses.
     *
     * Deliberately *not* `list().map { it.id }`. This answers "does a design with
     * this id exist?", which is the same question [exists] and [allocateId] ask,
     * and it has to answer it the same way — from the filesystem. A design file
     * that fails validation is still a design the user has, and anything keyed by
     * its id (a conversation, say) must not be treated as an orphan and swept up
     * because today's build could not parse the artwork.
     *
     * A design sitting under its `.bak` name counts too. [recoverBackups] will
     * put it back on the next listing, so treating it as absent for one moment
     * would be enough to have something else keyed by its id swept away.
     *
     * Cheap: it reads names, never contents.
     */
    @Synchronized
    fun storedIds(): Set<String> {
        val files = dir.listFiles() ?: return emptySet()
        return files.mapNotNullTo(HashSet(files.size)) { storedDesignId(it.name) }
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
    }
}

internal const val FILE_SUFFIX = ".json"
internal const val TMP_SUFFIX = ".tmp"

/** Where the outgoing file waits while its replacement lands. */
internal const val BAK_SUFFIX = ".bak"

/**
 * The design id a file in the designs directory belongs to, or null if the name
 * is not one this store writes.
 *
 * Top-level and pure for the same reason [replaceViaBackup] is: `DesignStore`
 * needs a `Context` and cannot be built under plain JUnit, and this is the rule
 * that decides whether something keyed by a design id is an orphan. Getting it
 * wrong in the harmless direction leaves a stale file; getting it wrong in the
 * other deletes a conversation whose design is alive but temporarily under its
 * backup name.
 *
 * Hence all three names are accepted — `<id>.json`, `<id>.json.bak`,
 * `<id>.json.tmp` — and the result is checked against the codec's safe-token
 * rule, so nothing else in the directory can ever be read as an id.
 */
internal fun storedDesignId(fileName: String): String? {
    val id = when {
        fileName.endsWith(FILE_SUFFIX + BAK_SUFFIX) -> fileName.removeSuffix(FILE_SUFFIX + BAK_SUFFIX)
        fileName.endsWith(FILE_SUFFIX + TMP_SUFFIX) -> fileName.removeSuffix(FILE_SUFFIX + TMP_SUFFIX)
        fileName.endsWith(FILE_SUFFIX) -> fileName.removeSuffix(FILE_SUFFIX)
        else -> return null
    }
    return id.takeIf { DesignCodec.isSafeId(it) }
}

/**
 * The "something else belongs to this design" hook, and the whole of its
 * failure policy.
 *
 * A separate class rather than a field of listeners on `DesignStore` so that
 * both halves of the contract can be proven under plain JUnit, which cannot
 * build a `DesignStore` at all:
 *
 * - **A listener is told about every delete**, by id, in registration order.
 * - **A listener that fails costs nobody their delete.** The whole point of the
 *   design store is that "your design is gone" is a promise it keeps; a chat
 *   store that cannot be reached — locked storage, a context that has none, a
 *   file another process holds — must not turn that into an exception on the way
 *   out of `delete`. So every listener is called inside its own catch, and one
 *   throwing does not stop the next.
 *
 * `CopyOnWriteArrayList` because registration happens once at process start
 * while deletes happen on whatever thread the UI used, and a plain list would be
 * a data race with no lock that covers both.
 */
internal class DesignDeletionHooks {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun add(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    /** Tells every listener that the design called [id] has been deleted. */
    fun notifyDeleted(id: String) {
        for (listener in listeners) {
            try {
                listener(id)
            } catch (e: Exception) {
                DebugLog.w("DesignStore", "a deletion listener failed for $id: ${e.message}")
            }
        }
    }
}

/**
 * [DesignStore.delete]'s file work, without the `Context` that class needs.
 *
 * Extracted so a test can watch a real file go and a real listener fire — the
 * same reason [replaceViaBackup] is out here. The listeners are notified
 * **whether or not** the file was there: a design already gone from disk can
 * still have left something behind under its id, and an orphan would be adopted
 * by whatever design next allocated that id.
 */
internal fun deleteDesignFile(file: File, id: String, hooks: DesignDeletionHooks): Boolean {
    val deleted = try {
        file.delete()
    } catch (e: Exception) {
        DebugLog.w("DesignStore", "delete $id failed: ${e.message}")
        false
    }
    hooks.notifyDeleted(id)
    return deleted
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
