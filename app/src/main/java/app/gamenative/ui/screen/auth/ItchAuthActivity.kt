package app.gamenative.ui.screen.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import app.gamenative.service.gog.GOGConstants
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.redactUrlForLogging
import timber.log.Timber

/**
 * Itch Auth Activity does some stuff
 */
class ItchAuthActivity : ComponentActivity() {

    companion object {
        // i don't think this is necessary but idk
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_ERROR = "error"
        private const val SAVED_OAUTH_STATE = "oauth_state"
        private const val SAVED_AUTH_URL = "auth_url"
    }

    private var oauthState: String? = null
    private var initialAuthUrl: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        oauthState?.let { outState.putString(SAVED_OAUTH_STATE, it) }
        initialAuthUrl?.let { outState.putString(SAVED_AUTH_URL, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (authUrl, state) = if (savedInstanceState != null) {
            val savedState = savedInstanceState.getString(SAVED_OAUTH_STATE)
            val savedUrl = savedInstanceState.getString(SAVED_AUTH_URL)
            if (savedState != null && savedUrl != null) {
                savedUrl to savedState
            } else {
                GOGConstants.LoginUrlWithState()
            }
        } else {
            GOGConstants.LoginUrlWithState()
        }
        oauthState = state
        initialAuthUrl = authUrl

        setContent {
            PluviaTheme {
                val alertBuilder = AlertDialog.Builder(this);
                alertBuilder.setTitle("Itch API key");
                val input = EditText(this);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                alertBuilder.setView(input);

                var completed = false;

                alertBuilder.setOnDismissListener {
                    if (!completed) {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                }
                alertBuilder.setPositiveButton("Ok") { dialog, which ->
                    val apiKey = input.text.toString();
                    val resultIntent = Intent().apply {
                        putExtra(ItchAuthActivity.EXTRA_AUTH_CODE, apiKey)
                    }
                    completed = true;
                    setResult(RESULT_OK, resultIntent)
                    finish()
                };
                alertBuilder.show();
            }
        }
    }
}
