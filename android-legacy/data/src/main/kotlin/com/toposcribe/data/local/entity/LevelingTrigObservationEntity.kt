package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "leveling_trig_observations",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["occupied_point_id"]),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["target_point_id"]),
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["occupied_point_id"]),
        Index(value = ["target_point_id"]),
    ],
)
data class LevelingTrigObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "occupied_point_id") val occupiedPointId: Long,
    @ColumnInfo(name = "target_point_id") val targetPointId: Long,
    @ColumnInfo(name = "slope_distance") val slopeDistance: Double? = null,
    @ColumnInfo(name = "horizontal_distance") val horizontalDistance: Double? = null,
    @ColumnInfo(name = "zenith_angle") val zenithAngle: Double? = null,
    @ColumnInfo(name = "vertical_angle") val verticalAngle: Double? = null,
    @ColumnInfo(name = "instrument_height") val instrumentHeight: Double,
    @ColumnInfo(name = "target_height") val targetHeight: Double,
    val source: String = "manual",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)
