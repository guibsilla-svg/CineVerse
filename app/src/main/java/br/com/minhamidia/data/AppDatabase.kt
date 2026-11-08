package br.com.minhamidia.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MediaItem::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN seasonProgress TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN tmdbVoteAverage REAL NOT NULL DEFAULT 0.0")
            }
        }
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN imdbRating REAL NOT NULL DEFAULT 0.0")
            }
        }
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN personalRating INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media ADD COLUMN franchiseId INTEGER")
                db.execSQL("ALTER TABLE media ADD COLUMN franchiseName TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "minha-midia.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
    }
}