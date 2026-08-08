package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.Tag
import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun getAll(): Flow<List<Tag>>
    fun getById(id: String): Flow<Tag?>
    suspend fun insert(tag: Tag)
    suspend fun insertAll(tags: List<Tag>)
    suspend fun update(tag: Tag)
    suspend fun delete(id: String)
}