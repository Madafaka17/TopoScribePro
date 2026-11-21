package com.toposcribe.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.toposcribe.calc.AngleConvention
import kotlinx.serialization.Serializable

/**
 * Header de "Estación Ocupada & Referencia de Orientación (Vista Atrás)" de la Libreta -- la
 * única pieza del esquema que no es un puerto 1:1 de la app web (confirmado con el usuario). Solo
 * PRELLENA valores por defecto al crear una observación nueva; cada PlanimetryObservationEntity
 * sigue guardando su propio occupiedPointId/instrumentHeight (denormalizado, igual que el
 * server), así que un setup posterior nunca reescribe el historial de observaciones ya creadas.
 */
@Serializable
@Entity(
    tableName = "station_setups",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["occupied_point_id"]),
    ],
    indices = [Index(value = ["project_id"]), Index(value = ["occupied_point_id"])],
)
data class StationSetupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "occupied_point_id") val occupiedPointId: Long,
    @ColumnInfo(name = "instrument_height_ai") val instrumentHeightAi: Double,
    @ColumnInfo(name = "backsight_point_label") val backsightPointLabel: String,
    @ColumnInfo(name = "base_azimuth_deg") val baseAzimuthDeg: Int,
    @ColumnInfo(name = "base_azimuth_min") val baseAzimuthMin: Int,
    @ColumnInfo(name = "base_azimuth_sec") val baseAzimuthSec: Double,
    // Cacheado via Angles.dmsToDecimal, nunca fuente de verdad -- deg/min/sec son los campos reales.
    @ColumnInfo(name = "base_azimuth_decimal") val baseAzimuthDecimal: Double,
    /** Lectura del círculo horizontal al visar la referencia (por defecto 0°00'00"). */
    @ColumnInfo(name = "backsight_reading_deg", defaultValue = "0") val backsightReadingDeg: Double = 0.0,
    /** Sentido en que crece la lectura del círculo horizontal (HORARIO / ANTIHORARIO). */
    @ColumnInfo(name = "angle_convention", defaultValue = "'HORARIO'") val angleConvention: AngleConvention = AngleConvention.HORARIO,
    /** Punto del proyecto usado como referencia de orientación (opcional; la etiqueta se conserva siempre). */
    @ColumnInfo(name = "backsight_point_id") val backsightPointId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
