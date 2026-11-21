package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Anexo del levantamiento (croquis, ficha de campo, PDF, fotografía general u otro respaldo).
 * Es independiente de la fotografía asociada a un punto. El archivo se guarda en el almacenamiento
 * privado de la app (files/annexes/<storedFile>); aquí solo viven sus metadatos. El respaldo
 * completo del proyecto incluye el CONTENIDO del archivo, no solo su nombre.
 */
@Serializable
@Entity(
    tableName = "annexes",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["project_id"]), Index(value = ["point_id"])],
)
data class AnnexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "original_name") val originalName: String,
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val kind: AnnexKind = AnnexKind.OTRO,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    /** Nombre del archivo dentro de files/annexes/ (único; no es el nombre original). */
    @ColumnInfo(name = "stored_file") val storedFile: String,
    @ColumnInfo(name = "added_at") val addedAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    /** Punto del proyecto con el que se relaciona (opcional). */
    @ColumnInfo(name = "point_id") val pointId: Long? = null,
    /** Sección del informe con la que se relaciona (opcional), p. ej. «Altimetría». */
    @ColumnInfo(name = "report_section") val reportSection: String? = null,
    /** Orden de aparición en el informe (1, 2, 3…). */
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
