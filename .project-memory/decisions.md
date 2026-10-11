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
