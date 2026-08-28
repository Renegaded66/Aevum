package com.d_drostes_apps.aevum.automation.rules

import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CandidateMergeEngineTest {

    private lateinit var engine: CandidateMergeEngine

    @Before
    fun setUp() {
        engine = CandidateMergeEngine(RuleStrings(null))
    }

    @Test
    fun `adjacent same-category candidates with small gap are merged`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L),
            candidate("2", "Fahrt", "transport", 1_230_000L, 1_500_000L) // 30s gap
        )
        val merged = engine.merge(candidates)
        assertEquals(1, merged.size)
        assertEquals("merged_1_2", merged[0].id)
        assertEquals(1_000_000L, merged[0].startAt)
        assertEquals(1_500_000L, merged[0].endAt)
    }

    @Test
    fun `candidates with different categories are not merged`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L),
            candidate("2", "Arbeit", "work", 1_230_000L, 1_500_000L)
        )
        val merged = engine.merge(candidates)
        assertEquals(2, merged.size)
    }

    @Test
    fun `candidates with gap larger than 5min are not merged`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L),
            candidate("2", "Fahrt", "transport", 1_600_000L, 1_800_000L) // 6m40s gap
        )
        val merged = engine.merge(candidates)
        assertEquals(2, merged.size)
    }

    @Test
    fun `candidates with total span exceeding 30min are not merged`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L),
            candidate("2", "Fahrt", "transport", 1_230_000L, 2_900_000L) // span = 31m40s
        )
        val merged = engine.merge(candidates)
        assertEquals(2, merged.size)
    }

    @Test
    fun `three fragments become one merged candidate`() {
        val candidates = listOf(
            candidate("a", "Fahrt", "transport", 1_000_000L, 1_200_000L),
            candidate("b", "Fahrt", "transport", 1_210_000L, 1_400_000L),
            candidate("c", "Fahrt", "transport", 1_410_000L, 1_500_000L)
        )
        val merged = engine.merge(candidates)
        assertEquals(1, merged.size)
        assertEquals(1_000_000L, merged[0].startAt)
        assertEquals(1_500_000L, merged[0].endAt)
    }

    @Test
    fun `confidence is averaged across merged pair`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L, confidence = 0.8f),
            candidate("2", "Fahrt", "transport", 1_230_000L, 1_500_000L, confidence = 0.6f)
        )
        val merged = engine.merge(candidates)
        assertEquals(1, merged.size)
        assertEquals(0.7f, merged[0].confidence, 0.001f)
    }

    @Test
    fun `single candidate passes through unchanged`() {
        val candidates = listOf(
            candidate("1", "Arbeit", "work", 1_000_000L, 5_000_000L)
        )
        val merged = engine.merge(candidates)
        assertEquals(1, merged.size)
        assertEquals("1", merged[0].id)
    }

    @Test
    fun `empty list returns empty`() {
        val merged = engine.merge(emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `non-adjacent same-category candidates are not merged across different-category candidate`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L),
            candidate("2", "Arbeit", "work", 1_230_000L, 5_000_000L),
            candidate("3", "Fahrt", "transport", 5_010_000L, 5_200_000L)
        )
        val merged = engine.merge(candidates)
        // 1 and 3 are both transport but separated by work → no merge
        assertEquals(3, merged.size)
    }

    @Test
    fun `merge preserves status`() {
        val candidates = listOf(
            candidate("1", "Fahrt", "transport", 1_000_000L, 1_200_000L, status = "PENDING"),
            candidate("2", "Fahrt", "transport", 1_230_000L, 1_500_000L, status = "PENDING")
        )
        val merged = engine.merge(candidates)
        assertEquals(1, merged.size)
        assertEquals("PENDING", merged[0].status)
    }

    private fun candidate(
        id: String,
        title: String,
        categoryId: String,
        startAt: Long,
        endAt: Long,
        confidence: Float = 0.75f,
        status: String = "PENDING"
    ) = ActivityCandidate(
        id = id,
        suggestedTitle = title,
        suggestedCategoryId = categoryId,
        activityTypeId = categoryId,
        startAt = startAt,
        endAt = endAt,
        confidence = confidence,
        status = status,
        reason = "Test candidate",
        createdBy = "TEST",
        createdAt = startAt,
        sourceCandidateId = id
    )
}
