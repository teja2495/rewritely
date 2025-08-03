package com.tk.rewritely

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.*

class RewritelyService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var floatingIcon: View? = null
    private var sparkleIcon: ImageView? = null
    private var optionsIcon: ImageView? = null

    private var originalText: String = ""
    private var newText: String = ""

    private var lastPackage: String? = null

    private var currentNode: WeakReference<AccessibilityNodeInfo>? = null
    private val ignoredFields = mutableSetOf<Pair<String?, String?>>()

    // Guard so we don't hide the icon while the menu is visible
    private var isOptionsMenuShowing = false

    // Prevent multiple API calls at once
    @Volatile private var isFetchInProgress = false

    // Icon position & drag state
    private var lastX = 0
    private var lastY = 0
    private var initialX = 0
    private var initialY = 0
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var longPressJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Foreground notification channel
    private val channelId = "InputAssistChannel"
    private val notificationId = 1

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = createLayoutParams()
        resetIconPosition()
        serviceInfo = createServiceInfo()
        startForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isOptionsMenuShowing) return

        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                clearIgnoredIfAppChanged(event.packageName?.toString())
                handleFocusChange(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusChange(event.source)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                    handleFocusChange(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        }
    }

    override fun onInterrupt() = cleanup()
    override fun onDestroy() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    private fun handleFocusChange(node: AccessibilityNodeInfo?) {
        val fresh = node?.let { tryObtain(it) }
        val id = fresh?.id()
        if (id in ignoredFields) {
            fresh?.recycle()
            return hideIcon()
        }
        if (fresh?.isEditable == true && fresh.isFocused) {
            currentNode = WeakReference(fresh)
            updateIcon()
        } else {
            currentNode?.clear()
            hideIcon()
            fresh?.recycle()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateIcon() {
        val node = currentNode?.get() ?: return hideIcon()
        if (!node.stillValid()) return hideIcon()

        val words = node.text?.split("\\s+".toRegex())?.count { it.isNotBlank() } ?: 0
        if (words <= 2) return hideIcon()

        if (floatingIcon == null) {
            // Create floating icon programmatically
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Create sparkle icon
            sparkleIcon = ImageView(this).apply {
                setImageResource(R.drawable.sparkle)
                layoutParams = LinearLayout.LayoutParams(27.dpToPx(), 27.dpToPx())
            }

            // Create options icon
            optionsIcon = ImageView(this).apply {
                setImageResource(R.drawable.options_icon)
                layoutParams = LinearLayout.LayoutParams(25.dpToPx(), 25.dpToPx()).apply {
                    marginStart = 8.dpToPx()
                }
            }

            container.addView(sparkleIcon)
            container.addView(optionsIcon)

            val touchListener = iconTouchListener()
            sparkleIcon?.setOnTouchListener(touchListener)
            optionsIcon?.setOnTouchListener(touchListener)

            floatingIcon = container
            windowManager.addView(
                container,
                params.apply {
                    x = lastX
                    y = lastY
                }
            )
        }
    }

    private fun hideIcon() {
        longPressJob?.cancel()
        longPressJob = null
        floatingIcon?.let { runCatching { windowManager.removeView(it) } }
        floatingIcon = null
        sparkleIcon = null
        optionsIcon = null
    }

    private fun iconTouchListener() =
            View.OnTouchListener { v, e ->
                val size = Point().also { windowManager.defaultDisplay.getSize(it) }
                val slop = ViewConfiguration.get(this).scaledTouchSlop

                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        downX = e.rawX
                        downY = e.rawY
                        isDragging = false
                        longPressJob =
                                scope.launch {
                                    delay(ViewConfiguration.getLongPressTimeout().toLong())
                                    if (!isDragging) onIconLongPress()
                                }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragging &&
                                        (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)
                        ) {
                            isDragging = true
                            longPressJob?.cancel()
                        }
                        if (isDragging) {
                            params.x =
                                    clamp(initialX + (e.rawX - downX).toInt(), 0, size.x - v.width)
                            params.y =
                                    clamp(initialY + (e.rawY - downY).toInt(), 0, size.y - v.height)
                            windowManager.updateViewLayout(floatingIcon, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (isDragging) {
                            lastX = params.x
                            lastY = params.y
                        } else if (e.action == MotionEvent.ACTION_UP) {
                            if (v == optionsIcon) {
                                showOptionsMenu(v)
                            } else if (v == sparkleIcon) {
                                fetchNewText("Rewrite in common language, NEVER use Em Dashes: ")
                            }
                        }
                        isDragging = false
                        true
                    }
                    else -> false
                }
            }

    private fun onIconLongPress() {
        currentNode?.get()?.id()?.let { ignoredFields += it }
        Toast.makeText(this, "Icon hidden for this field.", Toast.LENGTH_SHORT).show()
        hideIcon()
    }

    private fun getInputFieldText(): String {
        val node = currentNode?.get()?.takeIf { it.stillValid() } ?: return ""
        if (node.id() in ignoredFields) {
            hideIcon()
            return ""
        }
        val currentText = node.text?.toString().orEmpty()
        return currentText
    }

    private fun setInputFieldText(text: String) {
        val node = currentNode?.get()?.takeIf { it.stillValid() }
        Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, this)
            }
        }
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, this)
            }
        }
    }

    // OpenAI API Call
    private fun fetchNewText(prefix: String) {
        if (isFetchInProgress) return

        val apiKey =
                SecurePrefs.getApiKey(this)
                        ?: return Toast.makeText(this, "API Key not set.", Toast.LENGTH_LONG).show()

        originalText = getInputFieldText()
        if (originalText.isBlank()) {
            return Toast.makeText(this, "Nothing to rewrite.", Toast.LENGTH_SHORT).show()
        }

        isFetchInProgress = true
        Toast.makeText(this, "Sending to AI...", Toast.LENGTH_SHORT).show()
        val prompt = "$prefix $originalText"

        scope.launch(Dispatchers.IO) {
            try {
                val request = OpenAiRequest(messages = listOf(Message(content = prompt)))
                val res = ApiClient.instance.getCompletion("Bearer $apiKey", request)
                withContext(Dispatchers.Main) {
                    val result = res.body()?.choices?.firstOrNull()?.message?.content?.trim()
                    if (res.isSuccessful && !result.isNullOrBlank()) {
                        setInputFieldText(result)
                        newText = result
                    } else {
                        Toast.makeText(applicationContext, "API Error", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.network_error, Toast.LENGTH_LONG)
                            .show()
                }
            } finally {
                withContext(Dispatchers.Main) { isFetchInProgress = false }
            }
        }
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.popup_menu, popup.menu)

        popup.menu.findItem(R.id.action_undo)?.isVisible =
                false // getInputFieldText() == newText && originalText.isNotBlank()
        popup.menu.findItem(R.id.action_redo)?.isVisible =
                getInputFieldText() == originalText && newText.isNotBlank()

        isOptionsMenuShowing = true
        popup.setOnDismissListener { isOptionsMenuShowing = false }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_fix_grammar -> {
                    fetchNewText("Just fix the grammar: ")
                    true
                }
                R.id.action_undo -> {
                    setInputFieldText(originalText)
                    true
                }
                R.id.action_redo -> {
                    setInputFieldText(newText)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun AccessibilityNodeInfo.id() = Pair(packageName?.toString(), viewIdResourceName)

    private fun AccessibilityNodeInfo.stillValid() =
            runCatching {
                        refresh()
                        isEditable && isFocused
                    }
                    .getOrDefault(false)

    private fun tryObtain(n: AccessibilityNodeInfo) =
            runCatching { AccessibilityNodeInfo.obtain(n).apply { refresh() } }.getOrNull()

    private fun clamp(v: Int, min: Int, max: Int) = max(min, min(v, max))

    private fun createLayoutParams() =
            WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            else WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            PixelFormat.TRANSLUCENT
                    )
                    .apply { gravity = Gravity.TOP or Gravity.START }

    private fun resetIconPosition() {
        val size = Point().also { windowManager.defaultDisplay.getSize(it) }
        lastX = (size.x * .75).toInt()
        lastY = (size.y * .08).toInt()
    }

    private fun createServiceInfo() =
            AccessibilityServiceInfo().apply {
                eventTypes =
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags =
                        AccessibilityServiceInfo.DEFAULT or
                                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 100
            }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(
                            NotificationChannel(
                                    channelId,
                                    "Rewritely",
                                    NotificationManager.IMPORTANCE_MIN
                            )
                    )
        }
        val notification =
                NotificationCompat.Builder(this, channelId)
                        .setContentTitle(getString(R.string.foreground_notification_title))
                        .setContentText(getString(R.string.foreground_notification_text))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(
                        notificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
        else startForeground(notificationId, notification)
    }

    private fun clearIgnoredIfAppChanged(pkg: String?) {
        if (pkg != null && pkg != lastPackage) {
            ignoredFields.removeAll { it.first == pkg }
            lastPackage = pkg
        }
    }

    private fun cleanup() {
        hideIcon()
        scope.cancel()
        currentNode?.clear()
        ignoredFields.clear()
    }

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }
}
