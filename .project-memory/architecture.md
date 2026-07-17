# Architecture

Projekt: **Aevum** (`de.devondroste.aevum`)

Aevum ist ein offline-first Life-Management- und Life-Analytics-Assistent. Keine Cloud, kein Login, kein Backend.

M2 ist abgeschlossen: Android-Projektgrundlage mit Kotlin, Jetpack Compose, Material 3, Light/Dark Theme, Hilt, Room, DataStore, Navigation Compose, Testsetup und App Shell ist buildfähig.

Wichtiges Architekturprinzip: Android API Signale werden als `RawDetectionEvent` gespeichert und erst durch eine Classification Pipeline zu bearbeitbaren `ActivitySession` Kandidaten. Manuell bearbeitete Sessions haben höchste Priorität.

Siehe `docs/MASTERPLAN.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE.md`, `docs/AUTOMATION_SYSTEM.md`, `docs/PROJECT_STATE.md`.
