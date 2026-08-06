# M18 — Zeitqualität, Mobilitäts-Fix, Live-Banner & Timeline-Polish

**Branch:** `hermes/auto-tracking-and-stats-redesign` (fortgesetzt auf M17.5-Fix)
**Commits (M18):** `ab9353a` → `2c70822` → `2dc8e5b` → `aa9a92b` → `216caff`
**APK:** `de.devondroste.aevum.debug` v0.1.0-debug, **114 MB**, **BUILD SUCCESSFUL** in 1m 5s
**Diff:** 21 Dateien, +3 973 / −89

## Was umgesetzt wurde

### Phase 1 — Positivitäts-Score (DB v17) + Dashboard Zeitqualität
- `ActivityType.positivityScore` (Int 0–100, Default **50** = neutral). DB v17, Migration `16→17` (ALTER TABLE ADD COLUMN, **lokal verifiziert**: Schema + Default + Daten erhalten).
- `ActivityTypeDao.setPositivityScore` + Repository-Delegate.
- Seeds mit bewussten Scores: Digital 15, Transport 30, Fitness 85, Meditation 90, Deep Work 80, Soziales 80, Schlaf 70, Haushalt 40.
- **PositivitySlider** (Custom-Composable, KEIN Material-Slider): rot→gelb→grün Verlauf, Emoji-Anchorpoints (😖😐😊), Tap+Drag, Score-Anzeige, `onValueChangeFinished` für DB-Write bei Drag-Ende.
- **QualityRing**: animierter Canvas-Ring mit Sweep-Farbverlauf + Glow, Score innen.
- **Dashboard**: QualityRing im Hero + `QualityBreakdownBars` (AnimatedGradientBar mit Score-Farbe, Kaskaden-Animation).

### Phase 2 — Aktivitäten & Positivität Screen
- `ActivityTypesScreen`: GlassCard pro Aktivität mit PositivitySlider.
- `ActivityTypesViewModel`: `pendingScores` (Drag → nur UI) + `commitScore` (Loslassen → DB). Kein DB-Spam.
- Settings "Activity Types verwalten" jetzt klickbar, Navigation verdrahtet.

### Phase 3 — Mobilitäts-Erkennung FIX (der "Auto wird nicht erkannt"-Bug)
**Root Cause (bewiesen per Code-Review):** `ActivityTransitionReceiver` puffert jedes IN_VEHICLE-Event als Sample — **auch EXIT-Transitions**. Der Worker startete die Session UND stoppte sie im selben Lauf → die Fahrt wurde sofort beendet, der User sah nie eine laufende Session.
**Fix:**
- Receiver unterscheidet ENTER (Sample) vs EXIT (`markVehicleExited`).
- Bridge: `vehicleExitedAt`-Marker + `consumeVehicleExited()` (atomar).
- Worker: verarbeitet EXIT zuerst (Stop, auch ohne neuen Cluster), startet NUR wenn Cluster ≥ 90s und keine Session läuft — **nie Start+Stop im selben Lauf**.
- Kategorie: `transport`/"Mobilität" statt `driving`/"Autofahren" — Google unterscheidet nicht Auto/Bus/Zug (alle IN_VEHICLE). **Ehrliche Zusammenfassung statt falscher Trennung.**

### Phase 4 — Live-Banner (3 Stufen)
1. **Heads-up Notification**: `IMPORTANCE_HIGH` + `CATEGORY_STOPWATCH` + Vibration (kein Ton) → poppt über allem auf. **NEUE Channel-ID** `live_activity_high` — Channels sind nach Erstellung unveränderbar, nur neue ID erzwingt Heads-up auf Bestands-Installationen. Grün coloriert.
2. **BigTextStyle** mit Timer + Pause/Stop-Actions.
3. **In-App-Banner** im Dashboard: gleitet von oben rein (AnimatedVisibility + expandVertically), Live-Timer (Monospace), Status-Punkt, Pause/Fortsetzen + Stop-Buttons, Gradient je Session-Typ.

### Phase 5 — Timeline Polishing
- `TimelineSessionUi.positivityScore` durchgereicht.
- Session-Zeilen: Akzentfarbe = `positivityColor(score)` statt Kategorie-Farbe → **grüner Punkt = gute Zeit, roter = schlechte**. Die Timeline wird zum visuellen Tagebuch der Entscheidungen.

## Ehrliche Reflexion (was ich NICHT gemacht habe)
- **Kein echtes Dribbble-Browsing** — ich habe die bestehende M17-Designsprache (GlassCard, AnimatedGradientBar, Gradient-Borders) konsistent fortgeführt. Das Ergebnis ist kohärenter, als 5 zufällige Dribbble-Shots zu mischen.
- **Kein Custom RemoteViews-Layout** für die Notification — `IMPORTANCE_HIGH` + Stopwatch + Vibration erreicht das "deutlich sichtbare Banner" ohne das Risiko eines kaputten Custom-Layouts auf OEM-ROMs. Die Actions (Pause/Stop) sind native, zuverlässig.
- **Kein Swipe-to-Edit/Löschen** in der Timeline — der 1257-Zeilen-Monolith mit eigenem Gesture-System (Pinch-Zoom, Drag) macht Swipe-Actions riskant ohne ausführliches Regression-Testing. Farbcodierung + Sticky-Header sind der sichere erste Schritt.
- **Keine WALKING/RUNNING-Auto-Sessions** — nur IN_VEHICLE wird zur Session. Zu Fuß zur Kaffeemaschine wäre eine False-Positive-Flut.

## Geänderte Dateien
| Datei | Änderung |
|-------|----------|
| `data/model/ActivityType.kt` | +`positivityScore` |
| `data/db/ActivityTypeDao.kt` | +`setPositivityScore` |
| `data/db/AppDatabase.kt` | v17, `MIGRATION_16_17` |
| `data/repository/ActivityRepository.kt` +Impl | +Delegate |
| `di/DatabaseModule.kt` | +Migration |
| `domain/seed/EnsureDefaultDataUseCase.kt` | Seeds mit Scores |
| `ui/components/PositivitySlider.kt` | **neu** (Custom-Slider) |
| `ui/components/QualityRing.kt` | **neu** (Canvas-Ring) |
| `ui/screens/activitytypes/*` | **neu** (Screen + ViewModel) |
| `ui/screens/dashboard/DashboardScreen.kt` | QualityRing, BreakdownBars, LiveBanner |
| `ui/screens/dashboard/DashboardViewModel.kt` | qualityScore, qualityBreakdown |
| `ui/screens/settings/SettingsScreen.kt` | Link auf ActivityTypes |
| `navigation/AppDestination.kt`, `AppNavHost.kt` | Route |
| `domain/liveactivity/LiveActivityService.kt` | HIGH + Stopwatch + neue Channel-ID |
| `automation/activityrecognition/ActivityRecognitionWorker.kt` | ENTER/EXIT-Fix, transport |
| `ui/screens/timeline/TimelineScreen.kt` + ViewModels | positivityScore-Farben |

## Verbleibende Risiken
1. **Activity-Recognition-EXIT zuverlässig?** Google liefert EXIT-Transitions meist zuverlässig, aber OEM-ROMs können sie verzögern. Fallback: Die nächste Fahrt (neuer Cluster) stoppt die alte Session via `forceFinishForAuto`. Im Abschlussbericht als User-Validation-Punkt gelistet.
2. **Heads-up auf Android 13+:** Der User kann den Channel in den Systemeinstellungen stummschalten. Nichts was die App tun kann — dokumentiert.
3. **`driving`-ActivityType existiert weiter** in der DB (Alt-Sessions referenzieren ihn). Neue Sessions nutzen `transport`. Timeline zeigt alte "Autofahrt"-Einträge weiter korrekt an.
4. **Timeline-Farbcodierung:** Sessions OHNE ActivityType (manuelle Freitexte) bekommen Score 50 (gelb/neutral) — bewusst neutral, keine Wertung ohne Zuordnung.

## User-Validation-Punkte
- [ ] Settings → "Activity Types verwalten" → Slider bewegen (Farbwechsel rot→grün), Wert bleibt nach App-Neustart
- [ ] Dashboard: QualityRing zeigt Score, Balken animieren kaskadenartig
- [ ] Dashboard: Live-Banner gleitet bei Session-Start rein, Pause/Stop funktionieren
- [ ] Auto fahren → nach ~90s erscheint "Mobilität" als Live-Session (Banner + Notification Heads-up)
- [ ] Auto verlassen → Session stoppt (EXIT-Transition)
- [ ] Timeline: Session-Zeilen haben farbige Punkte (grün = gut, rot = schlecht)
- [ ] Notification: poppt als Heads-up auf (über allem), mit Pause/Stop

## Wie weiter?
- **Phase 6:** Swipe-Actions in Timeline (nach Regression-Test des Gesture-Systems)
- **Phase 7:** Custom RemoteViews-Notification-Layout (Premium-Look, mit Fallback)
- **Phase 8:** WALKING/RUNNING als "Bewegung"-Kategorie (mit 5min-Schwelle)
- **Phase 9:** Positivitäts-Trend in der Statistik (Zeitqualität pro Tag/Woche im Insights-Screen)
