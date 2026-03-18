package social.bony.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [EventEntity::class, ProfileEntity::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun profileDao(): ProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` " +
                        "(`pubkey` TEXT NOT NULL, `contentJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`pubkey`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN accountPubkey TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
