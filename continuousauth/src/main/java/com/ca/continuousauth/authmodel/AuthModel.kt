package com.ca.continuousauth.authmodel

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.tensorflow.lite.Interpreter

/**
 * Manages the TFLite Autoencoder model for both Inference, On-Device Training, and Checkpoint
 * Management (Save/Restore).
 *
 * Thread-Safety: All public methods accessing the interpreter are thread-safe via [ReentrantLock].
 * Performance: Reuses standard buffers for inference to minimize allocation overhead.
 */
class AuthModel(private val context: Context) : AutoCloseable {

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
        private val RECONSTRUCTION_ERROR_KEY = AuthConfigManager.config.outputReconstructionError

        // Constants used for internal logic
        private const val DUMMY_INPUT_KEY = "x"
    }

    private val lock = ReentrantLock()
    private var interpreter: Interpreter? = null

    // Pre-allocated buffers for Inference to avoid allocation in hot path
    // These are protected by [lock]
    private val inferenceInputBuffer: FloatBuffer by lazy { FloatBuffer.allocate(1 * INPUT_DIM) }
    private val inferenceReconstructionBuffer: FloatBuffer by lazy {
        FloatBuffer.allocate(INPUT_DIM)
    }
    private val inferenceErrorBuffer: FloatBuffer by lazy { FloatBuffer.allocate(1) }

    // Reusable maps for inference inputs/outputs
    private val inferenceInputs: MutableMap<String, Any> = HashMap()
    private val inferenceOutputs: MutableMap<String, Any> = HashMap()

    init {
        Logger.d("Initializing AuthModel...")
        initializeInterpreter()
        setupInferenceMaps()
    }

    private fun setupInferenceMaps() {
        inferenceInputs[INPUT_KEY] = inferenceInputBuffer
        inferenceOutputs[OUTPUT_RECONSTRUCTION] = inferenceReconstructionBuffer
        inferenceOutputs[RECONSTRUCTION_ERROR_KEY] = inferenceErrorBuffer
    }

    private fun initializeInterpreter() {
        lock.withLock {
            try {
                if (interpreter != null) return

                Logger.d("Attempting to load model file: $MODEL_FILENAME")
                val modelBuffer = loadModelFile(MODEL_FILENAME)

                Logger.d("Model loaded into buffer. Size: ${modelBuffer.capacity()} bytes")

                val options = Interpreter.Options()
                // Use CPU for training (Select TF Ops usually require CPU delegate or default)
                interpreter = Interpreter(modelBuffer, options)

                Logger.d("Interpreter initialized successfully.")
            } catch (e: Exception) {
                Logger.e("Error initializing model: ${e.message}", e)
            }
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
            trainingData: List<List<Float>>,
            epochs: Int = AuthConfigManager.config.trainingEpochs,
            batchSize: Int = AuthConfigManager.config.trainingBatchSize
    ) {
        lock.withLock {
            val interpreter =
                    interpreter
                            ?: run {
                                Logger.e("Training aborted: Interpreter is null.")
                                return
                            }

            try {
                val signatureKeys = interpreter.getSignatureKeys()
                Logger.d("Available Signatures: ${signatureKeys.joinToString(", ")}")

                // 1. Factory Reset (init_model)
                runInitialization(interpreter, signatureKeys)

                Logger.d(
                        "Starting Training Session. Epochs: $epochs, BatchSize: $batchSize, Total Samples: ${trainingData.size}"
                )
                val startTime = System.currentTimeMillis()

                // 2. Prepare Batches
                val trainBatches = prepareTrainingBatches(trainingData, batchSize)
                if (trainBatches.isEmpty()) {
                    Logger.e("No full batches available for training.")
                    return
                }

                Logger.d("Total full batches: ${trainBatches.size}")

                // 3. Training Loop
                val losses = performTrainingLoop(interpreter, trainBatches, epochs)

                val totalTime = System.currentTimeMillis() - startTime
                Logger.d(
                        "Training Complete. Final Loss: ${losses.lastOrNull() ?: 0f}. Total time: ${totalTime}ms"
                )
            } catch (e: Exception) {
                Logger.e("Training failed: ${e.message}", e)
            }
        }
    }

    private fun runInitialization(interpreter: Interpreter, signatureKeys: Array<String>) {
        if (signatureKeys.contains(SIG_INIT)) {
            Logger.d("Running initialization signature: $SIG_INIT")
            try {
                val x = floatArrayOf(1.0f) // Dummy input
                val inputs: MutableMap<String, Any> = hashMapOf(DUMMY_INPUT_KEY to x)
                val outputs: MutableMap<String, Any> =
                        hashMapOf(OUTPUT_STATUS to FloatBuffer.allocate(1))

                interpreter.runSignature(inputs, outputs, SIG_INIT)
                Logger.d("Initialization successful.")
            } catch (e: Exception) {
                Logger.e("Failed to run initialization: ${e.message}")
                throw e
            }
        } else {
            Logger.e("WARNING: No '$SIG_INIT' signature found. Variables might not be initialized!")
        }
    }

    private fun prepareTrainingBatches(
            trainingData: List<List<Float>>,
            batchSize: Int
    ): List<FloatBuffer> {
        val trainBatches = ArrayList<FloatBuffer>()
        val numFullBatches = trainingData.size / batchSize

        for (i in 0 until numFullBatches) {
            val startIdx = i * batchSize
            val endIdx = startIdx + batchSize
            val batch = trainingData.subList(startIdx, endIdx)

            val buffer = FloatBuffer.allocate(batch.size * INPUT_DIM)
            for (sample in batch) {
                // Safe check handled by Kotlin list access, but dimension check is good
                if (sample.size != INPUT_DIM) {
                    throw IllegalArgumentException(
                            "Sample at index matches batch but has wrong dim: ${sample.size} vs $INPUT_DIM"
                    )
                }
                buffer.put(sample.toFloatArray())
            }
            buffer.rewind()
            trainBatches.add(buffer)
        }
        return trainBatches
    }

    private fun performTrainingLoop(
            interpreter: Interpreter,
            trainBatches: List<FloatBuffer>,
            epochs: Int
    ): FloatArray {
        val losses = FloatArray(epochs)
        val lossOutputBuffer = FloatBuffer.allocate(1)
        val inputs: MutableMap<String, Any> = HashMap()
        val outputs: MutableMap<String, Any> = HashMap()

        // Reuse map objects as much as possible, though putting new buffers is necessary
        outputs[OUTPUT_LOSS] = lossOutputBuffer

        for (epoch in 0 until epochs) {
            for (batchIdx in trainBatches.indices) {
                val inputBatch = trainBatches[batchIdx]
                inputBatch.rewind()
                lossOutputBuffer.rewind()

                inputs[INPUT_KEY] = inputBatch

                interpreter.runSignature(inputs, outputs, SIG_TRAIN)

                if (batchIdx == trainBatches.lastIndex) {
                    losses[epoch] = lossOutputBuffer.get(0)
                }
            }
            // Log every epoch might be too verbose if epochs is large, consider interval
            if ((epoch + 1) % 5 == 0 || epoch == 0 || epoch == epochs - 1) {
                Logger.d("Epoch ${epoch + 1}/$epochs. Loss: ${losses[epoch]}")
            }
        }
        return losses
    }

    // --------------------------------------------------------------------------------
    // Save & Restore (Checkpointing)
    // --------------------------------------------------------------------------------

    fun saveCheckpoint(checkpointFile: File): Boolean {
        lock.withLock {
            val interpreter = interpreter ?: return false
            Logger.d("Saving checkpoint to: ${checkpointFile.absolutePath}")

            try {
                // 1. Prepare Outputs map for "save" signature
                val signatureOutputs = interpreter.getSignatureOutputs(SIG_SAVE)
                val outputs: MutableMap<String, Any> = HashMap()
                val weightsMap = HashMap<String, FloatArray>()

                for (outputName in signatureOutputs) {
                    val tensor = interpreter.getOutputTensorFromSignature(outputName, SIG_SAVE)
                    val totalElements = tensor.shape().fold(1) { acc, dim -> acc * dim }
                    outputs[outputName] = FloatBuffer.allocate(totalElements)
                }

                // 2. Run the SAVE signature
                val inputs: MutableMap<String, Any> =
                        hashMapOf(DUMMY_INPUT_KEY to floatArrayOf(1.0f))
                interpreter.runSignature(inputs, outputs, SIG_SAVE)

                // 3. Extract data
                for ((key, value) in outputs) {
                    val buffer = value as FloatBuffer
                    buffer.rewind()
                    val floatArray = FloatArray(buffer.capacity())
                    buffer.get(floatArray)
                    weightsMap[key] = floatArray
                }

                // 4. Write to disk
                FileOutputStream(checkpointFile).use { fos ->
                    ObjectOutputStream(fos).use { oos -> oos.writeObject(weightsMap) }
                }

                Logger.d("Checkpoint saved successfully. Saved ${weightsMap.size} tensors.")
                return true
            } catch (e: Exception) {
                Logger.e("Failed to save checkpoint: ${e.message}", e)
                return false
            }
        }
    }

    fun loadCheckpoint(checkpointFile: File): Boolean {
        lock.withLock {
            val interpreter = interpreter ?: return false
            Logger.d("Loading checkpoint from: ${checkpointFile.absolutePath}")

            if (!checkpointFile.exists()) {
                Logger.e("Checkpoint file does not exist.")
                return false
            }

            try {
                // 1. Read Map from disk
                val loadedWeights: HashMap<String, FloatArray>? =
                        FileInputStream(checkpointFile).use { fis ->
                            ObjectInputStream(fis).use { ois ->
                                @Suppress("UNCHECKED_CAST")
                                ois.readObject() as? HashMap<String, FloatArray>
                            }
                        }

                if (loadedWeights == null) {
                    Logger.e("Failed to deserialize weights.")
                    return false
                }

                // 2. Prepare Inputs for "restore" signature
                val inputs: MutableMap<String, Any> = HashMap()
                for ((key, array) in loadedWeights) {
                    inputs[key] = FloatBuffer.wrap(array)
                }

                // 3. Prepare Output (Status)
                val statusBuffer = FloatBuffer.allocate(1)
                val outputs: MutableMap<String, Any> = hashMapOf(OUTPUT_STATUS to statusBuffer)

                // 4. Run RESTORE signature
                interpreter.runSignature(inputs, outputs, SIG_RESTORE)

                Logger.d("Checkpoint loaded successfully. Status: ${statusBuffer.get(0)}")
                return true
            } catch (e: Exception) {
                Logger.e("Failed to load checkpoint: ${e.message}", e)
                return false
            }
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
        // Lightweight check before lock
        if (featureVector.size != INPUT_DIM) {
            Logger.e(
                    "Feature vector size ${featureVector.size} does not match expected INPUT_DIM $INPUT_DIM"
            )
            return null
        }

        lock.withLock {
            val interpreter =
                    interpreter
                            ?: run {
                                Logger.e("Interpreter is null. Cannot perform inference.")
                                return null
                            }

            return try {
                // --------------------------
                // reuse pre-allocated buffers
                // --------------------------
                inferenceInputBuffer.clear()
                // Manual put loop or toFloatArray needed. toFloatArray creates garbage,
                // but List doesn't have a bulk put to Buffer.
                // Optimally we'd iterate and put, but toFloatArray is typical in Android.
                // To avoid alloc, we could loop:
                for (f in featureVector) {
                    inferenceInputBuffer.put(f)
                }
                inferenceInputBuffer.rewind()

                inferenceReconstructionBuffer.clear()
                inferenceErrorBuffer.clear()

                // inputs/outputs maps are already set up in init() pointing to these buffers

                // --------------------------
                // Run inference
                // --------------------------
                interpreter.runSignature(inferenceInputs, inferenceOutputs, SIG_INFER)

                // --------------------------
                // Get reconstruction error
                // --------------------------
                inferenceErrorBuffer.rewind()
                inferenceErrorBuffer.get(0) // return scalar error
            } catch (e: Exception) {
                Logger.e("Inference failed: ${e.message}", e)
                null
            }
        }
    }

    // --------------------------------------------------------------------------------
    // Utils & Cleanup
    // --------------------------------------------------------------------------------

    fun deleteCheckpoint(checkpointFile: File): Boolean {
        Logger.d("Attempting to delete checkpoint: ${checkpointFile.absolutePath}")
        return try {
            if (!checkpointFile.exists()) {
                Logger.e("Delete failed: Checkpoint does not exist.")
                false
            } else {
                val deleted = checkpointFile.delete()
                if (deleted) Logger.d("Checkpoint deleted successfully.")
                else Logger.e("Failed to delete checkpoint file.")
                deleted
            }
        } catch (e: Exception) {
            Logger.e("Error deleting checkpoint: ${e.message}", e)
            false
        }
    }

    fun isCheckpointExists(checkpointFile: File): Boolean {
        return checkpointFile.exists().also { exists ->
            Logger.d("Checkpoint exists (${checkpointFile.absolutePath}): $exists")
        }
    }

    override fun close() {
        lock.withLock {
            Logger.d("Closing interpreter resources.")
            interpreter?.close()
            interpreter = null
        }
    }
}
