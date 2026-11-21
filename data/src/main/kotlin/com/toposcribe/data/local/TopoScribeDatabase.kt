package com.toposcribe.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.toposcribe.data.local.converter.EnumConverters
import com.toposcribe.data.local.converter.JsonConverters
import com.toposcribe.data.local.dao.AdjustmentDao
import com.toposcribe.data.local.dao.AnnexDao
import com.toposcribe.data.local.dao.CalcSnapshotDao
import com.toposcribe.data.local.dao.AlignmentDao
import com.toposcribe.data.local.dao.ComputedPointDao
import com.toposcribe.data.local.dao.ImportBatchDao
import com.toposcribe.data.local.dao.LevelingObservationDao
import com.toposcribe.data.local.dao.LevelingRunDao
import com.toposcribe.data.local.dao.LevelingTrigObservationDao
import com.toposcribe.data.local.dao.ObservationEditLogDao
import com.toposcribe.data.local.dao.PlanimetryObservationDao
import com.toposcribe.data.local.dao.PointDao
import com.toposcribe.data.local.dao.ProjectDao
import com.toposcribe.data.local.dao.StationSetupDao
import com.toposcribe.data.local.dao.TraverseDao
import com.toposcribe.data.local.entity.AdjustmentEntity
import com.toposcribe.data.local.entity.AnnexEntity
import com.toposcribe.data.local.entity.CalcSnapshotEntity
import com.toposcribe.data.local.entity.AlignmentEntity
import com.toposcribe.data.local.entity.ComputedPointEntity
import com.toposcribe.data.local.entity.ImportBatchEntity
import com.toposcribe.data.local.entity.LevelingObservationEntity
import com.toposcribe.data.local.entity.LevelingRunEntity
import com.toposcribe.data.local.entity.LevelingTrigObservationEntity
import com.toposcribe.data.local.entity.ObservationEditLogEntity
import com.toposcribe.data.local.entity.PlanimetryObservationEntity
import com.toposcribe.data.local.entity.PointEntity
import com.toposcribe.data.local.entity.ProjectEntity
import com.toposcribe.data.local.entity.StationSetupEntity
import com.toposcribe.data.local.entity.TraverseEntity

@Database(
    entities = [
        ProjectEntity::class,
        PointEntity::class,
        StationSetupEntity::class,
        TraverseEntity::class,
        PlanimetryObservationEntity::class,
        LevelingRunEntity::class,
        LevelingObservationEntity::class,
        LevelingTrigObservationEntity::class,
        AdjustmentEntity::class,
        ComputedPointEntity::class,
        ObservationEditLogEntity::class,
        AlignmentEntity::class,
        ImportBatchEntity::class,
        AnnexEntity::class,
        CalcSnapshotEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class, JsonConverters::class)
abstract class TopoScribeDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun pointDao(): PointDao
    abstract fun stationSetupDao(): StationSetupDao
    abstract fun traverseDao(): TraverseDao
    abstract fun planimetryObservationDao(): PlanimetryObservationDao
    abstract fun levelingRunDao(): LevelingRunDao
    abstract fun levelingObservationDao(): LevelingObservationDao
    abstract fun levelingTrigObservationDao(): LevelingTrigObservationDao
    abstract fun adjustmentDao(): AdjustmentDao
    abstract fun computedPointDao(): ComputedPointDao
    abstract fun observationEditLogDao(): ObservationEditLogDao
    abstract fun alignmentDao(): AlignmentDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun annexDao(): AnnexDao
    abstract fun calcSnapshotDao(): CalcSnapshotDao

    companion object {
        const val DATABASE_NAME = "toposcribe.db"
        const val VERSION = 2
    }
}
