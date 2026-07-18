# DATABASE — Aevum

## M4 Pre-Review Ergebnis

Der bisherige M2-Entwurf war als technische Grundlage richtig, aber fachlich noch zu grob für eine App, die über Jahre erweitert werden soll. Der Review zeigt: **Zeitintervalle müssen das zentrale kanonische Datenmodell sein**, während Sensoren, Android APIs, Kandidaten, Nutzerbestätigungen, Ziele, Habits und Statistiken sauber getrennt bleiben.

Wichtigste Architekturentscheidung für M4:

> **Alles, was Lebenszeit beschreibt, wird als Zeitintervall modelliert.**

Schlaf, Arbeit, Autofahrt, Lernen, Handy-Nutzung, Fitnessstudio, Meditation und Lesen sind nicht separate Primärtabellen, sondern unterschiedliche Ausprägungen einer allgemeinen `activity_session`. Quellenspezifische Details bleiben in Raw-/Detection-/Evidence-Tabellen.

## Review des bisherigen Modells

### Was gut ist

- `activity_session` als zeitbasierter Kern ist die richtige Richtung.
- `raw_detection_event` als Audit Trail verhindert Datenverlust und erlaubt Reprocessing.
- Kategorien und Tags sind getrennt und damit erweiterbar.
- Goals/Habits referenzieren Kategorien/Tags statt fest codierter Aktivitätstypen.
- Zeitstempel als UTC millis sind performant und migrationsfreundlich.
- Aggregationen werden als Cache verstanden, nicht als Source of Truth.

### Schwächen vor M4

| Bereich | Schwäche | Risiko | M4-Korrektur |
|---|---|---|---|
| Candidates vs. Wahrheit | `activity_session.status=CANDIDATE` vermischt Vorschläge und bestätigte Lebenszeit | Timeline/Stats könnten versehentlich Kandidaten einbeziehen | Separate `activity_candidate` Tabelle; `activity_session` wird Nutzerwahrheit |
| Raw Events | `raw_detection_event` ist zu unspezifisch für spätere Sensoren/Wearables | schwerere Deduplikation/Reprocessing | `source_id`, `external_id`, `schema_version`, `ingested_at`, `payload_json` |
| Evidenz | Keine explizite Verbindung zwischen Session und Rohsignalen | Debugging/KI/Erklärbarkeit leiden | `session_evidence` Join-Tabelle mit Gewicht/Reason |
| App Usage | `app_usage_sample` steht neben Sessions | Smartphone-Nutzung wird Sonderfall | App Usage bleibt Raw/Detection; bestätigte Digitalzeit kann ebenfalls Session sein |
| Goals/Habits | Zieldefinition nur `target_minutes`, `category_id`, `tag_id` | neue Zieltypen brauchen Schemaänderungen | flexible Rule-/Filter-Felder per JSON + typisierte Kernfelder |
| Migrationen | `exportSchema=false` | Migrationstests verlieren Schemahistorie | M4 setzt `exportSchema=true` und Schema-Verzeichnis |
| Statistik | Noch keine klare Cache-Strategie | langsame mehrjährige Reports | Tages-/Perioden-Caches als ableitbare Tabellen |
| Sync-Zukunft | Keine Change-Metadaten | Multi-Device später schwierig | `created_at`, `updated_at`, `deleted_at`, `revision`, optional `origin_device_id` |
| Historische Nachvollziehbarkeit | finale Session überschreibt ggf. den ursprünglichen Vorschlag | spätere KI/Reprocessing/Debugging verliert Lernsignal | `activity_session_change` als kleine Historie plus `created_by`, `updated_by`, `supersedes_session_id` |

## Zielmodell ab M4

### Ebenenmodell

```text
Sensor / externe Quelle
  -> raw_source_event          // unveränderter Audit Trail
  -> detection_event           // normalisiertes Android-/Sensor-Ereignis
  -> activity_candidate        // vorgeschlagener Zeitblock
  -> activity_session          // bestätigte/manuelle Nutzerwahrheit
  -> aggregierte Statistik     // ableitbarer Cache
```

### 1. `data_source`

Registriert jede Datenquelle. Dadurch können Wear OS, Kalender, Health Connect Erweiterungen oder zukünftige Android APIs ergänzt werden, ohne das Schema jedes Mal zu ändern.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | z. B. `phone_activity_recognition`, `health_connect`, `wear_os` |
| `type` | TEXT | `ANDROID_API`, `HEALTH_CONNECT`, `WEAR_OS`, `CALENDAR`, `MANUAL`, `IMPORT` |
| `name` | TEXT | Anzeigename |
| `enabled` | INTEGER | aktiv/inaktiv |
| `permission_state` | TEXT | `UNKNOWN`, `GRANTED`, `DENIED`, `REVOKED` |
| `last_sync_at` | INTEGER NULL | letzter Import |
| `config_json` | TEXT NULL | quellenspezifische Einstellungen |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |

### 2. `raw_source_event`

Unveränderte, möglichst vollständige Rohdaten. Diese Tabelle ist ein Audit- und Reprocessing-Log, nicht die fachliche Wahrheit.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | ULID/UUID |
| `source_id` | TEXT FK | Datenquelle |
| `external_id` | TEXT NULL | ID der Quelle, falls vorhanden |
| `event_type` | TEXT | quellenspezifischer Typ |
| `observed_at` | INTEGER | Zeitpunkt des Signals |
| `start_at` | INTEGER NULL | falls Quelle bereits ein Intervall liefert |
| `end_at` | INTEGER NULL | falls Quelle bereits ein Intervall liefert |
| `timezone_id` | TEXT NULL | Kontext für spätere lokale Tageslogik |
| `payload_json` | TEXT | Originaldaten / normalisierte Rohdetails |
| `schema_version` | INTEGER | Payload-Version |
| `ingested_at` | INTEGER | Importzeitpunkt |
| `processed_at` | INTEGER NULL | Pipeline verarbeitet |

Indizes:

```sql
CREATE UNIQUE INDEX idx_raw_source_external ON raw_source_event(source_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_raw_source_observed ON raw_source_event(source_id, observed_at);
CREATE INDEX idx_raw_processed ON raw_source_event(processed_at);
```

### 3. `detection_event`

Normalisierte Erkennungsereignisse aus Raw Events. Beispiele: Geofence Enter/Exit, Activity Transition, Sleep Session, App Usage Interval, Calendar Busy Block.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Event-ID |
| `raw_event_id` | TEXT FK NULL | Ursprung |
| `source_id` | TEXT FK | Quelle |
| `kind` | TEXT | `GEOFENCE_ENTER`, `IN_VEHICLE`, `SLEEP`, `APP_USAGE`, `CALENDAR_BUSY` |
| `start_at` | INTEGER | Start oder Eventzeitpunkt |
| `end_at` | INTEGER NULL | optionales Ende |
| `confidence` | REAL | 0.0–1.0 |
| `place_id` | TEXT NULL | Ort/Geofence |
| `metadata_json` | TEXT NULL | Zusatzdaten |
| `created_at` | INTEGER | erstellt |

Indizes:

```sql
CREATE INDEX idx_detection_kind_time ON detection_event(kind, start_at);
CREATE INDEX idx_detection_source_time ON detection_event(source_id, start_at);
```

### 4. `activity_candidate`

Ein automatisch vorgeschlagener Lebenszeitblock. Kandidaten sind **nicht** die Nutzerwahrheit und dürfen nicht ungefiltert in Statistiken einfließen.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Candidate-ID |
| `suggested_title` | TEXT | Vorschlagstitel |
| `suggested_category_id` | TEXT FK NULL | vorgeschlagene Kategorie |
| `activity_type_id` | TEXT FK NULL | semantischer Aktivitätstyp |
| `start_at` | INTEGER | Start |
| `end_at` | INTEGER | Ende |
| `confidence` | REAL | 0.0–1.0 |
| `status` | TEXT | `PENDING`, `ACCEPTED`, `EDITED`, `DISMISSED`, `MERGED`, `EXPIRED` |
| `reason` | TEXT NULL | kurze Erklärung |
| `created_by` | TEXT | Pipeline/Rule-Version |
| `created_at` | INTEGER | erstellt |
| `resolved_at` | INTEGER NULL | Nutzer/System hat entschieden |
| `resolved_session_id` | TEXT FK NULL | daraus entstandene Session |

### 5. `activity_session`

Kanonische Lebenszeit. Diese Tabelle ist die Grundlage für Timeline, Dashboard, Ziele, Habits, Reports, Export und spätere Sync-Logik.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | stabile ULID/UUID |
| `title` | TEXT | bearbeitbarer Titel |
| `category_id` | TEXT FK NULL | Nutzerkategorie |
| `activity_type_id` | TEXT FK NULL | semantischer Typ, optional |
| `start_at` | INTEGER | Start UTC millis |
| `end_at` | INTEGER NULL | Ende; NULL = laufend |
| `timezone_id` | TEXT | lokale Zeitzone bei Erfassung |
| `description` | TEXT NULL | Notiz |
| `source_type` | TEXT | `MANUAL`, `CONFIRMED_CANDIDATE`, `IMPORT`, `MERGED`, `SPLIT` |
| `created_by` | TEXT | `MANUAL`, `AUTO`, `MERGE`, `IMPORT`, `SYSTEM` |
| `updated_by` | TEXT NULL | letzte Änderungsquelle |
| `source_candidate_id` | TEXT FK NULL | ursprünglicher angenommener Kandidat |
| `supersedes_session_id` | TEXT FK NULL | Vorgänger bei Split/Merge/Neuanlage statt Überschreiben |
| `confidence` | REAL | bei automatischem Ursprung |
| `is_user_edited` | INTEGER | manuell geändert |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |
| `deleted_at` | INTEGER NULL | Soft Delete für Restore/Sync |
| `revision` | INTEGER | Optimistic Sync/Merge später |
| `origin_device_id` | TEXT NULL | Multi-Device später |

Wichtige Regeln:

- `activity_session` enthält bestätigte oder manuell erstellte Lebenszeit.
- Kandidaten bleiben in `activity_candidate`.
- Die finale Session überschreibt nie die historische Herkunft: ursprünglicher Kandidat und spätere Nutzerentscheidung bleiben nachvollziehbar.
- Laufende Aktivität darf `end_at=NULL` haben.
- Abfragen auf Statistiken filtern immer `deleted_at IS NULL` und nur bestätigte Sessions.
- Überlappungen sind erlaubt, aber müssen semantisch bewertet werden: z. B. `Smartphone` kann Overlay sein; `Schlaf` und `Arbeit` sollten nicht überlappen.

Indizes:

```sql
CREATE INDEX idx_session_start ON activity_session(start_at);
CREATE INDEX idx_session_end ON activity_session(end_at);
CREATE INDEX idx_session_category_start ON activity_session(category_id, start_at);
CREATE INDEX idx_session_type_start ON activity_session(activity_type_id, start_at);
CREATE INDEX idx_session_deleted_start ON activity_session(deleted_at, start_at);
CREATE INDEX idx_session_source_candidate ON activity_session(source_candidate_id);
CREATE INDEX idx_session_supersedes ON activity_session(supersedes_session_id);
```

### 6. `activity_session_change`

Kleine Änderungshistorie für bestätigte Sessions. Sie ist bewusst einfach gehalten, aber macht spätere KI-Auswertungen, bessere Erkennungsalgorithmen, Debugging und Reprocessing wertvoller.

Beispiel:

```text
Candidate: Arbeit 08:00–17:00
User Edit: Arbeit 08:15–16:45
```

Die App weiß dadurch:

- ursprünglicher Vorschlag
- finale Nutzerentscheidung
- wer/was geändert hat
- wann geändert wurde
- welche Felder betroffen waren

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Change-ID |
| `session_id` | TEXT FK | betroffene finale Session |
| `change_type` | TEXT | `CREATED`, `USER_EDITED`, `AUTO_REPROCESSED`, `MERGED`, `SPLIT`, `DELETED`, `RESTORED` |
| `changed_by` | TEXT | `MANUAL`, `AUTO`, `MERGE`, `IMPORT`, `SYSTEM` |
| `changed_at` | INTEGER | Änderungszeitpunkt |
| `before_json` | TEXT NULL | Snapshot relevanter Felder vor Änderung |
| `after_json` | TEXT | Snapshot relevanter Felder nach Änderung |
| `reason` | TEXT NULL | optionale Erklärung |
| `source_candidate_id` | TEXT FK NULL | ursprünglicher/auslösender Kandidat |

Indizes:

```sql
CREATE INDEX idx_session_change_session_time ON activity_session_change(session_id, changed_at);
CREATE INDEX idx_session_change_type_time ON activity_session_change(change_type, changed_at);
```

Regeln:

- Bei Erstellung aus Candidate wird ein `CREATED` Change mit Candidate-Snapshot geschrieben.
- Bei Nutzeränderung wird ein `USER_EDITED` Change mit Before/After-Snapshot geschrieben.
- Bei Merge/Split wird die Beziehung zusätzlich über `supersedes_session_id` bzw. Change Records nachvollziehbar.
- Diese Historie ist nicht die primäre Query-Basis für Timeline/Statistiken; sie dient Audit, Lernen, Erklärbarkeit und späterer KI.

### 7. `session_evidence`

Verbindet Kandidaten/Sessions mit den Detection Events, aus denen sie entstanden sind. Das macht automatische Entscheidungen erklärbar und später für lokale KI-Auswertungen nutzbar.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Evidence-ID |
| `session_id` | TEXT FK NULL | bestätigte Session |
| `candidate_id` | TEXT FK NULL | Kandidat |
| `detection_event_id` | TEXT FK | Evidence |
| `weight` | REAL | Einfluss 0.0–1.0 |
| `relationship` | TEXT | `START`, `END`, `SUPPORTS`, `CONFLICTS`, `OVERLAY` |
| `reason` | TEXT NULL | erklärbare Begründung |

### 8. `activity_type`

Semantische Aktivitätstypen sind nicht gleich Kategorien. Kategorien sind nutzerfreundliche Gruppierungen; Activity Types sind stabile technische/semantische Typen.

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | z. B. `sleep`, `work`, `driving`, `meditation` |
| `name` | TEXT | Anzeigename |
| `default_category_id` | TEXT FK NULL | Default-Kategorie |
| `is_system` | INTEGER | Systemtyp |
| `properties_json` | TEXT NULL | Regeln/Overlay/Exklusivität |

Beispiel:

- `driving` kann Kategorie `Transport` sein.
- Nutzer kann denselben Type später einer anderen Kategorie zuordnen.
- Neue Aktivitätstypen entstehen durch Seed-Daten oder Nutzerkonfiguration, nicht durch Schemaänderungen.

### 9. `category`, `tag`, `activity_session_tag`

Bleiben normalisiert.

Kategorien:

- wenige, visuelle Top-Level-Gruppen
- für Dashboard/Charts
- nutzereditierbar

Tags:

- beliebig viele, feinere Bedeutung
- für Ziele, Habits, Filter, Reports

### 10. Goals und Habits

Goals/Habits sollten nicht zu hart auf `target_minutes` + `category_id` beschränkt bleiben. M4 behält einfache Felder für häufige Fälle, ergänzt aber eine flexible Regelstruktur.

Empfohlene Erweiterung:

#### `goal`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Ziel-ID |
| `title` | TEXT | Anzeigename |
| `type` | TEXT | `DURATION`, `COUNT`, `STREAK`, `REDUCE`, `CUSTOM` |
| `period` | TEXT | `DAILY`, `WEEKLY`, `MONTHLY`, `CUSTOM` |
| `target_value` | REAL | Zielwert |
| `target_unit` | TEXT | `MINUTES`, `SESSIONS`, `PERCENT`, `COUNT` |
| `filter_json` | TEXT | Kategorien, Tags, Activity Types, Zeitfenster |
| `start_at` | INTEGER | Start |
| `end_at` | INTEGER NULL | Ende |
| `status` | TEXT | `ACTIVE`, `PAUSED`, `DONE`, `ARCHIVED` |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |

#### `habit`

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Habit-ID |
| `title` | TEXT | Anzeigename |
| `frequency_rule_json` | TEXT | RRULE-/Custom-Regel |
| `success_rule_json` | TEXT | Was zählt als erledigt |
| `active` | INTEGER | aktiv |
| `created_at` | INTEGER | erstellt |
| `updated_at` | INTEGER | geändert |

`habit_log` bleibt als Materialisierung/Override erhalten, weil Nutzer einzelne Tage korrigieren können.

### 11. Statistiksystem

Statistiken sind ableitbar und dürfen nicht die Primärdaten ersetzen.

Empfohlene Tabellen:

#### `activity_aggregate_day`

| Feld | Typ | Zweck |
|---|---|---|
| `date` | TEXT | lokaler Tag `YYYY-MM-DD` |
| `timezone_id` | TEXT | Zeitzone |
| `category_id` | TEXT NULL | Dimension |
| `activity_type_id` | TEXT NULL | Dimension |
| `tag_id` | TEXT NULL | optionale Dimension |
| `duration_ms` | INTEGER | Summe |
| `session_count` | INTEGER | Anzahl |
| `updated_at` | INTEGER | Cache-Zeit |

Composite PK: `(date, timezone_id, category_id, activity_type_id, tag_id)` oder technischer Hash-Key.

#### `stat_cache`

Für komplexe spätere Reports:

| Feld | Typ | Zweck |
|---|---|---|
| `id` | TEXT PK | Cache-ID |
| `scope` | TEXT | `DAY`, `WEEK`, `MONTH`, `YEAR`, `LIFE` |
| `period_start` | INTEGER | Start |
| `period_end` | INTEGER | Ende |
| `metric_key` | TEXT | z. B. `time_distribution`, `streaks` |
| `params_hash` | TEXT | Filterparameter |
| `payload_json` | TEXT | berechnetes Ergebnis |
| `schema_version` | INTEGER | Cache-Version |
| `computed_at` | INTEGER | berechnet |

Caches können jederzeit gelöscht und neu berechnet werden.

## Aktivitätsmodell: genaue Trennung der Ebenen

### Sensor-Rohdaten

Beispiele: GPS-/Geofence Callback, Activity Recognition Transition, Health Connect Sleep Record, UsageStats Event, Wear OS Sample.

Speicherort: `raw_source_event`

Eigenschaften:

- unverändert
- append-only
- enthält Payload
- nicht direkt für UI/Stats

### Android Detection Events

Normalisierte Interpretation eines Raw Events in einer gemeinsamen Form.

Speicherort: `detection_event`

Beispiele:

- `GEOFENCE_ENTER(work)`
- `ACTIVITY_IN_VEHICLE`
- `SLEEP_SESSION(23:10–06:40)`
- `APP_USAGE(com.whatsapp, 12:10–12:18)`

### Erkannte Aktivitäten

Zusammengeführter Vorschlag aus mehreren Detection Events.

Speicherort: `activity_candidate`

Beispiel:

- „Arbeit, 08:12–16:45, Confidence 0.91, Evidence: Geofence Enter/Exit + Calendar Busy“

### Bestätigte Aktivitäten

Nutzerwahrheit nach Annahme, Korrektur oder manuellem Eintrag.

Speicherort: `activity_session`

Historie: `activity_session_change`

Beispiel: Wenn automatisch „Arbeit 08:00–17:00“ erkannt und später auf „08:15–16:45“ korrigiert wird, bleibt der ursprüngliche Candidate über `source_candidate_id`/Evidence und die Änderung über `activity_session_change` erhalten.

### Manuelle Aktivitäten

Sind direkte `activity_session` mit `source_type=MANUAL` und ohne nötige Evidence.

Optional kann später ein synthetisches Detection/Event für Audit erstellt werden, aber die Session selbst reicht als Wahrheit.

### Aktivitätsblöcke / Sessions

Jeder Lebenszeitblock ist eine `activity_session` mit `start_at` und `end_at`.

Beispiele:

- Schlaf: Session Kategorie `Schlaf`, Type `sleep`
- Arbeit: Session Kategorie `Arbeit`, Type `work`
- Autofahrt: Session Kategorie `Transport`, Type `driving`
- Handy-Nutzung: entweder Overlay-Session `digital` oder App-Usage-Evidence, abhängig vom Report
- Meditation: Session Kategorie `Gesundheit`, Type `meditation`

### Kategorien

Visuelle Nutzergruppierung, stabil für Dashboard und Reports.

### Tags

Flexible Mehrfachauszeichnung: `deep-work`, `cardio`, `family`, `reading`, `low-energy`.

### Ziele

Regeln, die über Sessions aggregieren.

Beispiel:

```json
{
  "includeCategories": ["learning"],
  "includeTags": ["deep-work"],
  "timeWindow": { "start": "06:00", "end": "22:00" }
}
```

### Gewohnheiten

Wiederkehrende Erfolgsregeln über Sessions plus manuelle Overrides in `habit_log`.

### Statistiken

Abgeleitete Projektionen aus `activity_session`, optional beschleunigt durch Aggregat-/Cache-Tabellen.

## Zeit als zentrales Datenmodell

Ja: **fast alles sollte über Zeiträume modelliert werden**, aber nicht alles muss dieselbe Semantik haben.

| Beispiel | Modellierung |
|---|---|
| Schlaf | `activity_session(type=sleep)` aus Health Connect Candidate oder manuell |
| Arbeit | `activity_session(type=work)` aus Geofence/Kalender/manuell |
| Autofahrt | `activity_session(type=driving)` aus Activity Recognition |
| Lernen | `activity_session(type=learning)` manuell/Kalender/Ort |
| Handy-Nutzung | Raw/Detection pro App; optional aggregierte `activity_session(type=digital)` für Dashboard |
| Fitnessstudio | Geofence Candidate → `activity_session(type=fitness)` |
| Meditation | manuell/Wearable/Health → `activity_session(type=meditation)` |
| Lesen | manuell/Kalender/Wearable später → `activity_session(type=reading)` |

Ausnahmen:

- Ziele, Habits, Bucket List Items, Einstellungen und Datenquellen sind keine Lebenszeitblöcke.
- Sie referenzieren oder bewerten Zeitblöcke.

## Erweiterbarkeit für spätere Features

| Feature | Datenmodell-Bewertung |
|---|---|
| Wear OS / Smartwatch | Neue `data_source`; Samples als `raw_source_event`; normalisierte `detection_event`; keine Session-Schemaänderung |
| Health Connect Erweiterungen | Neue Detection-Kinds und Payload-Versionen; Sessions bleiben stabil |
| Kalenderintegration | Calendar Events als Raw/Detection; Candidates für Arbeit/Lernen/Reisen |
| Lokale KI-Auswertungen | Nutzt Sessions + Evidence + Raw Payloads; kein Cloud-Zwang |
| CSV/JSON Export | Klare Tabellen und UTC-Zeiten exportierbar; Evidence ermöglicht Transparenz |
| Backup/Restore | Soft Delete, revision, origin_device_id vorbereiten konfliktarme Wiederherstellung |
| Widgets | lesen Aggregat-/Cache-Tabellen; keine neue Primärstruktur |
| Monatsberichte PDF | basiert auf `activity_aggregate_day` und `stat_cache` |
| Desktop-App | Datenmodell ist plattformneutral genug; Export/Sync kann später darauf aufbauen |
| Multi-Device Sync | noch nicht implementiert, aber durch stabile IDs, revision, deleted_at, origin_device_id vorbereitbar |

## M4 Implementierungsleitplanken

1. Keine destruktive Migration in Release.
2. `exportSchema=true` aktivieren.
3. Neue Tabellen zuerst neben bestehender M2-Struktur einführen oder M2 vor Release sauber auf Version 2 migrieren.
4. `activity_session` bleibt klein und stabil; komplexe Herkunft/Erklärbarkeit liegt in Evidence/Raw/Candidate.
5. Jede Session-Änderung, die fachlich relevant ist, schreibt einen `activity_session_change` Record.
6. `payload_json` nur für quellenspezifische flexible Details verwenden, nicht für häufige Query-Dimensionen.
7. Häufige Filterdimensionen bleiben Spalten: Zeit, Kategorie, Activity Type, Status/Delete, Quelle.
8. Statistik-Caches dürfen gelöscht und neu berechnet werden.
9. Jede neue Quelle muss durch `data_source -> raw_source_event -> detection_event -> candidate/session` passen.

## M4 Ziel-Schema

M4 sollte mindestens folgende fachliche Tabellen stabilisieren:

- `data_source`
- `raw_source_event` oder Erweiterung/Umbenennung von `raw_detection_event`
- `detection_event`
- `activity_candidate`
- `activity_session`
- `activity_session_change`
- `session_evidence`
- `activity_type`
- `category`
- `tag`
- `activity_session_tag`
- `goal` mit flexibler Filter-/Rule-Struktur
- `habit` mit flexibler Frequenz-/Success-Regel
- `habit_log`
- `activity_aggregate_day` als erster Statistikcache

## Fazit

Das aktuelle M2-Modell ist als Startpunkt brauchbar, aber für den langfristigen Anspruch nicht ausreichend getrennt. Vor der M4-Implementierung sollte die Architektur auf das oben beschriebene Ebenenmodell umgestellt werden.

Die wichtigste Änderung ist die Trennung von:

- Rohdaten
- Detection Events
- Kandidaten
- bestätigten Sessions
- Evidence
- Aggregaten

Damit bleibt Aevum über Jahre erweiterbar, auch wenn neue Sensoren, Wearables, Kalenderdaten, KI-Auswertungen, Exporte, Widgets, PDF-Berichte oder Multi-Device-Sync hinzukommen.
