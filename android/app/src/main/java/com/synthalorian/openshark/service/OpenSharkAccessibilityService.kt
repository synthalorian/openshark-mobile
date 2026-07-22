package com.synthalorian.openshark.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Base64

/**
 * OpenShark Accessibility Service — provides full UI automation like KimiClaw.
 *
 * This service runs with BIND_ACCESSIBILITY_SERVICE permission, giving it:
 * - Full view hierarchy access (UI tree reading)
 * - Gesture injection (tap, swipe, long-press)
 * - Text input into any field
 * - Window content change notifications
 * - Screenshot capture (via MediaProjection)
 *
 * The service exposes an HTTP API on port 9878 that the Termux-side
 * OpenShark Rust server calls for UI automation tasks.
 */
class OpenSharkAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "OpenSharkA11y"
        const val PORT = 9878
        
        @Volatile
        var instance: OpenSharkAccessibilityService? = null
            private set
        
        fun isRunning(): Boolean = instance != null
        
        fun getRootNode(): AccessibilityNodeInfo? = instance?.rootInActiveWindow
        
        fun performGlobalAction(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var bridge: UiAutomationBridge? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Screenshot state
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 420

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        instance = this
        
        // Get screen dimensions
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        // Configure service info
        serviceInfo = serviceInfo.apply {
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
        }
        
        // Start the HTTP bridge
        bridge = UiAutomationBridge().apply {
            try {
                start()
                Log.i(TAG, "UI Automation bridge started on port $PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start bridge", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can monitor events here if needed for reactive automation
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        bridge?.stop()
        cleanupScreenshot()
        Log.i(TAG, "Accessibility service destroyed")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI Tree Capture
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Capture the full accessibility tree of the current window.
     */
    fun captureUiTree(maxNodes: Int = 500): JsonObject {
        val root = rootInActiveWindow ?: return JsonObject().apply {
            addProperty("error", "No active window. Is the service enabled?")
        }
        
        val result = JsonObject()
        val windows = JsonArray()
        
        try {
            val windowRoot = nodeToJson(root, maxNodes)
            val windowObj = JsonObject()
            windowObj.addProperty("package", root.packageName?.toString() ?: "unknown")
            windowObj.add("nodes", windowRoot)
            windows.add(windowObj)
            
            result.add("windows", windows)
            result.addProperty("node_count", countNodes(root))
            result.addProperty("screenshot_recommended", shouldRecommendScreenshot(root))
        } finally {
            root.recycle()
        }
        
        return result
    }
    
    private fun nodeToJson(node: AccessibilityNodeInfo, maxNodes: Int, current: Int = 0): JsonArray {
        val array = JsonArray()
        if (current >= maxNodes) return array
        
        val obj = JsonObject()
        
        // Identifiers
        node.viewIdResourceName?.let { obj.addProperty("resource_id", it) }
        node.text?.let { obj.addProperty("text", it.toString()) }
        node.contentDescription?.let { obj.addProperty("content_desc", it.toString()) }
        node.hintText?.let { obj.addProperty("hint", it.toString()) }
        
        // Class and package
        node.className?.let { obj.addProperty("class", it.toString()) }
        node.packageName?.let { obj.addProperty("package", it.toString()) }
        
        // State flags
        val flags = mutableListOf<String>()
        if (node.isClickable) flags.add("C")
        if (node.isEditable) flags.add("E")
        if (node.isFocusable) flags.add("F")
        if (node.isCheckable) flags.add("K")
        if (node.isChecked) flags.add("k")
        if (node.isScrollable) {
            when {
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } -> flags.add("S±")
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } -> flags.add("S+")
                else -> flags.add("S-")
            }
        }
        if (node.isEnabled) flags.add("e")
        if (node.isSelected) flags.add("s")
        if (node.isVisibleToUser) flags.add("V")
        if (flags.isNotEmpty()) obj.addProperty("flags", flags.joinToString(""))
        
        // Bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        obj.addProperty("bounds", "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
        
        array.add(obj)
        
        // Children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childArray = nodeToJson(child, maxNodes, current + array.size())
            childArray.forEach { array.add(it) }
            child.recycle()
        }
        
        return array
    }
    
    private fun countNodes(node: AccessibilityNodeInfo): Int {
        var count = 1
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                count += countNodes(it)
                it.recycle()
            }
        }
        return count
    }
    
    private fun shouldRecommendScreenshot(root: AccessibilityNodeInfo): Boolean {
        // Recommend screenshot for WebViews or when accessibility info is limited
        return root.className?.contains("WebView", ignoreCase = true) == true ||
               root.childCount == 0
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gestures (Tap, Swipe, Long-press)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Perform a tap at screen coordinates.
     */
    fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        val latch = CountDownLatch(1)
        var success = false
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        
        latch.await(2, TimeUnit.SECONDS)
        return success
    }
    
    /**
     * Perform a swipe gesture.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        val latch = CountDownLatch(1)
        var success = false
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        
        latch.await(2, TimeUnit.SECONDS)
        return success
    }
    
    /**
     * Click a node by selector (resource_id, text, content_desc).
     */
    fun clickBySelector(selector: Map<String, String>): Boolean {
        val node = findNode(selector) ?: return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return clicked
    }
    
    /**
     * Input text into an editable field.
     */
    fun inputText(selector: Map<String, String>, text: String, clearFirst: Boolean = false): Boolean {
        val node = findNode(selector) ?: return false
        
        if (clearFirst) {
            node.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
            node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }
        
        // Focus the field
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        
        // Set text using arguments
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        
        node.recycle()
        return success
    }
    
    /**
     * Find a node matching the selector criteria.
     */
    private fun findNode(selector: Map<String, String>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        
        try {
            return findNodeRecursive(root, selector)
        } finally {
            // Don't recycle root here - we're returning a child
        }
    }
    
    private fun findNodeRecursive(node: AccessibilityNodeInfo, selector: Map<String, String>): AccessibilityNodeInfo? {
        if (matchesSelector(node, selector)) {
            return node // Caller must recycle
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, selector)
            if (found != null) {
                child.recycle() // We found it deeper, recycle this reference
                return found
            }
            child.recycle()
        }
        
        return null
    }
    
    private fun matchesSelector(node: AccessibilityNodeInfo, selector: Map<String, String>): Boolean {
        selector["resource_id"]?.let {
            if (node.viewIdResourceName != it) return false
        }
        selector["text"]?.let {
            if (node.text?.toString() != it) return false
        }
        selector["text_contains"]?.let {
            if (node.text?.toString()?.contains(it, ignoreCase = true) != true) return false
        }
        selector["content_desc"]?.let {
            if (node.contentDescription?.toString() != it) return false
        }
        selector["content_desc_contains"]?.let {
            if (node.contentDescription?.toString()?.contains(it, ignoreCase = true) != true) return false
        }
        selector["any_text_contains"]?.let { query ->
            val inText = node.text?.toString()?.contains(query, ignoreCase = true) == true
            val inDesc = node.contentDescription?.toString()?.contains(query, ignoreCase = true) == true
            val inHint = node.hintText?.toString()?.contains(query, ignoreCase = true) == true
            if (!inText && !inDesc && !inHint) return false
        }
        selector["class_name"]?.let {
            if (node.className?.toString() != it) return false
        }
        selector["clickable"]?.let {
            if (node.isClickable != it.toBoolean()) return false
        }
        selector["package_name"]?.let {
            if (node.packageName?.toString() != it) return false
        }
        
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Screenshot Capture
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Capture a screenshot and return as base64.
     * Requires MediaProjection (screen capture permission).
     */
    fun captureScreenshot(): String? {
        // For now, return a message about needing MediaProjection
        // Full implementation requires starting an Activity for result
        return null
    }

    private fun cleanupScreenshot() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP Bridge
    // ═══════════════════════════════════════════════════════════════════════

    inner class UiAutomationBridge : NanoHTTPD(PORT) {
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            
            return try {
                when {
                    uri == "/ui/tree" -> handleTree(session)
                    uri == "/ui/tap" -> handleTap(session)
                    uri == "/ui/swipe" -> handleSwipe(session)
                    uri == "/ui/click" -> handleClick(session)
                    uri == "/ui/input" -> handleInput(session)
                    uri == "/ui/key" -> handleKeyEvent(session)
                    uri == "/ui/screenshot" -> handleScreenshot()
                    uri == "/ui/health" -> jsonResponse(mapOf("status" to "ok", "service" to "accessibility"))
                    else -> jsonError("Unknown endpoint: $uri", Response.Status.NOT_FOUND)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bridge error for $uri", e)
                jsonError(e.message ?: "Unknown error")
            }
        }
        
        private fun handleTree(session: IHTTPSession): Response {
            val maxNodes = session.parameters["max_nodes"]?.firstOrNull()?.toIntOrNull() ?: 400
            val tree = captureUiTree(maxNodes)
            return jsonResponse(tree)
        }
        
        private fun handleTap(session: IHTTPSession): Response {
            val params = parseBody(session)
            val x = params["x"]?.toFloatOrNull() ?: return jsonError("Missing x")
            val y = params["y"]?.toFloatOrNull() ?: return jsonError("Missing y")
            val duration = params["duration_ms"]?.toLongOrNull() ?: 50
            
            val success = tap(x, y, duration)
            return jsonResponse(mapOf("success" to success, "action" to "tap", "x" to x, "y" to y))
        }
        
        private fun handleSwipe(session: IHTTPSession): Response {
            val params = parseBody(session)
            val x1 = params["x1"]?.toFloatOrNull() ?: return jsonError("Missing x1")
            val y1 = params["y1"]?.toFloatOrNull() ?: return jsonError("Missing y1")
            val x2 = params["x2"]?.toFloatOrNull() ?: return jsonError("Missing x2")
            val y2 = params["y2"]?.toFloatOrNull() ?: return jsonError("Missing y2")
            val duration = params["duration_ms"]?.toLongOrNull() ?: 300
            
            val success = swipe(x1, y1, x2, y2, duration)
            return jsonResponse(mapOf("success" to success, "action" to "swipe"))
        }
        
        private fun handleClick(session: IHTTPSession): Response {
            val params = parseBody(session)
            val target = params["target"] as? Map<String, String> ?: return jsonError("Missing target selector")
            
            val success = clickBySelector(target)
            return jsonResponse(mapOf("success" to success, "action" to "click", "target" to target))
        }
        
        private fun handleInput(session: IHTTPSession): Response {
            val params = parseBody(session)
            val text = params["text"] as? String ?: return jsonError("Missing text")
            val target = params["target"] as? Map<String, String>
            val clear = params["clear"] as? Boolean ?: false
            
            val success = if (target != null) {
                inputText(target, text, clear)
            } else {
                // Find first editable field
                val root = rootInActiveWindow
                val editable = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val result = editable?.let {
                    val args = android.os.Bundle()
                    if (clear) {
                        it.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
                    }
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } ?: false
                editable?.recycle()
                root?.recycle()
                result
            }
            
            return jsonResponse(mapOf("success" to success, "action" to "input", "text" to text))
        }
        
        private fun handleKeyEvent(session: IHTTPSession): Response {
            val params = parseBody(session)
            val key = params["key"] as? String ?: return jsonError("Missing key")
            
            val keyCode = when (key.uppercase()) {
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "POWER" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                else -> false
            }
            
            return jsonResponse(mapOf("success" to keyCode, "action" to key))
        }
        
        private fun handleScreenshot(): Response {
            val screenshot = captureScreenshot()
            return if (screenshot != null) {
                jsonResponse(mapOf("screenshot" to screenshot, "width" to screenWidth, "height" to screenHeight))
            } else {
                jsonResponse(mapOf(
                    "error" to "Screenshot requires MediaProjection. Start screen capture from the app UI first.",
                    "hint" to "Use the Android system screenshot button or grant screen capture permission"
                ))
            }
        }
        
        private fun parseBody(session: IHTTPSession): Map<String, Any> {
            return try {
                val body = HashMap<String, String>()
                session.parseBody(body)
                val jsonStr = body["postData"] ?: "{}"
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(jsonStr, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                // Try query params as fallback
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
