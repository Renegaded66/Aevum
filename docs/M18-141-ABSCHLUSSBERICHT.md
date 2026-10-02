# M18.141 — Erklärungsdialog vor jeder Berechtigungs-Anfrage

**Datum:** 2. Oktober 2026
**Version:** 1.0.21 (versionCode 22) — vorher 1.0.20 (versionCode 21)

---

## Auftrag

> „Damit nicht beim nächsten Update erneut bzw. neue ähnliche Fehler
> auftreten, kannst du diesen Dialog auch so einbauen, dass wenn man in den
> Einstellungen auf Trigger & Detection klickt, dass dann bei jeder einzelnen
> Berechtigung, die man erteilen will — falls sie noch nicht erteilt wurde und
> man drauf klickt — ein passendes Dialogfenster erscheint (auf Englisch und
> Deutsch), auch wenn man bereits einmal die Berechtigung erteilt und dann
> wieder verworfen hat und dann erneut erteilen will."

---

## Warum das der richtige Schritt ist

Die Play-Ablehnung von 1.0.19 traf den **Standort**, aber der Einwand war
allgemein formuliert:

> „Anfragen zur Nutzereinwilligung und zu Laufzeitberechtigungen in der App
> geht keine unmittelbare Offenlegung in der App voraus."

Das ist keine Standort-spezifische Regel. Jede Berechtigung, deren Systemdialog
ohne Erklärung in der App erscheint, hat denselben Mangel. Der teuerste Fehler
wäre gewesen, nur den Standort zu reparieren und beim nächsten Prüflauf an der
Aktivitätserkennung zu scheitern.

**Ausgangslage:** Vor diesem Commit ging der Klick auf „Aktivitätserkennung",
„Benachrichtigungen", „Nutzungszugriff" und „Kalender" direkt in den
Systemdialog bzw. in die Systemeinstellungen — ohne ein Wort dazu in der App.

---

## Umgesetzt

### Neues Modul `ui/disclosure/PermissionDisclosure.kt`

Eine generische Infrastruktur für alle Berechtigungen:

| Baustein | Zweck |
|---|---|
| `PermissionDisclosureKind` | Die vier abgesicherten Berechtigungen als Enum |
| `PermissionDisclosureText` | Pflichtinhalte als reine Daten (Titel, Einleitung, 3 Funktionen, Datenhinweis) |
| `disclosureTextFor(kind)` | Einzige Quelle der Dialoginhalte |
| `PermissionDisclosureDialog` | Der Compose-Dialog |
| `PermissionDisclosureGateState` | Entscheidet „erklären oder direkt ausführen" |
| `isPermissionGranted` | Aktueller Status |
| `willShowSystemDialog` | Kann Android den Dialog noch zeigen? |
| `PermissionDisclosureMemory` | Technischer Dialog-Verlauf (siehe unten) |
| `openAppSettings` | Führt in die App-Einstellungen |

### Die vier abgesicherten Berechtigungen

| Berechtigung | Warum sie einen Dialog braucht |
|---|---|
| `ACTIVITY_RECOGNITION` | Erkennt Fahrten, Gehen, Radfahren — läuft dauerhaft, auch bei geschlossener App |
| `POST_NOTIFICATIONS` | Ohne sie laufen automatische Aktivitätswechsel unsichtbar ab |
| Nutzungszugriff (Sonderzugriff) | Grundlage von Digital Balance — läuft über eine eigene Systemseite, nicht über den üblichen Dialog |
| `READ_CALENDAR` | Termine starten Aktivitäten — Zugriff auf persönliche Kalenderdaten |

### Inhalt jedes Dialogs

Vier Pflichtteile, in jeder Sprache:

1. **Frage als Titel** — „Aktivitätserkennung erlauben?" / „Allow activity recognition?"
2. **Einleitung** — was die Berechtigung ermöglicht UND warum sie gebraucht wird
3. **Drei konkrete Funktionen** — nicht „wird benötigt", sondern was der Nutzer davon hat
4. **Datenhinweis** — lokal verarbeitet, jederzeit widerrufbar

Alle Texte in `res/values/strings_permission_disclosure.xml` (Englisch, Fallback)
und `res/values-de/strings_permission_disclosure.xml` (Deutsch).

---

## Die zentrale Design-Entscheidung: KEINE Persistenz

Das ist der Punkt, der die Anforderung „auch nach Widerruf wieder" erfüllt.

Der Standort-Dialog merkt sich die Zustimmung (`LocationDisclosure`), weil
Google für den Hintergrund-Standort eine dokumentierte Einwilligung verlangt.
**Für diese vier Berechtigungen wäre eine gemerkte Marke ein Fehler:**

| Fall | Verhalten mit gemerkter Marke | Verhalten wie umgesetzt |
|---|---|---|
| Noch nie gefragt, Klick | Dialog erscheint | Dialog erscheint |
| Einmal abgelehnt, neuer Klick | Dialog erscheint **nicht** (Marke steht) | Dialog erscheint |
| Erteilt | egal | kein Dialog (Status ist „erteilt") |
| **Erteilt, dann widerrufen, neuer Klick** | **Dialog erscheint NICHT** — Fehler | **Dialog erscheint** — korrekt |
| Nach Grant wieder abgelehnt | Marke veraltet | Dialog erscheint |

Maßgeblich ist immer der **aktuelle Berechtigungsstatus**, nie eine
gespeicherte Antwort. Damit kann kein Zustand mit dem System auseinanderlaufen.
Ein Guard-Test schützt diese Entscheidung (siehe unten).

---

## Der Logikfehler, der beim Bauen aufgefallen ist

`shouldShowRequestPermissionRationale` (SSRPR) allein reicht NICHT, um zu
erkennen, ob Android noch einen Dialog zeigen wird:

| Zustand | SSRPR | granted |
|---|---|---|
| noch nie gefragt | `false` | `false` |
| einmal abgelehnt | `true` | `false` |
| dauerhaft abgelehnt | `false` | `false` |
| erteilt | `false` | `true` |

**„noch nie gefragt" und „dauerhaft abgelehnt" haben denselben SSRPR-Wert.**

Eine naive Auswertung hätte einem **Erstnutzer** „Einstellungen öffnen" gezeigt,
statt ihm den normalen Dialog zu geben — die Berechtigung wäre für neue Nutzer
unnötig umständlich geworden.

**Lösung:** `PermissionDisclosureMemory` merkt sich, ob für eine Berechtigung
schon einmal ein Systemdialog lief und abgelehnt wurde. Erst dann ist
`SSRPR == false` ein sicheres Signal für „dauerhaft abgelehnt". Bei einem Grant
wird der Verlauf zurückgesetzt (`clearDenied`) — sonst würde ein späterer
Widerruf fälschlich als dauerhafte Ablehnung gelten.

Der Dialog-Button heißt dann korrekt „Open settings"/„Einstellungen öffnen"
statt „Allow"/„Erlauben" — der Nutzer wird nicht in einen Dialog geschickt,
der nicht mehr erscheint.

---

## Einbaustellen

### `TriggerSettingsScreen` (Trigger & Detection)

- **Status-Karte**: alle fünf Zeilen laufen über `requestPermission(...)`
- **Vier Trigger-Toggles** (Fahrten, Walking, Rad, Step-Walk-Stop): Erklärung
  vor dem Systemdialog; `pendingTrigger` wird erst **nach** der Zustimmung
  gesetzt (im `before`-Block), damit ein Abbruch den Trigger nicht scharf stellt
- **AdditionalAutomationCard**: der zweite Einstieg in den Nutzungszugriff —
  hätte sonst einen Bypass für den Dialog dargestellt

### `CalendarRulesScreen`

Kalender-Banner läuft über das Gate. Der Banner fasst den Zweck zusammen; der
Dialog nennt zusätzlich die konkreten Funktionen und die Datenverarbeitung.

---

## Tests — und der Nachweis, dass sie greifen

**15 neue Tests** in `PermissionDisclosureTest`:

**Text-Seite (9):**
- Titel + Einleitung vorhanden, Titel ist eine Frage, Einleitung ≥ 120 Zeichen
- Mindestens drei konkrete Funktionen je Berechtigung, je ≥ 25 Zeichen
- Datenhinweis nennt lokale Verarbeitung UND Widerruf
- Hinweise behaupten keine Übertragung (Widerspruchsfreiheit zur Datenschutzerklärung)
- Zustimmungsbutton ist eine ausdrückliche Handlung, kein „OK"
- Ablehnung ist nicht als Zustimmung formuliert (kein „Weiter"/„Continue")
- Nutzungszugriff erklärt seine Sonderstellung
- Kalender-Hinweis nennt den Nur-Lesen-Zugriff
- DE/EN-Schlüsselparität

**Verdrahtung (6):**
- Beide Screens nutzen Gate + rendern den Dialog
- **Kein Berechtigungs-Request umgeht das Gate** (Kern-Guard)
- Kalender-Banner ruft `requestCalendarAccess()`
- Jeder Gate-Pfad führt bei `requiresSettings` in die Systemeinstellungen
- Der Screen persistiert KEINE Zustimmung (schützt die Design-Entscheidung)

### Gegenprobe: Die Guards können fehlschlagen

Ein Test, der nie fehlschlägt, ist wertlos. Beide Kern-Guards wurden mit
absichtlich eingebauten Fehlern geprüft:

| Sabotage | Ergebnis |
|---|---|
| Direkter `activityLauncher.launch(...)` im UI-Pfad | `kein Berechtigungs-Request umgeht das Gate` **FAILED** ✓ |
| Direkter `permissionLauncher.launch(...)` im Kalender-Banner | `Kalender-Screen nutzt das Gate` **FAILED** ✓ |

Nach Wiederherstellung: Suite grün.

**Gesamtstand: 971 Tests, 0 Fehler.**

---

## Geänderte/neue Dateien

| Datei | Änderung |
|---|---|
| `ui/disclosure/PermissionDisclosure.kt` | **neu** — Gate, Dialog, Texte, Dialog-Verlauf |
| `res/values/strings_permission_disclosure.xml` | **neu** — EN-Texte (Fallback) |
| `res/values-de/strings_permission_disclosure.xml` | **neu** — DE-Texte |
| `test/.../PermissionDisclosureTest.kt` | **neu** — 15 Guards |
| `ui/screens/settings/TriggerSettingsScreen.kt` | Gate an 7 Stellen, Launcher-Callbacks mit Verlauf |
| `ui/screens/calendar/CalendarRulesScreen.kt` | Gate am Banner |
| `app/build.gradle.kts` | versionCode 22 / 1.0.21 |

---

## Was das für die nächste Prüfung bedeutet

Die Play-Prüfung sieht jetzt in der App:

1. **Standort** → Disclosure-Dialog mit allen vier Hintergrund-Funktionen (M18.139)
2. **Aktivitätserkennung** → Erklärungsdialog mit Zweck und Funktionen
3. **Benachrichtigungen** → dito
4. **Nutzungszugriff** → dito, inklusive Hinweis auf die Sonder-Einstellungsseite
5. **Kalender** → dito, inklusive Nur-Lesen-Zusage

Jeder Dialog nennt, was die Berechtigung ermöglicht, was mit den Daten
passiert und dass sie widerrufbar ist. Kein Berechtigungs-Request erreicht den
Systemdialog ohne vorherige Erklärung in der App.

**Wiederholte Prüfläufe sind damit nicht mehr vom Zufall abhängig:** Die
Guards erzwingen, dass jeder künftige Berechtigungs-Request über das Gate
läuft. Wer einen direkten `launcher.launch(...)` einbaut, bekommt sofort einen
fehlschlagenden Test.

---

## Was Devon tun muss

Unverändert zu M18.139/M18.140:

1. **AAB mit versionCode 22 hochladen** (löst die vorherige 21 ab)
2. **Video ≤ 30 s** mit dem Disclosure-Dialog + Runtime-Prompt
3. **FGS-Anwendungsfall** auf „vehicle activity tracking"
4. **Store-Beschreibung**: englischer Text aus `docs/PLAY-STORE-BESCHREIBUNG.md`
5. Alle Test-Tracks prüfen

**Nicht mehr nötig:** Formulartexte anpassen — die Dialoge sind jetzt in der
App abgesichert, unabhängig von der Formulierung im Store.
