package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.Category
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun getAll(): Flow<List<Category>>
    fun getById(id: String): Flow<Category?>
    suspend fun insert(category: Category)
    suspend fun insertAll(categories: List<Category>)
    suspend fun update(category: Category)
    suspend fun delete(id: String)
}