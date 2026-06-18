package app.gamenative.service.itch

import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import timber.log.Timber

private val httpClient = Net.http
internal val apiUrlRoot : String = "https://api.itch.io";

@Serializable
data class OwnedKey(
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("game_id")
    val gameId: Long,
    @SerialName("purchase_id")
    val purchaseId: Long? = null,
    val id: Long,
    val downloads: Long? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val game: Game,
)

@Serializable
data class Game(
    @SerialName("created_at")
    val createdAt: String? = null,
    val type: String? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("short_text")
    val shortText: String? = null,
    @SerialName("min_price")
    val minPrice: Long? = null,
    val title: String,
    val classification: String? = null,
    val id: Long,
    val url: String,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val traits: List<String>? = null,
    val user: User? = null,
)

@Serializable
data class User(
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val id: Long,
    val url: String? = null,
    val username: String,

    @SerialName("display_name")
    val displayName: String? = null,
)

@Serializable
data class Upload(
    @SerialName("channel_name")
    val channelName: String? = null,
    val position: Long? = null,
    @SerialName("game_id")
    val gameId: Long,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    val traits: List<String>?= null,
    val filename: String?= null,
    @SerialName("build_id")
    val buildId: Long?= null,
    val size: Long,
    @SerialName("updated_at")
    val updatedAt: String?= null,
    val build: Build?= null,
    val id: Long,
    val type: String?,
    val storage: String?,
)

@Serializable
data class Build(
    @SerialName("upload_id")
    val uploadId: Long,
    @SerialName("created_at")
    val createdAt: String?= null,
    val state: String?= null,
    @SerialName("user_id")
    val userId: Long?= null,
    @SerialName("updated_at")
    val updatedAt: String?= null,
    val id: Long,
    val version: Long?= null,
    @SerialName("game_id")
    val gameId: Long,
    @SerialName("parent_build_id")
    val parentBuildId: Long?= null,
)


@Serializable
data class OwnedKeysResponse(
    val page: Long,
    @SerialName("owned_keys")
    val ownedKeys: List<OwnedKey>,
)

@Serializable
data class UploadsResponse(
    val uploads: List<Upload>,
)

@Serializable
data class ProfileResponse(
    val user : User
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

    public suspend fun getOwnedKeys(auth : ItchAuth) : Result<List<OwnedKey>>
    {
        try {
            val ownedKeysUrl = "$apiUrlRoot/profile/owned-keys".toHttpUrl();
            val ownedKeysResponse = makeApiCall<OwnedKeysResponse>(auth, ownedKeysUrl).getOrThrow();
            return Result.success(ownedKeysResponse.ownedKeys); // todo: pages. it's just a query param ?pages=5
        } catch (e : Exception) {
            return Result.failure(Exception("Failed to get games list"))
        }
    }

    public suspend fun getGameUploads(auth : ItchAuth, gameId : Long, downloadKeyId : Long) : Result<List<Upload>>
    {
        try {
            val url = "$apiUrlRoot/games/$gameId/uploads?download_key_id=$downloadKeyId".toHttpUrl();
            val response = makeApiCall<UploadsResponse>(auth, url).getOrThrow();
            return Result.success(response.uploads);
        } catch (e : Exception) {
            return Result.failure(Exception("Failed to get game uploads - ${e.message}"))
        }
    }

    public fun getDownloadUrl(uploadId : Long, downloadKey : Long, apiKey : String): Request {
        val url = "$apiUrlRoot/uploads/$uploadId/download?download_key_id=$downloadKey&api_key=$apiKey"
        val request = Request.Builder().url(url).get().build();
        return request;
    }

    private suspend inline fun <reified TResponse> makeApiCall(
        auth: ItchAuth,
        url: HttpUrl
    ): Result<TResponse> {
        val apiKey = auth.apiKey;
        val request = Request.Builder().url(url)
            .get()
            .header("Authorization", "Bearer $apiKey")
            .build();

        Timber.tag("itch").i("Making itch api call to ${request.url}");

        val serialisedHttpResponse = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute()
        }.use { response ->
            Timber.tag("itch");

            if (response.isSuccessful == false) {
                val errorBody = response.body?.string() ?: "Unknown error";
                Timber.tag("itch").e("API key check failed");
                return Result.failure(Exception("failed to auth: ${response.code} - $errorBody"));
            }

            val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            Timber.tag("itch").d("Response body received, length: ${responseBody.length}")

            try {
                val profileResponse = jsonSerializer.decodeFromString<TResponse>(responseBody);
                profileResponse;
            } catch (e: SerializationException) {
                Timber.tag("itch").e("Unable to deserialize API response - ${e.message}");
                return Result.failure(Exception("Unable to deserialize API response - ${e.message}"));
            }
        }

        return Result.success(serialisedHttpResponse);
    }
}
