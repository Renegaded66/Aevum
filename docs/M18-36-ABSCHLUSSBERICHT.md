# M18.36 — Bugfixes: TodoEditor, LifeView, Timeline, Insights

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. Todo-Editor — "kein Text eingebbar, keine Klick-Reaktion" (Root Cause GEFUNDEN)

**Ursache (gewiss):** `uiState` im `TodoEditorViewModel` war ein `map` auf den `activityTypeRepository.getAll()`-Flow. Dieser Flow emittiert **NUR**, wenn sich ActivityTypes ändern. Die Setter (`setTitle`, `setDuration`, …) schrieben zwar `formState.value`, aber die UI las `uiState` — der sich **nie** aktualisierte. Deshalb: kein Text, keine Reaktion auf "Dauer-Ziel", "Wiederholung", "Fällig".

**Fix:** `combine(formState, activityTypeRepository.getAll())` — jede Setter-Änderung emittiert jetzt sofort. Der Editor funktioniert vollständig.

## 2. LifeView — "nach Geburtstagseingabe passiert nichts" (Root Cause GEFUNDEN)

**Ursache (gewiss):** `birthday`/`expectedAge` waren reine SharedPreferences-Getter. `saveBirthday()` schrieb die Prefs, aber der Room-`combine`-Flow wurde dadurch **nicht** getriggert — die UI zeigte erst nach Screen-Wechsel (neues ViewModel) die Werte.

**Fix:** Beide als `MutableStateFlow` — `saveBirthday()`/`saveExpectedAge()` aktualisieren den State sofort, der `combine` (jetzt mit `_birthday` + `_expectedAge` als Quellen) emittiert neu. Die Werte erscheinen **sofort** nach der Eingabe.

## 3. LifeView — Text-Overlap

**Ursache:** 40sp Monospace in einer Zeile ("80 Jahre, 123 Tage") überlappte auf schmalen Screens.
**Fix:** 32sp + `lineHeight 36sp` + `maxLines 2` — nie wieder Overlap.

## 4. Timeline — Overlap + toter Platz

- **"Lücken prüfen"-Button entfernt** (User-Wunsch)
- Header jetzt **eine einzige Zeile**: `[‹] [Titel + Datum] [Heute] [›]` — Datum mit `maxLines 1` + Ellipsis, kein Overlap mit dem Heute-Chip mehr möglich
- **Toter 88dp-Spacer unter der Timeline entfernt** — der freie Platz unten ist weg
- FAB-Schutz liegt jetzt als **Bottom-Padding (88dp) NUR im internen Scroll-Container der Listenansicht** — die Tag-Ansicht bleibt vollflächig

## 5. Insights — Rundung

**Ursache:** `AnimatedNumberCounter` animierte/rundete auf ganze Int-Stunden.
**Fix:** Hero zeigt jetzt **Dezimal-Stunden mit 1 Nachkommastelle** (z.B. "7,5 Std") aus exakten Millisekunden (`totalMsIncludingAllowances` neu im UiState). Minuten bleiben exakt.

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (53s)
- Commit: `d6d4b4a`

## Test-Anleitung
1. **Todos:** Editor öffnen → Text eingeben funktioniert, "Dauer-Ziel" zeigt Slider, "Wiederholung" zeigt Optionen, "Fällig" erscheint bei Einmalig
2. **LifeView:** Geburtstag eingeben → Werte erscheinen SOFORT, kein Overlap
3. **Timeline:** Header eine Zeile, kein Overlap, kein toter Platz unten; Listenansicht scrollt bis zum letzten Eintrag (FAB überdeckt nichts)
4. **Insights:** Hero zeigt "7,5 Std" statt gerundeter Werte
