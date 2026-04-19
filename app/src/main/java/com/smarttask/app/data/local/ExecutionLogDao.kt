package com.smarttask.app.data.local

import androidx.room.*
import com.smarttask.app.domain.model.ExecutionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs ORDER BY triggeredAt DESC")
    fun getAllLogs(): Flow<List<ExecutionLog>>

    @Query("SELECT * FROM execution_logs WHERE reminderId = :reminderId ORDER BY triggeredAt DESC")
    fun getLogsByReminderId(reminderId: Long): Flow<List<ExecutionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLog): Long

    @Query("DELETE FROM execution_logs")
    suspend fun clearAllLogs()
}
