package xyz.lovestyle.home.canary

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Official Health Connect reader. Kotlin is intentionally isolated here because
 * Health Connect readRecords is a suspend API.
 */
object HealthConnectReader {
    private const val SOURCE = "health_connect"
    private const val MAX_RECORDS = 500
    private val required = setOf(
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    )

    @JvmStatic
    fun requiredPermissions(): Set<String> = required

    @JvmStatic
    fun collectBlocking(context: Context): String = runBlocking { collect(context) }

    private suspend fun collect(context: Context): String {
        val collectedAt = Instant.now()
        val output = JSONObject()
            .put("schemaVersion", 1)
            .put("source", SOURCE)
            .put("collectedAt", collectedAt.toString())
            .put("available", false)
            .put("permission", "unknown")
            .put("providerStatus", "UNAVAILABLE")
            .put("records", JSONArray())
        val statuses = JSONObject()
        listOf("heart_rate", "steps", "sleep").forEach {
            statuses.put(it, metricStatus("UNAVAILABLE"))
        }
        output.put("metricStatuses", statuses)

        val sdkStatus = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (_: Exception) {
            return output.toString()
        }
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return output.put("permission", "unavailable").toString()
        }

        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Exception) {
            return output.toString()
        }
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            return output.toString()
        }
        if (!granted.containsAll(required)) {
            listOf("heart_rate", "steps", "sleep").forEach {
                statuses.put(it, metricStatus("PERMISSION_DENIED"))
            }
            return output.put("available", true)
                .put("permission", "denied")
                .put("providerStatus", "PERMISSION_DENIED")
                .toString()
        }

        output.put("available", true).put("permission", "granted")
        val start = Instant.now().minus(7, ChronoUnit.DAYS)
        val end = Instant.now()
        val records = output.getJSONArray("records")

        val heartOk = readHeartRate(client, start, end, records, collectedAt)
        statuses.put("heart_rate", metricStatus(
            if (!heartOk) "UNAVAILABLE" else if (count(records, "heart_rate") > 0) "PASS" else "EMPTY"
        ))
        val stepsOk = readSteps(client, start, end, records, collectedAt)
        statuses.put("steps", metricStatus(
            if (!stepsOk) "UNAVAILABLE" else if (count(records, "steps") > 0) "PASS" else "EMPTY"
        ))
        val sleepOk = readSleep(client, start, end, records, collectedAt)
        statuses.put("sleep", metricStatus(
            if (!sleepOk) "UNAVAILABLE" else if (count(records, "sleep") > 0) "PASS" else "EMPTY"
        ))

        val anyPass = listOf("heart_rate", "steps", "sleep").any {
            statuses.getJSONObject(it).optString("status") == "PASS"
        }
        val anyUnavailable = listOf("heart_rate", "steps", "sleep").any {
            statuses.getJSONObject(it).optString("status") == "UNAVAILABLE"
        }
        output.put("providerStatus", when {
            anyPass -> "PASS"
            anyUnavailable -> "UNAVAILABLE"
            else -> "EMPTY"
        })
        return output.toString()
    }

    private suspend fun readHeartRate(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        output: JSONArray,
        collectedAt: Instant
    ): Boolean {
        return try {
            var token: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        HeartRateRecord::class,
                        TimeRangeFilter.between(start, end),
                        pageToken = token
                    )
                )
                response.records.forEach { record ->
                    record.samples.forEach { sample ->
                        if (output.length() >= MAX_RECORDS) return@forEach
                        output.put(JSONObject()
                            .put("metric", "heart_rate")
                            .put("sampledAt", sample.time.toString())
                            .put("dataDate", sample.time.toString().substring(0, 10))
                            .put("value", sample.beatsPerMinute)
                            .put("unit", "bpm")
                            .put("details", JSONObject())
                            .put("source", SOURCE)
                            .put("sourceRecordId", record.metadata.id)
                            .put("collectedAt", collectedAt.toString()))
                    }
                }
                token = response.pageToken
            } while (!token.isNullOrEmpty() && output.length() < MAX_RECORDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readSteps(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        output: JSONArray,
        collectedAt: Instant
    ): Boolean {
        return try {
            var token: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        StepsRecord::class,
                        TimeRangeFilter.between(start, end),
                        pageToken = token
                    )
                )
                response.records.forEach { record ->
                    if (output.length() < MAX_RECORDS) {
                        output.put(JSONObject()
                            .put("metric", "steps")
                            .put("sampledAt", record.endTime.toString())
                            .put("dataDate", record.endTime.toString().substring(0, 10))
                            .put("value", record.count)
                            .put("unit", "steps")
                            .put("details", JSONObject()
                                .put("startAt", record.startTime.toString())
                                .put("endAt", record.endTime.toString()))
                            .put("source", SOURCE)
                            .put("sourceRecordId", record.metadata.id)
                            .put("collectedAt", collectedAt.toString()))
                    }
                }
                token = response.pageToken
            } while (!token.isNullOrEmpty() && output.length() < MAX_RECORDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readSleep(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        output: JSONArray,
        collectedAt: Instant
    ): Boolean {
        return try {
            var token: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(start, end),
                        pageToken = token
                    )
                )
                response.records.forEach { record ->
                    if (output.length() < MAX_RECORDS) {
                        val stages = JSONArray()
                        record.stages.forEach { stage ->
                            stages.put(JSONObject()
                                .put("stage", stage.stage)
                                .put("startAt", stage.startTime.toString())
                                .put("endAt", stage.endTime.toString()))
                        }
                        output.put(JSONObject()
                            .put("metric", "sleep")
                            .put("sampledAt", record.endTime.toString())
                            .put("dataDate", record.startTime.toString().substring(0, 10))
                            .put("value", Duration.between(record.startTime, record.endTime).toMinutes())
                            .put("unit", "minutes")
                            .put("details", JSONObject()
                                .put("startAt", record.startTime.toString())
                                .put("endAt", record.endTime.toString())
                                .put("stages", stages))
                            .put("source", SOURCE)
                            .put("sourceRecordId", record.metadata.id)
                            .put("collectedAt", collectedAt.toString()))
                    }
                }
                token = response.pageToken
            } while (!token.isNullOrEmpty() && output.length() < MAX_RECORDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun count(records: JSONArray, metric: String): Int {
        var result = 0
        for (index in 0 until records.length()) {
            if (metric == records.optJSONObject(index)?.optString("metric")) result++
        }
        return result
    }

    private fun metricStatus(status: String) = JSONObject()
        .put("status", status)
        .put("source", SOURCE)
}
