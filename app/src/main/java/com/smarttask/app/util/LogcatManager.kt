package com.smarttask.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

object LogcatManager {
    private const val MAX_LOGS = 500
    private val logs = ConcurrentLinkedQueue<LogEntry>()

    fun d(tag: String, message: String) {
        addLog("D", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        addLog("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        addLog("W", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = throwable?.let { "$message\n${Log.getStackTraceString(it)}" } ?: message
        addLog("E", tag, fullMessage)
        Log.e(tag, message, throwable)
    }

    private fun addLog(level: String, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        logs.offer(entry)

        // 保持日志数量在限制内
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
    }

    fun getLogs(): List<LogEntry> = logs.toList()

    fun clear() {
        logs.clear()
    }

    fun getLogsByTag(tag: String): List<LogEntry> =
        logs.filter { it.tag == tag }

    fun getErrorLogs(): List<LogEntry> =
        logs.filter { it.level == "E" }
}