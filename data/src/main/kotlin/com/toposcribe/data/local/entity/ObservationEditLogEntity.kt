package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
/** Registra el valor anterior de un campo antes de sobrescribirlo -- nunca se pierde el dato original. */
@Entity(
    tableName = "observation_edit_log",
    indices = [Index(value = ["table_name", "row_id"])],
)
data class ObservationEditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: Long,
    val field: String,
    @ColumnInfo(name = "old_value") val oldValue: String? = null,
    @ColumnInfo(name = "new_value") val newValue: String? = null,
    @ColumnInfo(name = "edited_at") val editedAt: String,
)
