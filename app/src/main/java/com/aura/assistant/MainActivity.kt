package com.aura.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Android Activity & Default Digital Assistant (`ASSIST` Intent) Host.
 * Connects WebView UI with real Android Contacts (`ContactsContract`), runtime permissions,
 * direct phone calling, installed app launching, RoleManager Assistant registration,
 * and `AuraAccessibilityService`.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.CAMERA
    )

    private val sampleDemoNumbers = mapOf(
        "+919876543210" to "Amma",
        "+919876543211" to "Appa",
        "+919811122233" to "Rahul",
        "+919822233344" to "Priya"
    )

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
                        val rawTarget = Uri.decode(url.removePrefix("tel:")).trim()
                        handleSmartCall(rawTarget)
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

    fun requestRequiredPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "if (window.onAndroidPermissionsGranted) window.onAndroidPermissionsGranted();",
                    null
                )
            }
        }
    }

    private fun readDeviceContactsList(): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val list = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim() ?: "" else ""
                    val cleanNum = rawNum.replace(Regex("[^0-9+]"), "")
                    if (name.isNotEmpty() && cleanNum.isNotEmpty()) {
                        val key = "${name.lowercase()}|$cleanNum"
                        if (seen.add(key)) {
                            list.add(name to cleanNum)
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return list
    }

    private fun lookupRealContact(queryRaw: String): Pair<String, String>? {
        val q = sampleDemoNumbers[queryRaw.trim()] ?: queryRaw.trim()
        val qLower = q.lowercase()
        val contacts = readDeviceContactsList()
        if (contacts.isEmpty()) return null

        val aliases = when {
            qLower in listOf("amma", "mom", "mummy", "mother", "అమ్మ", "अम्मा", "मम्मी", "அம்மா") ->
                listOf("amma", "mom", "mummy", "mother", "అమ్మ", "अम्मा")
            qLower in listOf("appa", "nanna", "dad", "papa", "father", "నాన్న", "అప్పా", "पापा", "अप्पा", "அப்பா") ->
                listOf("nanna", "appa", "dad", "papa", "father", "నాన్న", "पापा")
            else -> listOf(qLower)
        }

        // 1. Exact match on any alias
        for (alias in aliases) {
            contacts.firstOrNull { it.first.lowercase() == alias }?.let { return it }
        }
        // 2. Starts-with or word-boundary match
        for (alias in aliases) {
            contacts.firstOrNull {
                val n = it.first.lowercase()
                n.startsWith(alias) || n.split(" ").any { part -> part == alias }
            }?.let { return it }
        }
        // 3. Substring match
        for (alias in aliases) {
            if (alias.length >= 2) {
                contacts.firstOrNull { it.first.lowercase().contains(alias) }?.let { return it }
            }
        }
        return null
    }

    fun placeDirectCall(phoneNumber: String) {
        val clean = phoneNumber.trim()
        if (clean.isEmpty()) return
        val uri = Uri.parse("tel:$clean")
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
            } catch (ignored: Exception) {}
        }
    }

    private fun handleSmartCall(nameOrNumber: String): String {
        val trimmed = nameOrNumber.trim()
        if (trimmed.isEmpty()) return "EMPTY"

        // If READ_CONTACTS is not granted yet, request it now
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestRequiredPermissions()
        }

        // Always try looking up the real contact in the phone first
        val matched = lookupRealContact(trimmed)
        if (matched != null) {
            placeDirectCall(matched.second)
            return "FOUND:${matched.first}|${matched.second}"
        }

        // If user spoke/typed an actual phone number (and NOT one of the fake sample demo numbers)
        val isSampleNumber = sampleDemoNumbers.containsKey(trimmed)
        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        if (!isSampleNumber && digitsOnly.length >= 7 && trimmed.all { it.isDigit() || it in "+ -()" }) {
            placeDirectCall(digitsOnly)
            return "FOUND:$digitsOnly|$digitsOnly"
        }

        // Open dialer without dialing a fake number
        try {
            startActivity(Intent(Intent.ACTION_DIAL))
        } catch (e: Exception) {}
        val displayQuery = sampleDemoNumbers[trimmed] ?: trimmed
        return "NOT_FOUND:$displayQuery"
    }

    inner class AndroidNativeBridge {

        @JavascriptInterface
        fun getDefaultApiKey(): String {
            return try {
                getString(R.string.default_gemini_key).trim()
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun requestAllPermissions() {
            runOnUiThread {
                requestRequiredPermissions()
            }
        }

        @JavascriptInterface
        fun hasContactsPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun getPhoneContactsJson(): String {
            val contacts = readDeviceContactsList()
            val arr = JSONArray()
            for ((name, phone) in contacts.take(250)) {
                val obj = JSONObject()
                obj.put("name", name)
                obj.put("phone", phone)
                arr.put(obj)
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun callContact(nameOrNumber: String): String {
            return handleSmartCall(nameOrNumber)
        }

        @JavascriptInterface
        fun openDefaultAssistantSettings() {
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val roleManager = getSystemService(RoleManager::class.java)
                        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                            val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                            startActivityForResult(roleIntent, 202)
                            return@runOnUiThread
                        }
                    }
                } catch (e: Exception) {}
                try {
                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    } catch (ignored: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun sendSms(phoneNumber: String, message: String) {
            val matched = lookupRealContact(phoneNumber)
            val targetNum = matched?.second ?: phoneNumber
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$targetNum")).apply {
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
            try {
                startActivity(intent)
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun openYouTube(query: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
            startActivity(intent)
        }

        @JavascriptInterface
        fun navigateMaps(destination: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")))
            }
        }

        @JavascriptInterface
        fun scrollScreen(direction: String): Boolean {
            return AuraAccessibilityService.instance?.scrollScreen(direction) ?: false
        }

        @JavascriptInterface
        fun clickScreenText(targetText: String): Boolean {
            return AuraAccessibilityService.instance?.clickNodeByText(targetText) ?: false
        }

        @JavascriptInterface
        fun openApp(appName: String) {
            val key = appName.trim().lowercase()
            val pkg = when {
                "whatsapp" in key || "vaatsap" in key || "వాట్సప్" in key || "వాట్సాప్" in key -> "com.whatsapp"
                "youtube" in key || "యుట్యూబ్" in key || "యూట్యూబ్" in key -> "com.google.android.youtube"
                "map" in key || "మ్యాప్" in key -> "com.google.android.apps.maps"
                "insta" in key -> "com.instagram.android"
                "spotify" in key -> "com.spotify.music"
                "chrome" in key || "browser" in key -> "com.android.chrome"
                "gmail" in key || "mail" in key -> "com.google.android.gm"
                else -> null
            }
            if (pkg != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    return
                }
            }

            // Dynamic lookup across all installed launchable apps on the user's phone
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolvedList = packageManager.queryIntentActivities(mainIntent, 0)
                for (info in resolvedList) {
                    val label = info.loadLabel(packageManager)?.toString()?.lowercase() ?: ""
                    val pkgName = info.activityInfo?.packageName?.lowercase() ?: ""
                    if (label == key || label.contains(key) || pkgName.contains(key)) {
                        val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
                        if (launch != null) {
                            startActivity(launch)
                            return
                        }
                    }
                }
            } catch (e: Exception) {}

            val fallbackUrl = when {
                "whatsapp" in key || "vaatsap" in key || "వాట్సప్" in key -> "https://wa.me/"
                "youtube" in key || "యూట్యూబ్" in key -> "https://m.youtube.com"
                "map" in key || "మ్యాప్" in key -> "https://maps.google.com"
                "insta" in key -> "https://www.instagram.com"
                else -> "https://www.google.com/search?q=" + Uri.encode(appName)
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } catch (e: Exception) {}
        }
    }
}
