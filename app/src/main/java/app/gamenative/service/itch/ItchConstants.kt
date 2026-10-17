package app.gamenative.service.itch

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import java.io.File
import java.nio.file.Paths
import java.security.SecureRandom
import timber.log.Timber

/**
 * Constants for Itch Games Store integration
 */
object ItchConstants {
    /**
     * Internal Itch games installation path (similar to Steam's internal path)
     * {context.dataDir}/Itch/games/
     */
    fun internalItchGamesPath(context: Context): String {
        val path = Paths.get(context.dataDir.path, "Itch", "games").toString()
        File(path).mkdirs()
        return path
    }

    /**
     * External Itch games installation path
     * {externalStoragePath}/Itch/games/
     */
    fun externalItchGamesPath(): String {
        val path = Paths.get(PrefManager.externalStoragePath, "Itch", "games").toString()
        // Ensure directory exists for StatFs
        File(path).mkdirs()
        return path
    }

//
//    /**
//     * Default Itch games installation path - uses external storage if available
//     */
    fun defaultItchGamesPath(context: Context): String {
        return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
            val path = externalItchGamesPath()
            Timber.i("Itch using external storage: $path")
            path
        } else {
            val path = internalItchGamesPath(context)
            Timber.i("Itch using internal storage: $path")
            // Ensure directory exists for StatFs
            File(path).mkdirs()
            path
        }
    }
//
//    /**
//     * Get the installation path for a specific Itch game
//     * Sanitizes the game title to be filesystem-safe
//     */
    fun getGameInstallPath(context: Context, gameTitle: String): String {
        // Sanitize game title for filesystem
        val sanitizedTitle = gameTitle.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
        return Paths.get(defaultItchGamesPath(context), sanitizedTitle).toString()
    }
}
