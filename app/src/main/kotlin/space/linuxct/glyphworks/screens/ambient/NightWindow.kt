package space.linuxct.glyphworks.screens.ambient

/** Fixed night window: 23:00-05:59 counts as night. */
object NightWindow {
    fun isNight(hourOfDay: Int): Boolean = hourOfDay >= 23 || hourOfDay < 6
}
