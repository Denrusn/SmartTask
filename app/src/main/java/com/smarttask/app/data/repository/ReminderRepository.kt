package com.smarttask.app.data.repository

import com.smarttask.app.data.local.ReminderDao
import com.smarttask.app.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

class ReminderRepository(private val reminderDao: ReminderDao) {

    fun getAllReminders(): Flow<List<Reminder>> = reminderDao.getAllReminders()

    suspend fun getReminderById(id: Long): Reminder? = reminderDao.getReminderById(id)

    suspend fun getEnabledReminders(): List<Reminder> = reminderDao.getEnabledReminders()

    suspend fun insertReminder(reminder: Reminder): Long = reminderDao.insertReminder(reminder)

    suspend fun updateReminder(reminder: Reminder) = reminderDao.updateReminder(reminder)

    suspend fun deleteReminder(reminder: Reminder) = reminderDao.deleteReminder(reminder)

    suspend fun deleteReminderById(id: Long) = reminderDao.deleteReminderById(id)
}
