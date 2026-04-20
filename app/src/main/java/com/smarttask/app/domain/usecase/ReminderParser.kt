package com.smarttask.app.domain.usecase

import com.smarttask.app.domain.model.TriggerType
import java.util.*

/**
 * 自然语言提醒解析器
 * 基于 wx_bot scheduler_parser 设计的算法，将中文自然语言解析为定时任务
 */
class ReminderParser {

    companion object {
        private val CN_NUM = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
            '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '两' to 2
        )

        private val WEEKDAY_NAMES = mapOf(
            "一" to Calendar.MONDAY,
            "二" to Calendar.TUESDAY,
            "三" to Calendar.WEDNESDAY,
            "四" to Calendar.THURSDAY,
            "五" to Calendar.FRIDAY,
            "六" to Calendar.SATURDAY,
            "日" to Calendar.SUNDAY,
            "天" to Calendar.SUNDAY
        )

        private val IGNORED_WORDS = setOf("的", "，", "。", "！", "？", "提醒", "我")
    }

    private var text: String = ""
    private var index: Int = 0
    private val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))

    private val timeFields = mutableMapOf<String, Int>()
    private val deltaFields = mutableMapOf<String, Int>()
    private val repeatFields = mutableMapOf<String, Int>()

    private var afternoonFlag = false
    private var eventDescription = ""

    class ParseResult(
        val triggerType: TriggerType,
        val triggerTime: Long,
        val triggerValue: String,
        val event: String,
        val rawInput: String,
        val error: String? = null
    )

    fun parse(input: String): ParseResult {
        index = 0
        text = input
        timeFields.clear()
        deltaFields.clear()
        repeatFields.clear()
        afternoonFlag = false
        eventDescription = ""

        return try {
            // 预处理：转换中文数字并标准化
            text = normalizeText(text)
            index = 0

            // 解析
            while (index < text.length) {
                val startIdx = index

                // 尝试各种解析
                if (consumeRepeat()) continue
                if (consumeRelativeDay()) continue
                if (consumeWeekday()) continue
                if (consumeHourPeriod()) continue
                if (consumeMinutePeriod()) continue
                if (consumeTime()) continue

                // 如果没有消耗任何字符，向前移动
                if (index == startIdx) {
                    index++
                }
            }

            // 提取剩余文本作为事件
            eventDescription = text.substring(index).filter { it !in IGNORED_WORDS }

            if (timeFields.isEmpty() && deltaFields.isEmpty()) {
                return ParseResult(
                    triggerType = TriggerType.ONCE,
                    triggerTime = System.currentTimeMillis(),
                    triggerValue = "",
                    event = input,
                    rawInput = input,
                    error = "无法理解你输入的时间，请换一种方式描述"
                )
            }

            val (triggerType, triggerValue, triggerTime) = calculateResult()

            ParseResult(
                triggerType = triggerType,
                triggerTime = triggerTime,
                triggerValue = triggerValue,
                event = eventDescription.ifEmpty { input },
                rawInput = input
            )
        } catch (e: Exception) {
            ParseResult(
                triggerType = TriggerType.ONCE,
                triggerTime = System.currentTimeMillis(),
                triggerValue = "",
                event = input,
                rawInput = input,
                error = e.message ?: "解析失败"
            )
        }
    }

    private fun normalizeText(text: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]

            when {
                char in CN_NUM -> {
                    val num = CN_NUM[char]!!
                    var j = i + 1
                    var unit = 1
                    var hasUnit = false

                    // 查看后续是否有单位
                    if (j < text.length) {
                        val next = text[j]
                        if (next == '十' || next == '拾') {
                            unit = 10
                            hasUnit = true
                            j++
                        } else if (next == '百' || next == '佰') {
                            unit = 100
                            hasUnit = true
                            j++
                        } else if (next == '千' || next == '仟') {
                            unit = 1000
                            hasUnit = true
                            j++
                        }
                    }

                    if (hasUnit) {
                        if (num == 0) {
                            result.append(unit)
                        } else {
                            result.append(num * unit)
                        }
                    } else {
                        result.append(num)
                    }
                    i = j
                }
                char.isDigit() || char.isWhitespace() || char in ":：.-" -> {
                    result.append(char)
                    i++
                }
                else -> {
                    result.append(char)
                    i++
                }
            }
        }

        return result.toString()
    }

    private fun peek(length: Int = 1): String {
        return text.substring(index, minOf(index + length, text.length))
    }

    private fun peekWord(): String {
        val start = index
        while (start < text.length && text[start] in IGNORED_WORDS) {
            // skip ignored
        }
        val end = start
        var i = end
        while (i < text.length && text[i] !in IGNORED_WORDS && text[i] !in " \t\n\r,，、；;:：.()-（）[]{}") {
            i++
        }
        return text.substring(start, i)
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\n\r") {
            index++
        }
    }

    private fun consume(keyword: String): Boolean {
        skipWhitespace()
        if (text.substring(index).startsWith(keyword)) {
            index += keyword.length
            return true
        }
        return false
    }

    private fun consumeWord(vararg keywords: String): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)
        for (kw in keywords) {
            if (remaining.startsWith(kw)) {
                // 检查是否是完整匹配（后面是分隔符或空白）
                val endIdx = index + kw.length
                if (endIdx >= text.length || text[endIdx] in " \t\n\r,，、；;:：.()-（）[]{}") {
                    index = endIdx
                    return true
                }
            }
        }
        return false
    }

    private fun consumeDigit(): Int? {
        skipWhitespace()
        var i = index
        while (i < text.length && text[i] == '0') i++
        var numStr = ""
        while (i < text.length && text[i].isDigit()) {
            numStr += text[i]
            i++
        }
        if (numStr.isNotEmpty()) {
            index = i
            return numStr.toInt()
        }
        return null
    }

    private fun consumeRepeat(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        if (remaining.startsWith("每隔")) {
            index += 2
            skipWhitespace()
            val count = consumeDigit() ?: 1
            skipWhitespace()
            when {
                consume("天") -> {
                    repeatFields["days"] = count
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("小时") || consume("钟头") -> {
                    repeatFields["hours"] = count
                    return true
                }
                consume("分钟") || consume("分") -> {
                    repeatFields["minutes"] = count
                    return true
                }
            }
        }

        if (remaining.startsWith("每")) {
            index++
            skipWhitespace()
            when {
                consume("天") || consume("每天") -> {
                    repeatFields["days"] = 1
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("周") || consume("每周") -> {
                    repeatFields["weeks"] = 1
                    // 尝试解析周几
                    if (consumeWord("一", "二", "三", "四", "五", "六", "日", "天")) {
                        val dayName = peekWord().let { if (it.length == 1) it else it.last().toString() }
                        if (dayName in WEEKDAY_NAMES) {
                            deltaFields["weekday"] = WEEKDAY_NAMES[dayName]!!
                        }
                    }
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("月") || consume("每月") -> {
                    repeatFields["months"] = 1
                    consumeDay()
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("年") || consume("每年") -> {
                    repeatFields["years"] = 1
                    consumeMonth()
                    consumeDay()
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("小时") -> {
                    repeatFields["hours"] = 1
                    return true
                }
            }
        }
        return false
    }

    private fun consumeRelativeDay(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        return when {
            remaining.startsWith("今天") -> {
                index += 2
                deltaFields["days"] = 0
                setDefaultTimeIfNeeded()
                true
            }
            remaining.startsWith("明天") || remaining.startsWith("明儿") -> {
                index += 2
                deltaFields["days"] = 1
                setDefaultTimeIfNeeded()
                true
            }
            remaining.startsWith("后天") -> {
                index += 2
                deltaFields["days"] = 2
                setDefaultTimeIfNeeded()
                true
            }
            remaining.startsWith("大后天") -> {
                index += 3
                deltaFields["days"] = 3
                setDefaultTimeIfNeeded()
                true
            }
            remaining.startsWith("今晚") || remaining.startsWith("今晚") -> {
                index += 2
                afternoonFlag = true
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                true
            }
            remaining.startsWith("明晚") -> {
                index += 2
                afternoonFlag = true
                deltaFields["days"] = 1
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                true
            }
            else -> {
                // 检查 X天后
                val digit = consumeDigit()
                if (digit != null) {
                    skipWhitespace()
                    if (consume("天后") || consume("天后")) {
                        deltaFields["days"] = digit
                        setDefaultTimeIfNeeded()
                        return true
                    }
                    if (consume("天") && (consume("后") || consumeWord("以后"))) {
                        deltaFields["days"] = digit
                        setDefaultTimeIfNeeded()
                        return true
                    }
                }
                false
            }
        }
    }

    private fun consumeWeekday(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        // 处理下周X
        if (remaining.startsWith("下周一") || remaining.startsWith("下周1")) {
            index += 3
            deltaFields["weekday"] = Calendar.MONDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("下周二") || remaining.startsWith("下周2")) {
            index += 3
            deltaFields["weekday"] = Calendar.TUESDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("下周三") || remaining.startsWith("下周3")) {
            index += 3
            deltaFields["weekday"] = Calendar.WEDNESDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("下周四") || remaining.startsWith("下周4")) {
            index += 3
            deltaFields["weekday"] = Calendar.THURSDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("下周五") || remaining.startsWith("下周5")) {
            index += 3
            deltaFields["weekday"] = Calendar.FRIDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("下周六") || remaining.startsWith("下周6")) {
            index += 3
            deltaFields["weekday"] = Calendar.SATURDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("下周日") || remaining.startsWith("下周7") || remaining.startsWith("下周日")) {
            index += 3
            deltaFields["weekday"] = Calendar.SUNDAY
            deltaFields["weekDelta"] = 1
            setDefaultTimeIfNeeded()
            return true
        }

        // 处理周X
        if (remaining.startsWith("周一") || remaining.startsWith("周1")) {
            index += 2
            deltaFields["weekday"] = Calendar.MONDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("周二") || remaining.startsWith("周2")) {
            index += 2
            deltaFields["weekday"] = Calendar.TUESDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("周三") || remaining.startsWith("周3")) {
            index += 2
            deltaFields["weekday"] = Calendar.WEDNESDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("周四") || remaining.startsWith("周4")) {
            index += 2
            deltaFields["weekday"] = Calendar.THURSDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("周五") || remaining.startsWith("周5")) {
            index += 2
            deltaFields["weekday"] = Calendar.FRIDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("周六") || remaining.startsWith("周6")) {
            index += 2
            deltaFields["weekday"] = Calendar.SATURDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("周日") || remaining.startsWith("周7") || remaining.startsWith("周天")) {
            index += 2
            deltaFields["weekday"] = Calendar.SUNDAY
            setDefaultTimeIfNeeded()
            return true
        }

        // 处理"星期X"
        if (remaining.startsWith("星期一")) {
            index += 3
            deltaFields["weekday"] = Calendar.MONDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("星期二")) {
            index += 3
            deltaFields["weekday"] = Calendar.TUESDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("星期三")) {
            index += 3
            deltaFields["weekday"] = Calendar.WEDNESDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("星期四")) {
            index += 3
            deltaFields["weekday"] = Calendar.THURSDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("星期五")) {
            index += 3
            deltaFields["weekday"] = Calendar.FRIDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("星期六")) {
            index += 3
            deltaFields["weekday"] = Calendar.SATURDAY
            setDefaultTimeIfNeeded()
            return true
        }
        if (remaining.startsWith("星期日") || remaining.startsWith("星期天")) {
            index += 3
            deltaFields["weekday"] = Calendar.SUNDAY
            setDefaultTimeIfNeeded()
            return true
        }

        return false
    }

    private fun consumeHourPeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        // 半小时后
        if (remaining.startsWith("半小时后") || remaining.startsWith("半个钟头后")) {
            index += if (remaining.startsWith("半小时后")) 4 else 5
            deltaFields["minutes"] = 30
            return true
        }

        // X个半小时后
        if (remaining.length >= 4 && remaining[0].isDigit()) {
            val i = index
            val count = consumeDigit()
            if (count != null) {
                skipWhitespace()
                if (consume("个") && consume("半") && (consume("小时") || consume("钟头"))) {
                    if (consume("后") || consumeWord("以后")) {
                        deltaFields["hours"] = count
                        deltaFields["minutes"] = 30
                        return true
                    }
                }
                index = i
            }
        }

        // X小时后
        if (remaining.length >= 3 && remaining[0].isDigit()) {
            val i = index
            val count = consumeDigit()
            if (count != null) {
                skipWhitespace()
                if (consume("小时") || consume("钟头")) {
                    if (consume("后") || consumeWord("以后")) {
                        deltaFields["hours"] = count
                        return true
                    }
                }
                index = i
            }
        }

        return false
    }

    private fun consumeMinutePeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        // 等会/一会儿
        if (remaining.startsWith("等会") || remaining.startsWith("一会") || remaining.startsWith("一会儿")) {
            index += if (remaining.startsWith("等会")) 2 else if (remaining.startsWith("一会儿")) 3 else 2
            deltaFields["minutes"] = 10
            return true
        }

        // X分钟后
        if (remaining.length >= 3 && remaining[0].isDigit()) {
            val count = consumeDigit()
            if (count != null) {
                skipWhitespace()
                if (consume("分钟") || consume("分")) {
                    if (consume("后") || consumeWord("以后")) {
                        deltaFields["minutes"] = count
                        return true
                    }
                    deltaFields["minutes"] = count
                    return true
                }
            }
        }

        return false
    }

    private fun consumeTime(): Boolean {
        skipWhitespace()
        var consumed = false

        // 解析时间前缀
        when {
            consume("凌晨") || consume("深夜") || consume("半夜") -> {
                afternoonFlag = false
                deltaFields["days"] = (deltaFields["days"] ?: 0) + 1
                timeFields["hour"] = 0
                timeFields["minute"] = 0
                consumed = true
            }
            consume("早上") || consume("早晨") || consume("上午") || consume("今早") -> {
                afternoonFlag = false
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 8
                    timeFields["minute"] = 0
                }
                consumed = true
            }
            consume("中午") -> {
                afternoonFlag = false
                timeFields["hour"] = 12
                timeFields["minute"] = 0
                consumed = true
            }
            consume("下午") -> {
                afternoonFlag = true
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 13
                    timeFields["minute"] = 0
                }
                consumed = true
            }
            consume("傍晚") -> {
                afternoonFlag = true
                timeFields["hour"] = 18
                timeFields["minute"] = 0
                consumed = true
            }
            consume("晚上") || consume("今晚") -> {
                afternoonFlag = true
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                consumed = true
            }
        }

        // 解析具体时间 8点03 / 8:03 / 8点
        if (text.length > index && text[index].isDigit()) {
            val hour = consumeDigit() ?: return consumed

            skipWhitespace()
            if (consume("点") || consume("点钟") || consume("点整") || consume("时") ||
                consume(":") || consume("：") || consume(".")) {

                var finalHour = hour

                // 12小时制转24小时制
                if (afternoonFlag && hour < 12) {
                    finalHour += 12
                } else if (!afternoonFlag && hour < 12 && now.get(Calendar.HOUR_OF_DAY) >= 12 &&
                    !timeFields.containsKey("hour") && !deltaFields.containsKey("days")) {
                    finalHour += 12
                }

                timeFields["hour"] = finalHour

                // 解析分钟
                skipWhitespace()
                val minute = consumeDigit()
                if (minute != null && minute <= 59) {
                    timeFields["minute"] = minute
                    if (consume("分") || consume("分钟")) {
                        // 消费"分"字符
                    }
                } else {
                    timeFields["minute"] = timeFields["minute"] ?: 0
                }

                return true
            } else if (hour <= 23) {
                // 只有小时没有分钟
                timeFields["hour"] = hour
                timeFields["minute"] = timeFields["minute"] ?: 0
                return true
            }
        }

        // 解析分钟单独出现
        if (consumeWord("半")) {
            timeFields["minute"] = 30
            return true
        }

        return consumed
    }

    private fun consumeDay(): Boolean {
        skipWhitespace()
        val digit = consumeDigit()
        if (digit != null && digit in 1..31) {
            skipWhitespace()
            consume("号") || consume("日")
            timeFields["day"] = digit
            return true
        }
        return false
    }

    private fun consumeMonth(): Boolean {
        skipWhitespace()
        val digit = consumeDigit()
        if (digit != null && digit in 1..12 && (text.length > index && text[index] == '月')) {
            index++
            timeFields["month"] = digit
            return true
        }
        return false
    }

    private fun setDefaultTimeIfNeeded() {
        if (!timeFields.containsKey("hour")) {
            timeFields["hour"] = 8
            timeFields["minute"] = 0
        }
    }

    private fun calculateResult(): Triple<TriggerType, String, Long> {
        val calendar = now.clone() as Calendar

        val days = deltaFields["days"] ?: 0
        calendar.add(Calendar.DAY_OF_YEAR, days)

        if (deltaFields.containsKey("weekday")) {
            val targetWeekday = deltaFields["weekday"]!!
            val weekDelta = deltaFields["weekDelta"] ?: 0

            var daysUntilWeekday = targetWeekday - calendar.get(Calendar.DAY_OF_WEEK)
            if (daysUntilWeekday < 0) daysUntilWeekday += 7
            daysUntilWeekday += weekDelta * 7

            if (daysUntilWeekday == 0 && calendar.get(Calendar.HOUR_OF_DAY) >= 12 && !timeFields.containsKey("hour")) {
                daysUntilWeekday = 7
            }

            calendar.add(Calendar.DAY_OF_YEAR, daysUntilWeekday)
        }

        if (timeFields.containsKey("month")) {
            calendar.set(Calendar.MONTH, timeFields["month"]!! - 1)
        }
        if (timeFields.containsKey("day")) {
            calendar.set(Calendar.DAY_OF_MONTH, timeFields["day"]!!)
        }
        if (timeFields.containsKey("hour")) {
            calendar.set(Calendar.HOUR_OF_DAY, timeFields["hour"]!!)
        }
        if (timeFields.containsKey("minute")) {
            calendar.set(Calendar.MINUTE, timeFields["minute"]!!)
        }
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return when {
            repeatFields["years"] != null -> {
                val month = timeFields["month"] ?: 1
                val day = timeFields["day"] ?: 1
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(TriggerType.YEARLY, "$month-$day $hour:$minute", calendar.timeInMillis)
            }
            repeatFields["months"] != null -> {
                val day = timeFields["day"] ?: 1
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(TriggerType.MONTHLY, "$day $hour:$minute", calendar.timeInMillis)
            }
            repeatFields["weeks"] != null -> {
                val weekday = deltaFields["weekday"] ?: Calendar.MONDAY
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(TriggerType.WEEKLY, "$weekday $hour:$minute", calendar.timeInMillis)
            }
            repeatFields["days"] != null -> {
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(TriggerType.DAILY, "$hour:$minute", calendar.timeInMillis)
            }
            repeatFields["hours"] != null -> {
                Triple(TriggerType.INTERVAL, "${repeatFields["hours"]!! * 60}", calendar.timeInMillis)
            }
            repeatFields["minutes"] != null -> {
                Triple(TriggerType.INTERVAL, "${repeatFields["minutes"]}", calendar.timeInMillis)
            }
            else -> {
                Triple(TriggerType.ONCE, calendar.timeInMillis.toString(), calendar.timeInMillis)
            }
        }
    }
}
