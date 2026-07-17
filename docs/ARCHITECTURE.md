# ARCHITECTURE

## Prinzipien

1. **Separation of Concerns:** UI, Domain und Data sind getrennt.
2. **Unidirectional Data Flow:** UI sendet Events, ViewModel reduziert State, UI rendert State.
3. **Offline-first als Default:** lokale Daten als primäre Quelle, Sync optional.
4. **Testbarkeit:** Domain und Data-Abstraktionen ohne Android Framework testbar.
5. **Design System first:** Screens nutzen zentrale Tokens/Komponenten.

## Zielstruktur

```text
premium-android-app/
  docs/
  app/                         # Android Application Modul, später
  core/
    common/                    # Result, Fehler, Dispatchers, Extensions
    model/                     # Domain Models
    database/                  # Room DB, DAO, Entities, Migrations
    datastore/                 # DataStore Preferences
    network/                   # Retrofit/OkHttp, DTOs, Auth Interceptors
    design-system/             # Theme, Tokens, Components
    analytics/                 # Analytics abstraction
  feature/
    onboarding/
    home/
    statistics/
    settings/
```

## Layer

### UI Layer

- Compose Screens
- Stateless UI Components
- ViewModels pro Screen/Feature
- `UiState` als immutable data class
- `UiEvent` für User-Aktionen
- Navigation nur über definierte Screen Contracts

### Domain Layer

- Use-Cases für Businessregeln
- Repository Interfaces
- Plain Kotlin, keine Android-Abhängigkeit
- Tests zuerst

### Data Layer

- Repository Implementierungen
- Room LocalDataSource
- RemoteDataSource falls Backend existiert
- Sync Queue falls Offline/Cloud kombiniert wird

## Datenfluss

```text
User Action -> UiEvent -> ViewModel -> UseCase -> Repository -> DB/API
DB/API -> Flow/Result -> ViewModel UiState -> Compose UI
```

## Fehler-/Loading-Modell

Jeder Screen unterstützt:

- `Loading`
- `Content`
- `Empty`
- `Error(message, retry)`

Keine stillen Fehler. Technische Details werden intern geloggt, Nutzertexte bleiben verständlich.

## Performance-Architektur

- LazyColumn/LazyGrid für Listen
- stabile Keys
- `remember`/`derivedStateOf` sparsam und gezielt
- keine schweren Berechnungen in Composables
- Aggregationen im Repository/UseCase
- Paging bei großen Datenmengen
- Baseline Profiles später prüfen
