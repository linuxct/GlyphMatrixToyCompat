package space.linuxct.glyphmatrixtoycompat.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.ui.MotionDialog
import space.linuxct.glyphmatrixtoycompat.ui.dialogCardWidth

/**
 * The one-off disclosure, shown before **anything** leaves the device.
 *
 * ## What it has to say, and why each line is there
 *
 * `ui/DisclosureActivity` is the idiom this follows: a prominent, explicit,
 * accept-or-decline screen that names the thing that is about to happen, shown
 * *before* it happens rather than in a settings page nobody opens. The copy here
 * answers the three questions that disclosure has to answer — **what** goes (the
 * design's own JSON, what you type, any photo you attach), **where** it goes
 * (OpenAI, under the account you are about to sign in to), and **what stays**
 * (the conversation, on this phone, beside the design, deleted with it).
 *
 * The order in `aiGate` is what makes it a disclosure at all: it comes before the
 * sign-in, so nothing — not even the fact that this app exists — has reached
 * OpenAI when the user reads it.
 *
 * ## A dialog, where the disclosure for accessibility is an Activity
 *
 * `DisclosureActivity` is its own screen because the thing it precedes is a trip
 * to the system settings, which leaves the app anyway. This precedes an action
 * *inside* the editor, taken with a drawing on screen, and pushing an activity
 * would tear that screen down and rebuild it — flushing a save, dropping the live
 * matrix preview and returning to a canvas that had to reload — to ask one
 * question. So the pattern is followed and the container is not: same structure,
 * same explicit accept and decline, same "nothing happens until you say yes".
 *
 * ## Declining
 *
 * Declining is dismissal. Nothing is written, nothing is sent, the editor is
 * exactly as it was, and tapping sparkles again asks again — which is the right
 * answer for somebody who tapped it a second time. See `AiConsentStore` for why
 * there is no stored "no".
 */
@Composable
internal fun GlyphAiConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    MotionDialog(onDismiss = onDismiss) { dismiss ->
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
                    stringResource(R.string.ai_consent_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ai_consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.ai_consent_storage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = dismiss) {
                        Text(stringResource(R.string.ai_consent_decline))
                    }
                    Spacer(Modifier.width(4.dp))
                    // Accepting does NOT dismiss: the gate moves on to the
                    // sign-in by itself, in the same window, so the user goes
                    // from "yes" to the sign-in button without a flicker of the
                    // editor in between.
                    Button(onClick = onAccept) {
                        Text(stringResource(R.string.ai_consent_accept))
                    }
                }
            }
        }
    }
}
