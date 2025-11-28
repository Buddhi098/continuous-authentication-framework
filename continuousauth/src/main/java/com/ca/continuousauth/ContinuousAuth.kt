package com.ca.continuousauth

import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.core.authmodel.AuthModel
import com.ca.continuousauth.core.featureextractor.sensor.SensorDataCollector
import com.ca.continuousauth.core.featureextractor.sensor.denoiser.denoiseWith
import com.ca.continuousauth.core.featureextractor.sensor.denoiser.denoisercollection.EMADenoiser
import com.ca.continuousauth.core.featureextractor.sensor.denoiser.denoisercollection.SMADenoiser
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.FeatureExtractorPipeline
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.extractorcollection.CorrelationFeatureExtractor
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.extractorcollection.FFTFeatureExtractor
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.extractorcollection.MagnitudeFeatureExtractor
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.extractorcollection.StatisticalFeatureExtractor
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.sensorFeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import java.io.File
import java.nio.FloatBuffer

class ContinuousAuth(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val enrollmentSample = AuthConfigManager.config.enrollmentSamples
    private val sensorDataCollector : SensorDataCollector = SensorDataCollector(context , enableLogging =false)

    private val emaDenoiser : EMADenoiser = EMADenoiser(alpha = 0.2f)
    private val smaDenoiser : SMADenoiser = SMADenoiser(windowSize = 3)

    fun runEnrollmentPhase(authenticationFz: Int){

        val rawSensorBatchFlow: Flow<List<FloatArray>> = sensorDataCollector.batchFlow
        val denoisedSensorBatchFlow: Flow<List<FloatArray>> = rawSensorBatchFlow.denoiseWith(emaDenoiser , enableLogging = false)

        val pipeline = FeatureExtractorPipeline(
            extractors = listOf(
                StatisticalFeatureExtractor(),
                MagnitudeFeatureExtractor(),
                CorrelationFeatureExtractor(),
                FFTFeatureExtractor() // optional
            )
        )
        val featureFlow = sensorFeatureExtractor(denoisedSensorBatchFlow, pipeline , enableLogging = true).take(enrollmentSample)


        scope.launch {
            featureFlow.collect { featureVector ->
                Logger.d("Feature vector size: ${featureVector.size}")
                Logger.d(featureVector.joinToString(", "))
            }
        }

    }

    fun trainModel(context: Context){
        val authModel = AuthModel(context)

        // Load model from assets

        // Run training in a background thread (never block UI thread)
        CoroutineScope(Dispatchers.Default).launch {
            authModel.runTrainingSession()

            // Once training is done, you can close the interpreter
            authModel.close()
        }
    }

    fun runFullWorkflow(context: Context) {

        val authModel = AuthModel(context)
        val checkpointFile = File(context.filesDir,"user_profile_v1.ckpt")

        // ----------------------------------------------------------------
        // STEP 1: RESTORE EXISTING PROFILE (If available)
        // ----------------------------------------------------------------
        if (checkpointFile.exists()) {
            Logger.d("Found existing user profile. Loading...")
            val success = authModel.loadCheckpoint(checkpointFile)
            if (success) {
                Logger.d("Profile loaded successfully! Model is personalized.")
            } else {
                Logger.e("Failed to load profile. Using factory defaults.")
            }
        } else {
            Logger.d("No profile found. Starting with factory model.")
        }

        // ----------------------------------------------------------------
        // STEP 2: ON-DEVICE TRAINING (Personalization)
        // ----------------------------------------------------------------
        Logger.d("--- Starting Training Session ---")

        // Run training: 5 epochs, batch size 32, total 100 samples
        // Note: In a real app, you would pass actual sensor data buffers here,
        // but currently runTrainingSession generates internal dummy data.
        authModel.runTrainingSession(
            epochs = 5,
            batchSize = 1,
            numTrainings = 100
        )

        Logger.d("Training finished.")

        // ----------------------------------------------------------------
        // STEP 3: SAVE THE NEW STATE
        // ----------------------------------------------------------------
        Logger.d("--- Saving Profile ---")
        val saved = authModel.saveCheckpoint(checkpointFile)

        if (saved) {
            Logger.d("User profile saved to: ${checkpointFile.absolutePath}")
            Logger.d("File size: ${checkpointFile.length()} bytes")
        } else {
            Logger.e( "Failed to save profile!")
        }

        // ----------------------------------------------------------------
        // STEP 4: INFERENCE (Authentication)
        // ----------------------------------------------------------------
        Logger.d("--- Running Inference ---")

        val batchSize = 1
        val inputDim = 20

        // Create dummy input (Simulating live sensor data)
        val inputBuffer = FloatBuffer.allocate(inputDim * batchSize)
        for (i in 0 until inputDim) {
            inputBuffer.put(0.5f) // Dummy value
        }

        // Run inference
        val reconstruction = authModel.infer(inputBuffer, batchSize)

        // Analyze results
        if (reconstruction != null) {
            val outputVector = reconstruction[0] // First item in batch

            // Calculate reconstruction error (MSE) roughly
            var errorSum = 0.0
            for (i in 0 until inputDim) {
                val diff = 0.5f - outputVector[i]
                errorSum += (diff * diff)
            }
            val mse = errorSum / inputDim

            Logger.d("Inference Output [0..4]: ${outputVector.take(5).joinToString(", ")}...")
            Logger.d("Reconstruction Error (MSE): $mse")

            // Simple threshold logic for authentication
            if (mse < 0.1) {
                Logger.d("AUTH RESULT: Authenticated (User Match)")
            } else {
                Logger.e("AUTH RESULT: Rejected (Anomaly Detected)")
            }
        } else {
            Logger.e("Inference returned null!")
        }
    }
}
