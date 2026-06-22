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
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    suspend fun startBackgroundSync(context : Context): Result<Unit> {
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

        Timber.tag("itch").d("Getting Game Details for ${newGameKeys.size} new itch Games...")

        val games = mutableListOf<ItchGame>()

        for ((index, ownedKey) in newGameKeys.withIndex()) {
            val game = ownedKey.game;
            val itchGame = ItchGame(
                ownedKey.gameId,
                ownedKey.game.title,
                ownedKey.id,
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

        return Result.success(ownedKeys.size);
    }

    suspend fun deleteAllNonInstalledGames() {
        withContext(Dispatchers.IO) {
            itchGameDao.deleteAllNonInstalledGames()
        }
    }

    suspend fun getGameFromDbById(gameId: Long): app.gamenative.data.ItchGame? {
        return itchGameDao.getById(gameId);
    }

    suspend fun updateGame(game: ItchGame) {
        withContext(Dispatchers.IO) {
            itchGameDao.update(game)
        }
    }

    fun verifyInstallation(gameId: Long) : Pair<Boolean, String?> {
        return Pair(true, "haven't implemented this yet lol")
    }

    /**
     * Resolves the effective launch executable for a itch game (container config or auto-detected).
     * Returns empty string if no executable can be found.
     */
    suspend fun getLaunchExecutable(appId: String, container: Container): String = withContext(Dispatchers.IO) {
        container.executablePath.ifEmpty {
            getInstalledExe(LibraryItem(appId = appId, name = "", gameSource = GameSource.ITCH))
        }
    }

    suspend fun getUploadsForGame(context : Context, gameId : Long): Result<List<Upload>> {
        val game = getGameFromDbById(gameId);
        val itchAuth = ItchAuthManager.getStoredCredentials(context).getOrNull();
        if(game != null && itchAuth != null){
            return ItchApiClient.getGameUploads(itchAuth, gameId, game.downloadKey);
        } else {
            return Result.failure(Exception("Failed to get game uploads"));
        }
    }

    suspend fun getInstalledExe(libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val gameId = libraryItem.gameId.toLong()
        try {
            val game = getGameFromDbById(gameId) ?: return@withContext ""
            val installPath = getGameInstallPath(context,game.title)

            val topLevelExecutables = File(installPath).listFiles { f -> f.extension == "exe" };
            if(topLevelExecutables?.size == 1){
                topLevelExecutables[0].name;
            }else{
            ""
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get executable for itch game $gameId")
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
                        Timber.w("Itch game directory doesn't exist: $path")
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

    fun getWineStartCommand(
        libraryItem: LibraryItem,
        container: Container,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
        gameId: Long,
    ): String {
        // Verify installation
        val (isValid, errorMessage) = verifyInstallation(gameId)
        if (!isValid) {
            Timber.e("Installation verification failed: $errorMessage")
            return "\"explorer.exe\""
        }

        val game = runBlocking { getGameFromDbById(gameId) }
        if (game == null) {
            Timber.e("Game not found for ID: $gameId")
            return "\"explorer.exe\""
        }

        val gameInstallPath = getGameInstallPath(context, game.title)
        val gameDir = File(gameInstallPath)

        if (!gameDir.exists()) {
            Timber.e("Game directory does not exist: $gameInstallPath")
            return "\"explorer.exe\""
        }

        // Use container's configured executable path if available, otherwise auto-detect
        val executablePath = if (container.executablePath.isNotEmpty()) {
            Timber.d("Using configured executable path from container: ${container.executablePath}")
            container.executablePath
        } else {
            val detectedPath = runBlocking { getInstalledExe(libraryItem) }
            Timber.d("Auto-detected executable path: $detectedPath")
            if (detectedPath.isNotEmpty()) {
                container.executablePath = detectedPath
                container.saveData()
            }
            detectedPath
        }

        if (executablePath.isEmpty()) {
            Timber.w("No executable found, opening file manager")
            return "\"explorer.exe\""
        }

        // Find the drive letter that's mapped to this game's install path
        var itchDriveLetter: String? = null
        for (drive in com.winlator.container.Container.drivesIterator(container.drives)) {
            if (drive[1] == gameInstallPath) {
                itchDriveLetter = drive[0]
                Timber.d("Found itch game mapped to ${drive[0]}: drive")
                break
            }
        }

        if (itchDriveLetter == null) {
            Timber.e("itch game directory not mapped to any drive: $gameInstallPath")
            return "\"explorer.exe\""
        }

        val gameInstallDir = File(gameInstallPath)
        val execFile = File(gameInstallPath, executablePath)
        // Handle potential IllegalArgumentException if paths don't share a common ancestor
        val relativePath = try {
            execFile.relativeTo(gameInstallDir).path.replace('/', '\\')
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Failed to compute relative path from $gameInstallDir to $execFile")
            return "\"explorer.exe\""
        }

        val windowsPath = "$itchDriveLetter:\\$relativePath"

        // Set working directory
        val execWorkingDir = execFile.parentFile
        if (execWorkingDir != null) {
            guestProgramLauncherComponent.workingDir = execWorkingDir
            envVars.put("WINEPATH", "$itchDriveLetter:\\")
        } else {
            guestProgramLauncherComponent.workingDir = gameDir
        }

        Timber.d("itch Wine command: \"$windowsPath\"")
        return "\"$windowsPath\""
    }
}
