package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.toposcribe.calc.ReadingType

@Entity(
    tableName = "leveling_observations",
    foreignKeys = [
        ForeignKey(entity = LevelingRunEntity::class, parentColumns = ["id"], childColumns = ["leveling_run_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"]),
    ],
    indices = [
        Index(value = ["leveling_run_id"]),
        Index(value = ["point_id"]),
    ],
)
data class LevelingObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "leveling_run_id") val levelingRunId: Long,
    @ColumnInfo(name = "sequence_order") val sequenceOrder: Int,
    @ColumnInfo(name = "point_id") val pointId: Long,
    @ColumnInfo(name = "reading_type") val readingType: ReadingType,
    @ColumnInfo(name = "reading_value") val readingValue: Double,
    @ColumnInfo(name = "is_turning_point") val isTurningPoint: Boolean = false,
    @ColumnInfo(name = "observation_date") val observationDate: String? = null,
    @ColumnInfo(name = "raw_notes") val rawNotes: String? = null,
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
    val source: String = "manual",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)
