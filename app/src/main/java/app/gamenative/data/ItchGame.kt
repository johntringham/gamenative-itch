package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.gamenative.enums.AppType

/**
 * Itch Game entity for Room database
 * Represents a game from the Itch platform
 */
@Entity(tableName = "itch_games")
data class ItchGame(
    @PrimaryKey
    @ColumnInfo("id")
    val id: Long,

    @ColumnInfo("title")
    val title: String = "",

    @ColumnInfo("download_size")
    val downloadSize: Long = 0,

    @ColumnInfo("install_size")
    val installSize: Long = 0,

    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",

    @ColumnInfo("image_url")
    val imageUrl: String = "",

    @ColumnInfo("icon_url")
    val iconUrl: String = "",

    @ColumnInfo("description")
    val description: String = "",

    @ColumnInfo("release_date")
    val releaseDate: String = "",

    @ColumnInfo("developer")
    val developer: String = "",

    @ColumnInfo("last_played")
    val lastPlayed: Long = 0,

    @ColumnInfo("play_time")
    val playTime: Long = 0,

    @ColumnInfo("type")
    val type: AppType = AppType.game,

    @ColumnInfo(name = "exclude", defaultValue = "0")
    val exclude: Boolean = false,
)

//data class ItchDownloadInfo(
//    val gameId: String,
//    val totalSize: Long,
//    val downloadedSize: Long = 0,
//    val progress: Float = 0f,
//    val isActive: Boolean = false,
//    val isPaused: Boolean = false,
//    val error: String? = null,
//)
