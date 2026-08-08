package space.linuxct.glyphworks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import java.io.File

/**
 * ASCII golden-file harness for matrix frames.
 *
 * Charset by brightness: ' ' = 0, '.' = 1..1365, '+' = 1366..2730, '#' = 2731..4095.
 * Goldens live in app/src/test/resources/goldens/<name>.txt.
 * Regenerate with: ./gradlew :app:testDebugUnitTest -DupdateGoldens=true
 */
object GoldenAscii {

    private val goldenDir: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        if (dir.name != "app" && File(dir, "app").isDirectory) dir = File(dir, "app")
        File(dir, "src/test/resources/goldens")
    }

    private val updateMode: Boolean
        get() = System.getProperty("updateGoldens") == "true"

    fun render(frame: IntArray, size: Int): String {
        val sb = StringBuilder()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = frame[y * size + x]
                sb.append(
                    when {
                        v <= 0 -> ' '
                        v <= 1365 -> '.'
                        v <= 2730 -> '+'
                        else -> '#'
                    }
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun assertFrameValid(frame: IntArray, size: Int) {
        assertEquals("frame length must be size^2", size * size, frame.size)
        frame.forEachIndexed { i, v ->
            assertTrue("cell $i out of range: $v", v in 0..MAX_BRIGHTNESS)
        }
    }

    /** Validates the frame and compares (or updates) its ASCII golden. */
    fun check(name: String, frame: IntArray, size: Int) {
        assertFrameValid(frame, size)
        val actual = render(frame, size)
        val file = File(goldenDir, "$name.txt")
        if (updateMode) {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.isFile) {
            fail(
                "Missing golden '$name'. Run ./gradlew :app:testDebugUnitTest -DupdateGoldens=true " +
                    "to generate, then review the ASCII output.\nActual frame:\n$actual"
            )
        }
        val expected = file.readText()
        if (expected != actual) {
            fail("Golden mismatch '$name'.\nExpected:\n$expected\nActual:\n$actual")
        }
    }
}
