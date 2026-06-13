package com.shiftorganization.shared.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.shiftorganization.shared.config.EnvironmentConfig
import com.shiftorganization.shared.domain.Role
import com.shiftorganization.shared.domain.UserPrincipal
import com.shiftorganization.shared.exception.ForbiddenException
import com.shiftorganization.shared.exception.UnauthorizedException
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.net.URI
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// JWKS provider cache
// ---------------------------------------------------------------------------

/**
 * Builds a [JwkProvider] that caches JWKS keys in-memory with the supplied TTL.
 *
 * The provider fetches keys from [jwksUri] on first use and caches them for
 * [cacheTtlMinutes] (default 5). This avoids a JWKS fetch on every request
 * while still rotating keys when the TTL expires.
 *
 * @param jwksUri         The full HTTPS URL of the Cognito JWKS endpoint.
 * @param cacheTtlMinutes How long to cache keys before re-fetching (default 5 min).
 * @param cacheSize       Maximum number of keys to keep in cache (default 10).
 */
fun buildJwkProvider(
    jwksUri: String,
    cacheTtlMinutes: Long = 5L,
    cacheSize: Long = 10L
): JwkProvider =
    JwkProviderBuilder(URI(jwksUri).toURL())
        .cached(cacheSize, cacheTtlMinutes, TimeUnit.MINUTES)
        .rateLimited(false)
        .build()

// ---------------------------------------------------------------------------
// Ktor Authentication plugin extension
// ---------------------------------------------------------------------------

/** Name used to register the Cognito JWT authentication scheme with Ktor. */
const val COGNITO_JWT_AUTH = "cognito"

/**
 * Registers a JWT authentication provider named [COGNITO_JWT_AUTH] that validates
 * Cognito-issued JWTs and infers the expected issuer from a standard Cognito JWKS URI.
 *
 * This convenience overload keeps existing call sites compact. If the issuer
 * cannot be derived from [jwksUri], use the overload that accepts [expectedIssuer].
 *
 * @param jwksUri         Full HTTPS URL to the Cognito JWKS endpoint.
 * @param cacheTtlMinutes In-memory key cache TTL in minutes (default 5).
 * @param jwkProvider     Optional override of the [JwkProvider]; useful for testing.
 *                        When `null` (the default), a caching provider is built from [jwksUri].
 */
fun AuthenticationConfig.cognitoJwt(
    jwksUri: String,
    cacheTtlMinutes: Long = 5L,
    jwkProvider: JwkProvider? = null
) {
    val derivedIssuer = deriveIssuerFromJwksUri(jwksUri)
        ?: error(
            "Unable to infer the expected JWT issuer from jwksUri='$jwksUri'. " +
                    "Use the overload that accepts an explicit expectedIssuer."
        )
    cognitoJwt(
        jwksUri = jwksUri,
        expectedIssuer = derivedIssuer,
        cacheTtlMinutes = cacheTtlMinutes,
        jwkProvider = jwkProvider
    )
}

/**
 * Registers a JWT authentication provider named [COGNITO_JWT_AUTH] that validates
 * Cognito-issued JWTs against an explicit issuer.
 *
 * Validation steps:
 * 1. The JWT signature is verified against the Cognito JWKS endpoint (cached with TTL).
 * 2. The `exp` claim is checked by the underlying JWT library.
 * 3. The `iss` claim must match the expected issuer supplied to this function.
 * 4. The `custom:role` claim is parsed into a [Role] enum; unknown values produce `null`
 *    which causes the credential to be rejected with HTTP 401.
 * 5. The `sub` claim is used as the [UserPrincipal.userId].
 *
 * If any validation step fails the request is rejected with HTTP 401 (handled by the
 * [com.shiftorganization.shared.plugins.StatusPagesConfig] exception mapping).
 *
 * @param jwksUri         Full HTTPS URL to the Cognito JWKS endpoint.
 * @param expectedIssuer   Expected `iss` claim value for the JWT.
 * @param cacheTtlMinutes In-memory key cache TTL in minutes (default 5).
 * @param jwkProvider     Optional override of the [JwkProvider]; useful for testing.
 *                        When `null` (the default), a caching provider is built from [jwksUri].
 */
fun AuthenticationConfig.cognitoJwt(
    jwksUri: String,
    expectedIssuer: String,
    cacheTtlMinutes: Long = 5L,
    jwkProvider: JwkProvider? = null
) {
    val provider = jwkProvider ?: buildJwkProvider(jwksUri, cacheTtlMinutes)

    jwt(COGNITO_JWT_AUTH) {
        verifier(provider, expectedIssuer) { }

        validate { credential ->
            val sub = credential.payload.subject
            val issuer = credential.payload.issuer
            val roleClaim = credential.payload.getClaim("custom:role").asString()
            val audience = credential.payload.audience
            val tokenUse = credential.payload.getClaim("token_use").asString()

            val config = EnvironmentConfig()

            // Reject tokens missing required claims
            if (sub.isNullOrBlank() || issuer.isNullOrBlank() || roleClaim.isNullOrBlank()) {
                return@validate null
            }

            // Reject tokens issued by a different Cognito user pool or issuer
            if (issuer != expectedIssuer) {
                return@validate null
            }

            // Validate audience if enabled
            if (config.deploymentConfig.jwtValidateAudience) {
                val expectedAudience = config.cognitoUserPoolId?.let { "cognito-idp.$it" }
                    ?: config.cognitoJwksUri.substringAfter("https://cognito-idp.").substringBefore("/")
                if (expectedAudience !in audience) {
                    return@validate null
                }
            }

            // Validate token_use == "id" if enabled
            if (config.deploymentConfig.jwtValidateTokenUse && tokenUse != "id") {
                return@validate null
            }

            // Reject tokens with unknown role values
            val role = runCatching { Role.fromClaim(roleClaim) }.getOrNull()
                ?: return@validate null

            UserPrincipal(userId = sub, role = role)
        }

        challenge { _, _ ->
            throw UnauthorizedException("Missing or invalid JWT token")
        }
    }
}

private fun deriveIssuerFromJwksUri(jwksUri: String): String? {
    val suffix = "/.well-known/jwks.json"
    return jwksUri.removeSuffix(suffix)
        .takeIf { it != jwksUri && it.isNotBlank() }
}

// ---------------------------------------------------------------------------
// Route-level authorization helper
// ---------------------------------------------------------------------------

/**
 * Asserts that the authenticated caller has one of the [permitted] roles.
 *
 * Reads [UserPrincipal] from the current [ApplicationCall]. If the principal is absent
 * (i.e., the route was not protected by `authenticate`) or the principal's role is not
 * in [permitted], a [ForbiddenException] is thrown and Ktor's StatusPages plugin maps
 * it to HTTP 403.
 *
 * Usage inside a route handler:
 * ```kotlin
 * authenticate(COGNITO_JWT_AUTH) {
 *     post("/properties") {
 *         call.requireRole(Role.OWNER, Role.ADMIN)
 *         // ... handler logic
 *     }
 * }
 * ```
 *
 * @param permitted One or more [Role] values that are allowed to call this endpoint.
 * @throws ForbiddenException when the caller's role is not in [permitted].
 */
fun ApplicationCall.requireRole(vararg permitted: Role) {
    val principal = principal<UserPrincipal>()
        ?: throw ForbiddenException("Authenticated principal not found")
    if (principal.role !in permitted) {
        throw ForbiddenException(
            "Role '${principal.role}' is not permitted. Required: ${permitted.joinToString()}"
        )
    }
}
