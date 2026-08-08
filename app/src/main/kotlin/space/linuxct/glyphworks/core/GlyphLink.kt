package space.linuxct.glyphworks.core

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager

/**
 * Sole owner of the process-wide GlyphMatrixManager singleton.
 *
 * Lease pattern: components acquire()/release() instead of touching the
 * manager. init() runs on refcount 0->1; turnOff()+unInit() only on 1->0
 * after a 3 s grace period (the system rebinds the AOD toy on transitions —
 * immediate teardown would thrash bind/register). register() is re-issued on
 * every onServiceConnected, frames are queued until registered, every SDK
 * call is marshalled to the "glyph-io" looper and wrapped for GlyphException.
 * setGlyphMatrixTimeout is deliberately never called — the system's default
 * blanking policy stays in charge.
 *
 * Threading: every SDK entry point we use is a *synchronous* binder round-trip
 * to com.nothing.thirdparty.GlyphService — the generated IGlyphService proxy
 * transacts with flags=0, never FLAG_ONEWAY, and we do not control that AIDL.
 * setMatrixColors alone runs 20-30x/s at the toys' tick rates, so doing it on
 * the UI thread ate most of a 90 Hz (11.11 ms) frame budget and showed up as
 * "Slow UI thread" / "High input latency" in gfxinfo. All manager access
 * therefore runs on a private HandlerThread instead. The single-thread
 * confinement that used to be provided by the main looper is unchanged — only
 * the identity of the thread differs — so `manager`, `ready`, `refCount`,
 * `pendingFrame`, `lastFrame`, `firstFrameLogged` and `teardown` remain
 * touched from exactly one thread and need no further synchronisation.
 */
class GlyphLink(private val app: Context) {

    /**
     * Started eagerly and never quit: GlyphLink is a Core singleton that lives
     * for the whole process, so there is no lifecycle at which stopping this
     * thread would be correct. One idle looper thread is the intended cost.
     */
    private val ioThread = HandlerThread("glyph-io").apply { start() }

    /** Owns all mutable state below and every GlyphMatrixManager call. */
    private val glyph = Handler(ioThread.looper)

    private var manager: GlyphMatrixManager? = null
    private var refCount = 0
    private var pendingFrame: IntArray? = null
    private var lastFrame: IntArray? = null
    private var teardown: Runnable? = null

    /** Full rebind after a transient failure (register=false, dead push, service restart). */
    private val recovery = Runnable {
        if (refCount > 0 && !ready) {
            disconnect()
            connect()
        }
    }

    private fun scheduleRecovery() {
        glyph.removeCallbacks(recovery)
        glyph.postDelayed(recovery, RECONNECT_DELAY_MS)
    }

    @Volatile
    var ready = false
        private set

    private var firstFrameLogged = false

    /** 25 (Phone 3), 13 (Phone 4a Pro) or 0 (no Glyph Matrix). */
    val matrixLength: Int = Common.getDeviceMatrixLength()
    val isSupported: Boolean = matrixLength == 25 || matrixLength == 13

    /** Render size used across the app; 13 on unsupported devices so previews/tests still work. */
    val size: Int = if (isSupported) matrixLength else 13

    inner class Lease internal constructor(private val tag: String) {
        private var released = false

        fun release() {
            glyph.post {
                if (released) return@post
                released = true
                doRelease(tag)
            }
        }
    }

    fun acquire(tag: String): Lease {
        glyph.post { doAcquire(tag) }
        return Lease(tag)
    }

    /** Queues [frame] for the matrix; safe to call from any thread. Latest frame wins while disconnected. */
    fun pushFrame(frame: IntArray) {
        glyph.post {
            val mgr = manager
            if (ready && mgr != null) {
                try {
                    mgr.setMatrixFrame(frame)
                    lastFrame = frame
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        DebugLog.i(C, "first frame delivered to the matrix")
                    }
                } catch (e: Exception) {
                    // GlyphException (checked) or dead service: queue the frame,
                    // drop readiness and rebuild the binding so the matrix does
                    // not stay dark for the life of the process.
                    DebugLog.w(C, "setMatrixFrame failed: $e — scheduling recovery")
                    ready = false
                    pendingFrame = frame
                    scheduleRecovery()
                }
            } else {
                pendingFrame = frame
            }
        }
    }

    private fun doAcquire(tag: String) {
        refCount++
        DebugLog.d(C, "acquire($tag) -> refCount=$refCount")
        teardown?.let { glyph.removeCallbacks(it) }
        teardown = null
        if (manager == null) connect()
    }

    private fun doRelease(tag: String) {
        refCount = (refCount - 1).coerceAtLeast(0)
        DebugLog.d(C, "release($tag) -> refCount=$refCount")
        if (refCount > 0) return
        val t = Runnable {
            if (refCount == 0) disconnect()
            teardown = null
        }
        teardown = t
        glyph.postDelayed(t, TEARDOWN_GRACE_MS)
    }

    private fun connect() {
        if (!isSupported) {
            DebugLog.w(C, "no Glyph Matrix on this device (${android.os.Build.MODEL}); rendering disabled")
            return
        }
        DebugLog.i(C, "connecting to the Glyph service")
        try {
            val mgr = GlyphMatrixManager.getInstance(app.applicationContext)
            manager = mgr
            mgr.init(callback)
        } catch (e: Exception) {
            DebugLog.w(C, "GlyphMatrixManager init failed: $e")
            manager = null
        }
    }

    private fun disconnect() {
        val mgr = manager ?: return
        ready = false
        manager = null
        pendingFrame = null // lastFrame survives so reconnects can restore the display
        try {
            mgr.turnOff()
        } catch (e: Exception) {
            DebugLog.w(C, "turnOff failed: $e")
        }
        try {
            mgr.unInit()
        } catch (e: Exception) {
            DebugLog.w(C, "unInit failed: $e")
        }
        DebugLog.i(C, "disconnected")
    }

    /**
     * Delivered on the MAIN thread, not on whatever thread called init():
     * GlyphMatrixManager.init() uses the 3-arg Context.bindService(), which
     * ContextImpl dispatches through the main-thread handler, and the SDK's
     * RemoteServiceConnection invokes this Callback inline from there. Both
     * methods therefore hop to [glyph] before touching any state below — that
     * hop is what keeps the confinement invariant true.
     *
     * The hop also safely publishes the SDK's own non-volatile mService field,
     * which RemoteServiceConnection writes on the main thread just before
     * calling us and our glyph thread reads inside register()/setMatrixFrame().
     */
    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            glyph.post {
                val mgr = manager ?: return@post
                val device = if (matrixLength == 25) Glyph.DEVICE_23112 else Glyph.DEVICE_25111p
                val ok = try {
                    mgr.register(device)
                } catch (e: Exception) {
                    DebugLog.w(C, "register threw: $e")
                    false
                }
                DebugLog.i(C, "glyph service connected, register($device) = $ok")
                ready = ok
                firstFrameLogged = false
                if (ok) {
                    // Re-deliver the queued or last-shown frame so static screens
                    // reappear immediately after a service restart (the
                    // ScreenManager dedup layer would otherwise suppress them).
                    val frame = pendingFrame ?: lastFrame
                    pendingFrame = null
                    frame?.let { pushFrame(it) }
                } else {
                    scheduleRecovery()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            glyph.post {
                DebugLog.w(C, "glyph service disconnected")
                ready = false
                if (refCount > 0 && manager != null) {
                    // The remote Glyph service died; rebuild the binding after a
                    // short delay so a live session comes back on its own.
                    scheduleRecovery()
                }
            }
        }
    }

    private companion object {
        const val C = "GlyphLink"
        const val TEARDOWN_GRACE_MS = 3000L
        const val RECONNECT_DELAY_MS = 2000L
    }
}
