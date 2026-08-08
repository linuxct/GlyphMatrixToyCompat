package space.linuxct.glyphworks.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Production scheduler: a single background HandlerThread hosts the tickers
 * and all ScreenManager/screen work; GlyphLink hops the final SDK push to
 * its own "glyph-io" looper. Neither is the main thread, so a blocking Glyph
 * binder call can never stall a frame of the UI.
 */
class AndroidRenderScheduler : RenderScheduler {

    private val thread = HandlerThread("compositor-worker").apply { start() }
    private val handler = Handler(thread.looper)
    private var ticker: Runnable? = null

    override fun setTicker(intervalMs: Long, tick: () -> Unit) {
        run {
            ticker?.let { handler.removeCallbacks(it) }
            val r = object : Runnable {
                override fun run() {
                    if (ticker !== this) return
                    tick()
                    if (ticker === this) handler.postDelayed(this, intervalMs)
                }
            }
            ticker = r
            handler.post(r)
        }
    }

    override fun clearTicker() {
        run {
            ticker?.let { handler.removeCallbacks(it) }
            ticker = null
        }
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable {
        val r = Runnable { action() }
        handler.postDelayed(r, delayMs)
        return object : Cancelable {
            override fun cancel() {
                handler.removeCallbacks(r)
            }
        }
    }

    override fun run(action: () -> Unit) {
        if (Looper.myLooper() == thread.looper) action() else handler.post(action)
    }
}
