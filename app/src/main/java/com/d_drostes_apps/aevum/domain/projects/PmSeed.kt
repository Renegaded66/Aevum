package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType

/**
 * M19.1: Beispielstruktur auf Knopfdruck.
 *
 * WARUM ES DIESE DATEI GIBT: Der leere Zustand eines Projektbaums ist eine
 * Zumutung — der Nutzer müsste fünf Ebenen selbst erfinden, bevor er die
 * erste Kleinigkeit abhaken darf. Genau daran scheitert Fokussierung.
 * Deshalb: EIN Tipp im leeren Zustand legt exakt die Struktur an, die im
 * Anwendungsfall beschrieben wurde (Studium → 2. Semester → Projektgruppe →
 * Präsentation → kleinste Schritte), damit die erste Handlung nicht
 * „organisieren", sondern „abhaken" ist.
 *
 * EIGENSCHAFTEN (und warum):
 *  - **Feste IDs** ([PREFIX] + laufende Nummer): zweimal tippen erzeugt KEINE
 *    Dubletten (Room schreibt mit REPLACE). Nichts ist schlimmer als ein
 *    doppelter Beispielbaum.
 *  - **10 Todos sind für heute eingeplant**, 13 bewusst nicht. Der Nutzer
 *    sieht also am ersten Tag sofort einen Tagesplan UND die Backlog-Regel
 *    („Zukünftige müssen nicht zwingend mit Datum versehen sein") — inklusive
 *    Karussell mit mehr als einer Seite, sobald er eine zweite Aufgabe
 *    dazuholt.
 *  - **Bewusst grob gekörnt**: die Schritte sind so klein, dass jeder einzelne
 *    an einem Abend schaffbar ist. Genau das ist die Anforderung.
 *  - Kein Beispiel-Todo ist vorab erledigt → das Glas startet leer und ehrlich.
 */
object PmSeed {

    const val PREFIX = "pm-seed-"

    fun isSeedNode(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * Die komplette Beispielstruktur. Rein: alles wird aus [now] und dem
     * heutigen Datum abgeleitet, damit Tests reproduzierbar sind.
     */
    fun example(now: Long = System.currentTimeMillis(), todayIso: String = PmToday.todayIso(now)): List<PmNode> {
        val nodes = mutableListOf<PmNode>()
        var order = 0
        fun add(
            id: String,
            parent: String?,
            type: String,
            title: String,
            note: String = "",
            color: String? = null,
            scheduled: String? = null,
            due: String? = null
        ) {
            nodes.add(
                PmNode(
                    id = id,
                    parentId = parent,
                    type = type,
                    title = title,
                    note = note,
                    color = color,
                    sortOrder = order++,
                    scheduledDate = scheduled,
                    dueDate = due,
                    doneAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        val inTenDays = java.time.LocalDate.parse(todayIso).plusDays(10).toString()

        // ── Studium ───────────────────────────────────────────────────────
        add("${PREFIX}studium", null, PmType.PROJECT, "Studium",
            note = "Alles, was mit der Uni zu tun hat.", color = "#8B7CFF")
        add("${PREFIX}semester", "${PREFIX}studium", PmType.PROJECT, "2. Semester",
            note = "Laufendes Semester.")
        add("${PREFIX}projektgruppe", "${PREFIX}semester", PmType.CATEGORY, "Projektgruppe")

        add("${PREFIX}praesentation", "${PREFIX}projektgruppe", PmType.TASK, "Präsentation",
            note = "Ziel ist nicht perfekt, sondern FERTIG.", color = "#FBBF24", due = inTenDays)

        // Heute eingeplant — die erste Hälfte des Weges (verstehen + entwerfen).
        add("${PREFIX}t01", "${PREFIX}praesentation", PmType.TODO, "Deckblatt und Gliederung entwerfen", scheduled = todayIso)
        add("${PREFIX}t02", "${PREFIX}praesentation", PmType.TODO, "Erste Hälfte der Quelle lesen", scheduled = todayIso)
        add("${PREFIX}t03", "${PREFIX}praesentation", PmType.TODO, "Zweite Hälfte der Quelle lesen", scheduled = todayIso)
        add("${PREFIX}t04", "${PREFIX}praesentation", PmType.TODO, "Themen für die Abschnitte sammeln", scheduled = todayIso)
        add("${PREFIX}t05", "${PREFIX}praesentation", PmType.TODO, "Thema 1 recherchieren", scheduled = todayIso)
        add("${PREFIX}t06", "${PREFIX}praesentation", PmType.TODO, "Thema 2 recherchieren", scheduled = todayIso)
        add("${PREFIX}t07", "${PREFIX}praesentation", PmType.TODO, "Stichpunkte ordnen", scheduled = todayIso)
        add("${PREFIX}t08", "${PREFIX}praesentation", PmType.TODO, "Rohentwurf aufschreiben", scheduled = todayIso)
        add("${PREFIX}t09", "${PREFIX}praesentation", PmType.TODO, "Entwurf kürzen", scheduled = todayIso)
        add("${PREFIX}t10", "${PREFIX}praesentation", PmType.TODO, "Einleitung und Schluss formulieren", scheduled = todayIso)

        // Bewusst OHNE Datum: Backlog (später planen, wenn es so weit ist).
        add("${PREFIX}t11", "${PREFIX}praesentation", PmType.TODO, "Folien bauen")
        add("${PREFIX}t12", "${PREFIX}praesentation", PmType.TODO, "Bilder und Grafiken einfügen")
        add("${PREFIX}t13", "${PREFIX}praesentation", PmType.TODO, "Quellenverzeichnis anlegen")
        add("${PREFIX}t14", "${PREFIX}praesentation", PmType.TODO, "Probevortrag halten")
        add("${PREFIX}t15", "${PREFIX}praesentation", PmType.TODO, "Rechtschreibung und Format prüfen")

        add("${PREFIX}bericht", "${PREFIX}projektgruppe", PmType.TASK, "Projektbericht",
            note = "Parallel zur Präsentation.", color = "#38BDF8")
        add("${PREFIX}b01", "${PREFIX}bericht", PmType.TODO, "Gliederung mit dem Betreuer abgleichen")
        add("${PREFIX}b02", "${PREFIX}bericht", PmType.TODO, "Einleitung schreiben")
        add("${PREFIX}b03", "${PREFIX}bericht", PmType.TODO, "Methodik beschreiben")
        add("${PREFIX}b04", "${PREFIX}bericht", PmType.TODO, "Auswertung und Diagramme erstellen")

        // ── Privat ────────────────────────────────────────────────────────
        add("${PREFIX}privat", null, PmType.PROJECT, "Privat", color = "#2DD4BF")
        add("${PREFIX}wohnung", "${PREFIX}privat", PmType.CATEGORY, "Wohnung")
        add("${PREFIX}ordnung", "${PREFIX}wohnung", PmType.TASK, "Ausmisten und Ordnung", color = "#34D399")
        add("${PREFIX}o01", "${PREFIX}ordnung", PmType.TODO, "Schreibtisch aufräumen")
        add("${PREFIX}o02", "${PREFIX}ordnung", PmType.TODO, "Kleiderschrank ausmisten")
        add("${PREFIX}o03", "${PREFIX}ordnung", PmType.TODO, "Papiere sortieren")
        add("${PREFIX}o04", "${PREFIX}ordnung", PmType.TODO, "Alte Kabel aussortieren")

        return nodes
    }
}
