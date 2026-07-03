package com.dtn.mesh.receiver

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding [MeshTransport] to [MultiTransportManager].
 *
 * The multi-transport manager aggregates all available transports
 * (LoRa via Meshtastic + WiFi Direct) behind the single MeshTransport interface.
 * The orchestrator and rest of the app only see one unified transport.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @Singleton
    abstract fun bindMeshTransport(manager: MultiTransportManager): MeshTransport
}
