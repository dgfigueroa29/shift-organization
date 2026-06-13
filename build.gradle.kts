plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.graalvm.native) apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
}
