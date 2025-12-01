package com.ca.continuousauth.featuremodalities.dataprocessing.postprocessers

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/**
 * Extension function for Flow<List<Float>> to apply post-processing pipeline directly.
 *
 * @param processors List of PostProcessor to apply sequentially
 * @param dispatcher CoroutineDispatcher for flowOn
 * @return Flow<List<Float>> processed feature vectors
 */
fun Flow<List<Float>>.postProcess(
    processors: List<PostProcessor>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<List<Float>> {
    return this
        .map { features ->
            try {
                var processed = features
                for (processor in processors) {
                    processed = processor.process(processed)
                }
                processed
            } catch (ex: Exception) {
                Logger.e("PostProcess map error", ex)
                emptyList()
            }
        }
        .catch { ex ->
            Logger.e("Error in postProcess Flow", ex)
            throw ex
        }
        .flowOn(dispatcher)
}
