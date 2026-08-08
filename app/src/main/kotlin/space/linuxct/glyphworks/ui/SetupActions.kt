package space.linuxct.glyphworks.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.key.EssentialKeyService

/** Setup checks/actions shared by MainActivity's checklist and onboarding. */

/**
 * True only on a Nothing OS device with a Glyph Matrix — i.e. Phone (3) or
 * Phone (4a) Pro. The matrix size (13/25) pins the exact models; the custom
 * system feature (declared by /system_ext/etc/permissions/com.nothing.feature.xml
 * on Nothing OS) confirms the platform. MainActivity dead-ends when this is
 * false, because uses-feature cannot block sideloaded installs.
 */
internal fun isNothingGlyphDevice(context: Context): Boolean =
    Core.glyphLink.isSupported &&
        context.packageManager.hasSystemFeature("com.nothing.feature")

/**
 * True on the one device this app has actually been *tested* on: the Phone
 * (4a) Pro. Everything else supported — today that means Phone (3) — runs code
 * paths nobody has watched on real hardware, and gets told so once.
 *
 * ## Why the matrix size and not [android.os.Build.MODEL]
 *
 * A model string would have to be written down from a device, and there is no
 * (4a) Pro here to read one off; inventing the literal and hoping is how you ship
 * a check that silently never fires — or worse, fires on everyone. The matrix
 * length is read from the Glyph SDK on the actual hardware, the app already
 * trusts it to choose a whole rendering path, and it separates exactly the two
 * devices in question: 13 is the (4a) Pro, 25 is the Phone (3).
 *
 * The trade is that a future 13x13 Nothing phone would be treated as tested. That
 * is the right way round: the warning is about untested *rendering*, and such a
 * device would at least be running the paths that have been exercised.
 */
internal fun isTestedGlyphDevice(): Boolean = Core.glyphLink.matrixLength == TESTED_MATRIX_LENGTH

/** The Phone (4a) Pro's 13x13 panel. See [isTestedGlyphDevice]. */
private const val TESTED_MATRIX_LENGTH = 13

internal fun isEssentialKeyServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, EssentialKeyService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any {
        it.equals(component.flattenToString(), ignoreCase = true) ||
            it.equals(component.flattenToShortString(), ignoreCase = true)
    }
}

/**
 * Opens the system UI where GlyphWorks is chosen as the always-on Glyph Toy.
 * Prefers the direct AOD-toy picker (one tap from selecting us); falls back
 * to the general toys manager on firmware where the picker activity is not
 * launchable. Returns false if neither exists.
 */
internal fun openGlyphToySettings(context: Context): Boolean {
    val candidates = listOf(
        "com.nothing.thirdparty.matrix.toys.manager.AodToySelectActivity",
        "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity",
    )
    return candidates.any { cls ->
        try {
            context.startActivity(
                Intent().setComponent(ComponentName("com.nothing.thirdparty", cls)),
            )
            DebugLog.i("Ui", "opened $cls")
            true
        } catch (e: Exception) {
            DebugLog.d("Ui", "$cls not launchable: ${e.message}")
            false
        }
    }
}
