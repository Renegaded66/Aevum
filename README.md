# Aevum

**Dein Leben. Automatisch erfasst.** — Aevum ist eine Android-App, die deine Aktivitäten erkennt, aufzeichnet und dir hilft, deine Zeit zu verstehen — ohne dass du an jede Sitzung denken musst.

Aevum kombiniert **manuelles Tracking** mit **automatischer Erkennung**: Geofences wissen, wann du zuhause, im Gym oder im Büro bist; die Activity-Recognition erkennt Fahrten; die Schlaf-Engine erkennt Schlafphasen. Alles fließt in eine gemeinsame Timeline, ein Dashboard und aussagekräftige Insights.

---

## ✨ Features

### 🎯 Automatisches Tracking
- **Geofence-Trigger** — „Zuhause betreten“, „Gym verlassen“ … startet und stoppt Sessions automatisch, mit Deduplizierung und GPS-Sprung-Schutz
- **Activity Recognition** — erkennt Fahrten (Auto/Bus/Zug) und startet eine „Mobilität“-Session, inkl. Auto-Stopp mit Bewegungs-Watchdog
- **Schlaf-Erkennung** — heuristische + fusionierte Erkennung aus Bildschirm-Nutzung und Health Connect, mit Schutzwällen gegen False-Positives
- **Trigger-Paare** — „Wegzeit“ zwischen Orten (z. B. Arbeitsweg, Einkauf) wird automatisch als Kandidat vorgeschlagen
- **Confidence-basiert** — nur sichere Erkennungen werden automatisch übernommen, alles andere landet in der Review-Inbox

### ⏱ Manuelles Tracking
- **Live-Session** mit Pause/Resume, Live-Timer und laufender Benachrichtigung
- **Rückwirkender Start** — „habe vor 20 Minuten angefangen“: Startzeit wählen, Session zählt sofort ab der eingestellten Zeit
- **Activity wechseln** direkt aus der Benachrichtigung
- **Quick-Create** mit Start-/Endzeit im Popup

### 📊 Verstehen
- **Timeline** — Tagesansicht mit farbigen Blöcken, Zoom, Detail-Ansicht und schnellem Löschen
- **Dashboard** — Flow-Diagramm des Tages, Live-Karte, Ziele mit Positivitäts-Score
- **Insights** — Zeitverteilung, Trends, Vergleiche
- **LifeView & Kalender** — langfristige Perspektive auf dein Leben
- **Goals** — erreiche deine Ziele, gemessen an der Positivität deiner Aktivitäten

### 🎨 Eigenständig & persönlich
- Eigene Aktivitäten anlegen: **Icon, Farbe, Kategorie, Positivitäts-Score** frei wählbar
- **Löschen mit Konzept** — Activity löschen, Aufzeichnungen umbuchen oder komplett entfernen
- Benachrichtigungen mit **individuellem Muster-Hintergrund** (Farbverlauf + Diagonal-Streifen, einzigartig pro Aktivität)
- Selbst gebaute **Zeit-Picker** (Tap-to-set) statt Standard-Widgets

---

## 🖼 Screenshots

<!-- TODO: Screenshots hier einfügen, z. B.
<img src="docs/screenshots/dashboard.png" width="240" /> <img src="docs/screenshots/timeline.png" width="240" /> <img src="docs/screenshots/notification.png" width="240" />
-->

---

## 🧱 Architektur

```
app/src/main/java/de/devondroste/aevum/
├── automation/          # Automatische Erkennung
│   ├── geofence/        #   Geofence-Transitions, Deduplizierung, Debouncer
│   ├── activityrecognition/ # Google Activity-Recognition-Bridge
│   ├── sleep/           #   Schlaf-Heuristik, Fusion, Schutzschicht
│   ├── rules/           #   Trigger-Paar-Regeln, Candidate-Merge
│   └── driving/         #   Fahrten-Worker (Bestätigung, Watchdog)
├── data/
│   ├── db/              # Room-Datenbank, DAOs, Migrationen
│   ├── model/           # Entitäten (Sessions, Kandidaten, Geofences …)
│   └── repository/      # Repositories
├── domain/
│   ├── liveactivity/    # Live-Session-Manager, Benachrichtigungs-Service
│   ├── automation/      # Review-Candidate-UseCase (Auto-Accept)
│   ├── seed/            # Default-Daten (Kategorien, Aktivitäten)
│   └── time/            # Zeitzonen-/Formatierung
└── ui/
    ├── screens/         # Dashboard, Timeline, Insights, Settings …
    ├── components/      # Eigene Compose-Komponenten (AevumTimePicker …)
    └── theme/           # Design-Tokens, Theme
```

**Datenfluss:** Sensoren & Geofences → Events → Engines → `ActivityCandidate` → Confidence-Check → `ActivitySession` → UI (Timeline/Dashboard/Insights).

**Wichtige Konzepte:**
- `ActivitySession` — eine Aufzeichnung (Zeit, Typ, Quelle, Confidence)
- `ActivityCandidate` — ein *Vorschlag* aus der Automatik, wartet auf Auto-Accept oder Review
- `ActivityType` — eine Aktivität (z. B. „Gitarre“) mit Icon, Farbe und Positivitäts-Score; `isSystem`-Typen (Schlaf, Sonstiges) sind geschützt
- **SourceTypes** — `MANUAL`, `GEOFENCE_AUTO`, `ACTIVITY_RECOGNITION_AUTO`, `HEALTH_SLEEP_AUTO` … steuern die „Auto“-Kennzeichnung in der UI

---

## 🛠 Tech-Stack

| Bereich | Technologie |
|---|---|
| Sprache | Kotlin |
| UI | Jetpack Compose (Material 3), eigene Canvas-Komponenten |
| Persistenz | Room (SQLite), Migrations-Schema in `app/schemas/` |
| DI | Hilt |
| Hintergrund | WorkManager, Foreground-Service (Live-Timer) |
| Automatik | Google Activity Recognition Transition API, Geofencing API |
| Health | Health Connect (Schlaf-Import) |
| Build | Gradle (Kotlin DSL), minSdk 29, targetSdk 35 |

---

## 🚀 Build & Test

Voraussetzungen: JDK 17+, Android SDK (compileSdk 35).

```bash
# Kompilieren
./gradlew :app:compileDebugKotlin

# Unit-Tests
./gradlew :app:testDebugUnitTest

# Debug-APK bauen
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release-APK (Signierung in local.properties / keystore konfigurieren)
./gradlew :app:assembleRelease
```

> Hinweis: `local.properties` (SDK-Pfad) und Keystores sind nicht eingecheckt — siehe `.gitignore`.

---

## 📄 Lizenz

Noch nicht festgelegt — Kontakt: Devon Droste.
