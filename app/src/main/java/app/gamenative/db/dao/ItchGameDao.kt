package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.ItchGame
import kotlinx.coroutines.flow.Flow

/**
 * DAO for itch games in the Room database
 */
@Dao
interface ItchGameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: ItchGame)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<ItchGame>)

    @Update
    suspend fun update(game: ItchGame)

    @Delete
    suspend fun delete(game: ItchGame)

    @Query("DELETE FROM itch_games WHERE id = :gameId")
    suspend fun deleteById(gameId: Long)

    @Query("SELECT * FROM itch_games WHERE id = :gameId")
    suspend fun getById(gameId: Long): ItchGame?

    @Query("SELECT * FROM itch_games WHERE exclude = 0 ORDER BY title ASC")
    fun getAll(): Flow<List<ItchGame>>

    @Query("SELECT * FROM itch_games WHERE exclude = 0 ORDER BY title ASC")
    suspend fun getAllAsList(): List<ItchGame>

    @Query("SELECT * FROM itch_games WHERE is_installed = :isInstalled AND exclude = 0 ORDER BY title ASC")
    fun getByInstallStatus(isInstalled: Boolean): Flow<List<ItchGame>>

    /** Returns all installed Itch games, excluding excluded entries, sorted by title. */
    @Query("SELECT * FROM itch_games WHERE is_installed = 1 AND exclude = 0 ORDER BY title ASC")
    suspend fun getInstalledGames(): List<ItchGame>

    @Query("SELECT * FROM itch_games WHERE is_installed = 0 AND exclude = 0")
    suspend fun getNonInstalledGames(): List<ItchGame>

    @Query("SELECT * FROM itch_games WHERE exclude = 0 AND title LIKE '%' || :searchQuery || '%' ORDER BY title ASC")
    fun searchByTitle(searchQuery: String): Flow<List<ItchGame>>

    @Query("DELETE FROM itch_games WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()

    @Query("SELECT COUNT(*) FROM itch_games WHERE exclude = 0")
    fun getCount(): Flow<Int>

    @Query("SELECT id FROM itch_games")
    suspend fun getAllGameIdsIncludingExcluded(): List<Long>

    /**
     * Upsert Itch games while preserving install status and paths
     * This is useful when refreshing the library from Itch API
     */
    @Transaction
    suspend fun upsertPreservingInstallStatus(games: List<ItchGame>) {
        games.forEach { newGame ->
            val existingGame = getById(newGame.id)
            if (existingGame != null) {
                // Preserve installation status, path, and size from existing game
                val gameToInsert = newGame.copy(
                    isInstalled = existingGame.isInstalled,
                    installPath = existingGame.installPath,
                    installSize = existingGame.installSize,
                    lastPlayed = existingGame.lastPlayed,
                    playTime = existingGame.playTime,
                )
                insert(gameToInsert)
            } else {
                // New game, insert as-is
                insert(newGame)
            }
        }
    }
}
