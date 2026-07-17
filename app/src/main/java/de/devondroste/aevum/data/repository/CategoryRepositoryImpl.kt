package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.CategoryDao
import de.devondroste.aevum.data.model.Category
import kotlinx.coroutines.flow.Flow

class CategoryRepositoryImpl(
    private val dao: CategoryDao
) : CategoryRepository {

    override fun getAll(): Flow<List<Category>> = dao.getAll()

    override fun getById(id: String): Flow<Category?> = dao.getById(id)

    override suspend fun insert(category: Category) = dao.insert(category)

    override suspend fun insertAll(categories: List<Category>) = dao.insertAll(categories)

    override suspend fun update(category: Category) = dao.update(category)

    override suspend fun delete(id: String) = dao.delete(id)
}