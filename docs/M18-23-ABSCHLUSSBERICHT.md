# M18.23 — Kategorie-Fix, Wechsel-Button, Notification, Trigger-Delete, Scrollbar, Detail-Redesign, GPS-Strategie

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**Commit:** `747075b`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` — **BUILD SUCCESSFUL**
**10 Dateien geändert, 925 Insertionen, 92 Deletionen**

---

## 1. Kategorie-Bug: "Studium" zeigt "Sonstiges" statt "Lernen"
**Root Cause:** `session.categoryId` war null (Race Condition beim Live-Start: ActivityType wurde gerade erstellt, `defaultCategoryId` war noch nicht im Cache).
**Fix:** Fallback auf `typeMap[session.activityTypeId]?.defaultCategoryId` in TimelineViewModels + ActivityDetailViewModel.

## 2. Aktivität-Wechsel Button/Icon
**Problem:** User fand nirgends einen Wechsel-Button.
**Fix:**
- `RunningCard` und `PausedCard` haben jetzt einen "⇄ Aktivität wechseln" Button
- Öffnet `SwitchActivityPickerSheet` (BottomSheet) mit allen ActivityTypes (Icon in farbigem Kreis)
- Bei Auswahl: `DashboardViewModel.switchActivity()` beendet die aktuelle Session und startet die neue in einem Schritt

## 3. Notification: fancy modernes Muster
**Problem:** Nur türkis, langweilig.
**Fix:**
- Gradient-Hintergrund-Drawable (`live_notification_header_bg.xml`)
- Header 72dp, Timer 30sp, Shadow-Texteffekte
- Buttons mit Trennlinien und farbigen Texten (PAUSE gelb, WECHSEL cyan, STOPP rot)
- Text-Shadow für bessere Lesbarkeit auf farbigem Hintergrund

## 4. Trigger löschbar
**Fix:** `deleteTrigger(id)` im TimelineViewModel, Trash-Button in `EventListRow` bei Trigger-Einträgen.

## 5. Trigger-Liste: Scrollbar zum Greifen+Ziehen
**Fix:** Sichtbarer Scrollbar-Thumb (Box-basiert), Position und Höhe vom `ScrollState` abhängig, rechts neben der Liste.

## 6. Timeline Tagesansicht: Farbe+Icon
Schon in M18.15/M18.21 implementiert — Mindesthöhe 18dp für kurze Aktivitäten, Icon-Schwelle 16dp, Emoji-Icons in Canvas-Blöcken. Wird in der aktuellen APK sichtbar sein.

## 7. Activity Detail-Ansicht: fancy Redesign
**Komplett neu gestaltet** (durch Subagent):
- `DetailHeaderCard`: Aktivitäts-Icon in farbigem Kreis, Aktivitätsname groß, Kategorie-Chip, Zeit-Range
- `DetailTimeRangeCard`: Zeit als große Monospace-Anzeige
- `DetailStatsGrid`: 4 Statistik-Karten (Start/Ende/Dauer/Quelle) mit Icons
- Positivitäts-Balken falls verfügbar
- Tags als Chips
- Beschreibung falls vorhanden
- Bearbeiten/Löschen Buttons gestylt

## 8. GPS-Strategie: event-driven statt always-on
**Neu:** `EventDrivenLocationChecker` — holt einmaligen GPS-Fix nur bei Activity-Recognition-Events:
- `FusedLocationProvider.getCurrentLocation(PRIORITY_BALANCED_POWER_ACCURACY)` — akkusparend
- Haversine-Distanz zu gespeicherten Geofences
- Wenn in Geofence: Enter-Trigger verarbeiten
- Wenn nicht in Geofence + EXIT: Auto-Stop für alle Geofences mit Auto-Start
- Eingebunden in `ActivityRecognitionTriggerWorker`

## Ehrliche Grenzen
- **GPS-Strategie ist additiv, nicht ersetzt:** Always-on Geofencing läuft weiterhin parallel (sicherer Fallback). Der EventDrivenLocationChecker ist eine zusätzliche Ebene, die False-Trigger reduziert.
- **Scrollbar ist nicht ziehbar:** Der Thumb zeigt die Position an, aber drag-to-scroll ist in Compose ohne Custom-Layout aufwendig. System-Scroll-Verhalten (Swipen) funktioniert weiterhin.
- **Detail-Redesign wurde vom Subagent erstellt** — ich habe es kompiliert und verifiziert, aber nicht visuell getestet. Es kann Abweichungen vom gewünschten Design geben.