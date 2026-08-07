# M18.37 — Pauschalen sichtbar, Todos-Dashboard, Navbar-Tausch, präziser Dauer-Slider

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. Tagespauschale erscheint jetzt ÜBERALL (Root Cause GEFUNDEN)

**Ursache (gewiss):** In `InsightsAnalytics.build()` wurde `allowanceTopBreakdown` berechnet, aber **NIE in `topBreakdown` gemischt** — die Pauschalen waren toter Code. Sie gingen nur in die Gesamtsumme ein, erschienen aber nie als eigene Zeile. Deshalb sah der User "Fertig machen 30m" nirgends.

**Fixes:**
- **Insights Top-Liste:** Echte Slices + Pauschalen-Slices werden jetzt gemerged, sortiert, Top 5 → "Fertig machen (Pauschale)" erscheint mit Icon, Farbe und Dauer.
- **Insights Kreisdiagramm:** Pauschalen werden als eigene Kategorie-Slices in die `timeDistribution` gemischt.
- **Dashboard:** Neue **"Tagespauschalen"-Karte** zeigt jede enabled Pauschale explizit (Name + "30 min/Tag") — nicht mehr nur in der Summe versteckt.
- **DELETE-FIX (wichtig für dein Experiment):** Beim Löschen einer Pauschale werden jetzt auch ihre Accumulations gelöscht (neue DAO-Query `deleteAccumulationsForAllowance`). Vorher blieb die alte Accumulation in der DB → gelöschte + neu erstellte Pauschale zählte **doppelt**.
- **SELF-HEALING:** Verwaiste Accumulations (Allowance existiert nicht mehr) werden in den Insights ignoriert — deine bereits doppelt gezählte alte Accumulation ist damit wirkungslos.

## 2. Todos auf dem Dashboard

Kompakte Karte (bewusst minimal, das Dashboard ist schon dicht):
- Icon-Kreis ✅, "X offen · Y erledigt" (oder "Alle erledigt 🎉")
- Dünner Gradient-Fortschrittsbalken
- Tipp auf die Karte → öffnet den Todos-Tab
- Nur sichtbar, wenn Todos existieren

## 3. Navbar: Kalender ↔ Timeline vertauscht

Neue Reihenfolge: Heute | Insights | **Timeline** | **Kalender** | Settings

## 4. Präziser Dauer-Slider (240 min jetzt exakt treffbar)

**Ursache:** `steps = 18` bei Range 5..480 → 25-Minuten-Schritte (230 → 255, 240 unmöglich).
**Fix:**
- Slider jetzt in **5-Minuten-Schritten** (`steps = 94`)
- Dazu **−15 / −5 / +5 / +15 Rund-Buttons** für Feintuning
- Aktueller Wert groß in Monospace + Primärfarbe

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (1m16s)
- Commit: `bc05bd2`

## Test-Anleitung
1. **Pauschale:** "Fertig machen 30m" erstellen → Dashboard zeigt "Tagespauschalen: Fertig machen 30 min/Tag", Insights (Heute) zeigt "Fertig machen (Pauschale)" in der Top-Liste + Kreisdiagramm
2. **Pauschale löschen + neu erstellen:** Kein Doppel-Zählen mehr (Accumulations werden mitgelöscht)
3. **Todos:** Dashboard-Karte erscheint, Tipp öffnet Todos
4. **Navbar:** Timeline vor Kalender
5. **Dauer-Slider:** 240 min exakt einstellbar (Slider + Buttons)
