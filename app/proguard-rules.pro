# GlyphMatrixToyCompat R8/ProGuard rules.
#
# DIAGNOSTIC LOGS MUST SURVIVE RELEASE BUILDS. The whole key/session pipeline
# logs through core.DebugLog under the single tag "GlyphToyCompat" so field
# issues (Essential Key capture, session arbitration, Glyph service binding)
# can be debugged with:  adb logcat -s GlyphToyCompat
#
# Never add `-assumenosideeffects` for android.util.Log or DebugLog here —
# that is the rule that strips log calls.

-keep class space.linuxct.glyphmatrixtoycompat.core.DebugLog { *; }

# The Nothing Glyph SDK is consumed as a local AAR; keep its surface intact
# (Messenger/AIDL contracts are reflective from the system side).
-keep class com.nothing.ketchum.** { *; }
-keep class com.nothing.thirdparty.** { *; }

# Components whose names are persisted by the system (accessibility enablement,
# Always-on Glyph Toy selection, placed Quick Settings tiles) must never be
# renamed.
-keep class space.linuxct.glyphmatrixtoycompat.key.EssentialKeyService { *; }
-keep class space.linuxct.glyphmatrixtoycompat.key.KeyCaptureTileService { *; }
-keep class space.linuxct.glyphmatrixtoycompat.toy.AodToyService { *; }
-keep class space.linuxct.glyphmatrixtoycompat.toy.TeaTimeAlarmReceiver { *; }
