package space.linuxct.glyphmatrixtoycompat.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.ui.theme.GmtcTheme

/**
 * Prominent pre-flight disclosure shown before sending the user to the
 * system accessibility settings (Play-policy style consent).
 */
class DisclosureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GmtcTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) {
                        Text(
                            stringResource(R.string.disclosure_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.disclosure_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { finish() }) {
                                Text(stringResource(R.string.disclosure_decline))
                            }
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                finish()
                            }) {
                                Text(stringResource(R.string.disclosure_accept))
                            }
                        }
                    }
                }
            }
        }
    }
}
