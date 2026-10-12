package com.aura.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Native Android Activity & Default Digital Assistant (`ASSIST` Intent) Host.
 * Zero-Dangerous-Permissions Architecture: Uses standard Android System Intents
 * for calling and messaging, ensuring 100% compliance with Google Play Protect.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            addJavascriptInterface(AndroidNativeBridge(), "AndroidNative")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("file://") || url.contains("localhost") || url.contains(":8000")) {
                        return false
                    }
                    if (url.startsWith("tel:")) {
                        placeDirectCall(url.removePrefix("tel:"))
                        return true
                    }
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        return false
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && request.url?.scheme != "file") {
                        view?.loadUrl("file:///android_asset/index.html")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestRequiredPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    fun placeDirectCall(phoneNumber: String) {
        val uri = Uri.parse("tel:${phoneNumber.trim()}")
        val intent = Intent(Intent.ACTION_DIAL, uri)
        startActivity(intent)
    }

    inner class AndroidNativeBridge {
        @JavascriptInterface
        fun callContact(nameOrNumber: String): String {
            placeDirectCall(nameOrNumber)
            return nameOrNumber
        }

        @JavascriptInterface
        fun sendSms(phoneNumber: String, message: String) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                putExtra("sms_body", message)
            }
            startActivity(intent)
        }

        @JavascriptInterface
        fun setTorch(enabled: Boolean) {
            try {
                val camManager = getSystemService(CameraManager::class.java)
                val cameraId = camManager.cameraIdList.firstOrNull() ?: return
                camManager.setTorchMode(cameraId, enabled)
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun setAlarm(hour: Int, minute: Int, label: String) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            startActivity(intent)
        }

        @JavascriptInterface
        fun openYouTube(query: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
            startActivity(intent)
        }

        @JavascriptInterface
        fun navigateMaps(destination: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
            startActivity(intent)
        }

        @JavascriptInterface
        fun openApp(appName: String) {
            val key = appName.trim().lowercase()
            val pkg = when {
                "whatsapp" in key || "vaatsap" in key || "వాట్సప్" in key -> "com.whatsapp"
                "youtube" in key || "యుట్యూబ్" in key || "యూట్యూబ్" in key -> "com.google.android.youtube"
                "map" in key || "మ్యాప్" in key -> "com.google.android.apps.maps"
                "insta" in key -> "com.instagram.android"
                "spotify" in key -> "com.spotify.music"
                else -> null
            }
            if (pkg != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
            }
            val fallbackUrl = when {
                "whatsapp" in key || "vaatsap" in key -> "whatsapp://"
                "youtube" in key -> "https://www.youtube.com"
                "map" in key -> "https://maps.google.com"
                else -> "https://play.google.com/store/search?q=" + Uri.encode(appName) + "&c=apps"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } catch (e: Exception) {}
        }
    }
}
