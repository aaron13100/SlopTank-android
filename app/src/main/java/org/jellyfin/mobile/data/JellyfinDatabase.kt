package org.jellyfin.mobile.data

import android.net.Uri
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RenameTable
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.DownloadFileDao
import org.jellyfin.mobile.data.dao.ProgressDao
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.OfflineProgressEntity
import org.jellyfin.mobile.data.entity.ProgressSequenceEntity
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

@Database(
    entities = [
        ServerEntity::class,
        UserEntity::class,
        DownloadEntity::class,
        DownloadFileEntity::class,
        OfflineProgressEntity::class,
        ProgressSequenceEntity::class,
    ],
    version = 7,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = JellyfinDatabase.MigrateV4::class),
        AutoMigration(from = 4, to = 5, spec = JellyfinDatabase.MigrateV5::class),
        AutoMigration(from = 6, to = 7, spec = JellyfinDatabase.MigrateV7::class),
    ],
)
@TypeConverters(JellyfinDatabase.Converters::class)
abstract class JellyfinDatabase : RoomDatabase() {
    abstract val serverDao: ServerDao
    abstract val userDao: UserDao
    abstract val downloadDao: DownloadDao
    abstract val downloadFileDao: DownloadFileDao
    abstract val progressDao: ProgressDao

    // Converters

    class Converters {
        @TypeConverter
        fun fromUuid(uuid: UUID?): String? = uuid?.toString()

        @TypeConverter
        fun toUuid(value: String?): UUID? = value?.toUUIDOrNull()

        @TypeConverter
        fun fromBaseItemDto(baseItem: BaseItemDto?): String? = baseItem?.let(Json::encodeToString)

        @TypeConverter
        fun toBaseItemDto(json: String?): BaseItemDto? = json?.let(Json::decodeFromString)

        @TypeConverter
        fun fromUri(uri: Uri?): String? = uri?.toString()

        @TypeConverter
        fun toUri(value: String?): Uri? = value?.let { Uri.parse(it) }
    }

    // Migrations

    @RenameTable(fromTableName = "Server", toTableName = "server")
    @RenameTable(fromTableName = "User", toTableName = "user")
    class MigrateV4 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("SELECT user_id FROM user")
            while (cursor.moveToNext()) {
                val oldValue = cursor.getString(0)
                val newValue = oldValue.toUUIDOrNull()?.toString()

                if (newValue == null) {
                    Timber.i("Deleting user $oldValue due to invalid userId")
                    db.execSQL("DELETE FROM user WHERE user_id = ?", arrayOf(oldValue))
                } else {
                    Timber.i("Updating user $oldValue to userId $newValue")
                    db.execSQL("UPDATE user SET user_id = ? WHERE user_id = ?", arrayOf(newValue, oldValue))
                }
            }

            cursor.close()
        }
    }

    @DeleteTable(tableName = "Download")
    class MigrateV5 : AutoMigrationSpec

    /** Version 7 adds the offline progress journal; every existing download gets an empty entry. */
    class MigrateV7 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO `offline_progress` (`download_id`, `server_id`, `user_id`, `item_id`, " +
                    "`local_position_ticks`, `local_played`, `local_completions`, `dirty`, `state`) " +
                    "SELECT `id`, `server_id`, `user_id`, `item_id`, 0, 0, 0, 0, 'CLEAN' FROM `download`",
            )
        }
    }

    companion object {
        /**
         * Version 6: download progress, validators and error reasons, one download per server, user and item,
         * and app-controlled storage. Downloads that were stored in a user-picked folder (SAF) are paused and
         * must be resumed to be stored again in the app's own storage; the old files are left untouched.
         */
        private const val SCHEMA_V5 = 5
        private const val SCHEMA_V6 = 6

        val MIGRATION_5_6 = object : Migration(SCHEMA_V5, SCHEMA_V6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download` ADD COLUMN `error_reason` TEXT")
                db.execSQL(
                    "DELETE FROM `download` WHERE `id` NOT IN " +
                        "(SELECT MIN(`id`) FROM `download` GROUP BY `server_id`, `user_id`, `item_id`)",
                )
                db.execSQL("DELETE FROM `download_file` WHERE `download_id` NOT IN (SELECT `id` FROM `download`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_server_id_user_id_item_id` " +
                        "ON `download` (`server_id`, `user_id`, `item_id`)",
                )
                db.execSQL("ALTER TABLE `download_file` ADD COLUMN `bytes_downloaded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `download_file` ADD COLUMN `total_bytes` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `download_file` ADD COLUMN `validator` TEXT")
                db.execSQL("ALTER TABLE `download_file` ADD COLUMN `stream_index` INTEGER")
                db.execSQL("ALTER TABLE `download_file` ADD COLUMN `local_path` TEXT")
                db.execSQL(
                    "UPDATE `download` SET `status` = 'PAUSED', `error_reason` = NULL WHERE `status` != 'CANCELLED'"
                )
                db.execSQL("UPDATE `download_file` SET `status` = 'QUEUED', `size` = 0")
            }
        }
    }
}
