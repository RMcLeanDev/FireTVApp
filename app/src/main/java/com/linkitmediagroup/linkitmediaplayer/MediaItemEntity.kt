package com.linkitmediagroup.linkitmediaplayer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val url: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "duration") val duration: Long
)
