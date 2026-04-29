package com.wmspro.tenant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.client.FreighAiAuthClient
import com.wmspro.common.external.freighai.dto.FreighAiAuthResponse
import com.wmspro.tenant.dto.LoginRequest
import com.wmspro.tenant.dto.LoginResponse
import com.wmspro.tenant.dto.LoginSession
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
 *        loginSession = synthesized UUID (frontend just stores it for tracking)
 *        userTypeId   = FreighAi role string (e.g. "ADMIN")
 *
 * Auth at the WMS gateway is updated separately to validate FreighAi-signed JWTs
 * (Phase 5 gateway change — JwtService secret swap).
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

        return LoginResponse(
            authToken = freighai.accessToken,
            clientId = tenant.clientId,
            accessLevel = buildAccessLevel(freighai),
            email = freighai.user.email,
            fullName = freighai.user.name,
            loginSession = LoginSession(id = UUID.randomUUID().toString()),
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
