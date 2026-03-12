package com.template.app.anova.cloud

import android.util.Base64
import com.google.gson.Gson
import com.template.app.logging.AppLogger
import com.template.app.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaFirebaseAuth"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()

// SecureKeyManager keys
private const val KEY_REFRESH_TOKEN = "firebase_refresh_token"
private const val KEY_ANOVA_JWT      = "anova_jwt"
private const val KEY_AUTH_EMAIL     = "anova_auth_email"

data class CachedToken(
    val idToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

@Singleton
class AnovaFirebaseAuth @Inject constructor(
    private val secureKeyManager: SecureKeyManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson   = Gson()

    @Volatile private var cached: CachedToken? = null
    @Volatile var lastSignInError: String? = null
        private set

    // ── Anova JWT ─────────────────────────────────────────────────────────────

    /** Expiry of the stored Anova JWT as epoch-millis, or 0 if not stored. */
    val anovaJwtExpiryMs: Long
        get() {
            val jwt = secureKeyManager.getKey(KEY_ANOVA_JWT) ?: return 0L
            return decodeJwtExpiry(jwt)
        }

    /** Email associated with the stored session, or null. */
    val storedEmail: String?
        get() = secureKeyManager.getKey(KEY_AUTH_EMAIL)

    /** True if we have a stored Anova JWT that hasn't expired. */
    val hasStoredSession: Boolean
        get() = anovaJwtExpiryMs > System.currentTimeMillis()

    /**
     * Returns a valid Anova JWT, refreshing if needed.
     * Loads the Firebase refresh token from secure storage on first call.
     * Returns null if unauthenticated.
     */
    suspend fun getAnovaJwt(): String? {
        // Return cached JWT if still valid (with 90-day refresh threshold)
        val storedJwt = secureKeyManager.getKey(KEY_ANOVA_JWT)
        if (storedJwt != null) {
            val expiry = decodeJwtExpiry(storedJwt)
            val now = System.currentTimeMillis()
            if (expiry - now > AnovaCloudConfig.ANOVA_JWT_REFRESH_THRESHOLD_MS) {
                AppLogger.d(TAG, "Anova JWT cache hit (expires in ${(expiry - now) / 86400_000}d)")
                return storedJwt
            }
            AppLogger.i(TAG, "Anova JWT within 90-day refresh window — refreshing")
        }

        // Get a Firebase ID token (using stored refresh token)
        val firebaseToken = getValidTokenOrRefresh() ?: return null

        // Exchange for fresh Anova JWT
        return exchangeForAnovaJwt(firebaseToken)
    }

    /** Called after email/password sign-in — also exchanges for and stores the Anova JWT. */
    suspend fun getValidToken(email: String, password: String): String? {
        val existing = cached
        if (existing != null && System.currentTimeMillis() < existing.expiresAt - AnovaCloudConfig.TOKEN_EXPIRY_MARGIN_MS) {
            return existing.idToken
        }
        if (existing?.refreshToken != null) {
            val refreshed = refresh(existing.refreshToken)
            if (refreshed != null) {
                cached = refreshed
                persistRefreshToken(refreshed.refreshToken)
                return refreshed.idToken
            }
        }
        val signed = signIn(email, password) ?: return null
        cached = signed
        persistRefreshToken(signed.refreshToken)
        secureKeyManager.saveKey(KEY_AUTH_EMAIL, email)
        exchangeForAnovaJwt(signed.idToken)
        return signed.idToken
    }

    /**
     * Returns a valid Firebase ID token using only the cache or persisted refresh token.
     * Loads the stored refresh token from SecureKeyManager if memory cache is empty.
     */
    suspend fun getValidTokenOrRefresh(): String? {
        val existing = cached
        if (existing != null && System.currentTimeMillis() < existing.expiresAt - AnovaCloudConfig.TOKEN_EXPIRY_MARGIN_MS) {
            return existing.idToken
        }

        // Try memory cache refresh token first, then fall back to persisted one
        val refreshToken = existing?.refreshToken
            ?: secureKeyManager.getKey(KEY_REFRESH_TOKEN)

        if (refreshToken != null) {
            val refreshed = refresh(refreshToken)
            if (refreshed != null) {
                cached = refreshed
                persistRefreshToken(refreshed.refreshToken)
                return refreshed.idToken
            }
        }
        AppLogger.w(TAG, "No valid cached token and no persisted refresh token")
        return null
    }

    fun clearToken() {
        cached = null
    }

    /** Remove all stored credentials. */
    fun clearAll() {
        cached = null
        secureKeyManager.deleteKey(KEY_REFRESH_TOKEN)
        secureKeyManager.deleteKey(KEY_ANOVA_JWT)
        secureKeyManager.deleteKey(KEY_AUTH_EMAIL)
    }

    /**
     * Store a Firebase refresh token from an external source (e.g. pasted by the user
     * from the Mac HTML auth page). This seeds the persistent session.
     */
    suspend fun seedRefreshToken(refreshToken: String, email: String? = null): Boolean {
        val refreshed = refresh(refreshToken) ?: return false
        cached = refreshed
        persistRefreshToken(refreshed.refreshToken)
        if (email != null) secureKeyManager.saveKey(KEY_AUTH_EMAIL, email)
        exchangeForAnovaJwt(refreshed.idToken)
        return true
    }

    // ── Google OAuth ──────────────────────────────────────────────────────────

    /**
     * Builds a Google OAuth URL using the OpenID Connect implicit flow
     * (response_type=id_token). Google returns the id_token directly in the
     * redirect URL fragment — no server-side code exchange needed, no client
     * secret required.
     *
     * Returns Pair(authUrl, nonce).
     */
    fun createGoogleAuthUri(): Pair<String, String> {
        val nonce = buildSessionId()
        val authUri = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=${AnovaCloudConfig.FIREBASE_WEB_CLIENT_ID}" +
            "&response_type=id_token" +
            "&scope=openid%20email%20profile" +
            "&redirect_uri=${AnovaCloudConfig.FIREBASE_AUTH_HANDLER_URL}" +
            "&nonce=$nonce"
        return Pair(authUri, nonce)
    }

    /**
     * Extracts the id_token from the redirect URL fragment (placed there by
     * the implicit flow) and delegates to [signInWithGoogleIdToken].
     */
    suspend fun signInWithGoogleRedirectUrl(redirectUrl: String, nonce: String): String? =
        withContext(Dispatchers.IO) {
            lastSignInError = null
            val fragment = redirectUrl.substringAfter('#', "")
            val idToken = fragment.split('&')
                .firstOrNull { it.startsWith("id_token=") }
                ?.removePrefix("id_token=")
            if (idToken.isNullOrBlank()) {
                AppLogger.e(TAG, "No id_token in redirect fragment: ${redirectUrl.take(120)}")
                lastSignInError = "Google sign-in failed: no token in response."
                return@withContext null
            }
            AppLogger.i(TAG, "Extracted id_token from redirect fragment — signing in…")
            signInWithGoogleIdToken(idToken)
        }

    suspend fun signInWithGoogleIdToken(googleIdToken: String): String? = withContext(Dispatchers.IO) {
        lastSignInError = null
        try {
            val body = """{"requestUri":"http://localhost","postBody":"id_token=$googleIdToken&providerId=google.com","returnSecureToken":true,"returnIdpCredential":true}"""
            val request = Request.Builder()
                .url(AnovaCloudConfig.FIREBASE_SIGN_IN_WITH_IDP_URL)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                lastSignInError = "Google sign-in failed (HTTP ${resp.code})."
                return@withContext null
            }
            val parsed = gson.fromJson(resp.body?.string(), FirebaseIdpResponse::class.java)
            val expiresAt = System.currentTimeMillis() + (parsed.expiresIn?.toLongOrNull() ?: 3600L) * 1000
            cached = CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
            persistRefreshToken(parsed.refreshToken)
            if (parsed.email != null) secureKeyManager.saveKey(KEY_AUTH_EMAIL, parsed.email)
            exchangeForAnovaJwt(parsed.idToken)
            parsed.idToken
        } catch (e: Exception) {
            lastSignInError = "Google sign-in failed: ${e.message}"
            null
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun exchangeForAnovaJwt(firebaseIdToken: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(AnovaCloudConfig.ANOVA_AUTH_URL)
                    .post("{}".toRequestBody(JSON_TYPE))
                    .header("firebase-token", firebaseIdToken)
                    .build()
                val resp = client.newCall(request).execute()
                if (!resp.isSuccessful) {
                    AppLogger.w(TAG, "Anova JWT exchange failed: ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                AppLogger.d(TAG, "Anova auth response: ${body.take(100)}")
                val parsed = gson.fromJson(body, AnovaAuthResponse::class.java)
                val jwt = parsed.jwt
                secureKeyManager.saveKey(KEY_ANOVA_JWT, jwt)
                val expMs = decodeJwtExpiry(jwt)
                AppLogger.i(TAG, "Anova JWT stored — expires ${java.util.Date(expMs)}")
                jwt
            } catch (e: Exception) {
                AppLogger.w(TAG, "Anova JWT exchange error: ${e.message}")
                null
            }
        }

    private fun persistRefreshToken(token: String) {
        secureKeyManager.saveKey(KEY_REFRESH_TOKEN, token)
    }

    private fun buildSessionId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Decode the `exp` claim from a JWT payload. Returns 0 on failure. */
    fun decodeJwtExpiry(jwt: String): Long {
        return try {
            val payload = jwt.split(".").getOrNull(1) ?: return 0L
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
            val exp = JSONObject(json).optLong("exp", 0L)
            exp * 1000L // convert seconds to millis
        } catch (e: Exception) {
            AppLogger.w(TAG, "JWT expiry decode failed: ${e.message}")
            0L
        }
    }

    private suspend fun signIn(email: String, password: String): CachedToken? = withContext(Dispatchers.IO) {
        lastSignInError = null
        try {
            val body = """{"email":"${email.trim()}","password":"$password","returnSecureToken":true}"""
            val request = Request.Builder()
                .url(AnovaCloudConfig.FIREBASE_SIGN_IN_URL)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                lastSignInError = when {
                    "INVALID_PASSWORD" in err || "INVALID_LOGIN_CREDENTIALS" in err -> "Incorrect password."
                    "EMAIL_NOT_FOUND" in err || "INVALID_EMAIL" in err -> "Email not found."
                    "TOO_MANY_ATTEMPTS_TRY_LATER" in err -> "Too many attempts. Try later."
                    "USER_DISABLED" in err -> "Account disabled."
                    else -> "Authentication failed (${resp.code})."
                }
                return@withContext null
            }
            val parsed = gson.fromJson(resp.body?.string(), FirebaseSignInResponse::class.java)
            val expiresAt = System.currentTimeMillis() + (parsed.expiresIn.toLongOrNull() ?: 3600L) * 1000
            CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
        } catch (e: Exception) {
            lastSignInError = "Cannot reach servers: ${e.message}"
            null
        }
    }

    private suspend fun refresh(refreshToken: String): CachedToken? = withContext(Dispatchers.IO) {
        try {
            val body = "grant_type=refresh_token&refresh_token=$refreshToken"
            val request = Request.Builder()
                .url(AnovaCloudConfig.FIREBASE_REFRESH_URL)
                .post(body.toRequestBody(FORM_TYPE))
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "Token refresh failed ${resp.code}")
                return@withContext null
            }
            val parsed = gson.fromJson(resp.body?.string(), FirebaseRefreshResponse::class.java)
            val expiresAt = System.currentTimeMillis() + (parsed.expiresIn.toLongOrNull() ?: 3600L) * 1000
            CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Refresh error: ${e.message}")
            null
        }
    }
}
