package space.linuxct.glyphworks.ui.design

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ai.GlyphAiViewModel
import space.linuxct.glyphworks.ai.SignInFailure
import space.linuxct.glyphworks.ui.MotionDialog
import space.linuxct.glyphworks.ui.dialogCardWidth

/**
 * The sparkles button's second door: sign in to OpenAI.
 *
 * ## Where this sits
 *
 * `aiGate` shows the disclosure first, this second, and the chat third — so by
 * the time this is composed the user has read what leaves the device and agreed
 * to it, and the moment `signedIn` flips true the editor replaces this with
 * `GlyphAiChatSheet` without another tap. The signed-in branch below is therefore
 * a single frame in normal use; it stays because a state this class can be in is
 * a state it has to be able to draw. **Signing out lives in the chat's own
 * header**, which is the only place it is reachable once there is a token.
 *
 * ## Structure
 *
 * [MotionDialog] and a 28 dp [Surface] at [dialogCardWidth], exactly as
 * `KeyTutorialDialog` and the editor's own `DesignSettingsCard` do — the app has
 * one dialog idiom and this is it, springs and width included. No new colours: the
 * only non-text element is a progress indicator, which takes the theme's.
 *
 * ## Dismissal is cancellation
 *
 * Every way out of this dialog — the close button, an outside tap, a back gesture
 * — goes through [MotionDialog]'s `dismiss` and then calls
 * [GlyphAiViewModel.cancelSignIn]. Leaving a sign-in running behind a dialog
 * nobody can see would hold port 1455 for ten minutes, so that a second attempt
 * failed to bind it; see that method for why cancelling has to close the socket
 * rather than merely cancel the job.
 */
@Composable
internal fun GlyphAiSignInDialog(onDismiss: () -> Unit) {
    val viewModel = glyphAiViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    MotionDialog(
        onDismiss = {
            viewModel.cancelSignIn()
            onDismiss()
        },
    ) { dismiss ->
        Surface(
            modifier = Modifier.width(dialogCardWidth()),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    stringResource(R.string.ai_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        if (state.signedIn) R.string.ai_body_signed_in else R.string.ai_body_signed_out,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Only while the browser is out in front. A 16 dp indicator beside
                // the sentence rather than a bar across the card: this is a wait on
                // somebody else's screen, not progress through a task of ours.
                if (state.busy) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.ai_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // The failure, in the app's own words, with the platform's
                // message under it — "Token request failed 400: invalid_grant"
                // is the difference between a bug report somebody can act on and
                // "it didn't work".
                state.failure?.let { failure ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(failure.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        // Mid-flight: the only two useful actions are "give up"
                        // and "leave it running while I go back to the browser",
                        // and the second is the close button.
                        state.busy -> TextButton(onClick = { viewModel.cancelSignIn() }) {
                            Text(stringResource(R.string.ai_cancel))
                        }
                        state.signedIn -> TextButton(onClick = { viewModel.signOut() }) {
                            Text(stringResource(R.string.ai_sign_out))
                        }
                        else -> TextButton(
                            onClick = {
                                viewModel.signIn { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    // A second attempt after a failure is a retry,
                                    // and saying so is how the user knows the tap
                                    // registered at all.
                                    if (state.failure != null) R.string.ai_retry else R.string.ai_sign_in,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
        }
    }
}

/** One sentence per [SignInFailure]; the recoveries differ, so the copy does. */
private fun SignInFailure.messageRes(): Int = when (this) {
    SignInFailure.TIMED_OUT -> R.string.ai_error_timeout
    SignInFailure.PORT_BUSY -> R.string.ai_error_port
    SignInFailure.NO_BROWSER -> R.string.ai_error_no_browser
    SignInFailure.FAILED -> R.string.ai_error_failed
}

/**
 * The activity-scoped [GlyphAiViewModel].
 *
 * Resolved through [ViewModelProvider] against the host Activity rather than with
 * the `viewModel()` composable, because that helper lives in
 * `lifecycle-viewmodel-compose`, which this app does not depend on — and this
 * feature's standing constraint is **zero new dependencies**. `ViewModelProvider`
 * and the Activity's own default factory come with `activity-compose` already,
 * and give exactly the same store: one instance per Activity, surviving every
 * configuration change.
 */
@Composable
internal fun glyphAiViewModel(): GlyphAiViewModel {
    val context = LocalContext.current
    val owner = remember(context) {
        requireNotNull(context.findActivity()) { "GlyphAiSignInDialog must be hosted by an Activity" }
    }
    return remember(owner) { ViewModelProvider(owner)[GlyphAiViewModel::class.java] }
}

/** Unwraps whatever `LocalContext` happens to be down to the hosting Activity. */
private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
