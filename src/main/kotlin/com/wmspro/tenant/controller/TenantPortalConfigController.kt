package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Manages a tenant's customer-portal code — the slug in `portal.wms.../{CODE}/login`.
 *
 * Mounted under `/api/v1/tenants/...` deliberately: that prefix is already routed at the gateway
 * and is already listed in `TenantInterceptor.CENTRAL_DB_PATHS`, so `tenant_database_mappings`
 * resolves against the central database. A new top-level path would have needed both, and missing
 * the interceptor registration is exactly how per-tenant data was previously corrupted (see the
 * comment in WmsTenantServiceApplication).
 *
 * Tenant Service **owns** this field. wms-customer-portal-service reads it and never writes it.
 *
 * ## Why every method re-derives the caller's tenant
 *
 * `clientId` is a caller-supplied path variable, and the gateway performs no role check — it
 * forwards the client's own `X-Tenant-Id` untouched (finding S1). Without the check below, any
 * authenticated staff user of any 3PL could read, rename or delete another 3PL's portal code, and
 * `DELETE` disables that tenant's entire customer portal.
 *
 * So the acting tenant is derived from the **`tenant_id` claim inside the token** and mapped through
 * this service's own directory — the same approach the portal's `StaffContextResolver` takes, and
 * the only identifier on the request that a caller cannot choose.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenant Portal Configuration", description = "Customer-portal enablement per tenant")
class TenantPortalConfigController(
    private val tenantRepository: TenantDatabaseMappingRepository,
    @Value("\${jwt.secret:freighai-dev-secret-key-256-bits-minimum-for-hs256-algorithm}")
    private val jwtSecret: String,
    /** Must match `portal.base-url` in wms-customer-portal-service, which builds email links from it. */
    @Value("\${portal.base-url:http://localhost:3100}")
    private val portalBaseUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    companion object {
        /** Uppercase alphanumeric plus hyphen, 2–32 characters. Mirrors the portal-side validation. */
        private val CODE_PATTERN = Regex("^[A-Z0-9][A-Z0-9-]{1,31}$")

        /**
         * The HMAC variants a staff token may be signed with, and nothing else.
         *
         * This was hardcoded to HmacSHA256, on the reasoning that never reading `alg` defeats
         * algorithm confusion. Sound reasoning, wrong conclusion: the platform issues **HS384** too
         * (see JwtTokenExtractor in wms-common), and jjwt — which every other service validates
         * with — reads `alg` and picks the matching HMAC. Computing SHA-256 unconditionally
         * rejected every genuine HS384 token, which is exactly what reached production: the admin
         * screen reported "Authentication required" for a valid staff session.
         *
         * An allowlist keeps the protection and drops the false rejection. `none`, `RS*`, `ES*` and
         * `PS*` are absent, so verification cannot be skipped and a public key cannot be handed to
         * an HMAC. Every entry uses the same symmetric key.
         */
        private val HMAC_BY_ALG = mapOf(
            "HS256" to "HmacSHA256",
            "HS384" to "HmacSHA384",
            "HS512" to "HmacSHA512"
        )
    }

    /**
     * Refuses unless the caller is acting for the tenant named in the path.
     *
     * ## The signature is verified here, and the reason it once was not was wrong
     *
     * This method previously base64-decoded the payload without checking the signature, on the
     * stated grounds that "the gateway has already done that against the staff HMAC key, which this
     * service does not hold", and that "a forged claim that resolves to nothing yields no access".
     * Both were false:
     *
     *  - This service *does* hold the key. It is the same `jwt.secret` the gateway uses, injected
     *    below with the same default.
     *  - The second half was false specifically for the `clientId` branch, which took the caller's
     *    asserted integer **verbatim, with no directory lookup at all**. There was nothing for it to
     *    "resolve to nothing" against.
     *
     * And "the gateway has already verified it" is an assumption about network topology, not a
     * control: port 6010 is published to Eureka and reachable from anywhere inside the cluster. A
     * token signed with any key at all was accepted, so any service — or anything that reached the
     * port — could read, set or delete **any tenant's portal code**, which is what decides whether
     * and how that tenant's customers can log in to the portal at all.
     *
     * This is the identical flaw that was just removed from the customer portal's
     * `StaffContextResolver`; this controller had copied the pattern before it was fixed there.
     *
     * @throws ResponseStatusException 404 when the caller is acting for a different tenant, so the
     *   response cannot be used to discover which client ids exist.
     */
    private fun assertActingFor(clientId: Int, http: HttpServletRequest) {
        val header = http.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith("Bearer ")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        }

        val claims = verifiedClaims(header.substring(7))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")

        // Both branches must survive a directory lookup. Returning the `clientId` claim directly
        // made the acting tenant a value the caller simply asserted, which is weaker than reading a
        // header — at least a header is visibly untrusted.
        val actingClientId = (claims["tenant_id"] as? String)
            ?.let { tenantRepository.findByFreighaiTenantId(it).orElse(null)?.clientId }
            ?: (claims["clientId"] as? Number)?.toInt()
                ?.let { tenantRepository.findByClientId(it).orElse(null)?.clientId }

        if (actingClientId == null || actingClientId != clientId) {
            logger.warn(
                "Cross-tenant portal-config attempt: caller acting for {} requested tenant {}",
                actingClientId, clientId
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")
        }
    }

    /**
     * Verifies the token's HMAC and expiry, then returns its claims.
     *
     * `alg` IS read, but only to select among [HMAC_BY_ALG] — validating it, not dispatching on it.
     * An absent or unrecognised `alg` is refused, so `none`, RS256-to-HS256 confusion and every
     * asymmetric algorithm remain impossible, while the HMAC variants the platform actually issues
     * all verify. [MessageDigest.isEqual] is the length-safe, non-short-circuiting comparison.
     *
     * @return the claims, or null if the token is malformed, wrongly signed, expired, or of a kind
     *   that must not reach this surface.
     */
    private fun verifiedClaims(token: String): Map<String, Any?>? = try {
        val parts = token.split('.')
        when {
            parts.size != 3 -> null
            else -> {
                @Suppress("UNCHECKED_CAST")
                val header = mapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]), Map::class.java
                ) as Map<String, Any?>

                val jcaAlgorithm = HMAC_BY_ALG[(header["alg"] as? String)?.uppercase()]
                if (jcaAlgorithm == null) {
                    logger.warn("Portal-config token rejected: unsupported alg '{}'", header["alg"])
                    null
                } else {
                    val mac = Mac.getInstance(jcaAlgorithm)
                    mac.init(SecretKeySpec(jwtSecret.toByteArray(StandardCharsets.UTF_8), jcaAlgorithm))
                    val expected = mac.doFinal(
                        "${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.UTF_8)
                    )

                    if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
                        logger.warn("Portal-config token rejected: signature does not verify")
                        null
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val claims = mapper.readValue(
                            Base64.getUrlDecoder().decode(parts[1]), Map::class.java
                        ) as Map<String, Any?>

                        val exp = (claims["exp"] as? Number)?.toLong()
                        val kind = (claims["typ"] as? String) ?: (claims["type"] as? String)
                        when {
                            exp == null || Instant.now().epochSecond >= exp -> null
                            // A refresh token is signed with the same secret and would otherwise be
                            // a long-lived credential for changing a tenant's portal configuration.
                            kind?.lowercase() in setOf("refresh", "service") -> null
                            else -> claims
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.debug("Could not verify portal-config token: {}", e.message)
        null
    }

    @GetMapping("/{clientId}/portal-config")
    @Operation(summary = "Read a tenant's customer-portal configuration")
    fun getPortalConfig(@PathVariable clientId: Int, http: HttpServletRequest): ResponseEntity<ApiResponse<PortalConfigResponse>> {
        assertActingFor(clientId, http)
        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Tenant not found"))

        return ResponseEntity.ok(
            ApiResponse.success(
                portalConfigOf(tenant.clientId, tenant.tenantName, tenant.portalTenantCode),
                "Portal configuration retrieved"
            )
        )
    }

    /**
     * Sets the portal code, switching the customer portal on for this tenant.
     *
     * Uniqueness is enforced here rather than only by an index, so the caller gets a usable message
     * instead of a duplicate-key error.
     */
    @PutMapping("/{clientId}/portal-config")
    @Operation(summary = "Set a tenant's customer-portal code")
    fun setPortalConfig(
        @PathVariable clientId: Int,
        @Valid @RequestBody request: SetPortalCodeRequest,
        http: HttpServletRequest
    ): ResponseEntity<ApiResponse<PortalConfigResponse>> {
        assertActingFor(clientId, http)
        val code = request.portalTenantCode.trim().uppercase()
        if (!CODE_PATTERN.matches(code)) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error(
                    "Workspace code must be 2–32 characters, uppercase letters, digits or hyphens, " +
                        "and must not start with a hyphen"
                )
            )
        }

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Tenant not found"))

        val clash = tenantRepository.findAll().firstOrNull {
            it.portalTenantCode == code && it.clientId != clientId
        }
        if (clash != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error("That workspace code is already in use")
            )
        }

        val saved = tenantRepository.save(tenant.copy(portalTenantCode = code))
        logger.info("Portal code for tenant {} set to '{}'", clientId, code)

        return ResponseEntity.ok(
            ApiResponse.success(
                portalConfigOf(saved.clientId, saved.tenantName, saved.portalTenantCode),
                "Customer portal enabled"
            )
        )
    }

    /**
     * Clears the code, disabling the portal for this tenant.
     *
     * Portal user records are deliberately left intact — this is a switch, not a delete, so
     * re-enabling later restores every existing login rather than requiring a re-invite.
     */
    @DeleteMapping("/{clientId}/portal-config")
    @Operation(summary = "Disable the customer portal for a tenant")
    fun disablePortal(
        @PathVariable clientId: Int,
        http: HttpServletRequest
    ): ResponseEntity<ApiResponse<PortalConfigResponse>> {
        assertActingFor(clientId, http)
        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Tenant not found"))

        val saved = tenantRepository.save(tenant.copy(portalTenantCode = null))
        logger.warn("Customer portal DISABLED for tenant {}", clientId)

        return ResponseEntity.ok(
            ApiResponse.success(
                portalConfigOf(saved.clientId, saved.tenantName, null),
                "Customer portal disabled"
            )
        )
    }

    /**
     * Builds the response, including the URL customers actually use.
     *
     * Centralised so the three endpoints cannot disagree about how the URL is formed — a read path
     * returning a different shape from the write path is exactly the kind of thing an admin screen
     * renders inconsistently and nobody notices until a customer is sent the wrong link.
     *
     * The host comes from configuration rather than the client, because it differs between local,
     * staging and production and the leadtorev-to-freighai move is still undecided. A hostname
     * hardcoded in an admin screen looks authoritative and will eventually be wrong.
     */
    private fun portalConfigOf(clientId: Int, tenantName: String, code: String?): PortalConfigResponse {
        val base = portalBaseUrl.trimEnd('/')
        return PortalConfigResponse(
            clientId = clientId,
            tenantName = tenantName,
            portalTenantCode = code,
            portalEnabled = !code.isNullOrBlank(),
            portalUrl = code?.takeIf { it.isNotBlank() }?.let { "$base/$it/login" },
            portalBaseUrl = base
        )
    }
}

data class SetPortalCodeRequest(
    @field:NotBlank(message = "Workspace code is required")
    val portalTenantCode: String
)

data class PortalConfigResponse(
    val clientId: Int,
    val tenantName: String,
    val portalTenantCode: String?,
    val portalEnabled: Boolean,

    /**
     * Where this tenant's customers actually sign in, or null when the portal is not enabled.
     *
     * Returned by the server rather than assembled by the client, because the host is deployment
     * configuration: it differs between local, staging and production, and the migration from
     * leadtorev to freighai is still undecided. A hardcoded hostname in an admin screen is a
     * support call waiting to happen — it will look authoritative and be wrong.
     */
    val portalUrl: String?,

    /** The host portion alone, so a client can render `{base}/{CODE}` while the code is typed. */
    val portalBaseUrl: String
)
