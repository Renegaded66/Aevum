# M18.39 — Insights exakt (keine Rundung) + komplettes Bucket-List-Feature

**Branch:** `hermes/auto-tracking-and-stats-redesign`
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (111 MB) — **BUILD SUCCESSFUL**

---

## 1. Insights-Hero ohne Rundung

**Vorher:** Dezimal-Stunden mit 1 Nachkommastelle ("7,5 Std") PLUS Minuten daneben — redundant und wirkte gerundet.

**Jetzt:** Exakte Minuten → **"7 Std 32 Min"**, 100 % präzise, keine Rundung mehr.

## 2. Bucket List — komplettes Feature (eigene Seite)

**Internet-Recherche** (beste Bucket-List-Apps: Buckist, The Bucket List App, Goji-Case-Study): Die Kern-Features sind Titel, Ort, Icon, Kategorie, optionales Datum, Erledigt-Status, optionales Bild, Fortschritt und Filter.

### Umgesetzt:
- **DB v20:** Neue Tabelle `bucket_list_item` (MIGRATION_19_20). Wichtig: Die alte M2-Tabelle (status/progress_percent-Schema) wird gedroppt — das Feature hatte nie eine UI, es gab nie echte Nutzerdaten.
- **BucketListScreen:**
  - Hero mit **Fortschritts-Ring** (X von Y geschafft, %-Anzeige)
  - **Filter-Chips:** Alle / Offen / Erledigt
  - Karten mit **Icon (Emoji), Titel, Ort 📍, Kategorie-Chip, Zieldatum**
  - Erledigte Einträge: **durchgestrichen + grüner Haken + "Geschafft am … 🎉"**
  - FAB für neue Einträge, **Stift zum Bearbeiten**, Löschen
- **BucketListEditorScreen:** Titel (Pflicht), Ort, Icon, Kategorie, Zieldatum (JJJJ-MM-TT), Notizen, **Bild aus Galerie** (wird in den App-Speicher kopiert — kein Coil nötig, BitmapFactory reicht)
- **Erreichbar:** Settings → Erweitert → "Bucket List 🌍"
- ViewModel + Editor-ViewModel + Repository an das neue Schema angepasst

---

## Verifiziert
- `compileDebugKotlin` + `assembleDebug`: **BUILD SUCCESSFUL** (1m29s)
- Commit: `e12d954`

## Test-Anleitung
1. **Insights:** Hero zeigt "7 Std 32 Min" — exakt, keine Rundung
2. **Bucket List:** Settings → Erweitert → "Bucket List 🌍" → + → Eintrag anlegen (Titel, Ort, Icon, Kategorie, Datum, Bild) → erscheint in der Liste → abhaken → "Geschafft am …" + Fortschritts-Ring steigt
