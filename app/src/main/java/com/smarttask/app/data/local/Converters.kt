package com.smarttask.app.data.local

import androidx.room.TypeConverter
import com.smarttask.app.domain.model.ActionType
import com.smarttask.app.domain.model.TriggerType

class Converters {
    @TypeConverter
    fun fromTriggerType(value: TriggerType): String = value.name

    @TypeConverter
    fun toTriggerType(value: String): TriggerType = TriggerType.valueOf(value)

    @TypeConverter
    fun fromActionType(value: ActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ActionType = ActionType.valueOf(value)
}
