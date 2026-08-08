package space.linuxct.glyphworks.util

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.annotation.Keep
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
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

    /**
     * The one registration with the platform, fanned out to [listeners].
     *
     * ## This field is load-bearing and R8 removed it once
     *
     * `SharedPreferences` keeps its change listeners in a **`WeakHashMap`**, so
     * whoever registers one must hold the only strong reference to it. This field
     * was that reference — and in the release build it was **not there**. R8 saw a
     * private field that is written and never read, deleted the field, and kept
     * the initializer's side effect, so the listener was registered and then
     * reachable only weakly. The next GC collected it and **every preference
     * change notification in the process stopped, permanently**, with no path to
     * re-register.
     *
     * That is why the Toys tab's stale highlight was rare, why it never recovered
     * once it happened, and why it was invisible in debug builds and in review:
     * the source was always right, the shipped code was not. `@Suppress("unused")`
     * is a Kotlin lint hint and carries no weight with R8.
     *
     * Two things keep it now, deliberately belt and braces because the failure is
     * silent and the cost of being wrong is a class of bug that cannot be
     * diagnosed from a stack trace:
     *
     * 1. [Keep], which R8 honours for members;
     * 2. an explicit `-keep` in `proguard-rules.pro`, which does not depend on the
     *    annotation processor being wired up in some future build change.
     *
     * `AndroidPrefsKeepRuleTest` is the third: it fails if the rule is ever
     * deleted from `proguard-rules.pro`.
     *
     * **How to check it for real**, because none of the three prove the outcome —
     * `mapping.txt` does not list members it kept under their own name, so its
     * silence means nothing either way. Look in the shipped DEX:
     *
     * ```
     * unzip -p app-release.apk classes.dex | strings | grep -c spListener
     * ```
     *
     * 1 is correct. **0 is this bug**, and 0 is what the build shipped before this
     * comment existed.
     */
    @Keep
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
    override fun contains(key: String): Boolean = sp.contains(key)

    override fun remove(key: String) = sp.edit().remove(key).apply()

    override fun putBoolean(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    override fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    override fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    override fun putFloat(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
    override fun putString(key: String, v: String) {
        // **A write of the value already stored notifies nobody**, and that is
        // platform behaviour, not a bug here: `SharedPreferencesImpl.commitToMemory`
        // only records a key in `keysModified` when the value actually differs, and
        // only modified keys reach the listeners.
        //
        // It is logged because it is the exact shape of the Toys tab's stale
        // highlight — a holder that has drifted makes the user re-select what the
        // store already holds, so their tap is silently a no-op and the UI stays
        // wrong. When that happens this line is the proof, and its absence rules
        // the whole theory out. Only the no-op case is logged: the ordinary write
        // is already visible as the `PrefWatch` change it produces.
        if (sp.contains(key) && sp.getString(key, null) == v) {
            DebugLog.d("Prefs", "putString $key = '$v' is unchanged — no listener will fire")
        }
        sp.edit().putString(key, v).apply()
    }

    override fun addChangeListener(listener: (String) -> Unit) {
        listeners += listener
    }

    override fun removeChangeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}
