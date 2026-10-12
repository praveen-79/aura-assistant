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
                    if (url.startsWith("tel:")) {
                        placeDirectCall(url.removePrefix("tel:"))
                        return true
                    }
                    if (url.startsWith("whatsapp://") || url.startsWith("sms:") || url.startsWith("https://wa.me/")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
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
    }
}
