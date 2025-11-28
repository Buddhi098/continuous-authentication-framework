package com.ca.continuousauth.core.authmodel

import android.content.Context
import android.content.res.AssetFileDescriptor
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
import java.util.HashMap

/**
 * Manages the TFLite Autoencoder model for both Inference, On-Device Training,
 * and Checkpoint Management (Save/Restore).
 */
class AuthModel(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME = "model.tflite" // Updated filename
        private const val INPUT_DIM = 20

        // Signature Keys (Must match Python export)
        private const val SIG_TRAIN = "train"
        private const val SIG_INFER = "infer"
        private const val SIG_INIT = "init_model" // Renamed from 'restore' in Python
        private const val SIG_SAVE = "save"       // New
        private const val SIG_RESTORE = "restore" // New

        // Tensor Names
        private const val INPUT_KEY = "inputs"
        private const val OUTPUT_RECONSTRUCTION = "reconstruction"
        private const val OUTPUT_LOSS = "loss"
        private const val OUTPUT_STATUS = "status"
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

    fun runTrainingSession(epochs: Int = 3, batchSize: Int = 1, numTrainings: Int = 60) {
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
                // Inputs: 'x' (dummy scalar)
                val x = floatArrayOf(1.0f)
                val inputs: MutableMap<String, Any> = HashMap()
                inputs["x"] = x

                // Outputs: 'status'
                val outputs: MutableMap<String, Any> = HashMap()
                val statusBuffer = FloatBuffer.allocate(1)
                outputs[OUTPUT_STATUS] = statusBuffer

                interpreter.runSignature(inputs, outputs, SIG_INIT)
                Logger.d("Initialization successful.")
            } catch (e: Exception) {
                Logger.e("Failed to run initialization: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Logger.e("WARNING: No '$SIG_INIT' signature found. Variables might not be initialized!")
        }

        Logger.d("Starting Training Session. Epochs: $epochs, BatchSize: $batchSize, Total Samples: $numTrainings")
        val startTime = System.currentTimeMillis()

        // 2. Prepare Data
        val numBatches = numTrainings / batchSize
        val trainBatches = ArrayList<FloatBuffer>(numBatches)

        try {
            for (i in 0 until numBatches) {
                val buffer = FloatBuffer.allocate(batchSize * INPUT_DIM)
                // Fill with random sensor data (Simulation)
                for (j in 0 until batchSize * INPUT_DIM) {
                    buffer.put(Random.nextFloat())
                }
                buffer.rewind()
                trainBatches.add(buffer)
            }
        } catch (e: Exception) {
            Logger.e("Error during batch allocation: ${e.message}")
            return
        }

        // 3. Training Loop
        val losses = FloatArray(epochs)
        val lossOutputBuffer = FloatBuffer.allocate(1)
        val inputs: MutableMap<String, Any> = HashMap()
        val outputs: MutableMap<String, Any> = HashMap()

        try {
            for (epoch in 0 until epochs) {
                for (batchIdx in 0 until numBatches) {
                    val inputBatch = trainBatches[batchIdx]
                    inputBatch.rewind()
                    lossOutputBuffer.rewind()

                    inputs[INPUT_KEY] = inputBatch
                    outputs[OUTPUT_LOSS] = lossOutputBuffer

                    interpreter.runSignature(inputs, outputs, SIG_TRAIN)

                    if (batchIdx == numBatches - 1) {
                        losses[epoch] = lossOutputBuffer.get(0)
                    }
                }
                if ((epoch + 1) % 10 == 0) {
                    Logger.d("Epoch ${epoch + 1}/$epochs. Loss: ${losses[epoch]}")
                }
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
    fun infer(inputData: FloatBuffer, batchSize: Int): Array<FloatArray>? {
        val interpreter = interpreter ?: return null
        // Logger.d("Starting inference. Batch Size: $batchSize")

        val outputData = Array(batchSize) { FloatArray(INPUT_DIM) }
        val inputs: MutableMap<String, Any> = HashMap()
        val outputs: MutableMap<String, Any> = HashMap()

        try {
            inputData.rewind()
            inputs[INPUT_KEY] = inputData
            outputs[OUTPUT_RECONSTRUCTION] = outputData

            interpreter.runSignature(inputs, outputs, SIG_INFER)
            return outputData
        } catch (e: Exception) {
            Logger.e("Inference failed: ${e.message}")
            return null
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