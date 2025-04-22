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

    // Variables for drag functionality
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false // Flag to track drag state
    private var lastIconX: Int = 0 // Store last X position (default: 0)
    private var lastIconY: Int = 0 // Store last Y position (default: 0)

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
            // Removed FLAG_NOT_TOUCH_MODAL to receive touch events
            PixelFormat.TRANSLUCENT
        )
        // Initial gravity setting - IMPORTANT for coordinate calculation
        params.gravity = Gravity.TOP or Gravity.START
        resetFloatingIconPosition() // Set initial position
        params.x = lastIconX // Use stored position
        params.y = lastIconY // Use stored position

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = null
        }
        this.serviceInfo = info
        startForegroundService()
    }

    private fun resetFloatingIconPosition() {
        // Get screen dimensions
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val screenWidth = size.x
        val screenHeight = size.y

        // Calculate a position above the input, towards the right
        // Adjust these values as needed for your desired offset
        val offsetX = (screenWidth * 0.75).toInt() // 75% from the left
        val offsetY = (screenHeight * 0.08).toInt() // 8% from above

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

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val sourceNode = event.source
                if (sourceNode == null) {
                    Log.w("InputAssist", "Focus event with null source node.")
                    return
                }
                // Log.d("InputAssist", "Focus Event Details: Node Class='${sourceNode.className}', Text='${sourceNode.text}', Editable=${sourceNode.isEditable}, Focused=${sourceNode.isFocused}, ViewId=${sourceNode.viewIdResourceName}")
                val isEditableField = sourceNode.isEditable && sourceNode.className?.contains("EditText", ignoreCase = true) == true
                // Log.d("InputAssist", "Is considered editable input field? $isEditableField")
                handleFocusChange(sourceNode)
                // sourceNode.recycle() // Avoid recycling if stored in WeakRef
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Log.d("InputAssist", "Window/Text Change Event - Checking current focus")
                val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    // Log.d("InputAssist", "Found focused input node after change: Class='${focusedNode.className}', Editable=${focusedNode.isEditable}")
                    handleFocusChange(focusedNode)
                    // focusedNode.recycle() // Recycle if not stored
                } else {
                    // Log.d("InputAssist", "No input field focused after change, hiding icon.")
                    hideFloatingIcon()
                }
            }
        }
    }

    private fun handleFocusChange(nodeInfo: AccessibilityNodeInfo) {
        var isStillValid = true
        try {
            nodeInfo.refresh() // Refresh to get current state
        } catch (e: IllegalStateException) {
            Log.w("InputAssist", "Node likely stale on refresh: ${e.message}")
            isStillValid = false
        }

        if (isStillValid && nodeInfo.isEditable && nodeInfo.isFocused && nodeInfo.className?.contains("EditText", ignoreCase = true) == true) {
            Log.i("InputAssist", "Conditions MET. Calling showFloatingIcon() for node: ${nodeInfo.viewIdResourceName}")
            // Obtain a copy for the weak reference to potentially avoid issues with the original node becoming invalid
            currentFocusedNode = WeakReference(AccessibilityNodeInfo.obtain(nodeInfo))
            updateFloatingIconVisibility()
        } else {
            val trackedNode = currentFocusedNode?.get()
            if (trackedNode != null && (trackedNode.equals(nodeInfo) || !isNodeStillValidAndEditable(trackedNode))) {
                Log.d("InputAssist", "Hiding icon because tracked node lost focus or became invalid.")
                hideFloatingIcon()
                currentFocusedNode?.clear() // Clear the weak reference
                currentFocusedNode = null
            } else if (trackedNode == null && floatingIconView != null) {
                // If we lost track of the node somehow but the icon is still showing, hide it.
                Log.d("InputAssist", "Hiding icon because tracked node is null.")
                hideFloatingIcon()
            }
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
        } finally {
            // Don't recycle the node here if it's still potentially needed
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Suppress warning for setting OnTouchListener on non-standard view
    private fun updateFloatingIconVisibility() {
        val node = currentFocusedNode?.get()
        val text = node?.text?.toString() ?: ""
        val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

        if (wordCount > 2) {
            showFloatingIcon()
        } else {
            hideFloatingIcon()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Suppress warning for setting OnTouchListener on non-standard view
    private fun showFloatingIcon() {
        if (floatingIconView != null) {
            return
        }

        val inflater = LayoutInflater.from(this)
        floatingIconView = inflater.inflate(R.layout.floating_icon_layout, null)
        val iconImage = floatingIconView?.findViewById<ImageView>(R.id.floating_icon_image)

        // Use stored position when adding the view
        params.x = lastIconX
        params.y = lastIconY

        // --- Touch Listener for Dragging ---
        iconImage?.setOnTouchListener { view, event ->
            // Get screen dimensions for boundary checks
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getSize(size)
            val screenWidth = size.x
            val screenHeight = size.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Record initial position and touch point
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX // Use rawX/Y for screen coordinates
                    initialTouchY = event.rawY
                    isDragging = false // Reset dragging flag
                    // Log.d("InputAssist", "ACTION_DOWN: initialX=$initialX, initialY=$initialY, initialTouchX=$initialTouchX, initialTouchY=$initialTouchY")
                    return@setOnTouchListener true // Consume event: we are handling this touch sequence
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    // Check if movement exceeds touch slop threshold to consider it a drag
                    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
                    if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        // Calculate new position
                        var newX = initialX + deltaX.toInt()
                        var newY = initialY + deltaY.toInt()

                        // Clamp position within screen bounds
                        val iconWidth = view.width
                        val iconHeight = view.height
                        newX = maxOf(0, minOf(newX, screenWidth - iconWidth))
                        newY = maxOf(0, minOf(newY, screenHeight - iconHeight))

                        params.x = newX
                        params.y = newY

                        // Update layout in WindowManager
                        try {
                            windowManager.updateViewLayout(floatingIconView, params)
                            // Log.d("InputAssist", "ACTION_MOVE: Updating layout to x=${params.x}, y=${params.y}")
                        } catch (e: IllegalArgumentException) {
                            // Handle case where view might have been removed unexpectedly
                            Log.e("InputAssist", "Error updating layout during move: ${e.message}")
                            floatingIconView = null // Assume view is gone
                        }
                    }
                    return@setOnTouchListener true // Consume event: we are handling movement
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Drag finished, save the final position
                        lastIconX = params.x
                        lastIconY = params.y
                        Log.d("InputAssist", "ACTION_UP (Drag): Drag ended. Saved position x=$lastIconX, y=$lastIconY")
                        isDragging = false // Reset flag
                        // Consume the event, don't treat it as a click
                        return@setOnTouchListener true
                    } else {
                        // Not dragging, treat as a click
                        Log.d("InputAssist", "ACTION_UP (Click): Detected as click.")
                        // Perform the click action
                        handleIconClick()
                        // Return false to allow standard click handling (though we handle it manually here)
                        // Or return true if we fully handled the click here. Let's return true.
                        return@setOnTouchListener true
                    }
                }
            }
            // Return false if the event wasn't handled (shouldn't happen with the above logic)
            return@setOnTouchListener false
        }
        // --- End Touch Listener ---

        try {
            windowManager.addView(floatingIconView, params)
            Log.i("InputAssist", "Floating icon ADDED successfully at x=${params.x}, y=${params.y}")
        } catch (e: Exception) {
            Log.e("InputAssist", "Error ADDING floating icon view: ${e.message}", e)
            Toast.makeText(this, "ERROR: Cannot display overlay. Check 'Appear on top' permission. Error: ${e.message}", Toast.LENGTH_LONG).show()
            floatingIconView = null
        }
    }

    private fun hideFloatingIcon() {
        if (floatingIconView != null) {
            try {
                windowManager.removeView(floatingIconView)
                Log.d("InputAssist", "Floating icon removed")
            } catch (e: Exception) {
                Log.e("InputAssist", "Error removing floating icon view: ${e.message}")
            } finally {
                floatingIconView = null
                // Don't clear currentFocusedNode here, handleFocusChange manages it
            }
        }
    }

    private fun handleIconClick() {
        // This function is now called ONLY from the ACTION_UP part of the touch listener
        // when isDragging is false.
        Log.d("InputAssist", "Handling icon click action.")
        val nodeRef = currentFocusedNode
        val node = nodeRef?.get() // Get the strong reference from WeakReference

        // Re-check node validity right before using it
        if (node == null || !isNodeStillValidAndEditable(node)) {
            Log.w("InputAssist", "No valid focused node found on icon click.")
            Toast.makeText(this, R.string.no_focused_field, Toast.LENGTH_SHORT).show()
            hideFloatingIcon() // Hide icon if the node is gone or invalid
            currentFocusedNode?.clear()
            currentFocusedNode = null
            return
        }

        val apiKey = SecurePrefs.getApiKey(this) // Retrieve securely stored key
        if (apiKey == null) {
            Log.e("InputAssist", "Click failed: API Key not found in secure storage.")
            Toast.makeText(this, "API Key not set. Please set it in the app.", Toast.LENGTH_LONG).show()
            return // Stop if key is missing
        }
        val bearerApiKey = "Bearer $apiKey" // Add Bearer prefix for OpenAI API

        val currentText = node.text?.toString()?.let { "Rewrite in common language: $it" } ?: ""

        Log.d("InputAssist", "Sending text to API: $currentText")
        Toast.makeText(this, "Sending to AI...", Toast.LENGTH_SHORT).show() // User feedback

        serviceScope.launch(Dispatchers.IO) {
            try {
                val request = OpenAiRequest(messages = listOf(Message(content = currentText)))
                val response = ApiClient.instance.getCompletion(bearerApiKey, request)

                withContext(Dispatchers.Main) { // Switch back to Main thread for UI updates
                    // Re-get the node from the weak reference *again* in case it changed
                    // during the network call.
                    val currentNode = nodeRef?.get()
                    if (currentNode == null || !isNodeStillValidAndEditable(currentNode)) {
                        Log.w("InputAssist", "Node became invalid during API call.")
                        Toast.makeText(applicationContext, "Input field changed or disappeared.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    if (response.isSuccessful && response.body() != null) {
                        val apiResponse = response.body()!!
                        val generatedText = apiResponse.choices?.firstOrNull()?.message?.content?.trim()

                        if (!generatedText.isNullOrBlank()) {
                            Log.d("InputAssist", "API response received: $generatedText")
                            setTextInNode(currentNode, generatedText) // Use the re-validated node
                        } else if (apiResponse.error != null) {
                            Log.e("InputAssist", "API Error: ${apiResponse.error.message}")
                            Toast.makeText(applicationContext, "${getString(R.string.api_error)}: ${apiResponse.error.message}", Toast.LENGTH_LONG).show()
                        } else {
                            Log.w("InputAssist", "API returned empty or null response.")
                            Toast.makeText(applicationContext, R.string.api_error, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Log.e("InputAssist", "API request failed: ${response.code()} / $errorBody")
                        Toast.makeText(applicationContext, "${getString(R.string.api_error)} (${response.code()})", Toast.LENGTH_LONG).show()
                    }
                    // Recycle the node copy we potentially made after the operation is fully complete
                    // currentNode.recycle() // Careful if other operations might need it.
                }
            } catch (e: Exception) {
                Log.e("InputAssist", "Network or API call exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.network_error, Toast.LENGTH_LONG).show()
                }
            } finally {
                // Recycle the original node reference if it's still around and not the same as the one used
                // node?.recycle() // Very careful with recycling nodes obtained from events.
            }
        }
    }

    private fun setTextInNode(nodeInfo: AccessibilityNodeInfo, text: String) {
        // Check validity one last time before performing action
        if (!isNodeStillValidAndEditable(nodeInfo)) {
            Log.w("InputAssist", "Node became invalid just before setting text.")
            Toast.makeText(this, "Input field changed.", Toast.LENGTH_SHORT).show()
            return
        }

        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val success = nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (success) {
            Log.d("InputAssist", "Successfully set text in node.")
            // Move cursor to the end
            val setSelectionArgs = Bundle()
            setSelectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            setSelectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, setSelectionArgs)
        } else {
            Log.e("InputAssist", "Failed to set text in node.")
            Toast.makeText(this, "Failed to update text field", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.d("InputAssist", "Accessibility Service Interrupted")
        hideFloatingIcon()
        serviceJob.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("InputAssist", "Accessibility Service Destroyed")
        hideFloatingIcon()
        serviceJob.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("InputAssist", "Accessibility Service Unbound")
        hideFloatingIcon()
        serviceJob.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Consider saving lastIconX/Y to SharedPreferences here if persistence across service restarts is needed
        return super.onUnbind(intent)
    }
}