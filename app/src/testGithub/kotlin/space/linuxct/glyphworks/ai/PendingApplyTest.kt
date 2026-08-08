package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.ai.PENDING_APPLY_FORMAT
import space.linuxct.glyphworks.core.ai.PENDING_APPLY_FORMAT_VERSION
import space.linuxct.glyphworks.core.ai.PENDING_APPLY_MAX_AGE_MS
import space.linuxct.glyphworks.core.ai.PendingApply
import space.linuxct.glyphworks.core.ai.PendingApplyCodec
import space.linuxct.glyphworks.core.ai.PendingApplyVerdict
import space.linuxct.glyphworks.core.ai.pendingApplyVerdict
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename
import java.io.File

/**
 * A design the assistant finished with nobody watching: the rule that decides
 * whether it may still be applied, and the file it waits in.
 *
 * The rule is the part worth proving. It is the one decision in this feature that
 * can destroy something either way round — the model's drawing if it is too
 * strict, the *user's own* if it is too loose — and it is invisible when it goes
 * wrong, because both outcomes look like "the design is what it is".
 *
 * The path derivation is here for `ChatStoreTest`'s reason, unchanged: the id
 * arrives from a design document, a design document can be a file a stranger
 * wrote, and `../../shared_prefs/openai_auth` must not be able to name one of
 * these.
 */
class PendingApplyTest {
    private fun record(
        baseModifiedAt: String = MODIFIED_AT,
        atMs: Long = NOW,
    ) = PendingApply(
        designId = "abc123",
        baseModifiedAt = baseModifiedAt,
        atMs = atMs,
        design = design(),
    )

    private fun design() = Design(
        id = "abc123",
        name = "Smiley",
        createdAt = MODIFIED_AT,
        modifiedAt = MODIFIED_AT,
        variants = mapOf(
            PokemonCodename.BELLSPROUT.codename to DesignVariant(
                frames = listOf(DesignFrame(durationMs = 120, cells = "0".repeat(169))),
            ),
        ),
    )

    // region the conflict rule

    @Test
    fun `an untouched design takes the change`() {
        assertEquals(
            PendingApplyVerdict.APPLY,
            pendingApplyVerdict(record(), MODIFIED_AT, NOW),
        )
    }

    /**
     * The case the rule exists for. The user closed the editor mid-turn, went
     * back in and drew something themselves, and closed it again. Their strokes
     * are newer than the model's draft, and a whole-document replace would erase
     * every one of them with nothing on screen to say so.
     */
    @Test
    fun `a design edited since the change was recorded is left alone`() {
        assertEquals(
            PendingApplyVerdict.CONFLICT,
            pendingApplyVerdict(record(), "2026-02-02T00:00:00Z", NOW),
        )
    }

    @Test
    fun `a design that is gone takes nothing`() {
        assertEquals(
            PendingApplyVerdict.MISSING,
            pendingApplyVerdict(record(), null, NOW),
        )
    }

    @Test
    fun `a change nobody came back for expires`() {
        val old = record(atMs = NOW - PENDING_APPLY_MAX_AGE_MS - 1)

        assertEquals(PendingApplyVerdict.EXPIRED, pendingApplyVerdict(old, MODIFIED_AT, NOW))
    }

    // endregion

    // region the record on disk

    @Test
    fun `a record round-trips with its design intact`() {
        val decoded = PendingApplyCodec.decode(PendingApplyCodec.encode(record()))

        assertEquals(record(), decoded)
        assertEquals(PENDING_APPLY_FORMAT, decoded!!.format)
        assertEquals(PENDING_APPLY_FORMAT_VERSION, decoded.formatVersion)
        assertEquals("Smiley", decoded.design.name)
        assertEquals(1, decoded.design.variants.size)
    }

    @Test
    fun `an ordinary design id names a file`() {
        assertEquals("abc123.json", pendingApplyFileName("abc123"))
    }

    @Test
    fun `nothing that could escape the directory can name one`() {
        listOf("..", "../secrets", "../../shared_prefs/openai_auth", "a/b", "dot.ted", "")
            .forEach { assertNull(it, pendingApplyFileName(it)) }
    }

    @Test
    fun `a truncated file is nothing waiting, not a crash`() {
        val file = File.createTempFile("pending", ".json")
        file.deleteOnExit()
        file.writeText(PendingApplyCodec.encode(record()).take(60))

        assertNull(readPendingApply(file))
    }

    @Test
    fun `a good file reads back`() {
        val file = File.createTempFile("pending", ".json")
        file.deleteOnExit()
        file.writeText(PendingApplyCodec.encode(record()))

        assertNotNull(readPendingApply(file))
    }

    /**
     * The backup goes with the record, for `deleteTranscript`'s reason: a record
     * surviving under its backup name would be picked up by the recovery path and
     * land a second time, on a design the user has since moved on from.
     */
    @Test
    fun `deleting takes the backup and the temp with it`() {
        val dir = createTempDirectory()
        File(dir, "abc123.json").writeText("{}")
        File(dir, "abc123.json.bak").writeText("{}")
        File(dir, "abc123.json.tmp").writeText("{}")
        File(dir, "other.json").writeText("{}")

        assertTrue(deletePendingApply(dir, "abc123.json"))

        assertFalse(File(dir, "abc123.json").exists())
        assertFalse(File(dir, "abc123.json.bak").exists())
        assertFalse(File(dir, "abc123.json.tmp").exists())
        assertTrue("nothing else in the directory is touched", File(dir, "other.json").exists())
    }

    // endregion

    private fun createTempDirectory(): File {
        val dir = File.createTempFile("pending-dir", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private companion object {
        const val MODIFIED_AT = "2026-01-01T00:00:00Z"
        const val NOW = 1_800_000_000_000L
    }
}
