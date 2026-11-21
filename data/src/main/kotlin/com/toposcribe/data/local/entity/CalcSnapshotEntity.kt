package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Huella de los datos de entrada con los que se calculó un resultado. Si las lecturas actuales dan
 * otra huella, el resultado guardado está DESACTUALIZADO hasta que se vuelva a calcular.
 * kind = "ALTIMETRIA" (subjectId = id de la nivelación) o "PLANIMETRIA" (subjectId = 0).
 */
@Serializable
@Entity(
    tableName = "calc_snapshots",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["project_id", "kind", "subject_id"], unique = true)],
)
data class CalcSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val kind: String,
    @ColumnInfo(name = "subject_id") val subjectId: Long,
    @ColumnInfo(name = "input_hash") val inputHash: String,
    @ColumnInfo(name = "computed_at") val computedAt: String,
)
