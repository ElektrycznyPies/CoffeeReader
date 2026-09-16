package com.electricdog.coffeereader.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.electricdog.coffeereader.BuildConfig
import com.electricdog.coffeereader.R

@Composable
internal fun CoffeeHelpPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val document = remember(context, language) {
        val available = context.assets.list("").orEmpty()
        val translated = "help_$language.html"
        if (translated in available) translated else "help_en.html"
    }
    val documentUrl = "file:///android_asset/$document"

    Column(modifier) {
        Text(
            text = stringResource(R.string.cr_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelLarge,
        )
        key(documentUrl) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        setBackgroundColor(Color.WHITE)
                        settings.apply {
                            javaScriptEnabled = false
                            domStorageEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            blockNetworkLoads = true
                            defaultTextEncodingName = "UTF-8"
                        }
                        // Only bundled help is displayed here. External links leave the panel.
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val uri = request.url
                                // Keep document anchors inside the help page.
                                if (uri.toString().substringBefore('#') == documentUrl) {
                                    return false
                                }
                                if (request.isForMainFrame &&
                                    uri.scheme in setOf("https", "http", "mailto")
                                ) {
                                    try {
                                        view.context.startActivity(
                                            Intent(Intent.ACTION_VIEW, uri)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        // Keep the help page open if no app can handle the link.
                                    }
                                }
                                return true
                            }
                        }
                        loadUrl(documentUrl)
                    }
                },
                onReset = null,
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                },
            )
        }
    }
}
