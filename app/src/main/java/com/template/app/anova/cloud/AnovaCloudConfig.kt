package com.template.app.anova.cloud

/**
 * Constants for Anova's unofficial cloud API.
 *
 * These were reverse-engineered from the official Anova app's network traffic by
 * the open-source community (projects: anova-wifi, py-anova-cooker, homebridge-anova).
 * Anova can change or revoke these endpoints at any time without notice.
 */
object AnovaCloudConfig {
    // Firebase project API key used by the official Anova app
    const val FIREBASE_API_KEY = "AIzaSyDQiOP2fTR9zvFcag2kSbcmG9zPh6gZhHw"

    const val FIREBASE_SIGN_IN_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_API_KEY"
    const val FIREBASE_SIGN_IN_WITH_IDP_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$FIREBASE_API_KEY"
    const val FIREBASE_REFRESH_URL =
        "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY"

    /**
     * Web client ID registered for the Anova Firebase project (used in Google OAuth URL).
     * Discovered via the Firebase createAuthUri endpoint.
     */
    const val FIREBASE_WEB_CLIENT_ID =
        "322173998509-vsa6hecaqqp5cjsaja9h3cds1bhgrq3f.apps.googleusercontent.com"

    /** Firebase auth handler host — we intercept WebView navigation to this host. */
    const val FIREBASE_AUTH_HANDLER_HOST = "anova-app.firebaseapp.com"

    /** Full Firebase auth handler URL — used as OAuth redirect_uri. */
    const val FIREBASE_AUTH_HANDLER_URL =
        "https://anova-app.firebaseapp.com/__/auth/handler"

    const val ANOVA_BASE_URL = "https://oven.anovaculinary.com"

    // Refresh the token 5 minutes before it actually expires to avoid mid-poll failures
    const val TOKEN_EXPIRY_MARGIN_MS = 5 * 60 * 1000L
}
