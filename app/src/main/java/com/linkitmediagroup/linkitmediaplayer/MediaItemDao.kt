package com.linkitmediagroup.linkitmediaplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaItemDao {

    @Query("SELECT * FROM media_items")
    fun getAllMediaItems(): List<MediaItemEntity> // Return List<MediaItemEntity> for Room compatibility

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(mediaItems: List<MediaItemEntity>) // Adjust parameter type to List<MediaItemEntity>

    @Query("DELETE FROM media_items")
    fun clearMediaItems() // Ensure this method is void or returns Int (number of rows affected)

    @Query("DELETE FROM media_items") // Use clearMediaItems in combination with insertAll for clearAndInsert
    fun clearAndInsert(mediaItems: List<MediaItemEntity>) {
        clearMediaItems()
        insertAll(mediaItems)
    }
}
