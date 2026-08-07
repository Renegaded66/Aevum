# M18.38 — Pauschale in Dashboard-Balken + Todo-Edit-Ansicht

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. Pauschale in den Dashboard-Balken ("WO DEINE ZEIT HINGEHT")

**User-Logik umgesetzt:** Die Pauschale erscheint als eigener Balken, sobald die Tageszeit die Pauschaldauer überschritten hat:
- "Fertig machen 30m" → erscheint ab **00:30** (Tagesminute ≥ 30)
- Ist der Tag schon weiter (z.B. 14:00), erscheint sie **sofort**
- Balken mit **⏱-Marker** gekennzeichnet, damit sie sich von echten Aktivitäten unterscheidet
- `take(6)` statt `take(5)`, damit die Pauschale nicht von echten Aktivitäten verdrängt wird
- Farbe = Positivitäts-Score der zugeordneten Aktivität (wie alle anderen Balken)

## 2. Todo-Edit-Ansicht

**Jede TodoCard hat jetzt einen Stift-Button (✏️)** — daneben Archiv und Löschen:
- Öffnet den bekannten Editor, aber im **Edit-Modus**:
  - Titel "TODO BEARBEITEN" / "Aufgabe anpassen"
  - Alle Felder sind **vorbefüllt**: Titel, Dauer-Ziel (inkl. Minuten), Aktivität, Wiederholung (inkl. gewählter Wochentage — Bitmask wird zurückgewandelt), Fälligkeit
  - Button: "Änderungen speichern"
- `save()` **aktualisiert** das bestehende Todo (gleiche ID, `updatedAt` neu) statt ein neues anzulegen — keine Duplikate, Completions bleiben erhalten

**Technik:**
- `TodoEditorViewModel.loadTodo(todoId)` füllt den Form-State
- `RecurrenceEngine.weekdaysFromBitmask()` (neu) wandelt die Bitmask zurück
- Neue Route `todo/edit/{todoId}` im NavHost

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (58s)
- Commit: `e8c404a`

## Test-Anleitung
1. **Dashboard:** Pauschale "Fertig machen 30m" → Balken "⏱ Fertig machen 30m" erscheint (ab 00:30 bzw. sofort, wenn der Tag weiter ist)
2. **Todos:** Stift-Icon auf einer Karte → Editor mit vorbefüllten Werten → ändern → "Änderungen speichern" → Todo ist aktualisiert (kein Duplikat)
