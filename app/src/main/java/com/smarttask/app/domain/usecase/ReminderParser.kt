package com.smarttask.app.domain.usecase

import com.smarttask.app.domain.model.Reminder
import com.smarttask.app.domain.model.TriggerType
import java.util.*

/**
 * 自然语言提醒解析器
 * 基于 wx_bot scheduler_parser 设计的算法，将中文自然语言解析为定时任务
 *
 * 支持的时间表达：
 * - 今天/明天/后天 + 时间
 * - 周一/周二/.../周日 + 时间
 * - 下周一/下周二/.../下周日
 * - 上午/下午/早上/晚上 + 时间
 * - X分钟后/小时后/天后
 * - 每天/每周/每月/每年
 * - 具体日期时间
 */
class ReminderParser {

    companion object {
        private val CN_NUM = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
            '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '两' to 2
        )

        private val CN_UNIT = mapOf(
            '十' to 10, '拾' to 10, '百' to 100, '千' to 1000,
            '万' to 10000, '亿' to 100000000
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

    private var index = 0
    private var words: List<String> = emptyList()
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

    /**
     * 解析自然语言输入
     * @param input 如："明天下午3点45分提醒我吃午饭"
     * @return ParseResult 解析结果
     */
    fun parse(input: String): ParseResult {
        // 重置状态
        index = 0
        words = emptyList()
        timeFields.clear()
        deltaFields.clear()
        repeatFields.clear()
        afternoonFlag = false
        eventDescription = ""

        return try {
            // 1. 预处理：中文数字转换
            val normalized = normalizeChineseNumber(input)

            // 2. 分词
            words = tokenize(normalized)

            // 3. 逐步解析
            index = 0
            while (index < words.size) {
                val startIdx = index

                // 按优先级尝试解析各种时间表达
                val consumed = consumeRepeat()
                    || consumeRelativeDay()
                    || consumeWeekday()
                    || consumeHourPeriod()
                    || consumeMinutePeriod()
                    || consumeHour()
                    || consumeMinute()

                if (index != startIdx) {
                    // 时间找到，提取剩余文本作为事件
                    eventDescription = words.subList(index, words.size)
                        .filter { it !in IGNORED_WORDS }
                        .joinToString("")
                    break
                }
                index++
            }

            // 4. 如果没找到任何时间表达，抛出错误
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

            // 5. 计算触发时间和类型
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
     * 将中文数字转换为阿拉伯数字
     * 三百八十二 -> 382
     */
    private fun normalizeChineseNumber(text: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]

            // 处理"十几"这种特殊表达
            if (char == '十' && i + 1 < text.length && text[i + 1].isDigit()) {
                result.append("十")
                i++
                continue
            }

            when {
                char in CN_NUM -> {
                    var num = CN_NUM[char]!!
                    var unit = 1

                    // 查看下一个字符是否是单位
                    if (i + 1 < text.length) {
                        val nextChar = text[i + 1]
                        if (nextChar in CN_UNIT) {
                            unit = CN_UNIT[nextChar]!!
                            if (unit == 10 && num == 0) {
                                // 十这种情况
                                result.append("10")
                            } else {
                                result.append(num * unit)
                            }
                            i++
                        } else if (nextChar == '十') {
                            // 十几的情况
                            result.append(num * 10 + (text.getOrNull(i + 2)?.let { CN_NUM[it] } ?: 0))
                            i += 2
                        } else {
                            result.append(num)
                        }
                    } else {
                        result.append(num)
                    }
                }
                char in CN_UNIT -> {
                    val unit = CN_UNIT[char]!!
                    if (unit == 10) {
                        result.append("10")
                    } else {
                        result.append(unit)
                    }
                }
                char.isDigit() || char.isWhitespace() || char in ":：.-" -> {
                    result.append(char)
                }
                else -> {
                    result.append(char)
                }
            }
            i++
        }

        return result.toString()
    }

    /**
     * 简单分词：按空格和标点分割
     */
    private fun tokenize(text: String): List<String> {
        return text.split(Regex("[\\s,，、；;\\(\\)\\[\\]（）()]+"))
            .filter { it.isNotBlank() }
    }

    private fun currentWord(): String {
        return words.getOrNull(index) ?: ""
    }

    private fun peekNext(): String {
        return words.getOrNull(index + 1) ?: ""
    }

    private fun hasNext(): Boolean {
        return index < words.size
    }

    private fun advance() {
        index++
    }

    /**
     * 消费重复标记：每天/每周/每月/每年
     */
    private fun consumeRepeat(): Boolean {
        if (currentWord() != "每" && currentWord() != "每隔") {
            return false
        }

        val startIdx = index
        advance() // 消费"每"

        when (currentWord()) {
            "天" -> {
                repeatFields["days"] = 1
                advance()
                // 默认时间
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 8
                    timeFields["minute"] = 0
                }
                return true
            }
            "周" -> {
                repeatFields["weeks"] = 1
                advance()
                // 解析具体是周几
                if (currentWord() in WEEKDAY_NAMES) {
                    val weekday = WEEKDAY_NAMES[currentWord()]!!
                    deltaFields["weekday"] = weekday
                    advance()
                }
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 8
                    timeFields["minute"] = 0
                }
                return true
            }
            "月" -> {
                repeatFields["months"] = 1
                advance()
                // 解析具体日期
                consumeDay()
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 8
                    timeFields["minute"] = 0
                }
                return true
            }
            "年" -> {
                repeatFields["years"] = 1
                advance()
                // 解析具体日期
                consumeMonth()
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 8
                    timeFields["minute"] = 0
                }
                return true
            }
            "小时", "钟头" -> {
                advance()
                val count = consumeDigit() ?: 1
                repeatFields["hours"] = count
                return true
            }
            "间隔" -> {
                advance()
                val count = consumeDigit() ?: 1
                advance() // 消费"个"
                when (currentWord()) {
                    "天" -> {
                        repeatFields["days"] = count
                        advance()
                        return true
                    }
                    "小时" -> {
                        repeatFields["hours"] = count
                        advance()
                        return true
                    }
                    "分钟", "分" -> {
                        repeatFields["minutes"] = count
                        advance()
                        return true
                    }
                }
            }
        }

        index = startIdx
        return false
    }

    /**
     * 消费相对天数：今天/明天/后天/3天后
     */
    private fun consumeRelativeDay(): Boolean {
        val word = currentWord()

        when (word) {
            "今天" -> {
                deltaFields["days"] = 0
                advance()
                setDefaultTimeIfNeeded()
                return true
            }
            "明天", "明儿" -> {
                deltaFields["days"] = 1
                advance()
                setDefaultTimeIfNeeded()
                return true
            }
            "后天" -> {
                deltaFields["days"] = 2
                advance()
                setDefaultTimeIfNeeded()
                return true
            }
            "大后天" -> {
                deltaFields["days"] = 3
                advance()
                setDefaultTimeIfNeeded()
                return true
            }
            "昨晚", "今晚" -> {
                deltaFields["days"] = 0
                afternoonFlag = true
                advance()
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                return true
            }
            "明晚" -> {
                deltaFields["days"] = 1
                afternoonFlag = true
                advance()
                if (!timeFields.containsKey("hour")) {
                    timeFields["hour"] = 20
                    timeFields["minute"] = 0
                }
                return true
            }
        }

        // 处理"X天后"
        val digit = consumeDigit()
        if (digit != null && (currentWord() == "天" && peekNext() == "后")) {
            advance() // 消费"天"
            advance() // 消费"后"
            deltaFields["days"] = digit
            setDefaultTimeIfNeeded()
            return true
        } else if (digit != null && currentWord() == "天后") {
            advance() // 消费"天后"
            deltaFields["days"] = digit
            setDefaultTimeIfNeeded()
            return true
        } else if (digit != null && currentWord() == "天") {
            advance() // 消费"天"
            if (currentWord() == "后" || currentWord() == "以后") {
                advance()
                deltaFields["days"] = digit
            } else {
                // "3天早上" 这种
                deltaFields["days"] = digit
            }
            setDefaultTimeIfNeeded()
            return true
        }

        return false
    }

    /**
     * 消费星期几
     */
    private fun consumeWeekday(): Boolean {
        val word = currentWord()

        // 处理下周X
        if (word.startsWith("下")) {
            val weekdayWord = word.drop(1)
            if (weekdayWord in WEEKDAY_NAMES) {
                advance()
                deltaFields["weekday"] = WEEKDAY_NAMES[weekdayWord]!!
                deltaFields["weekDelta"] = 1
                setDefaultTimeIfNeeded()
                return true
            }
        }

        // 处理本周
        if (word == "本周") {
            advance()
            if (currentWord() in WEEKDAY_NAMES) {
                deltaFields["weekday"] = WEEKDAY_NAMES[currentWord()]!!
                advance()
                setDefaultTimeIfNeeded()
                return true
            }
        }

        // 处理周X
        if (word == "周" || word == "星期" || word == "礼拜") {
            advance()
            if (currentWord() in WEEKDAY_NAMES) {
                deltaFields["weekday"] = WEEKDAY_NAMES[currentWord()]!!
                advance()
                setDefaultTimeIfNeeded()
                return true
            }
            // 处理"周一"这种
            for ((name, value) in WEEKDAY_NAMES) {
                if (word == "周$name") {
                    deltaFields["weekday"] = value
                    return true
                }
            }
        }

        return false
    }

    /**
     * 消费小时段：X小时后/半小时后
     */
    private fun consumeHourPeriod(): Boolean {
        val startIdx = index

        // 半小时后
        if (currentWord() == "半" && peekNext() in listOf("小时", "钟头", "个", "")) {
            advance()
            if (currentWord() == "个") advance()
            if (currentWord() in listOf("小时", "钟头")) advance()
            if (currentWord() == "后" || currentWord() == "以后") advance()
            deltaFields["minutes"] = 30
            return true
        }

        // X个半小时后
        val digit = consumeDigit()
        if (digit != null) {
            if (currentWord() == "个" && peekNext() == "半") {
                advance() // 消费"个"
                advance() // 消费"半"
                if (currentWord() in listOf("小时", "钟头")) advance()
                if (currentWord() == "后" || currentWord() == "以后") advance()
                deltaFields["hours"] = digit
                deltaFields["minutes"] = 30
                return true
            }

            if (currentWord() == "小时" || currentWord() == "钟头") {
                advance()
                if (currentWord() == "后" || currentWord() == "以后") advance()
                if (currentWord() == "半") {
                    advance()
                    deltaFields["hours"] = digit
                    deltaFields["minutes"] = 30
                } else {
                    deltaFields["hours"] = digit
                }
                return true
            }

            // 1点半小时 - 恢复
            index = startIdx
        }

        return false
    }

    /**
     * 消费分钟段：X分钟后
     */
    private fun consumeMinutePeriod(): Boolean {
        val startIdx = index

        // 等会/一会儿
        if (currentWord() in listOf("等会", "一会", "一会儿")) {
            advance()
            deltaFields["minutes"] = 10
            return true
        }

        val digit = consumeDigit()
        if (digit != null) {
            if (currentWord() == "分" || currentWord() == "分钟") {
                advance()
                if (currentWord() == "后" || currentWord() == "以后") advance()
                deltaFields["minutes"] = digit
                return true
            } else {
                index = startIdx
            }
        }

        return false
    }

    /**
     * 消费具体时间：上午/下午/早上/3点/15:30
     */
    private fun consumeHour(): Boolean {
        val startIdx = index
        var consumed = false

        when (currentWord()) {
            "凌晨", "深夜", "半夜" -> {
                afternoonFlag = false
                deltaFields["days"] = (deltaFields["days"] ?: 0) + 1
                timeFields["hour"] = 0
                timeFields["minute"] = 0
                advance()
                consumed = true
            }
            "早上", "早晨", "上午", "今早" -> {
                afternoonFlag = false
                timeFields["hour"] = 8
                timeFields["minute"] = 0
                advance()
                consumed = true
            }
            "中午" -> {
                afternoonFlag = false
                timeFields["hour"] = 12
                timeFields["minute"] = 0
                advance()
                consumed = true
            }
            "下午" -> {
                afternoonFlag = true
                timeFields["hour"] = 13
                timeFields["minute"] = 0
                advance()
                consumed = true
            }
            "傍晚" -> {
                afternoonFlag = true
                timeFields["hour"] = 18
                timeFields["minute"] = 0
                advance()
                consumed = true
            }
            "晚上", "今晚" -> {
                afternoonFlag = true
                timeFields["hour"] = 20
                timeFields["minute"] = 0
                advance()
                consumed = true
            }
        }

        // 解析具体数字时间
        val hour = consumeDigit()
        if (hour != null) {
            val nextWord = currentWord()
            if (nextWord == "点" || nextWord == "点钟" || nextWord == "时" ||
                nextWord == ":" || nextWord == "：" || nextWord == ".") {
                advance()

                var finalHour = hour

                // 12小时制转24小时制
                if (afternoonFlag && hour < 12) {
                    finalHour += 12
                } else if (!afternoonFlag && hour == 0 && timeFields.containsKey("hour") && timeFields["hour"] == 0) {
                    // 凌晨0点已经是第二天
                } else if (!afternoonFlag && hour < 12 && now.get(Calendar.HOUR_OF_DAY) >= 12 &&
                    !timeFields.containsKey("hour") && !deltaFields.containsKey("days")) {
                    // 当前是下午，用户说"3点"默认是下午3点
                    finalHour += 12
                }

                timeFields["hour"] = finalHour

                // 解析分钟
                val minute = consumeDigit()
                if (minute != null) {
                    timeFields["minute"] = minute
                    if (currentWord() == "分" || currentWord() == "分钟") {
                        advance()
                    }
                } else {
                    timeFields["minute"] = 0
                }

                return true
            } else if (hour <= 23 && (nextWord == "" || nextWord == " ")) {
                // 直接是数字
                timeFields["hour"] = hour
                timeFields["minute"] = 0
                return true
            } else {
                index = startIdx + 1
            }
        }

        return consumed
    }

    /**
     * 消费分钟
     */
    private fun consumeMinute(): Boolean {
        if (currentWord() == "半") {
            advance()
            timeFields["minute"] = 30
            return true
        }

        val digit = consumeDigit()
        if (digit != null && digit <= 59) {
            if (currentWord() == "分" || currentWord() == "分钟") {
                advance()
            }
            timeFields["minute"] = digit
            return true
        }

        return false
    }

    /**
     * 消费日期
     */
    private fun consumeDay(): Boolean {
        val digit = consumeDigit()
        if (digit != null) {
            if (currentWord() == "号" || currentWord() == "日") {
                advance()
            }
            if (digit in 1..31) {
                timeFields["day"] = digit
                return true
            }
        }
        return false
    }

    /**
     * 消费月份
     */
    private fun consumeMonth(): Boolean {
        val digit = consumeDigit()
        if (digit != null && currentWord() == "月") {
            advance()
            if (digit in 1..12) {
                timeFields["month"] = digit
                return true
            }
        }
        return false
    }

    private fun consumeDigit(): Int? {
        val word = currentWord()
        if (word.isNotEmpty() && word.all { it.isDigit() }) {
            advance()
            return word.toInt()
        }
        return null
    }

    private fun setDefaultTimeIfNeeded() {
        if (!timeFields.containsKey("hour")) {
            timeFields["hour"] = 8
            timeFields["minute"] = 0
        }
    }

    private fun calculateResult(): Triple<TriggerType, String, Long> {
        val calendar = now.clone() as Calendar

        // 应用时间增量
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
                // 如果是今天但已经过了中午，默认下周
                daysUntilWeekday = 7
            }

            calendar.add(Calendar.DAY_OF_YEAR, daysUntilWeekday)
        }

        // 应用具体时间
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

        // 检查是否是重复提醒
        return when {
            repeatFields["years"] != null -> {
                val month = timeFields["month"] ?: 1
                val day = timeFields["day"] ?: 1
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(
                    TriggerType.YEARLY,
                    "$month-$day $hour:$minute",
                    calendar.timeInMillis
                )
            }
            repeatFields["months"] != null -> {
                val day = timeFields["day"] ?: 1
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(
                    TriggerType.MONTHLY,
                    "$day $hour:$minute",
                    calendar.timeInMillis
                )
            }
            repeatFields["weeks"] != null -> {
                val weekday = deltaFields["weekday"] ?: Calendar.MONDAY
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(
                    TriggerType.WEEKLY,
                    "$weekday $hour:$minute",
                    calendar.timeInMillis
                )
            }
            repeatFields["days"] != null -> {
                val hour = timeFields["hour"] ?: 8
                val minute = timeFields["minute"] ?: 0
                Triple(
                    TriggerType.DAILY,
                    "$hour:$minute",
                    calendar.timeInMillis
                )
            }
            repeatFields["hours"] != null -> {
                val minutes = repeatFields["hours"]!! * 60
                Triple(
                    TriggerType.INTERVAL,
                    "$minutes",
                    calendar.timeInMillis
                )
            }
            repeatFields["minutes"] != null -> {
                Triple(
                    TriggerType.INTERVAL,
                    "${repeatFields["minutes"]}",
                    calendar.timeInMillis
                )
            }
            else -> {
                // 单次提醒
                Triple(
                    TriggerType.ONCE,
                    calendar.timeInMillis.toString(),
                    calendar.timeInMillis
                )
            }
        }
    }
}
