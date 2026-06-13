package com.shiftorganization.shared.observability

import com.amazonaws.xray.AWSXRay
import com.amazonaws.xray.AWSXRayRecorderBuilder
import com.amazonaws.xray.strategy.sampling.NoSamplingStrategy

fun initXRay() {
    val builder = AWSXRayRecorderBuilder.standard()
        .withSamplingStrategy(NoSamplingStrategy())
    AWSXRay.setGlobalRecorder(builder.build())
}
