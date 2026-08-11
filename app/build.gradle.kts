plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.d_drostes_apps.aevum"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.d_drostes_apps.aevum"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // M18.58: Garmin-Bridge-Basis-URL (Cloudflare-Quick-Tunnel zur
        // Aevum-Garmin-Bridge auf dem Server). In der App überschreibbar
        // über die Garmin-Einstellungen (falls der Tunnel neu startet und
        // eine neue URL erhält).
        // M18.62-FIX: Quick-Tunnel-URL rotiert bei JEDEM Server-Neustart —
        // 2026-08-10: residence-joe-virtue-enclosure → submitting-portraits-
        // anderson-solaris. Siehe auch URL-Feld in FitnessTrackersScreen.
        buildConfigField(
            "String",
            "GARMIN_BRIDGE_URL",
            "\"https://submitting-portraits-anderson-solaris.trycloudflare.com\""
        )
        // M18.59: Bridge-API-Key — kommt aus ~/.gradle/gradle.properties
        // (NICHT im Repo, das Repo ist öffentlich!). Leerer Fallback für
        // Fremd-Builds; die App zeigt dann einen klaren Hinweis.
        val bridgeKey = providers.gradleProperty("aevumGarminBridgeKey")
            .orElse("").get()
        buildConfigField(
            "String",
            "GARMIN_BRIDGE_KEY",
            "\"$bridgeKey\""
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        // M18.58: Custom BuildConfig-Feld GARMIN_BRIDGE_URL
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
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

    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // MapLibre for Geofence Editor Map
    implementation("org.maplibre.gl:android-sdk:11.4.0")

    // M8: Health Connect SDK
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    // M18.60: Echte org.json-Implementierung fuer JVM-Unit-Tests
    // (android.jar mocked org.json nicht — "not mocked" Fehler).
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}