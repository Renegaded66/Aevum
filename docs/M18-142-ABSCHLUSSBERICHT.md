# M18.142 — Fehler aus M18.141 behoben: Standort-Dialog erschien nicht

**Datum:** 2. Oktober 2026
**Version:** 1.0.22 (versionCode 23) — vorher 1.0.21 (versionCode 22)

---

## Der gemeldete Fehler

> „Wenn ich unter Einstellungen → Trigger und Erkennung oben auf ‚Location'
> klicke, dann passiert nichts. Rechts ist aber ‚Pending' angegeben, also die
> Berechtigung wurde noch nicht erteilt. Es sollte sich zuerst der
> informierende Dialog öffnen und dann die Nachfrage der Berechtigung. Bei
> Background location öffnen sich immerhin die Einstellungen, aber auch dort
> fehlt vorher das informierende Pop-up."

Beide Beobachtungen sind korrekt und haben **dieselbe Ursache**.

---

## Die Ursache

Das Gate entschied über die **gemerkte Zustimmung**, nicht über den
**aktuellen Berechtigungsstatus**:

```kotlin
fun request(action, onReady) {
    if (LocationDisclosure.isAccepted(context)) {
        onReady(action)      // ← direkt zum Systemdialog, ohne Dialog
    } else {
        pendingAction = action      // ← nur beim allerersten Mal
    }
}
```

`isAccepted` wird durch `markAccepted()` dauerhaft gesetzt — beim App-Start,
wenn man die Offenlegung dort einmal bestätigt. Danach war der Wert für immer
`true`. Folge:

| Aktion | Erwartet | Tatsächlich |
|---|---|---|
| „Location" klicken | Dialog → dann Systemdialog | Kein Dialog; Request lief direkt. Android zeigte nach Ablehnung keinen Dialog mehr → **es passierte nichts** |
| „Background location" klicken | Dialog → dann Einstellungen | Kein Dialog; **sofortiger Sprung** in die Einstellungen |

Der Zustand `Pending` in der UI war korrekt — die Berechtigung fehlte ja. Nur
die Entscheidung „muss ich noch erklären?" wurde an der falschen Quelle
getroffen.

**Das war ein Widerspruch zur Play-Auflage:** Google verlangt die Offenlegung
vor *jedem* Request einer Laufzeitberechtigung, nicht nur beim ersten Mal.
Ein Prüfer, der die Berechtigung nach dem ersten Dialog ablehnt und dann erneut
auf „Location" tippt, hätte wieder keine Offenlegung gesehen — und der Fehler
wäre bei der nächsten Prüfung erneut aufgetreten.

**Und es widersprach der Anforderung aus M18.141:** Dort galt für alle anderen
Berechtigungen ausdrücklich „maßgeblich ist der aktuelle Berechtigungsstatus,
damit der Dialog nach einem Widerruf erneut erscheint". Beim Standort — der
wichtigsten Berechtigung — war genau das noch nicht umgesetzt.

---

## Die Korrektur

### 1. Das Gate entscheidet über den Berechtigungsstatus

```kotlin
fun needsDisclosure(action: Action, alreadySatisfied: Boolean): Boolean = !alreadySatisfied
```

`LocationDisclosureGateState.request()` ermittelt `alreadySatisfied` selbst:

- `REQUEST_FOREGROUND_LOCATION` → `ACCESS_FINE_LOCATION` **oder**
  `ACCESS_COARSE_LOCATION` erteilt
- `REQUEST_BACKGROUND_VIA_SETTINGS` → `ACCESS_BACKGROUND_LOCATION` erteilt
  (auf Android < 10 immer `true`, dort gab es die Berechtigung nicht)

Die Regeln im Klartext:

| Berechtigung | Verhalten |
|---|---|
| erteilt | nichts zu erklären → direkt ausführen |
| **nicht erteilt** | **Offenlegung zeigen, dann anfragen** |
| nach Widerruf wieder offen | Offenlegung erscheint erneut |

`isAccepted` dient jetzt nur noch dem Nachweis der dokumentierten Einwilligung
(Aufbewahrungspflicht) — **niemals** als Abkürzung.

### 2. Fallback, wenn Android keinen Systemdialog mehr zeigt

Nach zweimaliger Ablehnung führt `launch(...)` ins Leere — Android ignoriert
den Request ohne Rückmeldung. Genau daher kam das „es passiert nichts".

`runLocationAction()` prüft jetzt vorher mit `willShowSystemDialog(...)`. Ist
kein Dialog mehr möglich, geht es direkt in die App-Einstellungen — der Nutzer
bekommt immer eine sichtbare Reaktion.

### 3. Verlaufseintrag für den Standort

`shouldShowRequestPermissionRationale` liefert für „noch nie gefragt" und
„dauerhaft abgelehnt" denselben Wert. Der Standort braucht deshalb — wie die
übrigen Berechtigungen — einen Verlaufseintrag:
`LOCATION_MEMORY_KEY = "LOCATION_FOREGROUND"`, zentral im Disclosure-Modul
definiert, damit beide Screens denselben Eintrag nutzen.

### 4. Geofence-Editor ebenfalls abgesichert

Derselbe Fehler steckte im Geofence-Editor (`AutomationScreens.kt`): der
Launcher hatte einen **leeren Callback** (`{ }`) und keinen Fallback. Drei
Einbaustellen laufen jetzt über eine gemeinsame Funktion
`requestForegroundLocation()`; der Launcher wird nur noch an **einer** Stelle
gestartet. Der Launcher-Callback pflegt außerdem den Verlauf.

---

## Tests — und die Gegenprobe

**10 neue Tests** in `LocationDisclosureRegressionTest`.

Der Test dokumentiert den Befund wörtlich und prüft die Entscheidungslogik
sowohl über die echte Gate-API als auch quelltextbasiert:

| Test | Prüft |
|---|---|
| `bestaetigte Offenlegung unterdrueckt den Dialog nicht` | Kernaussage: accepted=true + nicht erteilt → Dialog |
| `nur der erteilte Zustand ueberspringt den Dialog` | Dialog nur bei tatsächlich erteilter Berechtigung (auch bei accepted=false) |
| `Gate entscheidet nicht ueber isAccepted` | Quelltext: keine Rückkehr zur alten Bedingung |
| `Location-Pfad hat einen Fallback wenn Android keinen Dialog zeigt` | `willShowSystemDialog` + `openAppDetails` |
| `Standort hat einen Dialog-Verlaufsschluessel` | Verlauf wird gepflegt und bei Grant zurückgesetzt |
| `beide Location-Zeilen laufen ueber das Gate` | Vordergrund **und** Hintergrund |
| `auch der Geofence-Editor hat den Fallback` | zweiter Screen, genau ein Launcher-Start |
| `beide Standort-Screens teilen den Verlaufsschluessel` | keine lokale Zweitdefinition |
| `Start-Offenlegung und Gate sind unabhaengig` | kein Rückfall auf `isAccepted` |

### Gegenprobe: Die Guards fangen den Originalfehler

| Sabotage | Ergebnis |
|---|---|
| `if (LocationDisclosure.isAccepted(context))` wieder eingebaut | `Gate entscheidet nicht ueber isAccepted` **FAILED** ✓ |
| Fallback-Prüfung entfernt (`if (true)`) | `Location-Pfad hat einen Fallback…` **FAILED** ✓ |

Beide danach wiederhergestellt, Suite grün.

**Gesamtstand: 981 Tests, 0 Fehler.**

---

## Warum das nicht schon in M18.141 aufgefallen ist

Der Guard aus M18.139 prüfte, **dass** das Gate benutzt wird — nicht, **wie es
entscheidet**. Ein Screen, der `disclosureGate.request(...)` aufruft, bestand
den Test, auch wenn das Gate intern sofort durchleitete.

Die Lehre: Ein Verdrahtungs-Guard muss bis zur **Entscheidungslogik** reichen.
Genau das leisten die neuen Tests — der alte Guard bleibt zusätzlich bestehen.

---

## Geänderte Dateien

| Datei | Änderung |
|---|---|
| `ui/disclosure/LocationDisclosure.kt` | Gate entscheidet über Berechtigungsstatus; `alreadySatisfied` |
| `ui/disclosure/PermissionDisclosure.kt` | `LOCATION_MEMORY_KEY` zentral; `willShowSystemDialog` für beliebige Berechtigungen |
| `ui/screens/settings/TriggerSettingsScreen.kt` | Fallback im Standort-Pfad, Verlaufspflege |
| `ui/screens/automation/AutomationScreens.kt` | einheitlicher Pfad `requestForegroundLocation()`, Fallback, Verlauf |
| `test/.../LocationDisclosureRegressionTest.kt` | **neu** — 10 Guards |
| `test/.../LocationDisclosureTest.kt` | an die neue Gate-Signatur angepasst |
| `app/build.gradle.kts` | versionCode 23 / 1.0.22 |

---

## Was Devon tun muss

1. **Version 1.0.22 (versionCode 23) hochladen** — löst die 22 ab
2. Prüfen: Einstellungen → Trigger & Erkennung → „Location" und
   „Background location" antippen
   - Berechtigung fehlt → **erst der Dialog**, dann die Systemabfrage
   - Berechtigung erteilt und wieder entzogen → **Dialog erscheint erneut**
3. Video ≤ 30 s mit dem Dialog (unverändert)
4. FGS-Anwendungsfall auf „vehicle activity tracking" (unverändert)
5. Englische Store-Beschreibung aus `docs/PLAY-STORE-BESCHREIBUNG.md` (unverändert)
