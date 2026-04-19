package com.smarttask.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.smarttask.app.domain.model.ExecutionLog
import com.smarttask.app.domain.model.Reminder

@Database(
    entities = [Reminder::class, ExecutionLog::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SmartTaskDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun executionLogDao(): ExecutionLogDao

    companion object {
        @Volatile
        private var INSTANCE: SmartTaskDatabase? = null

        fun getDatabase(context: Context): SmartTaskDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SmartTaskDatabase::class.java,
                    "smarttask_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
