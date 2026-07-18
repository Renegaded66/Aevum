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

## ADR-0011 — minSdk auf API 29 anheben

**Entscheidung:** minSdk wird auf API 29 (Android 10) angehoben.

**Begründung:**
- Die ursprüngliche Produktvorgabe war Android 10+.
- Aevum ist als Premium-App mit langfristigem Qualitätsanspruch geplant, nicht als maximale Altgeräte-Abdeckung.
- Eine kleinere Geräte-/OS-Matrix reduziert QA-Aufwand und Risiko bei Sensor-, Permission-, Background- und Datenschutzverhalten.
- API 29 passt besser zum modernen Android-Privacy-Modell und zur geplanten Arbeit mit sensiblen Lebensdaten.
- Es gibt aktuell keinen klaren Produktvorteil, API 26–28 weiter zu unterstützen.

**Alternativen:** minSdk 26 beibehalten – hätte breitere Geräteabdeckung, erhöht aber Testmatrix und Altverhalten ohne erkennbaren Kernnutzen für das Premium-Zielbild.

**Konsequenz:** Build-Konfiguration und APK-Badging weisen `minSdk=29` aus. Falls später eine Lite-/Legacy-Variante sinnvoll wird, wird sie separat entschieden.

## ADR-0012 — Screen UX Review Gate

**Entscheidung:** Jeder neue Screen erhält vor der Implementierung einen kurzen UX-Review.

**Leitfrage:** „Wenn diese App morgen im Play Store erscheinen würde und mit den besten Produktivitäts-Apps konkurrieren müsste – wäre ich stolz auf diesen Screen?“

**Begründung:** Aevum soll als Premium-App wirken. Geschwindigkeit darf nicht dazu führen, dass Screens wie generische Material-Beispiele aussehen oder den Nutzer täglich ermüden.

**Konsequenz:** Screens werden vor Code-Arbeit auf Informationshierarchie, Textmenge, Kartenanzahl, visuelle Darstellung, Interaktionsklarheit, Dark-Mode-Wirkung und sinnvolle Animationen geprüft. Wenn das Ergebnis nicht hochwertig genug ist, wird zuerst das UX-Konzept verbessert.

## ADR-0013 — Zeitintervalle als kanonisches Aktivitätsmodell

**Entscheidung:** Alles, was Lebenszeit beschreibt, wird als Zeitintervall modelliert. Die kanonische Nutzerwahrheit liegt in `activity_session`; Schlaf, Arbeit, Autofahrt, Lernen, Handy-Nutzung, Fitnessstudio, Meditation und Lesen sind keine separaten Primärmodelle, sondern Aktivitäts-Sessions mit Kategorie, Activity Type und Tags.

**Begründung:** Aevum basiert auf Lebenszeit. Ein einheitliches Intervallmodell ermöglicht langfristig beliebige Statistiken, neue Aktivitätstypen und neue Quellen, ohne Kernschema-Refactorings.

**Konsequenz:** Neue Fachfeatures bewerten oder erzeugen Sessions, statt eigene parallele Zeitmodelle aufzubauen. Sonderdaten bleiben als Evidence/Raw Payload erhalten.

## ADR-0014 — Raw Events, Detection Events, Candidates, Sessions und Evidence trennen

**Entscheidung:** M4 trennt die Ebenen `raw_source_event`, `detection_event`, `activity_candidate`, `activity_session` und `session_evidence`.

**Begründung:** Automatische Erkennung ist fehleranfällig. Kandidaten sind nicht die Nutzerwahrheit. Evidence macht Automatisierung erklärbar und erlaubt Reprocessing, lokale KI-Auswertungen und spätere neue Sensoren.

**Konsequenz:** `activity_session.status=CANDIDATE` wird nicht als langfristiges Zielmodell verwendet. Kandidaten bekommen eine eigene Tabelle und werden erst durch Annahme/Bearbeitung zu Sessions.

## ADR-0015 — Activity Type getrennt von Kategorie

**Entscheidung:** M4 führt `activity_type` als semantische Ebene ein, getrennt von visuellen Nutzerkategorien.

**Begründung:** Kategorien sind für Nutzer und Charts gedacht; Activity Types sind stabile fachliche Bedeutungen wie `sleep`, `driving`, `meditation`, `reading`. Diese Trennung erlaubt neue Aktivitätstypen, alternative Gruppierungen und bessere Statistikfilter ohne Migration.

**Konsequenz:** Sessions können sowohl `category_id` als auch `activity_type_id` tragen. Neue Aktivitätstypen werden als Daten/Seeds ergänzt, nicht durch Schemaänderungen.

## ADR-0016 — ActivitySession Historie bleibt nachvollziehbar

**Entscheidung:** Jede fachlich relevante Änderung an einer bestätigten `activity_session` wird historisch nachvollziehbar gespeichert. M4 ergänzt dafür `created_by`, `updated_by`, `source_candidate_id`, optional `supersedes_session_id` und eine kleine `activity_session_change` Historie mit Before/After-Snapshots.

**Begründung:** Aevum soll später aus Nutzerkorrekturen lernen können. Wenn ein automatischer Vorschlag „Arbeit 08:00–17:00“ später zu „08:15–16:45“ geändert wird, sind ursprünglicher Vorschlag und finale Nutzerentscheidung wertvolle Daten für Debugging, bessere Erkennungsalgorithmen, lokale KI-Auswertungen und Reprocessing.

**Konsequenz:** Die aktuelle Session bleibt für Timeline/Statistiken einfach abfragbar, aber Herkunft und Änderungen bleiben auditierbar. Die Historie ist bewusst klein und nicht als Event-Sourcing-System für jede technische Kleinigkeit gedacht.
