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
import com.ca.continuousauth.security.SecureModelStorage
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType

/**
 * Manages the TFLite Autoencoder model for Inference, On-Device Training, and Checkpointing.
 *
 * Thread-Safety: All public methods accessing the interpreter are thread-safe via [ReentrantLock].
 * Shape Agnostic: Dynamically calculates total required elements, supporting both 2D (batch, features)
 * and 4D (batch, features, window, channel) tensor shapes.
 */
class AuthModel(
    private val context: Context,
    private val modelFileName: String,
    private val fallbackInputDim: Int
) : AutoCloseable {

    companion object {
        // Signature Keys
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

        private const val DUMMY_INPUT_KEY = "x"
    }

    private val rwLock = ReentrantReadWriteLock()
    private var interpreter: Interpreter? = null

    // Dynamically calculated total element counts based on model shapes
    private var inferenceSampleElements: Int = fallbackInputDim * AuthConfigManager.config.windowSize
    private var inferenceOutputElements: Int = fallbackInputDim * AuthConfigManager.config.windowSize
    private var trainBatchElements: Int = -1
    private var modelRequiredBatchSize: Int = AuthConfigManager.config.trainingBatchSize

    // Buffers allocated after dynamic shape extraction
    private var inferenceInputBuffer: FloatBuffer? = null
    private var inferenceReconstructionBuffer: FloatBuffer? = null
    private var inferenceErrorBuffer: FloatBuffer? = null

    private val inferenceInputs: MutableMap<String, Any> = HashMap()
    private val inferenceOutputs: MutableMap<String, Any> = HashMap()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            initializeInterpreter()
        }
    }

    private fun initializeInterpreter() {
        rwLock.write {
            try {
                if (interpreter != null) return

                Logger.d("Loading model: $modelFileName")
                val modelBuffer = loadModelFile(modelFileName)

                val options = Interpreter.Options().apply {
                    setNumThreads(Runtime.getRuntime().availableProcessors())
                    setUseXNNPACK(true)
                }
                interpreter = Interpreter(modelBuffer, options)

                extractTensorDimensions(interpreter!!)
                allocateInferenceBuffers()

                Logger.d("Interpreter initialized successfully.")
            } catch (e: Exception) {
                Logger.e("Error initializing model: ${e.message}", e)
            }
        }
    }

    /**
     * Unrolls multi-dimensional tensor shapes (e.g., [batch, feature, window, channel])
     * into a single flat element count required for Java/Kotlin byte buffers.
     */
    private fun extractTensorDimensions(interpreter: Interpreter) {
        try {
            // 1. Inference Dimensions
            val inferInputTensor = interpreter.getInputTensorFromSignature(INPUT_KEY, SIG_INFER)
            val inferOutputTensor = interpreter.getOutputTensorFromSignature(OUTPUT_RECONSTRUCTION, SIG_INFER)

            // Compute per-sample elements by skipping batch dim (index 0)
            // Shape is typically [batch, feature_dim, seq_len, channel]
            val inferInputShape = inferInputTensor.shape()
            val inferOutputShape = inferOutputTensor.shape()
            inferenceSampleElements = inferInputShape.drop(1).fold(1) { acc, dim -> acc * if (dim > 0) dim else 1 }
            inferenceOutputElements = inferOutputShape.drop(1).fold(1) { acc, dim -> acc * if (dim > 0) dim else 1 }

            Logger.d("Inference elements per sample: Input=$inferenceSampleElements (shape=${inferInputShape.toList()}), Output=$inferenceOutputElements (shape=${inferOutputShape.toList()})")

            // 2. Training Dimensions (Used to strictly enforce batch sizes)
            try {
                val trainInputTensor = interpreter.getInputTensorFromSignature(INPUT_KEY, SIG_TRAIN)
                val trainInputShape = trainInputTensor.shape()
                trainBatchElements = trainInputShape.fold(1) { acc, dim -> acc * if (dim > 0) dim else 1 }

                // Calculate the exact batch size the model requires
                if (inferenceSampleElements > 0 && trainBatchElements > 0) {
                    modelRequiredBatchSize = trainBatchElements / inferenceSampleElements
                    Logger.d("Training tensor expects $trainBatchElements elements. Enforced batch size: $modelRequiredBatchSize")
                }
            } catch (e: Exception) {
                Logger.e("Warning: Could not extract training signature shapes. Relying on config limits.", e)
            }

        } catch (e: Exception) {
            Logger.e("Failed to extract shapes dynamically. Falling back to inputDim=$fallbackInputDim. Error: ${e.message}")
            inferenceSampleElements = fallbackInputDim * AuthConfigManager.config.windowSize
            inferenceOutputElements = fallbackInputDim * AuthConfigManager.config.windowSize
        }
    }

    private fun allocateInferenceBuffers() {
        inferenceInputBuffer = FloatBuffer.allocate(inferenceSampleElements)
        inferenceReconstructionBuffer = FloatBuffer.allocate(inferenceOutputElements)
        inferenceErrorBuffer = FloatBuffer.allocate(1)

        inferenceInputs[INPUT_KEY] = inferenceInputBuffer!!
        inferenceOutputs[OUTPUT_RECONSTRUCTION] = inferenceReconstructionBuffer!!
        inferenceOutputs[RECONSTRUCTION_ERROR_KEY] = inferenceErrorBuffer!!
    }

    private fun loadModelFile(filename: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    // --------------------------------------------------------------------------------
    // Training
    // --------------------------------------------------------------------------------

    fun runTrainingSession(
        trainingData: List<List<Float>>,
        epochs: Int ,
        requestedBatchSize: Int = AuthConfigManager.config.trainingBatchSize
    ) {
        rwLock.write {
            val interpreter = interpreter ?: run {
                Logger.e("Training aborted: Interpreter is null.")
                return
            }

            if (trainingData.isEmpty()) {
                Logger.e("Training aborted: No training data provided.")
                return
            }

            // 1. Validate Input Data Shape
            val actualSampleElements = trainingData.first().size
            if (actualSampleElements != inferenceSampleElements && actualSampleElements != fallbackInputDim) {
                Logger.e("Shape mismatch: Training data has $actualSampleElements elements per sample, " +
                        "but model expects $inferenceSampleElements elements. Are your features properly flattened?")
                return
            }

            // 2. Override requested batch size with the model's compiled batch size requirement
            val effectiveBatchSize = if (trainBatchElements > 0) modelRequiredBatchSize else requestedBatchSize

            try {
                val signatureKeys = interpreter.getSignatureKeys()
                runInitialization(interpreter, signatureKeys)

                Logger.d("Starting Training Session. Epochs: $epochs, BatchSize: $effectiveBatchSize, Total Samples: ${trainingData.size}")
                val startTime = System.currentTimeMillis()

                val trainBatches = prepareTrainingBatches(trainingData, effectiveBatchSize, actualSampleElements)
                if (trainBatches.isEmpty()) {
                    Logger.e("No full batches available for training.")
                    return
                }

                val losses = performTrainingLoop(interpreter, trainBatches, epochs)

                val totalTime = System.currentTimeMillis() - startTime
                Logger.d("Training Complete. Final Loss: ${losses.lastOrNull() ?: 0f}. Total time: ${totalTime}ms")
            } catch (e: Exception) {
                Logger.e("Training failed: ${e.message}", e)
            }
        }
    }

    private fun runInitialization(interpreter: Interpreter, signatureKeys: Array<String>) {
        if (signatureKeys.contains(SIG_INIT)) {
            try {
                val inputs: MutableMap<String, Any> = hashMapOf(DUMMY_INPUT_KEY to floatArrayOf(1.0f))
                val outputs: MutableMap<String, Any> = HashMap()

                try {
                    if (interpreter.getSignatureOutputs(SIG_INIT).contains(OUTPUT_STATUS)) {
                        val tensor = interpreter.getOutputTensorFromSignature(OUTPUT_STATUS, SIG_INIT)
                        if (tensor.dataType() == DataType.FLOAT32) {
                            outputs[OUTPUT_STATUS] = FloatBuffer.allocate(1)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore dummy status tensor
                }

                interpreter.runSignature(inputs, outputs, SIG_INIT)
            } catch (e: Exception) {
                Logger.e("Failed to run initialization: ${e.message}")
                throw e
            }
        }
    }

    private fun prepareTrainingBatches(
        trainingData: List<List<Float>>,
        batchSize: Int,
        sampleElements: Int
    ): List<FloatBuffer> {
        val trainBatches = ArrayList<FloatBuffer>()
        val numFullBatches = trainingData.size / batchSize

        // Total elements expected per buffer execution
        val elementsPerBatch = batchSize * sampleElements

        for (i in 0 until numFullBatches) {
            val startIdx = i * batchSize

            val buffer = FloatBuffer.allocate(elementsPerBatch)
            for (j in 0 until batchSize) {
                val sample = trainingData[startIdx + j]
                // Copy directly from List<Float> into buffer without intermediate array
                for (value in sample) {
                    buffer.put(value)
                }
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
        val inputs: MutableMap<String, Any> = HashMap()
        val outputs: MutableMap<String, Any> = HashMap()

        var lossOutputBuffer: FloatBuffer? = null

        // Safely resolve the loss tensor to prevent "DataType 0" crash on control edges
        try {
            if (interpreter.getSignatureOutputs(SIG_TRAIN).contains(OUTPUT_LOSS)) {
                val tensor = interpreter.getOutputTensorFromSignature(OUTPUT_LOSS, SIG_TRAIN)
                if (tensor.dataType() == DataType.FLOAT32) {
                    val elements = tensor.shape().fold(1) { acc, dim -> acc * max(1, dim) }
                    lossOutputBuffer = FloatBuffer.allocate(elements)
                    outputs[OUTPUT_LOSS] = lossOutputBuffer
                }
            }
        } catch (e: Exception) {
            Logger.d("Loss tensor omitted due to incompatible type (e.g., control edge): ${e.message}")
        }

        for (epoch in 0 until epochs) {
            var epochLossSum = 0f

            for (batchIdx in trainBatches.indices) {
                val inputBatch = trainBatches[batchIdx]
                inputBatch.rewind()
                lossOutputBuffer?.rewind()

                inputs[INPUT_KEY] = inputBatch
                interpreter.runSignature(inputs, outputs, SIG_TRAIN)

                if (lossOutputBuffer != null) {
                    epochLossSum += lossOutputBuffer.get(0)
                }
            }

            losses[epoch] = if (lossOutputBuffer != null && trainBatches.isNotEmpty()) {
                epochLossSum / trainBatches.size
            } else {
                0f
            }

            if ((epoch + 1) % 5 == 0 || epoch == 0 || epoch == epochs - 1) {
                Logger.d("Epoch ${epoch + 1}/$epochs. Avg Loss: ${losses[epoch]}")
            }
        }
        return losses
    }

    // --------------------------------------------------------------------------------
    // Inference
    // --------------------------------------------------------------------------------

    fun inferScore(featureVector: List<Float>): Float? {
        rwLock.write {
            val interpreter = interpreter ?: return null
            val inputBuffer = inferenceInputBuffer ?: return null
            val reconBuffer = inferenceReconstructionBuffer ?: return null
            val errBuffer = inferenceErrorBuffer ?: return null

            if (featureVector.size != inferenceSampleElements && featureVector.size != fallbackInputDim) {
                Logger.e("Inference failed: Vector size ${featureVector.size} != expected $inferenceSampleElements")
                return null
            }

            return try {
                inputBuffer.clear()
                for (f in featureVector) {
                    inputBuffer.put(f)
                }
                inputBuffer.rewind()
                reconBuffer.clear()
                errBuffer.clear()

                interpreter.runSignature(inferenceInputs, inferenceOutputs, SIG_INFER)

                errBuffer.rewind()
                errBuffer.get(0)
            } catch (e: Exception) {
                Logger.e("Inference failed: ${e.message}", e)
                null
            }
        }
    }

    // --------------------------------------------------------------------------------
    // Checkpointing & Cleanup
    // --------------------------------------------------------------------------------

    fun saveCheckpoint(checkpointFile: File): Boolean {
        rwLock.write {
            val interpreter = interpreter ?: return false
            try {
                val signatureOutputs = interpreter.getSignatureOutputs(SIG_SAVE)
                val outputs: MutableMap<String, Any> = HashMap()
                val weightsMap = HashMap<String, Any>() // Safely accepts FloatArray, LongArray, etc.

                for (outputName in signatureOutputs) {
                    val tensor = interpreter.getOutputTensorFromSignature(outputName, SIG_SAVE)
                    val totalElements = tensor.shape().fold(1) { acc, dim -> acc * max(1, dim) }

                    // Dynamically allocate the correct buffer type to avoid INT64->FloatBuffer crash
                    outputs[outputName] = when (tensor.dataType()) {
                        DataType.FLOAT32 -> FloatBuffer.allocate(totalElements)
                        DataType.INT64 -> LongBuffer.allocate(totalElements)
                        DataType.INT32 -> IntBuffer.allocate(totalElements)
                        else -> ByteBuffer.allocate(totalElements * tensor.dataType().byteSize())
                    }
                }

                val inputs: MutableMap<String, Any> = hashMapOf(DUMMY_INPUT_KEY to floatArrayOf(1.0f))
                interpreter.runSignature(inputs, outputs, SIG_SAVE)

                for ((key, value) in outputs) {
                    when (value) {
                        is FloatBuffer -> {
                            value.rewind()
                            val array = FloatArray(value.capacity())
                            value.get(array)
                            weightsMap[key] = array
                        }
                        is LongBuffer -> {
                            value.rewind()
                            val array = LongArray(value.capacity())
                            value.get(array)
                            weightsMap[key] = array
                        }
                        is IntBuffer -> {
                            value.rewind()
                            val array = IntArray(value.capacity())
                            value.get(array)
                            weightsMap[key] = array
                        }
                        is ByteBuffer -> {
                            value.rewind()
                            val array = ByteArray(value.capacity())
                            value.get(array)
                            weightsMap[key] = array
                        }
                    }
                }

                SecureModelStorage.encryptCheckpoint(weightsMap, checkpointFile)
                return true
            } catch (e: Exception) {
                Logger.e("Failed to save checkpoint: ${e.message}", e)
                return false
            }
        }
    }

    fun loadCheckpoint(checkpointFile: File): Boolean {
        rwLock.write {
            val interpreter = interpreter ?: return false
            if (!checkpointFile.exists()) return false

            try {
                val loadedWeights = SecureModelStorage.decryptCheckpoint(checkpointFile) ?: return false

                val inputs: MutableMap<String, Any> = HashMap()
                for ((key, array) in loadedWeights) {
                    inputs[key] = when (array) {
                        is FloatArray -> FloatBuffer.wrap(array)
                        is LongArray -> LongBuffer.wrap(array)
                        is IntArray -> IntBuffer.wrap(array)
                        is ByteArray -> ByteBuffer.wrap(array)
                        else -> array
                    }
                }

                val outputs: MutableMap<String, Any> = HashMap()

                // Safely handle STATUS tensor
                try {
                    if (interpreter.getSignatureOutputs(SIG_RESTORE).contains(OUTPUT_STATUS)) {
                        val tensor = interpreter.getOutputTensorFromSignature(OUTPUT_STATUS, SIG_RESTORE)
                        val elements = tensor.shape().fold(1) { acc, dim -> acc * max(1, dim) }
                        outputs[OUTPUT_STATUS] = when (tensor.dataType()) {
                            DataType.FLOAT32 -> FloatBuffer.allocate(elements)
                            DataType.INT64 -> LongBuffer.allocate(elements)
                            DataType.INT32 -> IntBuffer.allocate(elements)
                            else -> ByteBuffer.allocate(elements)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore dummy status tensor
                }

                interpreter.runSignature(inputs, outputs, SIG_RESTORE)
                return true
            } catch (e: Exception) {
                Logger.e("Failed to load checkpoint: ${e.message}", e)
                return false
            }
        }
    }

    fun deleteCheckpoint(checkpointFile: File): Boolean = checkpointFile.delete()

    fun isCheckpointExists(checkpointFile: File): Boolean = checkpointFile.exists()

    override fun close() {
        rwLock.write {
            interpreter?.close()
            interpreter = null
        }
    }
}