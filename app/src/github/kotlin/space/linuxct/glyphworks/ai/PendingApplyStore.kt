package space.linuxct.glyphworks.ai

import android.content.Context
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.PendingApply
import space.linuxct.glyphworks.core.ai.PendingApplyCodec
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.designs.replaceViaBackup
import java.io.File
import java.io.FileOutputStream

/**
 * Designs the assistant finished while nobody was looking, one file per design.
 *
 * Built exactly like [ChatStore] and for the same three reasons, so the two read
 * as one storage layer rather than as two ideas about storage:
 *
 * - **Credential-protected.** This holds a drawing rather than a sentence, so the
 *   privacy argument is weaker than [ChatStore]'s — but it is written by the
 *   assistant, from a conversation, and splitting one feature's persistence
 *   across two encryption domains to save nothing would be the worse trade. The
 *   `init` guard is the same guard: the realistic mistake is somebody handing
 *   this the device-protected context `Core` already holds.
 * - **[dir] is lazy**, because credential-protected `filesDir` cannot be created
 *   while the device is locked and `Core.init` runs in exactly that state during
 *   Direct Boot. Nothing here is reached before an editor is on screen.
 * - **Nothing throws, and a broken file is "nothing waiting".** This is read on
 *   the way into the editor. A record that will not parse must cost the user a
 *   drawing they can ask for again, never the design they were opening.
 *
 * Writes go through `DesignStore`'s [replaceViaBackup] for the reason [ChatStore]
 * gives: there is one crash-safe write in this app and no second answer to it.
 *
 * ## Consumed, not accumulated
 *
 * There is at most one record per design and reading it deletes it — see
 * [take]. A deferred apply is an event ("the model finished this while you were
 * away"), not a queue: two of them would mean the older one landing and being
 * immediately replaced, which is a flicker rather than a feature.
 */
class PendingApplyStore(context: Context) {

    private val app: Context

    init {
        val application = context.applicationContext
        check(!application.isDeviceProtectedStorage) {
            "PendingApplyStore must be built from a credential-protected Context, " +
                "like the chat store it sits beside"
        }
        app = application
    }

    /** See this class's KDoc: lazy is load-bearing, not tidy. */
    private val dir: File by lazy { File(app.filesDir, PENDING_DIRECTORY_NAME) }

    /**
     * The record for [designId] **and its removal**, or null if there is none
     * this build can read.
     *
     * Read-and-delete in one call, deliberately. Every outcome of a deferred
     * apply — applied, refused, in conflict with the user's own edits, expired —
     * ends with the record gone, and leaving the delete to the caller would leave
     * one path where a record that could not be applied is retried on every
     * single open of that design.
     */
    fun take(designId: String): PendingApply? {
        val file = fileFor(designId) ?: return null
        val record = readPendingApply(file)
        deletePendingApply(dir, file.name)
        return record
    }

    /**
     * Records [record] for its own design, replacing anything already waiting.
     * Returns false if it could not be stored.
     */
    fun put(record: PendingApply): Boolean {
        val target = fileFor(record.designId) ?: return false
        val tmp = File(dir, target.name + PENDING_TMP_SUFFIX)
        val backup = File(dir, target.name + PENDING_BAK_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                DebugLog.w(TAG, "could not create $dir")
                return false
            }
            val bytes = PendingApplyCodec.encode(record).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!replaceViaBackup(tmp, target, backup, File::renameTo)) {
                DebugLog.w(TAG, "could not put ${tmp.name} in place of ${target.name}")
                tmp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "could not record a deferred apply: ${e.message}")
            tmp.delete()
            false
        }
    }

    /** The only place a path is built here, and only ever from a validated id. */
    private fun fileFor(designId: String): File? =
        pendingApplyFileName(designId)?.let { File(dir, it) }

    private companion object {
        const val TAG = "PendingApply"
    }
}

/** Where deferred applies live under the credential-protected `filesDir`. */
internal const val PENDING_DIRECTORY_NAME = "pending_designs"

private const val PENDING_FILE_SUFFIX = ".json"
private const val PENDING_TMP_SUFFIX = ".tmp"
private const val PENDING_BAK_SUFFIX = ".bak"

/**
 * The file name for a design's deferred apply, or null if [designId] is not
 * something that may name a file.
 *
 * The same rule, the same reasoning and the same trust boundary as
 * [chatFileName]: the id arrives from a design document, a design document can be
 * a file a stranger wrote, and `../../shared_prefs/openai_auth` must be
 * impossible rather than unlikely.
 */
internal fun pendingApplyFileName(designId: String): String? =
    if (DesignCodec.isSafeId(designId)) designId + PENDING_FILE_SUFFIX else null

/**
 * [file] as a deferred apply, or null for every reason it might not be one.
 *
 * Top-level and pure so a test can hand it a genuinely truncated file, exactly as
 * [readTranscript] is. The size check runs before the read for the same reason it
 * does there: a cap checked afterwards is a cap that only reports the damage.
 */
internal fun readPendingApply(file: File): PendingApply? = try {
    when {
        !file.isFile -> null
        file.length() > PendingApplyCodec.MAX_BYTES -> {
            DebugLog.w("PendingApply", "ignoring ${file.name}: ${file.length()} bytes")
            null
        }

        else -> PendingApplyCodec.decode(file.readText(Charsets.UTF_8))
    }
} catch (e: Exception) {
    DebugLog.w("PendingApply", "could not read ${file.name}: ${e.message}")
    null
}

/**
 * Removes [fileName] from [directory], with its backup and its temp.
 *
 * The backup goes unconditionally, for [deleteTranscript]'s reason: a record
 * surviving under its backup name would be adopted by the next design to take
 * that id, and would have a consumed apply land a second time.
 */
internal fun deletePendingApply(directory: File, fileName: String): Boolean = try {
    val backupGone = File(directory, fileName + PENDING_BAK_SUFFIX).delete()
    val tmpGone = File(directory, fileName + PENDING_TMP_SUFFIX).delete()
    File(directory, fileName).delete() || backupGone || tmpGone
} catch (e: Exception) {
    DebugLog.w("PendingApply", "could not delete $fileName: ${e.message}")
    false
}
