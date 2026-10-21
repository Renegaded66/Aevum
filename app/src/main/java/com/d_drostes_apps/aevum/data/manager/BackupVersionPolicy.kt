package com.d_drostes_apps.aevum.data.manager

/**
 * M27: Darf ein Backup mit [backupVersion] in eine App mit
 * [currentVersion] eingespielt werden?
 *
 * Vorher verlangte Aevum EXAKTE Gleichheit. Das war doppelt falsch:
 *  1. Die Konstante im DataManager war auf 40 eingefroren, während die DB
 *     längst bei 49 stand — jeder Restore scheiterte.
 *  2. Selbst mit gepflegter Konstante hätte ein Backup von einem älteren
 *     App-Stand nach einem Update nicht mehr funktioniert. Genau beim
 *     Gerätewechsel ist das Backup aber oft älter als die App.
 *
 * Richtig ist: ältere Backups sind erlaubt — Room migriert sie beim
 * nächsten Start über die vorhandene Migrationskette. Neuere Backups
 * (App zu alt) müssen abgelehnt werden, weil die ältere App die neuen
 * Tabellen/Spalten nicht kennt. 0/negativ = keine gültige Room-DB.
 */
object BackupVersionPolicy {

    enum class Decision {
        /** Backup ist älter — einspielen und beim Start migrieren. */
        OLDER_OK,

        /** Gleiche Schema-Version — direkt einspielen. */
        SAME,

        /** Backup stammt aus einer neueren App — ablehnen. */
        NEWER,

        /** Keine gültige Room-Datenbank. */
        INVALID
    }

    fun decide(backupVersion: Int, currentVersion: Int): Decision = when {
        backupVersion <= 0 -> Decision.INVALID
        backupVersion > currentVersion -> Decision.NEWER
        backupVersion < currentVersion -> Decision.OLDER_OK
        else -> Decision.SAME
    }

    fun canRestore(backupVersion: Int, currentVersion: Int): Boolean =
        decide(backupVersion, currentVersion) in setOf(Decision.OLDER_OK, Decision.SAME)
}
