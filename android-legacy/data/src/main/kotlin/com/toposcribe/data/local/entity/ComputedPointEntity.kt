package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class SourceObservationRef(val table: String, val id: Long)

/**
 * Núcleo de trazabilidad, sin cambios de filosofía respecto a la app web: recalcular = INSERT
 * nueva fila + is_current=false en la anterior. NUNCA se sobrescribe east/north/elevation de una
 * fila existente. Bruto y compensado son filas distintas (method="poligonal_bruto" vs
 * "poligonal_bowditch"), así la UI muestra antes/después sin perder el dato bruto.
 */
@Entity(
    tableName = "computed_points",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"]),
        ForeignKey(entity = AdjustmentEntity::class, parentColumns = ["id"], childColumns = ["adjustment_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["project_id", "point_id", "is_current"]),
        Index(value = ["point_id", "method", "is_current"]),
        Index(value = ["adjustment_id"]),
    ],
)
data class ComputedPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "point_id") val pointId: Long,
    val east: Double? = null,
    val north: Double? = null,
    val elevation: Double? = null,
    val method: String,
    @ColumnInfo(name = "formula_id") val formulaId: String,
    @ColumnInfo(name = "source_observation_ids") val sourceObservationIds: List<SourceObservationRef>,
    @ColumnInfo(name = "adjustment_id") val adjustmentId: Long? = null,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean = true,
    val warnings: List<String>? = null,
    @ColumnInfo(name = "computed_at") val computedAt: String,
)
