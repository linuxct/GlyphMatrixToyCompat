package space.linuxct.glyphmatrixtoycompat

import space.linuxct.glyphmatrixtoycompat.core.AzimuthPort
import space.linuxct.glyphmatrixtoycompat.core.BatteryPort
import space.linuxct.glyphmatrixtoycompat.core.Cancelable
import space.linuxct.glyphmatrixtoycompat.core.ClockPort
import space.linuxct.glyphmatrixtoycompat.core.ConnectionState
import space.linuxct.glyphmatrixtoycompat.core.ConnectivityPort
import space.linuxct.glyphmatrixtoycompat.core.DesignPort
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.InclinePort
import space.linuxct.glyphmatrixtoycompat.core.LightPort
import space.linuxct.glyphmatrixtoycompat.core.LocationPort
import space.linuxct.glyphmatrixtoycompat.core.Ports
import space.linuxct.glyphmatrixtoycompat.core.Prefs
import space.linuxct.glyphmatrixtoycompat.core.RandomPort
import space.linuxct.glyphmatrixtoycompat.core.RenderScheduler
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.core.ScreenManager
import space.linuxct.glyphmatrixtoycompat.core.ShakePort
import space.linuxct.glyphmatrixtoycompat.core.SpectrumPort
import space.linuxct.glyphmatrixtoycompat.core.SpeedPort
import space.linuxct.glyphmatrixtoycompat.core.TimerSignalPort
import space.linuxct.glyphmatrixtoycompat.core.TiltPort
import space.linuxct.glyphmatrixtoycompat.core.design.Design

class FakePrefs : Prefs {
    val map = mutableMapOf<String, Any>()
    private val listeners = mutableListOf<(String) -> Unit>()

    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
    override fun getFloat(key: String, def: Float) = map[key] as? Float ?: def
    override fun getString(key: String, def: String) = map[key] as? String ?: def
    override fun contains(key: String) = map.containsKey(key)

    override fun remove(key: String) {
        if (map.remove(key) != null) listeners.forEach { it(key) }
    }

    private fun put(key: String, v: Any) {
        map[key] = v
        listeners.forEach { it(key) }
    }

    override fun putBoolean(key: String, v: Boolean) = put(key, v)
    override fun putInt(key: String, v: Int) = put(key, v)
    override fun putLong(key: String, v: Long) = put(key, v)
    override fun putFloat(key: String, v: Float) = put(key, v)
    override fun putString(key: String, v: String) = put(key, v)

    override fun addChangeListener(listener: (String) -> Unit) {
        listeners += listener
    }

    override fun removeChangeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}

class FakeClock(
    var now: Long = 1_000_000L,
    var hour: Int = 12,
    var min: Int = 34,
    var sec: Int = 0,
    var utcOffsetMin: Int = 0,
    var doy: Int = 80,
) : ClockPort {
    override fun nowMillis() = now
    override fun hourOfDay() = hour
    override fun minute() = min
    override fun second() = sec
    override fun utcOffsetMinutes() = utcOffsetMin
    override fun dayOfYear() = doy

    fun advance(ms: Long) {
        now += ms
    }
}

/** Deterministic LCG so goldens are stable. */
class FakeRandom(seed: Long = 42L) : RandomPort {
    private var state = seed

    private fun nextBits(): Long {
        state = state * 6364136223846793005L + 1442695040888963407L
        return state ushr 17
    }

    override fun nextInt(bound: Int): Int = ((nextBits() % bound + bound) % bound).toInt()
    override fun nextFloat(): Float = ((nextBits() % 10000 + 10000) % 10000) / 10000f
}

class FakeBattery(
    var level: Int = 80,
    var charging: Boolean = false,
    /** null models "platform will not tell us" — the common case. */
    var watts: Float? = null,
) : BatteryPort {
    override fun levelPercent() = level
    override fun isCharging() = charging
    override fun chargeWatts() = watts
}

class FakeSpeed(var total: Long = 0L) : SpeedPort {
    override fun totalRxBytes() = total
}

class FakeSpectrum(var values: FloatArray? = FloatArray(32)) : SpectrumPort {
    override fun bands(n: Int): FloatArray? {
        val v = values ?: return null
        return FloatArray(n) { i -> v.getOrElse(i) { 0f } }
    }
}

class FakeAzimuth(var value: Float? = 0f) : AzimuthPort {
    override fun azimuthDegrees() = value
}

class FakeShake(var millisSince: Long = Long.MAX_VALUE) : ShakePort {
    override fun millisSinceLastShake() = millisSince
}

class FakeTilt(var x: Float = 0f, var y: Float = 0f) : TiltPort {
    override fun tiltX() = x
    override fun tiltY() = y
}

/**
 * Both nullable on purpose: null models "no gravity sensor / no reading yet".
 * Defaults to dead flat. Signs follow [space.linuxct.glyphmatrixtoycompat.core.InclinePort]:
 * positive = that edge of the device is the low one.
 */
class FakeIncline(var pitch: Float? = 0f, var roll: Float? = 0f) : InclinePort {
    override fun pitchDegrees() = pitch
    override fun rollDegrees() = roll
}

/** [lux] is nullable on purpose: null models "no sensor / no reading yet". */
class FakeLight(var lux: Float? = null) : LightPort {
    var polls = 0

    override fun lux(): Float? {
        polls++
        return lux
    }
}

class FakeConnectivity(var value: ConnectionState = ConnectionState.WIFI) : ConnectivityPort {
    override fun state() = value
}

class FakeLocation(var value: Pair<Double, Double>? = 0.0 to 0.0) : LocationPort {
    override fun latLon() = value
}

/**
 * The design the Custom screen will find. Settable so a test can move between
 * "nothing selected" (the placeholder) and any design it builds, without a
 * filesystem — [DesignStore] is Android and CustomScreen never sees it anyway.
 */
class FakeDesignPort(var design: Design? = null) : DesignPort {
    override fun selected(): Design? = design
}

class FakeTimer : TimerSignalPort {
    var scheduledAt: Long? = null
    var cancelCount = 0
    var chimeCount = 0

    override fun scheduleAlarm(atEpochMillis: Long) {
        scheduledAt = atEpochMillis
    }

    override fun cancelAlarm() {
        cancelCount++
        scheduledAt = null
    }

    override fun chime() {
        chimeCount++
    }
}

/**
 * Manually advanced scheduler for JVM tests. run() executes inline; tick()
 * advances the fake clock by the ticker interval and fires one tick; due
 * one-shots fire whenever time advances.
 */
class FakeScheduler(val clock: FakeClock) : RenderScheduler {
    var tickerInterval: Long? = null
        private set
    private var tickerFn: (() -> Unit)? = null
    private val delayed = mutableListOf<Pair<Long, () -> Unit>>()

    override fun setTicker(intervalMs: Long, tick: () -> Unit) {
        tickerInterval = intervalMs
        tickerFn = tick
        tick() // matches AndroidRenderScheduler: first tick fires immediately
    }

    override fun clearTicker() {
        tickerInterval = null
        tickerFn = null
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable {
        val entry = (clock.now + delayMs) to action
        delayed += entry
        return object : Cancelable {
            override fun cancel() {
                delayed.remove(entry)
            }
        }
    }

    override fun run(action: () -> Unit) = action()

    /** Advances by the ticker interval and fires one tick (plus any due one-shots). */
    fun tick(times: Int = 1) {
        repeat(times) {
            clock.advance(tickerInterval ?: 0L)
            runDue()
            tickerFn?.invoke()
        }
    }

    fun advanceTime(ms: Long) {
        clock.advance(ms)
        runDue()
    }

    private fun runDue() {
        while (true) {
            val due = delayed.filter { it.first <= clock.now }
            if (due.isEmpty()) return
            due.forEach {
                delayed.remove(it)
                it.second()
            }
        }
    }
}

/** Bundles the standard fakes and builds a ScreenContext capturing pushed frames. */
class TestHarness(
    val size: Int = 13,
    val clock: FakeClock = FakeClock(),
) {
    val prefs = FakePrefs()
    val scheduler = FakeScheduler(clock)
    val random = FakeRandom()
    val battery = FakeBattery()
    val speed = FakeSpeed()
    val spectrum = FakeSpectrum()
    val azimuth = FakeAzimuth()
    val shake = FakeShake()
    val tilt = FakeTilt()
    val incline = FakeIncline()
    val light = FakeLight()
    val connectivity = FakeConnectivity()
    val location = FakeLocation()
    val timer = FakeTimer()
    val design = FakeDesignPort()

    val ports = Ports(
        clock, random, battery, speed, spectrum, azimuth, shake, tilt, incline, light,
        connectivity, location, timer, design,
    )

    val frames = mutableListOf<IntArray>()

    val context = ScreenContext(size, prefs, ports, scheduler) { frames += it.copyOf() }

    fun lastFrame(): IntArray = frames.last()

    /** Frames that came out of [manager], i.e. the production output path. */
    val output = mutableListOf<IntArray>()

    /**
     * A real [ScreenManager] over [screens] pushing into [output].
     *
     * Use this — not [context] — for anything about what the DEVICE receives.
     * [context] is the screen's own sink and deliberately captures the art
     * exactly as drawn, which is what makes the ASCII goldens readable; every
     * step between a screen and the hardware (brightness scaling, the identical-
     * frame dedup, the menu blink) lives in the manager's sink instead, and is
     * invisible to a golden.
     */
    fun manager(screens: List<GlyphScreen>) =
        ScreenManager(screens, prefs, ports, scheduler, size) { output += it.copyOf() }
}
