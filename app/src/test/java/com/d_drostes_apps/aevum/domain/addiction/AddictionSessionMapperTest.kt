package com.d_drostes_apps.aevum.domain.addiction

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.domain.digital.UsageEventFolding
import org.junit.Test

/**
 * M27: Phasen → Sitzungen.
 *
 * Beweist die drei Regeln, an denen die Datenqualität hängt:
 *  - nur beobachtete Pakete,
 *  - keine Rausch-Schnipsel unter 3 s,
 *  - stabile ID pro Paket+Start (idempotenter Upsert).
 */
class AddictionSessionMapperTest {

    @Test
    fun `filtert-Pakete-und-Kurzphasen`() {
        val phases = listOf(
            UsageEventFolding.Phase("com.instagram.android", 1_000, 11_000),
            UsageEventFolding.Phase("com.instagram.android", 20_000, 21_000), // 1 s
            UsageEventFolding.Phase("com.facebook.katana", 30_000, 60_000),
            UsageEventFolding.Phase("com.zhiliaoapp.musically", 70_000, 100_000)
        )

        val sessions = AddictionSessionMapper.toSessions(
            phases = phases,
            watchedPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically")
        )

        assertThat(sessions).hasSize(2)
        assertThat(sessions.map { it.packageName }).containsExactly(
            "com.instagram.android",
            "com.zhiliaoapp.musically"
        )
    }

    @Test
    fun `ID-ist-paket-plus-start-und-macht-Scans-idempotent`() {
        val phase = UsageEventFolding.Phase("com.instagram.android", 42_000, 52_000)

        val first = AddictionSessionMapper.toSessions(listOf(phase), setOf(phase.packageName)).single()
        val second = AddictionSessionMapper.toSessions(listOf(phase), setOf(phase.packageName)).single()

        assertThat(first.id).isEqualTo(second.id)
        assertThat(first.id).isEqualTo("com.instagram.android@42000")
    }

    @Test
    fun `Screen-Off-schliesst-offene-Sitzung`() {
        val folder = UsageEventFolding.Folder(clipStart = 0)
        folder.onForeground("com.instagram.android", ".MainActivity", 1_000)
        // Sperren: kein ACTIVITY_PAUSED — die offene Sitzung muss enden.
        folder.onScreenOff(5_000)
        folder.onForeground("com.instagram.android", ".MainActivity", 6_000)
        val phases = folder.finish(8_000)

        assertThat(phases).hasSize(2)
        assertThat(phases[0].start).isEqualTo(1_000)
        assertThat(phases[0].end).isEqualTo(5_000)
        assertThat(phases[1].start).isEqualTo(6_000)
        assertThat(phases[1].end).isEqualTo(8_000)
    }
}
