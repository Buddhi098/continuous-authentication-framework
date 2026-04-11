package com.ca.authframework.features.evalhistory

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for persisting evaluation records using JSON file storage. Provides simple CRUD
 * operations for evaluation history.
 */
class EvaluationRepository(context: Context) {

    private val file: File = File(context.filesDir, FILENAME)

    companion object {
        private const val FILENAME = "evaluation_records.json"

        // JSON keys
        private const val KEY_ID = "id"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_EVALUATOR_NAME = "evaluatorName"
        private const val KEY_EVALUATOR_LABEL = "evaluatorLabel"
        private const val KEY_FINAL_CONFIDENCE = "finalConfidence"
        private const val KEY_SAMPLES_PROCESSED = "samplesProcessed"
        private const val KEY_AVERAGE_SCORE = "averageScore"
        private const val KEY_MEDIAN_SCORE = "medianScore"
    }

    /** Save a new evaluation record to storage. */
    fun saveRecord(record: EvaluationRecord) {
        val records = getAllRecords().toMutableList()
        records.add(record)
        saveAllRecords(records)
    }

    /** Retrieve all stored evaluation records, sorted by timestamp (newest first). */
    fun getAllRecords(): List<EvaluationRecord> {
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText()
            if (content.isBlank()) return emptyList()

            val jsonArray = JSONArray(content)
            val records = mutableListOf<EvaluationRecord>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                records.add(parseRecord(obj))
            }

            records.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Clear all stored evaluation records. */
    fun clearRecords() {
        if (file.exists()) {
            file.delete()
        }
    }

    /** Delete a specific evaluation record by ID. */
    fun deleteRecord(id: String) {
        val records = getAllRecords().filter { it.id != id }
        saveAllRecords(records)
    }

    private fun saveAllRecords(records: List<EvaluationRecord>) {
        val jsonArray = JSONArray()
        records.forEach { record -> jsonArray.put(recordToJson(record)) }
        file.writeText(jsonArray.toString())
    }

    private fun recordToJson(record: EvaluationRecord): JSONObject {
        return JSONObject().apply {
            put(KEY_ID, record.id)
            put(KEY_TIMESTAMP, record.timestamp)
            put(KEY_EVALUATOR_NAME, record.evaluatorName)
            put(KEY_EVALUATOR_LABEL, record.evaluatorLabel.name)
            put(KEY_FINAL_CONFIDENCE, record.finalConfidence)
            put(KEY_SAMPLES_PROCESSED, record.samplesProcessed)
            put(KEY_AVERAGE_SCORE, record.averageScore)
            put(KEY_MEDIAN_SCORE, record.medianScore)
        }
    }

    private fun parseRecord(obj: JSONObject): EvaluationRecord {
        return EvaluationRecord(
                id = obj.getString(KEY_ID),
                timestamp = obj.getLong(KEY_TIMESTAMP),
                evaluatorName = obj.getString(KEY_EVALUATOR_NAME),
                evaluatorLabel = EvaluatorLabel.valueOf(obj.getString(KEY_EVALUATOR_LABEL)),
                finalConfidence = obj.optDouble(KEY_FINAL_CONFIDENCE, obj.optDouble("avgConfidence", 0.0)),
                samplesProcessed = obj.getInt(KEY_SAMPLES_PROCESSED),
                averageScore = obj.optDouble(KEY_AVERAGE_SCORE, 0.0),
                medianScore = obj.optDouble(KEY_MEDIAN_SCORE, 0.0)
        )
    }
}
