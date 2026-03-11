package com.template.app.ui.screens.monitor

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.template.app.anova.cloud.AnovaCloudConfig
import com.template.app.logging.AppLogger

private const val TAG = "GoogleSignInWebView"

/**
 * Full-screen dialog that hosts a WebView for Google OAuth sign-in.
 *
 * Flow:
 * 1. WebView loads [authUri] (Google's OAuth consent screen via Firebase's createAuthUri)
 * 2. User signs in with their Google account
 * 3. Google redirects to https://anova-app.firebaseapp.com/__/auth/handler?code=...
 * 4. We intercept that redirect in [WebViewClient.shouldOverrideUrlLoading]
 * 5. [onAuthRedirectIntercepted] is called with the full redirect URL + [sessionId]
 *
 * The caller (MonitorScreen) is responsible for calling Firebase signInWithIdp with
 * the intercepted URL and sessionId.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleSignInWebViewDialog(
    authUri: String,
    sessionId: String,
    onAuthRedirectIntercepted: (redirectUrl: String, sessionId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Surface(shadowElevation = 2.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Sign in with Google",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) { Text("Cancel") }
                    }
                }

                Spacer(Modifier.height(0.dp))

                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        val host = request.url?.host ?: ""

                                        // Intercept Google's redirect back to Firebase's auth handler
                                        if (host == AnovaCloudConfig.FIREBASE_AUTH_HANDLER_HOST) {
                                            AppLogger.i(TAG, "Intercepted Firebase auth handler redirect")
                                            onAuthRedirectIntercepted(url, sessionId)
                                            return true // prevent WebView from loading it
                                        }
                                        return false
                                    }
                                }
                                loadUrl(authUri)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}
