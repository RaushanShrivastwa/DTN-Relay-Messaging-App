package com.dtn.mesh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: DtnViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        } else {
            viewModel.onPermissionsDenied(grants.filterValues { !it }.keys.toList())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request all needed permissions upfront
        permissionLauncher.launch(PermissionHelper.requiredPermissions())

        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(viewModel)
                }
            }
        }
    }
}
