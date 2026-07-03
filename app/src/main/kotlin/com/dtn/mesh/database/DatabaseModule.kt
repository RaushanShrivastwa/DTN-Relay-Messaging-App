package com.dtn.mesh.database

import android.content.Context
import androidx.room.Room
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room database and DAO singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            // Destructive migration for research prototype — we don't need migration paths yet.
            // Replace with proper migrations before any field deployment.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideEncounterDao(db: AppDatabase): EncounterDao = db.encounterDao()

    @Provides
    fun provideForwardingDecisionDao(db: AppDatabase): ForwardingDecisionDao = db.forwardingDecisionDao()
}
