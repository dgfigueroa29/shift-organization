package com.shiftorganization.shared.observability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest
import java.time.Instant

/**
 * Fire-and-forget CloudWatch metrics emitter.
 *
 * Emits KPI metrics for booking lifecycle, conflicts, and notifications.
 * Failures are logged but never propagated to callers.
 */
class CloudWatchMetricEmitter(
    private val cloudWatch: CloudWatchClient,
    private val namespace: String = "ShiftOrganization",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) {
    private val logger = LoggerFactory.getLogger(CloudWatchMetricEmitter::class.java)

    fun increment(metricName: String, dimensions: Map<String, String> = emptyMap()) {
        scope.launch {
            runCatching {
                val datum = MetricDatum.builder()
                    .metricName(metricName)
                    .timestamp(Instant.now())
                    .value(1.0)
                if (dimensions.isNotEmpty()) {
                    datum.dimensions(
                        dimensions.entries.map { (key, value) ->
                            software.amazon.awssdk.services.cloudwatch.model.Dimension.builder()
                                .name(key)
                                .value(value)
                                .build()
                        }
                    )
                }
                cloudWatch.putMetricData(
                    PutMetricDataRequest.builder()
                        .namespace(namespace)
                        .metricData(datum.build())
                        .build()
                )
            }.onFailure { e ->
                logger.warn("Failed to emit CloudWatch metric '$metricName': ${e.message}")
            }
        }
    }

    companion object {
        fun create(namespace: String = "ShiftOrganization"): CloudWatchMetricEmitter {
            return CloudWatchMetricEmitter(CloudWatchClient.create(), namespace)
        }
    }
}