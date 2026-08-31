# Datenschutzerklärung für Aevum

**Stand: 31. August 2026**

---

## 1. Verantwortlicher

Devon Droste
E-Mail: **drostedevon@gmail.com**

---

## 2. Kurzfassung (Das Wichtigste zuerst)

Aevum verarbeitet alle deine Daten **ausschließlich lokal auf deinem Gerät**.

- Es gibt **keinen Aevum-Server**, keine Benutzerkonten und keine Cloud-Speicherung.
- Es gibt **keine Werbung, keine Analyse- oder Tracking-Dienste** und keine Weitergabe deiner Daten an den Entwickler.
- Verbindungen zu Dritten entstehen nur zu: **Google Play Services** (Systemdienste deines Geräts), **OpenStreetMap** (Kartenkacheln für die Kartenansicht) und – nur wenn du es selbst aktiv verbindest – **Garmin**.

---

## 3. Welche Daten verarbeitet Aevum?

### 3.1 Standortdaten (Geofences, Orts-Timeline, Fahrterkennung)

**Zweck:** Automatisches Tracking deiner Aktivitäten an selbst angelegten Orten (z. B. Zuhause, Arbeit), die Orts-Timeline (wann warst du wo) und die Fahrterkennung.

**Verarbeitung:** vollständig **lokal auf deinem Gerät**. Geofence-Grenzen (Koordinaten + Radius), Besuchszeiträume und ggf. zur Routen-Anzeige aufgezeichnete Positionspunkte werden nur in der lokalen App-Datenbank gespeichert. Es findet **keine Übertragung an den Entwickler oder einen Aevum-Server statt**.

**Berechtigungen:** Genauer Standort (Vordergrund), grober Standort, optional Hintergrund-Standort (nur, wenn du Automatisierungen mit Orten nutzen willst).

### 3.2 Aktivitätserkennung (Fahrten, Fortbewegung)

Die App nutzt die Activity-Recognition-API von **Google Play Services**, um Fortbewegungsarten (z. B. Fahren, Gehen) zu erkennen und daraus automatisch Zeit-Einträge vorzuschlagen. Die Erkennungsergebnisse werden lokal gespeichert. Welche Daten Google Play Services dabei verarbeitet, regelt die Datenschutzerklärung von Google.

### 3.3 Schlaf- und Gesundheitsdaten (optional, nicht aktiviert = keine Verarbeitung)

- **Health Connect:** Wenn du die Schlaf-Quelle „Health Connect" aktivierst, liest Aevum Schlaf- und Trainingsdaten (Berechtigungen: Schlaf lesen, Übungen lesen) und speichert sie nur lokal.
- **Garmin Connect:** Wenn du Garmin in den Einstellungen verbindst, fragt Aevum Schritte, Schlaf und Trainingsdaten von Garmin ab. Deine Garmin-Anmeldedaten (E-Mail/Passwort) werden **ausschließlich direkt an Garmin übermittelt** (offizieller Garmin-Login), von Aevum **nicht gespeichert** und zu keinem Zeitpunkt an den Entwickler oder Dritte weitergegeben. Auf dem Gerät verbleiben nur die resultierenden Zugriffstokens in der privaten App-Speicherung. Du kannst die Verbindung jederzeit trennen; die Tokens werden dann sofort vom Gerät gelöscht.

### 3.4 Bildschirm- und App-Nutzung (Digital Balance, Schlaf-Heuristik)

Mit der Sonderberechtigung **„Nutzungszugriff"** erfasst die App lokal, welche Apps du wie lange nutzt (Android UsageStats). Das dient der Funktion „Digital Balance" (Nutzungszeiten, App-Sperren) und der automatischen Schlaf-Erkennung. Diese Daten bleiben auf dem Gerät.

### 3.5 Kartenanzeige (OpenStreetMap)

Die Karten in Aevum nutzen Kartenkacheln von **openstreetmap.org**. Beim Laden einer Karte werden deine IP-Adresse und der angeforderte Kartenausschnitt an die OpenStreetMap Foundation übermittelt. Informationen dazu: https://osmfoundation.org/wiki/Privacy_Policy

### 3.6 Was Aevum nicht tut

- Kein Zugriff auf Kontakte, Kamera, Mikrofon, SMS oder Anruflisten
- Keine Werbung, keine Werbe-IDs, keine Analyse-SDKs (z. B. Google Analytics, Firebase)
- Keine automatischen Crash- oder Fehlermeldungen an den Entwickler
- Kein personenbezogenes Benutzerprofil, kein Tracking über andere Apps hinweg (abgesehen von der lokal verarbeiteten Nutzungszeit für Digital Balance)

---

## 4. Weitergabe an Dritte

Aevum übermittelt **keine personenbezogenen Daten an den Entwickler**, Werbenetzwerke oder Datenhändler. Technisch bedingte Berührungen mit Drittanbietern:

| Anbieter | Anlass | Daten |
|---|---|---|
| Google LLC (Play Services) | Geofencing, Aktivitätserkennung, Karten-Umgebung | Von deinem Gerät an Google gelieferte Standort-/Aktivitätssignale |
| OpenStreetMap Foundation | Anzeige von Karten | IP-Adresse, angeforderter Kachelbereich |
| Garmin Ltd. | Nur bei aktivierter Verbindung | Garmin-Anmeldedaten (direkt an Garmin), Abfrage deiner Garmin-Fitnessdaten |

---

## 5. Speicherdauer und Löschung

Alle Daten existieren nur lokal auf deinem Gerät, solange du Aevum (oder deine Daten darin) behältst. Du hast jederzeit volle Kontrolle:

- **In der App:** Aktivitäten, Orte, Trigger und Einträge kannst du dort löschen, wo sie entstehen; unter „Einstellungen → Daten" stehen Export- und Backup-Funktionen bereit (du bestimmst, wohin die exportierten Dateien gelangen).
- **Komplett:** Android-Systemeinstellungen → Apps → Aevum → „Daten löschen" oder Deinstallation entfernen **sämtliche** lokal gespeicherten Daten, einschließlich der Garmin-Zugriffstokens.

Da keine Daten bei uns ankommen, gibt es bei uns nichts zu löschen oder einzuschränken.

---

## 6. Sicherheit

- Daten liegen im privaten, durch Android geschützten App-Speicher deines Benutzerprofils.
- Die automatische Sicherung auf Google-Dienste (Android-Backup) ist **deaktiviert**.
- Verbindungen zu Garmin und OpenStreetMap erfolgen verschlüsselt (HTTPS).

---

## 7. Deine Rechte (insbesondere DSGVO)

Da Aevum keine personenbezogenen Daten an uns übermittelt und wir keine eigene Datenverarbeitung auf Serverseite betreiben, sind wir nicht in der Lage, auf deine Daten zuzugreifen — Auskunft, Berichtigung, Löschung und Datenübertragbarkeit übst du direkt am Gerät aus (siehe Abschnitt 5). Für Fragen zu dieser Erklärung erreichst du uns unter der E-Mail-Adresse in Abschnitt 1. Ist das anwendbare Recht (z. B. DSGVO) trotzdem eine Beschwerde bei einer Aufsichtsbehörde vor, z. B. der zuständigen Landesdatenschutzbehörde.

---

## 8. Kinder

Aevum richtet sich an Erwachsene und jugendliche Nutzer zum allgemeinen Zeit-Tracker-Gebrauch. Es werden bewusst keine personenbezogenen Daten erhoben, die eine gesonderte Kinderverarbeitung erfordern würden; Eltern können die im Gerät vorhandenen Kinderschutz-Maßnahmen nutzen.

---

## 9. Änderungen dieser Erklärung

Bei funktionalen Änderungen der App (insbesondere neuer Datenverarbeitung) aktualisieren wir diese Erklärung und das Datum am Anfang. Maßgeblich ist die jeweils im Play Store bzw. in der App verlinkte Fassung.

---

*Stand: 31. August 2026 — Aevum (com.d_drostes_apps.aevum)*