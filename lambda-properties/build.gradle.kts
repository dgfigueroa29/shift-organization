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
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.compression)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation("org.opensearch.client:opensearch-java:2.7.0")
    implementation("org.opensearch.client:opensearch-rest-client:2.7.0")
    implementation(libs.aws.apache.client)
    implementation("software.amazon.awssdk:regions:2.25.69")
}

application {
    mainClass = "com.shiftorganization.lambda.properties.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "properties"
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.shiftorganization.lambda.properties.PropertiesModuleKt"
    }
}

kotlin {
    jvmToolchain(17)
}

if (project.hasProperty("native")) {
    extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
        binaries {
            named("main") {
                imageName.set("properties")
                buildArgs.add("--no-fallback")
                buildArgs.add("-H:+ReportExceptionStackTraces")
                buildArgs.add("--initialize-at-build-time=org.slf4j,org.postgresql,software.amazon.awssdk,com.fasterxml.jackson")
                buildArgs.add("-H:ReflectionConfigurationFiles=src/main/resources/reflect-config.json")
                buildArgs.add("-H:JNIConfigurationFiles=src/main/resources/jni-config.json")
            }
        }
    }
}