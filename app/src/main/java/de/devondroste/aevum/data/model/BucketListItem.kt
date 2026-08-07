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
 *
 * WICHTIG (M18.39-Crash-Fix): Die Indices MÜSSEN hier deklariert sein,
 * weil MIGRATION_19_20 sie erstellt. Room validiert nach der Migration
 * die DB-Struktur gegen dieses Entity-Schema — nicht-deklarierte
 * Indices fuehren zu IllegalStateException beim DB-Oeffnen (Crash).
 */
@Entity(
    tableName = "bucket_list_item",
    indices = [
        androidx.room.Index("completed"),
        androidx.room.Index("created_at")
    ]
)
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
    // M18.43: Schwierigkeitsgrad 1-5 (Gamification). Bestimmt die XP-
    // Belohnung beim Abhaken: 1 Stern = 10 XP, 5 Sterne = 50 XP.
    val difficulty: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    /** M18.43: XP-Belohnung für dieses Item (10 pro Schwierigkeits-Stern). */
    val xpReward: Int get() = difficulty.coerceIn(1, 5) * 10
}
