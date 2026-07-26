package space.linuxct.glyphmatrixtoycompat.util

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SharedPreferences-backed settings in DEVICE-PROTECTED storage so the
 * direct-boot-aware services can read them before the first unlock. Nothing
 * stored here is credential-sensitive (DE-only from v1, no migration).
 */
class AndroidPrefs(context: Context) : Prefs {

    private val sp: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Suppress("unused") // strong reference required: SharedPreferences holds listeners weakly
    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) listeners.forEach { it(key) }
    }.also { sp.registerOnSharedPreferenceChangeListener(it) }

    init {
        if (!sp.contains(PrefKeys.USE_12H)) {
            sp.edit().putBoolean(PrefKeys.USE_12H, !DateFormat.is24HourFormat(context)).apply()
        }
        if (!sp.contains(PrefKeys.PREFS_VERSION)) {
            sp.edit().putInt(PrefKeys.PREFS_VERSION, PrefKeys.PREFS_VERSION_DEF).apply()
        }
    }

    override fun getBoolean(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    override fun getInt(key: String, def: Int): Int = sp.getInt(key, def)
    override fun getLong(key: String, def: Long): Long = sp.getLong(key, def)
    override fun getFloat(key: String, def: Float): Float = sp.getFloat(key, def)
    override fun getString(key: String, def: String): String = sp.getString(key, def) ?: def

    override fun putBoolean(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    override fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    override fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    override fun putFloat(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    override fun putString(key: String, v: String) = sp.edit().putString(key, v).apply()

    override fun addChangeListener(listener: (String) -> Unit) {
        listeners += listener
    }

    override fun removeChangeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}
