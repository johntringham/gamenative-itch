package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.data.ItchGame
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.ItchGameDao
import app.gamenative.enums.AppType
import app.gamenative.enums.Marker
import app.gamenative.service.itch.ItchConstants.getGameInstallPath
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import com.winlator.container.Container
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ItchManager @Inject constructor(
    private val itchGameDao: ItchGameDao,
    @ApplicationContext private val context: Context,
) {

    private val REFRESH_BATCH_SIZE = 10

    public suspend fun startBackgroundSync(context : Context): Result<Unit> {
        try {
            if (!ItchAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("itch").i("Starting itch library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("itch").i("Background sync completed: $count games synced")
                return Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                return Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync itch library in background")
            return Result.failure(e)
        }
    }

    suspend fun refreshLibrary(context: Context) : Result<Int> {
        if (!ItchAuthManager.hasStoredCredentials(context)) {
            Timber.w("Cannot refresh library: not authenticated with itch")
            return Result.failure(Exception("Not authenticated with itch"))
        }

        val ownedKeysResult = ItchApiClient.getOwnedKeys(ItchAuthManager.getStoredCredentials(context).getOrThrow());
        if (!ownedKeysResult.isSuccess) {
            return Result.failure(Exception("Failed to get owned keys"));
        }

        val ownedKeys = ownedKeysResult.getOrNull() ?: emptyList();

        Timber.tag("itch").i("Successfully fetched ${ownedKeys.size} owned-keys from itch")

        if (ownedKeys.isEmpty()) {
            Timber.w("No games found in itch library")
            return Result.success(0)
        }

        // Get existing game IDs from database to avoid re-fetching
        val existingGameIds = itchGameDao.getAllGameIdsIncludingExcluded().toMutableSet()

        Timber.tag("itch").d("Found ${existingGameIds.size} games already in database")

        // Filter to only new games that need details fetched
        val newGameKeys = ownedKeys.filter { it.gameId !in existingGameIds }
        Timber.tag("itch").d("${newGameKeys.size} new games need details fetched")

        if (newGameKeys.isEmpty()) {
            Timber.tag("itch").d("No new games to fetch, library is up to date")
            return Result.success(0)
        }

        var totalProcessed = 0

        Timber.tag("itch").d("Getting Game Details for ${newGameKeys.size} new GOG Games...")

        val games = mutableListOf<ItchGame>()

        // Use direct HTTP calls via GOGApiClient
        for ((index, ownedKey) in newGameKeys.withIndex()) {
//            try {
//                // Fetch game details using direct HTTP call
//                val result = GOGApiClient.getGameById(context, id)
//
//                if (result.isSuccess) {
//                    val gameDetails = result.getOrNull()
//                    if (gameDetails != null) {
//                        Timber.tag("GOG").d("Got Game Details for ID: $id")
//                        val game = parseGameObject(gameDetails)
//                        if (game != null) {
//                            games.add(game)
//                            Timber.tag("GOG").d("Refreshed Game: ${game.title}")
//                            totalProcessed++
//                        }
//                    }
//                } else {
//                    Timber.w("GOG game ID $id not found in library after refresh")
//                }
//            } catch (e: Exception) {
//                Timber.e(e, "Failed to parse game details for ID: $id")
//            }

            val game = ownedKey.game;
            val itchGame = ItchGame(
                ownedKey.gameId,
                ownedKey.game.title,
                0,
                0,
                false,
                "",
                ownedKey.game.coverUrl ?: "",
                ownedKey.game.coverUrl ?: "",
                ownedKey.game.shortText ?: "",
                ownedKey.game.publishedAt ?: "",
                game.user?.displayName ?: game.user?.username ?: game.user?.url ?: "",
                0, 0, AppType.game
            );
            games.add(itchGame)

            if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == newGameKeys.size - 1) {
                if (games.isNotEmpty()) {
                    itchGameDao.upsertPreservingInstallStatus(games)
                    Timber.tag("itch").d("Batch inserted ${games.size} games (processed ${index + 1}/${newGameKeys.size})")
                    games.clear()
                }
            }
        }

//        val detectedCount = detectAndUpdateExistingInstallations()
//        if (detectedCount > 0) {
//            Timber.d("Detected and updated $detectedCount existing installations")
//        }

        return Result.success(ownedKeys.size);
    }

    fun deleteAllNonInstalledGames() {
        TODO("Not yet implemented")
    }

    suspend fun getGameFromDbById(gameId: Long): app.gamenative.data.ItchGame? {
        return itchGameDao.getById(gameId);
    }
//    fun updateGame(game: ItchGame) {}
    fun verifyInstallation(gameId: Long) : Pair<Boolean, String?> {
        return Pair(false, "haven't implemented this yet lol")
    }

    /**
     * Resolves the effective launch executable for a GOG game (container config or auto-detected).
     * Returns empty string if no executable can be found.
     */
    suspend fun getLaunchExecutable(appId: String, container: Container): String = withContext(Dispatchers.IO) {
        container.executablePath.ifEmpty {
            getInstalledExe(LibraryItem(appId = appId, name = "", gameSource = GameSource.GOG))
        }
    }

    // Get the exe. There is a v1 and v2 depending on the age of the game.
    suspend fun getInstalledExe(libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val gameId = libraryItem.gameId.toLong()
        try {
//            val game = getGameFromDbById(gameId) ?: return@withContext ""
//            val installPath = getGameInstallPath(context,game.id, game.title)

//            // todo
//
//            // Try V1 structure: goggame-*.info and exe can be in install root or in a subdir
//            val installDirFile = File(installPath)
//            val exe = getGameExecutable(installPath, installDirFile)
//            if (exe.isNotEmpty()) return@withContext exe
//            val subdirs = installDirFile.listFiles()?.filter {
//                it.isDirectory && it.name != "saves" && it.name != "_CommonRedist"
//            } ?: emptyList()
//
//            for (subdir in subdirs) {
//                val subdirExe = getGameExecutable(installPath, subdir)
//                if (subdirExe.isNotEmpty()) return@withContext subdirExe
//            }

            ""
        } catch (e: Exception) {
            Timber.e(e, "Failed to get executable for GOG game $gameId")
            ""
        }
    }

    suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val gameId = libraryItem.gameId.toLong()

                val game = getGameFromDbById(gameId)
                val storedPath = game?.installPath?.takeIf { it.isNotBlank() }
                val computedPath = getGameInstallPath(context, libraryItem.name) // todo - is name the right choice here? - no
                val pathsToClean = listOfNotNull(storedPath, computedPath).distinct()

                val manifestPath = File(context.filesDir, "manifests/$gameId")
                if (manifestPath.exists()) {
                    manifestPath.delete()
                    Timber.i("Deleted manifest file for game $gameId")
                }

                val failedPaths = mutableListOf<String>()
                for (path in pathsToClean) {
                    val dir = File(path)
                    if (dir.exists()) {
                        if (dir.deleteRecursively()) {
                            Timber.i("Successfully deleted game directory: $path")
                        } else {
                            Timber.w("Failed to delete some game files at $path")
                            failedPaths.add(path)
                        }
                    } else {
                        Timber.w("GOG game directory doesn't exist: $path")
                    }
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }

                if (game != null) {
                    val updatedGame = game.copy(isInstalled = false, installPath = "")
                    itchGameDao.update(updatedGame)
                    Timber.d("Updated database: game marked as not installed")
                }

                withContext(Dispatchers.Main) {
                    ContainerUtils.deleteContainer(context, libraryItem.appId)
                }

                // Trigger library refresh event
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId, app.gamenative.data.GameSource.ITCH),
                )

                if (failedPaths.isNotEmpty()) {
                    return@withContext Result.failure(Exception("Failed to fully delete at: ${failedPaths.joinToString()}"))
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete Itch game ${libraryItem.gameId}")
                Result.failure(e)
            }
        }
    }
}
