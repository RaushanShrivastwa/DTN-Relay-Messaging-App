package com.dtn.mesh

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for DTN Mesh Relay Layer.
 * @HiltAndroidApp triggers Hilt code generation and serves as the application-level DI container.
 */
@HiltAndroidApp
class DtnMeshApplication : Application()
