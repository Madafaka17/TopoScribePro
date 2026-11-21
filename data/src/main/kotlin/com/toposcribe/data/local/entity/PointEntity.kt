package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["project_id", "code"], unique = true),
        Index(value = ["project_id"]),
    ],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val code: String,
    val description: String? = null,
    val observations: String? = null,
    /** Instrumento o procedimiento relacionado: NIVEL, TEODOLITO, CONTROL u OTRO. */
    @ColumnInfo(name = "instrument_ref") val instrumentRef: String? = null,
    @ColumnInfo(name = "tramo_sector") val tramoSector: String? = null,
    @ColumnInfo(name = "is_control") val isControl: Boolean = false,
    val role: PointRole = PointRole.OTRO,
    @ColumnInfo(name = "data_source") val dataSource: DataSource = DataSource.OTRO,
    @ColumnInfo(name = "fix_type") val fixType: FixType? = null,
    @ColumnInfo(name = "fixed_east") val fixedEast: Double? = null,
    @ColumnInfo(name = "fixed_north") val fixedNorth: Double? = null,
    @ColumnInfo(name = "fixed_elevation") val fixedElevation: Double? = null,
    @ColumnInfo(name = "sketch_photo_path") val sketchPhotoPath: String? = null,
    @ColumnInfo(name = "photo_caption") val photoCaption: String? = null,
    @ColumnInfo(name = "photo_segment") val photoSegment: String? = null,
    @ColumnInfo(name = "photo_chainage") val photoChainage: Double? = null,
    val reviewed: Boolean = false,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: String? = null,
    @ColumnInfo(name = "reviewed_by") val reviewedBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)
