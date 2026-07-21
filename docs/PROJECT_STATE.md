# PROJECT_STATE

> Stand: 2026-07-21T16:00:00Z
> Produktname: **Aevum**
> Paketname: `de.devondroste.aevum`
> Status: **M7 — Automation Experience v1 abgeschlossen**.

## Aktueller Entwicklungsstand

- [x] Projektordner angelegt: `/root/ai-projects/premium-android-app`
- [x] `/docs` als dauerhaftes Projektgedächtnis erstellt
- [x] Skill-/Technologieanalyse durchgeführt
- [x] Architekturplanung initial erstellt
- [x] Produktdefinition eingearbeitet
- [x] Appname gewählt: **Aevum**
- [x] Paketname festgelegt: `de.devondroste.aevum`
- [x] Offline-first / kein Backend / kein Login entschieden
- [x] M2 Android-Projektgrundlage abgeschlossen
- [x] M3 Design System & Dashboard Skeleton abgeschlossen
- [x] M4 Datenmodell fachlich stabilisiert
- [x] M5 erster installierbarer Kernflow
- [x] M5.5 UX Polish
- [x] M6.1 Geofencing & Trigger Events
- [x] M6.2 Intelligente Geofences & Trigger
- [x] M6.3a Daily Review Dashboard
- [x] M6.3b Dashboard Feedback & Review Inbox
- [x] M6.4 Life Analytics v1
- [x] M6.5 Weekly Review
- [x] M6.6 Goals & Habits MVP + Geofence UX Fix
- [x] M7 Automation Experience v1 (Daily Capture)

## M7 — Automation Experience v1

**Status:** **Abgeschlossen.**

### Product Owner Review

M7 fokussiert auf Automation Experience statt Health Connect (ADR-0025). Begründung: Die bestehende Geofence-Automation braucht zuerst einen nutzbaren Review-Flow. Health Connect ohne funktionierende Review-Pipeline wäre toter Code.

### Neue Funktionen

#### 1. Trigger Pair Engine erweitert
- Spezifische Regeln: Home→Work = Arbeitsweg, Work→Home = Heimweg, Home→Gym = Anfahrt Fitness, Home→Supermarkt = Einkauf
- Generic Transit-Fallback mit niedrigerer Confidence (0.60)
- Erkennung von Rewe Frischezentrum als Arbeitsort
- Confidence-Werte je nach Regel: 0.85 (Arbeitsweg/Heimweg), 0.78 (Anfahrt), 0.72 (Einkauf), 0.60 (Transit)
- Modulare, erklärbare lokale Regeln — keine KI

#### 2. Candidate Merge Engine
- Deterministische, lokale Merge-Engine (ADR-0026)
- Gleiche Kategorie + Lücke ≤5min → zusammenführen
- Maximalspanne 30min
- Merged Candidates: gemittelte Confidence, kombinierte Reason
- Läuft in CandidateRuleOrchestrator nach Trigger-Generierung und vor Insert

#### 3. Candidate Timeline UX
- Candidates erscheinen **halbtransparent** (alpha=0.35) im Dashboard-Tagesfluss
- Gestrichelte Umrandung macht sie als Vorschlag erkennbar
- Kombinierte Flow-Segmente (bestätigt + Candidate) im DayFlowCanvas
- Timeline-Rows zeigen Source="Vorschlag" für Candidates

#### 4. Review Workflow verbessert
- **Multi-Select-Modus:** Long-press auf Candidate → Checkbox-Modus
- **Auswahl-Leiste:** "X ausgewählt" + [Alle übernehmen] [Alle verwerfen]
- **„Alle sicheren übernehmen"**-Button: akzeptiert alle ≥70% Confidence
- **Batch-Accept/Dismiss:** acceptAll(), dismissAll() im ReviewCandidateUseCase
- Keine automatische Bestätigung — Nutzer muss explizit handeln

#### 5. Dashboard Automatisierungs-Karte
- Neue Karte „Automatische Erfassung"
- Zeigt: „Heute erkannt: • N Aktivitäten" und „Noch prüfen: • N Vorschläge"
- Ruhig, keine Badges, keine roten Warnungen
- Tappable → öffnet Review Inbox

#### 6. Trigger Debug (minimal)
- ActivityCandidateDao erweitert um Query-Methoden für Fehlersuche
- countByStatusInDateRange, getByCreatedByInDateRange, getByTriggerIdInDateRange
- Kein UI — nur Datenzugriff für spätere Fehlersuche

### Architekturentscheidungen

- **ADR-0025**: M7 Scope — Automation Experience v1 statt Health Connect
- **ADR-0026**: Candidate Merge Engine — deterministisch, lokal
- **ADR-0027**: Trigger Debug & Quality Metrics — Minimal in M7

### Keine Schemaänderung

Keine neue Room-Version in M7. Keine neuen Tabellen.

### UX

- Keine Gamification, keine roten Warnungen, keine aufdringlichen Notifications
- Ruhige Premium-Optik beibehalten
- Candidates klar als Vorschlag erkennbar (transparent + gestrichelt)
- Übernehmen ohne Detailansicht möglich (one-tap aus Timeline)

## Verifikation

Ausgeführt:

```bash
git diff --check
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
```

Ergebnis: **BUILD SUCCESSFUL** (Kompilierung, Unit Tests, AndroidTest-Kompilierung, Lint, APK-Assembly).

APK-Verifikation:

```text
APK: app/build/outputs/apk/debug/app-debug.apk
Package: de.devondroste.aevum.debug
Version: 0.1.0-debug
minSdk: 29
targetSdk: 35
APK Signature Scheme v2: true
```

## Bekannte Einschränkungen

- Release-Signing noch nicht eingerichtet; APK ist debug-signiert.
- Geofence-Auslösung kann nur real auf einem Gerät mit Google Play Services und Hintergrundstandort geprüft werden.
- Connected Android Tests können in dieser Umgebung ohne Gerät/Emulator nicht ausgeführt werden.
- Activity Recognition, Health Connect Sleep und UsageStats folgen später (M8).
- Candidate Merge Engine kann ohne reale Geofence-Ereignisse nicht live getestet werden.
- ActivityCandidateRepository-Interface ist kapt-generiert; neue DAO-Queries sind nur direkt über DAO nutzbar.

## Nächster Schritt

**Empfehlung für M7.1: Trigger Debug UI + Quality Cockpit.** Sobald erste reale Nutzungsdaten vorliegen, ein ruhiges Diagnose-Cockpit (letzte Trigger, letzte Candidates, Warum entstanden/nicht entstanden). Keine neue Tabelle — alles aus Bestandsdaten ableitbar.

**Empfehlung für M8: Health Connect / Sleep & UsageStats.** Schlaf- und Smartphone-Nutzungsdaten als neue Datenquellen integrieren. Die M7-Review-Pipeline ist bereit, neue Candidate-Quellen aufzunehmen.
