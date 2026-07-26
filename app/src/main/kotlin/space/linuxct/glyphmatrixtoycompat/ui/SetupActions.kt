package space.linuxct.glyphmatrixtoycompat.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.key.EssentialKeyService

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
 * Opens the system UI where GMTC is chosen as the always-on Glyph Toy.
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
