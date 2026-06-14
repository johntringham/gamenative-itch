package app.gamenative.service.itch

import android.content.Context
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object ItchAuthManager {

    private val httpClient = Net.http
    internal val apiUrlRoot : String = "https://api.itch.io";

    fun getAuthConfigPath(context: Context): String {
        return "${context.filesDir}/itch_auth.json"
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val authFile = File(getAuthConfigPath(context))
        return authFile.exists()
    }

    suspend fun authenticateWithApiKey(context : Context, apiKey : String) : Result<ItchCredentials>{
        Timber.tag("Starting itch auth with API key");

        if(apiKey.isEmpty()){
            return Result.failure(Exception("Empty Itch API key"));
        }

        val authConfigPath = ItchAuthManager.getAuthConfigPath(context)

        // Create auth config directory
        val authFile = File(authConfigPath)
        val authDir = authFile.parentFile
        if (authDir != null && !authDir.exists()) {
            authDir.mkdirs()
            Timber.tag("itch").d("Created auth config directory: ${authDir.absolutePath}")
        }

        Timber.tag("itch").d("Validating API key by fetching user profile");

        val profileUrl = "$apiUrlRoot/profile".toHttpUrl();
        val request = okhttp3.Request.Builder().url(profileUrl).get().header("Authorization", "Bearer $apiKey").build();

        val profileJson = withContext(Dispatchers.IO){
            httpClient.newCall(request).execute()
        }.use { response ->
            Timber.tag("itch");

            if(response.isSuccessful == false){
                val errorBody = response.body?.string() ?: "Unknown error";
                Timber.tag("itch").e("API key check failed");
                return Result.failure(Exception("failed to auth: ${response.code} - $errorBody"));
            }

            val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            Timber.tag("itch").d("Response body received, length: ${responseBody.length}")
            val response = JSONObject(responseBody);

            // todo: delete this, don't want to log this
            Timber.tag("itch").i(response.toString(2));

            response;
        }

        return Result.failure(Exception("failed :("));
    }
}

data class ItchCredentials (
    val userName : String,
    val displayName : String,
    val apiKey : String,
)
