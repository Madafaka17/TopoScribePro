package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class PointCorrection(val pointId: Long, val correctionE: Double, val correctionN: Double)

@Serializable
@Entity(
    tableName = "adjustments",
    foreignKeys = [
        ForeignKey(entity = TraverseEntity::class, parentColumns = ["id"], childColumns = ["traverse_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["traverse_id"])],
)
data class AdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "traverse_id") val traverseId: Long,
    val method: String = "bowditch",
    @ColumnInfo(name = "angular_misclosure") val angularMisclosure: Double? = null,
    @ColumnInfo(name = "angular_tolerance") val angularTolerance: Double? = null,
    @ColumnInfo(name = "angular_ok") val angularOk: Boolean? = null,
    @ColumnInfo(name = "fE") val fE: Double,
    @ColumnInfo(name = "fN") val fN: Double,
    @ColumnInfo(name = "fL") val fL: Double,
    val perimeter: Double,
    @ColumnInfo(name = "relative_precision") val relativePrecision: String,
    @ColumnInfo(name = "per_point_corrections") val perPointCorrections: List<PointCorrection>,
    val applied: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
