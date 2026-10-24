package xyz.lovestyle.home.canary

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.LinkedHashSet

/**
 * Official Health Connect reader. Kotlin is intentionally isolated here because
 * Health Connect readRecords/aggregate are suspend APIs.
 *
 * Steps are cumulative data: the canonical steps records below are daily
 * COUNT_TOTAL aggregates, never an arbitrary raw interval count.
 */
object HealthConnectReader {
    private const val SOURCE = "health_connect"
    private const val HEART_RATE_LIMIT = 300
    private const val RESTING_HEART_RATE_LIMIT = 30
    private const val STEPS_LIMIT = 100
    private const val SLEEP_LIMIT = 100
    private const val MAX_PAYLOAD_RECORDS =
        HEART_RATE_LIMIT + RESTING_HEART_RATE_LIMIT + STEPS_LIMIT + SLEEP_LIMIT

    private val coreReadPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )
    private val restingReadPermission =
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    private val baseReadPermissions = coreReadPermissions + setOf(restingReadPermission)
    private val backgroundReadPermission =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    @JvmStatic
    fun requiredPermissions(): Set<String> = baseReadPermissions

    @JvmStatic
    fun permissionsForRequest(context: Context): Set<String> {
        val result = LinkedHashSet(baseReadPermissions)
        if (backgroundReadFeatureAvailable(context)) {
            result.add(backgroundReadPermission)
        }
        return result
    }

    @JvmStatic
    fun backgroundReadFeatureAvailable(context: Context): Boolean {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun canReadInBackground(context: Context): Boolean = runBlocking {
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val featureAvailable = client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            featureAvailable && client.permissionController.getGrantedPermissions()
                .contains(backgroundReadPermission)
        } catch (_: Exception) {
            false
        }
    }

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
        listOf("heart_rate", "resting_heart_rate", "steps", "sleep").forEach {
            statuses.put(it, metricStatus("UNAVAILABLE"))
        }
        output.put("metricStatuses", statuses)

        val sdkStatus = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (_: Exception) {
            return output.put("permissionState", permissionState("unavailable", "unsupported"))
                .toString()
        }
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return output
                .put("permission", "unavailable")
                .put("permissionState", permissionState("unavailable", "unsupported"))
                .toString()
        }

        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Exception) {
            return output.put("permissionState", permissionState("unavailable", "unsupported"))
                .toString()
        }

        val backgroundAvailable = try {
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Exception) {
            false
        }
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            return output.put(
                "permissionState",
                permissionState("unavailable", if (backgroundAvailable) "denied" else "unsupported")
            ).toString()
        }
        val backgroundState = when {
            !backgroundAvailable -> "unsupported"
            granted.contains(backgroundReadPermission) -> "granted"
            else -> "denied"
        }

        if (!granted.containsAll(coreReadPermissions)) {
            listOf("heart_rate", "resting_heart_rate", "steps", "sleep").forEach {
                statuses.put(it, metricStatus("PERMISSION_DENIED"))
            }
            return output
                .put("available", true)
                .put("permission", "denied")
                .put("permissionState", permissionState("denied", backgroundState))
                .put("providerStatus", "PERMISSION_DENIED")
                .toString()
        }

        output
            .put("available", true)
            .put("permission", "granted")
            .put("permissionState", permissionState("granted", backgroundState))

        val start = Instant.now().minus(7, ChronoUnit.DAYS)
        val heartStart = Instant.now().minus(1, ChronoUnit.HOURS)
        val end = Instant.now()
        val zone = ZoneId.systemDefault()
        val heartRecords = JSONArray()
        val restingRecords = JSONArray()
        val stepRecords = JSONArray()
        val sleepRecords = JSONArray()

        val heartOk = readHeartRate(client, heartStart, end, heartRecords, collectedAt)
        statuses.put("heart_rate", metricStatus(
            if (!heartOk) "UNAVAILABLE"
            else if (heartRecords.length() > 0) "PASS"
            else "EMPTY"
        ))

        if (!granted.contains(restingReadPermission)) {
            statuses.put("resting_heart_rate", metricStatus("PERMISSION_DENIED"))
        } else {
            val restingOk = readRestingHeartRate(client, start, end, restingRecords, collectedAt)
            statuses.put("resting_heart_rate", metricStatus(
                if (!restingOk) "UNAVAILABLE"
                else if (restingRecords.length() > 0) "PASS"
                else "EMPTY"
            ))
        }

        // COUNT_TOTAL is the only canonical steps path. Raw interval records are
        // not summed manually because multiple origins can overlap.
        val stepsOk = readDailySteps(client, zone, stepRecords, collectedAt)
        statuses.put("steps", metricStatus(
            if (!stepsOk) "UNAVAILABLE"
            else if (stepRecords.length() > 0) "PASS"
            else "EMPTY"
        ))

        val sleepOk = readSleep(client, start, end, sleepRecords, collectedAt)
        statuses.put("sleep", metricStatus(
            if (!sleepOk) "UNAVAILABLE"
            else if (sleepRecords.length() > 0) "PASS"
            else "EMPTY"
        ))

        val records = output.getJSONArray("records")
        appendRecords(records, heartRecords)
        appendRecords(records, restingRecords)
        appendRecords(records, stepRecords)
        appendRecords(records, sleepRecords)
        if (records.length() > MAX_PAYLOAD_RECORDS) {
            return output
                .put("records", JSONArray())
                .put("providerStatus", "UNAVAILABLE")
                .put("permissionState", permissionState("granted", backgroundState))
                .toString()
        }

        val anyPass = listOf("heart_rate", "resting_heart_rate", "steps", "sleep").any {
            statuses.getJSONObject(it).optString("status") == "PASS"
        }
        val anyUnavailable = listOf("heart_rate", "resting_heart_rate", "steps", "sleep").any {
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
                    record.samples.forEach sampleLoop@ { sample ->
                        if (output.length() >= HEART_RATE_LIMIT) return@sampleLoop
                        output.put(JSONObject()
                            .put("metric", "heart_rate")
                            .put("sampledAt", sample.time.toString())
                            .put("dataDate", localDateFor(sample.time, record.startZoneOffset))
                            .put("value", sample.beatsPerMinute)
                            .put("unit", "bpm")
                            .put("details", JSONObject())
                            .put("source", SOURCE)
                            .put("sourceRecordId", record.metadata.id)
                            .put("collectedAt", collectedAt.toString()))
                    }
                }
                token = response.pageToken
            }             while (!token.isNullOrEmpty() && output.length() < HEART_RATE_LIMIT)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readRestingHeartRate(
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
                        RestingHeartRateRecord::class,
                        TimeRangeFilter.between(start, end),
                        pageToken = token
                    )
                )
                response.records.forEach { record ->
                    if (output.length() >= RESTING_HEART_RATE_LIMIT) return@forEach
                    output.put(JSONObject()
                        .put("metric", "resting_heart_rate")
                        .put("sampledAt", record.time.toString())
                        .put("dataDate", localDateFor(record.time, record.zoneOffset))
                        .put("value", record.beatsPerMinute)
                        .put("unit", "bpm")
                        .put("details", JSONObject())
                        .put("source", SOURCE)
                        .put("sourceRecordId", record.metadata.id)
                        .put("collectedAt", collectedAt.toString()))
                }
                token = response.pageToken
            } while (!token.isNullOrEmpty() && output.length() < RESTING_HEART_RATE_LIMIT)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readDailySteps(
        client: HealthConnectClient,
        zone: ZoneId,
        output: JSONArray,
        collectedAt: Instant
    ): Boolean {
        return try {
            val today = LocalDate.now(zone)
            val firstDay = today.minusDays(6)
            for (offset in 0..6) {
                if (output.length() >= STEPS_LIMIT) break
                val date = firstDay.plusDays(offset.toLong())
                val start = date.atStartOfDay(zone).toInstant()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
                val now = Instant.now()
                val end = if (date == today && now.isBefore(dayEnd)) now else dayEnd
                if (!start.isBefore(end)) continue

                val response = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(
                            startTime = start,
                            endTime = end
                        )
                    )
                )
                val total = response[StepsRecord.COUNT_TOTAL] ?: continue
                val sampledAt = if (date == today) end else end.minusNanos(1)
                output.put(JSONObject()
                    .put("metric", "steps")
                    .put("sampledAt", sampledAt.toString())
                    .put("dataDate", date.toString())
                    .put("value", total)
                    .put("unit", "steps")
                    .put("details", JSONObject()
                        .put("aggregation", "COUNT_TOTAL")
                        .put("startAt", start.toString())
                        .put("endAt", end.toString())
                        .put("zoneId", zone.id))
                    .put("source", SOURCE)
                    .put("sourceRecordId", "aggregate:steps:" + date.toString())
                    .put("collectedAt", collectedAt.toString()))
            }
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
                    if (output.length() >= SLEEP_LIMIT) return@forEach
                    val stages = JSONArray()
                    record.stages.forEach { stage ->
                        stages.put(JSONObject()
                            .put("stage", stage.stage)
                            .put("startAt", stage.startTime.toString())
                            .put("endAt", stage.endTime.toString()))
                    }
                    // Sleep belongs to the local date on which it wakes/ends.
                    output.put(JSONObject()
                        .put("metric", "sleep")
                        .put("sampledAt", record.endTime.toString())
                        .put("dataDate", localDateFor(record.endTime, record.endZoneOffset))
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
                token = response.pageToken
            } while (!token.isNullOrEmpty() && output.length() < SLEEP_LIMIT)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun localDateFor(instant: Instant, recordOffset: ZoneOffset?): String {
        val offset = recordOffset ?: ZoneId.systemDefault().rules.getOffset(instant)
        return instant.atOffset(offset).toLocalDate().toString()
    }

    private fun permissionState(metrics: String, backgroundRead: String) = JSONObject()
        .put("metrics", metrics)
        .put("backgroundRead", backgroundRead)

    private fun metricStatus(status: String) = JSONObject()
        .put("status", status)
        .put("source", SOURCE)

    private fun appendRecords(target: JSONArray, source: JSONArray) {
        for (index in 0 until source.length()) {
            target.put(source.getJSONObject(index))
        }
    }
}
