# DECISIONS — Aevum

## ADR-0001 — Appname Aevum

**Entscheidung:** Die App heißt **Aevum**.

**Begründung:** Aevum bedeutet sinngemäß Zeitalter/Lebenszeit und wirkt kurz, hochwertig, international und passend zu Zeit/Bewusstsein/Fortschritt.

**Paketname:** `de.devondroste.aevum`

## ADR-0002 — Offline-first ohne Backend

**Entscheidung:** Aevum hat kein Login, kein Backend und keine Cloud.

**Begründung:** Die App verarbeitet sehr persönliche Lebensdaten. Lokale Datenhaltung maximiert Privatsphäre, reduziert Komplexität und funktioniert offline.

**Alternativen:** Cloud Sync/Auth; wird bewusst ausgeschlossen, bis ein klarer Mehrwert entsteht.

## ADR-0003 — Kotlin + Jetpack Compose + Material 3

**Entscheidung:** Native Android mit Kotlin, Compose und Material 3.

**Begründung:** Beste Basis für hochwertige, moderne Android-UX und visuelle Dashboards.

## ADR-0004 — MVVM/MVI mit StateFlow

**Entscheidung:** ViewModels exponieren immutable `UiState` über StateFlow; UI sendet Events.

**Begründung:** Gute Testbarkeit, robust bei komplexen Dashboards und Permission-/Loading-States.

## ADR-0005 — Room als Source of Truth

**Entscheidung:** Alle Lebensdaten liegen lokal in Room.

**Begründung:** Offline-first, migrationsfähig, testbar, Flow-kompatibel.

## ADR-0006 — Rohsignale getrennt von Activity Sessions

**Entscheidung:** Android API Signale werden als `raw_detection_event` gespeichert und erst danach in bearbeitbare `activity_session` Kandidaten übersetzt.

**Begründung:** Automatische Erkennung ist fehleranfällig. Trennung erlaubt Debugging, Reprocessing und Nutzerkontrolle.

## ADR-0007 — Health Connect primär für Schlaf

**Entscheidung:** Schlafdaten werden bevorzugt über Health Connect gelesen.

**Begründung:** Health Connect ist die moderne Android-Plattform für Gesundheits-/Fitnessdaten inkl. Sleep Sessions.

**Alternative:** Google Sleep API oder manuelle Schlafsessions; bleibt als Fallback/Ergänzung möglich.

## ADR-0008 — UsageStatsManager nur optional

**Entscheidung:** Smartphone-Nutzung wird nur bei explizit erteiltem Usage Access importiert.

**Begründung:** Sonderberechtigung, sensibel, muss freiwillig sein. App bleibt ohne nutzbar.

## ADR-0009 — Geofencing statt dauerndes GPS Tracking

**Entscheidung:** Orte wie Arbeit/Gym werden über Geofencing erkannt, nicht durch permanentes Tracking.

**Begründung:** Batterieschonender und privater. Background Location bleibt dennoch sensibel und optional.

## ADR-0010 — Dashboard-first Produktstrategie

**Entscheidung:** Dashboard ist der wichtigste Screen und Startpunkt nach Onboarding.

**Begründung:** Der Kernnutzen ist visuelles Lebensbewusstsein, nicht Dateneingabe.
