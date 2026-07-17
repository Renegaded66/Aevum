package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.Tag
import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun getAll(): Flow<List<Tag>>
    fun getById(id: String): Flow<Tag?>
    suspend fun insert(tag: Tag)
    suspend fun insertAll(tags: List<Tag>)
    suspend fun update(tag: Tag)
    suspend fun delete(id: String)
}