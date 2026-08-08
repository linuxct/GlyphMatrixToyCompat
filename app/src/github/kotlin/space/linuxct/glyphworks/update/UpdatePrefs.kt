package space.linuxct.glyphworks.update

/**
 * The update checker's own preference key.
 *
 * One constant in its own file for the same reason [space.linuxct.glyphworks.core.ai.AiPrefKeys]
 * exists: the Play flavour ships without `update/`, and a shared `PrefKeys` that
 * named this would be a shared file that could not compile without it.
 *
 * The key string is unchanged from when it lived in `PrefKeys`, so an existing
 * store reads back identically.
 */
object UpdatePrefKeys {
    /** Release version the daily update check has already notified about. */
    const val NOTIFIED_VERSION = "updateNotifiedVersion"
    const val NOTIFIED_VERSION_DEF = ""
}
