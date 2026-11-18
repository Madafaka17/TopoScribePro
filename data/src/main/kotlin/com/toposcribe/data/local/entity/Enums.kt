package com.toposcribe.data.local.entity

enum class InstrumentType { ESTACION_TOTAL, TEODOLITO, NIVEL_OPTICO, GNSS_RTK }

enum class CoordinateMode { LOCAL, UTM }

enum class Hemisphere { N, S }

/** Etiqueta organizativa/de visualización -- independiente de PointEntity.isControl, que es el
 * campo que habilita un punto como referencia de cierre en los cálculos. */
enum class PointRole { CONTROL, POLIGONAL, RELLENO, OTRO }

enum class DataSource { CONTROL_OFICIAL, MEDIDO_GNSS, IMPORTADO, OTRO }

enum class FixType { FIXED, FLOAT, DGPS, AUTONOMO }

enum class GnssSolutionStatus { SIN_CONEXION, AUTONOMO, RTK_FLOAT, RTK_FIXED }

enum class DifferentialCorrectionType { NTRIP, UHF, SBAS }

enum class PlanimetryMethod { ACIMUT_DISTANCIA, ANGULO_DISTANCIA }
