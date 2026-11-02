# M35 — Manuelle Ziele mit Zeitgutschrift, wählbare Fahrt-Activity, Umbenennen

**Version:** 1.0.55 (versionCode 56) · **Datum:** 02.11.2026 · **DB:** v52 · **Status:** gebaut, 1.490 Tests grün

---

## 1. Der gemeldete Fehler: „mindestens 5× pro Woche meditieren“

**Ursache:** `GoalEditorViewModel.setTrackingMode(MODE_MANUAL)` setzte den Zieltyp
hart auf `TYPE_COUNT` und die Einheit auf `COUNT`. Beim Speichern wurde zusätzlich
`goalType = TYPE_COUNT` erzwungen. Damit war „Mindestens 5× pro Woche“ nicht
abbildbar — die Auswahl „Mindestens“ wurde vom Modus überschrieben.

**Fix:** Manuell bedeutet jetzt nur noch „wird abgehakt/gezählt“, nicht mehr
„Anzahl“. Der gewählte Zieltyp (`AT_LEAST`, `AT_MOST`, `COUNT`) bleibt erhalten;
die Einheit ist bei manuellen Zielen immer `COUNT`. Die UI prüft die
Zähl-Darstellung jetzt über `targetUnit` statt über `goalType`. Ergebnis:

- Ziel „5× meditieren“, Zeitraum „Woche“, Typ „Mindestens“, Modus „Manuell“
- vier Check-ins → „4 von 5“, nicht erfüllt
- fünf Check-ins → „5 von 5“, erfüllt

## 2. Manuelles Ziel optional mit einer Activity verknüpfen

Im Editor erscheint bei „Manuell“ ein Activity-Picker („Ziel-Aktivität
(optional)“, inklusive „Keine Aktivität“). Die Verknüpfung ändert **nicht** die
Ziel-Logik: Das Ziel bleibt ein Check-in-Ziel. Die Activity ist der Bezug für
die optionale Zeitgutschrift.

**Check-in:** Ein Tap schreibt sofort den Check-in (ein Tap bleibt ein Tap).
Ist eine Activity verknüpft, öffnet sich danach der optionale Dialog „Zeit
erfassen?“. Eingabe in Minuten, z. B. 5. Die Zeit wird:

- im Check-in als `duration_ms` gespeichert,
- dem **aktuellen Tag** gutgeschrieben (Zielkarte: „5 min heute gutgeschrieben“),
- in der **Aktivitätsstatistik** der verknüpften Activity als Zeit gezählt,
- **nicht** als Session/Timeline-Block angelegt.

**Rückgängig/ändern:** Jeder Check-in in der Verlaufsliste hat einen
Zeit-Button („⏱ 5 min“ bzw. „+ Zeit“). Darüber lässt sich die Zeit ändern oder
über „Zeit entfernen“ auf 0 setzen. Der Check-in selbst bleibt bestehen.
Die Activity-ID wird beim Check-in aus dem Ziel kopiert (Snapshot), damit eine
spätere Zieländerung bereits gutgeschriebene Zeit nicht rückwirkend umbucht.

**Bewusste Grenze:** Die Gutschrift ist keine Timeline-Session. Die
Dashboard-Gesamtzeit bleibt session-basiert; sichtbar wird die Gutschrift in
der Zielkarte und in der Aktivitätsstatistik. Das entspricht der Vorgabe
„dem Tag zurechnen, ohne in der Timeline aufzutauchen“.

## 3. Automatische Fahrten: Activity frei wählbar

Neue Karte in **Einstellungen → Trigger**: „Aktivität für automatische
Fahrten“ mit Activity-Picker. Default bleibt `driving` („Autofahren“), damit
Bestandsgeräte exakt das alte Verhalten zeigen.

- Die **Erkennung** (Activity Recognition + GPS, Stop-/Watchdog-Pfade) bleibt
  unverändert.
- Geändert wird nur die Activity der gestarteten Session: Name, Icon, Farbe und
  Statistik.
- `radfahren` ist nie als Fahrt-Ziel wählbar. Rad- und Fahrt-Erkennung teilen
  sich `sourceType = ACTIVITY_RECOGNITION_AUTO`; die Unterscheidung hängt an
  der Activity-ID. Ein Fahrt-Typ „radfahren“ würde Stop-Pfade falsch
  klassifizieren.
- Der Resolver fällt bei gelöschter/ungültiger Einstellung auf `driving`,
  danach auf den geschützten Typ `other` zurück — die Erkennung kann nicht
  dauerhaft stumm werden.

Damit lässt sich z. B. „Unterwegs“ als Fahrt-Activity setzen; Zug- und
Busfahrten werden weiterhin als Fahrt erkannt, aber als „Unterwegs“ geführt.

## 4. Activity Types umbenennen (auch System-Typen)

Jede Activity-Karte hat jetzt einen Stift-Button. Der Dialog ändert nur den
Anzeigenamen; die **ID bleibt stabil**. Dadurch bleiben Auto-Erkennung,
Ziele, Check-ins, Sessions und Statistiken verknüpft.

„Autofahrt“ → „Unterwegs“ ist damit direkt möglich. Der Seed-Reset beim
App-Start überschreibt nur unveränderte Seed-Namen (DE/EN); ein eigener Name
wie „Unterwegs“ bleibt erhalten.

## 5. Datenmodell und Migration (v51 → v52)

Rein additiv, keine Tabellenneubauten:

| Tabelle | Neue Spalte | Typ/Default |
|---|---|---|
| `goal_checkin` | `activity_type_id` | `TEXT`, nullable |
| `goal_checkin` | `duration_ms` | `INTEGER NOT NULL DEFAULT 0` |
| `automation_settings` | `drive_activity_type_id` | `TEXT NOT NULL DEFAULT 'driving'` |

Bestandsdaten behalten exakt das alte Verhalten: alte Check-ins haben
`duration_ms = 0` und keine Activity; bestehende Einstellungen bekommen
`driving`. `MIGRATION_51_52` liegt in `APP_DATABASE_VERSION = 52` und
`AppDatabase.ALL_MIGRATIONS`.

## 6. Tests

| Test | Prüft |
|---|---|
| `GoalProgressAnalyticsTest` (neu erweitert) | manuelles „Mindestens 5× pro Woche“: 4 von 5 nicht erfüllt, 5 von 5 erfüllt |
| `ActivityStatsAnalyticsTest` (neu erweitert) | Gutschriften zählen als Aktivitätszeit, fremde Activity zählt nicht, ohne Activity unsichtbar |
| `PendingDrivePolicyTest` (neu erweitert) | custom Fahrt-Activity ist Duplikat, Radfahrt blockiert weiterhin |
| `DriveActivityResolverTest` (neu) | Default, Trim, `radfahren`-Ausschluss |
| `SchemaV52Test` (neu) | v52 = v51 + genau drei additive Spalten, Defaults |
| `ManualGoalCreditMigrationTest` (neu) | echte SQLite 51 → Room 52: Bestands-Check-in, Einstellungen, Schreibbarkeit |
| `GoalArchitectureWiringTest` | Migrationskette enthält `MIGRATION_51_52` |
| `LanguageResourcesParityTest` | DE/EN + 9 weitere Sprachen: gleiche Keys/Platzhalter |

**Gesamt:** `1490 tests completed, 1 skipped` — alle grün.

Zusätzlich wurden alle bestehenden Migrations-/Schema-Tests von „aktuelle
Version 51“ auf 52 gezogen.

## 7. Build

```
robust_build.sh /root/ai-projects/premium-android-app aevum-m35 -- assembleDebug
BUILD SUCCESSFUL
signing-verify OK: app-debug.apk = kanonischer Key (SHA-1 9c3055c4…)
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (121 MB), VersionCode 56,
VersionName 1.0.55-debug, targetSdk 36.

Kopie für die LAN-Auslieferung:
`/srv/aevum-apk/Aevum-1.0.55-M35-Debug.apk` (Port 8098).

## 8. Bekannte Grenzen

- Kein Gerät/Emulator verbunden; die UI-Flows wurden über Logik-, Migrations-
  und Paritätstests abgesichert, nicht per Instrumentation auf Hardware.
- Die Activity-Erkennung selbst wurde nicht verändert; nur das Ziel der
  Session. Ein echter Zug-/Bus-Test auf Hardware bleibt sinnvoll.
- Zeitgutschriften erzeugen bewusst keine Session und fließen nicht in die
  Timeline ein.
