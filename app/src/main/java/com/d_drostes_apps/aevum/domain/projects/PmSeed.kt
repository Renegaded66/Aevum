package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType

/**
 * M19.2: Beispielstruktur („Vorlage") auf Knopfdruck.
 *
 * WARUM ES DIESE DATEI GIBT: Der leere Zustand eines Projektbaums ist eine
 * Zumutung — der Nutzer müsste sich erst eine Struktur ausdenken, bevor er
 * die erste Kleinigkeit abhaken darf. EIN Tipp im leeren Zustand legt
 * deshalb einen fertigen Startpunkt an, damit die erste Handlung nicht
 * „organisieren", sondern „abhaken" ist.
 *
 * NEU IN M19.2 (Nutzer-Entscheidung, wörtlich): „deine Beispielstruktur ist
 * exakt mein Beispiel gewesen. […] die App kommt in den Play Store, da
 * möchte ich das nicht so haben, denke dir eine Struktur aus die auf die
 * meisten Menschen möglicherweise zutrifft." Deshalb steht hier KEINE
 * persönliche Studien-Struktur mehr, sondern eine Vorlage aus den vier
 * Lebensbereichen, die fast jeden betreffen:
 *
 *   Haushalt & Alltag · Arbeit & Ausbildung · Gesundheit · Finanzen
 *
 * Je Bereich 1–2 Unterprojekte und ein paar glaubwürdige Aufgaben, von denen
 * wenige für HEUTE eingeplant sind. Dadurch sieht der Nutzer am ersten Tag
 * sofort Tagesplan UND Backlog-Regel — ohne dass die Vorlage nach einem
 * fremden Leben aussieht.
 *
 * TITEL SIND LOKALISIERT: Die Titel kommen NICHT aus dieser Datei, sondern
 * über [titleOf] (im echten Betrieb aus den String-Ressourcen — Deutsch in
 * values-de/, Englisch als Standard). [DEFAULT_TITLES] ist nur der reine
 * Fallback für Domain-Tests.
 *
 * EIGENSCHAFTEN (und warum):
 *  - **Feste IDs** ([PREFIX] + Schlüssel): zweimal tippen erzeugt KEINE
 *    Dubletten (Room schreibt mit REPLACE). Nichts ist schlimmer als ein
 *    doppelter Beispielbaum.
 *  - **Sechs Aufgaben sind für heute eingeplant**, der Rest bewusst nicht
 *    (Backlog). Keine Aufgabe ist vorab erledigt → das Glas startet leer
 *    und ehrlich.
 *  - **Bewusst grob gekörnt**: Jede Aufgabe ist an einem Abend schaffbar.
 *    Genau das ist die Anforderung („jede kleinste Aufgabe schriftlich
 *    festhalten und abarbeiten").
 */
object PmSeed {

    const val PREFIX = "pm-seed-"

    fun isSeedNode(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * Englischer Standard-Fallback (entspricht values/). Im Betrieb liefert
     * der Android-Titel-Resolver die zur Gerätesprache passende Fassung.
     */
    val DEFAULT_TITLES: Map<String, String> = mapOf(
        // Haushalt & Alltag
        "alltag" to "Household & Daily Life",
        "wohnung" to "Home",
        "einkauf" to "Write a shopping list",
        "aufraeumen" to "Tidy up the apartment",
        "waesche" to "Do the laundry",
        "papiere" to "Sort paperwork",
        "termine" to "Plan the week's appointments",
        // Arbeit & Ausbildung
        "arbeit" to "Work & Education",
        "projekt" to "Current project",
        "schritt" to "Define the next work step",
        "wochenziele" to "Note down weekly goals",
        "offenepunkte" to "Go through open points",
        "ergebnis" to "Write down the result briefly",
        "lernen" to "Further Education",
        "lernziel" to "Set a learning goal for this week",
        "lektion" to "Work through the first lesson",
        // Gesundheit
        "gesundheit" to "Health",
        "sport" to "Schedule sport for this week",
        "spaziergang" to "Go for a walk",
        "vorsorge" to "Book a check-up appointment",
        // Finanzen
        "finanzen" to "Finances",
        "budget" to "Plan the month's budget",
        "rechnungen" to "File invoices",
        "vertraege" to "Review contracts and subscriptions"
    )

    fun defaultTitle(key: String): String = DEFAULT_TITLES[key] ?: key

    /**
     * Die komplette Vorlage. Rein: alles wird aus [now] und dem heutigen
     * Datum abgeleitet, damit Tests reproduzierbar sind.
     */
    fun example(
        now: Long = System.currentTimeMillis(),
        todayIso: String = PmToday.todayIso(now),
        titleOf: (String) -> String = ::defaultTitle
    ): List<PmNode> {
        val nodes = mutableListOf<PmNode>()
        var order = 0
        fun add(
            id: String,
            parent: String?,
            type: String,
            titleKey: String,
            color: String? = null,
            scheduled: String? = null,
            due: String? = null
        ) {
            nodes.add(
                PmNode(
                    id = id,
                    parentId = parent,
                    type = type,
                    title = titleOf(titleKey),
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

        // ── Haushalt & Alltag ─────────────────────────────────────────────
        add("${PREFIX}alltag", null, PmType.PROJECT, "alltag", color = "#38BDF8")
        add("${PREFIX}wohnung", "${PREFIX}alltag", PmType.PROJECT, "wohnung")
        add("${PREFIX}einkauf", "${PREFIX}wohnung", PmType.TASK, "einkauf", scheduled = todayIso)
        add("${PREFIX}aufraeumen", "${PREFIX}wohnung", PmType.TASK, "aufraeumen")
        add("${PREFIX}waesche", "${PREFIX}wohnung", PmType.TASK, "waesche")
        add("${PREFIX}papiere", "${PREFIX}wohnung", PmType.TASK, "papiere")
        add("${PREFIX}termine", "${PREFIX}alltag", PmType.TASK, "termine", scheduled = todayIso)

        // ── Arbeit & Ausbildung ────────────────────────────────────────────
        add("${PREFIX}arbeit", null, PmType.PROJECT, "arbeit", color = "#F87171")
        add("${PREFIX}projekt", "${PREFIX}arbeit", PmType.PROJECT, "projekt")
        add("${PREFIX}schritt", "${PREFIX}projekt", PmType.TASK, "schritt", scheduled = todayIso)
        add("${PREFIX}wochenziele", "${PREFIX}projekt", PmType.TASK, "wochenziele", scheduled = todayIso)
        add("${PREFIX}offenepunkte", "${PREFIX}projekt", PmType.TASK, "offenepunkte")
        add("${PREFIX}ergebnis", "${PREFIX}projekt", PmType.TASK, "ergebnis")
        add("${PREFIX}lernen", "${PREFIX}arbeit", PmType.PROJECT, "lernen")
        add("${PREFIX}lernziel", "${PREFIX}lernen", PmType.TASK, "lernziel")
        add("${PREFIX}lektion", "${PREFIX}lernen", PmType.TASK, "lektion")

        // ── Gesundheit ─────────────────────────────────────────────────────
        add("${PREFIX}gesundheit", null, PmType.PROJECT, "gesundheit", color = "#34D399")
        add("${PREFIX}sport", "${PREFIX}gesundheit", PmType.TASK, "sport", scheduled = todayIso)
        add("${PREFIX}spaziergang", "${PREFIX}gesundheit", PmType.TASK, "spaziergang")
        add("${PREFIX}vorsorge", "${PREFIX}gesundheit", PmType.TASK, "vorsorge")

        // ── Finanzen ───────────────────────────────────────────────────────
        add("${PREFIX}finanzen", null, PmType.PROJECT, "finanzen", color = "#FBBF24")
        add("${PREFIX}budget", "${PREFIX}finanzen", PmType.TASK, "budget", scheduled = todayIso)
        add("${PREFIX}rechnungen", "${PREFIX}finanzen", PmType.TASK, "rechnungen")
        add("${PREFIX}vertraege", "${PREFIX}finanzen", PmType.TASK, "vertraege")

        return nodes
    }
}
