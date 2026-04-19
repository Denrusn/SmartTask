package com.smarttask.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "execution_logs")
data class ExecutionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val reminderId: Long,
    val reminderTitle: String,
    val triggeredAt: Long,
    val actionType: ActionType,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)
