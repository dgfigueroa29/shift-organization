package com.shiftorganization.shared.observability

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest

@OptIn(ExperimentalCoroutinesApi::class)
class CloudWatchMetricEmitterTest {

    private val cloudWatch = mock<CloudWatchClient>()
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `increment sends one metric datum with correct name and namespace`() = runTest(testDispatcher) {
        val emitter = CloudWatchMetricEmitter(cloudWatch, "TestNamespace", this)

        emitter.increment("test.metric")

        testDispatcher.scheduler.advanceUntilIdle()
        val captor = argumentCaptor<PutMetricDataRequest>()
        verify(cloudWatch).putMetricData(captor.capture())
        val request = captor.firstValue
        assertEquals("TestNamespace", request.namespace())
        assertEquals(1, request.metricData().size)
        assertEquals("test.metric", request.metricData()[0].metricName())
        assertEquals(1.0, request.metricData()[0].value(), 0.001)
    }

    @Test
    fun `increment includes dimensions when provided`() = runTest(testDispatcher) {
        val emitter = CloudWatchMetricEmitter(cloudWatch, "TestNamespace", this)

        emitter.increment("test.metric", mapOf("key1" to "val1", "key2" to "val2"))

        testDispatcher.scheduler.advanceUntilIdle()
        val captor = argumentCaptor<PutMetricDataRequest>()
        verify(cloudWatch).putMetricData(captor.capture())
        val dimensions = captor.firstValue.metricData()[0].dimensions()
        assertEquals(2, dimensions.size)
        val dimMap = dimensions.associate { it.name() to it.value() }
        assertEquals("val1", dimMap["key1"])
        assertEquals("val2", dimMap["key2"])
    }
}
