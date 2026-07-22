package com.synthalorian.openshark.service

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import android.view.accessibility.AccessibilityNodeInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Android Bridge Service — HTTP server running inside the OpenShark Mobile app.
 *
 * Exposes Android APIs to the Termux-side OpenShark server:
 *   GET  /android/files?path=/sdcard/Download
 *   GET  /android/files/read?path=/sdcard/note.txt
 *   POST /android/files/write {path, content}
 *   GET  /android/sms?limit=20
 *   GET  /android/contacts?query=John
 *   GET  /android/calendar?days=7
 *   GET  /android/clipboard
 *   POST /android/clipboard {text}
 *   GET  /android/location
 *   GET  /android/battery
 *   GET  /android/wifi
 *   GET  /android/apps
 *   POST /android/apps/open {package}
 *   GET  /android/device
 *   GET  /android/camera/capture
 *   GET  /android/notifications
 *   GET  /android/health
 *
 * The Rust server in Termux calls these endpoints via localhost.
 */
class AndroidBridgeService : Service() {

    private var server: BridgeServer? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val TAG = "AndroidBridge"
        const val PORT = 9877  // One port above OpenShark server
        const val PREFS_NAME = "android_bridge"
        const val KEY_ENABLED = "bridge_enabled"

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AndroidBridgeService creating...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            try {
                server = BridgeServer()
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "Bridge server started on port $PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start bridge server", e)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        server?.stop()
        server = null
        Log.i(TAG, "Bridge server stopped")
    }

    /**
     * NanoHTTPD server handling all Android API endpoints.
     */
    inner class BridgeServer : NanoHTTPD(PORT) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d(TAG, "${method.name()} $uri")

            return try {
                when {
                    uri == "/android/health" -> healthCheck()
                    uri.startsWith("/android/files") -> handleFiles(session)
                    uri == "/android/sms" -> handleSms(session)
                    uri == "/android/contacts" -> handleContacts(session)
                    uri == "/android/calendar" -> handleCalendar(session)
                    uri == "/android/clipboard" -> handleClipboard(session)
                    uri == "/android/location" -> handleLocation()
                    uri == "/android/battery" -> handleBattery()
                    uri == "/android/wifi" -> handleWifi()
                    uri.startsWith("/android/apps") -> handleApps(session)
                    uri == "/android/device" -> handleDevice()
                    uri == "/android/notifications" -> handleNotifications()
                    uri == "/android/camera/capture" -> handleCameraCapture()
                    // UI Automation endpoints (delegate to AccessibilityService)
                    uri == "/ui/tree" -> handleUiTree(session)
                    uri == "/ui/tap" -> handleUiTap(session)
                    uri == "/ui/swipe" -> handleUiSwipe(session)
                    uri == "/ui/click" -> handleUiClick(session)
                    uri == "/ui/input" -> handleUiInput(session)
                    uri == "/ui/key" -> handleUiKeyEvent(session)
                    uri == "/ui/screenshot" -> handleUiScreenshot()
                    else -> jsonError("Not found: $uri", Response.Status.NOT_FOUND)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission denied for $uri", e)
                jsonError("Permission denied. Grant required permission in Android Settings.", Response.Status.FORBIDDEN)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $uri", e)
                jsonError(e.message ?: "Unknown error", Response.Status.INTERNAL_ERROR)
            }
        }

        // ── Health ────────────────────────────────────────────────────────

        private fun healthCheck(): Response {
            return jsonResponse(mapOf(
                "status" to "ok",
                "service" to "android-bridge",
                "version" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to Build.MODEL
            ))
        }

        // ── Files ─────────────────────────────────────────────────────────

        private fun handleFiles(session: IHTTPSession): Response {
            return when (session.method) {
                Method.GET -> {
                    val path = session.parameters["path"]?.firstOrNull() ?: "/sdcard"
                    val action = session.parameters["action"]?.firstOrNull() ?: "list"

                    when (action) {
                        "read" -> readFile(path)
                        "list" -> listFiles(path)
                        else -> listFiles(path)
                    }
                }
                Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val json = gson.fromJson(body["postData"] ?: "{}", JsonObject::class.java)
                    val path = json.get("path")?.asString ?: return jsonError("Missing path")
                    val content = json.get("content")?.asString

                    if (content != null) {
                        writeFile(path, content)
                    } else {
                        jsonError("Missing content")
                    }
                }
                else -> jsonError("Method not allowed", Response.Status.METHOD_NOT_ALLOWED)
            }
        }

        private fun listFiles(path: String): Response {
            val dir = File(path)
            if (!dir.exists()) return jsonError("Path not found: $path", Response.Status.NOT_FOUND)

            val files = dir.listFiles()?.map { file ->
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "isDirectory" to file.isDirectory,
                    "size" to file.length(),
                    "lastModified" to file.lastModified()
                )
            } ?: emptyList()

            return jsonResponse(mapOf(
                "path" to path,
                "files" to files,
                "count" to files.size
            ))
        }

        private fun readFile(path: String): Response {
            val file = File(path)
            if (!file.exists()) return jsonError("File not found: $path", Response.Status.NOT_FOUND)
            if (!file.canRead()) return jsonError("Cannot read file: $path", Response.Status.FORBIDDEN)

            val content = file.readText(Charsets.UTF_8)
            return jsonResponse(mapOf(
                "path" to path,
                "size" to file.length(),
                "content" to content
            ))
        }

        private fun writeFile(path: String, content: String): Response {
            return try {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                jsonResponse(mapOf("success" to true, "path" to path, "bytes" to content.length))
            } catch (e: Exception) {
                jsonError("Failed to write: ${e.message}")
            }
        }

        // ── SMS ───────────────────────────────────────────────────────────

        private fun handleSms(session: IHTTPSession): Response {
            val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 20

            return try {
                val cursor = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE
                    ),
                    null, null,
                    "${Telephony.Sms.DATE} DESC LIMIT $limit"
                )

                val messages = mutableListOf<Map<String, Any?>>()
                cursor?.use {
                    while (it.moveToNext()) {
                        messages.add(mapOf(
                            "id" to it.getString(0),
                            "address" to it.getString(1),
                            "body" to it.getString(2),
                            "date" to it.getLong(3),
                            "type" to it.getInt(4) // 1=inbox, 2=sent
                        ))
                    }
                }

                jsonResponse(mapOf("messages" to messages, "count" to messages.size))
            } catch (e: SecurityException) {
                jsonError("SMS permission required", Response.Status.FORBIDDEN)
            }
        }

        // ── Contacts ──────────────────────────────────────────────────────

        private fun handleContacts(session: IHTTPSession): Response {
            val query = session.parameters["query"]?.firstOrNull() ?: ""

            return try {
                val selection = if (query.isNotEmpty()) {
                    "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
                } else null
                val selectionArgs = if (query.isNotEmpty()) arrayOf("%$query%") else null

                val cursor = contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER
                    ),
                    selection, selectionArgs,
                    ContactsContract.Contacts.DISPLAY_NAME + " ASC"
                )

                val contacts = mutableListOf<Map<String, Any?>>()
                cursor?.use {
                    while (it.moveToNext()) {
                        val id = it.getString(0)
                        val name = it.getString(1)
                        val hasPhone = it.getInt(2) > 0

                        // Get phone numbers
                        val phones = mutableListOf<String>()
                        if (hasPhone) {
                            val phoneCursor = contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(id), null
                            )
                            phoneCursor?.use { pc ->
                                while (pc.moveToNext()) {
                                    phones.add(pc.getString(0))
                                }
                            }
                        }

                        contacts.add(mapOf(
                            "id" to id,
                            "name" to name,
                            "phones" to phones
                        ))
                    }
                }

                jsonResponse(mapOf("contacts" to contacts, "count" to contacts.size))
            } catch (e: SecurityException) {
                jsonError("Contacts permission required", Response.Status.FORBIDDEN)
            }
        }

        // ── Calendar ──────────────────────────────────────────────────────

        private fun handleCalendar(session: IHTTPSession): Response {
            val days = session.parameters["days"]?.firstOrNull()?.toIntOrNull() ?: 7
            val now = System.currentTimeMillis()
            val end = now + (days * 24 * 60 * 60 * 1000L)

            return try {
                val cursor = contentResolver.query(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    arrayOf(
                        android.provider.CalendarContract.Events._ID,
                        android.provider.CalendarContract.Events.TITLE,
                        android.provider.CalendarContract.Events.DTSTART,
                        android.provider.CalendarContract.Events.DTEND,
                        android.provider.CalendarContract.Events.EVENT_LOCATION
                    ),
                    "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
                    arrayOf(now.toString(), end.toString()),
                    android.provider.CalendarContract.Events.DTSTART + " ASC"
                )

                val events = mutableListOf<Map<String, Any?>>()
                cursor?.use {
                    while (it.moveToNext()) {
                        events.add(mapOf(
                            "id" to it.getString(0),
                            "title" to it.getString(1),
                            "start" to it.getLong(2),
                            "end" to it.getLong(3),
                            "location" to (it.getString(4) ?: "")
                        ))
                    }
                }

                jsonResponse(mapOf("events" to events, "count" to events.size))
            } catch (e: SecurityException) {
                jsonError("Calendar permission required", Response.Status.FORBIDDEN)
            }
        }

        // ── Clipboard ─────────────────────────────────────────────────────

        private fun handleClipboard(session: IHTTPSession): Response {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            return when (session.method) {
                Method.GET -> {
                    val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    jsonResponse(mapOf("text" to text))
                }
                Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val json = gson.fromJson(body["postData"] ?: "{}", JsonObject::class.java)
                    val text = json.get("text")?.asString ?: return jsonError("Missing text")

                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OpenShark", text))
                    jsonResponse(mapOf("success" to true))
                }
                else -> jsonError("Method not allowed", Response.Status.METHOD_NOT_ALLOWED)
            }
        }

        // ── Location ──────────────────────────────────────────────────────

        private fun handleLocation(): Response {
            // Return last known location or indicate GPS needs to be enabled
            return jsonResponse(mapOf(
                "status" to "location requires GPS. Use 'android location' tool with GPS enabled.",
                "hint" to "Enable GPS and grant location permission"
            ))
        }

        // ── Battery ───────────────────────────────────────────────────────

        private fun handleBattery(): Response {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = batteryManager.isCharging

            return jsonResponse(mapOf(
                "level" to batteryPct,
                "charging" to isCharging,
                "status" to if (isCharging) "charging" else "discharging"
            ))
        }

        // ── WiFi ──────────────────────────────────────────────────────────

        private fun handleWifi(): Response {
            return try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wifiManager.connectionInfo

                jsonResponse(mapOf(
                    "ssid" to (info.ssid ?: "unknown"),
                    "bssid" to (info.bssid ?: "unknown"),
                    "rssi" to info.rssi,
                    "linkSpeed" to info.linkSpeed,
                    "ip" to android.text.format.Formatter.formatIpAddress(info.ipAddress)
                ))
            } catch (e: SecurityException) {
                jsonError("Location permission required for WiFi info", Response.Status.FORBIDDEN)
            }
        }

        // ── Apps ──────────────────────────────────────────────────────────

        private fun handleApps(session: IHTTPSession): Response {
            val action = session.uri.removePrefix("/android/apps").trimStart('/')

            return when {
                action.isEmpty() || action == "list" -> listApps()
                action == "open" -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val json = gson.fromJson(body["postData"] ?: "{}", JsonObject::class.java)
                    val pkg = json.get("package")?.asString ?: return jsonError("Missing package")
                    openApp(pkg)
                }
                else -> jsonError("Unknown apps action: $action")
            }
        }

        private fun listApps(): Response {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0).map { app ->
                mapOf(
                    "package" to app.packageName,
                    "name" to pm.getApplicationLabel(app).toString(),
                    "system" to ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                )
            }.sortedBy { it["name"] as String }

            return jsonResponse(mapOf("apps" to apps, "count" to apps.size))
        }

        private fun openApp(pkg: String): Response {
            return try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    jsonResponse(mapOf("success" to true, "package" to pkg))
                } else {
                    jsonError("App not found: $pkg")
                }
            } catch (e: Exception) {
                jsonError("Failed to open app: ${e.message}")
            }
        }

        // ── Device ────────────────────────────────────────────────────────

        private fun handleDevice(): Response {
            val storage = File("/sdcard").let { sd ->
                mapOf(
                    "total" to sd.totalSpace,
                    "free" to sd.freeSpace,
                    "used" to (sd.totalSpace - sd.freeSpace)
                )
            }

            return jsonResponse(mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "device" to Build.DEVICE,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "abi" to Build.SUPPORTED_ABIS?.firstOrNull(),
                "storage" to storage,
                "time" to System.currentTimeMillis()
            ))
        }

        // ── Camera ────────────────────────────────────────────────────────

        private fun handleCameraCapture(): Response {
            return jsonResponse(mapOf(
                "status" to "Camera capture requires UI interaction. Use the Android Camera app directly.",
                "hint" to "Use 'android apps open com.android.camera' to open camera"
            ))
        }

        // ── Notifications ─────────────────────────────────────────────────

        private fun handleNotifications(): Response {
            // Requires NotificationListenerService — complex to implement fully
            return jsonResponse(mapOf(
                "status" to "Notification access requires NotificationListenerService permission",
                "hint" to "Grant notification access in Settings > Apps > Special access"
            ))
        }

        // ═══════════════════════════════════════════════════════════════════
        // UI Automation (delegates to OpenSharkAccessibilityService)
        // ═══════════════════════════════════════════════════════════════════

        private fun handleUiTree(session: IHTTPSession): Response {
            val maxNodes = session.parameters["max_nodes"]?.firstOrNull()?.toIntOrNull() ?: 400
            val a11y = OpenSharkAccessibilityService.instance
                ?: return jsonError("Accessibility service not running. Enable it in Settings > Accessibility > OpenShark.", Response.Status.SERVICE_UNAVAILABLE)
            
            val tree = a11y.captureUiTree(maxNodes)
            return jsonResponse(tree)
        }

        private fun handleUiTap(session: IHTTPSession): Response {
            val params = parseBody(session)
            val x = (params["x"] as? Number)?.toFloat() ?: return jsonError("Missing x")
            val y = (params["y"] as? Number)?.toFloat() ?: return jsonError("Missing y")
            val duration = (params["duration_ms"] as? Number)?.toLong() ?: 50
            
            val a11y = OpenSharkAccessibilityService.instance
                ?: return jsonError("Accessibility service not running", Response.Status.SERVICE_UNAVAILABLE)
            
            val success = a11y.tap(x, y, duration)
            return jsonResponse(mapOf("success" to success, "action" to "tap", "x" to x, "y" to y))
        }

        private fun handleUiSwipe(session: IHTTPSession): Response {
            val params = parseBody(session)
            val x1 = (params["x1"] as? Number)?.toFloat() ?: return jsonError("Missing x1")
            val y1 = (params["y1"] as? Number)?.toFloat() ?: return jsonError("Missing y1")
            val x2 = (params["x2"] as? Number)?.toFloat() ?: return jsonError("Missing x2")
            val y2 = (params["y2"] as? Number)?.toFloat() ?: return jsonError("Missing y2")
            val duration = (params["duration_ms"] as? Number)?.toLong() ?: 300
            
            val a11y = OpenSharkAccessibilityService.instance
                ?: return jsonError("Accessibility service not running", Response.Status.SERVICE_UNAVAILABLE)
            
            val success = a11y.swipe(x1, y1, x2, y2, duration)
            return jsonResponse(mapOf("success" to success, "action" to "swipe"))
        }

        private fun handleUiClick(session: IHTTPSession): Response {
            val params = parseBody(session)
            @Suppress("UNCHECKED_CAST")
            val target = params["target"] as? Map<String, String> ?: return jsonError("Missing target selector")
            
            val a11y = OpenSharkAccessibilityService.instance
                ?: return jsonError("Accessibility service not running", Response.Status.SERVICE_UNAVAILABLE)
            
            val success = a11y.clickBySelector(target)
            return jsonResponse(mapOf("success" to success, "action" to "click", "target" to target))
        }

        private fun handleUiInput(session: IHTTPSession): Response {
            val params = parseBody(session)
            val text = params["text"] as? String ?: return jsonError("Missing text")
            @Suppress("UNCHECKED_CAST")
            val target = params["target"] as? Map<String, String>
            val clear = params["clear"] as? Boolean ?: false
            
            val a11y = OpenSharkAccessibilityService.instance
                ?: return jsonError("Accessibility service not running", Response.Status.SERVICE_UNAVAILABLE)
            
            val success = if (target != null) {
                a11y.inputText(target, text, clear)
            } else {
                // Find focused input field
                val root = a11y.rootInActiveWindow
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val result = focused?.let {
                    val args = android.os.Bundle()
                    if (clear) it.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } ?: false
                focused?.recycle()
                root?.recycle()
                result
            }
            
            return jsonResponse(mapOf("success" to success, "action" to "input", "text" to text))
        }

        private fun handleUiKeyEvent(session: IHTTPSession): Response {
            val params = parseBody(session)
            val key = params["key"] as? String ?: return jsonError("Missing key")
            
            val action = when (key.uppercase()) {
                "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "POWER" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                else -> return jsonError("Unknown key: $key. Use: BACK, HOME, RECENTS, NOTIFICATIONS, POWER")
            }
            
            val success = OpenSharkAccessibilityService.performGlobalAction(action)
            return jsonResponse(mapOf("success" to success, "action" to key))
        }

        private fun handleUiScreenshot(): Response {
            val a11y = OpenSharkAccessibilityService.instance
                ?: return jsonError("Accessibility service not running", Response.Status.SERVICE_UNAVAILABLE)
            
            val screenshot = a11y.captureScreenshot()
            return if (screenshot != null) {
                jsonResponse(mapOf("screenshot" to screenshot))
            } else {
                jsonResponse(mapOf(
                    "error" to "Screenshot requires MediaProjection permission",
                    "hint" to "Grant screen capture permission in the app UI"
                ))
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────

        private fun parseBody(session: IHTTPSession): Map<String, Any> {
            return try {
                val body = HashMap<String, String>()
                session.parseBody(body)
                val jsonStr = body["postData"] ?: "{}"
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(jsonStr, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                @Suppress("UNCHECKED_CAST")
                session.parameters.mapValues { it.value.firstOrNull() ?: "" } as Map<String, Any>
            }
        }

        private fun jsonResponse(data: Any): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(data)
            )
        }

        private fun jsonError(message: String, status: Response.Status = Response.Status.BAD_REQUEST): Response {
            return newFixedLengthResponse(
                status,
                "application/json",
                gson.toJson(mapOf("error" to message))
            )
        }
    }
}
