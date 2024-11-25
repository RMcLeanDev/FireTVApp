package com.linkitmediagroup.linkitmediaplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaItemDao {

    // Fetch all media items from the database
    @Query("SELECT * FROM media_items")
    fun getAllMediaItems(): List<MediaItemEntity>

    // Insert a list of media items into the database (replace on conflict)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(mediaItems: List<MediaItemEntity>)

    // Clear all media items from the database
    @Query("DELETE FROM media_items")
    fun clearMediaItems()

    // Clear existing media items and insert new ones
    @androidx.room.Transaction // Ensures this operation runs atomically
    fun clearAndInsert(mediaItems: List<MediaItemEntity>) {
        clearMediaItems()
        insertAll(mediaItems)
    }
}
