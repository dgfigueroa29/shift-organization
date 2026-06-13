package com.shiftorganization.shared.config

/**
 * Reads all environment variables required by the Shift Organization Lambda
 * deployment units and exposes them as typed, named properties.
 *
 * Construction fails fast — if any **required** variable is absent a
 * descriptive [IllegalStateException] is thrown immediately, making
 * misconfigured deployments obvious before the handler processes any request.
 *
 * Optional variables (those that are only needed by specific Lambda units) are
 * exposed as nullable [String] properties; callers that depend on them should
 * call [requireOptional] to surface a clear error at the call site rather than
 * receiving a silent `null`.
 *
 * Usage:
 * ```kotlin
 * val config = EnvironmentConfig()          // reads System.getenv()
 * val config = EnvironmentConfig(myEnvMap)  // useful in tests
 * ```
 */
class EnvironmentConfig(
    private val env: Map<String, String> = System.getenv()
) {

    val deploymentConfig: DeploymentConfig by lazy { DeploymentConfig(env) }

    // -----------------------------------------------------------------------
    // RDS / PostgreSQL  (properties-handler, bookings-handler, health-handler)
    // -----------------------------------------------------------------------

    /** JDBC connection URL for the RDS PostgreSQL instance, e.g. `jdbc:postgresql://host:5432/db`. */
    val rdsJdbcUrl: String by lazy { require("RDS_JDBC_URL") }

    /** PostgreSQL username. */
    val rdsUsername: String by lazy { require("RDS_USERNAME") }

    /** PostgreSQL password. */
    val rdsPassword: String by lazy { require("RDS_PASSWORD") }

    // -----------------------------------------------------------------------
    // DynamoDB table names
    // -----------------------------------------------------------------------

    /** DynamoDB table name for `recurring_events` (partition: propertyId, sort: eventId). */
    val dynamoTableRecurringEvents: String by lazy { require("DYNAMO_TABLE_RECURRING_EVENTS") }

    /** DynamoDB table name for `workflow_state` (partition: workflowId, sort: workflowType). */
    val dynamoTableWorkflowState: String by lazy { require("DYNAMO_TABLE_WORKFLOW_STATE") }

    // -----------------------------------------------------------------------
    // Cognito
    // -----------------------------------------------------------------------

    /** HTTPS URL of the Cognito JWKS endpoint, e.g. `https://cognito-idp.<region>.amazonaws.com/<poolId>/.well-known/jwks.json`. */
    val cognitoJwksUri: String by lazy { require("COGNITO_JWKS_URI") }

    /**
     * Cognito User Pool ID — only required by the notifications handler for
     * resolving email addresses via `adminGetUser`.
     */
    val cognitoUserPoolId: String? by lazy { optional("COGNITO_USER_POOL_ID") }

    // -----------------------------------------------------------------------
    // SNS
    // -----------------------------------------------------------------------

    /** ARN of the SNS topic used for all notification fan-out messages. */
    val snsTopicArn: String by lazy { require("SNS_TOPIC_ARN") }

    // -----------------------------------------------------------------------
    // SES
    // -----------------------------------------------------------------------

    /**
     * Verified SES sender email address (`From:` header), e.g. `noreply@shift.example.com`.
     * Only required by the notifications handler.
     */
    val sesSenderAddress: String? by lazy { optional("SES_SENDER_ADDRESS") }

    // -----------------------------------------------------------------------
    // OpenSearch
    // -----------------------------------------------------------------------

    /** Base URL of the OpenSearch cluster, e.g. `https://my-domain.us-east-1.es.amazonaws.com`. */
    val openSearchEndpoint: String by lazy { require("OPENSEARCH_ENDPOINT") }

    // -----------------------------------------------------------------------
    // EventBridge
    // -----------------------------------------------------------------------

    /**
     * Name of the EventBridge event bus used for recurring-event cron rules.
     * Defaults to `"default"` when not set, which suits development but should
     * be a dedicated bus in staging/production.
     */
    val eventBridgeBusName: String by lazy { optional("EVENTBRIDGE_BUS_NAME") ?: "default" }

    // -----------------------------------------------------------------------
    // CloudWatch
    // -----------------------------------------------------------------------

    /**
     * CloudWatch metrics namespace, e.g. `"ShiftOrganization/prod"`.
     * Defaults to `"ShiftOrganization"` when not set.
     */
    val cloudWatchNamespace: String by lazy { optional("CLOUDWATCH_NAMESPACE") ?: "ShiftOrganization" }

    // -----------------------------------------------------------------------
    // Deployment Config Helpers
    // -----------------------------------------------------------------------

    val isAwsDeployment: Boolean by lazy { deploymentConfig.deployTarget == DeployTarget.AWS }
    val isLocalStack: Boolean by lazy { deploymentConfig.useLocalStack }
    val isNativeImage: Boolean by lazy { deploymentConfig.runtimeMode == RuntimeMode.GRAALVM_NATIVE }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the value of [name] from the environment map, or throws
     * [IllegalStateException] with a human-readable message if it is absent
     * or blank.
     */
    private fun require(name: String): String {
        val value = env[name]
        check(!value.isNullOrBlank()) {
            "Required environment variable '$name' is missing or blank. " +
                    "Please set it in the Lambda function configuration before deploying."
        }
        return value
    }

    /**
     * Returns the value of [name] from the environment map, or `null` if it
     * is absent.  Blank values are treated as absent and returned as `null`.
     */
    private fun optional(name: String): String? =
        env[name]?.takeIf { it.isNotBlank() }

    /**
     * Convenience method for callers that depend on an optional variable.
     * Throws [IllegalStateException] with a descriptive message when the
     * variable is absent, allowing clear error attribution at the call site.
     */
    fun requireOptional(name: String): String {
        return optional(name) ?: error(
            "Environment variable '$name' is required for this operation but was not set. " +
                    "Ensure it is configured in the Lambda function environment."
        )
    }
}
