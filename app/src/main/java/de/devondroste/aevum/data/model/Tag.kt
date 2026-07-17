package de.devondroste.aevum.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "tag")
data class Tag(
    @PrimaryKey
    val id: String,
    val name: String,
    val color: String? = null
) : Serializable