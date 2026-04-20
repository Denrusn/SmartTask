package com.smarttask.app.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionInfo(
    val name: String,
    val description: String,
    val isGranted: Boolean,
    val action: ((Context) -> Unit)?
)

object PermissionUtils {

    fun getRequiredPermissions(context: Context): List<PermissionInfo> {
        val permissions = mutableListOf<PermissionInfo>()

        // 1. 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            permissions.add(
                PermissionInfo(
                    name = "通知权限",
                    description = "用于接收提醒通知",
                    isGranted = notificationGranted,
                    action = if (!notificationGranted) {
                        { ctx -> requestNotificationPermission(ctx) }
                    } else null
                )
            )
        }

        // 2. 精确闹钟权限 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val exactAlarmGranted = alarmManager.canScheduleExactAlarms()
            permissions.add(
                PermissionInfo(
                    name = "精确闹钟权限",
                    description = "允许精确的定时提醒（推荐）",
                    isGranted = exactAlarmGranted,
                    action = if (!exactAlarmGranted) {
                        { ctx -> openExactAlarmSettings(ctx) }
                    } else null
                )
            )
        }

        // 3. 系统弹窗权限
        val overlayGranted = Settings.canDrawOverlays(context)
        permissions.add(
            PermissionInfo(
                name = "系统弹窗权限",
                description = "在屏幕顶部显示提醒（推荐）",
                isGranted = overlayGranted,
                action = if (!overlayGranted) {
                    { ctx -> openOverlaySettings(ctx) }
                } else null
            )
        )

        return permissions
    }

    fun hasAllCriticalPermissions(context: Context): Boolean {
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true

        return notificationGranted && exactAlarmGranted
    }

    fun getMissingPermissions(context: Context): List<PermissionInfo> {
        return getRequiredPermissions(context).filter { !it.isGranted }
    }

    private fun requestNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent().apply {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }
    }

    private fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            context.startActivity(intent)
        }
    }

    private fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}