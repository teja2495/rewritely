package com.tk.rewritely

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.math.abs

class RewritelyService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatingIconView: View? = null
    private var currentFocusedNode: WeakReference<AccessibilityNodeInfo>? = null
    private lateinit var params: WindowManager.LayoutParams // Make params a member variable

    // Variables for drag/long press functionality
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false // Flag to track drag state
    private var longPressDetected: Boolean = false // Flag for long press detection
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout().toLong() // System long press duration

    private var lastIconX: Int = 0 // Store last X position (default: 0)
    private var lastIconY: Int = 0 // Store last Y position (default: 0)

    // Set to store identifiers of input fields to ignore temporarily
    // Using Pair<String?, String?> to store (packageName, viewIdResourceName)
    private val ignoredFields = mutableSetOf<Pair<String?, String?>>()
    private var lastActivePackage: String? = null // Track the package of the focused app

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val NOTIFICATION_CHANNEL_ID = "InputAssistChannel"
    private val NOTIFICATION_ID = 1

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("InputAssist", "SERVICE CONNECTED and running!")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Initialize LayoutParams once
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Keep it non-focusable
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        resetFloatingIconPosition()
        params.x = lastIconX
        params.y = lastIconY

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED // Ensure we get window state changes
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS // Needed for package name
            notificationTimeout = 100
            packageNames = null // Monitor all apps
        }
        this.serviceInfo = info
        startForegroundService()
    }

    private fun resetFloatingIconPosition() {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val screenWidth = size.x
        val screenHeight = size.y
        val offsetX = (screenWidth * 0.75).toInt()
        val offsetY = (screenHeight * 0.08).toInt()
        lastIconX = offsetX
        lastIconY = offsetY
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Input Assistant Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Track the current active application package
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null && packageName != lastActivePackage) {
                Log.d("InputAssist", "App changed: $lastActivePackage -> $packageName. Clearing ignored fields for $packageName.")
                // Clear ignored fields specifically for the newly focused app
                // This implements the "unless I open that app again" logic
                ignoredFields.removeAll { it.first == packageName }
                lastActivePackage = packageName
            }
            // Also handle focus changes that might occur with window state changes
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            handleFocusChange(focusedNode) // Pass nullable node
        } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val sourceNode = event.source ?: return
            handleFocusChange(sourceNode)
            // sourceNode.recycle() - Handled by WeakReference or explicit recycle later
        } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // Refresh focus state on text change
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            handleFocusChange(focusedNode) // Pass nullable node
        }
    }

    // Accepts nullable nodeInfo
    private fun handleFocusChange(nodeInfo: AccessibilityNodeInfo?) {
        var currentNode: AccessibilityNodeInfo? = null
        var isStillValid = false

        if (nodeInfo != null) {
            try {
                // Obtain a fresh copy to avoid modifying the event source directly
                currentNode = AccessibilityNodeInfo.obtain(nodeInfo)
                currentNode?.refresh() // Refresh to get current state
                isStillValid = true
            } catch (e: Exception) { // Catch generic exception as various issues can occur
                Log.w("InputAssist", "Node likely stale or inaccessible on refresh/obtain: ${e.message}")
                isStillValid = false
                currentNode?.recycle() // Recycle if obtained but failed refresh
                currentNode = null
            }
        }

        val nodeIdentifier = getNodeIdentifier(currentNode) // Get package and view ID

        // Check if the field is currently ignored
        if (ignoredFields.contains(nodeIdentifier)) {
            Log.d("InputAssist", "Field $nodeIdentifier is currently ignored. Not showing icon.")
            hideFloatingIcon() // Ensure icon is hidden if field is ignored
            currentFocusedNode?.clear()
            currentFocusedNode = null
            currentNode?.recycle() // Recycle the obtained node
            return // Stop processing for this ignored field
        }

        // Check if the node is an editable input field and currently focused
        val isEditableInputField = isStillValid &&
                currentNode != null &&
                currentNode.isEditable &&
                currentNode.isFocused &&
                currentNode.className?.contains("EditText", ignoreCase = true) == true

        if (isEditableInputField) {
            Log.i("InputAssist", "Editable field focused: ${nodeIdentifier.second}. Storing reference.")
            currentFocusedNode = WeakReference(currentNode) // Store the obtained and refreshed node
            updateFloatingIconVisibility()
            // Don't recycle currentNode here, it's held by the WeakReference
        } else {
            // Handle cases where focus is lost or node becomes invalid
            val trackedNode = currentFocusedNode?.get()
            if (trackedNode != null && trackedNode.equals(currentNode)) {
                // If the currently tracked node is the one that just lost focus or became invalid
                Log.d("InputAssist", "Tracked node lost focus or became invalid. Hiding icon.")
                hideFloatingIcon()
                currentFocusedNode?.clear()
                currentFocusedNode = null
            } else if (trackedNode == null && floatingIconView != null) {
                // If we lost track of the node and the icon is showing, hide it.
                Log.d("InputAssist", "Hiding icon because tracked node is null or different.")
                hideFloatingIcon()
            }
            // Recycle the obtained node if it wasn't stored in WeakReference or isn't valid anymore
            currentNode?.recycle()
            // Recycle the tracked node if it's different from the current event node
            if (trackedNode != null && !trackedNode.equals(currentNode)) {
                // trackedNode.recycle() // Be careful recycling nodes held by WeakRef elsewhere
            }
        }
    }

    // Helper to get a stable identifier (package, viewId) for a node
    private fun getNodeIdentifier(node: AccessibilityNodeInfo?): Pair<String?, String?> {
        if (node == null) return Pair(null, null)
        try {
            val packageName = node.packageName?.toString()
            val viewId = node.viewIdResourceName
            return Pair(packageName, viewId)
        } catch (e: Exception) {
            Log.w("InputAssist", "Could not get node identifier: ${e.message}")
            return Pair(null, null) // Return nulls if properties aren't accessible
        }
    }


    private fun isNodeStillValidAndEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            node.refresh()
            return node.isEditable && node.isFocused // Check both editable and focused
        } catch (e: Exception) {
            Log.w("InputAssist", "Error checking node validity: ${e.message}")
            return false
        }
        // Don't recycle here, caller should manage lifecycle
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateFloatingIconVisibility() {
        val node = currentFocusedNode?.get()
        if (node == null || !isNodeStillValidAndEditable(node)) {
            hideFloatingIcon() // Hide if node is invalid
            return
        }

        // Check if the field is ignored *again* just before showing
        val nodeIdentifier = getNodeIdentifier(node)
        if (ignoredFields.contains(nodeIdentifier)) {
            Log.d("InputAssist", "Field $nodeIdentifier is ignored. Preventing icon show.")
            hideFloatingIcon()
            return
        }

        val text = node.text?.toString() ?: ""
        val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

        if (wordCount > 2) {
            showFloatingIcon()
        } else {
            hideFloatingIcon()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingIcon() {
        if (floatingIconView != null) {
            // If view exists, ensure its position is up-to-date (though unlikely to change unless dragged)
            // windowManager.updateViewLayout(floatingIconView, params)
            return // Already showing
        }

        val node = currentFocusedNode?.get()
        if (node == null) {
            Log.w("InputAssist", "Attempted to show icon but node is null.")
            return
        }
        // Final check for ignored status
        val nodeIdentifier = getNodeIdentifier(node)
        if (ignoredFields.contains(nodeIdentifier)) {
            Log.d("InputAssist", "Final check: Field $nodeIdentifier is ignored. Aborting showFloatingIcon.")
            return
        }


        val inflater = LayoutInflater.from(this)
        floatingIconView = inflater.inflate(R.layout.floating_icon_layout, null)
        val iconImage = floatingIconView?.findViewById<ImageView>(R.id.floating_icon_image)

        params.x = lastIconX
        params.y = lastIconY

        iconImage?.setOnTouchListener { view, event ->
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getSize(size)
            val screenWidth = size.x
            val screenHeight = size.y
            val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressDetected = false // Reset long press flag

                    // Start long press detection
                    longPressRunnable = Runnable {
                        if (!isDragging) { // Only trigger if not dragging
                            longPressDetected = true
                            Log.d("InputAssist", "Long press detected!")
                            // *** LONG PRESS ACTION ***
                            handleIconLongPress()
                            // Vibrate or give feedback? (Optional)
                            // val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            // if (vibrator.hasVibrator()) {
                            //     vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            // }
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT)

                    return@setOnTouchListener true // We are handling this touch stream
                }
                MotionEvent.ACTION_MOVE -> {
                    // Cancel long press detection if movement exceeds touch slop
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        if (longPressRunnable != null) {
                            longPressHandler.removeCallbacks(longPressRunnable!!)
                            longPressRunnable = null // Prevent it from running
                        }
                        if (!isDragging) {
                            isDragging = true // Start dragging only after exceeding slop
                            Log.d("InputAssist", "Dragging started.")
                        }
                    }


                    if (isDragging) {
                        // Calculate new position based on drag delta
                        var newX = initialX + (event.rawX - initialTouchX).toInt()
                        var newY = initialY + (event.rawY - initialTouchY).toInt()

                        // Clamp position within screen bounds
                        val iconWidth = view.width.takeIf { it > 0 } ?: floatingIconView?.width ?: 0 // Ensure width > 0
                        val iconHeight = view.height.takeIf { it > 0 } ?: floatingIconView?.height ?: 0 // Ensure height > 0
                        newX = maxOf(0, minOf(newX, screenWidth - iconWidth))
                        newY = maxOf(0, minOf(newY, screenHeight - iconHeight))

                        params.x = newX
                        params.y = newY

                        try {
                            windowManager.updateViewLayout(floatingIconView, params)
                        } catch (e: IllegalArgumentException) {
                            Log.e("InputAssist", "Error updating layout during move: ${e.message}")
                            floatingIconView = null // Assume view is gone
                        }
                    }
                    return@setOnTouchListener true // Consume move event
                }
                MotionEvent.ACTION_UP -> {
                    // Always remove the long press callback on ACTION_UP
                    if (longPressRunnable != null) {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        longPressRunnable = null
                    }

                    if (longPressDetected) {
                        // If long press was detected, the action was handled in the runnable.
                        // Reset flag and consume the event.
                        Log.d("InputAssist", "ACTION_UP after long press detected.")
                        longPressDetected = false
                        isDragging = false // Ensure drag flag is reset
                        // Don't treat as click or save position from long press
                        return@setOnTouchListener true
                    } else if (isDragging) {
                        // Drag finished, save the final position
                        lastIconX = params.x
                        lastIconY = params.y
                        Log.d("InputAssist", "ACTION_UP (Drag): Drag ended. Saved position x=$lastIconX, y=$lastIconY")
                        isDragging = false // Reset flag
                        return@setOnTouchListener true // Consume event, don't treat as click
                    } else {
                        // Not dragging, not a long press -> treat as a click
                        Log.d("InputAssist", "ACTION_UP (Click): Detected as click.")
                        handleIconClick()
                        return@setOnTouchListener true // Consume the click event
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Cancel long press detection if touch is cancelled
                    if (longPressRunnable != null) {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        longPressRunnable = null
                    }
                    Log.d("InputAssist", "ACTION_CANCEL received.")
                    isDragging = false
                    longPressDetected = false
                    return@setOnTouchListener true // Consume cancel event
                }
            }
            return@setOnTouchListener false // Default case (should not be reached)
        }

        try {
            windowManager.addView(floatingIconView, params)
            Log.i("InputAssist", "Floating icon ADDED successfully at x=${params.x}, y=${params.y}")
        } catch (e: Exception) {
            Log.e("InputAssist", "Error ADDING floating icon view: ${e.message}", e)
            Toast.makeText(this, "ERROR: Cannot display overlay. Check 'Appear on top' permission.", Toast.LENGTH_LONG).show()
            floatingIconView = null
        }
    }

    // Function called when long press is detected
    private fun handleIconLongPress() {
        Log.i("InputAssist", "Handling icon long press.")
        val node = currentFocusedNode?.get()
        val nodeIdentifier = getNodeIdentifier(node)

        if (nodeIdentifier.first != null || nodeIdentifier.second != null) { // Only ignore if we have some identifier
            Log.i("InputAssist", "Adding field $nodeIdentifier to ignored list.")
            ignoredFields.add(nodeIdentifier)
            Toast.makeText(this, "Icon hidden for this field.", Toast.LENGTH_SHORT).show() // User feedback
        } else {
            Log.w("InputAssist", "Could not get identifier for long-pressed field. Cannot ignore.")
            Toast.makeText(this, "Could not identify field to hide icon.", Toast.LENGTH_SHORT).show()
        }

        // Hide the icon immediately after long press action
        hideFloatingIcon()

        // Clear the current focus reference as we are now ignoring this field
        currentFocusedNode?.clear()
        currentFocusedNode = null
    }

    private fun hideFloatingIcon() {
        // Remove any pending long press callbacks if icon is hidden externally
        if (longPressRunnable != null) {
            longPressHandler.removeCallbacks(longPressRunnable!!)
            longPressRunnable = null
        }

        if (floatingIconView != null) {
            try {
                windowManager.removeView(floatingIconView)
                Log.d("InputAssist", "Floating icon removed")
            } catch (e: Exception) {
                Log.e("InputAssist", "Error removing floating icon view: ${e.message}")
            } finally {
                floatingIconView = null
            }
        }
    }

    private fun handleIconClick() {
        Log.d("InputAssist", "Handling icon click action.")
        val nodeRef = currentFocusedNode // Capture ref
        val node = nodeRef?.get()

        if (node == null || !isNodeStillValidAndEditable(node)) {
            Log.w("InputAssist", "No valid focused node found on icon click.")
            Toast.makeText(this, R.string.no_focused_field, Toast.LENGTH_SHORT).show()
            hideFloatingIcon()
            currentFocusedNode?.clear() // Ensure reference is cleared if node is invalid
            currentFocusedNode = null
            node?.recycle() // Recycle if we obtained it but it's invalid
            return
        }

        // Check if ignored (shouldn't happen if logic is correct, but safety check)
        val nodeIdentifier = getNodeIdentifier(node)
        if (ignoredFields.contains(nodeIdentifier)) {
            Log.w("InputAssist", "Click detected on an ignored field $nodeIdentifier. Hiding icon.")
            hideFloatingIcon()
            node.recycle() // Recycle the node we checked
            return
        }

        val apiKey = SecurePrefs.getApiKey(this)
        if (apiKey == null) {
            Log.e("InputAssist", "Click failed: API Key not found.")
            Toast.makeText(this, "API Key not set.", Toast.LENGTH_LONG).show()
            node.recycle() // Recycle node since we are aborting
            return
        }
        val bearerApiKey = "Bearer $apiKey"
        val currentText = node.text?.toString()?.let { "Rewrite in common language: $it" } ?: ""

        if (currentText.isBlank()) {
            Toast.makeText(this, "Nothing to rewrite.", Toast.LENGTH_SHORT).show()
            node.recycle() // Recycle node since we are aborting
            return
        }

        Log.d("InputAssist", "Sending text to API: $currentText")
        Toast.makeText(this, "Sending to AI...", Toast.LENGTH_SHORT).show()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val request = OpenAiRequest(messages = listOf(Message(content = currentText)))
                val response = ApiClient.instance.getCompletion(bearerApiKey, request)

                withContext(Dispatchers.Main) {
                    val currentNode = nodeRef?.get() // Re-get from WeakRef on Main thread
                    if (currentNode == null || !isNodeStillValidAndEditable(currentNode)) {
                        Log.w("InputAssist", "Node became invalid during API call.")
                        Toast.makeText(applicationContext, "Input field changed.", Toast.LENGTH_SHORT).show()
                        // Don't recycle nodeRef.get() here, it might be null or invalid
                        return@withContext
                    }
                    // Check ignored status again
                    val currentIdentifier = getNodeIdentifier(currentNode)
                    if (ignoredFields.contains(currentIdentifier)) {
                        Log.w("InputAssist", "Field became ignored during API call.")
                        currentNode.recycle() // Recycle the re-validated node
                        return@withContext
                    }


                    if (response.isSuccessful && response.body() != null) {
                        val apiResponse = response.body()!!
                        val generatedText = apiResponse.choices?.firstOrNull()?.message?.content?.trim()

                        if (!generatedText.isNullOrBlank()) {
                            Log.d("InputAssist", "API response received: $generatedText")
                            setTextInNode(currentNode, generatedText) // Use re-validated node
                        } else {
                            val errorMsg = apiResponse.error?.message ?: "Empty response"
                            Log.e("InputAssist", "API Error or empty response: $errorMsg")
                            Toast.makeText(applicationContext, "API Error: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Log.e("InputAssist", "API request failed: ${response.code()} / $errorBody")
                        Toast.makeText(applicationContext, "${getString(R.string.api_error)} (${response.code()})", Toast.LENGTH_LONG).show()
                    }
                    currentNode.recycle() // Recycle the node after use in Main thread
                }
            } catch (e: Exception) {
                Log.e("InputAssist", "Network or API call exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.network_error, Toast.LENGTH_LONG).show()
                    // Try recycling node here in case of exception
                    nodeRef?.get()?.recycle()
                }
            }
        }
        // Don't recycle the original 'node' here, it's passed to the coroutine indirectly via nodeRef
    }

    private fun setTextInNode(nodeInfo: AccessibilityNodeInfo, text: String) {
        // No need to check isNodeStillValidAndEditable again, done by caller (handleIconClick)
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            Log.e("InputAssist", "Failed to set text in node.")
            Toast.makeText(this, "Failed to update text field", Toast.LENGTH_SHORT).show()
        } else {
            Log.d("InputAssist", "Successfully set text in node.")
            // Move cursor to the end (optional, but good UX)
            val setSelectionArgs = Bundle()
            setSelectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            setSelectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, setSelectionArgs) // Best effort
        }
        // Node recycled by the caller (handleIconClick) after this function returns
    }

    override fun onInterrupt() {
        Log.d("InputAssist", "Accessibility Service Interrupted")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("InputAssist", "Accessibility Service Destroyed")
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE) // Use correct flag for API 31+
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("InputAssist", "Accessibility Service Unbound")
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE) // Use correct flag for API 31+
        // Consider saving lastIconX/Y and ignoredFields to SharedPreferences here for persistence
        return super.onUnbind(intent)
    }

    private fun cleanup() {
        hideFloatingIcon() // Ensures icon is removed and long press handler cleared
        serviceJob.cancel() // Cancel coroutines
        currentFocusedNode?.clear() // Clear weak reference
        ignoredFields.clear() // Clear ignored fields on service stop/unbind
        Log.d("InputAssist", "Service cleanup performed.")
    }
}