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
    ],
    version = 2,
    exportSchema = true,
)
abstract class OmnDatabase : RoomDatabase() {
    abstract fun omnDao(): OmnDao

    companion object {
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
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { database -> instance = database }
        }
    }
}
