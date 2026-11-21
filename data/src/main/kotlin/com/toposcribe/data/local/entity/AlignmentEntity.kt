package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "alignments",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id"])],
)
data class AlignmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    val name: String,
    @ColumnInfo(name = "point_sequence") val pointSequence: List<Long>,
    /** true = la línea se cierra uniendo el último punto con el primero (polígono). */
    @ColumnInfo(name = "closed", defaultValue = "0") val closed: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
