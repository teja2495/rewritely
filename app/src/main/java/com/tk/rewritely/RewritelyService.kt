package com.tk.rewritely

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RewritelyService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var floatingIcon: View? = null

    private var currentNode: WeakReference<AccessibilityNodeInfo>? = null
    private val ignoredFields = mutableSetOf<Pair<String?, String?>>()

    // Icon position
    private var lastX = 0
    private var lastY = 0

    // Drag / long-press
    private var initialX = 0
    private var initialY = 0
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var longPressJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Foreground
    private val channelId = "InputAssistChannel"
    private val notificationId = 1

    /* ---------- Service ---------- */

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = createLayoutParams()
        resetIconPosition()
        serviceInfo = createServiceInfo()
        startForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                clearIgnoredIfAppChanged(event.packageName?.toString())
                handleFocusChange(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED       -> handleFocusChange(event.source)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED  -> handleFocusChange(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        }
    }

    override fun onInterrupt() = cleanup()
    override fun onDestroy()   { cleanup(); stopForeground(STOP_FOREGROUND_REMOVE) }
    override fun onUnbind(i: Intent?) = cleanup().let { super.onUnbind(i) }

    /* ---------- Focus ---------- */

    private fun handleFocusChange(node: AccessibilityNodeInfo?) {
        val fresh = node?.let { tryObtain(it) }
        val id = fresh?.id()

        if (id in ignoredFields) return hideIcon()

        if (fresh?.isEditable == true && fresh.isFocused) {
            currentNode = WeakReference(fresh)
            updateIcon()
        } else {
            currentNode?.clear()
            currentNode = null
            hideIcon()
            fresh?.recycle()
        }
    }

    /* ---------- Icon ---------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun updateIcon() {
        val node = currentNode?.get() ?: return hideIcon()
        if (!node.stillValid()) return hideIcon()

        val words = node.text?.split("\\s+".toRegex())?.count { it.isNotBlank() } ?: 0
        if (words <= 2) return hideIcon()

        if (floatingIcon == null) {
            floatingIcon = LayoutInflater.from(this)
                .inflate(R.layout.floating_icon_layout, null)
                .apply {
                    findViewById<ImageView>(R.id.floating_icon_image).setOnTouchListener(iconTouchListener())
                    windowManager.addView(this, params.apply { x = lastX; y = lastY })
                }
        }
    }

    private fun hideIcon() {
        longPressJob?.cancel(); longPressJob = null
        floatingIcon?.let { runCatching { windowManager.removeView(it) } }
        floatingIcon = null
    }

    /* ---------- Icon actions ---------- */

    private fun iconTouchListener() = View.OnTouchListener { v, e ->
        val size = Point().also { windowManager.defaultDisplay.getSize(it) }
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x; initialY = params.y
                downX = e.rawX; downY = e.rawY
                isDragging = false
                longPressJob = scope.launch {
                    delay(ViewConfiguration.getLongPressTimeout().toLong())
                    if (!isDragging) onIconLongPress()
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                    isDragging = true; longPressJob?.cancel()
                }
                if (isDragging) {
                    params.x = clamp(initialX + (e.rawX - downX).toInt(), 0, size.x - v.width)
                    params.y = clamp(initialY + (e.rawY - downY).toInt(), 0, size.y - v.height)
                    windowManager.updateViewLayout(floatingIcon, params)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel(); longPressJob = null
                if (isDragging) { lastX = params.x; lastY = params.y }
                else if (e.action == MotionEvent.ACTION_UP) onIconClick()
                isDragging = false; true
            }
            else -> false
        }
    }

    private fun onIconLongPress() {
        currentNode?.get()?.id()?.let { ignoredFields += it }
        Toast.makeText(this, "Icon hidden for this field.", Toast.LENGTH_SHORT).show()
        hideIcon()
    }

    private fun onIconClick() {
        val node = currentNode?.get()?.takeIf { it.stillValid() } ?: return
        if (node.id() in ignoredFields) return hideIcon()

        val apiKey = SecurePrefs.getApiKey(this)
            ?: return Toast.makeText(this, "API Key not set.", Toast.LENGTH_LONG).show()

        val original = node.text?.toString().orEmpty()
        if (original.isBlank()) return Toast.makeText(this, "Nothing to rewrite.", Toast.LENGTH_SHORT).show()

        Toast.makeText(this, "Sending to AI...", Toast.LENGTH_SHORT).show()
        val prompt = "Rewrite in common language: $original"

        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = OpenAiRequest(messages = listOf(Message(content = prompt)))
                ApiClient.instance.getCompletion("Bearer $apiKey", request)
            }.onSuccess { res ->
                withContext(Dispatchers.Main) {
                    val reply = res.body()?.choices?.firstOrNull()?.message?.content?.trim()
                    if (res.isSuccessful && !reply.isNullOrBlank()) setText(node, reply)
                    else Toast.makeText(applicationContext, "API Error", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.network_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /* ---------- Helpers ---------- */

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, this)
        }
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,   text.length)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, this)
        }
    }

    private fun AccessibilityNodeInfo.id() = Pair(packageName?.toString(), viewIdResourceName)
    private fun AccessibilityNodeInfo.stillValid() = runCatching { refresh(); isEditable && isFocused }.getOrDefault(false)
    private fun tryObtain(n: AccessibilityNodeInfo) = runCatching { AccessibilityNodeInfo.obtain(n).apply { refresh() } }.getOrNull()

    private fun clamp(v: Int, min: Int, max: Int) = max(min, min(v, max))

    /* ---------- Service setup ---------- */

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun resetIconPosition() {
        val size = Point().also { windowManager.defaultDisplay.getSize(it) }
        lastX = (size.x * .75).toInt()
        lastY = (size.y * .08).toInt()
    }

    private fun createServiceInfo() = AccessibilityServiceInfo().apply {
        eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        notificationTimeout = 100
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(
                    NotificationChannel(channelId, "Input Assist", NotificationManager.IMPORTANCE_MIN)
                )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(notificationId, notification)
    }

    private var lastPackage: String? = null
    private fun clearIgnoredIfAppChanged(pkg: String?) {
        if (pkg != null && pkg != lastPackage) {
            ignoredFields.removeAll { it.first == pkg }
            lastPackage = pkg
        }
    }

    /* ---------- Clean up ---------- */

    private fun cleanup() {
        hideIcon()
        scope.cancel()
        currentNode?.clear()
        ignoredFields.clear()
    }
}