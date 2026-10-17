import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.d_drostes_apps.aevum"
    // M18.88: compileSdk/targetSdk 36 (Play-Store-Anforderung 2026).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.d_drostes_apps.aevum"
        minSdk = 29
        targetSdk = 36
        // M18.105: Startup-Crash auf frischen Installationen gefixt
        // (FGS-Vertrag + Permission-Gate, siehe DriveDetectionService).
        // M18.123: App-Start-Crash (M18.122) gefixt — StickyGuard-SharedPrefs
        // aus Property-Init nach onCreate verlagert (NPE vor Context-Attach).
        // M18.125: Notification-Bitmap-Recycling (fahrt-gekoppelter OOM —
        // ~1,3 MB/Tick ohne Recycle) + Crash-Log-Spiegel nach Downloads/Aevum.
        // M18.131: Timeline-Mitternachts-Blockfix (Minuten-Offset statt
        // Uhrzeit), Limit-Vorwarnung 5 min (Channel war seit M19 tot +
        // Warnung war nicht persistent), Termin-Auswahl (calendar_event_pin,
        // DB v42).
        // M18.132: Kalender-Termin-Auswahl 7-Tage-Ansicht, Ghost-Termin-Fix
        // (gelöschte Termine verschwinden aus dem Cache), STATUS-NULL-Fix
        // (Termine ohne Status waren unsichtbar), Öffnungs-Sync, QUEUE-
        // Overlap-Policy (startet, sobald nichts mehr läuft).
        // M18.134 (Kanban t_0bf5541e): Kalender-Aufzeichnung als FALLBACK —
        // ein Termin wird nach einer Verdrängung (Auto-Fahrt, Wanderung,
        // Geofence, App-Tracking, Screen) wieder aufgenommen, solange er
        // läuft und nichts anderes aufzeichnet; die Stop-Pfade stoßen den
        // Kalender-Lauf sofort an statt erst beim 15-Minuten-Takt.
        // M18.135 (Kanban t_099f1911): Radfahren wird nicht mehr als
        // Autofahrt aufgezeichnet — ON_BICYCLE als eigener Motion-Kontext
        // (12-m/s-Gate), Rad-Sessions als eigener Session-Typ, Testmatrix.
        // M18.136 (Kanban t_099f1911, Play-Console-Auflagen): R8 aktiviert
        // (Verschleierung war 0 %) + randlose Anzeige abwärtskompatibel.
        // Siehe proguard-rules.pro und LocalizedActivity.enableEdgeToEdge.
        // M18.139 (Play-Ablehnung Update 1.0.19 / versionCode 20):
        //   1) Prominent In-App Disclosure für Standort im Hintergrund
        //      eingebaut (Dialog vor jedem Permission-Request + beim Start
        //      + im Datenschutz-Screen), DE/EN, mit Wire- und Text-Guards.
        //   2) GeofenceForegroundService ENTFERNT — Geofencing ist ab
        //      26.08.2026 kein genehmigter Anwendungsfall für
        //      FOREGROUND_SERVICE_LOCATION mehr. Die Geofence API braucht
        //      keinen Dienst (Registrierung via GeofencingClient, Übergänge
        //      per PendingIntent an den GeofenceBroadcastReceiver).
        //   3) Manifest bereinigt: FOREGROUND_SERVICE_HEALTH und
        //      SYSTEM_ALERT_WINDOW waren ungenutzt.
        //   4) PrivacyScreen: Link zur Datenschutzerklärung + Standort-
        //      Offenlegung nachlesbar.
        //   5) Datenschutzerklärung (online + docs/) um Abschnitt
        //      "Standort im Hintergrund" erweitert.
        // M18.140: Standardsprache Englisch korrigiert — values/ ist jetzt der
        //   englische Fallback, Deutsch liegt in values-de/. Dazu die
        //   Datenschutz-Seite zweisprachig und die Store-Texte auf Englisch.
        // M18.141: Erklärungsdialog VOR jeder Berechtigungs-Anfrage
        //   (Aktivitätserkennung, Benachrichtigungen, Nutzungszugriff,
        //   Kalender) in DE+EN — dieselbe Logik wie beim Standort, damit der
        //   Grund der Play-Ablehnung nicht bei der nächsten Berechtigung
        //   erneut auftritt. Ohne Persistenz: der Dialog hängt am aktuellen
        //   Berechtigungsstatus und erscheint nach einem Widerruf erneut.
        // M18.142: Fehler aus M18.141 behoben — das Standort-Gate entschied
        //   über die GEMERKTE Zustimmung (LocationDisclosure.isAccepted)
        //   statt über den aktuellen Berechtigungsstatus. Nach einer einmal
        //   bestätigten Offenlegung lief jeder Klick direkt durch: bei
        //   „Location" passierte nichts sichtbares, bei „Background location"
        //   ging es ohne Erklärung in die Einstellungen. Jetzt entscheidet
        //   der Berechtigungsstatus; fehlt die Berechtigung, erscheint der
        //   Dialog immer. Dazu ein Fallback in die App-Einstellungen, wenn
        //   Android keinen Systemdialog mehr zeigt.
        // M18.144: Zwei Geofence-Fehler behoben (Devon, 02.10.2026):
        //   1) Beim Anlegen eines Geofence am AKTUELLEN Ort startete die
        //      Automatisierung nicht — der Nutzer war ja schon drin. Jetzt
        //      läuft direkt nach dem Speichern ein Zonen-Check (nicht
        //      blockierend, im Scope des Providers), der den aktuellen
        //      Standort gegen den neuen Geofence prüft. Nur wenn der
        //      Geofence selbst eine Automatisierung hat.
        //   2) Eine vom Nutzer MANUELL gestartete Aktivität wurde beim
        //      Verlassen des Geofence nicht beendet — beide Stop-Pfade
        //      verlangten sourceType == "GEOFENCE_AUTO". Jetzt entscheidet
        //      die Aktivität (GeofenceAutoStopPolicy), nicht die Herkunft.
        // M18.145 (Play-Konsistenz, gefunden beim Abgleich mit den beiden
        //   Ablehnungsmails zu 1.0.19): M18.141 hat nur die damals bekannten
        //   Einstiege abgesichert. Drei Wege führten weiterhin DIREKT in die
        //   Systemeinstellungen, ohne Offenlegung in der App:
        //     a) DigitalBalanceScreen — „Balance" ist ein Tab in der
        //        Bottom-Navigation; der Button führte direkt in den
        //        Nutzungszugriff.
        //     b) AppTrackingScreen — über den Balance-Tab erreichbar,
        //        derselbe Sonderzugriff, ebenfalls direkt.
        //     c) CalendarRulesScreen (PermanentDenied-Zweig) — der
        //        Banner-Button ging am Gate vorbei in die App-Einstellungen.
        //   Alle drei laufen jetzt über PermissionDisclosureGate.
        //   Neuer Guard: DisclosureCoverageTest prüft nicht mehr nur die zwei
        //   bekannten Dateien, sondern ALLE *Screen*.kt-Dateien daraufhin,
        //   dass kein Sonderzugriff ohne Dialog geöffnet wird.
        //   PC-Tracker 1.1 (1.0.25 / 26):
        //     – Kopplungs-Liste: PC-Name/Status brachen in extrem schmalen Spalten
        //       um (ein Zeichen je Zeile) → gewichtete Spalte, Ellipsis.
        //     – Sync spiegelt jetzt die Aktivitäten der App an den PC (Regeln-
        //       Dropdown dort: „Von deinem Handy“) + Gerätename als Header.
        //     – Kurzanleitung „Windows-App installieren“ im PC-Tracker (DE/EN).
        // M18.147 (App 1.0.27):
        //   1) Neues adaptives App-Icon aus dem gelieferten Artwork
        //      (mipmap-anydpi-v26 + Hintergrund-Layer je Dichte; Manifest
        //      icon/roundIcon umgestellt; Play-Asset docs/store-assets/icon-512.png).
        //   2) App-Aufzeichnung: Paket-Scan + Icon-Dekodierung raus aus dem
        //      Main-Thread (InstalledAppsProvider, Dispatchers.IO, chunkweises
        //      Nachladen) — Screen öffnet sofort, Listen wachsen beim Benutzen
        //      nach; Interaktionen (Verschieben/Zuordnen) sind optimistisch
        //      (sofort sichtbar, DB folgt).
        //   3) PC-Sync zuverlässiger: alle 15 Min nur im WLAN (UNMETERED,
        //      vorher 30 Min / irgendein Netz) + gedrosselter stiller Kick bei
        //      App-Öffnen/Hintergrund-Start (PcSyncGate, >=10 Min Abstand) —
        //      ohne jede Meldung, ohne Akku-Last.
        // M18.149 (App 1.0.28, Icon-Fix): Das Artwork liegt jetzt verkleinert
        //   (76 %) mit dunklem Rand im Icon — auf Devons Handy schnitt die
        //   Kreis-Maske des Launchers den Ring der zehn Bereichs-Icons an
        //   (je Icon halb sichtbar). Neu: Knoten-Außenkante ~35 dp statt ~46 dp,
        //   unter jeder realistischen Maske vollständig sichtbar.
        // M18.150 (App 1.0.29, Play-Console-Auflage): Optimiertes
        //   Ressourcen-Schrumpfen aktiviert (android.r8.optimizedResourceShrinking)
        //   — die Console beanstandete die deaktivierte R8-Ressourcen-
        //   Optimierung im Release "Google play policy fix".
        // M18.151 (App 1.0.30, App-Aufzeichnung × Digital): Übergabe-Regeln
        //   der Smartphone-Aufzeichnungen — eine getrackte App übernimmt
        //   SOFORT von "Digital" (SCREEN_AUTO), App-Wechsel stoppen/starten
        //   direkt, Screen-OFF beendet die App-Session sofort, nach dem
        //   App-Ende setzt sich "Digital" nach der Vorlaufzeit selbst neu
        //   an, und der Digital-Worker startet nie, wenn eine getrackte App
        //   im Vordergrund ist. Fremde Sessions (Geofence/Fahrt/Sport/
        //   manuell/Kalender) behalten strikt Vorrang.
        // M19.1 (App 1.0.31): Projektmanagement — ein selbstreferenzieller
        //   Baum (pm_node, DB v44) für Projekte → Kategorien → Aufgaben →
        //   kleinste Schritte, mit Dashboard-Einstieg (Zähler der offenen
        //   Schritte), von unten einfahrendem Sheet, Tages-Karussell mit
        //   Swipe, Glas mit Juwelen + Feuerwerk (Feier-Gate gegen
        //   Dauerfeuer), Backlog, Tag-planen-Sheet, Sammel-Eingabe und
        //   Fortschrittsbalken je Aufgabe und Projekt. Keine Levels, keine
        //   XP, keine Maskottchen — abhakbare Arbeit statt Spielerei.
        // M19.2 (App 1.0.32): Umbau des Projektmanagements auf ZWEI Knoten-
        //   arten (Projekt = Container, Aufgabe = Blatt) — Nutzerwunsch:
        //   „Ich möchte keine Unterscheidung zwischen Projekt, Kategorie und
        //   Aufgabe." Dazu: To-do-Listen mit Drag & Drop, Löschen und
        //   Inline-Eingabezeile statt Sammel-Textfeld; PM-Dashboard zeigt nur
        //   für heute zugewiesene Aufgaben; das Juwelen-Glas sitzt als
        //   schmale Spalte neben den Todos und füllt sich animiert beim
        //   Abhaken; generische Vorlage aus vier Lebensbereichen (statt der
        //   persönlichen Studien-Struktur, App geht in den Play Store);
        //   App-Theme in Eisblau/Feuerrot mit Schimmer auf Hauptknöpfen;
        //   Digital-Balance: Wiederbelebung nach Reboot/Update/Kill über
        //   BootReceiver + 15-Minuten-Watchdog + Screen-On-Nudge; DB v45
        //   (reine Daten-Migration: CATEGORY→PROJECT, TASK-mit-Kindern→
        //   PROJECT, TODO→TASK).
        // M19.3 (App 1.0.33): Projektmanagement KOMPLETT wieder entfernt
        //   („Ich nehme es zurück. Ich will das ganze PM Management doch
        //   nicht. Also alles davon.") — Dashboard-Knopf, Settings-Eintrag,
        //   Routen, Screens, Domäne, Repository, Vorlage und Tests sind weg;
        //   DB v46 (MIGRATION_45_46 = DROP der Tabelle pm_node samt Inhalt).
        //   App-Aufzeichnung zuverlässig: 2-s-Poll statt 15s, Nachprüfung
        //   auch bei unveränderter App (später angelaufenes „Digital" wird
        //   übernommen) und einmaliges Nachfassen bei Stopp-Races — Ziel:
        //   getrackte App ≤5s nach dem Öffnen übernimmt, nichts anderes
        //   zeichnet parallel auf. Feuerrot nachgeschärft (#FF3B2E/#D32614);
        //   der Schimmer wandert auf den Dashboard-Plus-Knopf.
        // 1.0.35: Update-Knopf gehört nur in die PC-App — der In-App-Updater
        //   der 1.0.34 (GitHub-Releases, System-Installer) ist wieder entfernt;
        //   die Handy-App kommt weiterhin über den Play Store. Sync-Features
        //   aus 1.0.34 (Live-Kanal zum PC, Sofort-Sync beim Öffnen) bleiben.
        // M19.4 (App 1.0.36): Dashboard-Hero 2.0 nach Devons Skizze —
        //   Tag-Cockpit mit zentriertem 180°-Güte-Gauge (Rot→Grün-Skala,
        //   leuchtender Marker + Nadel), drei versetzten Glas-Kacheln
        //   (Erfasst/Schlaf/Bildschirm) mit Schimmer und Zähler-Animation,
        //   Zonen-Chip (Zuhause/Abwesend) neben dem Gauge, 24h-Tagesleiste
        //   mit Stundenlabels und Aufnahme-Status-Balken („Aufnahme läuft"
        //   grün mit Timer / pausiert amber / „Aufnahme bereit" mit
        //   Tagesfortschritt). Das komplette Karten-Drumherum liegt in
        //   EINEM oben abgerundeten Sheet, das sich beim Scrollen über den
        //   gepinnten Hero schiebt (Referenzbild; Parallax an der gemessenen
        //   Hero-Höhe, Deko-Rest schimmert durch die Sheet-Ecken).
        //   Atmosphere-Orbs jetzt in Eisblau/Feuerrot/Gold. Keine DB-,
        //   Navigations- oder Domänen-Änderung.
        // M19.5 (App 1.0.37): Devon-Feinschliff am Dashboard + Maskottchen —
        //   1) Scroll-Dynamik: der komplette obere Bereich (Hero) ist beim
        //      Scrollen GEPINNT (exakte graphicsLayer-Kompensation gegen
        //      den Scroll), nur das Sheet rutscht darüber (vorher: Collapse
        //      mit Fade/Scale/Parallax — komplett entfernt).
        //   2) Der Blubber-Banner (Live-Aufzeichnung) wohnt jetzt IM HERO
        //      direkt unter dem Güte-Gauge (Headline-Zeile entfernt, im
        //      Sheet erscheint nichts doppelt). Emoji-Glyphen ⏸/⇄/■ der
        //      Banner-Buttons exakt zentriert (contentPadding 0 +
        //      fillMaxSize-Box + includeFontPadding aus).
        //   3) Maskottchen (Einstellungen, Default AUS): selbst erstellbares
        //      Canvas-Wesen im Güte-Gauge — lächelt mit der Güte, atmet,
        //      blinzelt, schaut umher, reagiert auf Tippen (Hüpfer+Herzchen,
        //      Wackel+Funkeln, Bäckchen-Pop), schläft 22–6 Uhr außer bei
        //      laufender Aufzeichnung (wechselt live mit Start/Stop); Farbe
        //      (8 Töne), Ohrenform und Bäckchen in den Einstellungen
        //      wählbar. Ohne Maskottchen: alles exakt wie bisher.
        //   4) Kacheln Erfasst/Schlaf/Bildschirm kompakter (kleineres Icon,
        //      engere Abstände — gilt in beiden Modi), Gauge größer (248×148),
        //      damit das Wesen darunter passt.
        //   Keine DB-, Navigations- oder Domänen-Änderungen.
        // M19.6 (App 1.0.38): Maskottchen im PAG-Vorbild-Stil (Devon:
        //   „Halte dich vielleicht an das maskottchen aus PAG") — runder
        //   Körper mit weichem Verlauf (kein Umriss), Antenne mit
        //   leuchtender Spitze (statt Ohren; personalisierbar Kugel/Stern/
        //   Blitz), große weiße Augen mit Pupillen, rosa Wangen, kleiner
        //   Güte-Smile; Reaktionen mit Sternchen statt Herzen
        //   (STAR_HOP/WIGGLE_SPARKLES/BLUSH_POP); Vorgabefarbe PAG-Blau;
        //   im Dashboard größer (108 statt 92 dp). Keine DB-Änderungen.
        // M19.7 (App 1.0.39): PC-Tracker-Verifikation — nach jedem Sync
        //   (Auto-Sync + App-Öffnen-Kick) meldet die App dem PC ausdrücklich
        //   ihren Live-Stand, auch „nichts läuft" (POST /api/live,
        //   session:null). Der PC (ab 1.6.0) startet nur noch mit dieser
        //   frischen Bestätigung. Kein neues UI, keine DB-Änderungen.
        // M20 (App 1.0.40): 1) Ziel-Architektur — DB v47 (goal + 4 Spalten,
        //   neue Tabelle goal_checkin), Zieltypen min/max/Anzahl, Auto-
        //   Tracking über Aktivität/Kategorie/Digital/Aufgaben ODER manuell,
        //   Wochentage, Streaks, Verlauf/Heatmap, Feier-Animation; neuer
        //   Einstellungspunkt „Ziele & Gewohnheiten". 2) App-Aufzeichnung —
        //   Activity-Faltung (Splash→Main leerte den Vordergrund nicht
        //   mehr), frischeres Probe-Fenster + 12-h-Selbstheilung, einheit-
        //   liches ensureAlive/isPollFresh-Netz, Boot-WakeLock + Boot-Check,
        //   sichtbares Diagnose-Protokoll.
        versionCode = 41
        versionName = "1.0.40"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // M18.88: Release-Signierung optional aus local.properties
        // (NICHT eingecheckt). Eintrag-Set:
        //   aevum.release.storeFile=<path>   (relativ zum Repo-Root oder absolut)
        //   aevum.release.storePassword=...
        //   aevum.release.keyAlias=...
        //   aevum.release.keyPassword=...
        // Fehlen die Einträge, bleibt das Release unsigniert (wie bisher) —
        // lokale Builds/CI brechen nicht. Die Upload-Entscheidung (eigener
        // Keystore vs. Play App Signing) trifft der Publisher in der
        // Play Console; die App selbst enthält KEINEN Schlüssel.
        create("releaseConfig") {
            val lpFile = rootProject.file("local.properties")
            if (lpFile.exists()) {
                val props = Properties()
                lpFile.inputStream().use { props.load(it) }
                val storePath = props.getProperty("aevum.release.storeFile")
                if (!storePath.isNullOrBlank()) {
                    storeFile = rootProject.file(storePath)
                    storePassword = props.getProperty("aevum.release.storePassword")
                    keyAlias = props.getProperty("aevum.release.keyAlias")
                    keyPassword = props.getProperty("aevum.release.keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // M18.136: R8 aktiviert (Play-Console-Auflage "DEX-Codeoptimierung
            // liegt unter unserem Grenzwert — Verschleierung 0 %"). Vorher
            // stand hier isMinifyEnabled = false — damit blieb der komplette
            // Code im Klartext im DEX (34,6 MB base/dex/classes*.dex) und
            // Play meldete 0 % Verschleierung in allen drei Kategorien.
            //
            // isShrinkResources entfernt zusätzlich ungenutzte Ressourcen
            // (Referenz-Graph wird zusammen mit dem Code ausgewertet).
            //
            // Die Keep-Regeln in proguard-rules.pro sind aus einem Audit
            // der Reflection-Stellen abgeleitet (WorkManager-Worker,
            // MapLibre-JNI, Hilt-EntryPoints, Enum-valueOf, Manifest-
            // Komponenten) — KEIN pauschaler Keep auf das App-Package.
            //
            // android.r8.strictFullModeForKeepRules=false (gradle.properties)
            // bleibt gesetzt: Keep-Regeln werden in der relaxierten
            // Legacy-Semantik angewandt = konservativer (mehr Code bleibt),
            // was das Risiko für die 25 CoroutineWorker und die
            // Background-Services senkt.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Nur signieren, wenn local.properties die Keystore-Einträge
            // enthält (signingConfig mit storeFile==null würde den Build
            // brechen); sonst unsignierte Artifacts wie bisher.
            if (signingConfigs.getByName("releaseConfig").storeFile != null) {
                signingConfig = signingConfigs.getByName("releaseConfig")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // M18.137 (Kanban t_70a06809): Robolectric-Compose-Tests brauchen die
    // GEMERGTEN Android-Ressourcen (inkl. Manifest). Ohne diese Option sieht
    // Robolectric nur ein leeres Default-Manifest und kann die
    // ComponentActivity aus ui-test-manifest nicht auflösen
    // ("Unable to resolve activity for Intent ... ComponentActivity").
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric leitet user.home aus /etc/passwd ab. Auf dieser
                // Build-Maschine ist /etc/passwd truncated → user.home = "?"
                // und Robolectric bricht ab mit "Couldn't create lock file
                // ?/.robolectric-download-lock". Der Test-JVM das Verzeichnis
                // explizit mitgeben; auf intakten Maschinen zeigt es auf
                // dasselbe Verzeichnis.
                it.systemProperty("user.home", System.getProperty("user.home") ?: System.getenv("HOME") ?: "/root")
            }
        }
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("com.google.dagger:hilt-android:2.60.1")
    kapt("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // MapLibre for Geofence Editor Map
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    // M8: Health Connect SDK
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    // M18.137 (Kanban t_70a06809): Compose-UI-Tests auf JVM-Ebene (Robolectric)
    // fuer den Icon-Toggle der Top-Aktivitäten. Ohne diese Abhaengigkeiten
    // laesst sich der Auf-/Zuklapp-Pfad nur auf einem Geraet pruefen — und
    // hier gibt es keine Hardware-Beschleunigung (/dev/kvm fehlt), also ist
    // Robolectric der einzige verfuegbare Weg zu echter UI-Evidenz.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // M18.135 (Kanban t_f15f4443): Robolectric + echte Room-In-Memory-DB fuer
    // die Kalender-Resume-INTEGRATIONSTESTS. Die Wiedereinstiegs-Evidenz haengt
    // an zwei echten SQL-Queries (getRecentFinishedBySourceType,
    // countForeignSessionsStartingBetween) — handgeschriebene Repository-Fakes
    // koennen von deren Semantik abweichen (deleted_at, Status-Filter,
    // ORDER BY/LIMIT, BETWEEN-Grenzen). Nur eine echte DB beweist die SQL-Seite.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.room:room-testing:2.8.4")
    // M18.60: Echte org.json-Implementierung fuer JVM-Unit-Tests
    // (android.jar mocked org.json nicht — "not mocked" Fehler).
    testImplementation("org.json:json:20240303")
    // PC-Tracker: HTTP-Vertragstests gegen einen echten lokalen Mock-Server
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}