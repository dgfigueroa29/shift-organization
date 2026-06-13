plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    testImplementation(libs.junit5.api)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}

if (project.hasProperty("native")) {
    apply(plugin = "org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.aws.dynamodb)
    implementation(libs.aws.sns)
    implementation(libs.aws.cloudwatch)
}

application {
    mainClass = "com.shiftorganization.lambda.recurringprocessor.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "recurring-processor"
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.shiftorganization.lambda.recurringprocessor.RecurringProcessorModuleKt"
    }
}

kotlin {
    jvmToolchain(17)
}

if (project.hasProperty("native")) {
    extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
        binaries {
            named("main") {
                imageName.set("recurring-processor")
                buildArgs.add("--no-fallback")
                buildArgs.add("-H:+ReportExceptionStackTraces")
                buildArgs.add("--initialize-at-build-time=org.slf4j,software.amazon.awssdk")
                buildArgs.add("-H:ReflectionConfigurationFiles=src/main/resources/reflect-config.json")
                buildArgs.add("-H:JNIConfigurationFiles=src/main/resources/jni-config.json")
            }
        }
    }
}