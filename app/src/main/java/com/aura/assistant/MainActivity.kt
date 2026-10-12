package com.aura.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
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
    private var lastExternalTaskDescription: String? = null

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
                        lastExternalTaskDescription = url
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

    override fun onResume() {
        super.onResume()
        val prevTask = lastExternalTaskDescription
        if (!prevTask.isNullOrBlank() && ::webView.isInitialized) {
            lastExternalTaskDescription = null
            val safeTask = prevTask.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
            webView.postDelayed({
                webView.evaluateJavascript(
                    "if (window.onReturnedFromExternalApp) window.onReturnedFromExternalApp('$safeTask');",
                    null
                )
            }, 400)
        }
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
                n.length >= 2 && (n.startsWith(alias) || n.split(" ").any { part -> part == alias })
            }?.let { return it }
        }
        // 3. Substring match
        for (alias in aliases) {
            if (alias.length >= 2) {
                contacts.firstOrNull { it.first.length >= 2 && it.first.lowercase().contains(alias) }?.let { return it }
            }
        }
        // 4. Multi-word token match (e.g., "open and hii babbi" -> matches real contact "Babbi")
        val stopWords = setOf(
            "open", "and", "send", "message", "msg", "whatsapp", "vaatsap", "to", "in", "on",
            "hi", "hii", "hello", "hey", "aura", "please", "call", "dial", "phone", "ki", "ku", "ko", "lo"
        )
        val tokens = qLower.split(Regex("[^a-z0-9\\u0C00-\\u0C7F\\u0900-\\u097F]+"))
            .filter { it.length >= 2 && it !in stopWords }
        for (tok in tokens.reversed()) {
            contacts.firstOrNull { it.first.lowercase() == tok }?.let { return it }
        }
        for (tok in tokens.reversed()) {
            contacts.firstOrNull {
                val n = it.first.lowercase()
                n.length >= 2 && (n.startsWith(tok) || n.split(" ").any { part -> part == tok })
            }?.let { return it }
        }
        return null
    }

    fun placeDirectCall(phoneNumber: String) {
        val clean = phoneNumber.trim()
        if (clean.isEmpty()) return
        lastExternalTaskDescription = "Phone Call ($clean)"
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
            lastExternalTaskDescription = "Phone Call to ${matched.first}"
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
            lastExternalTaskDescription = "Phone Dialer"
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
        fun readScreenSummary(): String {
            val bat = getRealBatteryStatus()
            val batText = if (bat.contains("|")) {
                val parts = bat.split("|")
                "Battery ${parts[0]}%" + if (parts.getOrNull(1) == "true") " (Charging)" else ""
            } else ""
            val prev = lastExternalTaskDescription ?: "Aura Assistant Screen"
            return listOf(prev, batText).filter { it.isNotBlank() }.joinToString(" • ")
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
        fun sendWhatsApp(nameOrNumber: String, message: String): String {
            val trimmed = nameOrNumber.trim()
            lastExternalTaskDescription = "WhatsApp Message"
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestRequiredPermissions()
            }

            val matched = if (trimmed.isNotEmpty()) lookupRealContact(trimmed) else null
            if (matched != null) {
                lastExternalTaskDescription = "WhatsApp to ${matched.first}"
                val digits = matched.second.replace(Regex("[^0-9]"), "")
                val waPhone = if (digits.length == 10) "91$digits" else digits
                val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$waPhone&text=${Uri.encode(message)}")
                try {
                    val intent = Intent(Intent.ACTION_VIEW, waUri).apply {
                        setPackage("com.whatsapp")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$waPhone&text=${Uri.encode(message)}")))
                    } catch (e2: Exception) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, waUri))
                        } catch (ignored: Exception) {}
                    }
                }
                return "FOUND:${matched.first}|${matched.second}"
            }

            val isSampleNumber = sampleDemoNumbers.containsKey(trimmed)
            val rawDigits = trimmed.replace(Regex("[^0-9]"), "")
            if (!isSampleNumber && rawDigits.length >= 10 && trimmed.all { it.isDigit() || it in "+ -()" }) {
                val waPhone = if (rawDigits.length == 10) "91$rawDigits" else rawDigits
                val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$waPhone&text=${Uri.encode(message)}")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, waUri).apply { setPackage("com.whatsapp") })
                } catch (e: Exception) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, waUri)) } catch (ignored: Exception) {}
                }
                return "FOUND:$rawDigits|$rawDigits"
            }

            // Fallback: Open WhatsApp's native contact picker with the message pre-filled
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage("com.whatsapp")
                }
                startActivity(shareIntent)
                return "PICKER:$trimmed"
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")))
                } catch (ignored: Exception) {}
                return "NOT_FOUND:$trimmed"
            }
        }

        @JavascriptInterface
        fun sendSms(phoneNumber: String, message: String) {
            val matched = lookupRealContact(phoneNumber)
            val targetNum = matched?.second ?: phoneNumber
            lastExternalTaskDescription = "SMS to ${matched?.first ?: phoneNumber}"
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
        fun setTimer(seconds: Int, label: String) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun getRealBatteryStatus(): String {
            return try {
                val bm = getSystemService(BatteryManager::class.java)
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    bm?.isCharging ?: false
                } else {
                    false
                }
                if (level in 0..100) "$level|$charging" else ""
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun adjustVolume(action: String): String {
            return try {
                val am = getSystemService(AudioManager::class.java) ?: return "ERR"
                val act = action.trim().lowercase()
                when {
                    "up" in act || "raise" in act || "louder" in act || "increase" in act -> {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    }
                    "down" in act || "lower" in act || "quieter" in act || "decrease" in act -> {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    }
                    "mute" in act || "silent" in act -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        }
                    }
                    "unmute" in act -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                        }
                    }
                }
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val pct = (cur * 100) / max
                "$pct%"
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun openSystemSettings(section: String) {
            val s = section.trim().lowercase()
            lastExternalTaskDescription = "System Settings ($section)"
            val actionStr = when {
                "wifi" in s || "wi-fi" in s || "internet" in s -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" in s -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" in s || "brightness" in s -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" in s || "volume" in s || "audio" in s -> Settings.ACTION_SOUND_SETTINGS
                "location" in s || "gps" in s -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "battery" in s -> Intent.ACTION_POWER_USAGE_SUMMARY
                else -> Settings.ACTION_SETTINGS
            }
            try {
                startActivity(Intent(actionStr))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (ex: Exception) {}
            }
        }

        @JavascriptInterface
        fun openYouTube(query: String) {
            val cleanQ = query.trim()
            if (cleanQ.isEmpty()) {
                openApp("youtube")
                return
            }
            lastExternalTaskDescription = "YouTube ($cleanQ)"
            try {
                val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", cleanQ)
                }
                startActivity(ytIntent)
                return
            } catch (e: Exception) {}
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQ)}"))
            startActivity(intent)
        }

        @JavascriptInterface
        fun navigateMaps(destination: String) {
            lastExternalTaskDescription = "Google Maps ($destination)"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")))
            }
        }

        @JavascriptInterface
        fun scrollScreen(direction: String): Boolean {
            runOnUiThread {
                if (::webView.isInitialized) {
                    val dy = if (direction.lowercase() == "up") -450 else 450
                    webView.scrollBy(0, dy)
                    webView.evaluateJavascript("if (window.performSmartScroll) window.performSmartScroll('$direction');", null)
                }
            }
            return true
        }

        @JavascriptInterface
        fun clickScreenText(targetText: String): Boolean {
            val safeText = targetText.replace("'", "\\'")
            runOnUiThread {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("if (window.performSmartSelect) window.performSmartSelect('$safeText');", null)
                }
            }
            return true
        }

        @JavascriptInterface
        fun openExternalReels() {
            lastExternalTaskDescription = "Instagram Reels"
            try {
                val reelsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://reels_home")).apply {
                    setPackage("com.instagram.android")
                }
                startActivity(reelsIntent)
                return
            } catch (e: Exception) {}
            try {
                val webReels = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reels/")).apply {
                    setPackage("com.instagram.android")
                }
                startActivity(webReels)
                return
            } catch (e: Exception) {}
            try {
                val launchInsta = packageManager.getLaunchIntentForPackage("com.instagram.android")
                if (launchInsta != null) {
                    startActivity(launchInsta)
                    return
                }
            } catch (e: Exception) {}
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reels/")))
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun openAppWithQuery(appName: String, query: String) {
            val key = appName.trim().lowercase()
            val q = query.trim()
            if (q.isEmpty()) {
                openApp(appName)
                return
            }
            lastExternalTaskDescription = "$appName ($q)"
            when {
                "youtube" in key || "yt" in key || "యూట్యూబ్" in key || "యుట్యూబ్" in key -> {
                    openYouTube(q)
                    return
                }
                "map" in key || "మ్యాప్" in key -> {
                    navigateMaps(q)
                    return
                }
                "insta" in key || "ఇన్‌స్టా" in key || "ఇన్స్టా" in key -> {
                    if ("reel" in q.lowercase() || "రీల్" in q) {
                        openExternalReels()
                        return
                    }
                    val cleanHandle = q.replace("@", "").trim()
                    if (!cleanHandle.contains(" ")) {
                        try {
                            val userIntent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=${Uri.encode(cleanHandle)}")).apply {
                                setPackage("com.instagram.android")
                            }
                            startActivity(userIntent)
                            return
                        } catch (e: Exception) {}
                    }
                    try {
                        val exploreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/explore/search/keyword/?q=${Uri.encode(q)}")).apply {
                            setPackage("com.instagram.android")
                        }
                        startActivity(exploreIntent)
                        return
                    } catch (e: Exception) {}
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/explore/search/keyword/?q=${Uri.encode(q)}")))
                    return
                }
                "spotify" in key -> {
                    try {
                        val spIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(q)}")).apply {
                            setPackage("com.spotify.music")
                        }
                        startActivity(spIntent)
                        return
                    } catch (e: Exception) {}
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${Uri.encode(q)}")))
                    return
                }
                "amazon" in key || "అమెజాన్" in key -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.amazon.in/s?k=${Uri.encode(q)}")))
                    return
                }
                "flipkart" in key || "ఫ్లిప్‌కార్ట్" in key -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flipkart.com/search?q=${Uri.encode(q)}")))
                    return
                }
                "swiggy" in key || "స్విగ్గీ" in key -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.swiggy.com/search?query=${Uri.encode(q)}")))
                    return
                }
                "zomato" in key || "జొమాటో" in key -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zomato.com/search?q=${Uri.encode(q)}")))
                    return
                }
                "chrome" in key || "google" in key || "browser" in key -> {
                    val searchUrl = if (q.startsWith("http://") || q.startsWith("https://")) q else "https://www.google.com/search?q=${Uri.encode(q)}"
                    try {
                        val chIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                            setPackage("com.android.chrome")
                        }
                        startActivity(chIntent)
                        return
                    } catch (e: Exception) {}
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)))
                    return
                }
                "play store" in key || "playstore" in key -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(q)}")))
                    return
                }
                else -> {
                    openApp(appName)
                }
            }
        }

        @JavascriptInterface
        fun openApp(appName: String) {
            val key = appName.trim().lowercase()
            lastExternalTaskDescription = appName.trim()

            // Always open REAL Instagram Reels or YouTube Shorts (never a mock modal!)
            if ("reel" in key || "రీల్" in key || "रील" in key) {
                openExternalReels()
                return
            }
            if ("short" in key || "షార్ట్" in key) {
                try {
                    val shortsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts")).apply {
                        setPackage("com.google.android.youtube")
                    }
                    startActivity(shortsIntent)
                    return
                } catch (e: Exception) {}
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts")))
                return
            }

            if (key == "settings" || key == "setting" || "సెట్టింగ్" in key || "सेटिंग" in key || "wifi" in key || "bluetooth" in key) {
                openSystemSettings(key)
                return
            }

            if (key == "clock" || key == "alarm" || key == "timer" || "అలారం" in key || "క్లాక్" in key) {
                try {
                    startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                    return
                } catch (e: Exception) {}
            }

            val pkgCandidates = when {
                "whatsapp" in key || "vaatsap" in key || "వాట్సప్" in key || "వాట్సాప్" in key || "व्हाट्सएप" in key -> listOf("com.whatsapp", "com.whatsapp.w4b")
                "insta" in key || "ఇన్‌స్టా" in key || "ఇన్స్టా" in key || "इंस्टा" in key -> listOf("com.instagram.android", "com.instagram.lite")
                "youtube music" in key || "yt music" in key -> listOf("com.google.android.apps.youtube.music")
                "youtube" in key || "యుట్యూబ్" in key || "యూట్యూబ్" in key || "यूट्यूब" in key -> listOf("com.google.android.youtube")
                "map" in key || "మ్యాప్" in key || "मैप" in key -> listOf("com.google.android.apps.maps")
                "spotify" in key || "స్పాటిఫై" in key -> listOf("com.spotify.music")
                "jiosaavn" in key || "saavn" in key -> listOf("com.jio.media.jiobeats")
                "gaana" in key -> listOf("com.gaana")
                "wynk" in key -> listOf("com.bsbportal.music")
                "snapchat" in key || "స్నాప్‌చాట్" in key -> listOf("com.snapchat.android")
                "telegram" in key || "టెలిగ్రామ్" in key -> listOf("org.telegram.messenger", "org.thunderdog.challegram")
                "facebook" in key || "fb" == key || "ఫేస్‌బుక్" in key -> listOf("com.facebook.katana", "com.facebook.lite")
                "twitter" in key || "x app" in key -> listOf("com.twitter.android")
                "linkedin" in key -> listOf("com.linkedin.android")
                "amazon" in key || "అమెజాన్" in key -> listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping")
                "flipkart" in key || "ఫ్లిప్‌కార్ట్" in key -> listOf("com.flipkart.android")
                "meesho" in key || "మీషో" in key -> listOf("com.meesho.supply")
                "myntra" in key -> listOf("com.myntra.android")
                "swiggy" in key || "స్విగ్గీ" in key -> listOf("in.swiggy.android")
                "zomato" in key || "జొమాటో" in key -> listOf("com.application.zomato")
                "blinkit" in key -> listOf("com.grofers.customerapp")
                "zepto" in key -> listOf("com.zeptoconsumerapp")
                "uber" in key -> listOf("com.ubercab")
                "ola" in key -> listOf("com.olacabs.customer")
                "rapido" in key -> listOf("com.rapido.passenger")
                "netflix" in key -> listOf("com.netflix.mediaclient")
                "hotstar" in key || "jiohotstar" in key -> listOf("in.startv.hotstar")
                "prime video" in key -> listOf("com.amazon.avod.thirdpartyclient")
                "chrome" in key || "browser" in key || "క్రోమ్" in key -> listOf("com.android.chrome")
                "gmail" in key || "mail" in key || "మెయిల్" in key -> listOf("com.google.android.gm")
                "calculator" in key || "calc" in key || "కాలిక్యులేటర్" in key -> listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator", "com.coloros.calculator", "com.android.calculator2")
                "calendar" in key || "క్యాలెండర్" in key -> listOf("com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar")
                "message" in key || "sms" in key -> listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms")
                "contact" in key || "phonebook" in key -> listOf("com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts")
                "play store" in key || "playstore" in key -> listOf("com.android.vending")
                else -> emptyList()
            }

            for (pkg in pkgCandidates) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        return
                    }
                } catch (e: Exception) {}
            }

            // Dynamic lookup across all installed launchable apps on the user's phone
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolvedList = packageManager.queryIntentActivities(mainIntent, 0)
                for (info in resolvedList) {
                    val label = info.loadLabel(packageManager).toString().lowercase()
                    val pkgName = info.activityInfo?.packageName?.lowercase() ?: ""
                    if (label == key || label.contains(key) || key.contains(label) && label.length >= 3 || pkgName.contains(key)) {
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
                "insta" in key || "ఇన్‌స్టా" in key || "ఇన్స్టా" in key -> "https://www.instagram.com/"
                "youtube" in key || "యూట్యూబ్" in key -> "https://m.youtube.com"
                "map" in key || "మ్యాప్" in key -> "https://maps.google.com"
                "spotify" in key -> "https://open.spotify.com"
                "amazon" in key -> "https://www.amazon.in"
                "flipkart" in key -> "https://www.flipkart.com"
                "swiggy" in key -> "https://www.swiggy.com"
                "zomato" in key -> "https://www.zomato.com"
                "snapchat" in key -> "https://www.snapchat.com"
                "telegram" in key -> "https://web.telegram.org"
                "facebook" in key -> "https://m.facebook.com"
                else -> "https://www.google.com/search?q=" + Uri.encode(appName)
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } catch (e: Exception) {}
        }
    }
}
