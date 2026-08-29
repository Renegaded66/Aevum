# M18.81 — Abschlussbericht: Dashboard-Redesign (Aufräumen & Priorisieren)

> Stand: 2026-08-29
> Scope: Dashboard-UI only — keine Logik-Änderungen, kein ViewModel-Touch, keine String-Ressourcen angefasst.

## Ausgangslage

Das M18.7-Dashboard („Der Puls deines Tages") war als bewusst reduzierter
Screen geplant. Durch iterierte Feature-Wünsche (M18.37 Todos/Pauschalen,
M18.58 Güte-Trend + Garmin-Kacheln, M18.60 Tages-Navigation +
Pauschal-Overrides, M18.66 Zone-Banner + Top-5) war der Screen auf
**12 List-Sektionen** angewachsen:

1. Live-Banner (conditional)
2. ZoneBanner
3. PulsHero
4. DayNavigationPill (eigene Sektion)
5. QualityTrendCard
6. GarminTilesRow
7. QualityBreakdownBars (Top-5)
8. InsightStrip
9. DashboardTodosCard (conditional)
10. DashboardAllowancesRow (conditional)
11. ReviewHintCard (conditional)
12. Bottom-Spacer

Das kollidiert mit der UI_GUIDELINES-Regel „Above the fold: alles auf einen
Blick, kein Scroll nötig" und mit der M18.7-Design-Philosophie („Nur Daten,
die die eine Frage beantworten: Wie gut habe ich meine Zeit heute genutzt?").

## Redesign (M18.81)

**Leitlinie: konsolidieren, nicht löschen.** Kein Inhalt wurde entfernt, nur
zusammengeführt. Zwei Entscheide wurden gesetzt:

1. **Tag-Nav in den Hero integrieren** — thematisch gehört die Tag-Navigation
   zum Tag, den der Hero zeigt (alle Hero-Werte inkl. Güte-Override gelten
   für `displayedDate`).
2. **Top-5-Balken + Insights zu einer Reflexions-Karte fusionieren** — beide
   beantworten zusammen die Frage „Wo ging meine Zeit hin — und was fällt mir
   dabei auf?".

Ergebnis: 12 → **10 Sektionen** (−2); zwei der drei unbedingten Sektionen
sind fusioniert.

### 1. Tag-Nav im Hero

- Vorher: eigenständige `DayNavigationPill`-Sektion direkt unter dem Hero.
- Jetzt: `HeroDayNavigation` als erste Zeile IM Hero-Header — die ‹ Datum ›-Pill
  sitzt dezent über dem QualityRing-Block (Hintergrund-Alpha 0.30, vertikales
  Padding 2dp, Pfeile 17sp — etwas dezenter als die alte Pill).
- Verhalten unverändert: ‹/›-Navigation, rechte Pfeiltaste gedimmt bei
  „heute", „Heute"-Reset-Chip nur sichtbar, wenn von heute weg navigiert.
- `DayNavigationPill` ist gelöscht; Referenzen im gesamten Modul: 0.

### 2. Reflexions-Karte

- Neu: `DayReflectionCard` (AevumCard, Elevated) enthält „Wo deine Zeit
  hingeht" (Top-5-Bars inkl. Kaskaden-Animation wie M18.66-FIX16) sowie die
  max. 2 Insights als `InsightStrip` untereinander (Spacing md).
- Sichtbar, wenn `qualityBreakdown` ODER `insights` nicht leer ist.
- Optik der Sub-Komponenten unverändert — nur die Hülle ist neu.

### 3. Reihenfolge (neu)

1. Live-Banner (conditional)
2. ZoneBanner
3. PulsHero (enthält jetzt die Tag-Nav)
4. QualityTrendCard
5. GarminTilesRow
6. DayReflectionCard (Top-5-Bars + Insights)
7. DashboardTodosCard (conditional)
8. DashboardAllowancesRow (conditional)
9. ReviewHintCard (conditional)
10. Bottom-Spacer

Die KDoc des Screens (Layout-Übersicht 1–6) wurde aktualisiert.

## Verifikation

```bash
./gradlew compileDebugKotlin --no-daemon --console=plain --max-workers=1 \
  -Dkotlin.compiler.execution.strategy=in-process
# BUILD SUCCESSFUL in 1m 12s
# Nur 4 vorbestehende Unused-Parameter-Warnungen (onOpenTimeline,
# onOpenSleepStatus, onOpenUsageSettings, onDiscardLive) —
# keine neuen Warnungen/Fehler durch M18.81.
```

Unit-Tests + assembleDebug: laufen in diesem Run (Ergebnis im Task-Kommentar).

## Bekannte Einschränkungen

- Kein ADB-Gerät in der Umgebung — On-Device-Eindruck (Scroll-Gefühl,
  Hero-Dichte) nicht prüfbar. Empfehlung: kurz auf dem Gerät ansehen und
  ggf. die Hero-Nav-Dezente (alpha 0.30) nachjustieren.

## Nächste Schritte (Vorschläge, nicht Teil von M18.81)

- Insights in der Reflexions-Karte tappable machen (Deep-Link in die jeweilige Detailansicht).
- Optional: QualityTrendCard und GarminTilesRow zu einem „Verlauf"-Cluster mit Switcher zusammenfassen (weiterer Sektions-Sparpotenzial von −1).