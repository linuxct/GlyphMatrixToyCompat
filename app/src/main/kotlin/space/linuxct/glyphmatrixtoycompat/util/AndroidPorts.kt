package space.linuxct.glyphmatrixtoycompat.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.provider.Settings
import space.linuxct.glyphmatrixtoycompat.core.BatteryPort
import space.linuxct.glyphmatrixtoycompat.core.ClockPort
import space.linuxct.glyphmatrixtoycompat.core.ConnectionState
import space.linuxct.glyphmatrixtoycompat.core.ConnectivityPort
import space.linuxct.glyphmatrixtoycompat.core.RandomPort
import space.linuxct.glyphmatrixtoycompat.core.SpeedPort
import java.util.Calendar
import java.util.Random

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
