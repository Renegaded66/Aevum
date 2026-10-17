# Decisions

Siehe `docs/DECISIONS.md`.

Wichtige aktuelle Entscheidungen:

- Appname: Aevum
- Paket: `com.d_drostes_apps.aevum`
- Offline-first ohne Backend/Login/Cloud
- Kotlin + Compose + Material 3
- Room als Source of Truth
- Rohsignale getrennt von Activity Sessions
- Health Connect primär für Schlaf
- UsageStats optional
- Geofencing statt permanentem GPS Tracking
- Dashboard-first Produktstrategie
- PC-Tracker: LAN-only (gleiches WLAN), Pull-Sync vom PC, Kurzcode-Kopplung, keine Cloud — bewusst so, weil die App kostenlos für jedermann ohne Serverkosten verfügbar sein soll
- PC-Import ohne Schema-Migration: external_id idempotent, Auto-Mapping über den Aktivitätsnamen, DataSource mit config_json
- PC-Tracker 1.1: Aktivitäts-Spiegel App→PC (`POST /api/activities`, App pusht alle Aktivitäten bei jedem Sync, PC ersetzt komplett) — Grundlage für „Aktivität am Handy anlegen → Sync → am PC zuordnen"
- PC-Tracker 1.1: Aufnahme-Modus pro Regel (`while_running` = läuft auch minimiert; Vordergrund-Start verdrängt laufende Hintergrund-Session, Neustart-Start auf Session-Ende gekappt; max. 1 aktive Session bleibt Prinzip)
- Avalonia: ComboBox-Auswahl nie nur per TwoWay-SelectedItem vertrauen → expliziter SelectionChanged-Handler im Code-behind (belegt: Auswahl kam sonst still nicht im VM an)
- Windows-UI: EN als Default, Sprachwechsel ausschließlich über gezeichnete Flaggen (Emoji-Flaggen rendern unter Windows nicht), Strings zentral in `Localization/Loc.cs` → DynamicResource
- PC-Tracker 1.2: Auto-Update über die ÖFFENTLICHE GitHub-Releases-API (kein Token/Konto — die App wird öffentlich vertrieben); stiller Check bei jedem Start + manueller Knopf; Installation nur auf Klick („Herunterladen & neu starten“ — Batch ersetzt Dateien, startet neu, löscht sich). Bewusst KEINE stille Selbstinstallation.
- PC-Tracker 1.2: Allgemeine Aufzeichnung ist eine normale Session (`program_key="__general__"`, „General use") → kein Protokoll-/Import-Sonderweg nötig. Regeln haben immer Vorrang; „aktiv“ = Maus/Tastatur < 60 s ODER Ton; Ende auf letzten aktiven Moment rückdatiert (Audio-Phasen zählen mit).
- PC-Tracker 1.2: Der regelmäßige Abgleich läuft in der HANDY-App (WorkManager, seit App 1.0.27 alle 15 min, still, NUR im WLAN/UNMETERED) plus ein gedrosselter Einmal-Kick bei App-Öffnen bzw. Prozess-Start im Hintergrund (PcSyncGate: ≥10 min Mindestabstand, Erfolg wie Fehlschlag zählt) — der PC ist Datenquelle; Push ans Handy bräuchte dort einen Dauerdienst (Akku) und wird bewusst nicht gebaut. Fehler des stillen Syncs bleiben unsichtbar; nur der manuelle Sync meldet.
- App-Aufzeichnung (App 1.0.27): Der PackageManager-Scan läuft in einem eigenen Provider (`InstalledAppsProvider`) auf Dispatchers.IO und liefert die App-Liste chunkweise — der Screen öffnet sofort, die Listen wachsen beim Benutzen nach. Interaktionen (verschieben/Activity zuordnen) sind optimistisch: Die lokale Absicht gilt, bis die DB sie bestätigt (`AppTrackingOptimism`), bei Fehlschlag stiller Rückfall. PackageManager-Aufrufe im combine-Transform sind damit dauerhaft ausgeschlossen (Ursache des 3-s-Hängers).
- App-Icon (App 1.0.27): Adaptives Icon — das Artwork liegt VOLLFLÄCHIG im Hintergrund-Layer (mipmap-anydpi-v26), der Vordergrund ist bewusst transparent; Legacy-PNGs + Play-512 aus dem reproduzierbaren Generator `docs/tools/gen_app_icon.py`. Ein zusätzlich gefüllter Vordergrund-Layer würde bei Launcher-Parallaxe doppelt erscheinen.
- PC-Tracker 1.2: Aktivität für die allgemeine Aufzeichnung kommt ausschließlich aus den gespiegelten Aevum-Aktivitäten („tatsächlich in Aevum eingerichtete") — ohne Handy-Sync bleibt die Karte mit Hinweis deaktiviert.
- App-Aufzeichnung × Digital (App 1.0.30): Übergabe-Regeln der Smartphone-Aufzeichnungen liegen zentral in `AppTrackingTakeoverPolicy` (pure, JVM-getestet): nichts läuft → starten; eigene Session → Wechsel A→B direkt; Digital (SCREEN_AUTO) läuft → SOFORT übernehmen; alles Fremde → nichts anfassen. Fremde Sessions werden NIE gestoppt — jeder automatische Stop nutzt `LiveActivityManager.stopIfLive(sessionId)` als Race-Schutz (zwischenzeitlich gestartete höherpriore Session bleibt unangetastet). Kalender-Fallback behält Vorrang (nicht Teil der User-Übernahmeliste).
- App-Aufzeichnung × Digital (App 1.0.30): "Vordergrund" hat genau EINE Definition — `ForegroundTracker` (pure, 8 Tests), genutzt vom Service-Poll UND von `ForegroundAppProbe` (One-Shot-Query des ScreenRecordingWorkers). Der Digital-Worker startet nie, wenn eine getrackte App im Vordergrund ist (Probe der letzten 2 min + prozess-lokaler Poll-Schnappschuss `AppTrackingService.recentPollSnapshot(90s)` für "App seit Stunden ruhig"), und stupst stattdessen den AppTrackingService an.
- App-Aufzeichnung × Digital (App 1.0.30): Nach dem Ende einer App-Aufzeichnung setzt der Service den Digital-Start mit der konfigurierten Vorlaufzeit neu an (EINE Funktion `ScreenRecordingWorker.schedule()`, EIN UniqueWork-Name — auch der ScreenEventReceiver nutzt sie). Screen-OFF beendet die App-Session SOFORT (konsistent zu M18.138); vorher lief sie eingefroren bis zum nächsten Entsperren (Nacht-Falschblock). Jede App-Session startet die Live-Notification.
- App-Aufzeichnung × Digital (App 1.0.30, Nachtrag): Verwaiste App-Tracking-Sessions (FGS-System-Kill, `trackedSessionId` leer, Session lebt weiter) zaehlen ueber `sourceType == APP_TRACKING` weiter zur Smartphone-Familie — Take-over-Pruefung (`isOwnSession`) und `stopOwnSessionIfRunning()` behandeln sie wie eine eigene Session, sonst blockierte sie fuer immer jeden Wechsel ohne je gestoppt zu werden. Fremde Quellen bleiben unberuehrt; nur dieses Feature erzeugt `APP_TRACKING`.

---

## M19.1 (17.10.2026)

**Entscheidung:** Ein selbstreferenzieller Baum (`pm_node`) statt fünf
Tabellen für Projekte/Kategorien/Aufgaben/Schritte.
**Grund:** Der Nutzer beschreibt eine offene Hierarchie („verschachtelt").
Fünf Tabellen hätten jede weitere Ebene zu einer Migration gemacht; die
Rekursion läuft stattdessen in Kotlin über einen vollständig geladenen,
indexierten Baum (unter 1 ms bei diesen Datenmengen).
**Verworfen:** Materialized Path, Closure Table (Nutzen erst bei
Zehntausenden Knoten), feste Ebenen-Tabellen.
**Konsequenz:** Ein DAO, ein Repository, ein Editor; Umzüge sind ein UPDATE.
Der Umzug in den eigenen Teilbaum wird vom Repository abgelehnt (Rückgabe
false, es wird nichts geschrieben).

**Entscheidung:** `done_at` als einzelner Zeitstempel statt eigener
Erledigungs-Tabelle.
**Grund:** Ein PM-Schritt wird genau einmal erledigt (keine Serien). Aus dem
Zeitstempel entsteht der 7-Tage-Verlauf ohne zusätzliche Tabelle.

**Entscheidung:** Getrenntes PM-Modul, KEINE Wiederverwendung der bestehenden
`todo`/`todo_completion`-Tabellen.
**Grund:** Das bestehende Modul ist ein Serien-/Gewohnheits-System (Recurrence,
Streaks, Auto-Check per Dauer). PM-Schritte sind einmalige, tagzuweisbare
Kleinschritte. Eine Vermischung hätte beide Modelle verzerrt und 1114
Bestandstests gefährdet.

**Entscheidung:** Feuerwerk nur beim Übergang auf „voll", persistiert pro Tag
(`PmCelebrationStore`).
**Grund:** Eine an „100 %" hängende Animation feuert sonst bei jeder
Neukomposition (Scrollen, Rotation, Rückkehr, DB-Emission). Der Nutzer fordert
ausdrücklich KEINE Spielerei-Mechanik — die Belohnung darf deshalb nicht
verpuffen.

**Entscheidung:** Zähler der offenen Schritte am Dashboard-Knopf, aber in einem
EIGENEN `PmEntryViewModel`.
**Grund:** Minimaler Eingriff in das 77-KB-DashboardViewModel, kein Risiko für
die bestehenden Tests; der Zähler macht den Tag ohne einen Tipp ablesbar.

## M19.2 (17.10.2026)

**Entscheidung:** PM-Hierarchie auf ZWEI Knotenarten reduziert (`PROJECT` =
Container, `TASK` = abhakbares Blatt); `CATEGORY` und die alte „Aufgabe mit
Schritten" entfallen.
**Grund:** Nutzerentscheidung wörtlich („Ich möchte keine Unterscheidung
zwischen Projekt, Kategorie und Aufgabe"). Weniger Typen = weniger
Sonderfälle in jeder Query, jedem Test und jeder späteren Oberfläche.
**Konsequenz:** Migration 44→45 ist eine REINE Daten-Migration (drei
UPDATEs: CATEGORY→PROJECT, TASK-mit-Kindern→PROJECT, TODO→TASK);
Schema bleibt identisch, Room-Validierung kann nicht brechen. Zwei Tests
sichern zu: `PmMigrationSchemaTest` (Element-Gleichheit 44≡45) und
`PmMigrationDataTest` (echte Migration auf SQLite aus `44.json`).

**Entscheidung:** Baum-Regeln (Umzug an die Wurzel nur für Projekte, unter
Aufgaben nichts, Geschwister-Nummerierung, exakt-Liste beim Neusortieren)
leben im Repository, nicht in der Oberfläche.
**Grund:** Die nächste Oberfläche (Widget, Import, Automation) erbt dieselben
Regeln; ein Mutex serialisiert alle Lese→Rechne→Schreib-Zyklen.

**Entscheidung:** Die gezogene Reihenfolge (`sort_order` per Drag & Drop) ist
die EINZIGE Ordnung; „übernommene zuerst" entfällt.
**Grund:** Eine Automatik hätte die gezogene Ordnung beim nächsten Öffnen
wieder überschrieben — der Nutzer sieht seine Anordnung und meint, die App
ignoriert ihn. Übernahme bleibt als Chip sichtbar.

**Entscheidung:** `done_at` bleibt nur an Aufgaben; `setDone` auf ein Projekt
ist ein No-Op.
**Grund:** Das Glas zählt Aufgaben, keine Container; verhindert, dass ein
„fertiges" Projekt Juwelen erzeugt, die niemand abgehakt hat.

**Entscheidung:** Digital-Balance-Revival über DREI äußere Netze (Boot/
PACKAGE_REPLACED, 15-Minuten-Watchdog mit KEEP, Screen-On-Nudge), alle mit
`isAlive()`-Guard; Kernlogik (`AppTrackingTakeoverPolicy`, `ForegroundTracker`,
`ScreenRecordingWorker`) unangetastet.
**Grund:** Der Rückfall war ein Lifecycle-Problem (nur zwei Startpfade); die
M18.151-Logik ist ausgetestet und darf nicht riskiert werden.

---

## M19.3 (17.10.2026)

**Entscheidung:** Das Projektmanagement wird KOMPLETT entfernt — Code, UI,
Routen, Einstiegsknopf, Vorlage, Tests UND die Tabelle (`MIGRATION_45_46` =
`DROP TABLE pm_node`). Kein Hintertürchen, kein Ausgrauen, kein Feature-Flag.
**Grund:** „Ich nehme es zurück. Ich will das ganze PM Management doch nicht.
Also alles davon." Ein halb entferntes Feature kostet Pflege (Migrationen,
Tests, Kompilierzeit) und verwirrt; die Git-Historie (M19.1/M19.2) hält die
Umsetzung vollständig fest, ein Zurück wäre ein Revert.

**Entscheidung:** Die 15-s-Poll-Drosselung von M18.93v11 wird für die
Digital-Übergabe aufgehoben → `POLL_INTERVAL_MS = 2_000L`.
**Grund:** Die Drosselung war eine Akku-Maßnahme, aber sie machte das
Kernversprechen kaputt: Bis zu 15 s lief „Digital" nach dem Öffnen einer
getrackten App weiter („oft zeichnet der einfach weiter digital auf obwohl
ich etwas bestimmtes geöffnet habe"). Der Nutzer verlangt ≤5 s; 2 s Worst
Case bei Takt NUR während Screen AN + aktiver getrackter App ist vertretbar.

**Entscheidung:** Zwei neue reine Regeln in `AppTrackingTakeoverPolicy` statt
Logik im Service: `onRepeatedForeground` (auch bei UNVERÄNDERTER
Vordergrund-App nachprüfen) und `onStopRace` (einmaliges Nachfassen während
der Stopp-Phase der eigenen Session).
**Grund:** Beide Fälle („manchmal klappt es dann direkt. Aber dann wieder
nicht.") entstanden durch Ereignis-Lücken — App war schon vorn, als die
Session startete (kein Wechsel-Trigger), bzw. die Übergabe kam während des
Stopps an. Als reine Funktionen sind sie unit-testbar festgenagelt
(Wiring-Tests 11/12) statt im Service versteckt.

**Entscheidung:** Feuerrot wird nicht neu erfunden, sondern nachgeschärft:
Sekundär dunkel `#FF3B2E`, hell `#D32614`; Container mitgezogen;
`DesignTokens[1]` angepasst.
**Grund:** „die rote theme Farbe soll noch etwas röter. So feuerrot." — der
Ton bleibt in der Familie der M19.2-Identität (Eisblau bleibt unangetastet),
nur die Sättigung steigt.
