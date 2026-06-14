package app.gamenative.service.itch

import android.content.Context
import app.gamenative.service.gog.api.GOGManifestParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class OwnedKeysResponse(
    val page: Long,
    val perPage: Long,
    val ownedKeys: List<OwnedKey>,
)

data class OwnedKey(
    val purchaseId: Long?,
    val id: Long,
    val downloads: Long,
    val createdAt: String,
    val updatedAt: String,
    val game: Game,
    val gameId: Long,
)

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

data class User(
    val coverUrl: String?,
    val id: Long,
    val url: String,
    val username: String,
    val displayName: String?,
    val stillCoverUrl: String?,
)

data class Sale(
    val startDate: String,
    val id: Long,
    val endDate: String,
    val rate: Long,
)


@Singleton
class ItchApiClient @Inject constructor(
    @ApplicationContext private val context: Context
){

}
