# M34 — Willkommens-Dialog & elf Sprachen

**Version:** 1.0.54 (versionCode 55) · **Datum:** 31.10.2026 · **Status:** fertig gebaut, Tests grün

---

## 1. Was gebaut wurde

Ein fünfseitiger Einrichtungs-Wizard, der **nur nach einer Neuinstallation** erscheint.

| Seite | Inhalt | Wirkung |
|---|---|---|
| 1 | Sprache (11 Einträge, Englisch vorausgewählt) | ab hier ist der Rest des Wizards sofort in dieser Sprache |
| 2 | Lebensbereiche + Aktivitäten (vorausgewählt, entfernbar, ergänzbar) | das Dashboard ist beim ersten Blick nicht leer |
| 3 | Maskottchen (an/aus, Farbe, Antenne, Bäckchen) | Bindung an ein „Wesen“, das den Nutzer begleitet |
| 4 | Berechtigungen (einzeln schaltbar, jede mit Begründung) | Kontrolle statt Überrumpelung |
| 5 | „Ganz deins.“ — kostenlos, lokal, in deiner Hand | Vertrauen, dann „Los geht’s“ |

Navigation: **Zurück/Weiter** auf jeder Seite, System-Zurück führt eine Seite zurück, fünf Punkte zeigen die Position (aktiver Punkt als Pille), der Fortschritt läuft über einen `HorizontalPager` — Wischen funktioniert zusätzlich.

**Bestehende Installationen sehen nichts davon.** `OnboardingGate.shouldShow()` prüft zwei Dinge: das Abschluss-Flag ist nicht gesetzt **und** `firstInstallTime ≈ lastUpdateTime`. Bei einem Update wird das Flag still gesetzt. Kann der Paketmanager nicht gelesen werden, gibt die Funktion `false` zurück — im Zweifel kein Wizard.

## 2. Elf Sprachen

Neu: **Spanisch, Französisch, Portugiesisch (pt-PT), Türkisch, Polnisch, Dänisch, Norwegisch (Bokmål), Schwedisch, Italienisch** — dazu Deutsch und Englisch.

- **Eine Quelle:** `util/LanguageCatalog.kt` (`AppLanguage`-Enum mit Code, Flagge, Label-Ressource, BCP-47-Tag). Onboarding, Einstellungen und `LocaleHelper` lesen nur noch daraus.
- **Sofortige Anwendung im Wizard:** Compose-`stringResource` sieht eine neue Sprache erst nach einem Activity-Recreate. Der Wizard stellt deshalb `LocalOnboardingRes` bereit und liest alle Texte über `oStr(...)`. Seite 1 umstellen → Seite 2–5 sind sofort mit umgestellt.
- **Endonyme bleiben unübersetzt** (Deutsch, Español, Français …), damit jeder seine Sprache erkennt, egal was gerade eingestellt ist.
- **Einstellungen:** Die Sprachliste kommt aus dem Katalog, alle elf sind dort wählbar.
- **Umfang:** 1.847 Strings × 10 Sprachordner (`values-de` … `values-tr`), je 20 Dateien.

## 3. Keine festen Texte mehr

`docs/tools/audit_hardcoded_strings.py` findet menschenlesbare Literale in Kotlin. Behoben wurden u. a.:

- Karten-Fallback und Marker-Titel im Standort-Verlauf (`place_timeline_map_unavailable`, `place_timeline_marker_unnamed`)
- Breiten-/Längengrad-Labels in den Automatisierungs-Screens
- `contentDescription` des Aktivitäts-Diagramms
- Wizard-Texte: keine `String.format("")`-Platzhalter, keine `uppercase()`-Tricks (türkisches i/İ)

Der Audit hat bewusst Ausnahmen: Log-Meldungen, `testTag`, SQL, MIME-Typen, `const val`.

## 4. Tests (alle grün)

| Test | Prüft | Anzahl |
|---|---|---|
| `OnboardingGateTest` | Neuinstallation → Wizard, Update → kein Wizard, Flag | 6 |
| `OnboardingPagesTest` | echte Compose-Hierarchie: Sprachwechsel wirkt auf Seite 2–5, jede Berechtigung einzeln schaltbar, Zähler, Maskottchen | 12 |
| `LanguageResourcesParityTest` | pro Sprache: gleiche Keys, gleiche Platzhalter, nichts leer, keine vergessenen Absätze | 6 |
| `LocaleSwitchTest` | Katalog ↔ Repository-Konstanten, Locale + Ressourcen für jede Sprache | 9 |
| `OnboardingScreenshotTest` | rendert alle fünf Seiten (auch komplett mit Kopf-/Fußzeile) als PNG | 15 |

**33 Testfälle + 15 Renderings.** Die Screenshots liegen in `app/build/screenshots/`.

Die Paritätstests haben zwei echte Fehler gefunden, bevor der Build sie gefunden hätte: fehlende `</resources>`-Abschlüsse und doppelte Backslashes vor Apostrophen (AAPT bricht daran ab). Beide sind behoben; `docs/tools/validate_language.py` prüft das jetzt für jede Sprache einzeln.

## 5. Feinheiten, die man nicht sieht

- **Seed-Sperrliste** (`SeedSuppressionStore`, SharedPreferences): Wer im Wizard „Arbeit“ entfernt, bekommt sie nicht beim nächsten App-Start wieder. Schlüssel mit Präfix (`category:`, `activity:`, `tag:`), weil Kategorien und Aktivitäten dieselben IDs teilen.
- **Seed-Namen in allen Sprachen** vorab erzeugt: Sonst hieße eine im Wizard angelegte Kategorie nach einem Sprachwechsel wieder englisch.
- **Eigene Einträge** aus dem Wizard werden nur einmal automatisch ausgewählt (`pendingAutoSelect`) — ein absichtlich abgewählter eigener Eintrag kommt nicht zurück.
- **Offenlegung:** Der Wizard ist die Offenlegung. Der separate Standort-Dialog wird beim Erststart unterdrückt, sonst läge er über der Einrichtung. Wird der Standort im Wizard erteilt, gilt die Offenlegung als bestätigt.
- **Nutzungszugriff** ist keine Runtime-Berechtigung — die Systemseite öffnet sich nur, wenn der Nutzer sie ausdrücklich einschaltet.
- **Maskottchen** ist im Wizard standardmäßig **aus** (bewusst: Zustimmung statt Voreinstellung), die Vorschau zeigt trotzdem, wie es aussieht.
- **Dashboard** liest die Werte nach dem Abschluss frisch: Maskottchen, Sprache und Aktivitäten sind beim ersten Blick da.

## 6. Gestaltung

Ruhiger vertikaler Verlauf statt Vollton, ein einzelner Lichtkegel oben, keine Muster, keine Bewegung. Karten mit 1 dp Kontur, Auswahl über Rahmen + Fläche + Schriftgewicht (nicht nur Farbe). Der aktive Fortschrittspunkt ist eine Pille, die anderen kleine Kreise. Der Primärbutton trägt einen horizontalen Verlauf.

Beim Rendern aufgefallen und behoben: Mit 58 dp Kartenhöhe passte die Fußnote „Deine Wahl gilt sofort.“ nicht mehr ganz in den Pager und wurde angeschnitten — jetzt 52 dp/8 dp, alle elf Karten plus Kopf- und Fußzeile passen auf 891 dp Höhe.

## 7. Geänderte Dateien (Auszug)

**Neu:** `util/LanguageCatalog.kt`, `domain/seed/SeedSuppressionStore.kt`, `ui/screens/onboarding/{OnboardingGate,OnboardingViewModel,OnboardingFlowScreen,OnboardingPages}.kt`, `res/values-{es,fr,pt,it,tr,pl,da,nb,sv}/` (je 20 Dateien), `docs/tools/{translate_resources,validate_language,audit_hardcoded_strings}.py`, vier Testklassen.

**Geändert:** `MainActivity.kt`, `navigation/AppNavHost.kt`, `data/repository/LanguageRepository.kt`, `util/LocaleHelper.kt`, `domain/seed/EnsureDefaultDataUseCase.kt`, `ui/screens/settings/SettingsScreen.kt`, `AutomationScreens.kt`, `PlaceTimelineMap.kt`, `ActivityStatsScreen.kt`, `res/values/strings*.xml` + `values-de/strings*.xml`, `app/build.gradle.kts`.

**Gelöscht:** `ui/screens/onboarding/OnboardingScreen.kt` (Platzhalter aus M30).

## 8. Build

```
robust_build.sh … aevum-m34-final -- :app:assembleDebug :app:testDebugUnitTest
BUILD SUCCESSFUL
signing-verify OK: app-debug.apk = kanonischer Key (SHA-1 9c3055c4…)
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (121 MB, Debug inkl. Tests/Bibliotheken).
