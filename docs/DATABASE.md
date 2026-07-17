# DATABASE

## Strategie

Gewählt: **Room** für lokale Persistenz und **DataStore** für Einstellungen. Ohne konkrete Fachdomäne bleibt das fachliche Schema offen. Geplant ist eine local-first Struktur, die später Sync-fähig erweitert werden kann.

## Basistabellen

### `analytics_event`

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | UUID/Event-ID |
| `name` | TEXT | Eventname |
| `properties_json` | TEXT | optionale Properties |
| `created_at` | INTEGER | Timestamp millis |
| `synced` | INTEGER | optionaler Syncstatus |

### `sync_operation` — optional bei Cloud/Backend

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Operation-ID |
| `entity_type` | TEXT | Fachobjekt-Typ |
| `entity_id` | TEXT | Fachobjekt-ID |
| `operation` | TEXT | CREATE/UPDATE/DELETE |
| `payload_json` | TEXT | Nutzdaten |
| `retry_count` | INTEGER | Anzahl Versuche |
| `last_error` | TEXT NULL | letzter Fehler |
| `created_at` | INTEGER | erstellt |
| `next_retry_at` | INTEGER NULL | Backoff-Zeit |

## DAO-Regeln

- Reads als `Flow<T>`
- Writes als `suspend`
- Transaktionen bei zusammengesetzten Änderungen
- keine Businesslogik im DAO
- Migrationen ab Version 1 konsequent testen

## Migrationen

- Jede Schemaänderung bekommt eine Migration.
- Destruktive Migration nur in Debug, nicht Release.
- Migration Tests für nichttriviale Änderungen.

## Alternativen

| Option | Bewertung |
|---|---|
| Room | Gewählt: stabil, Jetpack, Flow, testbar |
| SQLite direkt | zu viel Boilerplate |
| Realm/ObjectBox | zusätzliche Abhängigkeit/Vendor-Risiko |
| Cloud-only | schlechtere Offline-UX |

## Nach M1 zu ergänzen

- Fachentitäten
- Beziehungen und Indizes
- Lösch-/Exportkonzept
- Datenschutzklassifikation
