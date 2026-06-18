package com.phonote.app

import androidx.room.*

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String = "",
    val folderPath: String = "",
    val isFolder: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val parentId: Long = 0
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE parentId = :parentId AND isFolder = 1 ORDER BY title ASC")
    suspend fun getFoldersByParent(parentId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE parentId = :parentId AND isFolder = 0 ORDER BY updatedAt DESC")
    suspend fun getNotesByParent(parentId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE folderPath = :path")
    suspend fun getByPath(path: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun search(query: String): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes WHERE id = :id OR parentId = :id")
    suspend fun deleteByIdCascade(id: Long)

    @Query("SELECT * FROM notes WHERE isFolder = 0 ORDER BY updatedAt DESC")
    suspend fun getAllNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE isFolder = 1")
    suspend fun getAllFolders(): List<NoteEntity>
}
