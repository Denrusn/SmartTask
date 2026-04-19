package com.smarttask.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val triggerType: TriggerType,
    val triggerValue: String, // JSON or cron expression
    val actionType: ActionType,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null
)

enum class TriggerType {
    ONCE,           // 指定日期时间
    DAILY,          // 每天
    WEEKLY,         // 每周
    MONTHLY,        // 每月
    YEARLY,         // 每年
    INTERVAL,       // 间隔 N 分钟/小时/天
    CRON            // Cron表达式
}

enum class ActionType {
    NOTIFICATION,   // 普通通知
    FORCE_ALARM,    // 强提醒
    DIALOG,         // 弹出对话框
    URL,            // 打开URL
    APP             // 启动应用
}
