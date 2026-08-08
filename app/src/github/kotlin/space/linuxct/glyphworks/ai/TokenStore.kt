package space.linuxct.glyphworks.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread

/**
 * Where the OpenAI sign-in lives: the refresh token, the current access token,
 * and the instant that access token stops being useful.
 *
 * ## Credential-protected, and that is the whole point of this file
 *
 * **Everything else this app persists is DEVICE-protected on purpose.**
 * `util/AndroidPrefs` and `designs/DesignStore` both call
 * `createDeviceProtectedStorageContext()` so that `AodToyService` and
 * `EssentialKeyService` — both `directBootAware` — can read settings and draw a
 * design *before the first unlock after a reboot*. That is the right call for a
 * screen order and for somebody's pixel art.
 *
 * It is the wrong call for an OAuth token, and this class deliberately does the
 * opposite: it takes the ordinary [Context] and never converts it. Device-protected
 * storage is readable while the device is still locked, which is precisely the
 * state in which nobody has proved they are the owner. A refresh token sitting
 * there is a credential that a stolen, powered-on-but-never-unlocked phone hands
 * over. In credential-protected storage it is encrypted with the user's own
 * credential and is simply not there until they unlock.
 *
 * Nothing in this app reads tokens before unlock — the sign-in and the assistant
 * both live in the editor, which is an Activity — so there is no cost to pay.
 *
 * The guard in `init` is not decoration. The single realistic way this file goes
 * wrong in future is somebody passing it a context that has already been
 * converted (`Core` holds one for [designs.DesignStore]), which would move the
 * token into DE storage silently and with no visible symptom. It fails loudly
 * instead.
 *
 * ## Backup
 *
 * `res/xml/backup_rules.xml` is an allowlist naming only `device_file/designs`,
 * so credential-protected data — this file included — is excluded from Auto
 * Backup and device transfer *by construction*, and a token can never be restored
 * onto another device. That is recorded in the rules file itself so the two
 * cannot drift apart.
 *
 * ## Naming
 *
 * [KEY_REFRESH_TOKEN] matches pulseloop-android's key exactly, so the two apps'
 * auth code stays diffable against each other.
 */
class TokenStore(context: Context) {

    private val sp: SharedPreferences

    init {
        val app = context.applicationContext
        check(!app.isDeviceProtectedStorage) {
            "TokenStore must be built from a credential-protected Context; " +
                "an OAuth token must not be readable before the first unlock"
        }
        sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** The long-lived credential. Present iff the user is signed in. */
    val refreshToken: String? get() = sp.getString(KEY_REFRESH_TOKEN, null)

    /** The short-lived credential; may be stale, see [accessTokenExpiresAtMs]. */
    val accessToken: String? get() = sp.getString(KEY_ACCESS_TOKEN, null)

    /** Wall-clock ms at which [accessToken] expires, or 0 if nothing is stored. */
    val accessTokenExpiresAtMs: Long get() = sp.getLong(KEY_EXPIRES_AT, 0L)

    /**
     * Whether there is a sign-in to work from — keyed on the REFRESH token, not
     * the access token: an expired access token is a refresh away, an absent
     * refresh token is a sign-in away, and only the second is something to ask
     * the user about.
     */
    val isSignedIn: Boolean get() = !refreshToken.isNullOrBlank()

    /**
     * Whether [accessToken] can still be used, with [EXPIRY_MARGIN_MS] of slack
     * so a call is not started with a token that expires while it is in flight.
     */
    fun hasFreshAccessToken(nowMs: Long = System.currentTimeMillis()): Boolean =
        !accessToken.isNullOrBlank() && accessTokenExpiresAtMs - EXPIRY_MARGIN_MS > nowMs

    /**
     * Stores a freshly issued set.
     *
     * `commit` rather than `apply`, against lint's general advice, and both
     * callers are `@WorkerThread` so the blocking write costs nothing: a token
     * that reached memory but not the platter would present as signed in until
     * the next launch and then silently not be, and the caller — the sign-in
     * dialog — reports success on the strength of this returning. One write per
     * sign-in is not a hot path.
     */
    @Suppress("ApplySharedPref")
    @WorkerThread
    fun save(tokens: OAuthTokens, nowMs: Long = System.currentTimeMillis()) {
        sp.edit()
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putLong(KEY_EXPIRES_AT, nowMs + tokens.expiresIn * 1000L)
            .commit()
    }

    /**
     * Signs out: removes every stored credential. `commit` for the stronger of
     * the two reasons — a sign-out that has not reached disk is a credential the
     * user believes they have revoked. See [save].
     */
    @Suppress("ApplySharedPref")
    @WorkerThread
    fun clear() {
        sp.edit().clear().commit()
    }

    companion object {
        /** Its own file, so a sign-out cannot take an app setting with it. */
        private const val PREFS_NAME = "openai_auth"

        /** Matching pulseloop-android's key name; see this class's KDoc. */
        const val KEY_REFRESH_TOKEN = "OPENAI_REFRESH_TOKEN"
        private const val KEY_ACCESS_TOKEN = "OPENAI_ACCESS_TOKEN"
        private const val KEY_EXPIRES_AT = "OPENAI_ACCESS_TOKEN_EXPIRES_AT"

        /** Treat an access token as spent this long before it really expires. */
        private const val EXPIRY_MARGIN_MS = 60_000L
    }
}
