package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.ItchGame
import app.gamenative.enums.Marker
import app.gamenative.utils.FileUtils.calculateDirectorySize
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private val httpClient = Net.http


@Singleton
class ItchDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itchManager: ItchManager
) {
//    suspend fun downloadGame(
//        game: ItchGame,
//        upload: Upload,
//        apiKey: String,
//        file: File
//    ) : Result<DownloadInfo> {
//        try {
//            Timber.tag("itch");
//
//            Timber.i("Starting download for ${game.title}")
//
//            val downloadRequest = ItchApiClient.getDownloadUrl(upload.id, game.downloadKey, apiKey);
//            val downloadResponse = withContext(Dispatchers.IO){
//                httpClient.newCall(downloadRequest).execute()
//            }.use { response ->
//
//                if(response.isSuccessful == false){
//                    Timber.i("Download failed for ${game.title}")
//                    return Result.failure(Exception("Failed to download"));
//                }
//
//                Timber.i("Successful download for ${game.title}")
//
//                val downloadStream = response.body.byteStream()
//                var fileOutputStream = FileOutputStream(file);
//                downloadStream.copyTo(fileOutputStream);
//                fileOutputStream.flush()
//                fileOutputStream.close()
//
//                Timber.i("Download ${game.title} written to ${file.toURI()}")
//
//                return Result.success(DownloadInfo(1,game.id.toInt(), CopyOnWriteArrayList()));
//            }
//
//        } catch (e: Exception) {
//            return Result.failure(Exception("Failed to download - ${e.message}"));
//        }
//    }

    suspend fun downloadGame(
        game: ItchGame,
        upload: Upload,
        apiKey: String,
        installFolder: File,
        downloadInfo : DownloadInfo
    ): Result<Unit> {
        try {
            val file = downloadGameUpload(game, upload, apiKey, installFolder, downloadInfo).getOrThrow();
            unarchiveFile(file);
            finalizeInstallSuccess(game.id, installFolder, downloadInfo);
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e);
        }
    }

    private fun unarchiveFile(file: File) {
//        file.listFiles().forEach {


            val extension = file.extension;
        Timber.tag("itch").i("Unarchiving $file");

            when (extension) {
                "zip" -> {
                    extractZipContents(file, file.parentFile ?: file);
                }

                else -> {}
            }

//        }
    }

    private fun extractZipContents(file: File, dest : File) {
        Timber.tag("itch").i("Unzipping $file");

        ZipFile(file.path).use {
            zip ->
            zip.entries().asSequence().forEach { entry ->
                Timber.tag("itch").i("Unzipping zip entry $entry");

                val inputStream = zip.getInputStream(entry);
                inputStream.use { stream ->
                    val path = dest.path + File.separator + entry.name
                    val entryFile = File( path);
                    entryFile.parentFile?.mkdirs()
                    if(!entry.isDirectory) {

//                    if(entry.isDirectory){
//                        Timber.tag("itch").i("Creating zipped directory $entry");
//                    } else {
                        writeExtractedEntryToFile(inputStream, entryFile);
//                    }
                    }
                }
            }
        }
    }

    private fun writeExtractedEntryToFile(inputStream: InputStream, entryFile: File) {
        if(entryFile.exists() && entryFile.isDirectory)
            return;

        val outputStream = BufferedOutputStream(FileOutputStream(entryFile));
        val bufferArray = ByteArray(4096);
        var readBytes = 0;
        while(inputStream.read(bufferArray).also { readBytes = it } != -1){
            outputStream.write(bufferArray, 0, readBytes);
        }

        outputStream.close()
    }

    fun downloadGameUpload(
        game: ItchGame,
        upload: Upload,
        apiKey: String,
        folder: File,
        downloadInfo : DownloadInfo
    ): Result<File> {
        //val file = f.file
        //val outFile = File(baseDir, file.path)
        //outFile.parentFile?.mkdirs()

//        if (file.size == 0L) {
//            outFile.createNewFile()
//            return Result.success(Unit)
//        }

        downloadInfo.updateStatusMessage("Fetching builds...");

        val downloadRequest = ItchApiClient.getDownloadUrl(upload.id, game.downloadKey, apiKey);

            //?: return Result.failure(Exception("No main.bin URL for product ${f.productId}"))

//        val offset = 0;//file.offset
//        if (offset == null) {
//            return Result.failure(Exception("Gen 1 file ${file.path} has no offset (main.bin range request required)"))
//        }

//        val rangeHeader = "bytes=$offset-${offset + file.size - 1}"
//        val request = Request.Builder()
//            .url(mainBinUrl)
//            .header("User-Agent", "GOG Galaxy")
//            .header("Range", rangeHeader)
//            .build()

        val mkDirs = folder.mkdirs();
        val exists = folder.exists()
        val isDirectory = folder.isDirectory();
        var file = File(folder.path + File.separator + upload.filename);
        val fileExists = file.exists()

        val fileCreated = file.createNewFile();

        if(!mkDirs || !fileCreated || !exists || !isDirectory || !fileExists)
        {
            Timber.tag("itch").i("wtf");
        }
//        folder.mkdirs();

        downloadInfo.updateStatusMessage("Starting download...");
        downloadInfo.setTotalExpectedBytes(upload.size);

        return try {
            Net.http.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code} for ${file.path}"))
                val body = response.body ?: return Result.failure(Exception("Empty response"))
                val md = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(256 * 1024) // 256KB
                val progressInterval = 512L * 1024 // emit progress every 512KB
                var copiedInFile = 0L

                downloadInfo.updateStatusMessage("Downloading...");
                downloadInfo.emitProgressChange();

                val fileOutputStream = FileOutputStream(file);
                BufferedOutputStream(fileOutputStream)
                .use { out ->
                    body.byteStream().use { input ->
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            if (!downloadInfo.isActive()) {
                                folder.delete()
                                return Result.failure(Exception("Download cancelled"))
                            }
                            out.write(buffer, 0, n)
                            copiedInFile += n
                            downloadInfo.updateBytesDownloaded(n.toLong())
                            if (copiedInFile >= progressInterval || downloadInfo.getBytesDownloaded() >= upload.size) {
                                copiedInFile = 0L
                                Timber.tag("Itch").i("Download at ${downloadInfo.getBytesDownloaded()} of ${upload.size}");
                                downloadInfo.setProgress(
                                    (downloadInfo.getBytesDownloaded().toFloat() / upload.size).coerceIn(0f, 1f)
                                )
                                downloadInfo.emitProgressChange()
                            }
                        }
                    }
                }
                val bytesWritten = file.length()
                if (bytesWritten != upload.size) return Result.failure(Exception("Size mismatch ${file.path}"))
                val md5 = md.digest().joinToString("") { "%02x".format(it) }
//                if (file.hash.isNotEmpty() && md5 != file.hash) return Result.failure(Exception("MD5 mismatch ${file.path}"))
                // bytes already reported during copy; ensure final progress is exact
                downloadInfo.setProgress(
                    (downloadInfo.getBytesDownloaded().toFloat() / upload.size).coerceIn(0f, 1f)
                )
                downloadInfo.emitProgressChange()
                Result.success(file)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Shared finalization after a successful install: update DB, set download complete, emit events.
     * Used by both Gen 2 and Gen 1 success paths.
     */
    private suspend fun finalizeInstallSuccess(gameId: Long, installPath: File, downloadInfo: DownloadInfo) {
        downloadInfo.updateStatusMessage("Updating database...")
        try {
            val game = itchManager.getGameFromDbById(gameId)
            if (game != null) {
                val installSize = calculateDirectorySize(installPath)
                itchManager.updateGame(game.copy(isInstalled = true, installPath = installPath.absolutePath, installSize = installSize))
                downloadInfo.clearPersistedBytesDownloaded(installPath.absolutePath)
                Timber.tag("itch").i("Updated database: game marked as installed, size: ${installSize / 1_000_000} MB")
            } else {
                Timber.tag("itch").w("Game $gameId not found in database, skipping DB update")
            }
        } catch (e: Exception) {
            Timber.tag("itch").e(e, "Failed to update database for game $gameId")
        }
        downloadInfo.updateStatusMessage("Complete")
        downloadInfo.setProgress(1.0f)
        downloadInfo.setActive(false)
        downloadInfo.emitProgressChange()
        MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)
        app.gamenative.PluviaApp.events.emitJava(
            app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toInt(), false),
        )
        app.gamenative.PluviaApp.events.emitJava(
            app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId.toInt(), app.gamenative.data.GameSource.ITCH),
        )
    }

    private sealed class DownloadState {
        data class Downloading(val progress: Int) : DownloadState()
        object Finished : DownloadState()
        object Failed : DownloadState()
    }
}
