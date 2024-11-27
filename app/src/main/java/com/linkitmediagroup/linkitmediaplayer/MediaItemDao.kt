package com.linkitmediagroup.linkitmediaplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_items")
    fun getAllMediaItems(): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(mediaItems: List<MediaItemEntity>)

    @Query("DELETE FROM media_items")
    fun clearMediaItems()
}
