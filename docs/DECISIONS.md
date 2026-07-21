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

## ADR-0017 — Room-Schemaänderungen brauchen Migrationstests

**Entscheidung:** Jede Änderung am Room-Schema muss passende Migrationstests enthalten. Mindestens vorherige Version → aktuelle Version; bei kritischen Änderungen zusätzlich ältere Versionen → aktuelle Version. Neue Foreign Keys, Indizes und Constraints werden explizit getestet.

**Begründung:** Der M6.1-Gerätecrash wurde durch eine unvollständige Migration verursacht, die in In-Memory-/Neuinstallations-Tests nicht sichtbar war. Aevum verarbeitet langlebige lokale Daten; Upgrade-Pfade sind produktkritisch.

**Konsequenz:** Vor jedem Commit wird geprüft, ob bessere Tests einen Fehler erkannt hätten. Wenn ja, werden Tests ergänzt. Wenn Android-Tests mangels Gerät/Emulator nicht laufen, wird das klar dokumentiert.

## ADR-0018 — M6.2 nutzt lokale Trigger-Pair-Regeln statt Blackbox-Erkennung

**Entscheidung:** Geofence-Candidates entstehen primär aus erklärbaren Trigger-Paaren, nicht aus einzelnen Triggern oder einer undurchsichtigen Klassifikation.

**Begründung:** Automatisierung soll Vertrauen schaffen. Einzelne Trigger sind Fakten, aber noch keine Aktivitätsintervalle. Paare wie `Home verlassen → Gym betreten` oder `Arbeit betreten → verlassen` sind nachvollziehbarer und später gut erweiterbar.

**Konsequenz:** `TriggerPairCandidateRuleEngine` ist lokal, deterministisch, idempotent und schreibt lesbare Reasons in Candidates. Offene Trigger ohne Ziel bleiben unresolved.

## ADR-0019 — M6.3a Dashboard wird Daily Review statt Statistikcontainer

**Entscheidung:** Das Dashboard wird vom Statistikcontainer zur täglichen Reflexionsfläche umgebaut. Above-the-fold beantwortet „Was war heute wichtig?“ statt 20 Metriken anzuzeigen.

**Begründung:** Premium-Apps wie Oura, Rise, Flighty oder Linear zeigen beim Öffnen sofort den Kontext, nicht Daten. Aevums Vision war: „Eine App, die mir hilft, meine Zeit zu verstehen.“ Das alte Dashboard zeigte Zeitverteilung, Signal Strip, Tagesfluss als Liste, Fokus-Score, Growth und Digital Balance — das war technisch solide, aber emotional distanziert.

**Konsequenz:**
- Daily Review Hero mit Headline und Narrativ
- Visueller Tagesfluss als 24h Lebensfluss
- Wenige kuratierte Elemente statt Karten-Sammlung
- Balance Score sanft heuristisch, nicht als Leistungswert
- Kandidaten ruhig integriert: zählen erst nach Entscheidung
- Empty States mit Premium-Copywriting

## ADR-0020 — M6.3b Review Inbox als Vertrauens-Ort

**Entscheidung:** Automatische Vorschläge bekommen einen eigenen `ReviewInboxScreen`, statt nur als kleine Dashboard- oder Timeline-Karte behandelt zu werden.

**Begründung:** Aevum baut Vertrauen auf, wenn automatische Erkennung transparent und kontrollierbar bleibt. Nutzer müssen jederzeit verstehen: Ein Vorschlag ist vorbereitet, aber noch keine Wahrheit. Ein eigener ruhiger Review-Ort macht Confidence, Zeitraum, Grund und Aktionen sichtbar, ohne das Dashboard zu überladen.

**Konsequenz:**
- Dashboard bleibt Daily Review und navigiert bei offenen Vorschlägen in die Review Inbox.
- Review Inbox bietet Übernehmen, Bearbeiten und Verwerfen.
- Übernehmen nutzt `ReviewCandidateUseCase.accept()` und erzeugt erst dann eine bestätigte `activity_session`.
- Bearbeiten nutzt den bestehenden Candidate-Prefill-Editor.
- Verwerfen nutzt `ReviewCandidateUseCase.dismiss()`.
- Keine neue Room-Version in M6.3b; Tagesnotizen bleiben bis zu echtem Nutzertest ohne Schemaänderung konzeptionell vorbereitet.

## ADR-0021 — M6.4 Insights nutzt bestehende Sessions statt neuer Analytics-Architektur

**Entscheidung:** Life Analytics v1 wird als eigener `InsightsScreen` umgesetzt, berechnet aber alle Kennzahlen direkt aus bestehenden `activity_session`-Daten, Kategorien und Activity Types. Es gibt keine neue Room-Tabelle, keine neue Sensorquelle, keine lokale KI und keine komplexe Rule Engine.

**Begründung:** Der Produktnutzen von M6.4 ist Sichtbarkeit: Zeitverteilung, Veränderungen und Muster hochwertig darstellen. Eine neue Analytics-Architektur wäre für v1 zu früh und würde Risiko erhöhen, ohne dem Nutzer sofort mehr Verständnis zu geben.

**Konsequenz:**
- `InsightsAnalytics` ist reine, unit-testbare Kotlin-Logik.
- Der Screen bietet Heute/Woche/Monat, Donut, Vorperiodenvergleich, Top-Aktivitäten, Balance, Insight Cards und Wochen-Heatmap.
- Vorperiodenvergleiche erscheinen nur bei echten Daten; es werden keine künstlichen Zahlen erzeugt.
- Insight Cards sind regelbasiert und beobachtend, nicht bewertend.
- Spätere Datenquellen wie Health Connect, UsageStats oder Activity Recognition können später in Sessions/Candidates einfließen, ohne M6.4 rückwirkend umzubauen.

## ADR-0022 — M6.5 Weekly Review als Reflexion statt Report

**Entscheidung:** Der Weekly Review wird als eigener Screen aus Insights heraus umgesetzt und nutzt ausschließlich bestehende Activity Sessions, Kategorien, Activity Types und Pending Candidates. Er erzeugt ein regelbasiertes Wochen-Narrativ, Wochen-Zeitstrahl, Donut, Vorwochenvergleich, Highlights, Wochenmuster, offene Zeit und Review-Inbox-Hinweis.

**Begründung:** Aevum soll regelmäßig bewusst geöffnet werden. Ein wöchentlicher Rückblick schafft einen wiederkehrenden Reflexionsmoment, ohne neue Datenquellen oder Automatisierung vorauszusetzen. Die Darstellung bleibt ruhig wie Oura/Apple Health und vermeidet Report-/BI-Gefühl.

**Konsequenz:**
- Keine neue Room-Version, keine neue Infrastruktur, keine KI und keine neuen Berechtigungen.
- `WeeklyReviewAnalytics` ist pure Kotlin und unit-testbar.
- Aussagen werden nur erzeugt, wenn echte aktuelle bzw. Vorwochendaten vorhanden sind.
- Sprache bleibt beobachtend und nicht bewertend.
- Weekly Review kann später durch neue Session-Quellen profitieren, ohne selbst Sensorlogik zu enthalten.

## ADR-0023 — M6.6 Goals & Habits MVP + Geofence UX Fix

**Entscheidung:** M6.6 implementiert Goals & Habits MVP als ersten nutzbaren Schritt des Wachstums-Systems (M8 in ROADMAP vorgezogen) und behebt den Geofence-Editor-UX-Mangel durch eine echte Karten-SDK-Lösung.

**Begründung für MVP-Vorgezug:**
- Dashboard, Insights und Weekly Review sind stabil; Nutzer braucht jetzt sichtbaren Fortschrittsnutzen.
- Goals & Habits geben dem Nutzer einen Grund, täglich/wochentlich aktiv zu bleiben — Kern der Retention.
- Room-Schema für Goal/Habit/HabitLog existiert bereits seit M4; Implementierung ist reine UI/Logic-Schicht.
- Keine neue Room-Version nötig; bestehende Migrationspfade bleiben stabil.

**Goals MVP Umfang:**
- CRUD für Ziele: Name, Activity Type, Zieltyp (Mindestens/Höchstens), Zeitraum (Tag/Woche/Monat), Zielwert, Einheit.
- Fortschrittskarten im Dashboard (max 3) und Insights ("Fortschritt"-Sektion).
- Darstellung ruhig, hochwertig, nicht gamifiziert: "Sport diese Woche: 2h 15m von 3h", "Digitalzeit heute: 1h 20m von maximal 2h".
- Leerer Zustand: "Du kannst Ziele anlegen, um deinen Fortschritt sichtbar zu machen."

**Habits MVP Umfang:**
- CRUD für Gewohnheiten: Titel, Activity Type, Frequenzregel (JSON), Erfolgsregel (JSON).
- Darstellung: kleine Heatmap, Streak, Erfolgsquote.
- Keine Punkte, Level, Badges, künstliche Gamification.

**Dashboard Integration:**
- Maximal 3 Ziel-Karten (Priorität: aktivste Ziele mit Fortschritt > 0%).
- Falls keine Ziele: ruhiger Empty State mit Hinweis.

**Insights Integration:**
- Neue Sektion "Fortschritt" mit: aktive Ziele, Habits, Trends.
- Weekly Review erwähnt Zielfortschritt.

## ADR-0024 — M6.6 Geofence UX Fix: Karten-SDK Entscheidung

**Entscheidung:** MapLibre GL Native (via MapLibre Android SDK) mit OpenStreetMap-Vektorkacheln (z. B. MapTiler, OpenMapTiles oder self-hosted) für den Geofence-Editor.

**Alternativen geprüft:**
| Option | Vorteile | Nachteile | Entscheidung |
|--------|----------|-----------|--------------|
| Aktueller Canvas-Grid | Keine Dependency | Keine echte Karte, Marker nicht verortbar, kein Zoom/Pan, keine Adresssuche | ❌ Unzureichend |
| Google Maps SDK | Reife, Satellit, Places API | Kosten (ab 200$/Monat ab 28k Laden), API-Key, Tracking, keine Offline-Kacheln, Datenschutz kritisch | ❌ Nur wenn eindeutig besser |
| Mapbox SDK | Schön, Vektorkacheln | Lizenzänderung (BSL), Kosten ab 50k MAU, Tracking | ❌ Lizenzrisiko |
| MapLibre GL Native + OSM | Open Source (BSD-2), Offline-fähig, keine Kosten, Vektorkacheln, Self-Host möglich, Datenschutz-freundlich | Weniger POI-Daten out-of-the-box, Setup etwas aufwendiger | ✅ **Gewählt** |
| osmdroid (Raster) | Einfach, OSM | Rasterkacheln (nicht Vektor), langsamer, veraltet | ❌ |

**Begründung MapLibre:**
- Open Source (BSD-2), keine Laufzeitkosten, keine Tracking-Pflicht.
- Vektorkacheln: flüssiges Zoomen, Drehen, Neigen, hochauflösend auf allen Dichten.
- Offline-first: MBTiles-Pakete können gebündelt oder nachgeladen werden — passt zu Aevums Offline-Philosophie.
- Datenschutz: Keine Google-/Mapbox-Telemetrie; Kachelserver frei wählbar (MapTiler Free Tier, Thunderforest, self-hosted).
- Android SDK ist stabil (MapLibre Native 11.x + Compose Interop via `MapView` Wrapper).
- Langfristig erweiterbar: Geocoding/Reverse Geocoding via Nominatim/Photon (Open Source), Routing via OSRM/Valhalla.

**Implementierungsdetails:**
- `MapView` Compose-Wrapper um `org.maplibre.android.MapView` (Android View Interop).
- Initialer Style: OSM Bright / MapTiler Basic (Light/Dark passend zu Aevum Theme).
- Geofence-Editor: sichtbare Karte, Marker (Zentrum), Radius-Kreis (Polygon/GeoJSON), Zoom/Pan, "Aktuelle Position" Button (FusedLocationProvider), Zuhause/Arbeit Presets.
- Radius per Slider/Stepper editierbar, Marker verschiebbar.
- Keine Google-Maps-Abhängigkeit im Code; optionaler Fallback später nur bei expliziter Nutzerwahl.

**Datenschutz & Offline:**
- Keine Account-Pflicht, keine Telemetrie an MapLibre.
- Kachel-Cache im App-Cache-Verzeichnis; Nutzer kann Cache leeren.
- Optional: Offline-MBTiles für häufige Regionen (späteres Feature).

**Migration:** Keine Room-Migration nötig (nur UI/Dependency). Bestehende Geofence-Daten unverändert.

**Definition of Done M6.6:**
- Goals CRUD + Dashboard/Insights/Weekly Integration
- Habits CRUD + Dashboard/Insights/Weekly Integration
- Geofence Editor mit echter MapLibre-Karte (Hintergrund, Marker, Radius, Zoom, Pan, aktuelle Position, Presets)
- Alle Tests grün: `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`
- Commit + aktualisierte Doku + Known Limitations + M7-Empfehlung

## ADR-0025 — M7 Scope: Automation Experience v1 statt Health Connect

**Entscheidung:** M7 fokussiert auf Automation Experience (Trigger-Regeln, Merge-Engine, Timeline-Integration, Multi-Review-Workflow, Dashboard-Karte) statt Health Connect / UsageStats.

**Begründung:** Die bestehende Geofence-Automation ist die Grundlage für Vertrauen in automatische Erkennung. Health Connect ohne nutzbaren Review-Flow wäre toter Code. Der Nutzer muss zuerst verstehen und kontrollieren können, was Aevum automatisch erkennt, bevor neue Datenquellen integriert werden.

**Konsequenz:** M7 liefert ein durchgängiges Erfassungserlebnis: Trigger-Erkennung → Merge → Timeline-Vorschau → Multi-Review → Dashboard-Status. Health Connect und UsageStats werden auf M8 oder später verschoben.

## ADR-0026 — Candidate Merge Engine: deterministisch, lokal

**Entscheidung:** Eine lokale, deterministische Merge-Engine fasst mehrere zeitnahe Candidates mit gleicher suggestedCategoryId zu einem Candidate zusammen. Merge-Regeln: Lücke ≤5min, Maximalspanne ≤30min.

**Begründung:** Zersplitterte Candidates (z.B. 3 Fahrt-Fragmente statt einer Fahrt) sind das hässlichste UX-Problem der Automatisierung. Merge vor Timeline-Anzeige reduziert Noise und macht Vorschläge auf einen Blick verständlich. Deterministische Regeln sind erklärbar und schaffen Vertrauen — keine Blackbox.

**Konsequenz:** Merge läuft in `CandidateRuleOrchestrator` nach der Trigger-Pair-Generierung und vor dem Insert. Merged Candidates bekommen eine neue ID (`merged_...`) und gemittelte Confidence.

## ADR-0027 — Trigger Debug & Quality Metrics: Minimal in M7

**Entscheidung:** Trigger-Debug bekommt nur DAO-Query-Methoden (kein eigenes UI). Quality Metrics werden aus bestehenden Candidate-Daten (Status-Feld: PENDING/ACCEPTED/DISMISSED) abgeleitet. Keine neue Tabelle in M7.

**Begründung:** Ohne reale Nutzungsdaten wären Metriken hypothetisch und eine Debug-UI wäre Overengineering. Die bestehenden Candidate-Daten reichen für erste Qualitätsaussagen (Accept-Rate = ACCEPTED / (ACCEPTED + DISMISSED)). Ein dediziertes Qualitätslogging kann in M7.1/M8 mit echten Daten sinnvoll werden.

**Konsequenz:** Keine Room-Migration für Qualitätstabellen. `ActivityCandidateDao` bekommt zusätzliche Query-Methoden. Dashboard-Karte und Debug-Queries nutzen bestehende Daten.

## M7 — Automation Experience v1 (Daily Capture)

**Ziel:** Nutzer soll einen normalen Tag verbringen und anschließend möglichst viele Aktivitäten bereits als Vorschläge vorfinden. Review soll sich extrem leicht anfühlen.

**Aufgaben:** Trigger-Pair-Engine erweitern, Candidate Merge Engine, Candidate Timeline UX, Multi-Review-Workflow, Dashboard Automatisierungs-Karte, minimale Debug-Queries.

**Definition of Done:** Candidates erscheinen halbtransparent im Tagesfluss, Merge reduziert Fragmente, Multi-Select im Review, „Alle sicheren übernehmen", Dashboard zeigt Automatisierungs-Status.

## M8 — Health Connect / Sleep & UsageStats

**Ziel:** Persönliche Entwicklungssysteme.

**Aufgaben:** Goals, Habits, Streak-Berechnung, Heatmap, automatische Zielprüfung.

**Tests:** TDD für Ziel-/Streak-Regeln.

**Definition of Done:** Ziele/Habits werden aus Sessions automatisch bewertet.

## M9 — Bucket List & Life Progress

**Ziel:** Langfristige Lebensperspektive.

**Aufgaben:** Bucket List CRUD, Fortschritt, Life Grid, Lebenszeitberechnung.

**Tests:** Berechnungslogik, UI Tests.

**Definition of Done:** Bucket List und Lebensstatistik sind im Dashboard/Insights sichtbar.

## M10 — Premium Polish, Performance, Release

**Ziel:** stabile Premium-App.

**Aufgaben:** Lint, Performance, Accessibility, Baseline Profiles prüfen, APK-Verifikation.

**Tests:** komplette Suite, APK badging/signature, manuelle Smoke Tests.

**Definition of Done:** verifizierte installierbare APK liegt vor.