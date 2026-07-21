# M7 — Automation Experience v1 (Daily Capture)

> **Für Hermes:** Autonom implementieren. Reihenfolge: Trigger Rules → Merge Engine → Timeline UX → Review Workflow → Dashboard Card → Debug Queries → Build/Test.

**Goal:** Nutzer soll einen normalen Tag verbringen und anschließend möglichst viele Aktivitäten bereits als Vorschläge vorfinden. Review soll sich extrem leicht anfühlen.

**Architecture:** Reine lokale Regeln (keine KI). Trigger-Pair-Engine modular erweitern. Candidate-Merge vor Timeline-Anzeige. Multi-Select im Review-Inbox-Workflow. Neue Dashboard-Karte zeigt Automatisierungs-Status.

**Tech Stack:** Kotlin, Compose, Room 2.6.1 (v3 → v4 Migration), Hilt, bestehende Aevum-Architektur.

**Kein Scope:** candidate_quality_log-Tabelle, Trigger-Debug-UI, Health Connect, UsageStats.

**Constraints:** Keine Gamification, keine roten Warnungen, ruhige Premium-Optik, Business-Logik in UseCases/Domain, nicht in Composables.

---

## ADRs (vorab dokumentieren)

### ADR-0025: M7 Scope — Automation Experience v1 statt Health Connect
**Entscheidung:** M7 fokussiert auf Automation Experience (Trigger-Regeln, Merge, Timeline-Integration, Multi-Review, Dashboard-Karte) statt Health Connect / UsageStats.
**Begründung:** Die bestehende Geofence-Automation ist die Grundlage. Health Connect ohne Review-Flow wäre toter Code. Der Nutzer muss zuerst Vertrauen in die Automatisierung aufbauen.

### ADR-0026: Candidate Merge Engine — deterministisch, lokal
**Entscheidung:** Merge erfolgt deterministisch: gleiche suggestedCategoryId + Lücke ≤5min → zusammenführen. Maximalspanne 30min. Keine ML, kein History-Learning.
**Begründung:** Zersplitterte Candidates sind das hässlichste UX-Problem. Merge vor Anzeige. Erklärbare Regeln schaffen Vertrauen.

### ADR-0027: Trigger Debug & Quality Metrics — Minimal in M7
**Entscheidung:** Trigger-Debug bekommt nur DAO-Query-Methoden (kein UI). Quality Metrics werden aus bestehenden Candidate-Daten abgeleitet (Accept/Edit/Dismiss via Status-Feld). Keine neue Tabelle.
**Begründung:** Ohne reale Nutzungsdaten wären Metriken hypothetisch. Kein Overengineering.

---

## Task 1: ADRs dokumentieren

**Files:**
- Modify: `docs/DECISIONS.md`

**Aktion:** ADR-0025, ADR-0026, ADR-0027 an `docs/DECISIONS.md` anhängen.

---

## Task 2: Trigger Pair Engine erweitern

**Objective:** Die bestehende Engine um spezifischere Orts-Regeln erweitern.

**Files:**
- Modify: `app/src/main/java/de/devondroste/aevum/automation/rules/TriggerPairCandidateRuleEngine.kt`
- Create: `app/src/test/java/de/devondroste/aevum/automation/rules/TriggerPairCandidateRuleEngineTest.kt` (falls nicht existiert)
- Modify: `app/src/main/java/de/devondroste/aevum/automation/model/AutomationConstants.kt`

**Neue Regeln:**
1. `Home Exit → Work Enter` = Arbeitsweg (Kategorie: transport, title: „Arbeitsweg")
2. `Work Exit → Home Enter` = Heimweg (Kategorie: transport, title: „Heimweg")
3. `Home Exit → Gym Enter` = Anfahrt Fitness (Kategorie: transport, title: „Anfahrt: Fitnessstudio")
4. `Gym Stay (Enter→Exit)` = Fitness (existiert bereits, confidence erhöhen)
5. `Home Exit → Supermarkt/Shop Enter` = Einkauf (Kategorie: household, title: „Einkauf: {place}")
6. `Any Exit → Any Enter (kein Match)` = Transit (Kategorie: transport, title: „Unterwegs", niedrigere Confidence)

**Implementierung:**
- `TriggerPairCandidateRuleEngine.evaluate()` erweitern um spezifische Travel-Paare
- `PlaceGeofence` Typ-Erkennung erweitern (isShopLike, isTransitLike)
- Confidence-Werte: Arbeitsweg/Heimweg 0.85, Anfahrt 0.78, Einkauf 0.72, Transit 0.60

**Tests:** Unit-Test für jede neue Regel mit Mock-TriggerEvents und Mock-Geofences.

---

## Task 3: Candidate Merge Engine

**Objective:** Mehrere ähnliche, zeitnahe Candidates automatisch zusammenführen.

**Files:**
- Create: `app/src/main/java/de/devondroste/aevum/automation/rules/CandidateMergeEngine.kt`
- Create: `app/src/test/java/de/devondroste/aevum/automation/rules/CandidateMergeEngineTest.kt`
- Modify: `app/src/main/java/de/devondroste/aevum/automation/rules/CandidateRuleOrchestrator.kt`

**Merge-Regeln:**
1. Candidates nach startAt sortieren
2. Für aufeinanderfolgende Candidates mit gleicher suggestedCategoryId:
   - Wenn Lücke (current.endAt → next.startAt) ≤ 5min → mergen
   - Maximalspanne vom ersten startAt zum letzten endAt ≤ 30min
3. Merged Candidate: startAt = min, endAt = max, confidence = Durchschnitt, reason = kombiniert

**Implementierung:**
```kotlin
class CandidateMergeEngine @Inject constructor() {
    fun merge(candidates: List<ActivityCandidate>): List<ActivityCandidate>
}
```
- In `CandidateRuleOrchestrator.evaluateRecentTriggers()`: Nach `ruleEngine.evaluate()`, vor `insertAll()`, merge anwenden.
- Merged Candidates bekommen neue ID: `merged_{firstId}_{lastId}`

---

## Task 4: Candidate Timeline UX

**Objective:** Candidates als transparente Vorschläge direkt im Dashboard-Tagesfluss anzeigen.

**Files:**
- Modify: `app/src/main/java/de/devondroste/aevum/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/de/devondroste/aevum/ui/screens/dashboard/DashboardScreen.kt`

**UX-Design:**
- Candidates erscheinen im `TodayFlowPanel` (24h-Visualisierung) als **halbtransparente Balken** (alpha = 0.4) mit gestricheltem Rand
- Unter dem Tagesfluss: eine „Vorschläge"-Sektion mit Candidates als kompakte Karten
- Jede Candidate-Karte zeigt: Titel, Zeit, Confidence-Badge, „Übernehmen"-Button (one-tap)
- „Übernehmen" akzeptiert den Candidate OHNE Detailansicht zu öffnen
- „Bearbeiten"-Link für Detailansicht

**DashboardViewModel-Änderungen:**
- `buildState()` um Candidates-Parameter erweitern
- Neue Felder in `DashboardUiState`: `candidateFlowSegments`, `candidates`
- Candidate-Segmente für Flow-Visualisierung mit `alpha = 0.4` markieren

---

## Task 5: Review Workflow verbessern

**Objective:** Multi-Select, Bulk-Accept/Dismiss, „Alle sicheren übernehmen".

**Files:**
- Modify: `app/src/main/java/de/devondroste/aevum/ui/screens/review/ReviewInboxViewModel.kt`
- Modify: `app/src/main/java/de/devondroste/aevum/ui/screens/review/ReviewInboxScreen.kt`
- Modify: `app/src/main/java/de/devondroste/aevum/domain/automation/ReviewCandidateUseCase.kt`

**Neue Funktionen:**
1. **Multi-Select-Modus:** Long-press auf Candidate → Checkbox-Modus. Checkboxen erscheinen. Ausgewählte zählen.
2. **Auswahl-Leiste (Bottom):** Wenn ≥1 ausgewählt: „X ausgewählt" + Buttons [Alle übernehmen] [Alle verwerfen]
3. **„Alle sicheren übernehmen"-Button** im Header: Akzeptiert alle Candidates mit confidence ≥ 0.70
4. **Batch-Accept/Dismiss** in `ReviewCandidateUseCase`: `acceptAll(ids: List<String>)`, `dismissAll(ids: List<String>)`

**UX:**
- Keine Swipe-Gesten (bleibt einfach)
- Ruhige Checkboxen in Primary-Farbe (nicht rot/grün)
- „Alle sicheren übernehmen" zeigt an, wie viele betroffen sind: „5 sichere Vorschläge übernehmen"

---

## Task 6: Dashboard Automatisierungs-Karte

**Objective:** Neue „Automatische Erfassung"-Karte im Dashboard.

**Files:**
- Modify: `app/src/main/java/de/devondroste/aevum/ui/screens/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/de/devondroste/aevum/ui/screens/dashboard/DashboardScreen.kt`

**Design:**
- Position: Nach `KeyMetricsRow`, vor `ReviewQuietCard` (oder integriert)
- Inhalt: „Heute erkannt: • 6 Aktivitäten" + „Noch prüfen: • 2 Vorschläge"
- Ruhige Darstellung, keine Animation, keine Badge-Zahlen
- Tappable → öffnet Review Inbox

**DashboardViewModel:**
- `candidateCount` (PENDING heute)
- `acceptedToday` (ACCEPTED mit resolvedAt heute)

---

## Task 7: Room-Migration v3 → v4

**Objective:** `source_candidate_id`-Spalte im `activity_candidate`-Index ergänzen (fehlte im v2→v3 CREATE TABLE, ist aber im Entity).

**Files:**
- Modify: `app/src/main/java/de/devondroste/aevum/data/db/AppDatabase.kt`

**Prüfung:** `source_candidate_id` ist im Entity als `@ColumnInfo(name = "source_candidate_id")` definiert, fehlt aber im `activity_candidate` CREATE TABLE in MIGRATION_1_2. MIGRATION_3_4 muss:
1. `ALTER TABLE activity_candidate ADD COLUMN source_candidate_id TEXT`
2. Index erstellen: `CREATE INDEX IF NOT EXISTS idx_candidate_source ON activity_candidate(source_candidate_id)`

**Achtung:** Wenn die Spalte auf Geräten, die direkt v3 installiert wurden, bereits existiert (weil Room das Entity direkt erstellt), dann ist `ALTER TABLE ... ADD COLUMN` ein no-op oder Fehler. Sicherer: `addColumnIfMissing()`-Pattern.

---

## Task 8: Tests

**Objective:** Unit-Tests für neue Domain-Logik.

**Files:**
- Create/Modify: `app/src/test/java/de/devondroste/aevum/automation/rules/TriggerPairCandidateRuleEngineTest.kt`
- Create: `app/src/test/java/de/devondroste/aevum/automation/rules/CandidateMergeEngineTest.kt`

**Tests:**
- TriggerPairCandidateRuleEngine: Home→Work, Work→Home, Home→Gym, Home→Shop, Transit-Fallback
- CandidateMergeEngine: gleiche Kategorie+Lücke≤5min merged, Lücke>5min nicht merged, Kategorie-Unterschied nicht merged, Maximalspanne eingehalten

---

## Task 9: Build, Verify, APK

**Befehl:**
```bash
cd /root/ai-projects/premium-android-app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

**Verifikation:**
- APK-Größe und Badging prüfen
- git diff --check
- git commit

---

## Task 10: Dokumentation aktualisieren

**Files:**
- Modify: `docs/PROJECT_STATE.md`
- Modify: `.project-memory/roadmap.md`
- Modify: `.project-memory/tasks.md`
