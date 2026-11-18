package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "import_batches",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id"])],
)
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    @ColumnInfo(name = "target_table") val targetTable: String? = null,
    @ColumnInfo(name = "imported_rows") val importedRows: Int? = null,
    val errors: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
