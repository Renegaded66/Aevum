package com.d_drostes_apps.aevum.util

import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.util.Locale

/**
 * M18.129-i18n: Guard-Test für den Sprachwechsel-Mechanismus.
 *
 * Sichert die Kette ab, die dafür sorgt, dass die Kalender-Einstellungen
 * im englischen Locale englisch erscheinen: Der gespeicherte Sprach-Code
 * ("system"/"de"/"en") wird über [LocaleHelper.localeFor] in ein Locale
 * übersetzt und über [AppLocale.update] als App-weites Runtime-Locale
 * gesetzt. Diese reine JVM-Schicht ist testbar — die Ressourcen-Auflösung
 * selbst (stringResource gegen values/ vs. values-en/) wird durch
 * [com.d_drostes_apps.aevum.domain.calendar.CalendarStringsI18nTest]
 * auf Dateiebene bewacht.
 *
 * Isolation: [AppLocale] und `Locale.setDefault` sind JVM-weiter Zustand.
 * Jeder Test bekommt eine frische Instanz, die bei der Initialisierung
 * den Ausgangszustand snapshottet; [@After] stellt beide zurück — sonst
 * würde ein Test seinen Zustand in den nächsten leaken.
 */
class LocaleSwitchTest {

    /** Schnappschuss pro Test-Instanz (JUnit erzeugt je Test eine neue Instanz). */
    private val originalDefault: Locale = Locale.getDefault()
    private val originalAppLocale: Locale = AppLocale.current

    @After
    fun restoreLocaleState() {
        Locale.setDefault(originalDefault)
        AppLocale.update(originalAppLocale)
    }

    @Test
    fun `Sprach-Code de wird auf Deutsch abgebildet`() {
        assertThat(LocaleHelper.localeFor(LanguageRepository.LANGUAGE_DE)).isEqualTo(Locale.GERMAN)
    }

    @Test
    fun `Sprach-Code en wird auf Englisch abgebildet`() {
        assertThat(LocaleHelper.localeFor(LanguageRepository.LANGUAGE_EN)).isEqualTo(Locale.ENGLISH)
    }

    @Test
    fun `system ergibt kein erzwungenes Locale`() {
        // "system" darf kein Locale erzwingen — die App folgt dann der
        // Systemsprache (applyLocale lässt den Context unverändert).
        assertThat(LocaleHelper.localeFor(LanguageRepository.LANGUAGE_SYSTEM)).isNull()
    }

    @Test
    fun `Default-Locale ist Deutsch (bisheriges Verhalten fuer Tests und Erstzugriff)`() {
        assertThat(originalAppLocale).isEqualTo(Locale.GERMAN)
    }

    @Test
    fun `Sprachwechsel auf Englisch setzt das App-Locale auf Englisch`() {
        AppLocale.update(Locale.ENGLISH)
        assertThat(AppLocale.current).isEqualTo(Locale.ENGLISH)
    }

    @Test
    fun `Sprachwechsel zieht das JVM-Default-Locale mit`() {
        AppLocale.update(Locale.ENGLISH)
        // JVM-Formatter (TimeFormatting, Wochentagsnamen) nutzen
        // Locale.getDefault() — sie müssen dem App-Locale folgen.
        assertThat(Locale.getDefault()).isEqualTo(Locale.ENGLISH)
    }

    @Test
    fun `Wechsel zurueck auf Deutsch funktioniert nach Englisch`() {
        AppLocale.update(Locale.ENGLISH)
        AppLocale.update(Locale.GERMAN)
        assertThat(AppLocale.current).isEqualTo(Locale.GERMAN)
        assertThat(Locale.getDefault()).isEqualTo(Locale.GERMAN)
    }

    @Test
    fun `gespeicherte Sprachwahl ist als Konstante in der Repository abgebildet`() {
        // Der Settings-Screen schreibt diese Codes; nur de/en/system sind
        // definiert. Der Test dokumentiert die vollständige Abbildung.
        val known = setOf(
            LanguageRepository.LANGUAGE_SYSTEM,
            LanguageRepository.LANGUAGE_DE,
            LanguageRepository.LANGUAGE_EN
        )
        assertThat(known).hasSize(3)
    }
}
