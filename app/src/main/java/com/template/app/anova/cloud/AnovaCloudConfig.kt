package com.template.app.anova.cloud

object AnovaCloudConfig {
    const val FIREBASE_API_KEY = "AIzaSyDQiOP2fTR9zvFcag2kSbcmG9zPh6gZhHw"

    const val FIREBASE_SIGN_IN_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_API_KEY"
    const val FIREBASE_SIGN_IN_WITH_IDP_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$FIREBASE_API_KEY"
    const val FIREBASE_REFRESH_URL =
        "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY"

    const val FIREBASE_WEB_CLIENT_ID =
        "322173998509-vsa6hecaqqp5cjsaja9h3cds1bhgrq3f.apps.googleusercontent.com"
    const val FIREBASE_AUTH_HANDLER_HOST = "anova-app.firebaseapp.com"
    const val FIREBASE_AUTH_HANDLER_URL  = "https://anova-app.firebaseapp.com/__/auth/handler"

    const val ANOVA_AUTH_URL = "https://anovaculinary.io/authenticate"
    const val ANOVA_WS_BASE  = "wss://devices.anovaculinary.io/"

    // Refresh Firebase ID token 5 min before expiry
    const val TOKEN_EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    // Refresh Anova JWT when fewer than 90 days remain
    const val ANOVA_JWT_REFRESH_THRESHOLD_MS = 90L * 24 * 60 * 60 * 1000
}
