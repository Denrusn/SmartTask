# wx_bot 提醒设置机制分析

## 概述

wx_bot 是一个基于微信的提醒机器人，其核心功能是将用户的自然语言描述解析成具体的定时任务。本文档详细分析其提醒设置机制。

---

## 核心组件

### 1. scheduler_parser.py - 自然语言解析器

**文件位置**: `api_server/utils/scheduler_parser.py`

这是最核心的模块，负责将自然语言转换成定时任务数据。

#### 技术选型
- **分词**: jieba（中文分词库）+ 词性标注（pseg）
- **时间计算**: dateutil.relativedelta（处理月份/年份等复杂时间计算）
- **时区**: zoneinfo（Python标准库）

#### 中文数字转换
```python
CN_NUM = {
    '零': 0, '一': 1, '二': 2, ... '九': 9,
    '壹': 1, '贰': 2, ... '玖': 9,
    '两': 2, '貮': 2
}

CN_UNIT = {
    '十': 10, '百': 100, '千': 1000, '万': 10000, '亿': 100000000
}
```

示例: `三百八十二` => `382`

#### 解析器类 `LocalParser`

**主要属性**:
```python
self.now                    # 当前时间（上海时区）
self.time_fields = {}       # 解析出的时间字段 {year, month, day, hour, minute}
self.time_delta_fields = {} # 时间增量 {days, hours, months, years}
self.repeat = {}            # 重复周期 {day: 1, hour: 2}
self.do_what = ''           # 解析出的事件描述
```

**主解析流程 `parse_by_rules()`**:
```
1. 分词处理（jieba + HMM=False）
2. 循环解析直到文本结束：
   a. consume_repeat()          - 解析"每天/每周/每月"等重复标记
   b. consume_year_period()     - 解析"明年/后年/3年后"
   c. consume_month_period()    - 解析"下个月/3个月后"
   d. consume_day_period()      - 解析"今天/明天/3天后"
   e. consume_weekday_period()   - 解析"周一/下周一"
   f. consume_hour_period()     - 解析"2小时后/半小时后"
   g. consume_minute_period()   - 解析"30分钟后"
   h. consume_hour()            - 解析具体时间"下午3点"
   i. consume_minute()          - 解析分钟"3点45分"
   j. consume_second()          - 解析秒
   k. consume_to_end()          - 提取剩余文本作为事件描述
3. 组装结果 {time, repeat, desc, event}
```

#### 支持的时间表达

| 类型 | 示例 | 解析结果 |
|------|------|----------|
| 今天/明天/后天 | 今天下午3点 | +0天 15:00 |
| 相对天数 | 3天后/两周后 | +3天/+14天 |
| 星期几 | 周五下午3点/下周一 | 特定星期 |
| 月份日期 | 每月20号/下个月3号 | 每月20日 |
| 年份日期 | 明年5月15号 | 2027-05-15 |
| 时间段 | 下午/早上/晚上 | 13:00/08:00/20:00 |
| 相对时间 | 半小时后/2小时后 | +30min/+2hour |
| 重复周期 | 每天/每周/每月 | repeat参数 |

#### 时间歧义处理

1. **下午/上午自动判断**:
   - 如果当前时间是下午，且用户说"3点"，自动+12小时
   - 使用`self.afternoon`标志记录上下文

2. **凌晨/深夜**:
   - `凌晨/半夜/深夜` => 次日 + 0:00
   - `今晚` => 当天 + 20:00

3. **默认时间**:
   - 未指定时间时，默认 08:00

#### 错误处理

```python
class ParseError(ValueError):
    pass
```

示例错误信息：
- `/:no亲，时间或者日期超范围了`
- `/:no亲，一年哪有%s个月！`
- `/:no亲，暂不支持设置连续时间段的提醒`

#### ignore_words.txt - 忽略词

这些词在分词时被强制设置为最低优先级，避免干扰：

```
号叫/星期日/周日/周天/今天下午/每个/每年/每月...
```

---

### 2. reminder_service.py - 提醒服务

**文件位置**: `api_server/views_services/reminder_service.py`

#### 添加提醒 `add_reminder()`

```python
async def add_reminder(who, reminder_time, repeat, description, event,
                       client_username, nickname):
    # 1. 将repeat + reminder_time 转换为 APscheduler trigger
    trigger_info = await repeat2trigger(repeat, reminder_time, description)

    # 2. 生成唯一task_id
    task_id = str(uuid.uuid4())

    # 3. 添加到APScheduler
    job = scheduler.add_job(
        func=execute_reminder,
        trigger=trigger,
        id=task_id,
        kwargs={...}
    )

    # 4. 返回自然语言响应
    return {"status": 1, "msg": "提醒添加成功！", "content": "✅将在 2小时后 提醒你吃午饭..."}
```

#### Trigger类型转换 `repeat2trigger()`

| repeat参数 | trigger类型 | 说明 |
|------------|-------------|------|
| `{'days': 1}` | CronTrigger | 每天执行 |
| `{'days': 2}` | CronTrigger(day='*/2') | 每2天执行 |
| `{'months': 1}` | CronTrigger | 每月执行 |
| `{'years': 1}` | CronTrigger | 每年执行 |
| `{'hours': 2}` | IntervalTrigger | 每2小时执行 |
| `{}` (空) | DateTrigger | 单次执行 |

#### 执行提醒 `execute_reminder()`

```python
def execute_reminder(task_id, event, description, who, repeat, ...):
    # 1. 构造提醒消息
    reminder_msg = f"🕒 {event}\n\n备注: {description}\n..."

    # 2. 通过RabbitMQ发送消息
    routing_key = f"{client_username}.{nickname}.remind"
    publish_to_topic_exchange(routing_key, payload, ...)
```

#### 查询和删除

- `get_all_reminders()` - 获取所有任务
- `get_all_reminders_by_who(who)` - 获取某人的所有提醒
- `delete_reminder(who, num)` - 按序号删除提醒

---

### 3. scheduler.py - 任务调度器

**文件位置**: `api_server/utils/scheduler.py`

基于 **APScheduler** 构建：

```python
scheduler = BackgroundScheduler(
    jobstores={'default': SQLAlchemyJobStore(url=database_url)},
    executors={'default': {'type': 'threadpool', 'max_workers': 20}},
    job_defaults={
        'coalesce': True,           # 合并错过的触发
        'max_instances': 3,         # 同一任务最多3个实例
        'misfire_grace_time': 300   # 5分钟宽限期
    },
    timezone='Asia/Shanghai'
)
```

#### 事件监听

```python
# 监听任务添加/删除事件，同步到PostgreSQL
scheduler.add_listener(job_listener, EVENT_JOB_ADDED | EVENT_JOB_REMOVED)
```

#### 数据库表 `reminder_scheduler`

```sql
CREATE TABLE reminder_scheduler (
    who VARCHAR,              -- 用户名
    reminder_time TIMESTAMP, -- 提醒时间
    repeat JSON,              -- 重复周期
    description TEXT,         -- 原始描述
    event TEXT,               -- 事件
    trigger_type VARCHAR,     -- trigger类型
    task_id VARCHAR,          -- 任务ID
    type VARCHAR,
    client_username VARCHAR,
    nickname VARCHAR,
    create_time TIMESTAMP DEFAULT NOW()
);
```

---

## 数据流

```
用户输入: "明天下午3点45分提醒我吃午饭"
                    │
                    ▼
        ┌─────────────────────┐
        │  LocalParser        │
        │  parse_by_rules()   │
        │                     │
        │  1. jieba分词       │
        │  2. 逐步解析时间    │
        │  3. 计算最终时间    │
        └─────────┬───────────┘
                  │
                  ▼
        {
            'time': 2026-04-20 15:45:00,
            'repeat': {},
            'desc': '明天下午3点45分提醒我吃午饭',
            'event': '吃午饭'
        }
                  │
                  ▼
        ┌─────────────────────┐
        │  repeat2trigger()   │
        │                     │
        │  空repeat =>        │
        │  DateTrigger        │
        └─────────┬───────────┘
                  │
                  ▼
        ┌─────────────────────┐
        │  scheduler.add_job │
        │                     │
        │  添加APScheduler任务│
        └─────────┬───────────┘
                  │
                  ▼
        ┌─────────────────────┐
        │  job_listener       │
        │                     │
        │  同步到PG数据库     │
        └─────────────────────┘
                  │
                  ▼
        ┌─────────────────────┐
        │  响应用户           │
        │                     │
        │  "✅将在 明天下午  │
        │   3点45分 提醒你   │
        │   吃午饭"          │
        └─────────────────────┘
```

---

## 关键设计亮点

### 1. 分层解析策略
- 按时间相关性和优先级逐步解析
- 每个`consume_*`方法负责一种时间表达
- 解析成功返回消耗的字符数，失败返回0

### 2. 上下文感知
- 使用`self.afternoon`标志处理下午/上午歧义
- 使用`self.now`记录基准时间
- 自动判断"今晚3点"是当天还是次日

### 3. 中文数字处理
- 完整的 中文数字=>阿拉伯数字 映射
- 支持"三百八十二"=>382、"两万"=>20000
- 支持大小写混合（壹、贰 vs 一、二）

### 4. 自然语言响应
- 使用`nature_time()`将时间差转成人能理解的文字
- 示例: "2小时30分钟后"

### 5. 健壮的错误处理
- 每种时间表达都有范围校验
- 友好的错误提示
- 不支持的功能明确告知用户

---

## 局限性

1. **不支持农历/节假日**
2. **不支持时间段**（如"周一至周五"）
3. **不支持分钟级重复**
4. **不支持工作日提醒**
5. **不支持复杂的跨年规则**

---

## 示例测试用例 (test_reminder.py)

```python
# 基础时间解析
'下午3点45分提醒我还钱'                    # 时间 + 事件
'明天九点58提醒秒杀流量'                    # 明天 + 时间
'今晚八点半导体制冷片'                      # 今晚 + 时间
'周五下午提醒我发信息给花花'                # 星期几

# 相对时间
'一分钟后提醒我'                           # 1分钟后
'两个半小时后提醒我同步'                   # 2.5小时后
'三天后提醒我'                              # 3天后
'三个月后的早上提醒我写代码'               # 3个月后

# 重复提醒
'每天晚上八点'                              # 每天
'每周一上午10点'                            # 每周
'每月20号提醒我还信用卡'                    # 每月
'每年1月22号生日'                          # 每年

# 特殊表达
'下个月三号早上写代码'                     # 下个月 + 日期
'明天(周四)晚上19点'                       # 日期 + 星期
'2017年11月12日 11:00 安美宝一包'          # 完整日期时间
```
