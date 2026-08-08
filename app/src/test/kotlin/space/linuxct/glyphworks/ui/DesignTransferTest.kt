package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename
import java.io.File
import java.io.InputStream

/**
 * The plumbing around the codec, which is where the codec's guarantees can still
 * be thrown away.
 *
 * `DesignCodec` is already covered by `DesignCodecTest`; nothing here re-tests
 * validation. What is tested here is everything the import/export/share path adds
 * on top of it, and every one of these is a way a correct codec could still lose:
 *
 * - a design name reaching a filesystem as a *path* rather than as a name,
 * - a hostile file being read whole before the size cap is applied,
 * - an import quietly overwriting a design already on the phone, or re-stamping
 *   somebody else's artwork with the importing user's name,
 * - the share cache growing without bound.
 *
 * All of it is plain JVM: the functions under test take a `File`, a `String` or a
 * `Design` and touch no `android.*`.
 */
class DesignTransferTest {
    // region filename sanitising

    @Test
    fun `an ordinary name becomes an ordinary filename`() {
        assertEquals("Slow-Ember.json", designFileName(design(name = "Slow Ember")))
    }

    @Test
    fun `path separators cannot survive into a filename`() {
        assertEquals("etc-passwd", sanitiseFileBaseName("/etc/passwd"))
        assertEquals("Windows-System32", sanitiseFileBaseName("\\Windows\\System32"))
        assertFalse(designFileName(design(name = "a/b/c")).contains('/'))
        assertFalse(designFileName(design(name = "a\\b\\c")).contains('\\'))
    }

    @Test
    fun `control characters and quotes are removed`() {
        assertEquals("a-b", sanitiseFileBaseName("a\u0000b"))
        assertEquals("a-b", sanitiseFileBaseName("a\nb"))
        assertEquals("name", sanitiseFileBaseName("\"name\""))
        assertEquals("a-b", sanitiseFileBaseName("a:b"))
    }

    @Test
    fun `an absurd name is capped`() {
        val out = sanitiseFileBaseName("x".repeat(10_000))
        assertEquals(48, out.length)
        // And the cap is applied to the base, so the extension is never lost.
        assertTrue(designFileName(design(name = "x".repeat(10_000))).endsWith(".json"))
    }

    @Test
    fun `a name that sanitises to nothing falls back to the id`() {
        val d = design(name = "🌙🌙🌙")
        assertEquals("0123456789abcdef0123456789abcdef.json", designFileName(d))
    }

    @Test
    fun `every hostile name produces a usable, contained filename`() {
        val hostile = listOf(
            "../../../etc/passwd", "..", ".", "", "   ", "/", "\\", "C:\\Users",
            "a\u0000b", "\n\r\t", "*?<>|", ".hidden", "..hidden", "con", "🌙",
            "name.json", "x".repeat(500),
        )
        for (raw in hostile) {
            val out = designFileName(design(name = raw))
            assertFalse(out, out.contains('/'))
            assertFalse(out, out.contains('\\'))
            assertFalse(out, out.contains('\u0000'))
            assertFalse(out, out.startsWith("."))
            assertFalse(out, out.startsWith("-"))
            assertTrue(out, out.endsWith(".json"))
            // A filename, not a path: what is left addresses one file in the
            // directory the user picked.
            assertEquals(out, File(out).name)
            assertTrue(out, out.length > ".json".length)
        }
    }

    // endregion

    // region the bounded read

    @Test
    fun `an endless stream is rejected without being consumed`() {
        // The defence the whole import path rests on. A JSON bomb is not large on
        // disk; it is large once read. If the cap were applied AFTER reading, this
        // stream would never return.
        val stream = CountingEndlessStream()

        val result = DesignCodec.decode(stream)

        assertEquals(DesignCodec.REASON_TOO_LARGE, invalidReason(result))
        assertTrue(
            "read ${stream.produced} bytes",
            stream.produced <= DesignCodec.MAX_BYTES + READ_BUFFER_SLACK,
        )
    }

    @Test
    fun `a real design well under the cap is read from a stream`() {
        val encoded = DesignCodec.encode(design())
        assertTrue(encoded.length < DesignCodec.MAX_BYTES)

        val result = DesignCodec.decode(encoded.byteInputStream())

        assertTrue(result is DesignCodec.Result.Ok)
    }

    // endregion

    // region import rules

    @Test
    fun `an import always gets a fresh id`() {
        val incoming = design(id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val stored = importedDesign(incoming, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", NOW)

        // Unconditionally, not only on collision: DesignStore.save overwrites by
        // contract, so keeping the incoming id would let a file somebody sent
        // replace a design already on this phone.
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", stored.id)
        assertNotEquals(incoming.id, stored.id)
        assertTrue(DesignCodec.isSafeId(stored.id))
    }

    @Test
    fun `an import preserves createdAt and takes the import time as modifiedAt`() {
        val incoming = design(createdAt = "2020-01-01T00:00:00Z", modifiedAt = "2020-01-02T00:00:00Z")

        val stored = importedDesign(incoming, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", NOW)

        assertEquals("2020-01-01T00:00:00Z", stored.createdAt)
        assertEquals(NOW, stored.modifiedAt)
    }

    @Test
    fun `an imported design is otherwise untouched and still valid`() {
        val incoming = design(name = "Slow Ember", author = "someone-else")

        val stored = importedDesign(incoming, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", NOW)

        assertEquals(incoming.name, stored.name)
        assertEquals(incoming.kind, stored.kind)
        assertEquals(incoming.variants, stored.variants)
        // And what comes out is something the store will accept — the import path
        // must never produce a design its own validator would refuse.
        assertTrue(DesignCodec.validate(stored) is DesignCodec.Result.Ok)
    }

    // endregion

    // region share cache hygiene

    @Test
    fun `stale shared copies are deleted and fresh ones are kept`() {
        val dir = tempDir()
        val fresh = file(dir, "fresh.json", NOW_MS - 60_000)
        val stale = file(dir, "stale.json", NOW_MS - 2 * DAY_MS)

        val deleted = pruneSharedCache(dir, NOW_MS, DAY_MS)

        assertEquals(1, deleted)
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    // endregion

    // region helpers

    private fun design(
        id: String = "0123456789abcdef0123456789abcdef",
        name: String = "Slow Ember",
        author: String = "",
        createdAt: String = "2026-07-30T12:00:00Z",
        modifiedAt: String = "2026-07-30T12:00:00Z",
    ): Design = Design(
        id = id,
        name = name,
        author = author,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            PokemonCodename.BELLSPROUT.codename to DesignVariant(
                frames = listOf(DesignFrame(cells = DesignFrames.blank(PokemonCodename.BELLSPROUT))),
            ),
        ),
    )

    private fun invalidReason(result: DesignCodec.Result): String =
        (result as DesignCodec.Result.Invalid).reason

    private fun tempDir(): File =
        File.createTempFile("glyphworks-share", null).let { probe ->
            probe.delete()
            probe.mkdirs()
            probe.deleteOnExit()
            probe
        }

    private fun file(dir: File, name: String, modified: Long): File =
        File(dir, name).apply {
            writeText("{}")
            setLastModified(modified)
            deleteOnExit()
        }

    /**
     * A stream that never ends, counting what it has handed out. Anything that
     * reads it to exhaustion hangs, which is the point: the assertion is that the
     * codec stops.
     */
    private class CountingEndlessStream : InputStream() {
        var produced = 0L
            private set

        override fun read(): Int {
            produced++
            return ' '.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill(' '.code.toByte(), off, off + len)
            produced += len
            return len
        }
    }

    private companion object {
        const val NOW = "2026-07-30T12:34:56Z"
        const val NOW_MS = 1_785_000_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * The bounded read gives up one byte past the cap, but it reads in 8 KB
         * blocks, so the last block may carry it slightly over. Anything within
         * one block is bounded; a stream read to exhaustion would not be.
         */
        const val READ_BUFFER_SLACK = 8 * 1024
    }
}
