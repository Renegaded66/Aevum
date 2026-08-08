package com.d_drostes_apps.aevum.data.converters

import androidx.room.TypeConverter
import java.util.Date

object Converters {
    @TypeConverter
    fun fromDate(value: Date?): Long? {
        return value?.time
    }

    @TypeConverter
    fun toDate(value: Long?): Date? {
        return value?.let { Date(it) }
    }
}