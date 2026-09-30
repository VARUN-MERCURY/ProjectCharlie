package com.alpha.charlie.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Fact::class], version = 1, exportSchema = false)
abstract class FactDatabase : RoomDatabase() {

    abstract fun factDao(): FactDao

    companion object {
        @Volatile
        private var INSTANCE: FactDatabase? = null

        fun getDatabase(context: Context): FactDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    FactDatabase::class.java,
                    "charlie_memory.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
