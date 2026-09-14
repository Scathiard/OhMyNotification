package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationItemEntity::class,
        HealthEvidenceEntity::class,
        ExcludedSourceEntity::class,
        ManualArchiveEntity::class,
        NotificationRevisionEntity::class,
        NotificationTargetEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class OmnDatabase : RoomDatabase() {
    abstract fun omnDao(): OmnDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notification_items` ADD COLUMN `lastListenerConnectionId` TEXT")
                db.execSQL("ALTER TABLE `notification_items` ADD COLUMN `lastObservedPostTimeEpochMillis` INTEGER")
                db.execSQL("ALTER TABLE `notification_items` ADD COLUMN `identityUncertain` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `notification_revisions` (`revisionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, `recordedAtEpochMillis` INTEGER NOT NULL, `title` TEXT, `body` TEXT, `originalPostTimeEpochMillis` INTEGER, `contentFingerprint` TEXT, `reason` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_revisions_itemId_revisionId` ON `notification_revisions` (`itemId`, `revisionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `notification_targets` (`itemId` INTEGER NOT NULL, `sourcePackage` TEXT NOT NULL, `uri` TEXT NOT NULL, `provenance` TEXT NOT NULL, `savedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `manual_archives` (`itemId` INTEGER NOT NULL, `archivedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
            }
        }
        private const val DATABASE_NAME = "oh-my-notification.db"

        @Volatile
        private var instance: OmnDatabase? = null

        fun get(context: Context): OmnDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OmnDatabase::class.java,
                DATABASE_NAME,
            ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { database -> instance = database }
        }
    }
}
