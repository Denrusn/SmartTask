package com.smarttask.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SmartTaskApp : Application() {

    companion object {
        const val CHANNEL_NORMAL = "normal_reminder"
        const val CHANNEL_FORCE = "force_reminder"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val normalChannel = NotificationChannel(
            CHANNEL_NORMAL,
            "普通提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "普通提醒通知"
        }

        val forceChannel = NotificationChannel(
            CHANNEL_FORCE,
            "强提醒",
            NotificationManager.IMPORTANCE_FULL_SCREEN
        ).apply {
            description = "强提醒通知，需要全屏展示"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(normalChannel)
        notificationManager.createNotificationChannel(forceChannel)
    }
}
