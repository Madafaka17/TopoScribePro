package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.toposcribe.calc.TraverseType

@Entity(
    tableName = "traverses",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id"])],
)
data class TraverseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val name: String,
    val type: TraverseType,
    @ColumnInfo(name = "start_point_id") val startPointId: Long? = null,
    @ColumnInfo(name = "end_point_id") val endPointId: Long? = null,
    @ColumnInfo(name = "start_azimuth") val startAzimuth: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
