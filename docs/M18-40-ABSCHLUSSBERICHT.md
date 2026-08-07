# M18.40 — CRASH-FIX: Room-Schema-Validierung schlug bei bucket_list_item fehl

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## Root Cause (gewiss)

**M18.39** (Bucket-List-Feature) führte die neue Tabelle `bucket_list_item` ein:

- **MIGRATION_19_20** erstellte die Tabelle **mit 2 Indices** (`index_bucket_list_item_completed`, `index_bucket_list_item_created_at`)
- Die **Entity `BucketListItem` deklarierte aber KEINE Indices** (Schema 20.json: `INDICES: []`)

**Room validiert nach jeder Migration die DB-Struktur gegen das Entity-Schema.** Nicht-deklarierte Indices → `IllegalStateException: Migration didn't properly handle bucket_list_item` → **Crash beim DB-Öffnen**.

## Warum das Symptom exakt passt

- **Dashboard kurz sichtbar:** Die Compose-UI rendert sofort mit leerem State
- **Keine Daten laden:** Der erste Flow-Collect (z.B. `activityRepository.getOverlappingRange`) öffnet die DB → Migration 19→20 läuft → Validierung schlägt fehl → Exception
- **Dann Crash:** Die Exception propagiert beim ersten DB-Zugriff

## Fix

Indices in der Entity deklariert:

```kotlin
@Entity(
    tableName = "bucket_list_item",
    indices = [
        androidx.room.Index("completed"),
        androidx.room.Index("created_at")
    ]
)
```

**Verifiziert:** Das generierte Schema 20.json enthält jetzt beide Indices — Schema und Migration stimmen überein.

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (35s)
- Commit: `6990cfc`

## Test-Anleitung
1. App öffnen → Dashboard lädt Daten, kein Crash
2. Settings → Erweitert → "Bucket List 🌍" → Eintrag anlegen → funktioniert
