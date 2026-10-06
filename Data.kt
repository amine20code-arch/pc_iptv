package com.streamtv.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

const val EPISODE_ID_BASE = 10_000_000_000L

@Entity(tableName = "profiles")
data class ProfileEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val pin: String = "", val isKids: Boolean = false)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val type: String, // m3u | xtream | stalker
    val url: String, val username: String = "", val password: String = "", val epgUrl: String = "",
    @ColumnInfo(defaultValue = "1") val enabled: Int = 1
)

/** A saved access code (Xtream / Stalker / M3U link) with the result of its last test. */
@Entity(tableName = "codes")
data class CodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, val name: String, val host: String, val user: String = "", val pass: String = "",
    val mac: String = "", val opts: String = "", val expiry: Long = 0L,
    val channels: Int = 0, val movies: Int = 0, val series: Int = 0,
    val status: String = "?", val testedAt: Long = 0L, val note: String = "", val playlistId: Long = 0L
)

/** kind: live | movie | series | radio.  For movies `ext` holds the Xtream vod id (used for trailers). */
@Entity(tableName = "channels", indices = [Index("kind", "groupTitle"), Index("playlistId"), Index("name")])
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long, val kind: String, val name: String, val logo: String = "",
    val groupTitle: String = "", val streamUrl: String, val tvgId: String = "",
    val rating: String = "", val plot: String = "", val ext: String = ""
)

@Entity(tableName = "history", primaryKeys = ["profileId", "itemId"])
data class HistoryEntity(
    val profileId: Long, val itemId: Long, val playlistId: Long, val kind: String, val name: String,
    val logo: String, val url: String, val tvgId: String, val position: Long, val duration: Long, val updated: Long
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "itemId"])
data class FavEntity(val profileId: Long, val itemId: Long)

@Entity(tableName = "epg", indices = [Index("channelTvg", "start")])
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val playlistId: Long,
    val channelTvg: String, val start: Long, val stop: Long, val title: String, val descr: String = ""
)

data class GroupCount(val groupTitle: String, val c: Int)

@Dao
interface AppDao {
    @Insert suspend fun insertItems(l: List<ChannelEntity>)
    @Insert suspend fun insertPlaylist(p: PlaylistEntity): Long
    @Query("SELECT * FROM playlists WHERE id=:id") suspend fun playlist(id: Long): PlaylistEntity?
    @Query("UPDATE playlists SET enabled=:v WHERE id=:id") suspend fun setEnabled(id: Long, v: Int)
    @Query("UPDATE playlists SET enabled=0 WHERE id!=:id") suspend fun disableOthers(id: Long)
    @Query("UPDATE playlists SET enabled=0") suspend fun disableAll()
    @Query("SELECT * FROM codes ORDER BY channels DESC, id") fun codes(): Flow<List<CodeEntity>>
    @Query("SELECT * FROM codes WHERE id=:id") suspend fun code(id: Long): CodeEntity?
    @Query("SELECT * FROM codes") suspend fun codesOnce(): List<CodeEntity>
    @Insert suspend fun insertCode(c: CodeEntity): Long
    @Update suspend fun updateCode(c: CodeEntity)
    @Query("DELETE FROM codes WHERE id=:id") suspend fun deleteCode(id: Long)
    @Query("UPDATE codes SET playlistId=0 WHERE playlistId=:pid") suspend fun unlinkCodes(pid: Long)
    @Query("SELECT * FROM playlists") fun playlists(): Flow<List<PlaylistEntity>>
    @Query("DELETE FROM channels WHERE playlistId=:id") suspend fun deleteItems(id: Long)
    @Query("DELETE FROM epg WHERE playlistId=:id") suspend fun deleteEpg(id: Long)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id: Long)

    @Query("SELECT groupTitle, SUM(CASE WHEN kind=:k THEN 1 ELSE 0 END) AS c FROM channels WHERE (kind=:k OR kind=:ck) AND playlistId IN (SELECT id FROM playlists WHERE enabled=1) GROUP BY groupTitle ORDER BY MIN(id)") fun groups(k: String, ck: String): Flow<List<GroupCount>>
    @Query("SELECT * FROM channels WHERE kind=:k AND groupTitle=:g") suspend fun placeholders(k: String, g: String): List<ChannelEntity>
    @Query("UPDATE channels SET ext=:ext WHERE id=:id") suspend fun setExt(id: Long, ext: String)
    @Query("SELECT * FROM channels WHERE kind='episode' AND playlistId=:p AND groupTitle=:g ORDER BY rating, ext") suspend fun localEpisodes(p: Long, g: String): List<ChannelEntity>
    @Query("SELECT COUNT(*) FROM channels WHERE kind=:k AND groupTitle=:g") suspend fun countIn(k: String, g: String): Int
    @Query("SELECT * FROM channels WHERE kind=:k AND groupTitle=:g AND playlistId IN (SELECT id FROM playlists WHERE enabled=1) LIMIT :n") fun byGroup(k: String, g: String, n: Int): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE kind=:k AND logo!='' AND playlistId IN (SELECT id FROM playlists WHERE enabled=1) ORDER BY RANDOM() LIMIT :n") suspend fun featured(k: String, n: Int): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE kind IN ('live','movie','series','radio') AND playlistId IN (SELECT id FROM playlists WHERE enabled=1) AND name LIKE '%' || :q || '%' LIMIT :n") suspend fun search(q: String, n: Int): List<ChannelEntity>
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId IN (SELECT id FROM playlists WHERE enabled=1)") fun count(): Flow<Int>

    @Query("SELECT * FROM history WHERE profileId=:p ORDER BY updated DESC LIMIT 30") fun history(p: Long): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM history WHERE profileId=:p AND kind=:k ORDER BY updated DESC LIMIT 100") fun historyOf(p: Long, k: String): Flow<List<HistoryEntity>>
    @Query("DELETE FROM history WHERE profileId=:p") suspend fun clearHistory(p: Long)
    @Query("DELETE FROM favorites WHERE profileId=:p") suspend fun clearFavs(p: Long)
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.itemId=c.id WHERE f.profileId=:p AND c.kind=:k AND c.playlistId IN (SELECT id FROM playlists WHERE enabled=1)") fun favoritesOf(p: Long, k: String): Flow<List<ChannelEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHistory(h: HistoryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addFav(f: FavEntity)
    @Query("DELETE FROM favorites WHERE profileId=:p AND itemId=:i") suspend fun removeFav(p: Long, i: Long)
    @Query("SELECT COUNT(*) FROM favorites WHERE profileId=:p AND itemId=:i") suspend fun isFav(p: Long, i: Long): Int
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.itemId=c.id WHERE f.profileId=:p AND c.playlistId IN (SELECT id FROM playlists WHERE enabled=1)") fun favorites(p: Long): Flow<List<ChannelEntity>>

    @Insert suspend fun insertEpg(l: List<EpgEntity>)
    @Query("SELECT * FROM epg WHERE channelTvg=:t AND stop>:now ORDER BY start LIMIT 2") suspend fun nowNext(t: String, now: Long): List<EpgEntity>
    @Query("SELECT * FROM epg WHERE channelTvg=:t AND stop<=:now ORDER BY stop DESC LIMIT 1") suspend fun previous(t: String, now: Long): EpgEntity?

    @Query("SELECT * FROM profiles") fun profiles(): Flow<List<ProfileEntity>>
    @Insert suspend fun insertProfile(p: ProfileEntity): Long
    @Query("DELETE FROM profiles WHERE id=:id") suspend fun deleteProfile(id: Long)
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId=:p AND kind IN ('live','movie','series','radio')") suspend fun countItems(p: Long): Int
    @Query("SELECT COUNT(*) FROM profiles") suspend fun profileCount(): Int
}

@Database(entities = [ProfileEntity::class, PlaylistEntity::class, ChannelEntity::class, HistoryEntity::class, FavEntity::class, EpgEntity::class, CodeEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS codes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, host TEXT NOT NULL, user TEXT NOT NULL, pass TEXT NOT NULL, mac TEXT NOT NULL, opts TEXT NOT NULL, expiry INTEGER NOT NULL, channels INTEGER NOT NULL, movies INTEGER NOT NULL, series INTEGER NOT NULL, status TEXT NOT NULL, testedAt INTEGER NOT NULL, note TEXT NOT NULL, playlistId INTEGER NOT NULL)")
            }
        }
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) { inst ?: build(c.applicationContext).also { inst = it } }

        // SQLCipher-encrypted database; the passphrase lives in Keystore-backed EncryptedSharedPreferences.
        private fun build(c: Context): AppDb =
            Room.databaseBuilder(c, AppDb::class.java, "streamtv_enc.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase(c)))
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration().build()

        private fun passphrase(c: Context): ByteArray {
            val sp: SharedPreferences = try {
                val mk = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(c, "sec", mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            } catch (e: Exception) { c.getSharedPreferences("sec_fallback", Context.MODE_PRIVATE) }
            var k = sp.getString("dbkey", null)
            if (k == null) {
                val b = ByteArray(32); SecureRandom().nextBytes(b)
                k = b.joinToString("") { "%02x".format(it) }
                sp.edit().putString("dbkey", k).apply()
            }
            return k.toByteArray()
        }
    }
}
