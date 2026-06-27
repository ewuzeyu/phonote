package site.wuzeyu.phonote

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NoteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "phonote_database"
                    ).build()
                } catch (e: Exception) {
                    // If database is corrupted (e.g., package name changed), recreate
                    context.deleteDatabase("phonote_database")
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "phonote_database"
                    ).build()
                }
                INSTANCE = instance
                instance
            }
        }
    }
}
