package com.linkitmediagroup.linkitmediaplayer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val url: String, // Primary key for identifying the media item
    @ColumnInfo(name = "type") val type: String, // Type of media (e.g., image or video)
    @ColumnInfo(name = "duration") val duration: Long // Duration of media item in milliseconds
)
