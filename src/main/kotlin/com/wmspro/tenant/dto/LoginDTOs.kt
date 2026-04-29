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
 */
data class LoginResponse(
    val authToken: String,                   // bare JWT — frontend prepends "Bearer "
    val clientId: Int,                       // WMS-internal Long tenant id, e.g. 199
    val accessLevel: Map<String, Any>,       // serialized as JSON object on frontend
    val email: String,
    val fullName: String,
    val loginSession: LoginSession,
    val userTypeId: String                   // FreighAi role string (e.g. "ADMIN")
)

data class LoginSession(
    val id: String                           // synthesised UUID at login time
)
