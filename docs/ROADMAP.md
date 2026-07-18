# ROADMAP — Aevum

## M0 — Vorbereitung & Dokumentation

**Ziel:** Projektgedächtnis und Grundarchitektur stehen.
**Status:** Abgeschlossen.

**Definition of Done:** Alle `/docs` Dateien vorhanden und Produktvision eingearbeitet.

## M1 — Produktdefinition

**Ziel:** Produktvision, Name, Paket, Kernfeatures und Offline-Entscheidung festlegen.
**Status:** Abgeschlossen.

**Ergebnis:** Aevum, Paket `de.devondroste.aevum`, Life-Analytics-Assistent, offline-first.

## M2 — Android-Projektgrundlage

**Ziel:** Buildfähiges Android-Projekt ohne komplexe Fachfeatures.
**Status:** **Abgeschlossen.**

**Erledigte Aufgaben:**

- [x] Gradle Wrapper, Kotlin und Android Gradle Plugin eingerichtet
- [x] Package/Namespace `de.devondroste.aevum` eingerichtet
- [x] Compose + Material 3 eingerichtet
- [x] Aevum Light/Dark Theme eingerichtet
- [x] Hilt eingerichtet
- [x] Room Grundstruktur eingerichtet
- [x] DataStore Preferences eingerichtet
- [x] Navigation Compose eingerichtet
- [x] Testsetup eingerichtet
- [x] leere App-Shell mit Platzhalter-Navigation erstellt

**Benötigte Dateien:** Gradle-Dateien, Manifest, MainActivity, Theme, NavHost, Data Layer, DI Module, Testbasis.

**Tests/Verifikation:**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=1
```

Ergebnis: erfolgreich. Debug APK existiert und wurde per `aapt`/`apksigner` geprüft.

**Definition of Done:** Erfüllt.

## M3 — Design System & Dashboard Skeleton

**Ziel:** Aevum Look & Feel als wiederverwendbare Komponenten und erster Dashboard-Skeleton.
**Status:** **Abgeschlossen.**

**Erledigte Aufgaben:**

- [x] UX-/Design-Review vor Buildfixes durchgeführt
- [x] Design Tokens aus `DESIGN_SYSTEM.md` in Compose-Komponenten konsolidiert
- [x] Wiederverwendbare Komponenten erstellt: `AevumCard`, `StatisticCard`, `ProgressRing`, `TimelineItem`, `CategoryChip`, `ChartContainer`, `EmptyState`, `SectionHeader`
- [x] Dashboard-Screen mit echter visueller Struktur, aber noch ohne komplexe Fachlogik erstellt
- [x] Skeletons für Zeitverteilung, Fortschrittsringe, Heatmap, Timeline, Aktivitäts-/Digital-Balance-Diagramme erstellt
- [x] Dashboard Preview ergänzt
- [x] Projektweite UX-Review-Regel eingeführt

**Tests/Verifikation:**

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain --max-workers=1
./gradlew lintDebug --no-daemon --console=plain --max-workers=1
./gradlew assembleDebug --no-daemon --console=plain --max-workers=1
```

Ergebnis: erfolgreich. Debug APK existiert und wurde per `aapt`/`apksigner` geprüft.

**Definition of Done:** Erfüllt für M3. Dashboard nutzt Mock-Daten, ist kompilierbar und bildet die Premium-UX-Richtung ab.

## M4 — Core Datenmodell & Room fachlich stabilisieren

**Ziel:** Lokale Datenbasis fachlich belastbar machen.

**M4 Pre-Review Entscheidung:** Das M2-Basismodell wird fachlich erweitert. Aevum nutzt Zeitintervalle als kanonisches Aktivitätsmodell und trennt Raw Events, Detection Events, Candidates, Sessions, Evidence und Aggregations-Caches.

**Status:** **Abgeschlossen.**

**Erledigte Aufgaben:**

- [x] `exportSchema=true` und Migrationstest-Basis einrichten
- [x] Zielmodell aus `docs/DATABASE.md` implementieren: `data_source`, `raw_source_event`, `detection_event`, `activity_candidate`, `activity_session`, `session_evidence`, `activity_type`
- [x] `activity_session_change` und Session-Herkunft (`created_by`, `updated_by`, `source_candidate_id`, optional `supersedes_session_id`) implementieren
- [x] bestehende Kategorien/Tags normalisiert beibehalten und Seed-Daten erstellen
- [x] Goals/Habits mit flexibler Rule-/Filter-Struktur vorbereiten
- [x] `activity_aggregate_day` als ersten ableitbaren Statistikcache planen/implementieren
- [x] DAO-Abfragen für Zeitintervalle, Kandidaten, Evidence und Aggregationen testgetrieben stabilisieren

**Tests:** DAO Tests, Migration Tests, Repository Unit Tests.

**Definition of Done:** Daten können lokal angelegt, gelesen, bearbeitet und gelöscht werden; Kandidaten und bestätigte Sessions sind getrennt; Raw/Evidence bleibt nachvollziehbar; Session-Änderungen sind historisch nachvollziehbar; Migrationen sind testbar.

## M5 — Timeline & manuelle Activity Sessions

**Ziel:** Ohne Permissions nutzbarer Kernflow.

**Status:** **Abgeschlossen.**

**Erledigte Aufgaben:**

- [x] Timeline mit echten Room-Daten
- [x] Tagesansicht mit Datum-Navigation
- [x] Wochenansicht vorbereitet über horizontale Wochenleiste
- [x] Activity Editor für neue/bestehende Sessions
- [x] Activity Detail Screen
- [x] Neue Activity anlegen
- [x] Activity bearbeiten
- [x] Activity soft-deleten
- [x] Kategorien auswählen
- [x] Activity Type auswählen
- [x] Tags hinzufügen/entfernen
- [x] Start-/Endzeit schnell per +/− Stunde und +/− 15 Minuten bearbeiten
- [x] Dauer automatisch berechnen
- [x] Plausibilitätsprüfung für leeren Titel, negative Zeiten und Überschneidungen
- [x] Dashboard auf echte Room-Daten umstellen

**Tests:** Unit Tests für Zeitformatierung und Intervallvalidierung; Build-/Lint-Verifikation; installierbare Debug APK geprüft.

**Definition of Done:** Nutzer kann Zeitblöcke manuell erfassen und visuell sehen.

## M5.5 — UX Polish vor Automatisierung

**Ziel:** Alpha-Feedback einarbeiten und den manuellen Kernflow vor M6 hochwertiger, einfacher und zukunftsfähiger machen.

**Status:** **Abgeschlossen.**

**Erledigte Aufgaben:**

- [x] Safe Area / Statusleisten-Abstand für Dashboard und Activity Editor korrigiert
- [x] Activity Type + Kategorie für Nutzer zu einer einzigen Aktivitätsauswahl vereinfacht
- [x] interne Trennung von Activity Type und Category beibehalten
- [x] visuellen Tages-Zeitstrahl im Editor eingeführt
- [x] Drag-basierte Grobjustierung von Start/Ende vorbereitet
- [x] ±h und ±15m Schnellbuttons beibehalten
- [x] Trigger Events als Architekturkonzept vorbereitet (`TriggerEventMarker`, `TriggerEventKind`)
- [x] Preview-Trigger als Snap-Marker im Editor eingebunden
- [x] Tags in Modal Bottom Sheet mit größeren Chips verlagert
- [x] Wochenbereich entfernt
- [x] Timeline Richtung Tageskalender mit 00:00–24:00 Zeitblöcken umgebaut
- [x] Settings-Struktur für Verwaltung/Automatisierung/Datenschutz/Daten vorbereitet

**Tests:** Unit Tests, Lint, Assemble, APK-Verifikation.

**Definition of Done:** Nutzer hat weniger Eingabekomplexität, korrekte Safe Areas, visuellere Zeitbearbeitung und eine Timeline, die klar Richtung Tageskalender entwickelt ist.

## M6 — Automatische Erkennung v1

**Ziel:** Erste automatische Quellen integrieren.

**Aufgaben:** Geofencing, Activity Recognition, RawDetectionEvent, Candidate-Erzeugung, Review UI.

**Tests:** Classifier Tests, Receiver Tests soweit möglich, Permission Flow Tests.

**Definition of Done:** Automatische Events erzeugen bearbeitbare Kandidaten.

## M7 — Health Connect / Sleep & UsageStats

**Ziel:** Schlaf und Smartphone-Nutzung integrieren.

**Aufgaben:** Health Connect Sleep Import, UsageStats Import, eigene Visualisierung.

**Tests:** Mapper Tests, Aggregations Tests, Permission Empty States.

**Definition of Done:** Schlaf und Smartphone-Nutzung erscheinen korrekt im Dashboard.

## M8 — Goals, Habits, Streaks

**Ziel:** Persönliche Entwicklungssysteme.

**Aufgaben:** Goals, Habits, Streak-Berechnung, Heatmap, automatische Zielprüfung.

**Tests:** TDD für Ziel-/Streak-Regeln.

**Definition of Done:** Ziele/Habits werden aus Sessions automatisch bewertet.

## M9 — Bucket List & Life Progress

**Ziel:** Langfristige Lebensperspektive.

**Aufgaben:** Bucket List CRUD, Fortschritt, Life Grid, Lebenszeitberechnung.

**Tests:** Berechnungslogik, UI Tests.

**Definition of Done:** Bucket List und Lebensstatistik sind im Dashboard/Insights sichtbar.

## M10 — Premium Polish, Performance, Release

**Ziel:** stabile Premium-App.

**Aufgaben:** Lint, Performance, Accessibility, Baseline Profiles prüfen, APK-Verifikation.

**Tests:** komplette Suite, APK badging/signature, manuelle Smoke Tests.

**Definition of Done:** verifizierte installierbare APK liegt vor.