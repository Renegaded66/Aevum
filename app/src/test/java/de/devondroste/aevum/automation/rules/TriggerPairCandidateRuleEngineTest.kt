package de.devondroste.aevum.automation.rules

import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TriggerPairCandidateRuleEngineTest {

    private lateinit var engine: TriggerPairCandidateRuleEngine

    private val homeGeofence = PlaceGeofence(
        id = "home", name = "Zuhause", latitude = 51.6, longitude = 7.5,
        radiusMeters = 200f, icon = "🏠", color = "#6366F1", enabled = true,
        createdAt = 0L, updatedAt = 0L
    )
    private val workGeofence = PlaceGeofence(
        id = "work", name = "Rewe Büro", latitude = 51.5, longitude = 7.4,
        radiusMeters = 300f, icon = "💼", color = "#F59E0B", enabled = true,
        createdAt = 0L, updatedAt = 0L
    )
    private val gymGeofence = PlaceGeofence(
        id = "gym", name = "Fitnessstudio", latitude = 51.61, longitude = 7.51,
        radiusMeters = 150f, icon = "🏋️", color = "#10B981", enabled = true,
        createdAt = 0L, updatedAt = 0L
    )
    private val supermarketGeofence = PlaceGeofence(
        id = "shop", name = "Edeka Markt", latitude = 51.62, longitude = 7.52,
        radiusMeters = 100f, icon = "🛒", color = "#EC4899", enabled = true,
        createdAt = 0L, updatedAt = 0L
    )

    @Before
    fun setUp() {
        engine = TriggerPairCandidateRuleEngine()
    }

    @Test
    fun `home exit then work enter produces Arbeitsweg with high confidence`() {
        val triggers = listOf(
            trigger("t1", "HOME_LEFT", "home", 1_000_000L),
            trigger("t2", "WORK_ENTERED", "work", 1_800_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence, workGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals("Arbeitsweg", c.suggestedTitle)
        assertEquals("transport", c.suggestedCategoryId)
        assertEquals(0.85f, c.confidence)
        assertEquals(1_000_000L, c.startAt)
        assertEquals(1_800_000L, c.endAt)
    }

    @Test
    fun `work exit then home enter produces Heimweg with high confidence`() {
        val triggers = listOf(
            trigger("t1", "WORK_LEFT", "work", 5_000_000L),
            trigger("t2", "HOME_ARRIVED", "home", 5_900_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence, workGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals("Heimweg", c.suggestedTitle)
        assertEquals("transport", c.suggestedCategoryId)
        assertEquals(0.85f, c.confidence)
    }

    @Test
    fun `home exit then gym enter produces Anfahrt Fitness`() {
        val triggers = listOf(
            trigger("t1", "HOME_LEFT", "home", 2_000_000L),
            trigger("t2", "CUSTOM_PLACE_ENTERED", "gym", 2_500_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence, gymGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals("Anfahrt: Fitnessstudio", c.suggestedTitle)
        assertEquals(0.78f, c.confidence)
    }

    @Test
    fun `home exit then supermarket enter produces Einkauf with place name`() {
        val triggers = listOf(
            trigger("t1", "HOME_LEFT", "home", 3_000_000L),
            trigger("t2", "CUSTOM_PLACE_ENTERED", "shop", 3_400_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence, supermarketGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals("Einkauf: Edeka Markt", c.suggestedTitle)
        assertEquals("household", c.suggestedCategoryId)
        assertEquals(0.72f, c.confidence)
    }

    @Test
    fun `gym stay enter then exit produces Fitness with high confidence`() {
        val triggers = listOf(
            trigger("t1", "CUSTOM_PLACE_ENTERED", "gym", 4_000_000L),
            trigger("t2", "CUSTOM_PLACE_LEFT", "gym", 7_600_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(gymGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals("Fitnessstudio", c.suggestedTitle)
        assertEquals("sport", c.suggestedCategoryId)
        assertEquals(0.90f, c.confidence)
    }

    @Test
    fun `work stay enter then exit produces Arbeit`() {
        val triggers = listOf(
            trigger("t1", "WORK_ENTERED", "work", 4_000_000L),
            trigger("t2", "WORK_LEFT", "work", 14_000_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(workGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals("Arbeit", c.suggestedTitle)
        assertEquals("work", c.suggestedCategoryId)
        assertEquals(0.90f, c.confidence)
    }

    @Test
    fun `unmatched exit then enter produces generic transit with lower confidence`() {
        val triggers = listOf(
            trigger("t1", "CUSTOM_PLACE_LEFT", "gym", 5_000_000L),
            trigger("t2", "CUSTOM_PLACE_ENTERED", "shop", 5_600_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(gymGeofence, supermarketGeofence))
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertTrue(c.suggestedTitle.contains("Unterwegs"))
        assertEquals(0.60f, c.confidence)
    }

    @Test
    fun `single trigger produces no candidates`() {
        val triggers = listOf(
            trigger("t1", "HOME_LEFT", "home", 1_000_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence))
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `candidate shorter than MIN_DURATION is filtered out`() {
        val triggers = listOf(
            trigger("t1", "CUSTOM_PLACE_ENTERED", "gym", 4_000_000L),
            trigger("t2", "CUSTOM_PLACE_LEFT", "gym", 4_000_001L) // 1ms = too short
        )
        val candidates = engine.evaluate(triggers, listOf(gymGeofence))
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `back-to-back pairs each produce separate candidates`() {
        val triggers = listOf(
            trigger("t1", "HOME_LEFT", "home", 1_000_000L),
            trigger("t2", "WORK_ENTERED", "work", 1_800_000L),
            trigger("t3", "WORK_LEFT", "work", 10_000_000L),
            trigger("t4", "HOME_ARRIVED", "home", 10_900_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence, workGeofence))
        // t1→t2 = Arbeitsweg, t2→t3 = Arbeit stay, t3→t4 = Heimweg
        assertEquals(3, candidates.size)
        assertEquals("Arbeitsweg", candidates[0].suggestedTitle)
        assertEquals("Arbeit", candidates[1].suggestedTitle)
        assertEquals("Heimweg", candidates[2].suggestedTitle)
    }

    @Test
    fun `Rewe Frischezentrum is treated as work`() {
        val rewe = PlaceGeofence(
            id = "rewe", name = "Rewe Frischezentrum", latitude = 51.5, longitude = 7.4,
            radiusMeters = 300f, icon = "💼", color = "#F59E0B", enabled = true,
            createdAt = 0L, updatedAt = 0L
        )
        val triggers = listOf(
            trigger("t1", "HOME_LEFT", "home", 1_000_000L),
            trigger("t2", "CUSTOM_PLACE_ENTERED", "rewe", 1_800_000L)
        )
        val candidates = engine.evaluate(triggers, listOf(homeGeofence, rewe))
        assertEquals(1, candidates.size)
        assertEquals("Arbeitsweg", candidates[0].suggestedTitle)
    }

    private fun trigger(id: String, type: String, geofenceId: String, occurredAt: Long) =
        TriggerEvent(
            id = id,
            occurredAt = occurredAt,
            type = type,
            source = AutomationConstants.DATA_SOURCE_GEOFENCING,
            geofenceId = geofenceId,
            confidence = 1.0f,
            createdAt = 0L
        )
}
