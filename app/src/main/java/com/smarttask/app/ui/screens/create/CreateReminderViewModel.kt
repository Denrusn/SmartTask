package com.smarttask.app.ui.screens.create

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smarttask.app.data.local.SmartTaskDatabase
import com.smarttask.app.data.repository.ReminderRepository
import com.smarttask.app.domain.model.ActionType
import com.smarttask.app.domain.model.Reminder
import com.smarttask.app.domain.model.TriggerType
import com.smarttask.app.domain.usecase.ReminderParser
import com.smarttask.app.service.ReminderService
import com.smarttask.app.util.LogcatManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateReminderUiState(
    val inputText: String = "",
    val parsedResult: ParsedReminderUiState? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val navigateBack: Boolean = false
)

data class ParsedReminderUiState(
    val event: String,
    val triggerTime: String,
    val triggerType: TriggerType,
    val repeatDescription: String,
    val rawInput: String
)

class CreateReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val database = SmartTaskDatabase.getDatabase(application)
    private val repository = ReminderRepository(database.reminderDao())
    private val parser = ReminderParser()

    private val _uiState = MutableStateFlow(CreateReminderUiState())
    val uiState: StateFlow<CreateReminderUiState> = _uiState.asStateFlow()

    init {
        LogcatManager.i("ViewModel", "CreateReminderViewModel 初始化")
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun parseInput() {
        val input = _uiState.value.inputText.trim()
        LogcatManager.i("ViewModel", "parseInput: input='$input'")

        if (input.isEmpty()) {
            LogcatManager.w("ViewModel", "输入为空")
            _uiState.value = _uiState.value.copy(error = "请输入提醒内容")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        val result = parser.parse(input)
        LogcatManager.i("ViewModel", "解析结果: event=${result.event}, triggerType=${result.triggerType}, triggerTime=${result.triggerTime}, error=${result.error}")

        if (result.error != null) {
            LogcatManager.w("ViewModel", "解析失败: ${result.error}")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = result.error
            )
            return
        }

        val repeatDesc = when (result.triggerType) {
            TriggerType.DAILY -> "每天"
            TriggerType.WEEKLY -> "每周"
            TriggerType.MONTHLY -> "每月"
            TriggerType.YEARLY -> "每年"
            TriggerType.INTERVAL -> "每隔${result.triggerValue}分钟"
            TriggerType.ONCE -> "仅一次"
            TriggerType.CRON -> "Cron表达式"
        }

        val timeStr = formatTriggerTime(result)
        LogcatManager.i("ViewModel", "格式化时间: $timeStr")

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            parsedResult = ParsedReminderUiState(
                event = result.event,
                triggerTime = timeStr,
                triggerType = result.triggerType,
                repeatDescription = repeatDesc,
                rawInput = result.rawInput
            )
        )
        LogcatManager.i("ViewModel", "解析成功，parsedResult 已设置")
    }

    fun saveReminder() {
        val parsed = _uiState.value.parsedResult
        LogcatManager.i("ViewModel", "saveReminder: parsed=$parsed")

        if (parsed == null) {
            LogcatManager.w("ViewModel", "parsedResult 为空，无法保存")
            return
        }

        viewModelScope.launch {
            try {
                LogcatManager.i("ViewModel", "开始保存提醒...")

                // 检查时间是否已过
                val triggerTimeMs = parseTriggerTime(parsed)
                val now = System.currentTimeMillis()
                LogcatManager.i("ViewModel", "triggerTimeMs=$triggerTimeMs, now=$now, diff=${triggerTimeMs - now}ms")

                if (parsed.triggerType == TriggerType.ONCE && triggerTimeMs <= now) {
                    LogcatManager.w("ViewModel", "提醒时间已过")
                    _uiState.value = _uiState.value.copy(error = "提醒时间已过，请重新设置一个将来的时间")
                    return@launch
                }

                // 构建 triggerValue
                val triggerValue = when (parsed.triggerType) {
                    TriggerType.ONCE -> triggerTimeMs.toString()
                    TriggerType.DAILY, TriggerType.WEEKLY, TriggerType.MONTHLY, TriggerType.YEARLY ->
                        parsed.triggerTime.split(" ").lastOrNull() ?: ""
                    else -> parsed.triggerTime
                }
                LogcatManager.i("ViewModel", "triggerValue = $triggerValue")

                val reminder = Reminder(
                    title = parsed.event,
                    content = "提醒：${parsed.event}",
                    triggerType = parsed.triggerType,
                    triggerValue = triggerValue,
                    actionType = ActionType.NOTIFICATION
                )
                LogcatManager.i("ViewModel", "创建 Reminder 对象: $reminder")

                val id = repository.insertReminder(reminder)
                LogcatManager.i("ViewModel", "数据库插入成功，id=$id")

                // 启动服务来调度提醒
                val intent = android.content.Intent(getApplication(), ReminderService::class.java).apply {
                    action = "com.smarttask.SCHEDULE"
                    putExtra("reminderId", id)
                }
                LogcatManager.i("ViewModel", "启动 ReminderService, intent.action=${intent.action}, reminderId=$id")
                getApplication<Application>().startService(intent)
                LogcatManager.i("ViewModel", "startService 调用完成")

                _uiState.value = _uiState.value.copy(parsedResult = null, inputText = "", navigateBack = true)
                LogcatManager.i("ViewModel", "保存成功，navigateBack=true")
            } catch (e: Exception) {
                LogcatManager.e("ViewModel", "保存失败: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = "保存失败: ${e.message}")
            }
        }
    }

    private fun parseTriggerTime(parsed: ParsedReminderUiState): Long {
        LogcatManager.i("ViewModel", "parseTriggerTime: triggerTime='${parsed.triggerTime}'")
        return try {
            val timeStr = parsed.triggerTime
            // 格式: "4月20日(周一) 08:03"
            val regex = Regex("(\\d+)月(\\d+)日.*?(\\d+):(\\d+)")
            val match = regex.find(timeStr)
            if (match != null) {
                val (month, day, hour, minute) = match.destructured
                val calendar = java.util.Calendar.getInstance()
                calendar.set(java.util.Calendar.MONTH, month.toInt() - 1)
                calendar.set(java.util.Calendar.DAY_OF_MONTH, day.toInt())
                calendar.set(java.util.Calendar.HOUR_OF_DAY, hour.toInt())
                calendar.set(java.util.Calendar.MINUTE, minute.toInt())
                calendar.set(java.util.Calendar.SECOND, 0)
                calendar.set(java.util.Calendar.MILLISECOND, 0)
                LogcatManager.i("ViewModel", "parseTriggerTime 结果: ${calendar.timeInMillis}")
                calendar.timeInMillis
            } else {
                LogcatManager.w("ViewModel", "parseTriggerTime: 正则不匹配，返回当前时间")
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            LogcatManager.e("ViewModel", "parseTriggerTime 异常: ${e.message}", e)
            System.currentTimeMillis()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun resetInput() {
        LogcatManager.i("ViewModel", "resetInput")
        _uiState.value = CreateReminderUiState()
    }

    fun clearParsedResult() {
        LogcatManager.i("ViewModel", "clearParsedResult")
        _uiState.value = _uiState.value.copy(parsedResult = null)
    }

    private fun setNavigateBack(value: Boolean) {
        _uiState.value = _uiState.value.copy(navigateBack = value)
    }

    private fun formatTriggerTime(result: ReminderParser.ParseResult): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = result.triggerTime

        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val weekday = calendar.get(java.util.Calendar.DAY_OF_WEEK)

        val weekdayStr = when (weekday) {
            java.util.Calendar.SUNDAY -> "周日"
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.TUESDAY -> "周二"
            java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"
            java.util.Calendar.FRIDAY -> "周五"
            java.util.Calendar.SATURDAY -> "周六"
            else -> ""
        }

        return String.format("%d月%d日(%s) %02d:%02d", month, day, weekdayStr, hour, minute)
    }
}

class CreateReminderViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CreateReminderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CreateReminderViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}