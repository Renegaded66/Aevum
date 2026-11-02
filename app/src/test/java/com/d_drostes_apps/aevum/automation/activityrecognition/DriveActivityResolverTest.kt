package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M35: Fahrt-Aktivität aus der Einstellung — reine Fallback-Regeln.
 *
 * Die Erkennung darf durch eine kaputte Einstellung nie lahmgelegt werden:
 * leer, unbekannt oder „radfahren" fallen auf `driving` zurück.
 */
class DriveActivityResolverTest {

    @Test
    fun `Default-ist-driving`() {
        assertThat(DriveActivityResolver.resolveTypeId(null)).isEqualTo("driving")
        assertThat(DriveActivityResolver.resolveTypeId("")).isEqualTo("driving")
        assertThat(DriveActivityResolver.resolveTypeId("   ")).isEqualTo("driving")
    }

    @Test
    fun `nutzerdefinierte-Activity-bleibt-erhalten`() {
        assertThat(DriveActivityResolver.resolveTypeId("unterwegs")).isEqualTo("unterwegs")
        assertThat(DriveActivityResolver.resolveTypeId("  unterwegs  ")).isEqualTo("unterwegs")
    }

    @Test
    fun `radfahren-ist-nie-eine-Fahrt-Activity`() {
        assertThat(DriveActivityResolver.resolveTypeId("radfahren")).isEqualTo("driving")
    }
}
