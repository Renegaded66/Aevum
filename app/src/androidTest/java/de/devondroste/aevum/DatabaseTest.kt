package de.devondroste.aevum

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.devondroste.aevum.data.db.AppDatabase
import de.devondroste.aevum.data.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun database_created_with_all_daos() {
        assertNotNull(db)
        assertNotNull(db.lifeProfileDao())
        assertNotNull(db.categoryDao())
        assertNotNull(db.tagDao())
        assertNotNull(db.activitySessionDao())
        assertNotNull(db.activityCandidateDao())
        assertNotNull(db.activityTypeDao())
        assertNotNull(db.rawSourceEventDao())
        assertNotNull(db.detectionEventDao())
        assertNotNull(db.dataSourceDao())
        assertNotNull(db.goalDao())
        assertNotNull(db.habitDao())
        assertNotNull(db.habitLogDao())
        assertNotNull(db.activitySessionChangeDao())
        assertNotNull(db.sessionEvidenceDao())
        assertNotNull(db.activityAggregateDayDao())
    }

    @Test
    fun life_profile_insert_round_trips() = runBlocking {
        val profile = LifeProfile(
            id = "default",
            birthDate = "1990-01-01",
            lifeExpectancyYears = 80
        )

        db.lifeProfileDao().insert(profile)
        val result = db.lifeProfileDao().getDefault().first()

        assertNotNull(result)
        assertEquals("default", result?.id)
        assertEquals("1990-01-01", result?.birthDate)
        assertEquals(80, result?.lifeExpectancyYears)
    }

    @Test
    fun activity_session_crud_with_new_fields() = runBlocking {
        val session = ActivitySession(
            id = "session-1",
            title = "Work Session",
            categoryId = "work",
            activityTypeId = "work",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
            timezoneId = "Europe/Berlin",
            description = "Deep work",
            sourceType = "MANUAL",
            createdBy = "MANUAL",
            confidence = 1.0f,
            isUserEdited = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            revision = 1,
            originDeviceId = "device-1"
        )

        db.activitySessionDao().insert(session)
        val result = db.activitySessionDao().getById("session-1").first()

        assertNotNull(result)
        assertEquals("session-1", result?.id)
        assertEquals("Work Session", result?.title)
        assertEquals("work", result?.categoryId)
        assertEquals("work", result?.activityTypeId)
        assertEquals("Europe/Berlin", result?.timezoneId)
        assertEquals("Deep work", result?.description)
        assertEquals("MANUAL", result?.sourceType)
        assertEquals("MANUAL", result?.createdBy)
        assertEquals(1.0f, result!!.confidence, 0.001f)
        assertEquals(false, result.isUserEdited)
        assertEquals(1, result?.revision)
        assertEquals("device-1", result?.originDeviceId)
        assertNull(result?.deletedAt)
    }

    @Test
    fun activity_candidate_crud() = runBlocking {
        val candidate = ActivityCandidate(
            id = "candidate-1",
            suggestedTitle = "Arbeit",
            suggestedCategoryId = "work",
            activityTypeId = "work",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
            confidence = 0.9f,
            status = "PENDING",
            reason = "Geofence + Calendar",
            createdBy = "AUTO",
            createdAt = System.currentTimeMillis()
        )

        db.activityCandidateDao().insert(candidate)
        val result = db.activityCandidateDao().getById("candidate-1").first()

        assertNotNull(result)
        assertEquals("candidate-1", result?.id)
        assertEquals("Arbeit", result?.suggestedTitle)
        assertEquals("work", result?.suggestedCategoryId)
        assertEquals("work", result?.activityTypeId)
        assertEquals(0.9f, result!!.confidence, 0.001f)
        assertEquals("PENDING", result?.status)
        assertEquals("AUTO", result?.createdBy)
    }

    @Test
    fun activity_type_crud() = runBlocking {
        val type = ActivityType(
            id = "custom-type",
            name = "Custom Activity",
            defaultCategoryId = "custom",
            isSystem = false,
            propertiesJson = "{\"icon\": \"custom\"}"
        )

        db.activityTypeDao().insert(type)
        val result = db.activityTypeDao().getById("custom-type").first()

        assertNotNull(result)
        assertEquals("custom-type", result?.id)
        assertEquals("Custom Activity", result?.name)
        assertEquals("custom", result?.defaultCategoryId)
        assertEquals(false, result?.isSystem)
        assertEquals("{\"icon\": \"custom\"}", result?.propertiesJson)
    }

    @Test
    fun data_source_crud() = runBlocking {
        val source = DataSource(
            id = "test-source",
            type = "ANDROID_API",
            name = "Test Source",
            enabled = true,
            permissionState = "GRANTED",
            lastSyncAt = System.currentTimeMillis(),
            configJson = "{}",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        db.dataSourceDao().insert(source)
        val result = db.dataSourceDao().getById("test-source").first()

        assertNotNull(result)
        assertEquals("test-source", result?.id)
        assertEquals("ANDROID_API", result?.type)
        assertEquals("Test Source", result?.name)
        assertEquals(true, result?.enabled)
        assertEquals("GRANTED", result?.permissionState)
    }

    @Test
    fun raw_source_event_crud() = runBlocking {
        val source = DataSource(
            id = "test-source-2",
            type = "ANDROID_API",
            name = "Test Source 2",
            enabled = true,
            permissionState = "GRANTED",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.dataSourceDao().insert(source)

        val event = RawSourceEvent(
            id = "raw-event-1",
            sourceId = "test-source-2",
            externalId = "ext-123",
            eventType = "GEOFENCE_ENTER",
            observedAt = System.currentTimeMillis(),
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 60000,
            timezoneId = "UTC",
            payloadJson = "{\"geofence_id\": \"work\"}",
            schemaVersion = 1,
            ingestedAt = System.currentTimeMillis()
        )

        db.rawSourceEventDao().insert(event)
        val result = db.rawSourceEventDao().getBySourceAndExternalId("test-source-2", "ext-123").first()

        assertNotNull(result)
        assertEquals("raw-event-1", result?.id)
        assertEquals("test-source-2", result?.sourceId)
        assertEquals("ext-123", result?.externalId)
        assertEquals("GEOFENCE_ENTER", result?.eventType)
    }

    @Test
    fun detection_event_crud() = runBlocking {
        val source = DataSource(
            id = "det-source",
            type = "ANDROID_API",
            name = "Detection Source",
            enabled = true,
            permissionState = "GRANTED",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.dataSourceDao().insert(source)

        val rawEvent = RawSourceEvent(
            id = "raw-det-1",
            sourceId = "det-source",
            externalId = null,
            eventType = "ACTIVITY_RECOGNITION",
            observedAt = System.currentTimeMillis(),
            payloadJson = "{}",
            schemaVersion = 1,
            ingestedAt = System.currentTimeMillis()
        )
        db.rawSourceEventDao().insert(rawEvent)

        val detection = DetectionEvent(
            id = "det-1",
            rawEventId = "raw-det-1",
            sourceId = "det-source",
            kind = "ACTIVITY_IN_VEHICLE",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 30 * 60 * 1000,
            confidence = 0.85f,
            metadataJson = "{\"confidence\": 0.85}",
            createdAt = System.currentTimeMillis()
        )

        db.detectionEventDao().insert(detection)
        val resultList = db.detectionEventDao().getByRawEventId("raw-det-1").first()

        assertNotNull(resultList)
        assertTrue(resultList.isNotEmpty())
        val result = resultList.first()

        assertEquals("det-1", result.id)
        assertEquals("ACTIVITY_IN_VEHICLE", result.kind)
        assertEquals(0.85f, result.confidence, 0.001f)
    }

    @Test
    fun activity_session_change_crud() = runBlocking {
        val session = ActivitySession(
            id = "session-change-1",
            title = "Work",
            categoryId = "work",
            activityTypeId = "work",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
            sourceType = "MANUAL",
            createdBy = "MANUAL",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.activitySessionDao().insert(session)

        val change = ActivitySessionChange(
            id = "change-1",
            sessionId = "session-change-1",
            changeType = "USER_EDIT",
            changedBy = "USER",
            changedAt = System.currentTimeMillis(),
            beforeJson = "{\"startAt\": 1000, \"endAt\": 2000}",
            afterJson = "{\"startAt\": 1100, \"endAt\": 1900}",
            reason = "Corrected start time",
            sourceCandidateId = null
        )

        db.activitySessionChangeDao().insert(change)
        val resultList = db.activitySessionChangeDao().getBySessionId("session-change-1").first()

        assertNotNull(resultList)
        assertTrue(resultList.isNotEmpty())
        val result = resultList.first()

        assertEquals("change-1", result.id)
        assertEquals("session-change-1", result.sessionId)
        assertEquals("USER_EDIT", result.changeType)
        assertEquals("USER", result.changedBy)
    }

    @Test
    fun session_evidence_crud() = runBlocking {
        val source = DataSource(
            id = "ev-source",
            type = "ANDROID_API",
            name = "Evidence Source",
            enabled = true,
            permissionState = "GRANTED",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.dataSourceDao().insert(source)

        val raw = RawSourceEvent(
            id = "raw-ev-1",
            sourceId = "ev-source",
            eventType = "GEOFENCE_ENTER",
            observedAt = System.currentTimeMillis(),
            payloadJson = "{}",
            schemaVersion = 1,
            ingestedAt = System.currentTimeMillis()
        )
        db.rawSourceEventDao().insert(raw)

        val detection = DetectionEvent(
            id = "det-ev-1",
            rawEventId = "raw-ev-1",
            sourceId = "ev-source",
            kind = "GEOFENCE_ENTER",
            startAt = System.currentTimeMillis(),
            confidence = 0.9f,
            createdAt = System.currentTimeMillis()
        )
        db.detectionEventDao().insert(detection)

        val candidate = ActivityCandidate(
            id = "cand-ev-1",
            suggestedTitle = "Work",
            suggestedCategoryId = "work",
            activityTypeId = "work",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
            confidence = 0.9f,
            status = "PENDING",
            createdBy = "AUTO",
            createdAt = System.currentTimeMillis()
        )
        db.activityCandidateDao().insert(candidate)

        val session = ActivitySession(
            id = "session-ev-1",
            title = "Work",
            categoryId = "work",
            activityTypeId = "work",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
            sourceType = "AUTO",
            createdBy = "AUTO",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.activitySessionDao().insert(session)

        val evidence = SessionEvidence(
            id = "ev-1",
            sessionId = "session-ev-1",
            candidateId = "cand-ev-1",
            detectionEventId = "det-ev-1",
            weight = 0.9f,
            relationship = "SUPPORTS",
            reason = "Geofence enter at workplace"
        )

        db.sessionEvidenceDao().insert(evidence)
        val resultList = db.sessionEvidenceDao().getBySessionId("session-ev-1").first()

        assertNotNull(resultList)
        assertTrue(resultList.isNotEmpty())
        val result = resultList.first()

        assertEquals("ev-1", result.id)
        assertEquals("session-ev-1", result.sessionId)
        assertEquals("cand-ev-1", result.candidateId)
        assertEquals("det-ev-1", result.detectionEventId)
        assertEquals(0.9f, result.weight, 0.001f)
        assertEquals("SUPPORTS", result.relationship)
    }

    @Test
    fun activity_aggregate_day_crud() = runBlocking {
        val aggregate = ActivityAggregateDay(
            date = "2025-01-15",
            timezoneId = "Europe/Berlin",
            categoryId = "work",
            activityTypeId = "work",
            tagId = "deep-work",
            durationMs = 8 * 60 * 60 * 1000L,
            sessionCount = 1,
            updatedAt = System.currentTimeMillis()
        )

        db.activityAggregateDayDao().insert(aggregate)
        val result = db.activityAggregateDayDao().getByDate("2025-01-15", "Europe/Berlin").first()

        assertNotNull(result)

        assertEquals("2025-01-15", result!!.date)
        assertEquals("Europe/Berlin", result.timezoneId)
        assertEquals("work", result.categoryId)
        assertEquals("work", result.activityTypeId)
        assertEquals("deep-work", result.tagId)
        assertEquals(8 * 60 * 60 * 1000L, result.durationMs)
        assertEquals(1, result.sessionCount)
    }

    @Test
    fun goal_crud_with_new_fields() = runBlocking {
        val goal = Goal(
            id = "goal-1",
            title = "8h Deep Work",
            categoryId = "learning",
            tagId = "deep-work",
            activityTypeId = "learning",
            type = "DURATION",
            period = "DAILY",
            targetValue = 480f,
            targetUnit = "MINUTES",
            filterJson = "{\"tags\": [\"deep-work\"]}",
            startAt = System.currentTimeMillis(),
            endAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000,
            status = "ACTIVE"
        )

        db.goalDao().insert(goal)
        val result = db.goalDao().getById("goal-1").first()

        assertNotNull(result)
        assertEquals("goal-1", result?.id)
        assertEquals("8h Deep Work", result?.title)
        assertEquals("learning", result?.categoryId)
        assertEquals("deep-work", result?.tagId)
        assertEquals("learning", result?.activityTypeId)
        assertEquals("DURATION", result?.type)
        assertEquals("DAILY", result?.period)
        assertEquals(480f, result!!.targetValue, 0.001f)
        assertEquals("MINUTES", result?.targetUnit)
        assertEquals("{\"tags\": [\"deep-work\"]}", result?.filterJson)
        assertEquals("ACTIVE", result?.status)
    }

    @Test
    fun geofence_and_trigger_v3_tables_round_trip() = runBlocking {
        val geofence = PlaceGeofence(
            id = "geo-home",
            name = "Zuhause",
            latitude = 51.616,
            longitude = 7.52,
            radiusMeters = 150f,
            icon = "🏠",
            color = "#22C55E",
            enabled = true,
            activityTypeId = "leisure",
            categoryId = "leisure"
        )

        db.placeGeofenceDao().insertWithTags(geofence, listOf("family"))
        val stored = db.placeGeofenceDao().getById("geo-home").first()

        assertNotNull(stored)
        assertEquals("Zuhause", stored?.name)
        assertEquals("🏠", stored?.icon)
        assertEquals("#22C55E", stored?.color)
        assertEquals("leisure", stored?.activityTypeId)
        assertEquals(listOf("family"), db.placeGeofenceDao().getTagIdsForGeofence("geo-home").first())

        val trigger = TriggerEvent(
            id = "trigger-1",
            occurredAt = 1_000L,
            type = "HOME_LEFT",
            source = "phone_geofencing",
            confidence = 0.82f,
            geofenceId = "geo-home",
            metadataJson = "{\"transition\":\"EXIT\"}"
        )
        db.triggerEventDao().insert(trigger)
        val triggers = db.triggerEventDao().getByGeofenceId("geo-home").first()

        assertEquals(1, triggers.size)
        assertEquals("HOME_LEFT", triggers.first().type)
        assertEquals(0.82f, triggers.first().confidence, 0.001f)
    }
}