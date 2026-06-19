package app.gamenative.service.itch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.PluviaApp
import app.gamenative.data.DownloadInfo
import app.gamenative.data.ItchGame
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.ItchGameDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.NotificationHelper
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject


/**
 * Itch Service - thin abstraction layer that delegates to managers.
 *
 * Architecture:
 * - ItchApiClient: Api Layer for interacting with Itch's APIs
 * - ItchDownloadManager: Handles Download Logic for Games
 * - ItchConstants: Shared Constants for our Itch-related data
 * - ItchCloudSavesManager: Handler for Cloud Saves
 * - ItchAuthManager: Authentication and account management
 * - ItchManager: Game library, downloads, and installation
 * - ItchManifestParser: Parses and has utils for parsing/extracting/decompressing manifests.
 * - ItchDataMdoels: Data Models for Itch-related Data types such as API responses
 *
 */
@AndroidEntryPoint
class ItchService : Service() {
    companion object {
        val externalItchGamesPath: String? = null;
        val internalItchGamesPath: String? = null;
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.ITCH_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.ITCH_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: ItchService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            // If already running, do nothing
            if (isRunning) {
                Timber.d("[ItchService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.i("[ItchService] First-time start - starting service with initial sync")
                val intent = Intent(context, ItchService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: always start service, but check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, ItchService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[ItchService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.d("[ItchService] Starting service without sync - throttled (${remainingMinutes}min remaining)")
                // Start service without sync action
            }
            context.startForegroundService(intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.i("[ItchService] Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, ItchService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to ItchAuthManager
        // ==========================================================================

//        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<ItchAuth> {
//            return ItchAuthManager.authenticateWithCode(context, authorizationCode)
//        }

        fun hasStoredCredentials(context: Context): Boolean {
            return ItchAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<ItchAuth> {
            return ItchAuthManager.getStoredCredentials(context)
        }

//        suspend fun validateCredentials(context: Context): Result<Boolean> {
//            return ItchAuthManager.validateCredentials(context)
//        }

        fun clearStoredCredentials(context: Context): Boolean {
            return ItchAuthManager.clearStoredCredentials(context)
        }

        /**
         * Logout from Itch - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.i("[ItchService] Logging out from Itch...")

                    // Get instance first before stopping the service
                    val instance = getInstance()
                    if (instance == null) {
                        Timber.w("[ItchService] Service instance not available during logout")
                        return@withContext Result.failure(Exception("Service not running"))
                    }

                    // Clear stored credentials
                    val credentialsCleared = clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.w("[ItchService] Failed to clear credentials during logout")
                    }

                    // Clear all non-installed Itch games from database
                    instance.itchManager.deleteAllNonInstalledGames()
                    Timber.i("[ItchService] All non-installed Itch games removed from database")

                    // Stop the service
                    stop()

                    Timber.i("[ItchService] Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "[ItchService] Error during logout")
                    Result.failure(e)
                }
            }
        }

        fun internalItchGamesPath(context: android.content.Context): String {
            val path = Paths.get(context.dataDir.path, "Itch", "games").toString()
            File(path).mkdirs()
            return path
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): ItchService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance ItchManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }

        fun getActiveDownloads(): Map<String, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        fun hasPartialDownload(context : Context, game: ItchGame): Boolean {
            if (game.isInstalled) return false
            val title = game.title.ifBlank { return false }
            val installPath = ItchConstants.getGameInstallPath(context,title)
            return app.gamenative.utils.MarkerUtils.hasPartialInstall(installPath)
        }

        fun hasPartialDownload(context : Context, gameId: Long, fallbackTitle: String? = null): Boolean {
            getItchGameOf(gameId)?.let { return hasPartialDownload(context,it) }
            val title = fallbackTitle?.ifBlank { null } ?: return false
            val installPath = ItchConstants.getGameInstallPath(context, title)
            return app.gamenative.utils.MarkerUtils.hasPartialInstall(installPath)
        }
//
//        private fun getPartialInstallPaths(): Set<String> {
//            val roots = buildList {
//                add(ItchConstants.internalItchGamesPath)
//                if (app.gamenative.PrefManager.externalStoragePath.isNotBlank()) {
//                    add(ItchConstants.externalItchGamesPath)
//                }
//            }.distinct()
//
//            return roots.asSequence()
//                .flatMap { root -> app.gamenative.utils.MarkerUtils.findResumablePartialInstalls(root).asSequence() }
//                .toSet()
//        }
//
//        suspend fun getPartialDownloads(): List<String> {
//            val instance = getInstance() ?: return emptyList()
//            val partialInstallPaths = getPartialInstallPaths()
//            if (partialInstallPaths.isEmpty()) return emptyList()
//
//            return instance.itchManager.getNonInstalledGames()
//                .asSequence()
//                .filter { game -> !instance.activeDownloads.containsKey(game.id) }
//                .filter { game ->
//                    val title = game.title.ifBlank { return@filter false }
//                    partialInstallPaths.contains(ItchConstants.getGameInstallPath(title))
//                }
//                .map { it.id }
//                .toList()
//        }

        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }

        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(gameId)
                Timber.d("Download cancelled for game: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance ItchManager
        // ==========================================================================

        fun getItchGameOf(gameId: Long): ItchGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.itchManager?.getGameFromDbById(gameId)
            }
        }

//        suspend fun updateItchGame(game: ItchGame) {
//            getInstance()?.itchManager?.updateGame(game)
//        }

        fun isGameInstalled(gameId: Long): Boolean {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.itchManager?.getGameFromDbById(gameId)
                if (game?.isInstalled != true) {
                    return@runBlocking false
                }

                // Verify the installation is actually valid
                val (isValid, errorMessage) = getInstance()?.itchManager?.verifyInstallation(gameId)
                    ?: Pair(false, "Service not available")
                if (!isValid) {
                    Timber.w("Game $gameId marked as installed but verification failed: $errorMessage")
                }
                isValid
            }
        }

        fun getInstallPath(gameId: Long): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.itchManager?.getGameFromDbById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }

        fun verifyInstallation(gameId: Long): Pair<Boolean, String?> {
            return getInstance()?.itchManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")
        }

//        suspend fun getInstalledExe(libraryItem: LibraryItem): String {
//            return getInstance()?.itchManager?.getInstalledExe(libraryItem)
//                ?: ""
//        }

        /**
         * Resolves the effective launch executable for a Itch game (container config or auto-detected).
         * Returns empty string if no executable can be found.
         */
        suspend fun getLaunchExecutable(appId: String, container: com.winlator.container.Container): String {
            return getInstance()?.itchManager?.getLaunchExecutable(appId, container) ?: ""
        }

        //  fun getGogWineStartCommand(
        //      libraryItem: LibraryItem,
        //      container: com.winlator.container.Container,
        //      bootToContainer: Boolean,
        //      appLaunchInfo: LaunchInfo?,
        //      envVars: com.winlator.core.envvars.EnvVars,
        //      guestProgramLauncherComponent: com.winlator.xenvironment.components.GuestProgramLauncherComponent,
        //      gameId: Int,
        //  ): String {
        //      return getInstance()?.itchManager?.getGogWineStartCommand(
        //          libraryItem, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent, gameId,
        //      ) ?: "\"explorer.exe\""
        //  }

        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.itchManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        suspend fun downloadGame(context: Context, gameId: Long, installPath: String, containerLanguage: String): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            val manager = instance.itchManager;
            // Create DownloadInfo for progress tracking
            val downloadInfo = DownloadInfo(jobCount = 1, gameId = 0, downloadingAppIds = CopyOnWriteArrayList<Int>())
            downloadInfo.setPersistencePath(installPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(installPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            val itchGame = manager.getGameFromDbById(gameId.toLong())
            if(itchGame == null){
                return Result.failure(Exception("Can't find game in database"));
            }

            // Track in activeDownloads first
            instance.activeDownloads[gameId.toString()] = downloadInfo

            // Launch download in service scope so it runs independently
            val job = instance.scope.launch {
                try {

                    val uploads = manager.getUploadsForGame(context, gameId).getOrThrow();

                    // builds could be linux or mac or etc - we just care about windows
                    val windowsUploads = uploads.filter { it.channelName == "win" || it.traits?.any { t -> t == "p_windows"} ?: false};
                    // todo: make this properly selectable, as there might be multiple windows downloads, or it might not be labelled correctly

                    Timber.i("${windowsUploads.count()} windows downloads found for itch game $gameId");

                    if(windowsUploads.isEmpty()){
                        Timber.i("No windows downloads found for itch game $gameId");
                        throw Exception("No windows builds found");
                    }

                    Timber.i("Found ${windowsUploads.count()} windows uploads");

                    Timber.d("[Download] Starting download for game $gameId")
                    val commonRedistDir = File(installPath, "_CommonRedist")
                    Timber.tag("itch").d("Will install dependencies to _CommonRedist")

                    val uploadToDownload = windowsUploads.first();
                    val apiKey = ItchAuthManager.getStoredCredentials(context).getOrNull()?.apiKey ?: "";

                    val result = instance.itchDownloadManager.downloadGame(
                        itchGame, uploadToDownload, apiKey, File(installPath), downloadInfo
                    )

                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[Download] Failed for game $gameId")
                        downloadInfo.setProgress(-1.0f)
                        downloadInfo.setActive(false)
                        SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")
                    } else {
                        Timber.i("[Download] Completed successfully for game $gameId")
                        SnackbarManager.show("Download completed successfully!")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                    }
                } catch (e: CancellationException) {
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId.toInt() ?: -1, false))
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId.toInt() ?: -1, false))
                    downloadInfo.setProgress(-1.0f)
                    downloadInfo.setActive(false)

                    SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")
                } finally {
                    // Remove from activeDownloads for both success and failure
                    // so UI knows download is complete and to prevent stale entries
                    instance.activeDownloads.remove(gameId.toString())
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }
            downloadInfo.setDownloadJob(job)

            return Result.success(downloadInfo)
        }


//        suspend fun refreshSingleGame(gameId: String, context: Context): Result<ItchGame?> {
//            return getInstance()?.itchManager?.refreshSingleGame(gameId, context)
//                ?: Result.failure(Exception("Service not available"))
//        }

        /**
         * Delete/uninstall a Itch game
         * Delegates to ItchManager.deleteGame
         */
        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            return getInstance()?.itchManager?.deleteGame(context, libraryItem)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Sync Itch cloud saves for a game
         * @param context Android context
         * @param appId Game app ID (e.g., "ITCH_123456")
         * @param preferredAction Preferred sync action: "download", "upload", or "none"
         * @return true if sync succeeded, false otherwise
         */
//        suspend fun syncCloudSaves(
//            context: Context,
//            appId: String,
//            preferredAction: String = "none",
//        ): Boolean = withContext(Dispatchers.IO) {
//            try {
//                Timber.tag("itch").d("[Cloud Saves] syncCloudSaves called for $appId with action: $preferredAction")
//
//                // Check if there's already a sync in progress for this appId
//                val serviceInstance = getInstance()
//                if (serviceInstance == null) {
//                    Timber.tag("itch").e("[Cloud Saves] Service instance not available for sync start")
//                    return@withContext false
//                }
//
//                if (!serviceInstance.itchManager.startSync(appId)) {
//                    Timber.tag("itch").w("[Cloud Saves] Sync already in progress for $appId, skipping duplicate sync")
//                    return@withContext false
//                }
//
//                try {
//                    val instance = getInstance()
//                    if (instance == null) {
//                        Timber.tag("itch").e("[Cloud Saves] Service instance not available")
//                        return@withContext false
//                    }
//
//                    if (!ItchAuthManager.hasStoredCredentials(context)) {
//                        Timber.tag("itch").e("[Cloud Saves] Cannot sync saves: not authenticated")
//                        return@withContext false
//                    }
//
//                    val authConfigPath = ItchAuthManager.getAuthConfigPath(context)
//                    Timber.tag("itch").d("[Cloud Saves] Using auth config path: $authConfigPath")
//
//                    // Get game info
//                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
//                    Timber.tag("itch").d("[Cloud Saves] Extracted game ID: $gameId from appId: $appId")
//                    val game = instance.itchManager.getGameFromDbById(gameId.toString())
//
//                    if (game == null) {
//                        Timber.tag("itch").e("[Cloud Saves] Game not found for appId: $appId")
//                        return@withContext false
//                    }
//                    Timber.tag("itch").d("[Cloud Saves] Found game: ${game.title}")
//
//                    // Get save directory paths (Android runs games through Wine, so always Windows)
//                    Timber.tag("itch").d("[Cloud Saves] Resolving save directory paths for $appId")
//                    val saveLocations = instance.itchManager.getSaveDirectoryPath(context, appId, game.title)
//
//                    if (saveLocations == null || saveLocations.isEmpty()) {
//                        Timber.tag("itch").w("[Cloud Saves] No save locations found for game $appId (cloud saves may not be enabled)")
//                        return@withContext false
//                    }
//                    Timber.tag("itch").i("[Cloud Saves] Found ${saveLocations.size} save location(s) for $appId")
//
//                    var allSucceeded = true
//
//                    // Sync each save location
//                    for ((index, location) in saveLocations.withIndex()) {
//                        try {
//                            Timber.tag("itch").d("[Cloud Saves] Processing location ${index + 1}/${saveLocations.size}: '${location.name}'")
//
//                            // Log directory state BEFORE sync
//                            try {
//                                val saveDir = java.io.File(location.location)
//                                Timber.tag("itch").d("[Cloud Saves] [BEFORE] Checking directory: ${location.location}")
//                                Timber.tag("itch").d("[Cloud Saves] [BEFORE] Directory exists: ${saveDir.exists()}, isDirectory: ${saveDir.isDirectory}")
//                                if (saveDir.exists() && saveDir.isDirectory) {
//                                    val filesBefore = saveDir.listFiles()
//                                    if (filesBefore != null && filesBefore.isNotEmpty()) {
//                                        Timber.tag("itch").i(
//                                            "[Cloud Saves] [BEFORE] ${filesBefore.size} files in '${location.name}': ${filesBefore.joinToString(", ") {
//                                                it.name
//                                            }}",
//                                        )
//                                    } else {
//                                        Timber.tag("itch").i("[Cloud Saves] [BEFORE] Directory '${location.name}' is empty")
//                                    }
//                                } else {
//                                    Timber.tag("itch").i("[Cloud Saves] [BEFORE] Directory '${location.name}' does not exist yet")
//                                }
//                            } catch (e: Exception) {
//                                Timber.tag("itch").e(e, "[Cloud Saves] [BEFORE] Failed to check directory")
//                            }
//
//                            // Get stored timestamp for this location
//                            val timestampStr = instance.itchManager.getCloudSaveSyncTimestamp(appId, location.name)
//                            val timestamp = timestampStr.toLongOrNull() ?: 0L
//
//                            Timber.tag("itch").i("[Cloud Saves] Syncing '${location.name}' for game $gameId (clientId: ${location.clientId}, path: ${location.location}, timestamp: $timestamp, action: $preferredAction)")
//
//                            // Validate clientSecret is available
//                            if (location.clientSecret.isEmpty()) {
//                                Timber.tag("itch").e("[Cloud Saves] Missing clientSecret for '${location.name}', skipping sync")
//                                continue
//                            }
//
//                            val cloudSavesManager = ItchCloudSavesManager(context)
//                            val newTimestamp = cloudSavesManager.syncSaves(
//                                clientId = location.clientId,
//                                clientSecret = location.clientSecret,
//                                localPath = location.location,
//                                dirname = location.name,
//                                lastSyncTimestamp = timestamp,
//                                preferredAction = preferredAction,
//                            )
//
//                            if (newTimestamp > 0) {
//                                // Success - store new timestamp
//                                instance.itchManager.setCloudSaveSyncTimestamp(appId, location.name, newTimestamp.toString())
//                                Timber.tag("itch").d("[Cloud Saves] Updated timestamp for '${location.name}': $newTimestamp")
//
//                                // Log the save files in the directory after sync
//                                try {
//                                    val saveDir = java.io.File(location.location)
//                                    if (saveDir.exists() && saveDir.isDirectory) {
//                                        val files = saveDir.listFiles()
//                                        if (files != null && files.isNotEmpty()) {
//                                            val fileList = files.joinToString(", ") { it.name }
//                                            Timber.tag("itch").i("[Cloud Saves] [$preferredAction] Files in '${location.name}': $fileList (${files.size} files)")
//
//                                            // Log detailed file info
//                                            files.forEach { file ->
//                                                val size = if (file.isFile) "${file.length()} bytes" else "directory"
//                                                Timber.tag("itch").d("[Cloud Saves] [$preferredAction]   - ${file.name} ($size)")
//                                            }
//                                        } else {
//                                            Timber.tag("itch").w("[Cloud Saves] [$preferredAction] Directory '${location.name}' is empty at: ${location.location}")
//                                        }
//                                    } else {
//                                        Timber.tag("itch").w("[Cloud Saves] [$preferredAction] Directory not found: ${location.location}")
//                                    }
//                                } catch (e: Exception) {
//                                    Timber.tag("itch").e(e, "[Cloud Saves] Failed to list files in directory: ${location.location}")
//                                }
//
//                                Timber.tag("itch").i("[Cloud Saves] Successfully synced save location '${location.name}' for game $gameId")
//                            } else {
//                                Timber.tag("itch").e("[Cloud Saves] Failed to sync save location '${location.name}' for game $gameId (timestamp: $newTimestamp)")
//                                allSucceeded = false
//                            }
//                        } catch (e: CancellationException) {
//                            throw e
//                        } catch (e: Exception) {
//                            Timber.tag("itch").e(e, "[Cloud Saves] Exception syncing save location '${location.name}' for game $gameId")
//                            allSucceeded = false
//                        }
//                    }
//
//                    if (allSucceeded) {
//                        Timber.tag("itch").i("[Cloud Saves] All save locations synced successfully for $appId")
//                        return@withContext true
//                    } else {
//                        Timber.tag("itch").w("[Cloud Saves] Some save locations failed to sync for $appId")
//                        return@withContext false
//                    }
//                } finally {
//                    // Always end the sync, even if an exception occurred
//                    getInstance()?.itchManager?.endSync(appId)
//                    Timber.tag("itch").d("[Cloud Saves] Sync completed and lock released for $appId")
//                }
//            } catch (e: CancellationException) {
//                throw e
//            } catch (e: Exception) {
//                Timber.tag("itch").e(e, "[Cloud Saves] Failed to sync cloud saves for App ID: $appId")
//                return@withContext false
//            }
//        }
    }

    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var itchManager: ItchManager

    @Inject
    lateinit var itchDownloadManager: ItchDownloadManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    // ItchManager is injected by Hilt
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.emit(AndroidEvent.ServiceReady)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[ItchService] onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_ITCH, "Connected")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_ITCH, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_ITCH, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_ITCH)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.i("[ItchService] Manual sync requested - bypassing throttle")
                true
            }

            ACTION_SYNC_LIBRARY -> {
                Timber.i("[ItchService] Automatic sync requested")
                true
            }

            null -> {
                // Service restarted by Android with null intent (START_STICKY behavior)
                // Only sync if we haven't done initial sync yet, or if it's been a while
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                if (shouldResync) {
                    Timber.i("[ItchService] Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)")
                    true
                } else {
                    Timber.d("[ItchService] Service restarted by Android - skipping sync (throttled)")
                    false
                }
            }

            else -> {
                // Service started without sync action (e.g., just to keep it alive)
                Timber.d("[ItchService] Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.i("[ItchService] Starting background library sync")
            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.d("[ItchService]: Starting background library sync")

                    val syncResult = itchManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("[ItchService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.i("[ItchService]: Background library sync completed successfully")
                        // Update last sync timestamp on successful sync
                        lastSyncTimestamp = System.currentTimeMillis()
                        // Mark that initial sync has been performed
                        hasPerformedInitialSync = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[ItchService]: Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.d("[ItchService] Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("[ItchService] Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_ITCH)
        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.tag("itch").i("Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.tag("itch").i("Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
