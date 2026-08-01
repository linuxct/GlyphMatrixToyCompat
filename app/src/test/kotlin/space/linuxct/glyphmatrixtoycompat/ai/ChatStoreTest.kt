package space.linuxct.glyphmatrixtoycompat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessage
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRole
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTranscript
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTranscriptCodec
import java.io.File

/**
 * The two halves of [ChatStore] that do not need a `Context`: how a design id
 * becomes a file name, and what happens when the file behind that name is not
 * what it should be.
 *
 * `ChatStore` itself takes a `Context` and cannot be built here — the same
 * situation `DesignStoreTest` is in, and the same answer: the parts worth proving
 * were made top-level functions so a test can hand them a real, genuinely broken
 * file rather than a mock of one.
 *
 * The path test is not busywork. The id reaches the store from a design document,
 * and a design document can be a file somebody else wrote and the user imported.
 * If `../../shared_prefs/openai_auth` could name a chat file, a malicious design
 * would be able to point the writer at the OAuth token.
 */
class ChatStoreTest {

    // region path derivation

    @Test
    fun `an ordinary design id names a json file beside the others`() {
        assertEquals("abc123.json", chatFileName("abc123"))
        assertEquals(
            "9f2c4b1e8a6d40f2b3c5d7e9f1a2b3c4.json",
            chatFileName("9f2c4b1e8a6d40f2b3c5d7e9f1a2b3c4"),
        )
        assertEquals("A_b-C.json", chatFileName("A_b-C"))
    }

    @Test
    fun `nothing that could escape the directory can name a file`() {
        val hostile = listOf(
            "..",
            "../secrets",
            "../../shared_prefs/openai_auth",
            "chats/../../x",
            "a/b",
            "a\\b",
            "with space",
            "dot.ted",
            "nul\u0000byte",
            "",
            "über",
        )

        hostile.forEach { assertNull(it, chatFileName(it)) }
    }

    @Test
    fun `the chats directory is not the designs directory`() {
        // The two stores must not share a directory: designs are device-protected
        // so the always-on display can read them before unlock, conversations are
        // credential-protected so nothing can. A name collision would be a very
        // quiet way to undo that.
        assertEquals("chats", DIRECTORY_NAME)
    }

    // endregion

    // region degrading gracefully

    @Test
    fun `a transcript written whole is read back whole`() {
        val file = write("good.json", ChatTranscriptCodec.encode(transcript))

        val read = readTranscript(file)

        assertEquals(transcript, read)
    }

    @Test
    fun `a file that does not exist is simply no history`() {
        assertNull(readTranscript(File(dir(), "never-written.json")))
    }

    @Test
    fun `a truncated file degrades to no history rather than throwing`() {
        val whole = ChatTranscriptCodec.encode(transcript)
        // The exact shape a crash between `write` and `fd.sync()` leaves behind.
        val file = write("truncated.json", whole.substring(0, whole.length - 40))

        assertNull(readTranscript(file))
    }

    @Test
    fun `a file of garbage degrades to no history`() {
        assertNull(readTranscript(write("garbage.json", "\u0000\u0001 not json at all {{{")))
        assertNull(readTranscript(write("empty.json", "")))
        assertNull(readTranscript(write("array.json", "[1,2,3]")))
    }

    @Test
    fun `a transcript from a newer build degrades to no history`() {
        val file = write(
            "future.json",
            """{"format":"glyph.chat","formatVersion":99,"designId":"abc123","messages":[]}""",
        )

        assertNull(readTranscript(file))
    }

    @Test
    fun `an absurdly large file is refused without being read into memory`() {
        val file = File(dir(), "huge.json").apply {
            // Sparse: the assertion is about the length check firing before the
            // read, so writing a real 4 MB of JSON would only slow the suite down.
            writeText("{")
            java.io.RandomAccessFile(this, "rw").use {
                it.setLength(ChatTranscriptCodec.MAX_BYTES + 1L)
            }
        }

        assertNull(readTranscript(file))
    }

    @Test
    fun `a directory where a file should be is not fatal`() {
        val asDirectory = File(dir(), "adirectory.json").apply { mkdirs() }

        assertNull(readTranscript(asDirectory))
    }

    @Test
    fun `a readable file is not affected by an unreadable neighbour`() {
        write("broken.json", "{{{")
        val good = write("intact.json", ChatTranscriptCodec.encode(transcript))

        assertNotNull(readTranscript(good))
        assertTrue(readTranscript(good)!!.messages.isNotEmpty())
    }

    // endregion

    // region orphans

    /**
     * The gap the deletion hook cannot close by itself.
     *
     * `DesignStore` notifies whoever registered a listener, and `Core.init`
     * registers one — but a delete that happens with no hook in place (before the
     * first unlock, or in a build without this package) leaves the transcript
     * behind, and `DesignStore.allocateId` only ever looks at *design* files. A
     * later design handed that id would inherit a stranger's conversation. So the
     * store sweeps on the way in as well.
     */
    @Test
    fun `a conversation whose design is gone is an orphan`() {
        val orphans = orphanChats(
            listOf("alive.json", "deleted.json", "alsodeleted.json"),
            setOf("alive"),
        )

        assertEquals(listOf("deleted.json", "alsodeleted.json"), orphans)
    }

    @Test
    fun `an orphan's backup and temp go with it`() {
        val orphans = orphanChats(
            listOf("gone.json", "gone.json.bak", "gone.json.tmp"),
            emptySet(),
        )

        // A transcript surviving under its backup name would be adopted by the
        // next design to take that id, through `recoverBackup` — the same reason
        // `delete` clears all three.
        assertEquals(listOf("gone.json", "gone.json.bak", "gone.json.tmp"), orphans)
    }

    @Test
    fun `a live design's backup and temp are left alone`() {
        assertEquals(
            emptyList<String>(),
            orphanChats(listOf("abc.json", "abc.json.bak", "abc.json.tmp"), setOf("abc")),
        )
    }

    @Test
    fun `nothing this store did not write is ever swept up`() {
        // Sweeping a directory is not a licence to delete what we do not
        // recognise: a subdirectory, somebody's notes, a file from a later build.
        val strangers = listOf(
            "notes.txt",
            "README",
            ".json",
            "with space.json",
            "über.json",
            "sub",
            "",
        )

        assertEquals(emptyList<String>(), orphanChats(strangers, emptySet()))
    }

    @Test
    fun `no designs at all means every conversation is an orphan`() {
        // The honest reading of an empty design directory, and the reason
        // `ChatStore` refuses to prune when the id supplier *throws* rather than
        // returning nothing: those are different facts.
        assertEquals(listOf("a.json", "b.json"), orphanChats(listOf("a.json", "b.json"), emptySet()))
    }

    // endregion

    // region clearing one conversation

    /**
     * "Reset this chat" and deleting a design end up in the same function, and
     * this is the half of it that can be proven: the transcript's three possible
     * files go, together, and nothing else in the directory is touched.
     *
     * The three names matter individually. A `.bak` left behind is adopted by the
     * next open through `recoverBackup`, so a reset would appear to work and then
     * hand the conversation back; a `.tmp` left behind is a conversation somebody
     * asked to be rid of, still on the disk under another name.
     */
    @Test
    fun `clearing a conversation takes its file, its backup and its temp`() {
        val directory = freshDir("gmtc-chat-delete")
        File(directory, "abc123.json").writeText(ChatTranscriptCodec.encode(transcript))
        File(directory, "abc123.json.bak").writeText("{}")
        File(directory, "abc123.json.tmp").writeText("{}")

        assertTrue(deleteTranscript(directory, "abc123.json"))

        assertEquals(emptyList<String>(), directory.list()!!.sorted())
    }

    /**
     * The promise the confirmation dialog makes: resetting a conversation is not
     * an undo of the artwork.
     *
     * Designs live in a different directory *and* on a different storage volume
     * (device-protected, so the always-on display can read them before unlock),
     * which is the real guarantee. This asserts the reachable part of it — the
     * only path built here is `<id>.json` inside the chats directory, so a design
     * file of the same name a directory away is untouched, and so is every other
     * conversation.
     */
    @Test
    fun `clearing one conversation leaves the design and every other chat alone`() {
        val root = freshDir("gmtc-chat-delete-scope")
        val designs = File(root, "designs").apply { mkdirs() }
        val chats = File(root, "chats").apply { mkdirs() }
        File(designs, "abc123.json").writeText("the design itself")
        File(chats, "abc123.json").writeText(ChatTranscriptCodec.encode(transcript))
        File(chats, "other.json").writeText(ChatTranscriptCodec.encode(transcript))

        deleteTranscript(chats, "abc123.json")

        assertEquals(listOf("other.json"), chats.list()!!.sorted())
        assertEquals("the design itself", File(designs, "abc123.json").readText())
    }

    @Test
    fun `clearing a conversation nobody ever had is not a failure`() {
        val directory = freshDir("gmtc-chat-delete-empty")

        // False here means "there was nothing to remove", which is the ordinary
        // case for a design nobody has talked to. No caller may treat it as an
        // error, and none does.
        assertEquals(false, deleteTranscript(directory, "never-written.json"))
    }

    // endregion

    // region a conversation, turn by turn

    /**
     * What the chat modal actually does to a transcript, end to end: append a
     * user turn, append the reply, write, and read it back on the next open.
     *
     * The parts worth asserting are the ones the ViewModel relies on without
     * saying so — that appending returns a *new* transcript rather than mutating
     * the one being written, that order survives the round trip, and that the
     * tool notes a turn produced survive with it, since "Updated your design" in
     * the scrollback is how a user knows the assistant changed anything.
     */
    @Test
    fun `a conversation appended turn by turn comes back in the same order`() {
        var thread = ChatTranscript(designId = "abc123")
        thread = thread.plus(ChatMessage(role = ChatRole.USER, text = "draw a smiley", atMs = 10L))
        thread = thread.plus(
            ChatMessage(
                role = ChatRole.ASSISTANT,
                text = "Here you go.",
                atMs = 11L,
                tools = listOf(
                    ChatToolNote(name = "get_current_design", label = "Read your design"),
                    ChatToolNote(name = "apply_design", label = "Applied a change", changedDesign = true),
                ),
            ),
        )
        thread = thread.plus(
            ChatMessage(role = ChatRole.USER, text = "now make it blink", atMs = 12L, imageCount = 2),
        )

        val restored = readTranscript(write("thread.json", ChatTranscriptCodec.encode(thread)))

        assertEquals(thread, restored)
        assertEquals(3, restored!!.messages.size)
        assertEquals(listOf(10L, 11L, 12L), restored.messages.map { it.atMs })
        assertTrue(restored.messages[1].tools.any { it.changedDesign })
        // The images themselves are deliberately not stored; the count is.
        assertEquals(2, restored.messages[2].imageCount)
    }

    /**
     * Reopening a design continues the conversation, which is the whole reason
     * these files exist. The history handed to the model is what was *said* —
     * tool notes are not replayed (a `function_call` without its output is a
     * protocol error) and neither are blank turns.
     */
    @Test
    fun `a restored conversation is the context the next turn is sent with`() {
        val thread = ChatTranscript(designId = "abc123")
            .plus(ChatMessage(role = ChatRole.USER, text = "draw a smiley", atMs = 1L))
            .plus(ChatMessage(role = ChatRole.ASSISTANT, text = "Here you go.", atMs = 2L))
            .plus(ChatMessage(role = ChatRole.ASSISTANT, text = "", atMs = 3L))

        val restored = readTranscript(write("resumed.json", ChatTranscriptCodec.encode(thread)))!!

        val input = restored.asInput()
        assertEquals("the blank turn is not replayed", 2, input.size)
    }

    // endregion

    // region helpers

    private val transcript = ChatTranscript(
        designId = "abc123",
        messages = listOf(
            ChatMessage(role = ChatRole.USER, text = "make it rounder", atMs = 1L),
            ChatMessage(role = ChatRole.ASSISTANT, text = "Done.", atMs = 2L),
        ),
    )

    /** A JVM temp directory, as `DesignTransferTest` and `DesignStoreTest` use. */
    private fun dir(): File =
        File(System.getProperty("java.io.tmpdir"), "gmtc-chat-test").apply { mkdirs() }

    /**
     * An empty directory of its own, for the tests that assert on what is *left*
     * in one. [dir] is shared and accumulates files from every test above it.
     */
    private fun freshDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), name).apply {
            deleteRecursively()
            mkdirs()
        }

    private fun write(name: String, text: String): File =
        File(dir(), name).apply { writeText(text) }

    // endregion
}
