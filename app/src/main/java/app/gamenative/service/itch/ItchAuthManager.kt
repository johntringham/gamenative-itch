package app.gamenative.service.itch

import android.content.Context
import app.gamenative.service.gog.GOGAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class ItchAuth (
    val apiKey : String,
    val userName : String,
    val displayName: String?,
    val userId : Long,
)

object ItchAuthManager {
    fun getAuthConfigPath(context: Context): String {
        return "${context.filesDir}/itch_auth.json"
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val authFile = File(getAuthConfigPath(context))
        return authFile.exists()
    }

    suspend fun authenticateWithApiKey(context : Context, apiKey : String) : Result<ItchAuth>{
        Timber.tag("Starting itch auth with API key");

        if(apiKey.isEmpty()){
            return Result.failure(Exception("Empty Itch API key"));
        }

        val userResult = ItchApiClient.getProfile(apiKey);
        if(userResult.isSuccess == false)
        {
            Timber.tag("itch").e("Failed to fetch user profile, probably invalid API key");
            return Result.failure(Exception("Failed to check API key"));
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

        val user = userResult.getOrThrow();
        // Store credentials to file
        val authData = ItchAuth(
            apiKey,
            user.username,
            user.displayName,
            user.id
        )

        withContext(Dispatchers.IO) {
            authFile.writeText(Json.encodeToString(authData));
        }
        Timber.tag("itch").d("Itch auth written to file");

        return Result.success(authData);
    }

    /**
     * Get user credentials from storage
     */
    suspend fun getStoredCredentials(context: Context): Result<ItchAuth> {
        return try {
            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Read credentials from file (IO dispatcher)
            val authFile = File(getAuthConfigPath(context))
            val authContent = withContext(Dispatchers.IO) { authFile.readText() }
            val authJson = Json.decodeFromString<ItchAuth>(authContent);
            return Result.success(authJson);
        } catch (e: Exception) {
            Timber.tag("itch").e(e, "Failed to get stored credentials")
            Result.failure(e)
        }
    }

    fun clearStoredCredentials(context: Context): Boolean
    {
        return try {
            val authFile = File(getAuthConfigPath(context))
            if (authFile.exists()) {
                authFile.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Timber.tag("itch").e(e, "Failed to clear itch credentials")
            false
        }
    }
}
