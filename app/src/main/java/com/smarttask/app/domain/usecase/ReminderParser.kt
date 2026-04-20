package com.smarttask.app.domain.usecase

import com.smarttask.app.domain.model.TriggerType
import java.util.*

/**
 * 自然语言提醒解析器
 * 基于 wx_bot scheduler_parser 设计，支持 WeCron 测试用例
 */
class ReminderParser {

    companion object {
        // 中文数字映射
        private val CN_NUM = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
            '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '两' to 2
        )

        // 中文单位映射
        private val CN_UNIT = mapOf(
            '十' to 10, '拾' to 10, '百' to 100, '佰' to 100,
            '千' to 1000, '仠' to 1000, '万' to 10000, '亿' to 100000000
        )

        // 星期映射
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

        // 忽略的字符
        private val IGNORED_CHARS = setOf('的', '，', '。', '！', '？', '、', '；', ':', '：')
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

                if (consumeRepeat()) continue
                if (consumeYearPeriod()) continue
                if (consumeMonthPeriod()) continue
                if (consumeRelativeDay()) continue
                if (consumeWeekday()) continue
                if (consumeYear()) continue
                if (consumeMonth()) continue
                if (consumeDay()) continue
                if (consumeHourPeriod()) continue
                if (consumeMinutePeriod()) continue
                if (consumeSecondPeriod()) continue
                if (consumeTime()) continue

                if (index == startIdx) {
                    index++
                }
            }

            // 提取剩余文本作为事件
            eventDescription = text.substring(index).filter { it !in IGNORED_CHARS }

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

    /**
     * 规范化文本：转换中文数字为阿拉伯数字
     */
    private fun normalizeText(text: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]

            when {
                char in CN_NUM -> {
                    val num = CN_NUM[char]!!
                    var j = i + 1
                    var hasUnit = false
                    var value = num

                    // 查看后续是否有单位
                    while (j < text.length) {
                        val next = text[j]
                        if (next in CN_UNIT) {
                            val unit = CN_UNIT[next]!!
                            if (unit == 10) {
                                // 十的特殊处理
                                if (value == 0) {
                                    value = 10
                                } else {
                                    value = value * 10
                                }
                                hasUnit = true
                                j++
                            } else if (unit == 100 || unit == 1000 || unit == 10000) {
                                value = value * unit
                                hasUnit = true
                                j++
                            } else {
                                break
                            }
                        } else {
                            break
                        }
                    }

                    if (value == 0 && !hasUnit) {
                        result.append("0")
                    } else {
                        result.append(value)
                    }
                    i = j
                }
                char.isDigit() || char.isWhitespace() -> {
                    result.append(char)
                    i++
                }
                char in ":：.-" -> {
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
        while (start < text.length && text[start] in IGNORED_CHARS) {}
        var end = start
        while (end < text.length && text[end] !in IGNORED_CHARS && text[end] !in " \t\n\r,，、；;:：.()-（）[]{}") {
            end++
        }
        return text.substring(start, end)
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

        // 每隔X天/小时
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

            // 每天/每周/每月/每年
            when {
                consume("天") -> {
                    repeatFields["days"] = 1
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("周") -> {
                    repeatFields["weeks"] = 1
                    if (consumeWord("一", "二", "三", "四", "五", "六", "日", "天")) {
                        val dayName = peekWord().let { if (it.length == 1) it else it.last().toString() }
                        if (dayName in WEEKDAY_NAMES) {
                            deltaFields["weekday"] = WEEKDAY_NAMES[dayName]!!
                        }
                    }
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("月") -> {
                    repeatFields["months"] = 1
                    consumeDay()
                    setDefaultTimeIfNeeded()
                    return true
                }
                consume("年") -> {
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

    private fun consumeYearPeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        when {
            remaining.startsWith("今年") -> {
                index += 2
                deltaFields["years"] = 0
                return true
            }
            remaining.startsWith("明年") -> {
                index += 2
                deltaFields["years"] = 1
                return true
            }
            remaining.startsWith("后年") -> {
                index += 2
                deltaFields["years"] = 2
                return true
            }
        }

        // X年后
        val num = consumeDigit()
        if (num != null && remaining.startsWith("年后") || remaining.startsWith("年以后")) {
            index += 2
            deltaFields["years"] = num
            return true
        }

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

        // X个月后
        val num = consumeDigit()
        if (num != null && (remaining.startsWith("个月后") || remaining.startsWith("个月以后"))) {
            index += 3
            deltaFields["months"] = num
            return true
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
            remaining.startsWith("今晚") -> {
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
                val num = consumeDigit()
                if (num != null) {
                    skipWhitespace()
                    if (consume("天后")) {
                        deltaFields["days"] = num
                        setDefaultTimeIfNeeded()
                        return true
                    }
                    if (consume("天") && (consumeWord("后") || consumeWord("以后"))) {
                        deltaFields["days"] = num
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

        // 下周X
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

        // 周X / 星期X / 礼拜X
        val thisWeekDays = listOf(
            "周一" to Calendar.MONDAY, "周二" to Calendar.TUESDAY,
            "周三" to Calendar.WEDNESDAY, "周四" to Calendar.THURSDAY,
            "周五" to Calendar.FRIDAY, "周六" to Calendar.SATURDAY,
            "周日" to Calendar.SUNDAY, "周天" to Calendar.SUNDAY,
            "星期" to -1, "礼拜" to -1
        )

        for ((prefix, _) in thisWeekDays) {
            if (remaining.startsWith(prefix)) {
                index += prefix.length
                // 检查是否是 "星期X" 或 "礼拜X" 格式
                if ((prefix == "星期" || prefix == "礼拜") && remaining.length > prefix.length) {
                    val nextChar = remaining[prefix.length]
                    for ((name, day) in WEEKDAY_NAMES) {
                        if (nextChar == name[0]) {
                            deltaFields["weekday"] = day
                            index++
                            setDefaultTimeIfNeeded()
                            return true
                        }
                    }
                } else if (prefix == "周" || prefix == "礼拜") {
                    // 周X 格式
                    for ((name, day) in WEEKDAY_NAMES) {
                        if (remaining.startsWith("周$name")) {
                            deltaFields["weekday"] = day
                            index++
                            setDefaultTimeIfNeeded()
                            return true
                        }
                    }
                }
                // 回到index，因为prefix可能是"星期"但没有后续
                index -= prefix.length
            }
        }

        // 单独处理 "星期X" 和 "礼拜X"
        if (remaining.startsWith("星期一") || remaining.startsWith("礼拜一")) {
            index += 3; deltaFields["weekday"] = Calendar.MONDAY; setDefaultTimeIfNeeded(); return true
        }
        if (remaining.startsWith("星期二") || remaining.startsWith("礼拜二")) {
            index += 3; deltaFields["weekday"] = Calendar.TUESDAY; setDefaultTimeIfNeeded(); return true
        }
        if (remaining.startsWith("星期三") || remaining.startsWith("礼拜三")) {
            index += 3; deltaFields["weekday"] = Calendar.WEDNESDAY; setDefaultTimeIfNeeded(); return true
        }
        if (remaining.startsWith("星期四") || remaining.startsWith("礼拜四")) {
            index += 3; deltaFields["weekday"] = Calendar.THURSDAY; setDefaultTimeIfNeeded(); return true
        }
        if (remaining.startsWith("星期五") || remaining.startsWith("礼拜五")) {
            index += 3; deltaFields["weekday"] = Calendar.FRIDAY; setDefaultTimeIfNeeded(); return true
        }
        if (remaining.startsWith("星期六") || remaining.startsWith("礼拜六")) {
            index += 3; deltaFields["weekday"] = Calendar.SATURDAY; setDefaultTimeIfNeeded(); return true
        }
        if (remaining.startsWith("星期日") || remaining.startsWith("星期天") || remaining.startsWith("礼拜日") || remaining.startsWith("礼拜天")) {
            index += 3; deltaFields["weekday"] = Calendar.SUNDAY; setDefaultTimeIfNeeded(); return true
        }

        return false
    }

    private fun consumeYear(): Boolean {
        skipWhitespace()
        val num = consumeDigit() ?: return false
        if (!consume("年") && !consume("-") && !consume("/") && !consume(".")) return false

        skipWhitespace()
        // 如果解析了月份
        if (consumeMonth()) {
            skipWhitespace()
            consumeDay()
        }

        timeFields["year"] = num
        return true
    }

    private fun consumeMonth(): Boolean {
        skipWhitespace()
        // 先尝试中文月份
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

        // 数字月份
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

        // 先尝试中文日期
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

        // 数字日期
        val num = consumeDigit() ?: return false
        if (num !in 1..31) return false

        skipWhitespace()
        consume("号") || consume("日")
        consume("(") // 处理括号中的星期
        if (consumeWord("周", "星期", "礼拜")) {
            val dayName = peekWord().let { if (it.length == 1) it else it.last().toString() }
            if (dayName in WEEKDAY_NAMES) {
                deltaFields["weekday"] = WEEKDAY_NAMES[dayName]!!
            }
        }
        consume(")") || consume("）")

        timeFields["day"] = num
        return true
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
                    if (consumeWord("后") || consumeWord("以后")) {
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
                    if (consumeWord("后") || consumeWord("以后")) {
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
            index += if (remaining.startsWith("一会儿")) 3 else 2
            deltaFields["minutes"] = 10
            return true
        }

        // X分钟后
        val count = consumeDigit()
        if (count != null) {
            skipWhitespace()
            if (consume("分钟") || consume("分")) {
                if (consumeWord("后") || consumeWord("以后")) {
                    deltaFields["minutes"] = count
                    return true
                }
                deltaFields["minutes"] = count
                return true
            }
        }

        return false
    }

    private fun consumeSecondPeriod(): Boolean {
        skipWhitespace()
        val remaining = text.substring(index)

        // X秒后
        val count = consumeDigit()
        if (count != null) {
            skipWhitespace()
            if (consume("秒") || consume("秒钟")) {
                if (consumeWord("后") || consumeWord("以后")) {
                    deltaFields["seconds"] = count
                    return true
                }
                deltaFields["seconds"] = count
                return true
            }
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
            consume("晚上") -> {
                afternoonFlag = true
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                consumed = true
            }
        }

        // 解析具体时间 8点03 / 8:03 / 8点 / 8:30
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
                    !timeFields.containsKey("hour") && deltaFields.isEmpty() && !deltaFields.containsKey("days")) {
                    finalHour += 12
                }

                timeFields["hour"] = finalHour

                // 解析分钟 - 可能直接连着(如"03")
                skipWhitespace()
                if (text.length > index && text[index].isDigit()) {
                    val minute = consumeDigit()
                    if (minute != null && minute <= 59) {
                        timeFields["minute"] = minute
                        consume("分") || consume("分钟")
                    }
                } else {
                    timeFields["minute"] = timeFields["minute"] ?: 0
                }

                return true
            } else if (hour <= 23) {
                timeFields["hour"] = hour
                timeFields["minute"] = timeFields["minute"] ?: 0
                return true
            }
        }

        // 解析分钟单独出现(半)
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

    private fun calculateResult(): Triple<TriggerType, String, Long> {
        val calendar = now.clone() as Calendar

        // 应用年份
        if (deltaFields.containsKey("years")) {
            calendar.add(Calendar.YEAR, deltaFields["years"]!!)
        }

        // 应用月份
        if (deltaFields.containsKey("months")) {
            calendar.add(Calendar.MONTH, deltaFields["months"]!!)
        }

        // 应用天数
        val days = deltaFields["days"] ?: 0
        calendar.add(Calendar.DAY_OF_YEAR, days)

        // 处理星期几
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

        // 应用具体时间
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

        // 应用秒
        if (deltaFields.containsKey("seconds")) {
            calendar.add(Calendar.SECOND, deltaFields["seconds"]!!)
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
