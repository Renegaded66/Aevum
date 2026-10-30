package com.d_drostes_apps.aevum.ui.screens.activitytypes

import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * M33: Der Anlegen-Screen ist der neue Pfad für eigene Aktivitäten. Der Test
 * prüft die Nahtstelle, die der Screen tatsächlich benutzt: aus Name, Icon,
 * Farbe, Kategorie und Favorit muss GENAU ein ActivityType mit allen Feldern
 * entstehen — und der Screen erst danach als gespeichert gelten.
 */
class ActivityCreateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class CapturingTypeRepository : ActivityTypeRepository {
        val inserted = mutableListOf<ActivityType>()
        override fun getById(id: String): Flow<ActivityType?> = flowOf(null)
        override fun getSystemTypes(): Flow<List<ActivityType>> = flowOf(emptyList())
        override fun getAll(): Flow<List<ActivityType>> = flowOf(inserted.toList())
        override fun getFavorites(): Flow<List<ActivityType>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun setPositivityScore(id: String, score: Int) = Unit
        override suspend fun setIcon(id: String, icon: String) = Unit
        override suspend fun setColor(id: String, color: Long) = Unit
        override suspend fun setCategory(id: String, categoryId: String?) = Unit
        override suspend fun insert(type: ActivityType) {
            inserted += type
        }
        override suspend fun insertAll(types: List<ActivityType>) {
            inserted += types
        }
        override suspend fun update(type: ActivityType) = Unit
        override suspend fun delete(typeId: String) = Unit
    }

    private class FakeCategoryRepository(
        private val categories: List<Category> = emptyList(),
    ) : CategoryRepository {
        override fun getAll(): Flow<List<Category>> = flowOf(categories)
        override fun getById(id: String): Flow<Category?> =
            flowOf(categories.firstOrNull { it.id == id })
        override suspend fun insert(category: Category) = Unit
        override suspend fun insertAll(categories: List<Category>) = Unit
        override suspend fun update(category: Category) = Unit
        override suspend fun delete(id: String) = Unit
    }

    @Test
    fun `create speichert alle Felder und meldet erst dann Erfolg`() = runTest(dispatcher) {
        val types = CapturingTypeRepository()
        val categories = FakeCategoryRepository(
            listOf(Category("sport", "Sport", "#22C55E", "◆", true, 30))
        )
        val viewModel = ActivityCreateViewModel(types, categories)
        var done = false

        viewModel.create(
            name = "  Gitarre  ",
            icon = "🎸",
            color = 0xFF8D6E63,
            categoryId = "sport",
            favorite = true,
            onDone = { done = true },
        )
        advanceUntilIdle()

        assertThat(done).isTrue()
        assertThat(types.inserted).hasSize(1)
        val created = types.inserted.single()
        assertThat(created.name).isEqualTo("Gitarre")
        assertThat(created.icon).isEqualTo("🎸")
        assertThat(created.color).isEqualTo(0xFF8D6E63)
        assertThat(created.defaultCategoryId).isEqualTo("sport")
        assertThat(created.isFavorite).isTrue()
        assertThat(created.isSystem).isFalse()
        assertThat(created.id).startsWith("custom_")
    }

    @Test
    fun `leerer Name legt nichts an und meldet keinen Erfolg`() = runTest(dispatcher) {
        val types = CapturingTypeRepository()
        val viewModel = ActivityCreateViewModel(types, FakeCategoryRepository())
        var done = false

        viewModel.create("   ", "✨", 0L, null, false) { done = true }
        advanceUntilIdle()

        assertThat(done).isFalse()
        assertThat(types.inserted).isEmpty()
    }
}
