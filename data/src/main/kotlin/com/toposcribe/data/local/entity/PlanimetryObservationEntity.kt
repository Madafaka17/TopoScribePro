package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "planimetry_observations",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["occupied_point_id"]),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["target_point_id"]),
        ForeignKey(entity = TraverseEntity::class, parentColumns = ["id"], childColumns = ["traverse_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = StationSetupEntity::class, parentColumns = ["id"], childColumns = ["station_setup_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["occupied_point_id"]),
        Index(value = ["target_point_id"]),
        Index(value = ["traverse_id"]),
        Index(value = ["station_setup_id"]),
    ],
)
data class PlanimetryObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "occupied_point_id") val occupiedPointId: Long,
    @ColumnInfo(name = "target_point_id") val targetPointId: Long,
    val method: PlanimetryMethod,
    @ColumnInfo(name = "horizontal_distance") val horizontalDistance: Double? = null,
    @ColumnInfo(name = "slope_distance") val slopeDistance: Double? = null,
    @ColumnInfo(name = "zenith_angle") val zenithAngle: Double? = null,
    @ColumnInfo(name = "vertical_angle") val verticalAngle: Double? = null,
    @ColumnInfo(name = "horizontal_angle") val horizontalAngle: Double? = null,
    val azimuth: Double? = null,
    val bearing: String? = null,
    @ColumnInfo(name = "instrument_height") val instrumentHeight: Double? = null,
    @ColumnInfo(name = "prism_height") val prismHeight: Double? = null,
    @ColumnInfo(name = "tramo") val tramo: String? = null,
    @ColumnInfo(name = "traverse_id") val traverseId: Long? = null,
    @ColumnInfo(name = "station_setup_id") val stationSetupId: Long? = null,
    @ColumnInfo(name = "sequence_order") val sequenceOrder: Int? = null,
    @ColumnInfo(name = "observation_date") val observationDate: String? = null,
    @ColumnInfo(name = "raw_notes") val rawNotes: String? = null,
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
    val source: String = "manual",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)
