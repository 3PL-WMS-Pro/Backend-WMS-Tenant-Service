package com.wmspro.tenant.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/**
 * Login request — same shape leadtorev's `/users/login` accepted, so the WMS
 * frontend's `loginUserAPI` call doesn't change.
 */
data class LoginRequest(
    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email must be valid")
    val email: String,

    @field:NotBlank(message = "Password is required")
    val password: String
)

/**
 * Refresh / logout request — both FreighAi endpoints key off the refresh token,
 * not the access token, so these are usable with an already-expired access token.
 * That is why the gateway leaves `/users/refresh` and `/users/logout` public.
 */
data class RefreshTokenRequest(
    @field:NotBlank(message = "Refresh token is required")
    val refreshToken: String
)

/**
 * Login response — leadtorev-shaped fields the WMS frontend stores in
 * react-secure-storage after a successful login. See Frontend-WMS-V2 useAuth.js
 * `storeUserSession` for the exact list:
 *   AUTH_TOKEN     ← "Bearer " + authToken
 *   CLIENT_ID      ← clientId
 *   ACCESS_CONTROL ← JSON.stringify(accessLevel)
 *   EMAIL          ← email
 *   FULL_NAME      ← fullName
 *   SESSION_ID     ← loginSession.id
 *   USER_TYPE      ← userTypeId
 *   USER_ABBREVIATION ← derived from fullName
 *
 * The mobile client (WMS-Executive-Mobile-App) consumes the same payload plus
 * `refreshToken`, `firstName`/`lastName` and `loginSession.tokenExpiry`. Those
 * three were added additively — the web app reads by field name and ignores
 * anything extra, so no web change was needed.
 */
data class LoginResponse(
    val authToken: String,                   // bare JWT — clients prepend "Bearer "
    val refreshToken: String,                // FreighAi refresh token (7d TTL, rotated on use)
    val clientId: Int,                       // WMS-internal Long tenant id, e.g. 199
    val accessLevel: Map<String, Any>,       // serialized as JSON object on frontend
    val email: String,
    val fullName: String,
    val firstName: String,                   // split from FreighAi `name` on first space
    val lastName: String,                    // remainder after the first space; "" if none
    val loginSession: LoginSession,
    val userTypeId: String                   // FreighAi role string (e.g. "ADMIN")
)

data class LoginSession(
    val id: String,                          // synthesised UUID at login time

    /**
     * Absolute expiry of `authToken`, ISO-8601 UTC (e.g. "2026-08-18T09:12:33Z"),
     * computed as now + FreighAi's `expiresIn` seconds.
     *
     * The mobile client gates every request on this and force-logs-out when it is
     * absent or past — so omitting it puts the app in an immediate logout loop.
     * The web client ignores it and uses its own 10h client-side timer.
     */
    val tokenExpiry: String
)
