package com.template.app.anova.cloud

import com.google.gson.Gson
import com.template.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnovaFirebaseAuth"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()

data class CachedToken(
    val idToken: String,
    val refreshToken: String,
    val expiresAt: Long  // System.currentTimeMillis() at expiry
)

/**
 * Manages Firebase ID tokens for the Anova cloud API.
 *
 * Tokens expire after 1 hour. This class caches the current token and transparently
 * refreshes it (or re-authenticates) when it nears expiry, so callers can always
 * call [getValidToken] without worrying about expiry.
 */
@Singleton
class AnovaFirebaseAuth @Inject constructor() {
    private val client = OkHttpClient()
    private val gson = Gson()

    @Volatile private var cached: CachedToken? = null
    /** Human-readable error from the last failed sign-in attempt, or null if no error. */
    @Volatile var lastSignInError: String? = null
        private set

    /**
     * Returns a valid Firebase ID token, refreshing/re-authenticating as needed.
     * Returns null on authentication failure; check [lastSignInError] for the reason.
     */
    suspend fun getValidToken(email: String, password: String): String? {
        val existing = cached
        if (existing != null && System.currentTimeMillis() < existing.expiresAt - AnovaCloudConfig.TOKEN_EXPIRY_MARGIN_MS) {
            AppLogger.d(TAG, "Token cache hit (${(existing.expiresAt - System.currentTimeMillis()) / 1000}s remaining)")
            return existing.idToken
        }

        // Try token refresh first (avoids sending password over the wire unnecessarily)
        if (existing?.refreshToken != null) {
            val refreshed = refresh(existing.refreshToken)
            if (refreshed != null) { cached = refreshed; return refreshed.idToken }
        }

        // Full sign-in
        val signed = signIn(email, password)
        cached = signed
        return signed?.idToken
    }

    fun clearToken() { cached = null }

    /**
     * Returns a valid Firebase ID token using only the cache or refresh token — no credentials needed.
     * Used after Google SSO login where email/password are not stored.
     */
    suspend fun getValidTokenOrRefresh(): String? {
        val existing = cached
        if (existing != null && System.currentTimeMillis() < existing.expiresAt - AnovaCloudConfig.TOKEN_EXPIRY_MARGIN_MS) {
            AppLogger.d(TAG, "Token cache hit (no-credentials path)")
            return existing.idToken
        }
        if (existing?.refreshToken != null) {
            val refreshed = refresh(existing.refreshToken)
            if (refreshed != null) { cached = refreshed; return refreshed.idToken }
        }
        AppLogger.w(TAG, "No valid cached token and no credentials to re-authenticate")
        return null
    }

    /**
     * Exchanges a Google ID token (from CredentialManager) for a Firebase ID token via signInWithIdp.
     * On success the token is cached so subsequent calls use the refresh path.
     * Use this after obtaining a GoogleIdTokenCredential from CredentialManager.
     */
    suspend fun signInWithGoogleIdToken(googleIdToken: String): String? = withContext(Dispatchers.IO) {
        lastSignInError = null
        AppLogger.i(TAG, "Exchanging Google ID token for Firebase token via signInWithIdp…")
        try {
            val body = """{"requestUri":"http://localhost","postBody":"id_token=$googleIdToken&providerId=google.com","returnSecureToken":true,"returnIdpCredential":true}"""
            val request = Request.Builder()
                .url(AnovaCloudConfig.FIREBASE_SIGN_IN_WITH_IDP_URL)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: ""
                AppLogger.e(TAG, "Google sign-in failed ${resp.code}: ${errorBody.take(200)}")
                lastSignInError = "Google sign-in failed (HTTP ${resp.code})."
                return@withContext null
            }
            val parsed = gson.fromJson(resp.body?.string(), FirebaseIdpResponse::class.java)
            val expiresAt = System.currentTimeMillis() + (parsed.expiresIn?.toLongOrNull() ?: 3600L) * 1000
            cached = CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
            AppLogger.i(TAG, "Google ID token → Firebase sign-in successful (email=${parsed.email})")
            parsed.idToken
        } catch (e: java.io.IOException) {
            AppLogger.e(TAG, "Google sign-in IO error: ${e.message}")
            lastSignInError = "Cannot reach Anova servers. Check your internet connection."
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Google sign-in error: ${e.message}")
            lastSignInError = "Google sign-in failed: ${e.message}"
            null
        }
    }

    // -----------------------------------------------------------------------------------------

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
                val errorBody = resp.body?.string() ?: ""
                AppLogger.e(TAG, "Sign-in failed ${resp.code}: ${errorBody.take(200)}")
                lastSignInError = when {
                    "INVALID_PASSWORD" in errorBody || "INVALID_LOGIN_CREDENTIALS" in errorBody ->
                        "Incorrect password. Check your Anova account credentials."
                    "EMAIL_NOT_FOUND" in errorBody || "INVALID_EMAIL" in errorBody ->
                        "Email not found. Check your Anova account email."
                    "TOO_MANY_ATTEMPTS_TRY_LATER" in errorBody ->
                        "Too many failed attempts. Please try again later."
                    "USER_DISABLED" in errorBody ->
                        "This account has been disabled."
                    else -> "Authentication failed (code ${resp.code})."
                }
                return@withContext null
            }
            val parsed = gson.fromJson(resp.body?.string(), FirebaseSignInResponse::class.java)
            val expiresAt = System.currentTimeMillis() + (parsed.expiresIn.toLongOrNull() ?: 3600L) * 1000
            AppLogger.i(TAG, "Signed in successfully")
            CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
        } catch (e: java.io.IOException) {
            AppLogger.e(TAG, "Sign-in error: ${e.message}")
            lastSignInError = "Cannot reach Anova servers. Check your internet connection."
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sign-in error: ${e.message}")
            lastSignInError = "Unexpected error during sign-in."
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
            AppLogger.d(TAG, "Token refreshed")
            CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Refresh error: ${e.message}")
            null
        }
    }
}
