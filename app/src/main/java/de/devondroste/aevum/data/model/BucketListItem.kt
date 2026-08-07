package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * M18.39: Bucket-List-Eintrag — "Was willst du im Leben unbedingt machen?"
 *
 * Felder (nach Recherche der besten Bucket-List-Apps):
 *  - title: Titel (Pflicht)
 *  - location: Ort (optional) — "Nordlichter, Island"
 *  - icon: Emoji-Icon (optional) — visuelle Wiedererkennung
 *  - category: Kategorie (optional) — Reisen, Abenteuer, Lernen, ...
 *  - targetDate: optionales Zieldatum (ISO) — "bis 2030"
 *  - completed: erledigt ja/nein
 *  - completedAt: wann erledigt (für die "geschafft"-Chronik)
 *  - imagePath: optionales Bild (App-interner Speicher, Dateipfad)
 *  - notes: Notizen (optional)
 */
@Entity(tableName = "bucket_list_item")
data class BucketListItem(
    @PrimaryKey val id: String,
    val title: String,
    val location: String? = null,
    val icon: String? = null,
    val category: String? = null,
    @ColumnInfo(name = "target_date") val targetDate: String? = null,
    val completed: Boolean = false,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
