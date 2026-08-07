# M18.11–M18.14 — Schlaf-Direkt-Eintrag + Aktivitäts-Struktur (Icon/Farbe/Anlegen)

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commits:**
- `2a89bfe` M18.11 Schlaf direkt eintragen (Screen-Heuristik ohne Review-Schwelle)
- `ae03d1b` M18.12 DB v18 — ActivityType icon + color, Seeds
- `0af4901` M18.13 UI — Picker-Icons, Neue-Aktivität-Dialog, ActivityTypes-Screen
- `046bebd` M18.14 Icons + Farben in Timeline & Insights

**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**

---

## 1. Schlaf: direkt eintragen, kein Vorschlag (M18.11)

**Root Cause "letzte Nacht kein Schlaf eingetragen" (bewiesen):**
Die Screen-Heuristik erzeugte Confidence **0.50–0.75**, aber Auto-Accept griff nur bei **≥ 0.70** (`SAFE_CONFIDENCE_THRESHOLD`). Bei 7h Schlaf mit Rand-Abzug (z.B. 0.60) blieb der Schlaf als **Vorschlag in der Review-Inbox** hängen — der User sah keinen automatischen Eintrag.

**Fix:**
- `SleepHeuristicEngine`: **immer** `acceptAuto` — kein Confidence-Gate mehr. Der Screen-Algorithmus (OFF nach 20:00 + ON 04:00–11:00) ist ein starkes Signal; Session ist `HEALTH_SLEEP_AUTO` markiert und in der Timeline editierbar/löschbar.
- `SleepFusionWorker` (Morgen-Scheduler): triggert jetzt **beide** Engines — Heuristik (immer) + Fusion (nur wenn aktiviert). Vorher deckte der Morgen-Lauf nur die Fusion ab; ohne App-Öffnung wurde der Schlaf nie erkannt.

## 2. Aktivitäts-Struktur: Icon + custom Farbe + manuell anlegen (M18.12–14)

**DB v18:**
- `ActivityType` + `icon` (TEXT, Default '•') + `color` (INTEGER ARGB, Default 0)
- `MIGRATION_17_18`: 2× ALTER TABLE ADD COLUMN — **lokal verifiziert** (Schema 18.json, Defaults, Daten erhalten)
- DAO/Repository: `setIcon`, `setColor`

**Seeds:** alle 15 Default-Aktivitäten mit passendem Emoji + Farbe (💼 Arbeit, 🧠 Deep Work, 🌙 Schlaf, 🏋️ Fitness, 📱 Digital, 🚆 Transport …)

**UI:**
- **ActivityTypesScreen** (Settings): Icon-Picker (40 Emojis, Grid-Dialog), Farb-Palette (10 Farben, Tippen setzt/entfernt), "+ Neu"-Button
- **Dashboard-Picker**: Aktivitäten zeigen Icon in farbigem Kreis (custom Farbe > Kategorie-Farbe); **"+ Neue Aktivität anlegen"** mit Name-Dialog → legt echten ActivityType an UND startet sofort
- **Timeline**: Session-Zeilen mit Icon-Kreis statt nacktem Punkt
- **Insights**: Top-Aktivitäten mit Icon-Kreis

## Ehrliche Reflexion
- **Kein e2e-Test** — die Schlaf-Erkennung braucht Devons Handy (Screen-Events über Nacht). Die Logik ist aber jetzt deterministisch: OFF≥20:00 + ON 04:00–11:00 → Session, garantiert getriggert durch Morgen-Scheduler + App-Start + Screen-ON.
- **`createAndStartActivity`** erzeugt die ID aus Timestamp+Hash — kollisionssicher genug für den Zweck.
- **Custom-Farbe 0 = Primärfarbe** — bewusst: 0 ist der "nicht gesetzt"-Sentinel, die UI fällt auf Kategorie-/Positivitätsfarbe zurück.

## User-Validation
- [ ] Über Nacht schlafen → morgens ist "Schlaf" **direkt** in der Timeline (kein Review nötig)
- [ ] Dashboard → "Alle anzeigen" → "+ Neue Aktivität anlegen" → Name → startet sofort
- [ ] Settings → Activity Types → Icon antippen → Emoji wählen; Farbe antippen → ändert sich
- [ ] Timeline/Insights zeigen die Icons + Farben
- [ ] App-Neustart → Werte bleiben (DB v18)
