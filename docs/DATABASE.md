# DATABASE — Aevum

## Strategie

Aevum nutzt **Room** als lokale Source of Truth und **DataStore** für Einstellungen. Keine Cloud, kein Backend, kein Login.

## Kernentitäten

### `life_profile`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | meist `default` |
| `birth_date` | TEXT NULL | für Lebensfortschritt |
| `life_expectancy_years` | INTEGER NULL | konfigurierbarer Wert |
| `ideal_week_json` | TEXT NULL | optionale ideale Zeitverteilung |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |

### `category`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Kategorie-ID |
| `name` | TEXT | Arbeit, Sport, Schlaf, Lernen usw. |
| `color` | TEXT | Hex-Farbe |
| `icon` | TEXT | Symbolname |
| `is_system` | INTEGER | Systemkategorie ja/nein |
| `sort_order` | INTEGER | Anzeige-Reihenfolge |

### `tag`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Tag-ID |
| `name` | TEXT | frei definierbar |
| `color` | TEXT NULL | optionale Farbe |

### `activity_session`

Bestätigte oder vorgeschlagene Lebenszeit-Blöcke.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Session-ID |
| `title` | TEXT | bearbeitbarer Titel |
| `category_id` | TEXT FK | Kategorie |
| `start_at` | INTEGER | Start millis |
| `end_at` | INTEGER NULL | Ende millis, NULL = aktuell laufend |
| `description` | TEXT NULL | Notiz |
| `source` | TEXT | MANUAL, GEOFENCE, ACTIVITY, SLEEP, USAGE, MERGED |
| `confidence` | REAL | 0.0–1.0 |
| `status` | TEXT | CANDIDATE, CONFIRMED, DISMISSED |
| `is_user_edited` | INTEGER | Nutzer hat geändert |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |

### `activity_session_tag`

| Feld | Typ | Zweck |
|---|---|---|
| `session_id` | TEXT FK | Aktivität |
| `tag_id` | TEXT FK | Tag |

### `raw_detection_event`

Unveränderte Signale der Android APIs.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Event-ID |
| `source` | TEXT | GEOFENCE, ACTIVITY_RECOGNITION, HEALTH_CONNECT, USAGE_STATS |
| `type` | TEXT | ENTER, EXIT, IN_VEHICLE, SLEEP_SESSION usw. |
| `payload_json` | TEXT | Rohdaten/Details |
| `occurred_at` | INTEGER | Zeitpunkt |
| `processed_at` | INTEGER NULL | Verarbeitung |

### `place_geofence`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Ort-ID |
| `name` | TEXT | Arbeit, Gym usw. |
| `category_id` | TEXT FK | Standardkategorie |
| `latitude` | REAL | lat |
| `longitude` | REAL | lon |
| `radius_meters` | REAL | Radius |
| `enabled` | INTEGER | aktiv |

### `goal`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Ziel-ID |
| `title` | TEXT | z. B. 2h lernen |
| `category_id` | TEXT NULL | Zielkategorie |
| `tag_id` | TEXT NULL | optionaler Tag |
| `target_minutes` | INTEGER | Soll-Dauer |
| `period` | TEXT | DAILY, WEEKLY, MONTHLY, CUSTOM |
| `start_date` | TEXT | Start |
| `end_date` | TEXT NULL | Ende |
| `status` | TEXT | ACTIVE, PAUSED, DONE, ARCHIVED |

### `habit`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Habit-ID |
| `title` | TEXT | Name |
| `category_id` | TEXT NULL | verknüpfte Aktivität |
| `frequency_type` | TEXT | DAILY, WEEKLY, TIMES_PER_WEEK, CUSTOM |
| `target_count` | INTEGER | z. B. 3x |
| `target_minutes` | INTEGER NULL | optional Dauer |
| `active` | INTEGER | aktiv |

### `habit_log`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Log-ID |
| `habit_id` | TEXT FK | Habit |
| `date` | TEXT | Tag |
| `status` | TEXT | DONE, MISSED, PARTIAL, AUTO_DONE |
| `source_session_id` | TEXT NULL | automatisch erkannte Session |

### `bucket_list_item`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Item-ID |
| `title` | TEXT | Titel |
| `description` | TEXT NULL | Beschreibung |
| `image_uri` | TEXT NULL | lokales Bild |
| `target_date` | TEXT NULL | optionales Datum |
| `status` | TEXT | IDEA, PLANNED, IN_PROGRESS, DONE, ARCHIVED |
| `progress_percent` | INTEGER | 0–100 |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |

### `app_usage_sample`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Sample-ID |
| `package_name` | TEXT | App-Paket |
| `app_label` | TEXT | Anzeigename |
| `start_at` | INTEGER | Start |
| `end_at` | INTEGER | Ende |
| `duration_ms` | INTEGER | Dauer |

## Indizes

Für performante Abfragen über jahrelange Datenmengen:

```sql
-- Aktivitätssessions: Hauptabfragen sind zeitbasiert
CREATE INDEX idx_activity_session_start_at ON activity_session(start_at);
CREATE INDEX idx_activity_session_end_at ON activity_session(end_at);
CREATE INDEX idx_activity_session_category_start ON activity_session(category_id, start_at);
CREATE INDEX idx_activity_session_status_start ON activity_session(status, start_at);

-- Raw Detection Events: Nach Quelle und Zeit filtern
CREATE INDEX idx_raw_detection_source_occurred ON raw_detection_event(source, occurred_at);

-- Habit Logs: Nach Habit und Datum
CREATE INDEX idx_habit_log_habit_date ON habit_log(habit_id, date);

-- App Usage: Zeitbasiert und nach App
CREATE INDEX idx_app_usage_start_pkg ON app_usage_sample(start_at, package_name);

-- Goals: Nach Status und Zeitraum
CREATE INDEX idx_goal_status_period ON goal(status, period);
```

## DataStore

- Onboarding abgeschlossen
- Theme
- Permission-Erklärstatus
- Dashboard-Konfiguration
- Privacy-Einstellungen

## Migrationen

Ab DB-Version 1 werden Migrationen testpflichtig. Keine destruktiven Migrationen in Release.

**Migrationstest-Strategie:**

- Jede Schemaänderung erhält eine `Migration`-Implementierung
- Test für `runMigrationsAndValidate()` für jede Version
- Destruktive Migrationen nur in Debug

## Performance bei jahrelangen Daten

| Aspekt | Strategie |
|---|---|
| Zeitreihenabfragen | Komposite Indizes (category+start, status+start) |
| Aggregationen | Tagesweise Vorabaggregation in separater Tabelle für Jahre-Ansichten |
| Partitionierung | Monatliche Tabellen optional ab 5 Jahren Daten |
| Paging | PagingSource für Timeline, LazyColumn für UI |
| Cleanup | WorkManager Job archiviert Sessions > 10 Jahre auf Wunsch |

## Room Entity Design Principles

1. **Stabile PKs:** UUID/ULID als TEXT, keine autoIncrement
2. **Explizite FKs:** `@ForeignKey` in Entity-Definitionen
3. **Keine Businesslogik in Entities:** reine Daten
4. **Time in Millis:** INTEGER UTC für alle Zeitstempel
5. **Nullable nur wo sinnvoll:** `end_at` NULL = laufend
6. **Enum als TEXT:** `source`, `status`, `period` etc. als TEXT für Lesbarkeit

## M2 Implementierungsstand

Die Room-Grundstruktur ist implementiert:

- 12 Entity-Dateien unter `app/src/main/java/de/devondroste/aevum/data/model/`
- 12 DAO/DB-Dateien unter `app/src/main/java/de/devondroste/aevum/data/db/`
- Repository-Interfaces und Implementierungen unter `data/repository/`
- `AppDatabase` Version 1
- Hilt-Provider für Datenbank, DAOs und Repositories

Die Struktur ist für M2 bewusst basisfähig, nicht final fachlich vollständig. M4 stabilisiert die Fachlogik, Seed-Daten, Migrationstests und DAO-Abfragen weiter.

## Konfliktlösung & Reconciliation

- `raw_detection_event` bleibt unverändert (Audit Trail)
- Classification Pipeline schreibt `activity_session` mit `status=CANDIDATE`
- User Edit → `status=CONFIRMED`, `is_user_edited=1`
- Reconciliation Worker korrigiert offene Sessions (fehlende Exits) täglich