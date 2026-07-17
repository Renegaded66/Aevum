# KNOWN_ISSUES

## Aktuelle bekannte Punkte

| ID | Thema | Schwere | Status | Lösung |
|---|---|---|---|---|
| KI-001 | Konkrete App-Idee fehlt | Hoch | Offen | Produktdefinition in M1 durchführen |
| KI-002 | Appname/Paketname unbekannt | Mittel | Offen | Nach Produktname festlegen |
| KI-003 | Backend-/Cloud-Anforderungen unbekannt | Mittel | Offen | Local-first als Default, Backend nur bei Bedarf |
| KI-004 | Datenschutzanforderungen unbekannt | Mittel | Offen | Datenklassifizierung in M1/M2 |

## Technische Risiken

- **Overengineering:** Featuremodule erst konkret erstellen, wenn M1 abgeschlossen ist.
- **Chart-Library-Lock-in:** Erst eigene Compose Canvas Charts prüfen.
- **Unnötige Berechtigungen:** Permissions nur mit klarem Nutzen.
- **Testlücken in UI:** Kritische Flows mit ViewModel- und Compose UI Tests absichern.
