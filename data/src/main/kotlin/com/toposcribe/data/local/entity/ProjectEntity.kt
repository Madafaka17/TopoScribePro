package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Paneles condicionales de Instrumento (Estación Total / GNSS-RTK / Nivel Óptico) del diseño --
 * no existen como tales en la app web, se agregan aquí via @Embedded (mismo criterio de "columnas
 * planas en projects" que ya usa el server, sin crear una tabla nueva para esto).
 */
data class InstrumentProfile(
    // Estación Total
    @ColumnInfo(name = "angular_precision_arcsec") val angularPrecisionArcSec: Double? = null,
    @ColumnInfo(name = "linear_tolerance_denominator") val linearToleranceDenominator: Double? = null,
    // GNSS/RTK
    @ColumnInfo(name = "gnss_solution_status") val gnssSolutionStatus: GnssSolutionStatus? = null,
    @ColumnInfo(name = "pdop") val pdop: Double? = null,
    @ColumnInfo(name = "differential_correction_type") val differentialCorrectionType: DifferentialCorrectionType? = null,
    // Nivel Óptico
    @ColumnInfo(name = "compensator_sensitivity_arcsec") val compensatorSensitivityArcSec: Double? = null,
    @ColumnInfo(name = "mira_type") val miraType: String? = null,
    @ColumnInfo(name = "standard_precision_mm_per_km") val standardPrecisionMmPerKm: Double? = null,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val purpose: String? = null,
    val location: String? = null,
    @ColumnInfo(name = "site_characteristics") val siteCharacteristics: String? = null,
    val date: String? = null,
    val responsible: String? = null,
    @ColumnInfo(name = "field_procedure") val fieldProcedure: String? = null,
    val instrument: String? = null,
    @ColumnInfo(name = "instrument_type") val instrumentType: InstrumentType = InstrumentType.ESTACION_TOTAL,
    // Constante prismática guardada en METROS internamente (coherente con el motor de cálculo ya
    // probado); la UI de Instrumento la muestra/convierte en mm como pide el diseño.
    @ColumnInfo(name = "prism_constant_m") val prismConstantM: Double = 0.0,
    @Embedded val instrumentProfile: InstrumentProfile = InstrumentProfile(),
    @ColumnInfo(name = "coordinate_system") val coordinateSystem: String? = null,
    @ColumnInfo(name = "coordinate_mode") val coordinateMode: CoordinateMode = CoordinateMode.LOCAL,
    @ColumnInfo(name = "utm_datum") val utmDatum: String? = null,
    @ColumnInfo(name = "utm_zone") val utmZone: Int? = null,
    @ColumnInfo(name = "utm_hemisphere") val utmHemisphere: Hemisphere? = null,
    @ColumnInfo(name = "units_distance") val unitsDistance: String = "m",
    @ColumnInfo(name = "units_angle") val unitsAngle: String = "DMS",
    @ColumnInfo(name = "display_precision") val displayPrecision: Int = 3,
    val observations: String? = null,
    @ColumnInfo(name = "is_demo") val isDemo: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
