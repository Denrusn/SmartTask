package com.smarttask.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.room.Room
import com.smarttask.app.data.local.SmartTaskDatabase
import com.smarttask.app.domain.model.Reminder
import com.smarttask.app.domain.model.TriggerType
import com.smarttask.app.receiver.AlarmReceiver
import kotlinx.coroutines.*
import java.util.*

class ReminderService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var database: SmartTaskDatabase
    private lateinit var alarmManager: AlarmManager

    override fun onCreate() {
        super.onCreate()
        database = SmartTaskDatabase.getDatabase(this)
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.smarttask.RESCHEDULE_ALL" -> rescheduleAllReminders()
            "com.smarttask.SCHEDULE" -> {
                val reminderId = intent.getLongExtra("reminderId", -1)
                if (reminderId != -1L) {
                    serviceScope.launch {
                        val reminder = database.reminderDao().getReminderById(reminderId)
                        reminder?.let {
                            val success = scheduleReminder(it)
                            if (!success) {
                                // 时间已过，标记提醒为禁用
                                val updated = it.copy(isEnabled = false)
                                database.reminderDao().updateReminder(updated)
                            }
                        }
                    }
                }
            }
            "com.smarttask.CANCEL" -> {
                val reminderId = intent.getLongExtra("reminderId", -1)
                if (reminderId != -1L) {
                    cancelReminder(reminderId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun rescheduleAllReminders() {
        serviceScope.launch {
            val reminders = database.reminderDao().getEnabledReminders()
            reminders.forEach { reminder ->
                scheduleReminder(reminder)
            }
        }
    }

    fun scheduleReminder(reminder: Reminder): Boolean {
        val triggerTime = calculateNextTriggerTime(reminder)
        val now = System.currentTimeMillis()

        if (triggerTime <= now) {
            android.util.Log.w("ReminderService", "时间已过: triggerTime=$triggerTime, now=$now")
            return false
        }

        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.smarttask.ALARM_TRIGGER"
            putExtra("title", reminder.title)
            putExtra("content", reminder.content)
            putExtra("actionType", reminder.actionType.ordinal)
            putExtra("reminderId", reminder.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    android.util.Log.w("ReminderService", "没有精确闹钟权限")
                    return false
                }
            }
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            android.util.Log.i("ReminderService", "提醒已设置: triggerTime=$triggerTime")
            return true
        } catch (e: Exception) {
            android.util.Log.e("ReminderService", "设置提醒失败", e)
            return false
        }
    }

    private fun cancelReminder(reminderId: Long) {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun calculateNextTriggerTime(reminder: Reminder): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        return when (reminder.triggerType) {
            TriggerType.ONCE -> {
                // 解析一次性时间
                try {
                    reminder.triggerValue.toLong()
                } catch (e: Exception) {
                    now + 60000 // 默认1分钟后
                }
            }
            TriggerType.DAILY -> {
                // 解析每天时间 HH:mm
                val parts = reminder.triggerValue.split(":")
                if (parts.size == 2) {
                    calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    calendar.set(Calendar.MINUTE, parts[1].toInt())
                    calendar.set(Calendar.SECOND, 0)
                    if (calendar.timeInMillis <= now) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    calendar.timeInMillis
                } else {
                    now + AlarmManager.INTERVAL_DAY
                }
            }
            TriggerType.INTERVAL -> {
                // 解析间隔 分钟
                try {
                    val minutes = reminder.triggerValue.toInt()
                    now + (minutes * 60 * 1000L)
                } catch (e: Exception) {
                    now + AlarmManager.INTERVAL_DAY
                }
            }
            else -> now + AlarmManager.INTERVAL_DAY
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
