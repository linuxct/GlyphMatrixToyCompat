package space.linuxct.glyphworks.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Which of the assistant's three doors the sparkles button opens.
 *
 * An enum and a pure function rather than a chain of `if`s in a composable,
 * because the ORDER is the whole rule and it is the one thing here worth
 * proving: the disclosure comes before the sign-in, and the sign-in before the
 * chat. Reversing them would send the user to OpenAI's login page — an act that
 * tells OpenAI something about them — before this app had told them anything at
 * all, which is precisely what a prominent disclosure exists to prevent.
 */
enum class AiGate {
    /** Nothing has been disclosed yet. Show the one-off disclosure. */
    CONSENT,

    /** Disclosed and accepted, but there is no account to talk to. */
    SIGN_IN,

    /** Both done: open the conversation. */
    CHAT,
}

/**
 * The gate, given the two facts that decide it.
 *
 * Deliberately total over all four combinations. "Signed in but never consented"
 * is reachable — this build ships sign-in that predates the disclosure, so an
 * existing user has a token and no acceptance — and it must still show the
 * disclosure first.
 */
fun aiGate(consented: Boolean, signedIn: Boolean): AiGate = when {
    !consented -> AiGate.CONSENT
    !signedIn -> AiGate.SIGN_IN
    else -> AiGate.CHAT
}

/**
 * Whether the user has been told what leaves the device, and said yes.
 *
 * An interface with one implementation, so the gate above and everything that
 * depends on it can be exercised without a `Context`. The Android side is
 * [AiConsentStore]; a test hands in whatever it likes.
 */
interface AiConsentStorage {
    val accepted: Boolean

    /** Records the answer. Only ever called with `true` — see [AiConsentStore]. */
    fun accept()
}

/**
 * The acceptance, persisted.
 *
 * ## Only "yes" is stored
 *
 * There is no `decline()`. Declining is the absence of an acceptance, and that is
 * not a nicety: a stored "no" would be a thing to migrate, a thing to offer a way
 * back from, and a second state that "never asked" could drift away from. The
 * user who declines simply closes the dialog, nothing was sent, and the next tap
 * on the sparkles button asks again — which is the correct behaviour for somebody
 * who tapped it a second time.
 *
 * ## Where it lives, and why lazily
 *
 * Credential-protected storage, its own file, taking the ordinary [Context] and
 * never `createDeviceProtectedStorageContext()` — the same rule [TokenStore] and
 * [ChatStore] follow, so that everything this feature persists sits behind the
 * lock screen together rather than being split across two encryption domains for
 * a Boolean.
 *
 * The [SharedPreferences] handle is `by lazy` for the reason recorded in
 * [ChatStore]: credential-protected `filesDir` **cannot be created while the
 * device is locked**, `Core.init` runs in exactly that state when `AodToyService`
 * starts during Direct Boot, and anything that touched this eagerly from there
 * would take the always-on display down. Nothing reads consent before the editor
 * is on screen, which is hours after the first unlock.
 *
 * It is deliberately NOT in the same file as the tokens: signing out clears that
 * file wholesale, and having to re-read a disclosure because you signed out of an
 * account is a dialog that has stopped meaning anything.
 */
class AiConsentStore(context: Context) : AiConsentStorage {

    private val app: Context

    init {
        val application = context.applicationContext
        check(!application.isDeviceProtectedStorage) {
            "AiConsentStore must be built from a credential-protected Context, " +
                "like the token and chat stores it sits beside"
        }
        app = application
    }

    private val sp: SharedPreferences by lazy {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override val accepted: Boolean get() = sp.getBoolean(KEY_ACCEPTED, false)

    /**
     * `commit`, as [TokenStore] does and for a weaker but real version of the
     * same reason: this is written once in the lifetime of an install, on the tap
     * that immediately precedes the first request leaving the device, and an
     * acceptance that had not reached disk would show the disclosure again after
     * the data had already gone.
     */
    @Suppress("ApplySharedPref")
    override fun accept() {
        sp.edit().putBoolean(KEY_ACCEPTED, true).commit()
    }

    private companion object {
        const val PREFS_NAME = "ai_consent"
        const val KEY_ACCEPTED = "AI_DISCLOSURE_ACCEPTED"
    }
}
