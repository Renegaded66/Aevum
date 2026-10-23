package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.navigation.activityCreateRoute
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Route für "neue Aktivität" mit optionalem Ende aus einer Timeline-Lücke. */
class ActivityCreateRouteTest {

    @Test
    fun routeWithoutEndKeepsEditorDefault() {
        assertThat(activityCreateRoute(1_700_000_000_000L))
            .isEqualTo("activity/new/1700000000000")
    }

    @Test
    fun routeWithEndCarriesTheGapEnd() {
        assertThat(activityCreateRoute(1_700_000_000_000L, 1_700_003_600_000L))
            .isEqualTo("activity/new/1700000000000?end=1700003600000")
    }
}
