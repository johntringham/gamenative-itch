package app.gamenative.ui.screen.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.service.itch.ItchApiClient
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.core.net.toUri


/**
 * Itch Auth Activity does some stuff
 */
class ItchAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            content()
        }
    }

    @Preview
    @Composable
    fun content(){
        val coroutineScope = rememberCoroutineScope()
        var apiKey by remember { mutableStateOf("") }
        PluviaTheme {
            Dialog(
                onDismissRequest = { dismiss() },
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentHeight(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Title
                        Text(
                            text = stringResource(R.string.itch_settings_login_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )

                        Text(
                            text = stringResource(R.string.itch_auth_screen_api_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { generateApiKey() }) {
                                Text("Create API key")
                            }
                        }

                        NoExtractOutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.itch_auth_screen_api_key_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,

                            ) {
                            TextButton(onClick = { dismiss() }) {
                                Text("Dismiss")
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch { submitApiKey(apiKey) }
                                },
                                modifier = Modifier.padding(start = 8.dp),
                                enabled = apiKey.length > 0,
                            ) {
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun submitApiKey(apiKey : String) {
        val profileResult = ItchApiClient.getProfile(apiKey);
        if(!profileResult.isSuccess)
        {
            SnackbarManager.show(profileResult.exceptionOrNull()?.message ?: "Failed to verify API key");

            // dismiss() is a bit of a hack to get the fact the snackbar messages are displayed
            // under the dialog, so the user can't see them with the dialog open.
            dismiss()
            return;
        }
        else
        {
            val profile = profileResult.getOrThrow();
            val name = profile.displayName ?: profile.username;
            SnackbarManager.show("Welcome ${name}!");

            val resultIntent = Intent().apply {
                putExtra(EXTRA_AUTH_CODE, apiKey)
            }

            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    fun dismiss(){
        setResult(RESULT_CANCELED)
        finish()
    }

    fun generateApiKey(){
        Timber.tag("itch").i("Sending user to itch.io to generate API key");
        val browserIntent = Intent(Intent.ACTION_VIEW, "https://itch.io/user/settings/api-keys".toUri())
        startActivity(browserIntent)
    }
}
