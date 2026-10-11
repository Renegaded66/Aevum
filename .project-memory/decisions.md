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
- PC-Tracker 1.2: Der regelmäßige Abgleich läuft in der HANDY-App (WorkManager, 30 min, still, nur im WLAN) — der PC ist Datenquelle; Push ans Handy bräuchte dort einen Dauerdienst (Akku) und wird bewusst nicht gebaut. Fehler des stillen Syncs bleiben unsichtbar; nur der manuelle Sync meldet.
- PC-Tracker 1.2: Aktivität für die allgemeine Aufzeichnung kommt ausschließlich aus den gespiegelten Aevum-Aktivitäten („tatsächlich in Aevum eingerichtete") — ohne Handy-Sync bleibt die Karte mit Hinweis deaktiviert.
