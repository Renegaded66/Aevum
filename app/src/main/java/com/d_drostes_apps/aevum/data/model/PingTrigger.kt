package com.d_drostes_apps.aevum.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M18.61g: Ping-Trigger — überwacht die Erreichbarkeit einer IP-Adresse
 * (z.B. FireTV im Heimnetz). Sobald die IP antwortet, wird eine Activity
 * automatisch gestartet; sobald sie nicht mehr antwortet, wird sie beendet.
 */
@Entity(
    tableName = "ping_trigger",
    indices = [Index(value = ["ipAddress"], unique = true)]
)
data class PingTrigger(
    @PrimaryKey val id: String,
    val name: String,
    val ipAddress: String,
    val activityTypeId: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
