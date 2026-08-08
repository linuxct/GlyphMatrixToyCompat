package space.linuxct.glyphworks.ai

import android.content.Context
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.ChatTranscript
import space.linuxct.glyphworks.core.ai.ChatTranscriptCodec
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.designs.replaceViaBackup
import java.io.File
import java.io.FileOutputStream

/**
 * One conversation per design, on disk.
 *
 * ## Credential-protected, unlike everything beside it
 *
 * `designs/DesignStore` and `util/AndroidPrefs` both call
 * `createDeviceProtectedStorageContext()`, deliberately, so the `directBootAware`
 * services can draw a design and read a setting *before the first unlock after a
 * reboot*. That is right for pixel art and for a screen order.
 *
 * It is wrong for a conversation. What a person typed to an assistant is not
 * artwork meant to be shared: it is the contents of their head, and
 * device-protected storage is readable while nobody has yet proved they own the
 * phone. This class therefore takes the ordinary [Context] and never converts it,
 * exactly as [TokenStore] does, and the guard in `init` is the same guard for the
 * same reason — the realistic way this goes wrong later is somebody handing it
 * the converted context that `Core` already holds, which would move every
 * conversation into DE storage silently and with no visible symptom.
 *
 * Nothing reads chats before unlock: they exist only in the design editor, which
 * is an Activity. And `res/xml/backup_rules.xml` is an allowlist naming only
 * `device_file/designs`, so conversations are excluded from Auto Backup by
 * construction rather than by a rule somebody has to remember to add.
 *
 * ## A missing or broken file is "no history", never an error
 *
 * This is read on the way into the editor, so *every* failure mode here has to
 * degrade to an empty thread. A truncated write, a file from a future build, a
 * directory that will not create, a design id that is not a safe token: all of
 * them return null and the user starts a new conversation. Losing a chat is a
 * disappointment; failing to open a drawing because of one would be a bug worth
 * shipping a hotfix for.
 *
 * ## Writes go through the design store's crash-safe dance
 *
 * [replaceViaBackup] is `designs/DesignStore`'s, reused rather than reimplemented:
 * it is already the one function in this app that has been reasoned through
 * failure-by-failure, and it is already unit-tested against rename failures a real
 * filesystem will not produce on demand. A transcript is smaller and less
 * precious than a design, but "half-written file" is the same problem and there
 * is no second answer to it.
 *
 * ## Orphans are swept on the way in
 *
 * `DesignStore` tells [DesignChatCleanup] about every design it deletes, and that
 * is how a conversation normally goes. It is not enough on its own: the hook is
 * registered by the object graph, and a delete that happens with no hook in place
 * — before the first unlock, or in a build without this package at all — leaves
 * the transcript behind. `DesignStore.allocateId()` looks at design files and
 * nothing else, so a later design could be handed that id and inherit a stranger's
 * conversation.
 *
 * Closing that is [designIds]' whole purpose: the first time this store touches
 * its directory it drops every transcript whose design is gone, whenever and
 * however it went. See [dir].
 */
class ChatStore(
    context: Context,
    /**
     * The ids of the designs that still exist — `DesignStore.storedIds`.
     *
     * A function rather than a set because it is called once, lazily, long after
     * this object is built, and it must see the directory as it is then. It is
     * also, deliberately, the *filesystem's* answer: a design whose file will not
     * parse is still the user's design, and its conversation is not an orphan.
     */
    private val designIds: () -> Set<String>,
) {

    private val app: Context

    init {
        val application = context.applicationContext
        check(!application.isDeviceProtectedStorage) {
            "ChatStore must be built from a credential-protected Context; " +
                "a conversation must not be readable before the first unlock"
        }
        app = application
    }

    /**
     * Resolved lazily, and that is load-bearing rather than tidy.
     *
     * `Context.filesDir` on credential-protected storage cannot be created while
     * the device is locked, and `Core.init` runs in exactly that state when
     * `AodToyService` starts during Direct Boot. `designs/DesignStore` holds one
     * of these (see `DesignChatCleanup`) and is constructed there, so touching
     * `filesDir` in a constructor would take the always-on display down with it.
     * Every method below runs from the editor, long after unlock.
     *
     * The orphan sweep hangs off this initialiser rather than off a method
     * somebody has to remember to call, so it happens exactly once per store, at
     * the first moment it is both safe and useful — and it is given the directory
     * rather than reading [dir], which would be re-entering this initialiser.
     */
    private val dir: File by lazy {
        File(app.filesDir, DIRECTORY_NAME).also { pruneOrphans(it) }
    }

    /**
     * The conversation about [designId], or null if there is none this build can
     * read. Never throws; see this class's KDoc.
     */
    fun load(designId: String): ChatTranscript? {
        val file = fileFor(designId) ?: return null
        // A transcript that only exists under its backup name is invisible
        // otherwise — the same recovery `DesignStore.load` performs, for the same
        // one-in-a-million second-rename failure. `readTranscript` copes with the
        // file still not being there afterwards.
        if (!file.isFile) recoverBackup(file)
        return readTranscript(file)
    }

    /**
     * Writes [transcript] under its own [ChatTranscript.designId]. Returns false
     * if it could not be stored, leaving whatever was there untouched.
     */
    fun save(transcript: ChatTranscript): Boolean {
        val target = fileFor(transcript.designId) ?: return false
        val tmp = File(dir, target.name + TMP_SUFFIX)
        val backup = File(dir, target.name + BAK_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                DebugLog.w(TAG, "could not create $dir")
                return false
            }
            val bytes = ChatTranscriptCodec.encode(transcript).toByteArray(Charsets.UTF_8)
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
            DebugLog.w(TAG, "save ${transcript.designId} failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    /**
     * Removes the conversation about [designId], and any backup of it.
     *
     * Two callers, and they mean different things by it. [DesignChatCleanup]'s
     * listener calls it because the *design* is going — see `DesignStore.delete`
     * for why that hangs off the one function that removes a design rather than
     * off the place in `ui/CreateTab.kt` a person actually taps. `GlyphAiViewModel`
     * calls it because the user asked to reset the conversation and keep the
     * design, which is why nothing here has ever known about designs: this
     * removes a transcript and only a transcript.
     *
     * Returns true if a file went. False is the ordinary case of a design nobody
     * ever talked to, so no caller should treat it as a failure.
     */
    fun delete(designId: String): Boolean {
        val name = chatFileName(designId) ?: return false
        return deleteTranscript(dir, name)
    }

    private fun recoverBackup(target: File) {
        val backup = File(dir, target.name + BAK_SUFFIX)
        if (!backup.isFile) return
        if (!backup.renameTo(target)) {
            DebugLog.w(TAG, "could not recover ${target.name} from ${backup.name}")
        }
    }

    /**
     * Deletes every transcript in [directory] whose design no longer exists.
     *
     * Guarded at both ends, because the failure mode is deleting somebody's
     * conversations: nothing happens if the directory is not there (a device that
     * has never opened the assistant, which is most of them), and nothing happens
     * if [designIds] cannot answer. A supplier that returned an empty set because
     * it genuinely found no designs is a different thing from one that threw, and
     * only the first is allowed to sweep.
     */
    private fun pruneOrphans(directory: File) {
        val files = directory.listFiles() ?: return
        val live = try {
            designIds()
        } catch (e: Exception) {
            DebugLog.w(TAG, "not pruning: could not list designs (${e.message})")
            return
        }
        var gone = 0
        for (file in orphanChats(files.map { it.name }, live)) {
            try {
                if (File(directory, file).delete()) gone++
            } catch (e: Exception) {
                DebugLog.w(TAG, "could not delete the orphaned $file: ${e.message}")
            }
        }
        if (gone > 0) DebugLog.i(TAG, "removed $gone conversation file(s) with no design")
    }

    /** The only place a path is built here, and only ever from a validated id. */
    private fun fileFor(designId: String): File? =
        chatFileName(designId)?.let { File(dir, it) }

    private companion object {
        const val TAG = "ChatStore"
    }
}

/** Where transcripts live under the credential-protected `filesDir`. */
internal const val DIRECTORY_NAME = "chats"

private const val FILE_SUFFIX = ".json"
private const val TMP_SUFFIX = ".tmp"
private const val BAK_SUFFIX = ".bak"

/**
 * The file name for a design's conversation, or null if [designId] is not
 * something that may name a file.
 *
 * A separate, pure, top-level function for two reasons. It is the app's whole
 * path-traversal defence — the id reaches here from a design document, which can
 * be an imported file somebody else wrote, so `../../shared_prefs/openai_auth`
 * has to be *impossible* rather than merely unlikely — and being pure it is the
 * one part of [ChatStore] a plain JUnit test can prove.
 *
 * The rule is [DesignCodec.isSafeId], reused rather than restated: the id that
 * names a chat file and the id that names a design file are the same id, and two
 * spellings of "safe" is one of them being wrong later.
 */
internal fun chatFileName(designId: String): String? =
    if (DesignCodec.isSafeId(designId)) designId + FILE_SUFFIX else null

/**
 * Of [fileNames] in the chats directory, the ones belonging to a design that is
 * not in [liveDesignIds].
 *
 * Pure and top-level so the rule can be proven under plain JUnit, because it is
 * the one rule here that *destroys* data. Two properties matter and both are
 * asserted in `ChatStoreTest`:
 *
 * - **A name this store did not write is never returned.** Anything that is not
 *   `<safe id>.json`, `.json.bak` or `.json.tmp` is left where it is. Sweeping a
 *   directory is not licence to delete things we do not recognise.
 * - **The `.bak` and `.tmp` of an orphan go with it**, for the reason
 *   [ChatStore.delete] gives: a transcript surviving under its backup name would
 *   be adopted by the next design to take that id, through `recoverBackup`.
 */
internal fun orphanChats(fileNames: List<String>, liveDesignIds: Set<String>): List<String> =
    fileNames.filter { name ->
        val id = when {
            name.endsWith(FILE_SUFFIX + BAK_SUFFIX) -> name.removeSuffix(FILE_SUFFIX + BAK_SUFFIX)
            name.endsWith(FILE_SUFFIX + TMP_SUFFIX) -> name.removeSuffix(FILE_SUFFIX + TMP_SUFFIX)
            name.endsWith(FILE_SUFFIX) -> name.removeSuffix(FILE_SUFFIX)
            else -> return@filter false
        }
        DesignCodec.isSafeId(id) && id !in liveDesignIds
    }

/**
 * Removes [fileName] from [directory], with its backup and its temp.
 *
 * Top-level and pure for the reason [readTranscript] is: this is the one
 * operation in this file that *destroys* something a person wrote, it is now
 * reachable from a menu item and not only from deleting a design, and a function
 * taking a [File] can be handed a real directory by a plain JUnit test where a
 * class needing a `Context` cannot.
 *
 * The backup goes unconditionally: leaving `<id>.json.bak` behind would have the
 * next design that happened to reuse the id adopt a stranger's conversation
 * through `recoverBackup`, and would have "reset this chat" quietly restore the
 * chat on the next open. The temp goes for the weaker but sufficient reason that
 * a conversation somebody deleted must not survive anywhere, under any name.
 *
 * **Nothing else in [directory] is touched**, which is the whole promise the
 * reset action makes: the design itself lives in another directory entirely (a
 * device-protected one — see this file's header), and no path here can reach it.
 */
internal fun deleteTranscript(directory: File, fileName: String): Boolean = try {
    val backupGone = File(directory, fileName + BAK_SUFFIX).delete()
    val tmpGone = File(directory, fileName + TMP_SUFFIX).delete()
    File(directory, fileName).delete() || backupGone || tmpGone
} catch (e: Exception) {
    DebugLog.w("ChatStore", "could not delete $fileName: ${e.message}")
    false
}

/**
 * [file] as a transcript, or null for every reason it might not be one.
 *
 * Pulled out of [ChatStore] and made top-level for the same reason
 * `designs/DesignStore` pulled out `replaceViaBackup`: this is the whole of the
 * "a broken file must not break the editor" promise, and a class that needs a
 * `Context` cannot be instantiated under plain JUnit, while a function that takes
 * a [File] can be handed a genuinely truncated one.
 *
 * The size check happens before the read, not after: the point of a cap is to
 * avoid pulling an absurd file into memory, and checking afterwards would be a
 * cap that only reports the damage.
 */
internal fun readTranscript(file: File): ChatTranscript? = try {
    when {
        !file.isFile -> null
        file.length() > ChatTranscriptCodec.MAX_BYTES -> {
            DebugLog.w("ChatStore", "ignoring ${file.name}: ${file.length()} bytes")
            null
        }

        else -> ChatTranscriptCodec.decode(file.readText(Charsets.UTF_8))
    }
} catch (e: Exception) {
    // A directory where a file should be, a permission problem, bytes that are
    // not UTF-8 at all. None of them is worth a dialog: the thread starts empty.
    DebugLog.w("ChatStore", "could not read ${file.name}: ${e.message}")
    null
}
