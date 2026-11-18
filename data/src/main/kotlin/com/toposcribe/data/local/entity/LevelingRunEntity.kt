package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "leveling_runs",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["start_bm_point_id"]),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["end_bm_point_id"]),
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["start_bm_point_id"]),
        Index(value = ["end_bm_point_id"]),
    ],
)
data class LevelingRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val name: String,
    @ColumnInfo(name = "start_bm_point_id") val startBmPointId: Long,
    @ColumnInfo(name = "end_bm_point_id") val endBmPointId: Long? = null,
    @ColumnInfo(name = "end_known_elevation") val endKnownElevation: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
