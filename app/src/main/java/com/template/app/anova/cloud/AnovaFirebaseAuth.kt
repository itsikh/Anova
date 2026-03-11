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

    /**
     * Returns a valid Firebase ID token, refreshing/re-authenticating as needed.
     * Returns null on authentication failure.
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

    // -----------------------------------------------------------------------------------------

    private suspend fun signIn(email: String, password: String): CachedToken? = withContext(Dispatchers.IO) {
        try {
            val body = """{"email":"${email.trim()}","password":"$password","returnSecureToken":true}"""
            val request = Request.Builder()
                .url(AnovaCloudConfig.FIREBASE_SIGN_IN_URL)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) {
                AppLogger.e(TAG, "Sign-in failed ${resp.code}: ${resp.body?.string()?.take(200)}")
                return@withContext null
            }
            val parsed = gson.fromJson(resp.body?.string(), FirebaseSignInResponse::class.java)
            val expiresAt = System.currentTimeMillis() + (parsed.expiresIn.toLongOrNull() ?: 3600L) * 1000
            AppLogger.i(TAG, "Signed in successfully")
            CachedToken(parsed.idToken, parsed.refreshToken, expiresAt)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sign-in error: ${e.message}")
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
