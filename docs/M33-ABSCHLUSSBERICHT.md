# M33 — Activity-Starter, Anlegen-Screen, aufgeräumte Einstellungen

**Version:** 1.0.53 (versionCode 54)
**Datum:** 2026-10-30

## Auftrag

1. Das Plus im Dashboard öffnet ein Planetensystem — weg damit, stattdessen
   etwas Übersichtlicheres, weiterhin hochwertig/fancy.
2. In dieser Ansicht ein Plus für eine neue Aktivität; Tap führt zu einem
   Screen, auf dem die Aktivität erstellt wird.
3. Einstellungen: „Automatisierung Status“ und „Geofences neu registrieren“
   entfernen.
4. Einstellungen besser gruppieren.
5. Reflektieren, warum die ersten Nutzer die App direkt wieder deinstallieren;
   kleine Änderungen umsetzen, bei großen vorher fragen.

## Umgesetzt

### 1. Activity-Starter ersetzt das Planetensystem

- `ui/components/RadialOrbitPicker.kt` gelöscht (Orbit-Layout, Rotation, Drift,
  Planet-Tap-Detektor) und der zugehörige `OrbitPickerTapPathRegressionTest`.
- Neu: `ui/components/ActivityLauncherSheet.kt`
  - Vollflächiger, ruhiger Screen (Gradient statt Sternenhimmel).
  - Kopf: Titel, Untertitel, Suche, **„+ Neu“**, Schließen.
  - „Zuletzt“ als horizontale Schnellstart-Chips.
  - „Alle Aktivitäten“ als 3-Spalten-Raster: Icon im Farbkreis, Name,
    Favoriten zuerst und mit Stern.
  - Tap → Detail-Panel mit „Jetzt starten“ / „Vorlaufzeit“.
  - Vorlaufzeit-Ring (0–120 min, Drag, Schnellwahl-Chips, exakte Uhrzeit)
    bleibt erhalten; Chips liegen jetzt in zwei Reihen (7 × 48 dp passen auf
    360-dp-Geräten nicht in eine Reihe).
  - Kein Endlos-Effekt: einmaliges, gestaffeltes Einblenden; Back-Taste
    schließt erst die Auswahl, dann den Starter.
  - Textfarbe auf Akzentflächen wird per WCAG-Kontrastverhältnis gewählt
    (dunkle Schrift auf Grün/Orange/Blau, weiße auf Indigo/Violett).

### 2. Eigener Anlegen-Screen

- Neu: `ui/screens/activitytypes/ActivityCreateScreen.kt` +
  `ActivityCreateViewModel`.
- Felder: Live-Vorschau, Name, Symbol-Grid (32 Emojis), Farbpalette (16),
  Kategorie-Chips, „Oben anheften“ (Favorit).
- „+ Neu“ im Starter schließt das Overlay und navigiert zu
  `activity_type/new`; Speichern legt den `ActivityType` an und geht zurück.
- Kein Dialog mehr, keine implizite Sofort-Aufzeichnung.

### 3. Einstellungen aufgeräumt

- `AutomationStatusScreen` (inkl. „Geofences neu registrieren“, Diagnose-Log)
  entfernt: Composable, ViewModel + UiState, Route, Enum-Eintrag und der
  Einstieg „Status-Details“ in „Trigger & Erkennung“.
- Geofence-Registrierung bleibt unverändert automatisch: App-Start, Boot,
  Aktivieren eines Triggers (`GeofenceRegistrar`/BootReceiver).
- `SettingsScreen` neu gruppiert (fünf Gruppen statt neun Karten):
  1. Darstellung — Sprache & Design, Maskottchen
  2. Deine Aktivitäten — Activity Types, Kategorien, Ziele, Todos, Pauschalen
  3. Automatisierung — Trigger & Erkennung, Geofences, Trigger Events,
     Orts-Timeline, Kalender, PC-Tracker
  4. Verbindungen — Garmin Connect
  5. Datenschutz & Daten — Datenschutz, Export, Backup
- Zeilen sind jetzt Titel + Untertitel + Chevron und vollflächig klickbar;
  die neun „Öffnen“-Buttons sind entfallen. Gruppenlabels stehen außerhalb
  der Karte.

## Verifikation

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` — **BUILD SUCCESSFUL**.
- **1432 Tests, 0 Fehler, 0 Errors, 1 skipped.**
- Neu: `ActivityLauncherUiTest` (5 Tests) — Raster/Sektionen sichtbar, Tap
  öffnet Start-Panel, „+ Neu“ feuert den Navigations-Callback, Render-PNGs für
  Starter und Vorlaufzeit.
- `GoalArchitectureWiringTest` an die neue Gruppenstruktur angepasst
  (Ziele liegen in „Deine Aktivitäten“).
- APK: `com.d_drostes_apps.aevum.debug`, versionCode 54, versionName
  1.0.53-debug, minSdk 29, targetSdk 36, debug-signiert mit dem kanonischen
  Key (SHA-1 `9c3055c4…`, `signing-verify OK`).
- Render-Evidenz: `app/build/reports/activity-launcher/launcher.png` und
  `retro.png` (echte Compose-Hierarchie, Robolectric NATIVE).

## Reflexion: Warum Installation → Deinstallation?

**Befund 1 — Das Store-Versprechen wird beim ersten Start nicht eingelöst.**
Die Play-Beschreibung verkauft „Automatic time tracker … no button pressing“
und „Aevum captures your day — without you having to log anything“. Die App
startet aber direkt ins Dashboard (kein Onboarding, `startDestination =
dashboard`; `OnboardingScreen` ist ein Platzhalter). Automatik ist
standardmäßig aus, es sind keine Orte angelegt, und der erste Bildschirm ist
der Standort-Disclosure-Dialog. Ergebnis: Nullen, keine erkennbare Automatik,
kein erster Erfolg — der Nutzer schließt die App vor dem ersten Aha.

**Befund 2 — Die Kernaktion war die schwerste Interaktion.**
Das Planetensystem hatte bewegte Ziele, drei Orbits, 10-sp-Labels und
zuverlässige Treffer erst nach der Suche. Wer nur „Sport starten“ will, musste
zielen und suchen. Ersetzt durch Raster + Suche + Zuletzt-Chips.

**Befund 3 — Einstellungen lasen sich wie ein Diagnose-Tool.**
Neun gleichrangige Karten mit „Öffnen“-Buttons, darunter „Automatisierung
Status“, „Geofences neu registrieren“, Trigger-Events und Debug-Flächen. Das
signalisiert Aufwand und Technik statt Selbstverständlichkeit. Jetzt fünf
Gruppen und nur noch Aufgaben, keine Diagnose.

**Befund 4 — Fünf Tabs, vier davon beim ersten Start leer.**
Heute, Insights, Timeline, Balance, Einstellungen. Bei leerem Zustand sind
vier Tabs ohne Inhalt; das wirkt wie eine große App ohne Substanz. Kein
kleiner Fix, sondern eine Onboarding-/First-Value-Aufgabe.

**Befund 5 — Englisch als Default.**
`LanguageRepository.LANGUAGE_DEFAULT = "en"` (bewusste frühere Entscheidung).
Für deutschsprachige Nutzer ist die Oberfläche beim ersten Start englisch.
Nicht eigenmächtig geändert; als Entscheidung offen.

## Empfehlungen (nicht Teil dieses Auftrags)

1. **Onboarding mit First Value** (hoher Hebel): 3 Schritte — was Aevum tut,
   welche Aktivitäten tracken (Vorauswahl), Automatik optional einrichten;
   überspringbar. Ziel: nach 60 Sekunden ist eine Aktivität gestartet oder die
   Automatik scharf.
2. **Empty-State auf dem Dashboard:** Wenn heute nichts erfasst ist, eine
   Karte „Erste Aktivität starten“ (öffnet den neuen Starter) statt nur des
   kleinen FAB.
3. **Automatik-Setup statt Diagnose:** In „Trigger & Erkennung“ ein geführter
   Zwei-Schritt (Orte anlegen, Berechtigungen), mit klarem Fortschritt.
4. **Play-Store-Material aktualisieren:** Screenshots zeigen vermutlich noch
   das Planetensystem; der neue Starter ist ein besseres Verkaufsargument.

**Commit:** 78bab69
