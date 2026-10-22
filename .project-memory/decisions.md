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

**Entscheidung:** Live-Kanal PC↔Handy bleibt strikt pull-basiert:
Die App meldet per `POST /api/live` alle 7 s (15 s bei Screen aus) ihren
Session-Zustand, die Antwort trägt den ausstehenden Befehl; am PC wartet der
Befehl in der `PhoneLiveRegistry` bis zur Bestätigung und wird bei jeder
Meldung erneut geliefert.
**Grund:** „Es sollte es aber. Auch mit buttons zum stoppen und pausieren."
— bei wenigen Sekunden Latenz fühlt sich die Steuerung direkt an, ohne die
Architektur-Entscheidung („nur das Handy initiiert", kein eingehender Port am
Handy) aufzugeben. Wiederhol-Lieferung macht Funklöcher verlustfrei.

**Entscheidung:** „Fortsetzen" nach Pause startet eine NEUE Handy-Session
(Split), die Registry akzeptiert den ID-Wechsel genau in diesem Fall.
**Grund:** Konsistenz mit der bestehenden M18.62-Split-Logik des Handys
(zwei getrennte Aufzeichnungsblöcke) — der PC spiegelt nur, er erfindet
keine Semantik.

**Entscheidung:** App-Updates ohne Token/Konto über die öffentlichen
GitHub-Releases; Debug-Builds laden die Debug-APK, reguläre die Store-APK;
Installation ausschließlich über den System-Dialog (FileProvider nur für
`files/updates`).
**Grund:** „Ohne irgendwelche tokens, weil das alles veröffentlicht wird."
+ „Sauber durchdacht für beste usability." — der Standardweg ist der
sicherste; kein Eigenbau-Installer, keine gespeicherten Geheimnisse.

**Entscheidung:** Sync-Kick beim App-Öffnen wird sofort im Prozess
ausgeführt (Gate: 2 min Mindestabstand, 60 s Frisch-Fenster); der
WorkManager-Kick bleibt nur als Zusatz.
**Grund:** „spätestens wenn ich aevum auf dem handy öffne sollte der im
hintergrund synchronisieren mit dem pc" — WorkManager darf das System
verzögern (das war das „nicht gekoppelt"-Erlebnis), das 10-min-Gate machte
einen einzelnen Fehlversuch stundenlang wirksam. In-Process-Sync +
gelockertes Gate garantieren das Versprechen; der Sturm-Schutz bleibt.

**Entscheidung:** Update-Knopf existiert NUR im PC-Tracker; der
In-App-Updater der App 1.0.34 wurde in 1.0.35 restlos zurückgebaut.
**Grund:** „der update button sollte nur in der pc anwendung sein. Natürlich
sollte die handy anwendung nach wie vor über play store heruntergeladen
werden." — Ein zweiter Update-Kanal in der Handy-App wäre ein Sonderweg
neben dem Play Store; der gehört der Desktop-App (ZIP außerhalb des Stores).

**Entscheidung:** PC-Update beendet den Prozess hart
(`Environment.Exit` nach sauberem Dienst-Stop) und das Austausch-Skript
wartet per PID-Poll statt per festem Timeout; Kopieren mit `/MIR`.
**Grund:** Echter Nutzerbefund: `desktop.Shutdown()` beendete die Tray-App
nicht (OnClosing-Cancel), robocopy lief gegen gelockte Dateien, nichts
wurde ersetzt, die gestartete alte EXE zeigte den Einzelinstanz-Dialog
(„app is already active in the system tray"). Warten auf das echte
Prozessende + /MIR = „alte Version verschwindet" ist die einzige
deterministische Lösung; ein Timeout ist immer eine Wette.

**Entscheidung:** „Es läuft immer nur eine Aufzeichnung — Handy hat
Vorrang": Der PC leitet den Vorrang aus dem BESTEHENDEN Live-Kanal ab
(frische Meldung, ≤ 45 s alt); keine neue Schnittstelle, kein Push. Nach
45 s Funkstille ist der PC wieder frei (ehrlich bei App-Absturz statt
Dauer-Blockade).
**Grund:** „handy hat vorrang […] immer nur eine sache gleichzeitig." Der
Live-Kanal läuft ohnehin alle 7 s während jeder Handy-Session — er ist die
natürliche Wahrheitsquelle; ein zweiter Mechanismus wäre doppelte Wahrheit.

**Entscheidung:** Ausnahmen gibt es beim Vorrang KEINE — auch eine manuell
am PC gestartete Session endet, wenn das Handy startet (Ende = jetzt), und
manuelle Starts während der Handy-Aufzeichnung werden abgelehnt (Engine
liefert false + UI zeigt Hinweis).
**Grund:** Der Vorrang ist eine Ansage des Nutzers („sobald ich am handy
was starte hat das vorrang"), kein technischer Sonderfall; halbe Ausnahmen
erzeugen überlappende Aufzeichnungen und Datenmüll.

**Entscheidung:** Verlauf-Löschung nur für abgeschlossene Sessions; die
Sync-Auslieferung ans Handy basiert auf denselben abgeschlossenen Zeilen —
Gelöschtes wird also nicht mehr ausgeliefert, bereits Importiertes bleibt am
Handy (kein Rückkanal, bewusst).
**Grund:** Laufende Sessions überleben ein Delete nicht (Heartbeat schreibt
sie zurück) — die Bedingung `end_at IS NOT NULL` macht das auf Store-Ebene
ehrlich; die UI bietet den Knopf nur dort an, wo er wirkt.

**Entscheidung:** Der Update-Austausch ist PowerShell (nicht Batch) und
protokolliert + verifiziert sich selbst: Warten per Get-Process-Schleife,
robocopy /MIR mit Copy-Item-Fallback, Größen-Abgleich der neuen EXE, alles
nach `%APPDATA%\AevumPcTracker\update.log`; `pending-update.txt` + rotes
Fehlerband in den Einstellungen, wenn der Austausch nicht durchkam.
**Grund:** Der Batch-Mechanismus (seit 1.2) verschluckte alle Fehler
(`>nul`) und kopierte beim Nutzer deterministisch nichts — ohne jede Spur.
Lehre: Ein stiller Auto-Updater ohne Protokoll ist nicht diagnostizierbar;
ein Update, das scheitern kann, MUSS seinen Fehlschlag sichtbar melden.
Batch-Fallstricke (timeout/Pipe/Quoting) entfallen mit PowerShell ohnehin.

**Entscheidung (M19.4):** Das Dashboard bekommt ein „Tag-Cockpit": 180°-Güte-Gauge
mit FESTEM Rot→Gelb→Grün-Farbraum (sweepGradient an absoluten Winkeln) statt des
bisherigen Vollrings (Farbe=Wert).
**Grund:** Die Nutzer-Skizze zeigt eine Skala mit Marker/Nadel (Messgerät-Bild);
fester Farbraum bleibt auch bei 0/100 lesbar und ist dieselbe Positivitäts-Achse,
die die App schon überall verwendet.
**Naheliegende Alternative:** Den QualityRing behalten (weniger Arbeit) — verworfen:
Der Auftrag war ausdrücklich der modernisierte obere Bereich nach Skizze; Vollring
UND Gauge wären Dopplung derselben Information.

**Entscheidung (M19.4):** Alle Dashboard-Karten liegen in EINER, oben abgerundeten
Sheet-Fläche im LazyColumn, die beim Scrollen über den gepinnten Hero schiebt
(Hero-Inhalt −0,5×scroll mit Alpha/Scale-Fade, Deko −0,55×, Basis = GEMESSENE
Hero-Höhe via onSizeChanged; Deko übersteht den Hero um DashboardHeroPeek 44dp,
sichtbar durch die Sheet-Ecken).
**Grund:** Referenzbild + Wunsch („das untere soll sich über den oberen Bereich
schieben"); EINE Fläche im Scroll-Content bleibt bei Tag-Wechsel-Animationen und
Taps trivial korrekt (der M18.100-Tap-Bug darf nicht zurückkommen).
**Naheliegende Alternative:** Zweites gepinntes Sheet-Backdrop-Layer mit eigener
Offset-Mathematik — verworfen: Koordinaten-Synchronisierung mit LazyColumn-Viewport
+ Statusleisten-Inset wäre fehleranfällig und hätte das Tap-Problem potenziell
wieder eingeführt.

**Entscheidung (M19.4):** Der Aufnahme-Status ist ein eigener Balken im Hero
(live grün mit Timer + Schimmer / pausiert amber / „Aufnahme bereit" neutral mit
Tagesfortschritt); der steuerbare Live-Banner (Pause/Stop/Wechsel) bleibt
zusätzlich im Sheet.
**Grund:** Skizze will die Statusbox sichtbar im oberen Bereich; Steuerung gehört
in den Sheet-Inhalt. „Keine Dopplung" (M18.7) gilt für Information — der Hero
zeigt Status, der Banner bietet Aktionen.

**Entscheidung (M19.4):** Screenshot-Evidenz für den Hero entsteht über Robolectric
mit Software-Canvas VIEW-DRAW (captureToImage/PixelCopy gibt es unter Robolectric
nicht) und FIXIERTER Test-Uhr (autoAdvance=false, 2 s vorgespult).
**Grund:** Diese Maschine hat kein /dev/kvm (kein Emulator) — Robolectric-View-Draw
ist der einzige echte Pixel-Beweis; der feste Zeitpunkt macht Wert-Assertions und
Sichtprüfung reproduzierbar (Endlos-Animationen sonst zeitabhängig).

**Entscheidung (M19.5):** Der Hero wird beim Scrollen per graphicsLayer EXAKT
gegen den Scroll-Offset kompensiert (translationY = +offset); der M19.4-Collapse
(Fade/Scale/Alpha/Parallax) wird komplett entfernt.
**Grund:** Devon: „Ich will, wenn ich runter scrolle, dass der komplette obere
bereich stehen bleibt und der untere bereich darüber rutscht." — das ist exaktes
Pinning; der Collapse hat den Hero stattdessen beschleunigt nach oben geschoben.
**Naheliegende Alternative:** `stickyHeader` aus Foundation — verworfen: pinnt
nur innerhalb des Headers und liefert nicht die Karten-ÜBER-Inhalt-Optik; die
graphicsLayer-Kompensation ist exakt (Integrationstest: Position ±4px konstant)
und läuft in der draw-Phase (kein Rekompositionsaufwand pro Frame).

**Entscheidung (M19.5):** Der Live-Banner (Blubber) wandert vom Sheet in den
Hero DIREKT unter das Güte-Gauge; die Headline-Zeile entfällt ersatzlos; der
alte Hero-Status-Balken (HeroStatusBar/LiveStatusContent) wird entfernt.
**Grund:** Devon: „unter der anzeige der güte … der kasten … mit der animation
die aktuelle aufzeichnung" und „das kann komplett weg" (Headline) — genau EINE
Steuer-Fläche, keine Dopplung.
**Alternative:** Banner zusätzlich im Sheet lassen — verworfen (Dopplung,
widerspricht dem wörtlichen Auftrag).

**Entscheidung (M19.5):** Maskottchen-Schlaf (22–6 Uhr) gilt nur für „Heute"
(displayedDate == heute) und wird aus state.currentMinute abgeleitet
(MascotBehavior.shouldSleep — pure, JVM-getestet); eine Aufzeichnung übersteuert
den Schlaf live.
**Grund:** Beim Blättern in alte Tage gibt es kein „Jetzt" (currentMinute =
1440 → 23 Uhr → das Wesen schliefe am Nachmittag grundlos); deterministische
Tests: die Test-Uhr fixiert currentMinute, kein LocalTime.now.

**Entscheidung (M19.5):** Emoji-Zentrierung über contentPadding 0 +
fillMaxSize-Zentrierbox + includeFontPadding = false (statt numerischer
Pixel-Offsets je Glyphe).
**Grund:** Robolectric-Screenshot belegt „perfekt zentriert" für alle drei
Glyphen; Nudge-Werte je Glyphe wären font-abhängig und brächen still.

**Entscheidung (M19.5):** Deko-Verlauf-Tail von 0.92 auf 0.965 gezogen.
**Grund:** Der Hero wuchs (Banner im Hero); das sichtbare Überstands-Band
(DashboardHeroPeek) lag danach im Ausblend-Bereich des Gradienten („kaum Farbe
durchschimmernd" in der Sichtprüfung). Mit 0.965 liegt das Band in der vollen
Farbe; die letzten 3,5 % blenden weich aus (Sichtprüfung: „Ja, dezent").

**Entscheidung (M19.6):** Das Maskottchen wird 1:1 an das PAG-Büro-Maskottchen
angelehnt (Anatomie und Kernfarben aus dessen geprüftem Compose-Code übernommen:
runder Körper 0.31 R, Augen-Radius 0.30 R, Pupille 46 %, Augen-Abstand 0.42 R,
Mund ±0.20 R, Antenne mit Gold-Kugel #FFD166, Töne #F4F8FF/#232B3A/#9FB0C6/
#FF8FA3; flach, OHNE Umriss); die M19.5-Ohren entfallen ersatzlos.
**Grund:** Devon: „Die sehen mir nicht gut genug aus. Halte dich vielleicht an
das maskottchen aus PAG. Das fand ich gut." — das Vorbild existiert als
geprüfter Code auf dieser Maschine; Übernehmen ist risikoärmer als neu erfinden.
**Alternative:** Eigenständigen Stil behalten und nur Details ändern — verworfen:
genau die Anatomie (runde Kugel, Antenne, große weiße Augen, kein Umriss) war
das, was Devon am PAG-Wesen mochte.

**Entscheidung (M19.6):** Personalisierung leicht gehalten: Farbe (8 Töne,
Vorgabe PAG-Blau #5E7CE8) + Antennen-Spitze (Kugel/Stern/Blitz) + Bäckchen —
die Ohren-Optionen werden durch Antennen-Spitzen ersetzt; alte Werte
(round/pointy/long) validieren auf Default (Kugel).
**Grund:** „Das einfach leicht personalisierbar." Drei klare Achsen reichen;
die Antennen-Spitze ist das natürliche Pendant zu den Ohren und knüpft an
Devons Sternchen-Wunsch an.

**Entscheidung (M19.6):** Alle Tipp-Reaktionen herzfrei — Sternchen-Hüpfer
(★ gold), Wackel+Funkeln (✦ gold), Bäckchen-Pop mit Freuden-LINIEN-Augen
(statt der M19.5-∩-Bögen).
**Grund:** „ohne herzen bitte, vielleicht mit sternchen oder so." Die
Bögen-Augen wurden durch PAG-konforme Linien-Augen ersetzt, damit die
Design-Sprache des Vorbilds überall konsistent bleibt.

**Entscheidung (M19.6):** Screenshot-Freeze auf 3,65 s (statt 1,2–2,0 s):
dann steht die Blick-Wanderung exakt in Mittelstellung (Antenne gerade),
Kaskaden/Zähler sind fertig.
**Grund:** Die Sichtprüfung stufte die eingefrorene Antenne (mitten im
Schwung) als „verrutscht" ein; bei 3,65 s sind Antenne/Pupillen symmetrisch.
Reine Test-/Doku-Maßnahme, kein App-Verhalten.

**Entscheidung (PC 1.6.0):** Verifikations-Gate **fail-closed** statt
längerer Karenz: Der PC startet gar nichts Neues (Regel, allgemein,
manuell), solange keine FRISCHE Bestätigung „am Handy läuft nichts" (≤ 30
min) vorliegt; ein veraltetes „zeichnet auf" zählt NIE als frei.
**Grund:** Devon wörtlich: „erst erlauben … wenn verifiziert … ansonsten
Meldung. Damit das problem nicht weiterhin besteht." 1.5.2 (5-min-Karenz)
dämpfte nur — jede Sendelücke > Karenz reproduzierte den Bug.
**Alternative:** Karenz auf 30–60 min erhöhen — verworfen (Pflaster;
Verifikation würde entwertet).

**Entscheidung (PC 1.6.0):** LAUFENDE PC-Sessions überleben einen
Bestätigungs-Abriss; nur NEUE Starts brauchen die Bestätigung.
**Grund:** Sonst schneidet jede Sync-Verzögerung (Doze, WLAN) eine gesunde
Aufzeichnung ab. Echte Doppel-Aufzeichnungen verhindert weiterhin der
frische Handy-Vorrang, sobald eine Meldung ankommt.

**Entscheidung (PC 1.6.0):** Bestätigungsfenster = 30 min
(`PhoneLiveRegistry.IdleVerificationWindowMs`) — zwei verpasste 15-min-
Sync-Takte inkl. Doze-Spielraum; EINE nachjustierbare Konstante.

**Entscheidung (PC 1.6.0):** Gate erst nach erstem Handy-Kontakt aktiv
(`AppSettings.PhoneKnownAtMs`, gesetzt von der ersten token-authentifizierten
Anfrage); PCs ohne Kopplung zeichnen wie immer.
**Grund:** Kein Regress für reine PC-Installationen; danach dauerhafte
Verifikationspflicht.

**Entscheidung (App 1.0.39):** Verifikations-Ping über den BESTEHENDEN
`/api/live`-Endpunkt (`session:null`), gesendet nach jedem erfolgreichen
Sync (Auto + App-Öffnen-Kick); gemeinsamer Snapshot-Bauer `PcLiveState`.
**Grund:** Kein Protokoll-Sprung, kein neuer Endpunkt; das positive
Leerlauf-Signal ist die Voraussetzung des Gates.

**Xvfb-Smoke-Funde (PC 1.6.0):** (1) „Jetzt starten"-Zeile war nicht am
Gate gebunden → klickbar ohne Wirkung; jetzt `IsStartEnabled` + sichtbar
deaktiviert. (2) Chips/Ghost-Buttons hatten KEINE Disabled-Styles → jetzt
Opacity-Stile. (3) Zwei App-Instanzen (zweite bindet Nachbarport; ihr
Fenster lag oben) führten zu einer scheinbar „eingefrorenen" UI — Test-
Fallstrick, kein App-Bug: Einstellungs-Blob wird beim Persist komplett
geschrieben (alte In-Memory-Sprache einer Altinstanz überschrieb die
Datei).

---

## M23 (19.10.2026) — Fahrt-Anker, Widget, Timeline-Herkunft

**Fahrt-Startanker:** `toVehicleCluster` bekommt einen optionalen Fahrzeug-
Anker (≥ 3,0 m/s, zwei Probes in 90 s). **Alternative:** AR-ENTER-Zeitstempel
als einzigen Start nehmen — verworfen, weil dann die GPS-Rückdatierung für
„Fahrt begann vor der Erkennung" verloren ginge. **Preis:** Bei Stop&Go ohne
Speed-Feld startet die Fahrt am jüngsten statt am ältesten Probe (Sekunden
später, nie Minuten Gehzeit vorher).

**App-Tracking-Zuverlässigkeit:** Der Fremd-Session-Zweig merkt sich die
getrackte App NICHT mehr als „aktuell". **Alternative:** `onRepeatedForeground`
bei „nichts live" auf START_APP erweitern — verworfen, weil das einen bewusst
manuell gestoppten Zustand sofort wieder starten würde. **Preis:** Bei jeder
Fremd-Session wird der Vordergrund-Zustand erneut bewertet (billig, 2-s-Poll).

**Widget:** RemoteViews + eigener Updater, Timer-Takt vom LiveActivityService.
**Alternative:** Glance — verworfen, weil die App kein Glance/Compose-Remote-
Views-Setup hat und ein zusätzliches Compose-Runtime-Modul für 2x1 Overhead
wäre. **Preis:** Layout ist XML/RemoteViews (keine Composables, keine
Animation), Maskottchen wird als statische Bitmap gezeichnet und gecacht.

**Timeline-Herkunft:** neue nullable Spalte `source_app_package` (DB v48)
statt Parsing der Notiz. **Alternative:** Paketname in `note` — verworfen,
weil Notizen nutzersichtbar/bearbeitbar sind und alte Sessions sonst nicht
eindeutig zuordenbar wären. **Preis:** eine Migration und ein zusätzliches
Feld im Serialisierungs-/Exportpfad.

**Ziel-Überschreitung:** Farbe + ⚠ statt Text. **Alternative:** „überschritten"
als Label — verworfen, weil der Nutzer ausdrücklich keine Wörter wollte und
Farbe/Icons sprachunabhängig sind.

---

## M24 (20.10.2026) — Digital-Balance-Tagesdetail & Geofence aus Orts-Timeline

**Digital Balance:** `detailForDay(date)` im Aggregator statt Tageslogik im
ViewModel. **Alternative:** `todayDetail()` nehmen und Stunden nachträglich
verschieben — verworfen, weil Unlocks/Phasen dann doppelt berechnet würden.
**Preis:** eine zusätzliche Aggregator-Methode, aber EINE gemeinsame
`detailForRange`-Basis.

**Feldname:** `todayTotalMs` → `totalMs`. **Alternative:** Namen behalten und
nur anders befüllen — verworfen, weil der Name dann lügt und ein Rückfall
nicht auffällt. **Preis:** vier UI-Stellen angefasst.

**Geofence-Flow:** Detail-Sheet in der Orts-Timeline + Route mit
lat/lon/name-Query-Args. **Alternative:** globaler „Pending Geofence"-State —
verworfen, weil unsichtbar und nicht testbar. **Preis:** lange Route und
URL-Kodierung, dafür deterministisch und ohne versteckten Zustand.

**Validierung:** `geofencePrefillFromArgs` lehnt ungültige Koordinaten und
0/0 ab. **Alternative:** Nav-Args ungeprüft übernehmen — verworfen, weil ein
leerer Editor sonst mit „0.000000, 0.000000" speichern könnte.

## M25 (20.10.2026) — Geofence benennt bestehende Unknown-Places

**Entscheidung:** Benennung beim Rendern der Orts-Timeline über
Koordinatenabgleich (`geofenceForPoint`, Haversine, Radius, nächster
Mittelpunkt) statt DB-Backfill der `unknown_place_session`.
**Alternative:** Beim Anlegen alle historischen Unknown-Places umbenennen —
verworfen, weil das Roh-Evidenz zerstört, eine Migration braucht und beim
Löschen des Geofences nicht rückgängig ist. **Preis:** Die Zuordnung kostet
pro Tag ein paar Distanzberechnungen (wenige Unknown-Places/Geofences) und
endet an der Radius-Grenze.

---

## M28 (22.10.2026) — Fahrt-Vorrang, AppOps-Status, Aufzeichnungs-Aktivität

**Fahrt-Vorrang:** Fahrt wird vorgemerkt und am Ende des blockierenden Blocks
gestartet, aufgelöst von EINEM Beobachter auf `liveSession`.
**Alternative:** Hook in jedem Stop-Pfad (Dashboard, Notification, Widget,
Geofence, Kalender, Screen-Off …) — verworfen, weil die Liste unvollständig
bleibt (M18.75/M18.76-Lehre: ein verpasster Stop-Pfad blockiert dauerhaft).
**Preis:** ein dauerhaft lebender StateFlow-Collector im App-Prozess; der
Vormerk selbst ist nur In-Memory (Prozess-Tod verwirft ihn — dann greift die
normale Erkennung neu).

**Evidenz-Fenster 6 min:** Der 2-Minuten-Takt des DriveProbeWorkers hält die
Evidenz während der Fahrt frisch; steht das Auto, verfällt sie nach 6 min.
**Alternative:** nur die aktuelle GPS-Klassifikation — verworfen, weil der
Block Sekunden nach dem letzten Probe enden kann und die Fahrt dann
fälschlich verfiele. **Alternative:** Evidenz gar nicht prüfen — verworfen,
weil genau der User-Fall „Fahrt vorbei, danach erst gestoppt" sonst wieder
eine Fahrt startet. **Preis:** 6 min Nachlauf; ein sehr langsames Ausparken
nach längerem Halt wird als neue Fahrt gesehen (bestehendes Verhalten).

**Start-Anker = Block-Ende, nicht Fahrt-Anker.** **Alternative:** den
ursprünglichen Cluster-Start verwenden — verworfen, weil er rückwirkend in
den laufenden Block schneidet (genau der gemeldete Fehler).

**Nutzungszugriff:** AppOps (`unsafeCheckOpNoThrow`) statt
`queryUsageStats(1-Sekunden-Fenster)`. **Alternative:** längeres Query-Fenster
(z. B. 60 s) — verworfen, weil es weiterhin von Events abhängt und beim
ersten Öffnen am Morgen leer sein kann. **Preis:** keine (der AppOps-Check
existierte bereits und war getestet; nur der Aufrufer war falsch).

**Bildschirm-Aufzeichnung:** Aktivität als neue Spalte mit Default `'digital'`;
`sourceType` bleibt `SCREEN_AUTO`. **Alternative:** eine zweite Aufzeichnungs-
Quelle pro Typ — verworfen, weil Screen-Off-Stop, App-Takeover und
Statistik-Policy an `SCREEN_AUTO` hängen und dupliziert werden müssten.
**Alternative:** Statistik-Zählung für zugewiesene Typen freischalten —
verworfen, weil M18.138 ausdrücklich „nur Digital-Balance-Zeit zählt"
festhält und die Aufzeichnung ungenau ist. **Preis:** Ein als „Lesen"
zugewiesener Block erscheint in der Timeline, zählt aber nicht in der
Statistik; der Hinweistext in der UI sagt das ausdrücklich.

**Sucht-Detektor-Zeitraum:** aktuelle Periode inklusive heute (currentWindow),
Druckindex weiterhin auf den letzten 7 vollen Tagen.
**Alternative:** weiter nur volle Perioden — verworfen (User-Report).
**Preis:** laufender Tag/Woche ist unvollständig, Prozentvergleich fällt
morgens niedriger aus; dokumentiert.
