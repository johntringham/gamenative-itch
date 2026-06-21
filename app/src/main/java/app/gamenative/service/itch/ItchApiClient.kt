package app.gamenative.service.itch

import androidx.compose.animation.core.keyframes
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
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
    @Serializable(with = TreatEmptyObjectsAsEmptyListsSerializer::class)
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
    @Serializable(with = TreatEmptyObjectsAsEmptyListsSerializer::class)
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
    @Serializable(with = TreatEmptyObjectsAsEmptyListsSerializer::class)
    val ownedKeys: List<OwnedKey>,
)

@Serializable
data class UploadsResponse(
    @Serializable(with = TreatEmptyObjectsAsEmptyListsSerializer::class)
    val uploads: List<Upload>,
)

@Serializable
data class ProfileResponse(
    val user : User
)

object ItchApiClient {

    val jsonSerializer = Json { ignoreUnknownKeys = true;
        useArrayPolymorphism = true;
        isLenient = true;
    }

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

    suspend fun getOwnedKeys(auth : ItchAuth) : Result<List<OwnedKey>>
    {
        try {
            var page = 1;
            val keys = ArrayList<OwnedKey>()
            while(page < 20){
                var keysOnPage = getOwnedKeysPage(auth, page).getOrThrow();
                if(keysOnPage.count() == 0){
                    // last page
                    return Result.success(keys);
                }

                keys.addAll(keysOnPage);

                page++;
            }

            Timber.tag("itch").i("Stopped looking for owned-keys after page 20");
            return Result.success(keys);

        } catch (e : Exception) {
            return Result.failure(Exception("Failed to get games list - ${e.message}"))
        }
    }

    suspend fun getOwnedKeysPage(auth : ItchAuth, page : Int) : Result<List<OwnedKey>>
    {
        try {
            val ownedKeysUrl = "$apiUrlRoot/profile/owned-keys?page=$page".toHttpUrl();
            val ownedKeysResponse = makeApiCall<OwnedKeysResponse>(auth, ownedKeysUrl).getOrThrow();
            return Result.success(ownedKeysResponse.ownedKeys); // todo: pages. it's just a query param ?pages=5
        } catch (e : Exception) {
            return Result.failure(Exception("Failed to get games list"))
        }
    }

    suspend fun getGameUploads(auth : ItchAuth, gameId : Long, downloadKeyId : Long) : Result<List<Upload>>
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

// Itch json sometimes returns {} for empty arrays instead of []. This serialiser is necessary to check if
// things we expect to be arrays aren't actually empty objects.
class TreatEmptyObjectsAsEmptyListsSerializer<E>(val elementSerializer: KSerializer<E>) : KSerializer<List<E>>
{
    override val descriptor: SerialDescriptor = ListSerializer(elementSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<E>) {
        val size = value.size
        val composite = encoder.beginCollection(descriptor, size)
        val iterator = value.iterator()
        for (index in 0 until size) {
            composite.encodeSerializableElement(descriptor, index, elementSerializer, iterator.next())
        }
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): List<E> {
        val jsonInput = decoder as? JsonDecoder
            ?: error("Can be deserialized only by JSON")
        val rawJson = jsonInput.decodeJsonElement()
        if (rawJson !is JsonArray) {
            return arrayListOf()
        }

        val jsonArray = rawJson.jsonArray
        val jsonParser = jsonInput.json
        val arrayList = ArrayList<E>(jsonArray.size)

        jsonArray.forEach { jsonElement ->
            val result = readElement(jsonParser, jsonElement)
            when {
                result.isSuccess -> arrayList.add(result.getOrThrow())
                result.isFailure -> error("Failed to deserialize")
            }
        }
        arrayList.trimToSize()
        return arrayList
    }

    private fun readElement(json: Json, jsonElement: JsonElement): Result<E> {
        return try {
            Result.success(json.decodeFromJsonElement(elementSerializer, jsonElement))
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }
}
