package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun Flow<List<Pair<Long, List<Float>>>>.featurePipeline(
    extractors: List<FeatureExtractor>
): Flow<List<Float>> {
    return this.map { window ->
        try {
            val allFeatures = mutableListOf<Float>()
            for (extractor in extractors) {
                allFeatures.addAll(extractor.extract(window))
            }
            allFeatures
        } catch (ex: Exception) {
            Logger.e("featurePipeline error", ex)
            emptyList()
        }
    }
}
