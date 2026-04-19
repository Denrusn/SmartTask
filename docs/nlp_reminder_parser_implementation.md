# SmartTask 自然语言提醒解析器实现建议

基于 wx_bot 的 scheduler_parser.py 分析，为 SmartTask Android 应用提供 Kotlin 实现建议。

---

## 核心思路

wx_bot 使用**状态机 + 逐步解析**策略：
1. 分词处理（中文数字转换）
2. 按优先级逐步尝试解析各种时间表达
3. 解析成功后提取剩余文本作为事件描述

---

## Kotlin 实现架构

### 1. 数据模型

```kotlin
data class ParsedReminder(
    val triggerTime: Long,        // 触发时间（毫秒时间戳）
    val triggerType: TriggerType, // ONCE / DAILY / WEEKLY / MONTHLY / YEARLY / INTERVAL
    val repeatValue: Int? = null, // 重复间隔值
    val repeatUnit: TimeUnit? = null, // 重复单位
    val event: String,            // 事件描述
    val rawInput: String          // 原始输入
)

enum class TimeUnit {
    MINUTE, HOUR, DAY, WEEK, MONTH, YEAR
}

class ParseException(message: String) : Exception(message)
```

### 2. 中文数字转换

```kotlin
private val CN_NUM = mapOf(
    '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
    '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9, '两' to 2
)

private val CN_UNIT = mapOf(
    '十' to 10, '百' to 100, '千' to 1000, '万' to 10000, '亿' to 100000000
)

fun parseChineseNumber(text: String): String {
    var result = 0
    var tempNum = 0
    for (char in text) {
        when {
            char in CN_NUM -> tempNum = CN_NUM[char]!!
            char in CN_UNIT -> {
                val unit = CN_UNIT[char]!!
                if (unit == 10) {
                    result += if (tempNum == 0) 10 else tempNum * 10
                    tempNum = 0
                } else {
                    result += tempNum * unit
                    tempNum = 0
                }
            }
        }
    }
    result += tempNum
    return result.toString()
}
```

### 3. 解析器类设计

```kotlin
class ReminderParser {

    private var index = 0
    private var words: List<Pair<String, String>> = emptyList() // word to tag
    private val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))

    private val timeFields = mutableMapOf<String, Int>()
    private val deltaFields = mutableMapOf<String, Int>()
    private val repeatFields = mutableMapOf<String, Int>()

    private var afternoonFlag = false
    private var eventDescription = ""

    fun parse(input: String): ParsedReminder {
        // 1. 预处理：中文数字转换
        val normalized = normalizeChineseNumber(input)

        // 2. 分词（简单实现，可考虑使用 IKAnalyzer 或 ANSJ）
        words = tokenize(normalized)

        // 3. 逐步解析
        index = 0
        while (hasNext()) {
            val startIdx = index

            // 按优先级尝试解析
            consumeRepeat() ||
            consumeYearPeriod() ||
            consumeMonthPeriod() ||
            consumeDayPeriod() ||
            consumeWeekdayPeriod() ||
            consumeHourPeriod() ||
            consumeMinutePeriod() ||
            consumeHour() ||
            consumeMinute()

            if (index != startIdx) {
                // 时间找到，提取剩余文本作为事件
                eventDescription = words.subList(index, words.size)
                    .filter { it.first !in IGNORED_CHARS }
                    .joinToString("") { it.first }
                break
            }
            index++
        }

        // 4. 计算最终时间
        val triggerTime = calculateTriggerTime()

        // 5. 转换为 Reminder 模型
        return buildParsedReminder(triggerTime)
    }

    private fun normalizeChineseNumber(text: String): String {
        // 使用正则替换中文数字
        // 例如：三百八十二 -> 382
    }

    private fun tokenize(text: String): List<Pair<String, String>> {
        // 简单分词实现
        // 实际可使用：ansj_seg / IKAnalyzer / jieba-android
    }
}
```

### 4. 时间段解析函数

```kotlin
// 解析 "今天/明天/后天"
private fun consumeDayPeriod(): Boolean {
    return when {
        consumeWord("今天") -> deltaFields["days"] = 0
        consumeWord("明天") -> deltaFields["days"] = 1
        consumeWord("后天") -> deltaFields["days"] = 2
        consumeWord("大后天") -> deltaFields["days"] = 3
        else -> {
            val num = consumeDigit() ?: return false
            if (consumeWord("天") && consumeWord("后")) {
                deltaFields["days"] = num
            } else return false
        }
    }
    // 默认时间设为 08:00
    if (!timeFields.containsKey("hour")) {
        timeFields["hour"] = 8
        timeFields["minute"] = 0
    }
    return true
}

// 解析 "下午3点/早上10点"
private fun consumeHour(): Boolean {
    var consumed = false

    when {
        consumeWord("凌晨") || consumeWord("深夜") -> {
            afternoonFlag = false
            deltaFields["days"] = (deltaFields["days"] ?: 0) + 1
            timeFields["hour"] = 0
            consumed = true
        }
        consumeWord("早上") || consumeWord("早晨") || consumeWord("上午") -> {
            afternoonFlag = false
            timeFields["hour"] = 8
            consumed = true
        }
        consumeWord("中午") -> {
            afternoonFlag = false
            timeFields["hour"] = 12
            consumed = true
        }
        consumeWord("下午") -> {
            afternoonFlag = true
            timeFields["hour"] = 13
            consumed = true
        }
        consumeWord("晚上") || consumeWord("今晚") -> {
            afternoonFlag = true
            timeFields["hour"] = 20
            consumed = true
        }
    }

    // 解析具体时间 "3点" / "15:30"
    val hour = consumeDigit() ?: return consumed
    if (!consumeWord("点", "点钟", "时", "：", ":")) {
        index-- // 回退
        return consumed
    }

    var finalHour = hour
    if (afternoonFlag && hour < 12) {
        finalHour += 12
    }

    timeFields["hour"] = finalHour

    // 解析分钟
    val minute = consumeDigit()
    if (minute != null) {
        consumeWord("分", "：", ":")
        timeFields["minute"] = minute
    } else {
        timeFields["minute"] = 0
    }

    return true
}

// 解析 "周一/周五/下周一"
private fun consumeWeekdayPeriod(): Boolean {
    val weekdayNames = mapOf(
        "一" to 0, "二" to 1, "三" to 2, "四" to 3,
        "五" to 4, "六" to 5, "日" to 6, "天" to 6
    )

    if (consumeWord("周", "星期", "礼拜")) {
        val name = consumeWord(*weekdayNames.keys.toTypedArray())
        if (name != null) {
            deltaFields["weekday"] = weekdayNames[name]!!
            return true
        }
    }

    if (consumeWord("下周一", "下周二", "下周三", "下周四", "下周五", "下周六", "下周日")) {
        // 处理下周逻辑
        return true
    }

    return false
}
```

### 5. 重复周期解析

```kotlin
// 解析 "每天/每周/每月/每年"
private fun consumeRepeat(): Boolean {
    if (!consumeWord("每")) return false

    when {
        consumeWord("天") -> {
            repeatFields["days"] = 1
            consumeHour() // 设置默认时间
        }
        consumeWord("周") -> {
            repeatFields["weeks"] = 1
            consumeHour()
        }
        consumeWord("月") -> {
            repeatFields["months"] = 1
            consumeDay()
        }
        consumeWord("年") -> {
            repeatFields["years"] = 1
            consumeMonth()
        }
        consumeWord("小时", "钟头") -> {
            repeatFields["hours"] = consumeDigit() ?: 1
        }
        else -> {
            index-- // 回退
            return false
        }
    }
    return true
}
```

### 6. 时间计算

```kotlin
private fun calculateTriggerTime(): Long {
    val calendar = now.clone() as Calendar

    // 应用时间增量
    deltaFields["years"]?.let { calendar.add(Calendar.YEAR, it) }
    deltaFields["months"]?.let { calendar.add(Calendar.MONTH, it) }
    deltaFields["days"]?.let { calendar.add(Calendar.DAY_OF_MONTH, it) }
    deltaFields["hours"]?.let { calendar.add(Calendar.HOUR_OF_DAY, it) }
    deltaFields["minutes"]?.let { calendar.add(Calendar.MINUTE, it) }

    // 应用具体时间
    timeFields["year"]?.let { calendar.set(Calendar.YEAR, it) }
    timeFields["month"]?.let { calendar.set(Calendar.MONTH, it - 1) } // Calendar 月份从0开始
    timeFields["day"]?.let { calendar.set(Calendar.DAY_OF_MONTH, it) }
    timeFields["hour"]?.let { calendar.set(Calendar.HOUR_OF_DAY, it) }
    timeFields["minute"]?.let { calendar.set(Calendar.MINUTE, it) }
    timeFields["second"]?.let { calendar.set(Calendar.SECOND, it) }
    timeFields["second"] ?: calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)

    return calendar.timeInMillis
}
```

---

## 支持的时间表达

| 输入示例 | 解析结果 |
|----------|----------|
| 今天下午3点提醒我吃饭 | 触发时间: 今天 15:00, 事件: "吃饭" |
| 明天早上8点提醒我拿快递 | 触发时间: 明天 08:00, 事件: "拿快递" |
| 周五下午提醒我开会 | 触发时间: 本周五 08:00, 事件: "开会" |
| 每天晚上8点提醒我运动 | 触发时间: 今天 20:00, 重复: DAILY |
| 每周一上午10点提醒我上课 | 触发时间: 下周一 10:00, 重复: WEEKLY |
| 每月20号提醒我还信用卡 | 触发时间: 本月20号 08:00, 重复: MONTHLY |
| 每年1月1号提醒我新年快乐 | 触发时间: 明年1月1号 08:00, 重复: YEARLY |
| 半小时后提醒我 | 触发时间: 30分钟后, 事件: "" |
| 2小时后提醒我喝水 | 触发时间: 2小时后, 事件: "喝水" |
| 下个月3号提醒我交报告 | 触发时间: 下月3号 08:00, 事件: "交报告" |

---

## 错误处理

```kotlin
fun parse(input: String): Result<ParsedReminder> {
    return try {
        val result = ReminderParser().parse(input)
        Result.success(result)
    } catch (e: Exception) {
        Result.failure(ParseException(e.message ?: "解析失败"))
    }
}

// 友好的错误信息
private fun validateTime() {
    val month = timeFields["month"]
    if (month != null && month > 12) {
        throw ParseException("一年哪有${month}个月！")
    }

    val day = timeFields["day"]
    if (day != null && day > 31) {
        throw ParseException("一个月哪有${day}天！")
    }

    val hour = timeFields["hour"]
    if (hour != null && hour > 24) {
        throw ParseException("一天哪有${hour}小时！")
    }
}
```

---

## 建议的 Android 分词库

1. **ansj_seg** - Java 实现，支持中文分词和词性标注
2. **IKAnalyzer** - 流行的中文分词器，有 Android 适配版本
3. **jieba-android** - jieba 的 Android 移植版本

---

## 参考测试用例

```kotlin
// 基础解析
assertParse("下午3点提醒我还钱", triggerHour = 15, event = "提醒我还钱")
assertParse("明天九点58提醒秒杀流量", triggerDay = 1, triggerHour = 9, triggerMinute = 58)
assertParse("今晚八点半导体制冷片", triggerHour = 20, triggerMinute = 30)
assertParse("周五下午提醒我发信息给花花", weekday = FRIDAY, eventPart = "发信息给花花")

// 相对时间
assertParse("一分钟后提醒我", deltaMinutes = 1)
assertParse("两个半小时后提醒我同步", deltaMinutes = 150)
assertParse("三天后提醒我", deltaDays = 3)

// 重复提醒
assertParse("每天晚上八点", repeatType = DAILY, triggerHour = 20)
assertParse("每周一上午10点", repeatType = WEEKLY, weekday = MONDAY, triggerHour = 10)
assertParse("每月20号提醒我还信用卡", repeatType = MONTHLY, dayOfMonth = 20)
assertParse("每年1月22号生日", repeatType = YEARLY, month = 1, dayOfMonth = 22)

// 特殊表达
assertParse("下个月三号早上写代码", deltaMonths = 1, dayOfMonth = 3)
assertParse("明天(周四)晚上19点", deltaDays = 1, triggerHour = 19)
assertParse("2017年11月12日 11:00 安美宝一包", year = 2017, month = 11, dayOfMonth = 12)
```
