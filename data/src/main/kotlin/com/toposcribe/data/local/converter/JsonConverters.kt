package com.toposcribe.data.local.converter

import androidx.room.TypeConverter
import com.toposcribe.data.local.entity.PointCorrection
import com.toposcribe.data.local.entity.SourceObservationRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

class JsonConverters {

    @TypeConverter
    fun longListToJson(value: List<Long>): String = json.encodeToString(ListSerializer(Long.serializer()), value)
    @TypeConverter
    fun jsonToLongList(value: String): List<Long> =
        if (value.isBlank()) emptyList() else json.decodeFromString(ListSerializer(Long.serializer()), value)

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? = value?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? =
        value?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }

    @TypeConverter
    fun pointCorrectionListToJson(value: List<PointCorrection>): String =
        json.encodeToString(ListSerializer(PointCorrection.serializer()), value)
    @TypeConverter
    fun jsonToPointCorrectionList(value: String): List<PointCorrection> =
        if (value.isBlank()) emptyList() else json.decodeFromString(ListSerializer(PointCorrection.serializer()), value)

    @TypeConverter
    fun sourceObservationRefListToJson(value: List<SourceObservationRef>): String =
        json.encodeToString(ListSerializer(SourceObservationRef.serializer()), value)
    @TypeConverter
    fun jsonToSourceObservationRefList(value: String): List<SourceObservationRef> =
        if (value.isBlank()) emptyList() else json.decodeFromString(ListSerializer(SourceObservationRef.serializer()), value)
}
