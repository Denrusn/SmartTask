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
import com.smarttask.app.util.LogcatManager
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
        LogcatManager.i("Service", "ReminderService 创建成功")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        LogcatManager.i("Service", "onStartCommand: action=$action")

        when (action) {
            "com.smarttask.RESCHEDULE_ALL" -> {
                LogcatManager.i("Service", "执行 RESCHEDULE_ALL")
                rescheduleAllReminders()
            }
            "com.smarttask.SCHEDULE" -> {
                val reminderId = intent?.getLongExtra("reminderId", -1) ?: -1
                LogcatManager.i("Service", "SCHEDULE: reminderId=$reminderId")
                if (reminderId != -1L) {
                    serviceScope.launch {
                        val reminder = database.reminderDao().getReminderById(reminderId)
                        reminder?.let {
                            LogcatManager.i("Service", "找到提醒: ${it.title}, triggerType=${it.triggerType}, triggerValue=${it.triggerValue}")
                            val success = scheduleReminder(it)
                            if (success) {
                                LogcatManager.i("Service", "提醒调度成功: ${it.title}")
                            } else {
                                LogcatManager.w("Service", "提醒调度失败: ${it.title}")
                                val updated = it.copy(isEnabled = false)
                                database.reminderDao().updateReminder(updated)
                                LogcatManager.i("Service", "已禁用提醒: ${it.title}")
                            }
                        } ?: LogcatManager.e("Service", "未找到提醒ID: $reminderId")
                    }
                }
            }
            "com.smarttask.CANCEL" -> {
                val reminderId = intent?.getLongExtra("reminderId", -1) ?: -1
                LogcatManager.i("Service", "CANCEL: reminderId=$reminderId")
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
            LogcatManager.i("Service", "rescheduleAll: 共 ${reminders.size} 个提醒")
            reminders.forEach { reminder ->
                scheduleReminder(reminder)
            }
        }
    }

    fun scheduleReminder(reminder: Reminder): Boolean {
        LogcatManager.i("Service", "scheduleReminder: ${reminder.title}")

        val triggerTime = calculateNextTriggerTime(reminder)
        val now = System.currentTimeMillis()
        LogcatManager.i("Service", "triggerTime=$triggerTime, now=$now, diff=${triggerTime - now}ms")

        if (triggerTime <= now) {
            LogcatManager.w("Service", "时间已过，无法调度")
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
                    LogcatManager.e("Service", "没有精确闹钟权限 (Android 12+)")
                    return false
                }
            }

            // 检查 AlarmManager 类型是否支持
            LogcatManager.i("Service", "设置 AlarmManager.setExactAndAllowWhileIdle")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            LogcatManager.i("Service", "提醒已设置成功，triggerTime=$triggerTime")
            return true
        } catch (e: Exception) {
            LogcatManager.e("Service", "设置提醒失败: ${e.message}", e)
            return false
        }
    }

    private fun cancelReminder(reminderId: Long) {
        LogcatManager.i("Service", "cancelReminder: $reminderId")
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        LogcatManager.i("Service", "已取消提醒: $reminderId")
    }

    private fun calculateNextTriggerTime(reminder: Reminder): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        LogcatManager.i("Service", "calculateNextTriggerTime: type=${reminder.triggerType}, value=${reminder.triggerValue}")

        return when (reminder.triggerType) {
            TriggerType.ONCE -> {
                try {
                    val timestamp = reminder.triggerValue.toLong()
                    LogcatManager.i("Service", "ONCE: timestamp=$timestamp")
                    timestamp
                } catch (e: Exception) {
                    LogcatManager.e("Service", "ONCE: 解析时间戳失败", e)
                    now + 60000
                }
            }
            TriggerType.DAILY -> {
                val parts = reminder.triggerValue.split(":")
                if (parts.size == 2) {
                    calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    calendar.set(Calendar.MINUTE, parts[1].toInt())
                    calendar.set(Calendar.SECOND, 0)
                    if (calendar.timeInMillis <= now) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    LogcatManager.i("Service", "DAILY: next trigger at ${calendar.timeInMillis}")
                    calendar.timeInMillis
                } else {
                    now + AlarmManager.INTERVAL_DAY
                }
            }
            TriggerType.INTERVAL -> {
                try {
                    val minutes = reminder.triggerValue.toInt()
                    val trigger = now + (minutes * 60 * 1000L)
                    LogcatManager.i("Service", "INTERVAL: ${minutes}分钟后，trigger=$trigger")
                    trigger
                } catch (e: Exception) {
                    now + AlarmManager.INTERVAL_DAY
                }
            }
            TriggerType.WEEKLY -> {
                LogcatManager.i("Service", "WEEKLY: not fully implemented")
                now + AlarmManager.INTERVAL_DAY
            }
            TriggerType.MONTHLY -> {
                LogcatManager.i("Service", "MONTHLY: not fully implemented")
                now + AlarmManager.INTERVAL_DAY
            }
            TriggerType.YEARLY -> {
                LogcatManager.i("Service", "YEARLY: not fully implemented")
                now + AlarmManager.INTERVAL_DAY
            }
            TriggerType.CRON -> {
                LogcatManager.i("Service", "CRON: not implemented")
                now + AlarmManager.INTERVAL_DAY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        LogcatManager.i("Service", "ReminderService 销毁")
    }
}