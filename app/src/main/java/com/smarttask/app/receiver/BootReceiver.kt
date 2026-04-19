package com.smarttask.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smarttask.app.service.ReminderService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 重新启动时重新调度所有启用的提醒
            val serviceIntent = Intent(context, ReminderService::class.java).apply {
                action = "com.smarttask.RESCHEDULE_ALL"
            }
            context.startService(serviceIntent)
        }
    }
}
