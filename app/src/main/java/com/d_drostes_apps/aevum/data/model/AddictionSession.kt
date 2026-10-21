package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M27: Sucht-Detektor — eine konkrete Vordergrund-Sitzung einer
 * beobachteten App.
 *
 * Warum eigene Sitzungen statt nur Tages-Summen:
 *  - „wie oft pro Tag geöffnet" braucht die Anzahl der Sitzungen,
 *  - „wie lange war JEDE Session" braucht Start + Dauer,
 *  - der Verlauf über Tage/Wochen/Monate lässt sich aus den Sitzungen
 *    verlustfrei aggregieren (auch nachträglich für andere Zeitfenster).
 *
 * Die ID ist `packageName@startAt`. Ein erneuter Scan derselben
 * Vordergrund-Phase aktualisiert damit dieselbe Zeile (offene Sitzung
 * wächst bis zum nächsten Scan) statt Duplikate anzulegen.
 *
 * [startAt] ist der Zeitpunkt, an dem die App in den Vordergrund kam;
 * [durationMs] ist die volle Sitzungsdauer. Für Tages-Summen wird die
 * Sitzung an Mitternacht geclippt (siehe
 * [com.d_drostes_apps.aevum.domain.addiction.AddictionAnalytics]).
 */
@Entity(
    tableName = "addiction_session",
    indices = [
        Index(value = ["package_name"]),
        Index(value = ["start_at"])
    ]
)
data class AddictionSession(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long
)
