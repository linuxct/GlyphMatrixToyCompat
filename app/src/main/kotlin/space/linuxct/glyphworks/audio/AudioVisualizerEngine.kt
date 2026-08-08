package space.linuxct.glyphworks.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.SpectrumPort
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The process's single audiofx.Visualizer(0) wrapper (output mix, capture
 * size 256, FFT reads). Both the ambient audio layer and the standalone
 * visualizer screen call bands() so only one Visualizer instance ever exists.
 *
 * Failure-tolerant by contract: without RECORD_AUDIO or when the engine
 * cannot start (e.g. mic appops blocked in background), bands() returns null
 * and callers fall back to their idle/permission patterns. Construction is
 * retried at most every 30 s, never per tick. Self-managing lifecycle:
 * releases the Visualizer after 5 s without polls.
 */
class AudioVisualizerEngine(
    private val app: Context,
    private val prefs: Prefs,
) : SpectrumPort {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var captureBuf = ByteArray(0)
    private var smoothed = FloatArray(0)
    private var bandEdges = IntArray(0) // log-spaced FFT-bin edges, rebuilt when n changes

    @Volatile private var lastPollAt = 0L
    @Volatile private var lastFailAt = 0L

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@AudioVisualizerEngine) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    releaseLocked()
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    @Synchronized
    override fun bands(n: Int): FloatArray? {
        lastPollAt = System.currentTimeMillis()
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val v = visualizer ?: create() ?: return null
        if (captureBuf.size != v.captureSize) captureBuf = ByteArray(v.captureSize)
        val status = try {
            v.getFft(captureBuf)
        } catch (e: Exception) {
            Log.w(TAG, "getFft failed", e)
            releaseLocked()
            lastFailAt = System.currentTimeMillis()
            return null
        }
        if (status != Visualizer.SUCCESS) return null
        return toBands(captureBuf, n)
    }

    private fun create(): Visualizer? {
        val now = System.currentTimeMillis()
        if (now - lastFailAt < RETRY_COOLDOWN_MS) return null
        return try {
            Visualizer(0).apply {
                captureSize = CAPTURE_SIZE
                enabled = true
            }.also {
                visualizer = it
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
                Log.d(TAG, "Visualizer started")
            }
        } catch (e: Exception) {
            // Expected when background mic capture is blocked by appops.
            Log.w(TAG, "Visualizer unavailable: ${e.message}")
            lastFailAt = now
            null
        }
    }

    private fun releaseLocked() {
        visualizer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (_: Exception) {
            }
            Log.d(TAG, "Visualizer released")
        }
        visualizer = null
    }

    /**
     * FFT bytes -> n bands 0..1. Layout per Visualizer docs: [0]=DC, [1]=Nyquist,
     * then (re, im) pairs.
     *
     * Band mapping is LOGARITHMIC (each bar ~ one musical octave), because a
     * linear split parks nearly all musical energy in the leftmost bar and
     * leaves the right half of the display permanently dark. Two further
     * perceptual corrections: a high-frequency tilt (music rolls off with
     * frequency, so the upper bars get extra gain) and square-root loudness
     * compression (lifts quiet detail, tames booming lows).
     *
     * Rise/fall smoothing (visualizerTuning 1..6, default 1 = calmest; higher
     * = snappier) keeps the 50 ms tick calm: bars glide up and sink over a few
     * hundred milliseconds instead of snapping to each FFT frame.
     */
    private fun toBands(fft: ByteArray, n: Int): FloatArray {
        val tuning = prefs.getInt(PrefKeys.VISUALIZER_TUNING, PrefKeys.VISUALIZER_TUNING_DEF).coerceIn(1, 6)
        val gain = 0.75f + tuning * 0.125f
        val attack = 0.25f + tuning * 0.05f
        val decay = 0.84f + (6 - tuning) * 0.015f

        val pairs = (fft.size - 2) / 2
        // ~2/3 of Nyquist (~16 kHz at 48 kHz output) — above that there is
        // rarely anything to show.
        val maxBin = (pairs * 2 / 3).coerceAtLeast(n + 1)
        if (bandEdges.size != n + 1) bandEdges = buildLogEdges(n, maxBin)
        if (smoothed.size != n) smoothed = FloatArray(n)

        val out = FloatArray(n)
        var rawMax = 0f
        for (band in 0 until n) {
            var sum = 0f
            var count = 0
            for (bin in bandEdges[band] until bandEdges[band + 1]) {
                if (bin >= pairs) break
                val re = fft[2 + bin * 2].toFloat()
                val im = fft[3 + bin * 2].toFloat()
                sum += hypot(re, im) / 128f
                count++
            }
            val tilt = 1f + HF_TILT * band / (n - 1).coerceAtLeast(1)
            val energy = if (count > 0) (sum / count) * gain * tilt else 0f
            val raw = min(1f, kotlin.math.sqrt(energy.coerceAtLeast(0f)))
            if (raw > rawMax) rawMax = raw
            val prev = smoothed[band]
            smoothed[band] = if (raw > prev) {
                prev + (raw - prev) * attack // eased rise
            } else {
                max(raw, prev * decay) // slow sink
            }
            out[band] = smoothed[band]
        }
        if (rawMax < TRUE_SILENCE) {
            // True silence: collapse the decay tails immediately so the
            // ambient audio layer reverts within one tick.
            smoothed.fill(0f)
            out.fill(0f)
        }
        return out
    }

    /** Monotonic log-spaced bin edges from bin 1 to [maxBin], n bands. */
    private fun buildLogEdges(n: Int, maxBin: Int): IntArray {
        val edges = IntArray(n + 1)
        edges[0] = 1
        val lnMax = kotlin.math.ln(maxBin.toDouble())
        for (i in 1..n) {
            val ideal = kotlin.math.exp(lnMax * i / n).toInt()
            edges[i] = maxOf(edges[i - 1] + 1, ideal)
        }
        edges[n] = edges[n].coerceAtMost(maxBin).coerceAtLeast(edges[n - 1] + 1)
        return edges
    }

    private companion object {
        const val TAG = "AudioVisualizer"
        const val CAPTURE_SIZE = 256
        const val IDLE_STOP_MS = 5000L
        const val RETRY_COOLDOWN_MS = 30_000L
        const val TRUE_SILENCE = 0.02f

        /** Extra gain on the highest band (linearly interpolated from 0). */
        const val HF_TILT = 1.6f
    }
}
