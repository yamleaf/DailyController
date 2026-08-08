package com.yample.daily.controller

/** 控制端解析被控端快照后使用的本地模型 */
data class DeviceSnapshot(
    val device: Map<String, String> = emptyMap(),
    val runtime: Map<String, String> = emptyMap(),
    val calendar: CalendarSnapshot = CalendarSnapshot(),
    val settings: List<SettingItem> = emptyList(),
    val statuses: List<StatusItem> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val batterySeries: List<BatteryPoint> = emptyList(),
    val syncedAt: Long = 0L
)

/** B5：被控端电池采样序列（{ts, level}），用于绘制电池曲线 */
data class BatteryPoint(val ts: Long, val level: Int)

data class CalendarSnapshot(
    val punched: String = "0",
    val scheduled: String = "0",
    val missed: String = "0",
    val recentPunch: String = "—",
    val today: String = "—",
    val days: List<CalendarDay> = emptyList()
)

data class CalendarDay(
    val date: String,
    val weekday: Int,
    val status: String,
    val label: String
)

data class SettingItem(
    val key: String,
    val label: String,
    val type: String,        // "bool" | "int" | "slider"
    var value: Any,
    val writable: Boolean,
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val options: List<Int>? = null   // 离散档位（如伪息屏延迟）；存在时滑块按档位索引映射，否则用 min/max/step 连续滑块
)

data class StatusItem(
    val key: String,
    val label: String,
    val value: String
)

data class TaskItem(
    val id: Int,
    val time: String,
    val name: String = "",      // 任务名称/备注（多任务命名，可区分不同时间点）
    val actualTime: String? = null,
    val status: String = "pending",
    val statusLabel: String = "待执行"
)

data class HistoryItem(
    val time: String,
    val result: String
)
