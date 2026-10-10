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
