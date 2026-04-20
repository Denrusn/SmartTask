package com.smarttask.app.domain.usecase

import com.smarttask.app.domain.model.TriggerType
import com.smarttask.app.util.LogcatManager
import java.util.*

/**
 * 自然语言提醒解析器
 */
class ReminderParser {

    companion object {
        private val CN_NUM = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
            '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '两' to 2
        )
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

        LogcatManager.d("Parser", "parse: input='$input'")

        return try {
            text = normalizeText(text)
            index = 0
            LogcatManager.d("Parser", "normalizeText后: '$text'")

            // 首先设置默认日期为今天
            deltaFields["days"] = 0

            while (index < text.length) {
                val startIdx = index
                val remaining = text.substring(index)

                LogcatManager.d("Parser", "循环开始: index=$index, remaining='$remaining'")

                val consumedRepeat = consumeRepeat()
                LogcatManager.d("Parser", "consumeRepeat=$consumedRepeat, index=$index")
                if (consumedRepeat) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedYearPeriod = consumeYearPeriod()
                if (consumedYearPeriod) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedMonthPeriod = consumeMonthPeriod()
                if (consumedMonthPeriod) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedRelativeDay = consumeRelativeDay()
                LogcatManager.d("Parser", "consumeRelativeDay=$consumedRelativeDay, index=$index")
                if (consumedRelativeDay) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedWeekday = consumeWeekday()
                if (consumedWeekday) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedYear = consumeYear()
                if (consumedYear) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedMonth = consumeMonth()
                if (consumedMonth) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedDay = consumeDay()
                if (consumedDay) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedHourPeriod = consumeHourPeriod()
                if (consumedHourPeriod) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedMinutePeriod = consumeMinutePeriod()
                LogcatManager.d("Parser", "consumeMinutePeriod=$consumedMinutePeriod, index=$index")
                if (consumedMinutePeriod) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedSecondPeriod = consumeSecondPeriod()
                if (consumedSecondPeriod) { LogcatManager.d("Parser", "deltaFields=$deltaFields"); continue }

                val consumedTime = consumeTime()
                LogcatManager.d("Parser", "consumeTime=$consumedTime, index=$index")
                if (consumedTime) { LogcatManager.d("Parser", "deltaFields=$deltaFields, timeFields=$timeFields"); continue }

                if (index == startIdx) {
                    LogcatManager.d("Parser", "没有匹配，增加index")
                    index++
                }
            }

            eventDescription = text.substring(index)

            if (timeFields.isEmpty() && !hasTimeInfo()) {
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

    private fun hasTimeInfo(): Boolean {
        return timeFields.isNotEmpty() || deltaFields.isNotEmpty() || repeatFields.isNotEmpty()
    }

    /**
     * 规范化文本：转换中文数字
     * 十一 => 11, 三月 => 3月, 二十 => 20, 九点20 => 9:20
     */
    private fun normalizeText(text: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]

            when {
                char in CN_NUM -> {
                    var value = CN_NUM[char]!!
                    var j = i + 1
                    var hasUnit = false

                    // 处理十一、二十等
                    while (j < text.length) {
                        val next = text[j]
                        if (next == '十' || next == '拾') {
                            value = if (value == 0) 10 else value * 10
                            hasUnit = true
                            j++
                        } else if (next == '百' || next == '佰') {
                            value *= 100
                            hasUnit = true
                            j++
                        } else if (next == '千' || next == '仠') {
                            value *= 1000
                            hasUnit = true
                            j++
                        } else {
                            break
                        }
                    }

                    // 如果十后面跟着数字（如二十三）
                    if (j < text.length && hasUnit && text[j] in CN_NUM) {
                        value += CN_NUM[text[j]]!!
                        j++
                    }

                    result.append(value)
                    i = j
                }
                char == ':' || char == '：' -> {
                    result.append(':')
                    i++
                }
                char == '.' -> {
                    result.append('.')
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
        // 跳过前导零但至少保留一个数字
        var hasNonZero = false
        while (i < text.length && text[i] == '0' && !hasNonZero) {
            i++
        }
        var numStr = ""
        while (i < text.length && text[i].isDigit()) {
            numStr += text[i]
            hasNonZero = true
            i++
        }
        LogcatManager.d("Parser", "consumeDigit: index=$index, i=$i, numStr='$numStr'")
        if (numStr.isNotEmpty()) {
            index = i
            return numStr.toInt()
        }
        // 如果全是零（如"00"），返回0
        if (i > index) {
            index = i
            return 0
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
                consume("天") -> { repeatFields["days"] = count; setDefaultTimeIfNeeded(); return true }
                consume("小时") || consume("钟头") -> { repeatFields["hours"] = count; return true }
                consume("分钟") || consume("分") -> { repeatFields["minutes"] = count; return true }
            }
        }

        if (remaining.startsWith("每")) {
            index++
            skipWhitespace()
            when {
                consume("天") -> { repeatFields["days"] = 1; setDefaultTimeIfNeeded(); return true }
                consume("周") -> {
                    repeatFields["weeks"] = 1
                    val dayName = peek(1)
                    if (dayName in listOf("一", "二", "三", "四", "五", "六", "日", "天")) {
                        index++
                    }
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("月") -> { repeatFields["months"] = 1; consumeDay(); setDefaultTimeIfNeeded(); return true }
                consume("年") -> { repeatFields["years"] = 1; consumeMonth(); consumeDay(); setDefaultTimeIfNeeded(); return true }
                consume("小时") -> { repeatFields["hours"] = 1; return true }
            }
        }
        return false
    }

    private fun consumeYearPeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        when {
            remaining.startsWith("今年") -> { index += 2; deltaFields["years"] = 0; return true }
            remaining.startsWith("明年") -> { index += 2; deltaFields["years"] = 1; return true }
            remaining.startsWith("后年") -> { index += 2; deltaFields["years"] = 2; return true }
        }

        val i = index
        val num = consumeDigit()
        if (num != null && consume("年后")) { deltaFields["years"] = num; return true }
        index = i
        return false
    }

    private fun consumeMonthPeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        when {
            remaining.startsWith("下个月") || remaining.startsWith("下月") -> {
                index += if (remaining.startsWith("下个月")) 3 else 2
                deltaFields["months"] = 1
                return true
            }
        }

        val i = index
        val num = consumeDigit()
        if (num != null && consume("个月后")) { deltaFields["months"] = num; return true }
        index = i
        return false
    }

    private fun consumeRelativeDay(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        return when {
            remaining.startsWith("今天") -> { index += 2; deltaFields["days"] = 0; setDefaultTimeIfNeeded(); true }
            remaining.startsWith("明天") || remaining.startsWith("明儿") -> { index += 2; deltaFields["days"] = 1; setDefaultTimeIfNeeded(); true }
            remaining.startsWith("后天") -> { index += 2; deltaFields["days"] = 2; setDefaultTimeIfNeeded(); true }
            remaining.startsWith("大后天") -> { index += 3; deltaFields["days"] = 3; setDefaultTimeIfNeeded(); true }
            remaining.startsWith("今晚") -> { index += 2; afternoonFlag = true; setDefaultTimeIfNeeded20(); true }
            remaining.startsWith("明晚") -> { index += 2; afternoonFlag = true; deltaFields["days"] = 1; setDefaultTimeIfNeeded20(); true }
            else -> {
                val i = index
                val num = consumeDigit()
                if (num != null) {
                    skipWhitespace()
                    if (consume("天后")) { deltaFields["days"] = num; setDefaultTimeIfNeeded(); return true }
                    if (consume("天") && consumeWord("后", "以后")) { deltaFields["days"] = num; setDefaultTimeIfNeeded(); return true }
                }
                index = i
                false
            }
        }
    }

    private fun consumeWeekday(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        // 下周X - 只有明确带"下"才设置weekDelta
        val nextWeekDays = listOf(
            "下周一" to Calendar.MONDAY, "下周二" to Calendar.TUESDAY,
            "下周三" to Calendar.WEDNESDAY, "下周四" to Calendar.THURSDAY,
            "下周五" to Calendar.FRIDAY, "下周六" to Calendar.SATURDAY,
            "下周日" to Calendar.SUNDAY, "下周天" to Calendar.SUNDAY
        )
        for ((pattern, day) in nextWeekDays) {
            if (remaining.startsWith(pattern)) {
                index += pattern.length
                deltaFields["weekday"] = day
                deltaFields["weekDelta"] = 1
                setDefaultTimeIfNeeded()
                return true
            }
        }

        // 本周X - 不设置weekDelta
        val thisWeekDays = listOf(
            "周一" to Calendar.MONDAY, "周二" to Calendar.TUESDAY,
            "周三" to Calendar.WEDNESDAY, "周四" to Calendar.THURSDAY,
            "周五" to Calendar.FRIDAY, "周六" to Calendar.SATURDAY,
            "周日" to Calendar.SUNDAY, "周天" to Calendar.SUNDAY
        )
        for ((pattern, day) in thisWeekDays) {
            if (remaining.startsWith(pattern)) {
                index += pattern.length
                deltaFields["weekday"] = day
                deltaFields["weekDelta"] = 0
                setDefaultTimeIfNeeded()
                return true
            }
        }

        // 星期X / 礼拜X
        val fullWeekDays = listOf(
            "星期一" to Calendar.MONDAY, "星期二" to Calendar.TUESDAY,
            "星期三" to Calendar.WEDNESDAY, "星期四" to Calendar.THURSDAY,
            "星期五" to Calendar.FRIDAY, "星期六" to Calendar.SATURDAY,
            "星期日" to Calendar.SUNDAY, "星期天" to Calendar.SUNDAY,
            "礼拜一" to Calendar.MONDAY, "礼拜二" to Calendar.TUESDAY,
            "礼拜三" to Calendar.WEDNESDAY, "礼拜四" to Calendar.THURSDAY,
            "礼拜五" to Calendar.FRIDAY, "礼拜六" to Calendar.SATURDAY,
            "礼拜日" to Calendar.SUNDAY, "礼拜天" to Calendar.SUNDAY
        )
        for ((pattern, day) in fullWeekDays) {
            if (remaining.startsWith(pattern)) {
                index += pattern.length
                deltaFields["weekday"] = day
                deltaFields["weekDelta"] = 0
                setDefaultTimeIfNeeded()
                return true
            }
        }

        return false
    }

    private fun consumeYear(): Boolean {
        skipWhitespace()
        val num = consumeDigit() ?: return false
        if (!consume("年") && !consume("-") && !consume("/") && !consume(".")) return false
        timeFields["year"] = num
        skipWhitespace()
        consumeMonth()
        skipWhitespace()
        consumeDay()
        return true
    }

    private fun consumeMonth(): Boolean {
        skipWhitespace()
        // 中文月份
        val monthNames = mapOf(
            "一月" to 1, "二月" to 2, "三月" to 3, "四月" to 4,
            "五月" to 5, "六月" to 6, "七月" to 7, "八月" to 8,
            "九月" to 9, "十月" to 10, "十一月" to 11, "十二月" to 12
        )
        for ((name, month) in monthNames) {
            if (consume(name)) {
                timeFields["month"] = month
                return true
            }
        }

        val num = consumeDigit() ?: return false
        if (!consume("月") && !consume("-") && !consume("/") && !consume(".")) return false
        if (num in 1..12) {
            timeFields["month"] = num
            return true
        }
        return false
    }

    private fun consumeDay(): Boolean {
        skipWhitespace()
        // 中文日期
        val dayNames = mapOf(
            "一号" to 1, "二号" to 2, "三号" to 3, "四号" to 4, "五号" to 5,
            "六号" to 6, "七号" to 7, "八号" to 8, "九号" to 9, "十号" to 10,
            "十一号" to 11, "十二号" to 12, "十三号" to 13, "十四号" to 14,
            "十五号" to 15, "十六号" to 16, "十七号" to 17, "十八号" to 18,
            "十九号" to 19, "二十号" to 20, "二十一号" to 21, "二十二号" to 22,
            "二十三号" to 23, "二十四号" to 24, "二十五号" to 25, "二十六号" to 26,
            "二十七号" to 27, "二十八号" to 28, "二十九号" to 29, "三十号" to 30,
            "三十一号" to 31
        )
        for ((name, day) in dayNames) {
            if (consume(name)) {
                timeFields["day"] = day
                return true
            }
        }

        val num = consumeDigit() ?: return false
        if (num !in 1..31) return false
        skipWhitespace()
        consume("号") || consume("日")
        consume("(")
        if (consumeWord("周", "星期", "礼拜")) {
            val dayName = peek().take(1)
            if (dayName in mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7)) {
                deltaFields["weekday"] = when(dayName) {
                    "一" -> Calendar.MONDAY; "二" -> Calendar.TUESDAY; "三" -> Calendar.WEDNESDAY
                    "四" -> Calendar.THURSDAY; "五" -> Calendar.FRIDAY; "六" -> Calendar.SATURDAY
                    else -> Calendar.SUNDAY
                }
            }
        }
        consume(")")
        timeFields["day"] = num
        return true
    }

    private fun consumeHourPeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        if (remaining.startsWith("半小时后") || remaining.startsWith("半个钟头后")) {
            index += if (remaining.startsWith("半小时后")) 4 else 5
            deltaFields["minutes"] = 30
            return true
        }

        val i = index
        val count = consumeDigit()
        if (count != null) {
            skipWhitespace()
            if (consume("个") && consume("半") && (consume("小时") || consume("钟头"))) {
                if (consumeWord("后") || consumeWord("以后")) {
                    deltaFields["hours"] = count
                    deltaFields["minutes"] = 30
                    return true
                }
            }
            index = i
        }

        val j = index
        val count2 = consumeDigit()
        if (count2 != null) {
            skipWhitespace()
            if (consume("小时") || consume("钟头")) {
                if (consumeWord("后") || consumeWord("以后")) {
                    deltaFields["hours"] = count2
                    return true
                }
            }
        }
        index = j
        return false
    }

    private fun consumeMinutePeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)
        LogcatManager.d("Parser", "consumeMinutePeriod: remaining='$remaining'")

        // 专门处理"X分钟后"格式（X是数字，后面直接跟"分"）
        if (remaining.length >= 3 && remaining[0].isDigit()) {
            val i = index
            val count = consumeDigit()
            if (count == null) {
                LogcatManager.d("Parser", "consumeMinutePeriod: consumeDigit返回null")
                return false
            }
            skipWhitespace()
            LogcatManager.d("Parser", "consumeMinutePeriod: count=$count, remaining='${text.substring(index)}'")

            // 检查是否有"分"或"分钟"
            val has分 = consume("分")
            val has分钟 = consume("分钟")
            LogcatManager.d("Parser", "consumeMinutePeriod: has分=$has分, has分钟=$has分钟, remaining='${text.substring(index)}'")

            if (has分 || has分钟) {
                consume("钟") // 处理"分钟钟"等
                skipWhitespace()

                // 处理"后"或"以后"
                val has后 = consume("后")
                val has以后 = consume("以后")
                LogcatManager.d("Parser", "consumeMinutePeriod: has后=$has后, has以后=$has以后")

                deltaFields["minutes"] = count
                LogcatManager.d("Parser", "consumeMinutePeriod: 成功! deltaFields=$deltaFields, index=$index")
                return true
            } else {
                // consumeDigit成功后但没有匹配到"分"，回退
                index = i
                LogcatManager.d("Parser", "consumeMinutePeriod: 未匹配到分/分钟，回退")
            }
        }

        // 处理"等会/一会/一会儿"等
        if (remaining.startsWith("等会") || remaining.startsWith("一会") || remaining.startsWith("一会儿")) {
            index += if (remaining.startsWith("一会儿")) 3 else 2
            deltaFields["minutes"] = 10
            LogcatManager.d("Parser", "consumeMinutePeriod: 匹配等会/一会, deltaFields=$deltaFields")
            return true
        }

        return false
    }

    private fun consumeSecondPeriod(): Boolean {
        skipWhitespace()
        val count = consumeDigit() ?: return false
        skipWhitespace()
        if (consume("秒") || consume("秒钟")) {
            if (consumeWord("后") || consumeWord("以后")) {
                deltaFields["seconds"] = count
                return true
            }
            deltaFields["seconds"] = count
            return true
        }
        return false
    }

    private fun consumeTime(): Boolean {
        skipWhitespace()
        var consumed = false

        // 时间前缀
        when {
            consume("凌晨") || consume("深夜") || consume("半夜") -> {
                afternoonFlag = false
                if (deltaFields["days"] == 0 || !deltaFields.containsKey("days")) {
                    deltaFields["days"] = (deltaFields["days"] ?: 0) + 1
                }
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
            consume("晚上") -> {
                afternoonFlag = true
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                consumed = true
            }
        }

        // 解析具体时间 8:03 / 8点03 / 8点
        if (text.length > index && text[index].isDigit()) {
            val hourStart = index
            val hour = consumeDigit() ?: return consumed

            skipWhitespace()
            // 检查是否是时间格式
            val hasTimeSeparator = consume("点") || consume("点钟") || consume("点整") || consume("时") ||
                consume(":") || consume("：") || consume(".")

            LogcatManager.d("Parser", "consumeTime: hour=$hour, hasTimeSeparator=$hasTimeSeparator, index=$index, remaining='${text.substring(index)}'")

            if (hasTimeSeparator) {
                var finalHour = hour

                // 12小时制转24小时制
                if (afternoonFlag && hour < 12) {
                    finalHour += 12
                } else if (!afternoonFlag && hour < 12 && now.get(Calendar.HOUR_OF_DAY) >= 12 &&
                    !timeFields.containsKey("hour") && (deltaFields["days"] == 0 || !deltaFields.containsKey("days"))) {
                    finalHour += 12
                }

                timeFields["hour"] = finalHour

                // 解析分钟 - 直接读取连续的数字
                skipWhitespace()
                if (text.length > index && text[index].isDigit()) {
                    val minute = consumeDigit()
                    LogcatManager.d("Parser", "consumeTime: after consumeDigit minute=$minute, index=$index, remaining='${text.substring(index)}'")
                    if (minute != null) {
                        // 确保分钟在有效范围内
                        val validMinute = if (minute in 0..59) minute else 0
                        timeFields["minute"] = validMinute
                        LogcatManager.d("Parser", "consumeTime: set minute=$validMinute (original=$minute)")
                        consume("分") || consume("分钟")
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

        // 半
        if (consumeWord("半")) {
            timeFields["minute"] = 30
            return true
        }

        return consumed
    }

    private fun setDefaultTimeIfNeeded() {
        if (!timeFields.containsKey("hour")) {
            timeFields["hour"] = 8
            timeFields["minute"] = 0
        }
    }

    private fun setDefaultTimeIfNeeded20() {
        if (!timeFields.containsKey("hour")) {
            timeFields["hour"] = 20
            timeFields["minute"] = 0
        }
    }

    private fun calculateResult(): Triple<TriggerType, String, Long> {
        val calendar = now.clone() as Calendar

        LogcatManager.d("Parser", "calculateResult: timeFields=$timeFields, deltaFields=$deltaFields")

        if (deltaFields.containsKey("years")) {
            calendar.add(Calendar.YEAR, deltaFields["years"]!!)
        }
        if (deltaFields.containsKey("months")) {
            calendar.add(Calendar.MONTH, deltaFields["months"]!!)
        }

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

        if (timeFields.containsKey("year")) {
            calendar.set(Calendar.YEAR, timeFields["year"]!!)
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

        if (deltaFields.containsKey("seconds")) {
            calendar.add(Calendar.SECOND, deltaFields["seconds"]!!)
        }
        if (deltaFields.containsKey("minutes")) {
            calendar.add(Calendar.MINUTE, deltaFields["minutes"]!!)
        }
        if (deltaFields.containsKey("hours")) {
            calendar.add(Calendar.HOUR_OF_DAY, deltaFields["hours"]!!)
        }

        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        LogcatManager.d("Parser", "calculateResult: final hour=${calendar.get(Calendar.HOUR_OF_DAY)}, minute=${calendar.get(Calendar.MINUTE)}, timeInMillis=${calendar.timeInMillis}")

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
