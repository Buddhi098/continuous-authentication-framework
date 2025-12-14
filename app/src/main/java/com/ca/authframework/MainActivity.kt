package com.ca.authframework

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ca.authframework.components.CollectionScreen
import com.ca.authframework.components.TrainingScreen
import com.ca.authframework.viewmodels.CollectionViewModel
import com.ca.authframework.viewmodels.TrainingViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.authframework.ui.theme.AuthframeworkTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CAFramework"
    }

    // Target sample count
    private val targetSamples: Int = 10

    // Single ContinuousAuth instance
    private val continuousAuth by lazy {
        Log.d(TAG, "Initializing ContinuousAuth")
        ContinuousAuth(
            context = this ,
            enrollmentSamples = targetSamples ,
            shouldLogFeatureVector = true,
            enableLog = true
        )
    }
    // ViewModels
    private val collectionViewModel by lazy {
        Log.d(TAG, "Initializing CollectionViewModel")
        CollectionViewModel(continuousAuth)
    }

    private val trainingViewModel by lazy {
        Log.d(TAG, "Initializing TrainingViewModel")
        TrainingViewModel(continuousAuth)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")

        enableEdgeToEdge()
        Log.d(TAG, "Edge-to-edge enabled")

        setContent {
            Log.d(TAG, "Setting Compose content")

            AuthframeworkTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    Log.d(TAG, "Scaffold composed")

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {

                        Log.d(TAG, "Composing CollectionScreen")
                        CollectionScreen(
                            viewModel = collectionViewModel,
                            targetSamples = targetSamples
                        )

                        Log.d(TAG, "Composing TrainingScreen")
                        TrainingScreen(
                            viewModel = trainingViewModel,
                            targetSamples = targetSamples
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called → Pausing collection")
        collectionViewModel.pauseCollection()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called → Cleaning up resources")
        collectionViewModel.pauseCollection()
    }
}
