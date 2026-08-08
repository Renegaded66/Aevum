package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.TagDao
import com.d_drostes_apps.aevum.data.model.Tag
import kotlinx.coroutines.flow.Flow

class TagRepositoryImpl(
    private val dao: TagDao
) : TagRepository {

    override fun getAll(): Flow<List<Tag>> = dao.getAll()

    override fun getById(id: String): Flow<Tag?> = dao.getById(id)

    override suspend fun insert(tag: Tag) = dao.insert(tag)

    override suspend fun insertAll(tags: List<Tag>) = dao.insertAll(tags)

    override suspend fun update(tag: Tag) = dao.update(tag)

    override suspend fun delete(id: String) = dao.delete(id)
}