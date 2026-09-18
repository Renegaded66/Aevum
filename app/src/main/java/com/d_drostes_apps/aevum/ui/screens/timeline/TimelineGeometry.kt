package com.d_drostes_apps.aevum.ui.screens.timeline

/**
 * M18.131: Minuten-Offset ab Tagesbeginn für die Timeline-Geometrie.
 *
 * WARUM NICHT TimeFormatting.minutesOfDay:
 * [com.d_drostes_apps.aevum.domain.time.TimeFormatting.minutesOfDay] liefert
 * die UHRZEIT im Tag (0..1439). Für das auf das Tagesende geclippte Ende
 * einer tagesübergreifenden Session ist das exakt 00:00 des Folgetags →
 * 0. Der Renderer interpretiert `endMinuteOfDay <= 0` als "ungültig" und
 * zeichnet `startMin + 1` — eine tagesübergreifende Aufzeichnung erschien
 * im Starttag deshalb nur als einminütiger Strich am Startzeitpunkt statt
 * als Block bis 24:00.
 *
 * Die Timeline-Geometrie braucht aber KEINE Uhrzeit, sondern einen Offset
 * ab dem linken/relevanten Rand des dargestellten Tages:
 *   00:00 des Tages → 0      23:30 → 1410      24:00 (Tagesende) → 1440
 *
 * Genau diese Semantik nutzen geplante Kalender-Blöcke bereits
 * ([buildPlannedSessionsForDay]), weshalb dort nie ein Strich auftrat.
 *
 * Bewusst eine reine Funktion (kein Android, keine Zone): nur so ist der
 * Mitternachts-Randfall im JVM-Unit-Test abdeckbar. Die Werte werden auf
 * [0, 1440] begrenzt — ein Offset außerhalb kann durch einen Zeitzonen-
 * wechsel während einer laufenden Session entstehen (Sommer-/Winterzeit)
 * und darf die Geometrie nicht sprengen.
 *
 * @param millis Absoluter Zeitpunkt des Blockrands.
 * @param dayStartMs Tagesbeginn (00:00) des dargestellten Tages.
 * @return Minuten seit [dayStartMs], begrenzt auf 0..1440.
 */
internal fun clippedMinuteOffset(millis: Long, dayStartMs: Long): Int =
    ((millis - dayStartMs) / 60_000L).toInt().coerceIn(0, MINUTES_PER_DAY)

/** Minuten eines vollen Tages (24 h) — obere Grenze der Timeline-Geometrie. */
internal const val MINUTES_PER_DAY = 24 * 60
