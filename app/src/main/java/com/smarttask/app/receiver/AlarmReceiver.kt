package com.smarttask.app.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smarttask.app.R
import com.smarttask.app.SmartTaskApp
import com.smarttask.app.domain.model.ActionType
import com.smarttask.app.ui.screens.home.ForceAlarmActivity

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "提醒"
        val content = intent.getStringExtra("content") ?: ""
        val actionTypeOrdinal = intent.getIntExtra("actionType", ActionType.NOTIFICATION.ordinal)
        val actionType = ActionType.entries[actionTypeOrdinal]
        val reminderId = intent.getLongExtra("reminderId", 0)

        when (actionType) {
            ActionType.NOTIFICATION -> showNotification(context, reminderId, title, content)
            ActionType.FORCE_ALARM -> showForceAlarm(context, title, content)
            else -> showNotification(context, reminderId, title, content)
        }
    }

    private fun showNotification(context: Context, id: Long, title: String, content: String) {
        val notification = NotificationCompat.Builder(context, SmartTaskApp.CHANNEL_NORMAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id.toInt(), notification)
        } catch (e: SecurityException) {
            // 通知权限被拒绝
        }
    }

    private fun showForceAlarm(context: Context, title: String, content: String) {
        val forceAlarmIntent = Intent(context, ForceAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("title", title)
            putExtra("content", content)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            forceAlarmIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(forceAlarmIntent)
    }
}
