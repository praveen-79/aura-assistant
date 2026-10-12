package com.aura.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
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
 * Connects to the Python FastAPI Brain on the local network or automatically falls back
 * to the bundled offline Android asset (`file:///android_asset/index.html`) when away from PC.
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
                    if (url.startsWith("whatsapp://") || url.startsWith("sms:")) {
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
                        // Automatic standalone Android APK fallback when away from PC server
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
        // Load standalone bundled Aura UI immediately (zero network delay on physical phones)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestRequiredPermissions() {
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    fun placeDirectCall(phoneNumber: String) {
        val uri = Uri.parse("tel:${phoneNumber.trim()}")
        val hasCallPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        startActivity(intent)
    }

    fun findContactNumber(spokenName: String): String? {
        val resolver = contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$spokenName%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(1)
            }
        }
        return null
    }

    inner class AndroidNativeBridge {
        @JavascriptInterface
        fun callContact(nameOrNumber: String): String {
            val number = findContactNumber(nameOrNumber) ?: nameOrNumber
            placeDirectCall(number)
            return number
        }

        @JavascriptInterface
        fun setTorch(enabled: Boolean) {
            val camManager = getSystemService(CameraManager::class.java)
            val cameraId = camManager.cameraIdList.firstOrNull() ?: return
            camManager.setTorchMode(cameraId, enabled)
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
        fun clickScreenText(text: String): Boolean {
            return AuraAccessibilityService.instance?.clickNodeByText(text) ?: false
        }

        @JavascriptInterface
        fun scrollScreen(direction: String): Boolean {
            return AuraAccessibilityService.instance?.scrollScreen(direction) ?: false
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
