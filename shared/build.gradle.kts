plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // alias(libs.plugins.detekt)  // Disabled due to config compatibility issues with detekt 1.23.6
    // alias(libs.plugins.ktlint)  // Not in Gradle Central Plugin Repository
    // alias(libs.plugins.pitest)  // Not in Gradle Central Plugin Repository
    // alias(libs.plugins.kover)  // Not in Gradle Central Plugin Repository
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.jwks.rsa)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    // Database
    implementation(libs.hikaricp)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.aws.dynamodb)
    implementation(libs.aws.sns)
    implementation(libs.aws.ses)
    implementation(libs.aws.cognito)
    implementation(libs.aws.eventbridge)
    implementation(libs.aws.cloudwatch)
    implementation(libs.aws.apache.client)
    implementation(libs.aws.xray.recorder.sdk.aws.sdk.v2)
    implementation(libs.aws.xray.recorder.sdk.apache.http)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation("org.opensearch.client:opensearch-java:2.7.0")

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.h2)
    testImplementation(libs.jqwik)
    // Testcontainers
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.opensearch)
    testImplementation(libs.testcontainers.junit5)
    testImplementation("org.opensearch.client:opensearch-rest-client:2.7.0")
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Run integration tests separately with: ./gradlew :shared:integrationTest
// Requires Docker to be running (Testcontainers: PostgreSQL, LocalStack, OpenSearch)
tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.test)
    description = "Runs integration tests that require Docker (Testcontainers)."
    group = "verification"
}

// tasks.detekt {  // Disabled due to config compatibility issues with detekt 1.23.6
//     config = files("../detekt.yml")
//     reports {
//         html.enabled = true
//         xml.enabled = true
//     }
// }

// tasks.ktlintCheck {  // Requires ktlint plugin
//     configFile = file("../.ktlint.yml")
// }

// tasks.pitest {  // Requires pitest plugin
//     targetClasses = ["com.shiftorganization.shared.*"]
//     mutators = ["ALL"]
//     excludedMethods = ["*Test*", "*test*"]
//     excludedClasses = ["*Test*", "*test*"]
//     outputFormats = ["XML", "HTML"]
//     verbose = true
// }

// tasks.koverXmlReport {  // Requires kover plugin
//     dependsOn(tasks.test)
// }

kotlin {
    jvmToolchain(17)
}