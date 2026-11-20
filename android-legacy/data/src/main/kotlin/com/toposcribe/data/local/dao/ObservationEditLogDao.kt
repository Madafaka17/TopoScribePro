package com.toposcribe.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.toposcribe.data.local.entity.ObservationEditLogEntity

@Dao
interface ObservationEditLogDao {
    @Query("SELECT * FROM observation_edit_log WHERE table_name = :tableName AND row_id = :rowId ORDER BY edited_at ASC")
    suspend fun listFor(tableName: String, rowId: Long): List<ObservationEditLogEntity>

    @Insert
    suspend fun insert(entry: ObservationEditLogEntity): Long
}
