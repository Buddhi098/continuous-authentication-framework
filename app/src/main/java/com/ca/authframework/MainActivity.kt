package com.ca.authframework

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ca.authframework.components.CollectionScreen
import com.ca.authframework.components.TrainingScreen
import com.ca.authframework.viewmodels.CollectionViewModel
import com.ca.authframework.viewmodels.TrainingViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.authframework.ui.theme.AuthframeworkTheme

class MainActivity : ComponentActivity() {

    // Single ContinuousAuth instance
    val targetSamples : Int = 200
    private val continuousAuth by lazy { ContinuousAuth(this) }

    // ViewModels
    private val collectionViewModel by lazy { CollectionViewModel(continuousAuth) }
    private val trainingViewModel by lazy { TrainingViewModel(continuousAuth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AuthframeworkTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Sample Collection UI
                        CollectionScreen(
                            viewModel = collectionViewModel,
                            targetSamples = targetSamples
                        )

                        // Training UI
                        TrainingScreen(viewModel = trainingViewModel , targetSamples = targetSamples)
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Auto-pause collection when app goes to background
        collectionViewModel.pauseCollection()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure collection is paused and resources cleared on destroy
        collectionViewModel.pauseCollection()
    }
}
