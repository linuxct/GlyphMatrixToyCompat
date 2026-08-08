package space.linuxct.glyphworks.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.provider.Settings
import space.linuxct.glyphworks.core.BatteryPort
import space.linuxct.glyphworks.core.ClockPort
import space.linuxct.glyphworks.core.ConnectionState
import space.linuxct.glyphworks.core.ConnectivityPort
import space.linuxct.glyphworks.core.RandomPort
import space.linuxct.glyphworks.core.SpeedPort
import java.util.Calendar
import java.util.Random
import kotlin.math.abs

class SystemClockPort : ClockPort {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun hourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    override fun minute(): Int = Calendar.getInstance().get(Calendar.MINUTE)
    override fun second(): Int = Calendar.getInstance().get(Calendar.SECOND)
    override fun utcOffsetMinutes(): Int = Calendar.getInstance().let {
        (it.get(Calendar.ZONE_OFFSET) + it.get(Calendar.DST_OFFSET)) / 60_000
    }
    override fun dayOfYear(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
}

class JavaRandomPort : RandomPort {
    private val random = Random()
    override fun nextInt(bound: Int): Int = random.nextInt(bound)
    override fun nextFloat(): Float = random.nextFloat()
}

/**
 * Battery state from the sticky ACTION_BATTERY_CHANGED broadcast, queried on
 * every call (cheap: no receiver is registered for a sticky query).
 */
class BatteryReader(private val app: Context) : BatteryPort {

    private fun sticky(): Intent? =
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun levelPercent(): Int {
        val intent = sticky() ?: return 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 100
        return level * 100 / scale
    }

    override fun isCharging(): Boolean {
        val status = sticky()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING
    }

    /**
     * watts = |CURRENT_NOW| / 1e6 (uA -> A) * EXTRA_VOLTAGE / 1e3 (mV -> V).
     *
     * Three layers of paranoia, because both inputs are notoriously
     * OEM-specific: the current's sign is discarded (some vendors report
     * charging as negative), a reading that lands above [MAX_WATTS] is retried
     * as milliamps (a documented vendor deviation from the uA contract), and
     * anything still outside [MIN_WATTS]..[MAX_WATTS] — or a voltage outside a
     * sane Li-ion window — yields null so the gauge is shown instead of
     * nonsense.
     */
    override fun chargeWatts(): Float? {
        val intent = sticky() ?: return null
        if (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) != BatteryManager.BATTERY_STATUS_CHARGING) {
            return null
        }
        val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (millivolts < MIN_MILLIVOLTS || millivolts > MAX_MILLIVOLTS) return null
        val bm = app.getSystemService(BatteryManager::class.java) ?: return null
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current == 0 || current == Int.MIN_VALUE) return null
        val amps = abs(current.toLong()).toFloat() / 1_000_000f
        var watts = amps * (millivolts / 1000f)
        if (watts > MAX_WATTS) watts /= 1000f // vendor reported mA, not uA
        if (watts < MIN_WATTS || watts > MAX_WATTS) return null
        return watts
    }

    private companion object {
        const val MIN_MILLIVOLTS = 2000
        const val MAX_MILLIVOLTS = 30_000
        const val MIN_WATTS = 0.5f
        const val MAX_WATTS = 500f
    }
}

class TrafficSpeedPort : SpeedPort {
    override fun totalRxBytes(): Long = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
}

class AndroidConnectivityPort(private val app: Context) : ConnectivityPort {
    override fun state(): ConnectionState {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps: NetworkCapabilities? = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ConnectionState.WIFI
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return ConnectionState.CELLULAR
        }
        val airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        return if (airplane) ConnectionState.AIRPLANE else ConnectionState.NONE
    }
}
