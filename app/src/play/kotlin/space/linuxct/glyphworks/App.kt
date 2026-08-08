package space.linuxct.glyphworks

/**
 * The Google Play build's `Application`.
 *
 * [BaseApp] and nothing else. This flavour has no update checker and no design
 * assistant, so it has no WorkManager dependency to configure and no channels
 * beyond the Timer's — a channel with no notification behind it is a row in the
 * user's settings that does nothing.
 */
class App : BaseApp()
