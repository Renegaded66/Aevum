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
        versionCode = 20
        versionName = "1.0.19"
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

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}