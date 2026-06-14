package app.gamenative.service.itch

import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber

private val httpClient = Net.http
internal val apiUrlRoot : String = "https://api.itch.io";

@Serializable
data class OwnedKeysResponse(
    val page: Long,
    val perPage: Long,
    val ownedKeys: List<OwnedKey>,
)

@Serializable
data class OwnedKey(
    val purchaseId: Long?,
    val id: Long,
    val downloads: Long,
    val createdAt: String,
    val updatedAt: String,
    val game: Game,
    val gameId: Long,
)

@Serializable
data class Game(
    val id: Long,
    val type: String,
    val createdAt: String,
    val publishedAt: String?,
    val minPrice: Long,
    val user: User,
    val classification: String,
    val shortText: String?,
    val coverUrl: String,
    val traits: List<String>,
    val url: String,
    val title: String,
    val stillCoverUrl: String?,
    val sale: Sale?,
)

@Serializable
data class User(
    @SerialName("cover_url")
    val coverUrl: String?,
    val id: Long,
    val url: String,
    val username: String,

    @SerialName("display_name")
    val displayName: String?,
)
@Serializable
data class ProfileResponse(
    val user : User
)

@Serializable
data class Sale(
    val startDate: String,
    val id: Long,
    val endDate: String,
    val rate: Long,
)

object ItchApiClient {

    val jsonSerializer = Json { ignoreUnknownKeys = true }

    public suspend fun getProfile(apiKey : String) : Result<User>
    {
        val profileUrl = "$apiUrlRoot/profile".toHttpUrl();
        val request = okhttp3.Request.Builder().url(profileUrl).get().header("Authorization", "Bearer $apiKey").build();

        val profileResponse = withContext(Dispatchers.IO){
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

            try {
                val profileResponse = jsonSerializer.decodeFromString<ProfileResponse>(responseBody);
                profileResponse;
            } catch (e : SerializationException)
            {
                return Result.failure(Exception("Unable to deserialize API response - ${e.message}"));
            }
        }

        return Result.success(profileResponse.user);
    }
}
