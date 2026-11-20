package com.toposcribe.data.local.converter

import androidx.room.TypeConverter
import com.toposcribe.calc.ReadingType
import com.toposcribe.calc.TraverseType
import com.toposcribe.data.local.entity.CoordinateMode
import com.toposcribe.data.local.entity.DataSource
import com.toposcribe.data.local.entity.DifferentialCorrectionType
import com.toposcribe.data.local.entity.FixType
import com.toposcribe.data.local.entity.GnssSolutionStatus
import com.toposcribe.data.local.entity.Hemisphere
import com.toposcribe.data.local.entity.InstrumentType
import com.toposcribe.data.local.entity.PlanimetryMethod
import com.toposcribe.data.local.entity.PointRole

/** Cada enum se guarda por NOMBRE (no por ordinal) -- más legible y más seguro ante reordenar valores. */
class EnumConverters {

    @TypeConverter
    fun instrumentTypeToString(value: InstrumentType): String = value.name
    @TypeConverter
    fun stringToInstrumentType(value: String): InstrumentType = InstrumentType.valueOf(value)

    @TypeConverter
    fun coordinateModeToString(value: CoordinateMode): String = value.name
    @TypeConverter
    fun stringToCoordinateMode(value: String): CoordinateMode = CoordinateMode.valueOf(value)

    @TypeConverter
    fun hemisphereToString(value: Hemisphere?): String? = value?.name
    @TypeConverter
    fun stringToHemisphere(value: String?): Hemisphere? = value?.let { Hemisphere.valueOf(it) }

    @TypeConverter
    fun pointRoleToString(value: PointRole): String = value.name
    @TypeConverter
    fun stringToPointRole(value: String): PointRole = PointRole.valueOf(value)

    @TypeConverter
    fun dataSourceToString(value: DataSource): String = value.name
    @TypeConverter
    fun stringToDataSource(value: String): DataSource = DataSource.valueOf(value)

    @TypeConverter
    fun fixTypeToString(value: FixType?): String? = value?.name
    @TypeConverter
    fun stringToFixType(value: String?): FixType? = value?.let { FixType.valueOf(it) }

    @TypeConverter
    fun gnssSolutionStatusToString(value: GnssSolutionStatus?): String? = value?.name
    @TypeConverter
    fun stringToGnssSolutionStatus(value: String?): GnssSolutionStatus? = value?.let { GnssSolutionStatus.valueOf(it) }

    @TypeConverter
    fun differentialCorrectionTypeToString(value: DifferentialCorrectionType?): String? = value?.name
    @TypeConverter
    fun stringToDifferentialCorrectionType(value: String?): DifferentialCorrectionType? = value?.let { DifferentialCorrectionType.valueOf(it) }

    @TypeConverter
    fun planimetryMethodToString(value: PlanimetryMethod): String = value.name
    @TypeConverter
    fun stringToPlanimetryMethod(value: String): PlanimetryMethod = PlanimetryMethod.valueOf(value)

    @TypeConverter
    fun traverseTypeToString(value: TraverseType): String = value.name
    @TypeConverter
    fun stringToTraverseType(value: String): TraverseType = TraverseType.valueOf(value)

    @TypeConverter
    fun readingTypeToString(value: ReadingType): String = value.name
    @TypeConverter
    fun stringToReadingType(value: String): ReadingType = ReadingType.valueOf(value)
}
