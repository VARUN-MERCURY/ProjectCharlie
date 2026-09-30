package com.alpha.charlie.memory

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: Fact): Long

    @Query("""
        SELECT * FROM facts 
        WHERE subject LIKE '%' || :query || '%' 
           OR value LIKE '%' || :query || '%' 
           OR raw_text LIKE '%' || :query || '%' 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 10): List<Fact>

    @Query("SELECT * FROM facts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<Fact>

    @Query("SELECT * FROM facts ORDER BY timestamp DESC")
    suspend fun getAll(): List<Fact>

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Delete
    suspend fun delete(fact: Fact)

    @Query("DELETE FROM facts")
    suspend fun deleteAll()
}
