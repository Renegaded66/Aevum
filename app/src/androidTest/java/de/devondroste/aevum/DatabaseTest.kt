package de.devondroste.aevum

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.devondroste.aevum.data.db.AppDatabase
import de.devondroste.aevum.data.model.LifeProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun database_created_with_life_profile_dao() {
        assertNotNull(db)
        assertNotNull(db.lifeProfileDao())
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
    }
}
