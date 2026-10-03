# M18.145 — Play-Konsistenz: drei Offenlegungs-Lücken geschlossen

**Datum:** 3. Oktober 2026
**Version:** 1.0.24 (versionCode 25) — vorher 1.0.23 (versionCode 24)
**Auslöser:** Prüfauftrag Devon — Abgleich des Code-Stands gegen die beiden
Play-Ablehnungsmails

---

## Vorbemerkung zur Einordnung der Mails

Beide Mails sind die **Benachrichtigung zur Ablehnung von 1.0.19
(versionCode 20)** — jener Einreichung, die M18.139–M18.142 bereits adressiert
haben. Belege:

| Beleg | Wert |
|---|---|
| Mail-Datum | Fri, 02 Oct 2026 21:01 −0700 = **03.10. 06:01 CEST** |
| Gmail-Empfang (`INTERNALDATE`) | 03-Oct-2026 04:01:02 +0000 |
| Ablehnungsobjekt | Update **1.0.19**; die Fixes entstanden am 02.10. ab 10:26 |
| Anzahl Aevum-Ablehnungen im Postfach | genau diese zwei (kein Vorgänger, kein Nachfolger) |
| Anhang | `IN_APP_EXPERIENCE-7836.png` (Google-Beweis-Screenshot) |

Google schreibt in beiden Mails „**Changes to your app weren't published**"
und „If you have an older version of your app, it will still be available" —
die Mails bestätigen also die *nicht* veröffentlichte 1.0.19, nicht einen neuen
Prüflauf eines gefixten Builds.

### Was der Google-Screenshot zeigt (ausgewertet)

`IN_APP_EXPERIENCE-7836.png` (1919×748, fünf Panels) zeigt den Weg, auf dem die
Prüfung den Verstoß feststellte:

1. Dashboard („Your day is still a blank page.")
2. Einstellungen
3. **„Triggers & detection"** — alle fünf Berechtigungen auf `Pending`
4. **System-Standortdialog — ohne Aevum-Dialog davor** ← der Befund
5. „Triggers & detection" nach Erteilung

Der Screenshot belegt wörtlich den Mailtext: „*Anfragen zur Nutzereinwilligung
und zu Laufzeitberechtigungen in der App geht keine unmittelbare Offenlegung in
der App voraus.*" Die Screens zeigen keine Spur einer Offenlegung — M18.139 war
zum Zeitpunkt der Prüfung nicht im Build.

---

## Der eigentliche Befund dieser Runde

M18.139/M18.141 haben den Dialog an den Stellen eingebaut, die **damals
bekannt** waren. Beim systematischen Nachprüfen aller Pfade — nicht nur der
zwei bekannten Dateien — sind drei Einstiege aufgefallen, die weiterhin
**direkt** in eine sensible Systemseite sprangen:

| # | Stelle | Weg | Vorher | Jetzt |
|---|---|---|---|---|
| **a** | `DigitalBalanceScreen` | **Bottom-Nav-Tab „Balance"** | `PermissionCard(onOpenSettings = viewModel::openUsageAccessSettings)` → direkt in den Nutzungszugriff | Gate + Dialog |
| **b** | `AppTrackingScreen` | über den Balance-Tab | `PermissionHint(onOpenSettings = viewModel::openUsageAccessSettings)` → direkt | Gate + Dialog |
| **c** | `CalendarRulesScreen` | Kalender-Regeln, `PermanentlyDenied` | Banner-Button `onOpenSettings = viewModel::openSettings` → direkt in die App-Einstellungen | Gate + Dialog |

**Warum (a) besonders schwer wog:** „Balance" ist ein **Haupt-Tab in der
Bottom-Navigation** (`MainActivity.bottomTabs`, Zeile 171). Ein Prüfer erreicht
den Screen ohne jeden Umweg über ein Menü — und bekam dort genau das Muster
präsentiert, das die Richtlinie verbietet: Klick → Systemseite, kein Wort in der
App. Der Screenshot der Prüfung zeigt den Weg über die Einstellungen; der
Balance-Tab wäre der nächste Befund gewesen.

**Warum (c) zählte:** Der `PermanentlyDenied`-Zweig erscheint genau dann, wenn
der Nutzer die Berechtigung dauerhaft abgelehnt hat. Die Offenlegung ist dort
**besonders** erforderlich — die Richtlinie verlangt sie vor *jeder* Anfrage,
und die Systemseite ist eine Anfrage-Handlung.

---

## Warum der alte Guard das nicht gefunden hat

Der M18.141-Guard `kein Berechtigungs-Request umgeht das Gate` prüfte
**zwei Dateien** (`TriggerSettingsScreen`, `CalendarRulesScreen`) auf direkte
`launcher.launch(`-Aufrufe. Er konnte nur finden, was er kannte:

- Er prüfte **Launcher-Starts**, nicht **Settings-Sprünge** — genau die
  Sonderzugriffs-Pfade (Nutzungszugriff läuft nie über einen Launcher) waren
  außerhalb seines Blickfelds.
- Er prüfte eine **Positivliste** statt der Gegenrichtung.
- `DigitalBalanceScreen` und `AppTrackingScreen` standen in keiner Liste.

Die Lehre aus M18.142 („ein Verdrahtungs-Guard muss bis zur
Entscheidungslogik reichen") wird hier fortgesetzt: **ein Guard muss über die
Gegenrichtung laufen, nicht über eine Liste bekannter Dateien.**

---

## Die Lösung

### 1. Die drei Stellen laufen über das Gate

`DigitalBalanceScreen`:

```kotlin
permissionGate.pending?.let { pending ->
    PermissionDisclosureDialog(
        kind = pending.kind,
        requiresSettings = pending.requiresSettings,
        onAllow = { permissionGate.consent { viewModel.openUsageAccessSettings() } },
        onDecline = { permissionGate.dismiss() }
    )
}
...
PermissionCard(
    onOpenSettings = {
        permissionGate.request(
            PermissionDisclosureKind.USAGE_ACCESS,
            alreadyGranted = false
        ) { viewModel.openUsageAccessSettings() }
    }
)
```

`AppTrackingScreen` analog (dort auch der zweite Einstieg über `PermissionHint`).
`CalendarRulesScreen` bekam eine eigene Funktion
`openCalendarSettingsViaGate()`, die der Banner jetzt für den
`PermanentlyDenied`-Zweig nutzt.

### 2. Neuer Guard `DisclosureCoverageTest` (5 Tests)

Der entscheidende Unterschied: Der Test prüft **alle** `*Screen*.kt`-Dateien
unter `app/src/main/java` und verlangt für jede Datei, die einen
Sonderzugriff öffnet, ein vollständiges Gate (Gate-Erzeugung, Dialog-Rendering,
`consent`, `dismiss`).

| Test | Prüft |
|---|---|
| `jeder Screen mit Sonderzugriff nutzt das Disclosure-Gate` | Die drei Screens haben Gate + Dialog + consent + dismiss |
| `kein Screen oeffnet den Sonderzugriff per direktem Methodenverweis` | Kein `viewModel::openUsageAccessSettings` / `::openSettings` / `::openCalendarSettings` mehr (genau der Bypass-Typ) |
| `projektweit oeffnet keine Screen-Datei einen Sonderzugriff ohne Gate` | **Gegenrichtung:** alle `*Screen*.kt` mit `ACTION_USAGE_ACCESS_SETTINGS` / `UsageStatsPermission.openSettings` / `openUsageAccessSettings(` müssen ein Gate haben — plus Mindest-Suchbasis |
| `die Erkennung findet einen eingebauten Bypass` | **Gegenprobe:** die Marker- und Gate-Logik erkennt einen simulierten Direktaufruf wirklich |
| `die Screens nutzen den passenden Berechtigungs-Dialog` | Balance/Tracking → `USAGE_ACCESS`, Kalender → `CALENDAR` |

### 3. Gegenprobe mit echtem Bypass (verifiziert)

Kein Guard, der nie fehlschlagen kann:

| Sabotage | Ergebnis |
|---|---|
| Bypass in `AppTrackingScreen` zurückgebaut (`viewModel::openUsageAccessSettings`) **und** Dialog-Rendering in `DigitalBalanceScreen` entfernt | **4 von 5 Tests FAILED** ✓ |

Danach wiederhergestellt, Suite grün.

---

## Tests

| Klasse | Tests | Status |
|---|---|---|
| `DisclosureCoverageTest` | **5 neu** | grün |
| `PermissionDisclosureTest` | 15 | grün |
| `LocationDisclosureTextTest` | 14 | grün |
| `LocationDisclosureTest` | 9 | grün |
| `LocationDisclosureWiringTest` | 9 | grün |
| `LocationDisclosureRegressionTest` | 9 | grün |
| **Gesamtsuite** | **1006** | **0 Fehler** |

### Gegenprobe vor dem Fix

Die drei Stellen wurden zuerst belegt (Quelltext-Lesung aller Pfade in die
Systemeinstellungen), dann der Test geschrieben, dann gefixt.

---

## Geänderte Dateien

| Datei | Änderung |
|---|---|
| `ui/screens/digitalbalance/DigitalBalanceScreen.kt` | Gate + Dialog + `PermissionCard` über Gate |
| `ui/screens/apptracking/AppTrackingScreen.kt` | Gate + Dialog + `PermissionHint` über Gate |
| `ui/screens/calendar/CalendarRulesScreen.kt` | `openCalendarSettingsViaGate()` + Banner verdrahtet |
| `test/.../DisclosureCoverageTest.kt` | **neu** — 5 Guards, projektweite Gegenrichtung |
| `app/build.gradle.kts` | versionCode 25 / 1.0.24 |

---

## Was Devon noch tun muss (Play Console)

Unverändert zu M18.139–M18.142 — die App-Seite ist mit diesem Commit vollständig:

1. **AAB mit versionCode 25 (1.0.24) hochladen** und einreichen.
2. **Video ≤ 30 s** für das Background-Location-Formular: die Funktion aus dem
   Hintergrund aktiviert, **der Prominent-Disclosure-Dialog**, danach der
   Runtime-Prompt. Aufnahme auf einem Android-Gerät.
3. **Nur EINE Funktion im Formular nennen** — Empfehlung **Fahrterkennung**
   (Google wertet mehrere Nennungen als Ablehnungsgrund). Textbausteine:
   `docs/PLAY-STORE-BESCHREIBUNG.md` § 5.
4. **FGS-Anwendungsfall** für `location` auf
   „Background Location Updates: **vehicle activity tracking**" —
   **nicht** „Geofencing" (seit 26.08.2026 kein zulässiger Anwendungsfall).
5. **Store-Beschreibung** (englisch) aus `docs/PLAY-STORE-BESCHREIBUNG.md`
   übernehmen — Google verlangt die Offenlegung „within the app itself **as well
   as in the app description and website**".
6. **Alle Tracks prüfen** (Closed/Open Testing): dort dürfen keine Alt-APKs mit
   `FOREGROUND_SERVICE_LOCATION` aus Geofence-Zeiten liegen.
7. **Datenschutz-URL** im Store-Eintrag bleibt
   `https://renegaded66.github.io/Aevum/`.
8. **Data-Safety-Formular**: „Genauer Standort" = erhoben, Zweck
   „App-Funktionalität".

---

## Offener Punkt / Testbedarf am Gerät

Nicht automatisch prüfbar (kein Emulator mit `/dev/kvm`):

- Balance-Tab → Button „Berechtigung erteilen" → **erscheint der
  Erklärungsdialog** und erst danach die Systemseite?
- App-Aufzeichnung (über Balance-Tab) → derselbe Ablauf.
- Kalender-Regeln mit dauerhaft abgelehnter Berechtigung → Banner-Button
  „Einstellungen öffnen" → **erst Dialog**, dann App-Einstellungen.

Wegtippen des Dialogs muss folgenlos bleiben (kein Sprung in die Systemseite).

---

## Rollback

Ein Commit. `git revert <commit>` genügt; der neue Guard fällt mit ihm weg.
