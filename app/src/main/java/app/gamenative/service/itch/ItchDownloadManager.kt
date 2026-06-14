package app.gamenative.service.itch

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ItchDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun downloadGame(
        gameId: String,
        file: java.io.File,
        downloadInfo: app.gamenative.data.DownloadInfo,
        containerLanguage: String,
        bool: Boolean,
        commonRedistDir: java.io.File,
    ) : Result<Unit> {
        return Result.failure(Exception("not implemented yet"))
    }
}
