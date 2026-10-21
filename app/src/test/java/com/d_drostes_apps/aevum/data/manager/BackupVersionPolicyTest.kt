package com.d_drostes_apps.aevum.data.manager

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M27: Backup-Versionen.
 *
 * Der alte Code verlangte exakte Gleichheit und hatte zusätzlich eine auf
 * 40 eingefrorene aktuelle Version — seit DB v41 schlug JEDER Restore fehl.
 * Ältere Backups müssen aber erlaubt sein (Room migriert beim Start), sonst
 * ist das Backup beim Gerätewechsel nach einem App-Update unbrauchbar.
 */
class BackupVersionPolicyTest {

    @Test
    fun `gleiche-Version-ist-erlaubt`() {
        assertThat(BackupVersionPolicy.decide(50, 50))
            .isEqualTo(BackupVersionPolicy.Decision.SAME)
        assertThat(BackupVersionPolicy.canRestore(50, 50)).isTrue()
    }

    @Test
    fun `aelteres-Backup-ist-erlaubt-und-wird-migriert`() {
        assertThat(BackupVersionPolicy.decide(40, 50))
            .isEqualTo(BackupVersionPolicy.Decision.OLDER_OK)
        assertThat(BackupVersionPolicy.canRestore(40, 50)).isTrue()
    }

    @Test
    fun `neueres-Backup-wird-abgelehnt`() {
        assertThat(BackupVersionPolicy.decide(51, 50))
            .isEqualTo(BackupVersionPolicy.Decision.NEWER)
        assertThat(BackupVersionPolicy.canRestore(51, 50)).isFalse()
    }

    @Test
    fun `keine-gueltige-Room-Version`() {
        assertThat(BackupVersionPolicy.decide(0, 50))
            .isEqualTo(BackupVersionPolicy.Decision.INVALID)
        assertThat(BackupVersionPolicy.decide(-1, 50))
            .isEqualTo(BackupVersionPolicy.Decision.INVALID)
        assertThat(BackupVersionPolicy.canRestore(0, 50)).isFalse()
    }
}
