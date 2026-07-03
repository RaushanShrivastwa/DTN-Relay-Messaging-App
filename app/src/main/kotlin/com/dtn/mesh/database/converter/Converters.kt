package com.dtn.mesh.database.converter

import androidx.room.TypeConverter

/**
 * Room type converters for non-primitive column types.
 *
 * Currently minimal — Room natively handles String, Int, Long, Float, Boolean, ByteArray.
 * Add converters here as composite types are introduced (e.g. serialised routing vectors).
 */
class Converters {

    /**
     * Convert a nullable list of strings (e.g. relay path) to a pipe-delimited string for storage.
     * Pipe chosen over comma because node IDs contain hex chars but never pipes.
     */
    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.joinToString("|")

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.takeIf { it.isNotEmpty() }?.split("|")
}
