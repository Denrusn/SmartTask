package com.smarttask.app.data.repository

import com.smarttask.app.data.local.ExecutionLogDao
import com.smarttask.app.domain.model.ExecutionLog
import kotlinx.coroutines.flow.Flow

class ExecutionLogRepository(private val executionLogDao: ExecutionLogDao) {

    fun getAllLogs(): Flow<List<ExecutionLog>> = executionLogDao.getAllLogs()

    fun getLogsByReminderId(reminderId: Long): Flow<List<ExecutionLog>> =
        executionLogDao.getLogsByReminderId(reminderId)

    suspend fun insertLog(log: ExecutionLog): Long = executionLogDao.insertLog(log)

    suspend fun clearAllLogs() = executionLogDao.clearAllLogs()
}
