package com.wmspro.tenant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.client.FreighAiAuthClient
import com.wmspro.common.external.freighai.client.RefreshResult
import com.wmspro.common.external.freighai.dto.FreighAiAuthResponse
import com.wmspro.tenant.dto.LoginRequest
import com.wmspro.tenant.dto.LoginResponse
import com.wmspro.tenant.dto.LoginSession
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * AuthProxyService — wraps FreighAi's `/api/v1/auth/login` and reshapes the
 * response so the WMS frontend's `useAuth.js` doesn't change a single line.
 *
 * Flow:
 *   1. Call FreighAi login. If credentials fail or FreighAi is down, return null.
 *   2. Decode the FreighAi JWT payload (no signature verification — we trust
 *      the response since it came from a TLS call we initiated).
 *   3. Read the tenant_id claim (e.g. "tenant_c9d375a64417").
 *   4. Look up the WMS Long clientId via tenant_database_mappings.freighaiTenantId.
 *      If not found, login fails with a config-error message.
 *   5. Return a LoginResponse with:
 *        authToken    = the FreighAi JWT verbatim (frontend forwards this on every
 *                       subsequent request; WMS gateway validates it; backend
 *                       services forward it to FreighAi for enrichment calls)
 *        clientId     = the resolved WMS Long
 *        accessLevel  = a small object derived from FreighAi's role/permissions
 *        email/fullName = from FreighAi's user payload
 *        loginSession = synthesized UUID + absolute token expiry
 *        userTypeId   = FreighAi role string (e.g. "ADMIN")
 *
 * Auth at the WMS gateway is updated separately to validate FreighAi-signed JWTs
 * (Phase 5 gateway change — JwtService secret swap).
 *
 * Mobile migration (M2/M3): [refresh] and [logout] were added so the handheld app
 * can survive FreighAi's 24h access-token TTL mid-shift, and the login payload
 * gained `refreshToken`, `firstName`/`lastName` and `loginSession.tokenExpiry`.
 * Those additions are purely additive — the web client reads by field name and
 * ignores the rest, so `useAuth.js` still needs no changes.
 */
@Service
class AuthProxyService(
    private val freighAiAuthClient: FreighAiAuthClient,
    private val tenantRepository: TenantDatabaseMappingRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Performs the proxied login. Returns null on credential failure or
     * FreighAi unavailability. Throws IllegalStateException if FreighAi accepts
     * the credentials but the tenant has no WMS mapping (operational misconfig).
     */
    fun login(request: LoginRequest): LoginResponse? {
        val freighai = freighAiAuthClient.login(request.email, request.password)
            ?: return null
        return toLoginResponse(freighai)
    }

    /**
     * Exchanges a refresh token for a fresh access token.
     *
     * FreighAi rotates the refresh token on every use, so the returned
     * `refreshToken` is a NEW one and the presented token is now revoked. Clients
     * must persist what comes back; re-presenting the old token fails.
     *
     * Returns [RefreshOutcome.Rejected] when FreighAi answers and refuses the token
     * (expired, revoked, already rotated) — terminal, the caller answers 401 and the
     * client re-logs-in. Returns [RefreshOutcome.Unavailable] when FreighAi could not
     * be reached at all — retryable, the caller answers 503 and the client keeps its
     * session. Conflating the two would log every operator out during a brief outage.
     */
    fun refresh(refreshToken: String): RefreshOutcome = when (val result = freighAiAuthClient.refresh(refreshToken)) {
        is RefreshResult.Success -> RefreshOutcome.Refreshed(toLoginResponse(result.auth))
        is RefreshResult.Rejected -> RefreshOutcome.Rejected
        is RefreshResult.Unavailable -> RefreshOutcome.Unavailable(result.errorMessage)
    }

    /**
     * Revokes the refresh token at FreighAi. Returns FreighAi's acknowledgement,
     * but the caller should treat logout as successful regardless — the client is
     * discarding its credentials either way, and a stale refresh token that we
     * failed to revoke expires on its own within 7 days.
     */
    fun logout(refreshToken: String): Boolean = freighAiAuthClient.logout(refreshToken)

    /**
     * Maps a FreighAi auth payload onto the leadtorev-shaped response both WMS
     * clients consume. Shared by [login] and [refresh] so the two can never drift
     * — a mobile client that got a different shape from refresh than from login
     * would corrupt its own stored session.
     *
     * `loginSession.id` is freshly synthesised on each call, including refresh.
     * Nothing keys off it; it exists because the leadtorev response had it.
     */
    private fun toLoginResponse(freighai: FreighAiAuthResponse): LoginResponse {
        val freighaiTenantId = extractTenantIdClaim(freighai.accessToken)
            ?: throw IllegalStateException(
                "FreighAi auth response did not contain a tenant_id claim; cannot resolve WMS clientId"
            )

        val tenant = tenantRepository.findByFreighaiTenantId(freighaiTenantId).orElseThrow {
            IllegalStateException(
                "No WMS tenant_database_mappings entry maps to FreighAi tenant '$freighaiTenantId'. " +
                    "Add a tenant doc with freighaiTenantId field before this user can log in."
            )
        }

        val (firstName, lastName) = splitName(freighai.user.name)

        return LoginResponse(
            authToken = freighai.accessToken,
            refreshToken = freighai.refreshToken,
            clientId = tenant.clientId,
            accessLevel = buildAccessLevel(freighai),
            email = freighai.user.email,
            fullName = freighai.user.name,
            firstName = firstName,
            lastName = lastName,
            loginSession = LoginSession(
                id = UUID.randomUUID().toString(),
                tokenExpiry = Instant.now().plusSeconds(freighai.expiresIn).toString()
            ),
            userTypeId = freighai.user.role
        )
    }

    /**
     * Decode the JWT payload (middle segment) without signature verification and
     * pull out `tenant_id`. We don't validate the signature here because (a) we
     * just got the token from FreighAi over TLS, (b) the WMS gateway validates
     * signatures on subsequent requests anyway.
     */
    private fun extractTenantIdClaim(jwt: String): String? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return try {
            val payload = Base64.getUrlDecoder().decode(parts[1])
            val claims = objectMapper.readValue(payload, Map::class.java)
            claims["tenant_id"] as? String
        } catch (e: Exception) {
            logger.error("Failed to decode JWT payload while extracting tenant_id", e)
            null
        }
    }

    /**
     * Build a leadtorev-shaped accessLevel object from FreighAi's role + permissions.
     * The WMS frontend stores this stringified; later code may inspect specific
     * fields. We expose role + permissions + teams which is the most useful subset.
     */
    private fun buildAccessLevel(freighai: FreighAiAuthResponse): Map<String, Any> = mapOf(
        "role" to freighai.user.role,
        "teams" to freighai.user.teams,
        "permissions" to freighai.user.permissions
    )
}

/**
 * FreighAi stores a single `name`; the mobile Profile and Home screens want first
 * and last separately. Splits on the first space — "Gautam Aggarwal" becomes
 * ("Gautam", "Aggarwal") and a single-word "Gautam" becomes ("Gautam", "").
 *
 * Top-level and `internal` so it can be unit-tested directly, matching the
 * `consolidateFreighAiLines` idiom elsewhere in this service.
 */
/**
 * Outcome of [AuthProxyService.refresh].
 *
 * [Rejected] becomes 401 and tells the client its session is over; [Unavailable]
 * becomes 503 and tells it to try again. They must not be conflated — see
 * [com.wmspro.common.external.freighai.client.RefreshResult].
 */
sealed class RefreshOutcome {
    data class Refreshed(val response: LoginResponse) : RefreshOutcome()
    object Rejected : RefreshOutcome()
    data class Unavailable(val errorMessage: String) : RefreshOutcome()
}

internal fun splitName(name: String): Pair<String, String> {
    val trimmed = name.trim()
    val idx = trimmed.indexOf(' ')
    return if (idx < 0) {
        trimmed to ""
    } else {
        trimmed.substring(0, idx) to trimmed.substring(idx + 1).trim()
    }
}
