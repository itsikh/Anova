package com.template.app.ui.screens.monitor

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
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

// Google blocks sign-in from Android WebView user agents. Spoofing as Chrome bypasses this.
private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleSignInWebViewDialog(
    authUri: String,
    sessionId: String,
    onAuthRedirectIntercepted: (redirectUrl: String, sessionId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    AppLogger.i(TAG, "Dialog composed — loading authUri: ${authUri.take(80)}…")

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
                                // Spoof Chrome user agent — Google blocks "Android WebView" UA
                                settings.userAgentString = CHROME_UA
                                AppLogger.i(TAG, "WebView created, UA set to Chrome")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        AppLogger.d(TAG, "onPageStarted: ${url?.take(120)}")
                                        isLoading = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        AppLogger.d(TAG, "onPageFinished: ${url?.take(120)}")
                                        isLoading = false
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        val url = request?.url?.toString() ?: "?"
                                        val desc = error?.description ?: "?"
                                        val code = error?.errorCode ?: -1
                                        // Only log main-frame errors (not sub-resource failures)
                                        if (request?.isForMainFrame == true) {
                                            AppLogger.e(TAG, "Main frame error [$code] $desc — url: ${url.take(120)}")
                                        }
                                    }

                                    @Deprecated("Deprecated in Java")
                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                        AppLogger.e(TAG, "SSL error: ${error?.primaryError} — proceeding anyway")
                                        handler?.proceed()
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        val host = request.url?.host ?: ""
                                        AppLogger.d(TAG, "shouldOverrideUrlLoading host=$host url=${url.take(120)}")

                                        if (host == AnovaCloudConfig.FIREBASE_AUTH_HANDLER_HOST) {
                                            AppLogger.i(TAG, "✅ Intercepted Firebase auth handler redirect — exchanging for token")
                                            onAuthRedirectIntercepted(url, sessionId)
                                            return true
                                        }
                                        return false
                                    }
                                }

                                AppLogger.i(TAG, "WebView.loadUrl called")
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
