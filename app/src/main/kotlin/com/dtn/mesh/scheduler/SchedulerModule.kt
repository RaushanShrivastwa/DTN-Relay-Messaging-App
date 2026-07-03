package com.dtn.mesh.scheduler

import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.QLearningConfig
import com.dtn.mesh.queue.BufferConfig
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.routing.MaxPropStrategy
import com.dtn.mesh.routing.ProphetStrategy
import com.dtn.mesh.routing.StrategySelector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing scheduler, routing, Q-engine, and queue singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object SchedulerModule {

    @Provides
    @Singleton
    fun provideSchedulerConfig(): SchedulerConfig = SchedulerConfig()

    @Provides
    @Singleton
    fun provideBufferConfig(): BufferConfig = BufferConfig()

    @Provides
    @Singleton
    fun provideQLearningConfig(): QLearningConfig = QLearningConfig()

    @Provides
    @Singleton
    fun provideDoubleQLearningEngine(config: QLearningConfig): DoubleQLearningEngine =
        DoubleQLearningEngine(config)

    @Provides
    @Singleton
    fun provideStrategySelector(): StrategySelector =
        StrategySelector(
            prophet = ProphetStrategy(),
            maxProp = MaxPropStrategy(),
            initialStrategy = StrategySelector.StrategyType.PROPHET,
        )

    @Provides
    @Singleton
    fun provideAirtimeBudgetTracker(): AirtimeBudgetTracker = AirtimeBudgetTracker()
}
