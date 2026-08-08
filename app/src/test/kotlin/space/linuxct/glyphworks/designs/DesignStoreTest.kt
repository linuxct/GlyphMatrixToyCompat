package space.linuxct.glyphworks.designs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [replaceViaBackup], which is the last step of every design save and the one
 * place in this app where a user's artwork can be destroyed.
 *
 * The version this replaced deleted the existing file before the replacement was
 * known to land, and deleted the temp too if the retry failed — two failed
 * renames and the design was gone, with `save` returning nothing worse than
 * false. So the assertion these tests actually care about is not the return
 * value: it is that **after every possible outcome, a complete design is still
 * readable somewhere**. [assertRecoverable] is that check, and it runs on every
 * failure path below.
 *
 * `DesignStore` itself takes a `Context` and cannot be instantiated here, which
 * is exactly why the rename dance was pulled out into a pure function that takes
 * the three files and the rename operation. The files are real (a JVM temp
 * directory, as `DesignTransferTest` uses); only the rename is faked, because
 * "renameTo returns false" is not a state a real filesystem can be asked for on
 * demand — and it is the state the whole function exists to survive.
 */
class DesignStoreTest {
    private val old = """{"design":"the one already on disk"}"""
    private val new = """{"design":"the one being saved"}"""

    // region the happy path

    @Test
    fun `a rename that replaces outright is the only step that runs`() {
        val f = fixture(withTarget = true)
        var renames = 0

        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { _, _ -> renames++; false })

        assertTrue(ok)
        assertEquals("POSIX replaces in one move; nothing else may be attempted", 1, renames)
        assertEquals(new, f.target.readText())
        assertFalse("no backup is made when none is needed", f.backup.exists())
    }

    // endregion

    // region the first rename fails

    @Test
    fun `a rename that cannot replace goes round by the backup`() {
        val f = fixture(withTarget = true)
        // The filesystems this fallback exists for: renameTo refuses to land on
        // an existing name, but moving to a free one is fine.
        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { _, to -> to.exists() })

        assertTrue(ok)
        assertEquals(new, f.target.readText())
        assertFalse("a backup that has been superseded is dropped", f.backup.exists())
    }

    @Test
    fun `a failure with nothing on disk to protect reports it and stops`() {
        val f = fixture(withTarget = false)
        var renames = 0

        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { _, _ -> renames++; true })

        assertFalse(ok)
        // No target means no recovery to attempt and nothing at risk: one try.
        assertEquals(1, renames)
        assertFalse(f.target.exists())
        assertFalse(f.backup.exists())
    }

    // endregion

    // region the second rename fails too

    @Test
    fun `a backup is restored when the replacement will not land`() {
        val f = fixture(withTarget = true)
        // Everything works except putting the temp in place — so the design gets
        // moved aside and then has to come back.
        val ok = replaceViaBackup(f.tmp, f.target, f.backup, rename { from, _ -> from == f.tmp })

        assertFalse(ok)
        assertEquals("the previous design is back under its own name", old, f.target.readText())
        assertFalse("and nothing is left lying beside it", f.backup.exists())
        assertRecoverable(f)
    }

    // endregion

    // region leftovers

    // endregion

    // region deletion hooks

    /**
     * The hook that replaced `DesignStore`'s import of `ai/ChatStore`.
     *
     * `designs/` is a storage layer the always-on display reads before the first
     * unlock, and `ai/` is an optional feature that may one day not be in the
     * build at all, so the dependency had to point the other way. What must not
     * change is the behaviour it was there for: deleting a design takes
     * everything keyed by its id with it, and no failure of that second part can
     * cost the caller the first.
     */
    @Test
    fun `a registered hook is told the id of every design deleted`() {
        val f = fixture(withTarget = true)
        val hooks = DesignDeletionHooks()
        val seen = mutableListOf<String>()
        hooks.add { seen.add(it) }

        assertTrue(deleteDesignFile(f.target, "abc", hooks))

        assertFalse(f.target.exists())
        assertEquals(listOf("abc"), seen)
    }

    @Test
    fun `a hook that throws costs the caller nothing`() {
        val f = fixture(withTarget = true)
        val hooks = DesignDeletionHooks()
        val seen = mutableListOf<String>()
        // The realistic failure: credential-protected storage while the device is
        // locked, or a file another process holds open.
        hooks.add { throw IllegalStateException("locked") }
        hooks.add { seen.add(it) }

        // Not `assertThrows`: the whole point is that this returns.
        assertTrue(deleteDesignFile(f.target, "abc", hooks))

        assertFalse(f.target.exists())
        assertEquals("one listener failing must not silence the next", listOf("abc"), seen)
    }

    // endregion

    // region what counts as a stored design

    @Test
    fun `every name the store writes maps back to its design id`() {
        assertEquals("abc123", storedDesignId("abc123.json"))
        // A design that only exists under its backup name is still the user's
        // design: `recoverBackups` puts it back on the next listing. Reading it as
        // absent for even a moment would have anything keyed by its id swept up.
        assertEquals("abc123", storedDesignId("abc123.json.bak"))
        assertEquals("abc123", storedDesignId("abc123.json.tmp"))
    }

    @Test
    fun `nothing else in the directory is read as a design id`() {
        val notOurs = listOf(
            "abc123",
            "abc123.txt",
            ".json",
            "abc 123.json",
            "../secrets.json",
            "sub/abc.json",
            "abc.json.bak.bak",
            "",
        )

        notOurs.forEach { assertNull(it, storedDesignId(it)) }
    }

    // endregion

    // region helpers

    /**
     * The guarantee, stated once: whatever happened, a caller can still open a
     * complete design — under its real name, or under the backup name the store's
     * read path adopts.
     */
    private fun assertRecoverable(f: Fixture) {
        val readable = when {
            f.target.exists() -> f.target.readText()
            f.backup.exists() -> f.backup.readText()
            else -> null
        }
        assertTrue(
            "no complete design left on disk — this is the data loss the function exists to prevent",
            readable == old || readable == new,
        )
    }

    private class Fixture(val tmp: File, val target: File, val backup: File)

    /** A temp directory holding a flushed `.tmp`, and optionally a live design. */
    private fun fixture(withTarget: Boolean): Fixture {
        val dir = File.createTempFile("glyphworks-store", null).let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }
        val target = File(dir, "abc.json")
        val fixture = Fixture(
            tmp = File(dir, "abc.json.tmp").apply { writeText(new); deleteOnExit() },
            target = target.apply { deleteOnExit() },
            backup = File(dir, "abc.json.bak").apply { deleteOnExit() },
        )
        if (withTarget) target.writeText(old)
        return fixture
    }

    /**
     * A rename that really renames, except where [fail] says it does not. Faking
     * the failure rather than the success keeps every end state above a genuine
     * on-disk one, which is what [assertRecoverable] has to be able to read.
     */
    private fun rename(fail: (from: File, to: File) -> Boolean): (File, File) -> Boolean =
        { from, to -> if (fail(from, to)) false else from.renameTo(to) }

    // endregion
}
