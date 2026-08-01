package space.linuxct.glyphmatrixtoycompat

import android.content.Context
import com.nothing.ketchum.Common
import space.linuxct.glyphmatrixtoycompat.ai.DesignChatCleanup
import space.linuxct.glyphmatrixtoycompat.audio.AudioVisualizerEngine
import space.linuxct.glyphmatrixtoycompat.core.AndroidRenderScheduler
import space.linuxct.glyphmatrixtoycompat.core.AutoBrightness
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphLink
import space.linuxct.glyphmatrixtoycompat.core.Ports
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.Prefs
import space.linuxct.glyphmatrixtoycompat.core.PrefsMigration
import space.linuxct.glyphmatrixtoycompat.core.ScreenManager
import space.linuxct.glyphmatrixtoycompat.core.SessionArbiter
import space.linuxct.glyphmatrixtoycompat.designs.AndroidDesignPort
import space.linuxct.glyphmatrixtoycompat.designs.DesignStore
import space.linuxct.glyphmatrixtoycompat.key.KeyActionRouter
import space.linuxct.glyphmatrixtoycompat.screens.CustomScreen
import space.linuxct.glyphmatrixtoycompat.screens.ScreenRegistry
import space.linuxct.glyphmatrixtoycompat.sensors.CompassSensor
import space.linuxct.glyphmatrixtoycompat.sensors.InclineSensor
import space.linuxct.glyphmatrixtoycompat.sensors.LightSensor
import space.linuxct.glyphmatrixtoycompat.sensors.ShakeDetector
import space.linuxct.glyphmatrixtoycompat.sensors.TiltSensor
import space.linuxct.glyphmatrixtoycompat.toy.AndroidTimerSignal
import space.linuxct.glyphmatrixtoycompat.util.AndroidConnectivityPort
import space.linuxct.glyphmatrixtoycompat.util.AndroidLocationPort
import space.linuxct.glyphmatrixtoycompat.util.AndroidPrefs
import space.linuxct.glyphmatrixtoycompat.util.BatteryReader
import space.linuxct.glyphmatrixtoycompat.util.JavaRandomPort
import space.linuxct.glyphmatrixtoycompat.util.ScreenStateWatcher
import space.linuxct.glyphmatrixtoycompat.util.SystemClockPort
import space.linuxct.glyphmatrixtoycompat.util.TrafficSpeedPort

/**
 * Process-wide object graph. Both entry points (accessibility service, AOD
 * toy service) and the UI run in this single process and share these
 * instances; init() is idempotent and safe from any component (including in
 * Direct Boot, since Prefs use device-protected storage).
 */
object Core {

    @Volatile
    private var built = false

    lateinit var prefs: Prefs
        private set

    /**
     * Single owner of the design directory. Shared by [ports]' design port and
     * the settings UI on purpose: the store caches its listing, and two
     * instances would be two caches that disagree the moment one of them writes.
     */
    lateinit var designStore: DesignStore
        private set

    lateinit var glyphLink: GlyphLink
        private set
    lateinit var scheduler: AndroidRenderScheduler
        private set
    lateinit var ports: Ports
        private set
    lateinit var screenManager: ScreenManager
        private set
    lateinit var arbiter: SessionArbiter
        private set
    lateinit var shake: ShakeDetector
        private set
    lateinit var autoBrightness: AutoBrightness
        private set
    lateinit var screenState: ScreenStateWatcher
        private set
    lateinit var audio: AudioVisualizerEngine
        private set
    lateinit var router: KeyActionRouter
        private set

    @Synchronized
    fun init(context: Context) {
        if (built) return
        val app = context.applicationContext

        // Install the logcat sink FIRST so every later init step is visible.
        DebugLog.sink = { level, component, message ->
            val line = "[$component] $message"
            when (level) {
                DebugLog.Level.DEBUG -> android.util.Log.d(DebugLog.TAG, line)
                DebugLog.Level.INFO -> android.util.Log.i(DebugLog.TAG, line)
                DebugLog.Level.WARN -> android.util.Log.w(DebugLog.TAG, line)
            }
        }
        DebugLog.i("Core", "init on ${android.os.Build.MODEL} (matrix=${Common.getDeviceMatrixLength()})")

        prefs = AndroidPrefs(app)
        // Must precede every prefs reader below (ScreenManager and the arbiter
        // read the screen order and current screen as they are built).
        if (PrefsMigration.run(prefs)) DebugLog.i("Core", "prefs migrated to v${PrefKeys.PREFS_VERSION_CURRENT}")
        // Device-protected, like prefs — CustomScreen reads a design during
        // onActivate, and arbiter.revive() at the end of this method can trigger
        // that before the first unlock after a reboot.
        designStore = DesignStore(app)
        // The one place `designs/` and `ai/` are joined, and it is joined from
        // here rather than by an import inside DesignStore: deleting a design has
        // to take its conversation with it, but the storage layer the always-on
        // display depends on must not depend on the assistant. Registration only;
        // nothing credential-protected is touched until the first delete, which
        // is what makes this safe to run during Direct Boot.
        DesignChatCleanup.install(app, designStore)
        glyphLink = GlyphLink(app)
        scheduler = AndroidRenderScheduler()
        shake = ShakeDetector(app)
        audio = AudioVisualizerEngine(app, prefs)

        ports = Ports(
            clock = SystemClockPort(),
            random = JavaRandomPort(),
            battery = BatteryReader(app),
            speed = TrafficSpeedPort(),
            spectrum = audio,
            azimuth = CompassSensor(app),
            shake = shake,
            tilt = TiltSensor(app),
            incline = InclineSensor(app),
            light = LightSensor(app),
            connectivity = AndroidConnectivityPort(app),
            location = AndroidLocationPort(app),
            timer = AndroidTimerSignal(app),
            design = AndroidDesignPort(prefs, designStore),
        )

        screenManager = ScreenManager(
            allScreens = ScreenRegistry.create(),
            prefs = prefs,
            ports = ports,
            scheduler = scheduler,
            size = glyphLink.size,
        ) { frame -> glyphLink.pushFrame(frame) }

        autoBrightness = AutoBrightness(prefs, ports.light, scheduler) {
            // Already on the scheduler thread (the controller marshals), so the
            // re-push can call straight into the manager.
            screenManager.reapplyBrightness()
        }
        screenState = ScreenStateWatcher(app) { on -> autoBrightness.setScreenOn(on) }

        arbiter = SessionArbiter(glyphLink, scheduler, screenManager, prefs) { running ->
            if (running) {
                shake.start()
                // start() seeds the screen state, so it must precede the poller.
                screenState.start()
                autoBrightness.start()
            } else {
                autoBrightness.stop()
                screenState.stop()
                shake.stop()
            }
        }

        router = KeyActionRouter(arbiter, screenManager, scheduler, prefs)

        shake.onShake = {
            scheduler.run { screenManager.dispatchGlyphEvent(Events.SHAKE) }
        }

        prefs.addChangeListener { key ->
            when (key) {
                PrefKeys.MASTER_TOGGLE -> arbiter.onMasterToggleChanged()
                // Turning auto-brightness off (including implicitly, by dragging
                // the brightness slider) must stop the polling right away.
                PrefKeys.AUTO_BRIGHTNESS -> autoBrightness.onEnabledChanged()
                // Choosing a different design has to reach a `custom` screen that
                // is already on the matrix — it read its design in onActivate and
                // will not read it again on its own. Handled at the pref rather
                // than at the toy's settings dialog because that dialog is only
                // one of the writers; see ScreenManager.onSelectedDesignChanged.
                //
                // Marshalled like the shake handler above: this listener fires on
                // whichever thread did the write, and every ScreenManager method
                // is scheduler-thread only.
                PrefKeys.CUSTOM_DESIGN_ID -> scheduler.run {
                    screenManager.onSelectedDesignChanged(CustomScreen.ID)
                }
            }
        }

        built = true
        // Bring the DIRECT session up right away (master toggle defaults on):
        // from process start — including before first unlock after boot — the
        // current screen renders and the system decides whether it is shown.
        arbiter.revive()
    }
}
