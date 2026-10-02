# M18.140 — Standardsprache Englisch korrekt umgesetzt

**Datum:** 2. Oktober 2026
**Version:** 1.0.20 (versionCode 21) — Versionsnummer unverändert, Änderung
geht in dieselbe Einreichung wie M18.139

---

## Ausgangslage

Der Auftrag: „Die Standardsprache der App ist Englisch und auch der Store-Eintrag
ist primär englisch. Die Dialoge sollten also auf Deutsch und Englisch verfügbar
sein und die Store-Beschreibung auf Englisch."

---

## Befund 1 (der eigentliche Fehler): Ressourcen-Struktur war gedreht

`LanguageRepository.LANGUAGE_DEFAULT = "en"` — die App-Einstellung sagte schon
korrekt „Englisch". **Aber die Ressourcen-Ordner sagten das Gegenteil:**

| Ordner | Inhalt vorher | Rolle bei Android |
|---|---|---|
| `values/` | **Deutsch** | Fallback für **alle** Sprachen |
| `values-en/` | Englisch | nur für explizit englisches Locale |

Androids Ressourcen-Auflösung nutzt `values/` als **Fallback für jedes Locale,
das keine eigene Übersetzung hat**. Ein Nutzer mit Systemsprache Spanisch,
Französisch oder Türkisch und der Einstellung „System" bekam damit **Deutsch**,
obwohl Englisch die Standardsprache ist.

Das war kein reines Theorie-Problem: Es widersprach direkt der
Formular-Erklärung an Google („main purpose"), in der die App als
englischsprachige App beschrieben ist.

**Zusätzlich:** Die Google-Review liest die Offenlegung in der Store-Sprache.
Bei englischem Store-Eintrag und deutschem Fallback hätte der Prüfer einen
deutschen Dialog gesehen, wenn sein Testgerät nicht auf Deutsch stand.

---

## Befund 2: Ein hartkodierter deutscher String im UI

`RadialOrbitPicker.kt` enthielt `Text("Auswählen", ...)` — ein direkter
Literaltext im Composable. Im englischen UI wäre das deutsch erschienen. Kein
Policy-Verstoß, aber ein sichtbarer Sprachfehler im englischen Store-Screenshot.

---

## Umsetzung

### 1. Ressourcen-Ordner gedreht

```
values/      ← Englisch   (Fallback; Default-Sprache der App)
values-de/   ← Deutsch    (neu; die bisherige values/-Fassung)
values-en/   ← entfernt   (redundant, Inhalt ist jetzt values/)
```

Alle 15 String-Dateien wurden verschoben. `colors.xml` und `themes.xml` bleiben
in `values/` — sie sind sprachneutral.

**`translatable="false"`-Einträge** (die Datenschutz-URL) müssen in der
Fallback-Datei liegen, weil sie nicht pro Sprache dupliziert werden. Der
`disclosure_privacy_url`-Eintrag wurde entsprechend in `values/` erhalten.

### 2. Test, der die Richtung erzwingt

Der bestehende `CalendarStringsI18nTest` hätte die Drehung **nicht** bemerkt —
er prüfte nur Parität, nicht die Richtung. Neu:

```kotlin
@Test fun `Englisch liegt in values - nicht in values-en`()
```

Der Test schlägt fehl, wenn:
- `values-en/` wieder auftaucht (Doppelstruktur),
- `values-de/` fehlt (deutsche Übersetzung verloren),
- `values/strings_disclosure.xml` deutschen Text enthält,
- `values-de/strings_disclosure.xml` keinen deutschen Text enthält.

Zusätzlich umgeschrieben: `CalendarStringsI18nTest` prüft jetzt die neue
Richtung und bindet `LanguageRepository.LANGUAGE_DEFAULT` mit ein, damit
Ordner-Struktur und Einstellungs-Default nicht auseinanderlaufen können.

### 3. Hartkodierten String ersetzt

`common_select` („Select" / „Auswählen") in beiden Sprachdateien ergänzt;
`RadialOrbitPicker` nutzt `stringResource(R.string.common_select)`.

### 4. Store-Beschreibung auf Englisch

`docs/PLAY-STORE-BESCHREIBUNG.md` komplett auf Englisch umgeschrieben:

- Short description (74 Zeichen) und Full description (~2.850 Zeichen)
- Die drei Google-Pflichtelemente sind enthalten: Begriff „location",
  Hintergrund-Natur („even when the app is **closed** or not in use"),
  Liste aller vier Funktionen
- Formulartexte („main purpose", „why does your app need access to the
  location in the background?") auf Englisch, damit sie direkt in die
  englische Play Console kopiert werden können
- Hinweis: nur EINE Funktion im Formular nennen (Fahrterkennung)

Die vier Funktionsnamen sind über alle drei Offenlegungsorte identisch:
App-Dialog, Store-Beschreibung, Datenschutzerklärung.

---

## Verifikation

- **956 Tests, 0 Fehler** (1 zusätzlicher Test: Sprachrichtungs-Guard)
- `values/` enthält Englisch: `dashboard_title` = „Today"
- `values-de/` enthält Deutsch: `dashboard_title` = „Heute"
- `values-en/` existiert nicht mehr
- Keine Repo-Referenz auf `values-en/` außer in historischen
  Abschlussberichten (M18.133, M18.139 — bewusst unverändert)

---

## Geänderte/neue Dateien

| Datei | Änderung |
|---|---|
| `res/values/*.xml` (15 Dateien) | Inhalt: Englisch (vorher Deutsch) |
| `res/values-de/*.xml` (15 Dateien) | **neu/verschoben**: Deutsch |
| `res/values-en/` | **gelöscht** (redundant) |
| `res/values/strings_common.xml` | `common_select` = „Select" |
| `res/values-de/strings_common.xml` | `common_select` = „Auswählen" |
| `ui/components/RadialOrbitPicker.kt` | hartkodiertes „Auswählen" → `stringResource` |
| `ui/disclosure/LocationDisclosure.kt` | Kommentar: Pfade korrigiert |
| `ui/screens/calendar/CalendarRulesScreen.kt` | Kommentar: Pfade korrigiert |
| `test/.../LocationDisclosureTextTest.kt` | Fallback-Pfad + Sprachrichtungs-Guard |
| `test/.../CalendarStringsI18nTest.kt` | neue Richtung + `LANGUAGE_DEFAULT`-Bindung |
| `test/.../LocaleSwitchTest.kt`, `TopActivitiesToggleTest.kt` | Kommentare |
| `docs/PLAY-STORE-BESCHREIBUNG.md` | komplett Englisch |

---

## Wichtiger Hinweis zur Sprachwahl „System"

Die App-Einstellung „System" (`LANGUAGE_SYSTEM`) folgt der Gerätesprache. Bei
einem deutschen Gerät führt das weiterhin zu Deutsch — das ist gewollt und
unverändert. **Nur der Fallback für nicht übersetzte Locales hat sich
geändert** (vorher Deutsch, jetzt Englisch). Die Default-Einstellung für eine
frische Installation bleibt Englisch (`LANGUAGE_DEFAULT = "en"`), unabhängig von
der Gerätesprache.

---

## Was Devon weiterhin tun muss

Unverändert gegenüber M18.139 — die Sprachumstellung ändert die Play-Console-
Aufgaben nicht:

1. Video (≤ 30 s) mit Disclosure-Dialog + Runtime-Prompt
2. AAB hochladen (versionCode 21)
3. FGS-Anwendungsfall auf „vehicle activity tracking"
4. Store-Beschreibung: **den englischen Text aus
   `docs/PLAY-STORE-BESCHREIBUNG.md` verwenden**
5. Alle Tracks prüfen
