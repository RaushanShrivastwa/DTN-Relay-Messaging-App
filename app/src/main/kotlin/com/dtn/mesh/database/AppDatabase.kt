package com.dtn.mesh.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dtn.mesh.database.converter.Converters
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.dao.MessageDao
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.database.entity.EncounterEntity
import com.dtn.mesh.database.entity.ForwardingDecisionEntity
import com.dtn.mesh.database.entity.MessageEntity

/**
 * Room database for the DTN Mesh Relay Layer.
 *
 * Schema version 1: Initial schema with message buffer, contact state, and encounter history.
 *
 * Export schema = true for migration tooling in future versions.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        EncounterEntity::class,
        ForwardingDecisionEntity::class,
    ],
    // v2: added ContactEntity.custom_name column (user-provided nickname per peer).
    // fallbackToDestructiveMigration is set in DatabaseModule so existing data is wiped on upgrade
    // — acceptable for research prototype; wire a proper migration before field deployment.
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun encounterDao(): EncounterDao
    abstract fun forwardingDecisionDao(): ForwardingDecisionDao

    companion object {
        const val DATABASE_NAME = "dtn_mesh_relay.db"
    }
}
