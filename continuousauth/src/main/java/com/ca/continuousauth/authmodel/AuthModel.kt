package com.ca.continuousauth.authmodel

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.random.Random

/**
 * Manages the TFLite Autoencoder model for both Inference, On-Device Training,
 * and Checkpoint Management (Save/Restore).
 */
class AuthModel(private val context: Context) {

    companion object {
        private val MODEL_FILENAME = AuthConfigManager.config.modelFileName
        private var INPUT_DIM = AuthConfigManager.config.featureDimension

        // Signature Keys (Must match Python export)
        private val SIG_TRAIN = AuthConfigManager.config.sigTrain
        private val SIG_INFER = AuthConfigManager.config.sigInfer
        private val SIG_INIT = AuthConfigManager.config.sigInit
        private val SIG_SAVE = AuthConfigManager.config.sigSave
        private val SIG_RESTORE = AuthConfigManager.config.sigRestore

        // Tensor Names
        private val INPUT_KEY = AuthConfigManager.config.inputKey
        private val OUTPUT_RECONSTRUCTION = AuthConfigManager.config.outputReconstruction
        private val OUTPUT_LOSS = AuthConfigManager.config.outputLoss
        private val OUTPUT_STATUS = AuthConfigManager.config.outputStatus

        private val RECONSTRUCTION_ERROR_KEY = "reconstruction_error"
    }

    private var interpreter: Interpreter? = null

    init {
        Logger.d("Initializing AuthModel...")
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        try {
            Logger.d("Attempting to load model file: $MODEL_FILENAME")
            val modelBuffer = loadModelFile(MODEL_FILENAME)

            Logger.d("Model loaded into buffer. Size: ${modelBuffer.capacity()} bytes")

            val options = Interpreter.Options()
            // Use CPU for training (Select TF Ops usually require CPU delegate or default)
            interpreter = Interpreter(modelBuffer, options)

            Logger.d("Interpreter initialized successfully.")
        } catch (e: Exception) {
            Logger.e("Error initializing model: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun loadModelFile(filename: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // --------------------------------------------------------------------------------
    // Training
    // --------------------------------------------------------------------------------

    fun runTrainingSession(
        trainingData: List<List<Float>>, // <-- pass your dataset here
        epochs: Int = AuthConfigManager.config.trainingEpochs,
        batchSize: Int = AuthConfigManager.config.trainingBatchSize
    ) {
        val interpreter = interpreter ?: run {
            Logger.e("Training aborted: Interpreter is null.")
            return
        }

        val signatureKeys = interpreter.getSignatureKeys()
        Logger.d("Available Signatures: ${signatureKeys.joinToString(", ")}")

        // 1. Factory Reset (init_model)
        if (signatureKeys.contains(SIG_INIT)) {
            Logger.d("Running initialization signature: $SIG_INIT")
            try {
                val x = floatArrayOf(1.0f) // Dummy input
                val inputs: MutableMap<String, Any> = hashMapOf("x" to x)
                val outputs: MutableMap<String, Any> = hashMapOf(OUTPUT_STATUS to FloatBuffer.allocate(1))

                interpreter.runSignature(inputs, outputs, SIG_INIT)
                Logger.d("Initialization successful.")
            } catch (e: Exception) {
                Logger.e("Failed to run initialization: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Logger.e("WARNING: No '$SIG_INIT' signature found. Variables might not be initialized!")
        }

        Logger.d("Starting Training Session. Epochs: $epochs, BatchSize: $batchSize, Total Samples: ${trainingData.size}")
        val startTime = System.currentTimeMillis()

        // 2. Prepare Batches from trainingData (discard incomplete batches)
        val trainBatches = ArrayList<FloatBuffer>()
        try {
            val numFullBatches = trainingData.size / batchSize // only full batches
            for (i in 0 until numFullBatches) {
                val startIdx = i * batchSize
                val endIdx = startIdx + batchSize
                val batch = trainingData.subList(startIdx, endIdx)

                val buffer = FloatBuffer.allocate(batch.size * INPUT_DIM)
                for (sample in batch) {
                    require(sample.size == INPUT_DIM) { "Each sample must have size $INPUT_DIM" }
                    buffer.put(sample.toFloatArray())
                }
                buffer.rewind()
                trainBatches.add(buffer)
            }
        } catch (e: Exception) {
            Logger.e("Error during batch preparation: ${e.message}")
            return
        }

        Logger.d("Total full batches: ${trainBatches.size}")

        // 3. Training Loop
        val losses = FloatArray(epochs)
        val lossOutputBuffer = FloatBuffer.allocate(1)
        val inputs: MutableMap<String, Any> = HashMap()
        val outputs: MutableMap<String, Any> = HashMap()

        try {
            for (epoch in 0 until epochs) {
                for (batchIdx in trainBatches.indices) {
                    val inputBatch = trainBatches[batchIdx]
                    inputBatch.rewind()
                    lossOutputBuffer.rewind()

                    inputs[INPUT_KEY] = inputBatch
                    outputs[OUTPUT_LOSS] = lossOutputBuffer

                    interpreter.runSignature(inputs, outputs, SIG_TRAIN)

                    if (batchIdx == trainBatches.lastIndex) {
                        losses[epoch] = lossOutputBuffer.get(0)
                    }
                }
                Logger.d("Epoch ${epoch + 1}/$epochs. Loss: ${losses[epoch]}")
            }

            val totalTime = System.currentTimeMillis() - startTime
            Logger.d("Training Complete. Final Loss: ${losses.last()}. Total time: ${totalTime}ms")

        } catch (e: Exception) {
            Logger.e("Training failed: ${e.message}")
            e.printStackTrace()
        }
    }

    // --------------------------------------------------------------------------------
    // Save & Restore (Checkpointing)
    // --------------------------------------------------------------------------------

    /**
     * Extracts weights from the model and saves them to a file.
     */
    fun saveCheckpoint(checkpointFile: File): Boolean {
        val interpreter = interpreter ?: return false
        Logger.d("Saving checkpoint to: ${checkpointFile.absolutePath}")

        try {
            // 1. Prepare Outputs map for "save" signature
            // The signature returns a dictionary where keys are "val_0", "val_1", etc.
            // We need to inspect the signature outputs to allocate the correct buffer sizes.
            val signatureOutputs = interpreter.getSignatureOutputs(SIG_SAVE)
            val outputs: MutableMap<String, Any> = HashMap()

            // Map to hold the resulting float arrays for serialization
            val weightsMap = HashMap<String, FloatArray>()

            for (outputName in signatureOutputs) {
                // Get the tensor details to find shape
                val tensor = interpreter.getOutputTensorFromSignature(outputName, SIG_SAVE)
                val shape = tensor.shape() // e.g. [12, 20]

                // Calculate total elements
                var totalElements = 1
                for (dim in shape) {
                    totalElements *= dim
                }

                // Allocate buffer
                val buffer = FloatBuffer.allocate(totalElements)
                outputs[outputName] = buffer
            }

            // 2. Run the SAVE signature
            // FIX: Pass the required dummy input 'x' to prevent TFLite runtime crash.
            val inputs: MutableMap<String, Any> = HashMap()
            inputs["x"] = floatArrayOf(1.0f)

            interpreter.runSignature(inputs, outputs, SIG_SAVE)

            // 3. Extract data from Buffers into Arrays for serialization
            for ((key, value) in outputs) {
                val buffer = value as FloatBuffer
                buffer.rewind()
                val floatArray = FloatArray(buffer.capacity())
                buffer.get(floatArray)
                weightsMap[key] = floatArray
            }

            // 4. Write to disk using ObjectOutputStream
            FileOutputStream(checkpointFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(weightsMap)
                }
            }

            Logger.d("Checkpoint saved successfully. Saved ${weightsMap.size} tensors.")
            return true

        } catch (e: Exception) {
            Logger.e("Failed to save checkpoint: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Loads weights from a file and injects them into the model.
     */
    fun loadCheckpoint(checkpointFile: File): Boolean {
        val interpreter = interpreter ?: return false
        Logger.d("Loading checkpoint from: ${checkpointFile.absolutePath}")

        if (!checkpointFile.exists()) {
            Logger.e("Checkpoint file does not exist.")
            return false
        }

        try {
            // 1. Read Map from disk
            var loadedWeights: HashMap<String, FloatArray>? = null
            FileInputStream(checkpointFile).use { fis ->
                ObjectInputStream(fis).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    loadedWeights = ois.readObject() as? HashMap<String, FloatArray>
                }
            }

            if (loadedWeights == null) {
                Logger.e("Failed to deserialize weights.")
                return false
            }

            // 2. Prepare Inputs for "restore" signature
            val inputs: MutableMap<String, Any> = HashMap()

            for ((key, array) in loadedWeights!!) {
                // Convert FloatArray back to FloatBuffer for TFLite
                val buffer = FloatBuffer.wrap(array)
                inputs[key] = buffer
            }

            // 3. Prepare Output (Status)
            val outputs: MutableMap<String, Any> = HashMap()
            val statusBuffer = FloatBuffer.allocate(1)
            outputs[OUTPUT_STATUS] = statusBuffer

            // 4. Run RESTORE signature
            interpreter.runSignature(inputs, outputs, SIG_RESTORE)

            Logger.d("Checkpoint loaded successfully. Status: ${statusBuffer.get(0)}")
            return true

        } catch (e: Exception) {
            Logger.e("Failed to load checkpoint: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // --------------------------------------------------------------------------------
    // Inference
    // --------------------------------------------------------------------------------
    /**
     * Perform inference on a single feature vector and return a score.
     * @param featureVector The input feature vector of size INPUT_DIM
     * @return The reconstruction/error score, or null if inference failed
     */
    fun inferScore(featureVector: List<Float>): Float? {
        val interpreter = interpreter ?: run {
            Logger.e("Interpreter is null. Cannot perform inference.")
            return null
        }

        if (featureVector.size != INPUT_DIM) {
            Logger.e("Feature vector size ${featureVector.size} does not match expected INPUT_DIM $INPUT_DIM")
            return null
        }

        return try {
            // --------------------------
            // Prepare input buffer (batch size 1)
            // --------------------------
            val inputBuffer = FloatBuffer.allocate(1 * INPUT_DIM)
            inputBuffer.put(featureVector.toFloatArray())
            inputBuffer.rewind()

            // --------------------------
            // Prepare output buffers
            // reconstruction_error is a scalar per sample (shape [1])
            // --------------------------
            val reconstructionErrorBuffer = FloatBuffer.allocate(1)
            val reconstructionBuffer = FloatBuffer.allocate(INPUT_DIM) // optional if you need reconstructed vector

            val inputs: MutableMap<String, Any> = hashMapOf(INPUT_KEY to inputBuffer)
            val outputs: MutableMap<String, Any> = hashMapOf(
                OUTPUT_RECONSTRUCTION to reconstructionBuffer,
                RECONSTRUCTION_ERROR_KEY to reconstructionErrorBuffer // Use the same key as your Python signature
            )

            // --------------------------
            // Run inference
            // --------------------------
            interpreter.runSignature(inputs, outputs, SIG_INFER)

            // --------------------------
            // Get reconstruction error
            // --------------------------
            reconstructionErrorBuffer.rewind()
            reconstructionErrorBuffer.get(0) // return scalar error

        } catch (e: Exception) {
            Logger.e("Inference failed: ${e.message}", e)
            null
        }
    }


    /**
     * Deletes the checkpoint file from storage.
     */
    fun deleteCheckpoint(checkpointFile: File): Boolean {
        Logger.d("Attempting to delete checkpoint: ${checkpointFile.absolutePath}")

        return try {
            if (!checkpointFile.exists()) {
                Logger.e("Delete failed: Checkpoint does not exist.")
                false
            } else {
                val deleted = checkpointFile.delete()
                if (deleted) {
                    Logger.d("Checkpoint deleted successfully.")
                } else {
                    Logger.e("Failed to delete checkpoint file.")
                }
                deleted
            }
        } catch (e: Exception) {
            Logger.e("Error deleting checkpoint: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Checks if the checkpoint file exists on disk.
     */
    fun isCheckpointExists(checkpointFile: File): Boolean {
        val exists = checkpointFile.exists()
        if (exists) {
            Logger.d("Checkpoint exists: ${checkpointFile.absolutePath}")
        } else {
            Logger.d("Checkpoint does NOT exist: ${checkpointFile.absolutePath}")
        }
        return exists
    }

    fun close() {
        Logger.d("Closing interpreter resources.")
        interpreter?.close()
        interpreter = null
    }
}