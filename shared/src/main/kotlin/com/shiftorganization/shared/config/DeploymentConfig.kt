package com.shiftorganization.shared.config

enum class IacProvider { NONE, SAM }
enum class DeployTarget { LOCAL, LOCALSTACK, AWS }
enum class HealthAuthMode { NONE, STATIC_TOKEN }
enum class RuntimeMode { JVM, GRAALVM_NATIVE }

class DeploymentConfig(private val env: Map<String, String> = System.getenv()) {
    val iacProvider: IacProvider = env["IAC_PROVIDER"]?.let { IacProvider.valueOf(it.uppercase()) } ?: IacProvider.NONE
    val deployTarget: DeployTarget = env["DEPLOY_TARGET"]?.let { DeployTarget.valueOf(it.uppercase()) } ?: DeployTarget.LOCAL
    val runtimeMode: RuntimeMode = when {
        env["NATIVE_IMAGE"]?.toBoolean() == true -> RuntimeMode.GRAALVM_NATIVE
        env["USE_GRAALVM"]?.toBoolean() == true -> RuntimeMode.GRAALVM_NATIVE
        else -> RuntimeMode.JVM
    }
    val enableCloudWatchMetrics: Boolean = env["ENABLE_CW_METRICS"]?.toBoolean() ?: (deployTarget == DeployTarget.AWS)
    val enableSnsRetries: Boolean = env["ENABLE_SNS_RETRIES"]?.toBoolean() ?: true
    val enableHttpCompression: Boolean = env["ENABLE_COMPRESSION"]?.toBoolean() ?: true
    val enableStructuredLogging: Boolean = env["ENABLE_STRUCTURED_LOGGING"]?.toBoolean() ?: true
    val healthAuthMode: HealthAuthMode = env["HEALTH_AUTH_MODE"]?.let { HealthAuthMode.valueOf(it.uppercase()) } ?: HealthAuthMode.NONE
    val healthStaticToken: String? = env["HEALTH_STATIC_TOKEN"]
    val jwtValidateAudience: Boolean = env["JWT_VALIDATE_AUD"]?.toBoolean() ?: (deployTarget == DeployTarget.AWS)
    val jwtValidateTokenUse: Boolean = env["JWT_VALIDATE_TOKEN_USE"]?.toBoolean() ?: (deployTarget == DeployTarget.AWS)
    val runFlywayOnStartup: Boolean = env["FLYWAY_ON_STARTUP"]?.toBoolean() ?: (deployTarget != DeployTarget.AWS)
    val dbConnectionLazy: Boolean = env["LAZY_DB_INIT"]?.toBoolean() ?: (deployTarget == DeployTarget.AWS)
    val useLocalStack: Boolean = env["USE_LOCALSTACK"]?.toBoolean() ?: (deployTarget == DeployTarget.LOCALSTACK)
    val localstackEndpoint: String? = env["LOCALSTACK_ENDPOINT"]
    val localstackAccessKey: String = env["LOCALSTACK_ACCESS_KEY"] ?: "test"
    val localstackSecretKey: String = env["LOCALSTACK_SECRET_KEY"] ?: "test"
    val serverPort: Int = env["SERVER_PORT"]?.toIntOrNull() ?: 8081
    val awsRegion: String = env["AWS_REGION"] ?: "us-east-1"
}