/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.akslabs.circletosearch

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Matrix
import androidx.core.graphics.drawable.toBitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.akslabs.circletosearch.R
import com.akslabs.circletosearch.data.ActionType
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.data.GestureType
import com.akslabs.circletosearch.data.OverlayConfigurationManager
import com.akslabs.circletosearch.data.OverlaySegment
import com.akslabs.circletosearch.ui.components.CopyTextOverlayManager
import com.akslabs.circletosearch.utils.ImageUtils
import com.akslabs.circletosearch.utils.BubblePreferences
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CircleToSearchAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private val overlayViews = mutableListOf<View>() // Track all added segment views
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private lateinit var configManager: OverlayConfigurationManager
    
    /** Kept by companion so scroll events can re-scan copy-text nodes. */
    internal var copyTextManager: CopyTextOverlayManager? = null
    
    // Bubble related - Keeping existing logic but refactoring slightly if needed
    // For now, keeping bubble separate as requested in prompt "statusbar overlay customization... but it should work normally like now"
    // The prompt asks to disable statusbar overlay in landscape but keep it working normally.
    
    private var bubbleView: View? = null
    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private val overlayPrefs by lazy { getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE) } // Watch overlay prefs too
    private val bubblePrefs by lazy { BubblePreferences(this) }
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bubble_enabled") {
            updateBubbleState()
        }
    }
    
    private val bubblePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // On bubble preference change, rebuild bubble
        updateBubbleState()
    }
    
    private val overlayPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // On any overlay config change, rebuild the overlay
        updateOverlay()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configManager = OverlayConfigurationManager(this)
        
        // node info from other windows without touching AndroidManifest.xml.
        // Also enable enhanced web accessibility for better WebView coverage.
        val info = serviceInfo
        info.flags = info.flags or 
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        bubblePrefs.registerOnSharedPreferenceChangeListener(bubblePrefsListener)
        overlayPrefs.registerOnSharedPreferenceChangeListener(overlayPrefsListener)
        
        updateBubbleState()
        updateOverlay()
    }
    
    private fun BubblePreferences.registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val bubblePrefsSharedPrefs = this@CircleToSearchAccessibilityService.getSharedPreferences("bubble_prefs", Context.MODE_PRIVATE)
        bubblePrefsSharedPrefs.registerOnSharedPreferenceChangeListener(listener)
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlay()
    }

    private fun updateBubbleState() {
        if (prefs.getBoolean("bubble_enabled", false)) {
            showBubble()
        } else {
            hideBubble()
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return // Already shown

        // Get bubble size and transparency from preferences
        val bubbleSize = bubblePrefs.getBubbleSize()
        val transparency = bubblePrefs.getBubbleTransparency()
        val alpha = bubblePrefs.getAlphaFromTransparency(transparency)

        val params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        bubbleView = View(this).apply {
            setBackgroundResource(R.mipmap.ic_launcher)
            elevation = 10f
            // Set transparency as View alpha (convert 0-255 to 0-1 range)
            this.alpha = alpha / 255f
            
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            
            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(this, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(event.rawX - initialTouchX) < 10 && Math.abs(event.rawY - initialTouchY) < 10) {
                            performCapture()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager?.addView(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideBubble() {
        if (bubbleView != null) {
            try {
                windowManager?.removeView(bubbleView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bubbleView = null
        }
    }

    private fun updateOverlay() {
        val config = configManager.getConfig()
        
        if (!config.isEnabled) {
            overlayViews.forEach { 
                try { windowManager?.removeView(it) } catch(e: Exception) {} 
            }
            overlayViews.clear()
            return
        }

        // --- Resolution of Colors ---
        val primaryColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(android.R.color.system_accent1_500)
        } else {
            Color.parseColor("#FF6200EE") // Default Material Primary
        }

        val themePalette = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.R.color.system_accent1_200,
                android.R.color.system_accent2_200,
                android.R.color.system_accent3_200,
                android.R.color.system_neutral1_200,
                android.R.color.system_neutral2_200
            ).map { 
                val color = getColor(it)
                // Set alpha to 40% (approx 102/255) for subtle blending
                Color.argb(102, Color.red(color), Color.green(color), Color.blue(color))
            }
        } else {
            // Legacy fallbacks with 40% opacity
            listOf(
                Color.parseColor("#66FF0000"), // Red
                Color.parseColor("#6600FF00"), // Green
                Color.parseColor("#660000FF"), // Blue
                Color.parseColor("#66FFFF00"), // Yellow
                Color.parseColor("#66FF00FF")  // Magenta
            )
        }
        
        // Landscape check
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE && !config.isEnabledInLandscape) {
             overlayViews.forEach { 
                try { windowManager?.removeView(it) } catch(e: Exception) {} 
            }
            overlayViews.clear()
            return
        }

        // --- OPTIMIZATION: Diff Update to prevent flashing ---
        // If the number of segments matches, we try to update existing views' LayoutParams
        // If not, we rebuild.
        
        if (overlayViews.size == config.segments.size) {
            // Update mode
            config.segments.forEachIndexed { index, segment ->
                val view = overlayViews[index]
                val params = view.layoutParams as WindowManager.LayoutParams
                
                // Update params
                var changed = false
                if (params.width != segment.width) { params.width = segment.width; changed = true }
                if (params.height != segment.height) { params.height = segment.height; changed = true }
                if (params.x != segment.xOffset) { params.x = segment.xOffset; changed = true }
                if (params.y != segment.yOffset) { params.y = segment.yOffset; changed = true }
                
                if (changed) {
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // Fallback implies view might be detached, shouldn't happen commonly
                    }
                }
                
                // Update Color (Debug)
                if (config.isVisible) {
                    val color = themePalette[index % themePalette.size]
                    val drawable = GradientDrawable().apply {
                        setColor(color)
                        // Only show solid primary border for the expanded/active overlay
                        if (index == config.activeSegmentIndex) {
                            setStroke(3, primaryColor)
                        }
                    }
                    view.background = drawable
                    view.elevation = 0f
                } else {
                    view.background = null
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.elevation = 0f
                }
                
                // Update gesture listener
                attachTouchListener(view, segment, index)
            }
        } else {
            // Rebuild mode (Count changed)
            overlayViews.forEach { 
                try { windowManager?.removeView(it) } catch(e: Exception) {} 
            }
            overlayViews.clear()
            
            config.segments.forEachIndexed { index, segment ->
                val view = View(this)
                val params = WindowManager.LayoutParams(
                    segment.width,
                    segment.height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                
                params.gravity = Gravity.TOP or Gravity.START
                params.x = segment.xOffset
                params.y = segment.yOffset
                
                 if (config.isVisible) {
                    val color = themePalette[index % themePalette.size]
                    val drawable = GradientDrawable().apply {
                        setColor(color)
                        // Only show solid primary border for the expanded/active overlay
                        if (index == config.activeSegmentIndex) {
                            setStroke(3, primaryColor)
                        }
                    }
                    view.background = drawable
                    view.elevation = 0f
                } else {
                    view.background = null
                    view.setBackgroundColor(Color.TRANSPARENT)
                    view.elevation = 0f
                }

                attachTouchListener(view, segment, index)
                
                try {
                    windowManager?.addView(view, params)
                    overlayViews.add(view)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(view: View, segment: OverlaySegment, segmentIndex: Int) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val action = segment.gestures[GestureType.DOUBLE_TAP] ?: ActionType.NONE
                if (action != ActionType.NONE) { performAction(action, segment); return true }
                return false
            }
            
            override fun onLongPress(e: MotionEvent) {
                val action = segment.gestures[GestureType.LONG_PRESS] ?: ActionType.NONE
                if (action != ActionType.NONE) performAction(action, segment)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                 // User wants "buttons behind to be clickable" 
                 // We temporarily disable touch on our window and dispatch the click through.
                 propagateSingleTap(view, e.rawX, e.rawY)
                 return false
            }
            
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                        if (diffX > 0) {
                             // Swipe Right
                             val action = segment.gestures[GestureType.SWIPE_RIGHT] ?: ActionType.NONE
                             if (action != ActionType.NONE) { performAction(action, segment); return true }
                        } else {
                            // Swipe Left
                            val action = segment.gestures[GestureType.SWIPE_LEFT] ?: ActionType.NONE
                             if (action != ActionType.NONE) { performAction(action, segment); return true }
                        }
                    }
                } else {
                    // Reduced threshold for vertical swipes to work with smaller overlay heights
                    if (Math.abs(diffY) > 50 && Math.abs(velocityY) > 100) {
                        if (diffY > 0) {
                             // Swipe Down
                             android.util.Log.d("CTS_Swipe", "Swipe DOWN detected - segmentIndex=$segmentIndex, diffY=$diffY, velocityY=$velocityY")
                             val action = segment.gestures[GestureType.SWIPE_DOWN] ?: ActionType.NONE
                             if (action != ActionType.NONE) {
                                 android.util.Log.d("CTS_Swipe", "Custom action assigned: $action")
                                 performAction(action, segment) 
                             } else {
                                 // Smart Swipe Logic: Only apply for first overlay (index 0) when it's full width
                                 val screenWidth = resources.displayMetrics.widthPixels
                                 val isFirstOverlay = segmentIndex == 0
                                 val isFullWidth = segment.width >= screenWidth
                                 
                                 android.util.Log.d("CTS_Swipe", "Smart swipe check - isFirstOverlay=$isFirstOverlay, isFullWidth=$isFullWidth (width=${segment.width}, screenWidth=$screenWidth)")
                                 
                                 if (isFirstOverlay && isFullWidth) {
                                     // Smart logic: Check where user actually swiped (touch X position)
                                     // Left half of screen = Notifications, Right half = Quick Settings
                                     val touchX = e1.rawX
                                     android.util.Log.d("CTS_Swipe", "Smart swipe active - touchX=$touchX, screenWidth/2=${screenWidth/2}")
                                     if (touchX < (screenWidth / 2)) {
                                         android.util.Log.d("CTS_Swipe", "Opening NOTIFICATIONS (left half)")
                                         performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                                     } else {
                                         android.util.Log.d("CTS_Swipe", "Opening QUICK_SETTINGS (right half)")
                                         performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                                     }
                                 } else {
                                     // Default: Always open notification shade
                                     android.util.Log.d("CTS_Swipe", "Default behavior - Opening NOTIFICATIONS")
                                     performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                                 }
                             }
                             return true
                        } else {
                            // Swipe Up
                             val action = segment.gestures[GestureType.SWIPE_UP] ?: ActionType.NONE
                             if (action != ActionType.NONE) { performAction(action, segment); return true }
                        }
                    }
                }
                return false
            }
        }).apply {
             setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val action = segment.gestures[GestureType.DOUBLE_TAP] ?: ActionType.NONE
                    if (action != ActionType.NONE) { performAction(action, segment); return true }
                    return false
                }
                override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
            })
        }
        
        var lastTapTime: Long = 0
        var tapCount = 0
        
        view.setOnTouchListener { _, event ->
             if (event.action == MotionEvent.ACTION_DOWN) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < 400) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = currentTime
                
                if (tapCount == 3) {
                     val action = segment.gestures[GestureType.TRIPLE_TAP] ?: ActionType.NONE
                     if (action != ActionType.NONE) {
                         performAction(action, segment)
                         tapCount = 0 
                         return@setOnTouchListener true
                     }
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }
    }
    
    private fun performAction(action: ActionType, segment: OverlaySegment) {
        if (action == ActionType.NONE) return
        
        // Haptic feedback for action trigger
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
             @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }

        when(action) {
            ActionType.SCREENSHOT -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            ActionType.FLASHLIGHT -> toggleFlashlight()
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
            ActionType.OPEN_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.OPEN_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.OPEN_APP -> {
                // Open App Logic
                val packageName = segment.gestureData[findGestureForAction(segment, ActionType.OPEN_APP)]
                if (!packageName.isNullOrEmpty()) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                }
            }
            ActionType.CTS_AUTO -> {
                 // Respect Global Preference
                 performCapture(null)
            }
            ActionType.CTS_LENS -> {
                 // Force Lens Mode for this session only
                 performCapture(true)
            }
            ActionType.CTS_MULTI -> {
                 // Force Multi Mode for this session only
                 performCapture(false)
            }
            ActionType.SPLIT_SCREEN -> {
                 val success = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                 if (!success) {
                     android.widget.Toast.makeText(this, getString(R.string.toast_split_screen_failed), android.widget.Toast.LENGTH_SHORT).show()
                 }
            }
            ActionType.SCROLL_TOP -> performScroll(true)
            ActionType.SCROLL_BOTTOM -> performScroll(false)
            ActionType.SCREEN_OFF -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                     android.widget.Toast.makeText(this, getString(R.string.toast_screen_off_requires_api), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            ActionType.TOGGLE_AUTO_ROTATE -> toggleAutoRotate()
            ActionType.MEDIA_PLAY_PAUSE -> injectMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.MEDIA_NEXT -> injectMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionType.MEDIA_PREVIOUS -> injectMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> {}
        }
    }
    
    // Helpers for new actions
    
    private fun performScroll(toTop: Boolean) {
        android.util.Log.d("CTS_Scroll", "performScroll called - toTop=$toTop")
        
        // We simulate multiple quick swipes instead of one long one
        // This is more reliable and less likely to be cancelled
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        
        // Perform 3 quick swipes with delays
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        for (i in 0..2) {
            handler.postDelayed({
                // Scroll To Top = Swipe DOWN (drag content down, revealing top)
                // Scroll To Bottom = Swipe UP (drag content up, revealing bottom)
                val startY = if (toTop) displayMetrics.heightPixels * 0.3f else displayMetrics.heightPixels * 0.7f
                val endY = if (toTop) displayMetrics.heightPixels * 0.7f else displayMetrics.heightPixels * 0.3f
                
                android.util.Log.d("CTS_Scroll", "Scroll swipe #${i+1} - toTop=$toTop, centerX=$centerX, startY=$startY, endY=$endY")
                
                val path = android.graphics.Path().apply {
                    moveTo(centerX, startY)
                    lineTo(centerX, endY)
                }
                // Shorter, faster swipes (200ms each)
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                
                val success = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        android.util.Log.d("CTS_Scroll", "Scroll swipe #${i+1} COMPLETED")
                    }
                    
                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        android.util.Log.e("CTS_Scroll", "Scroll swipe #${i+1} CANCELLED")
                    }
                }, null)
                
                android.util.Log.d("CTS_Scroll", "Scroll swipe #${i+1} dispatched: $success")
            }, i * 250L) // 250ms delay between each swipe
        }
    }
    
    private fun injectMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val eventTime = android.os.SystemClock.uptimeMillis()
        
        val downEvent = android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_UP, keyCode, 0)
        
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }
    
    private fun toggleAutoRotate() {
        if (android.provider.Settings.System.canWrite(this)) {
            val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
            val next = if (current == 1) 0 else 1
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, next)
            android.widget.Toast.makeText(this, getString(R.string.toast_auto_rotate, if (next == 1) getString(R.string.status_on) else getString(R.string.status_off)), android.widget.Toast.LENGTH_SHORT).show()
        } else {
             android.widget.Toast.makeText(this, getString(R.string.toast_auto_rotate_permission), android.widget.Toast.LENGTH_SHORT).show()
             val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                 data = android.net.Uri.parse("package:$packageName")
                 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
             }
             startActivity(intent)
        }
    }
    
    private fun findGestureForAction(segment: OverlaySegment, action: ActionType): GestureType {
        return segment.gestures.entries.firstOrNull { it.value == action }?.key ?: GestureType.DOUBLE_TAP
    }
    
    // Pass-through Logic for Single Tap
    // We must temporarily make the window UNTOUCHABLE so the injected gesture falls through to the app below.
    // Otherwise, the injected tap hits our own overlay (loop/blocked).
    private fun propagateSingleTap(view: View, x: Float, y: Float) {
        val params = view.layoutParams as WindowManager.LayoutParams
        val originalFlags = params.flags
        
        // Make untouchable
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager?.updateViewLayout(view, params)
        
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50) // 50ms tap duration (more standard)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        
        // Wait for WindowManager to update input focus before dispatching
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    restoreFlags()
                }
     
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    restoreFlags()
                }
                
                fun restoreFlags() {
                    // Restore original flags (Touchable) using main thread to be safe with UI
                    handler.post {
                        params.flags = originalFlags
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // View might be removed
                        }
                    }
                }
            }, null)
        }, 100) // 100ms Delay to ensure 'untouchable' takes effect solidly
    }
    
    private fun toggleFlashlight() {
         try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            // This is tricky because we don't know current state easily without callback.
            // For now, let's assume valid flash.
            // A robust implementation needs a callback to track state.
            // We'll just try to turn it on for a second for testing or we need a tracked state.
            // Let's implement a simple tracking using static var or prefs?
            // Or just ignore toggle for now and just turn ON? No, user expects toggle.
            // Let's use a static state?
            if (isFlashlightOn) {
                cameraManager.setTorchMode(cameraId, false)
                isFlashlightOn = false
            } else {
                cameraManager.setTorchMode(cameraId, true)
                isFlashlightOn = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performCapture(searchModeOverride: Boolean? = null) {
        android.util.Log.d("CircleToSearch", "performCapture called. hasWindowManager=${windowManager != null}")
        
        // Clear repository at the source to prevent any "ghost" flash of old data
        BitmapRepository.clear()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                         try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (bitmap == null) {
                                hardwareBuffer.close()
                                return
                            }

                            // Copy to software bitmap
                            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close() // Close buffer after copy

                            if (copy == null) {
                                return
                            }
                            
                            // Store in Repository (In-Memory)
                            BitmapRepository.setScreenshot(copy)
                            
                            // Launch Overlay Immediately
                            launchOverlay(searchModeOverride)
                            
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.e("CircleToSearch", "Screenshot failed with error code: $errorCode")
                    }
                }
            )
        }
    }

    fun launchOverlay(searchModeOverride: Boolean? = null) {
        android.util.Log.d("CircleToSearchAccess", "AccessibilityService launching OverlayActivity")
        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // Disable animation for faster feel
            searchModeOverride?.let { putExtra("EXTRA_SEARCH_MODE_OVERRIDE", it) }
        }
        startActivity(intent)
    }

    companion object {
        var isFlashlightOn = false
    }

    // Custom ImageView that clips to rounded corners on the Canvas level
    // This is much more robust than OutlineProvider for rapid movements and scaling.
    private class RoundedImageView(context: android.content.Context) : android.widget.ImageView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val matrix = Matrix()
        private val radius = 12f * context.resources.displayMetrics.density

        override fun onDraw(canvas: android.graphics.Canvas) {
            val drawable = drawable ?: return
            val bitmap = try { 
                drawable.toBitmap() 
            } catch (e: Exception) { 
                return 
            }
            
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            
            // Adjust shader to current view bounds
            matrix.reset()
            matrix.setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            shader.setLocalMatrix(matrix)
            
            paint.shader = shader
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            
            // This never "looses roundness" because it's rendering at the pixel level on each frame
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun showPinnedArea(bitmap: Bitmap, rect: android.graphics.Rect) {
        android.util.Log.d("CircleToSearch", "showPinnedArea called for rect: $rect")
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Initial Position: Center of the selection
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        
        // --- Phase 32: 1:1 Scaling ---
        // Use natural dimensions of the cropped bitmap (match what user saw in lens)
        val width = bitmap.width
        val height = bitmap.height

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = centerX - width / 2
        params.y = centerY - height / 2

        val pinnedView = RoundedImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            elevation = 0f
            clipToOutline = true
            
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false
            var isScaling = false
            var currentMenu: View? = null
            
            // --- Phase 45: Sticker Physics ---
            var velocityTracker: android.view.VelocityTracker? = null
            var flingAnimator: android.animation.ValueAnimator? = null
            
            val stopFling = {
                flingAnimator?.cancel()
                flingAnimator = null
            }

            // --- Phase 33: ScaleGestureDetector for pinch zoom ---
            val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    val scaleFactor = detector.scaleFactor
                    
                    val newWidth = (params.width * scaleFactor).toInt()
                    val newHeight = (params.height * scaleFactor).toInt()
                    
                    // Constraints: 15% to 95% of screen
                    val minDim = (screenWidth * 0.15f).toInt()
                    val maxDim = (screenWidth * 0.95f).toInt()
                    
                    if (newWidth in minDim..maxDim && newHeight in minDim..maxDim) {
                        // Adjust position to scale from center of pinch
                        val focusX = detector.focusX
                        val focusY = detector.focusY
                        
                        params.x -= ((newWidth - params.width) * (focusX / this@apply.width)).toInt()
                        params.y -= ((newHeight - params.height) * (focusY / this@apply.height)).toInt()
                        
                        params.width = newWidth
                        params.height = newHeight
                        windowManager?.updateViewLayout(this@apply, params)
                    }
                    return true
                }
            })

            // --- Phase 31: GestureDetector for reliable long-press ---
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (!isDragging) {
                        // Show actions
                        if (currentMenu == null) {
                            showPinnedActions(this@apply, bitmap, params) { menu ->
                                currentMenu = menu
                            }
                        }
                    }
                }
            })

            @SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { v, event ->
                // Feed both detectors
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isScaling = false
                        stopFling()
                        velocityTracker?.recycle()
                        velocityTracker = android.view.VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        isScaling = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isScaling) return@setOnTouchListener true
                        
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            // If dragging, dismiss menu
                            currentMenu?.let { try { windowManager?.removeView(it) } catch(e: Exception) {} }
                            currentMenu = null
                        }
                        
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(v, params)
                        velocityTracker?.addMovement(event)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isScaling = false
                        if (isDragging) {
                            velocityTracker?.computeCurrentVelocity(1000)
                            val vx = velocityTracker?.xVelocity ?: 0f
                            val vy = velocityTracker?.yVelocity ?: 0f
                            
                            if (Math.abs(vx) > 300 || Math.abs(vy) > 300) {
                                // Start physics fling
                                flingAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = 2000
                                    interpolator = android.view.animation.LinearInterpolator()
                                    var lastTime = 0f
                                    var currVx = vx * 2.2f
                                    var currVy = vy * 2.2f
                                    
                                    addUpdateListener { anim ->
                                        val faction = anim.animatedFraction
                                        val dt = faction - lastTime
                                        lastTime = faction
                                        
                                        params.x += (currVx * dt * 0.95f).toInt()
                                        params.y += (currVy * dt * 0.95f).toInt()
                                        
                                        // Bounce off edges (Bouncy!)
                                        val display = resources.displayMetrics
                                        if (params.x < 0 || params.x + params.width > display.widthPixels) {
                                            currVx = -currVx * 0.9f 
                                            params.x = params.x.coerceIn(0, display.widthPixels - params.width)
                                        }
                                        if (params.y < 0 || params.y + params.height > display.heightPixels) {
                                            currVy = -currVy * 0.9f
                                            params.y = params.y.coerceIn(0, display.heightPixels - params.height)
                                        }
                                        
                                        try { 
                                            windowManager?.updateViewLayout(v, params)
                                        } catch(e: Exception) { 
                                            anim.cancel()
                                            flingAnimator = null
                                        }
                                        
                                        // Lower friction for fun play
                                        currVx *= 0.992f
                                        currVy *= 0.992f
                                        
                                        // Safety check: if velocity is zero or view is gone, stop
                                        if (Math.abs(currVx) < 10 && Math.abs(currVy) < 10) {
                                            anim.cancel()
                                            flingAnimator = null
                                        }
                                    }
                                    addListener(object : android.animation.AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: android.animation.Animator) {
                                            flingAnimator = null
                                        }
                                        override fun onAnimationCancel(animation: android.animation.Animator) {
                                            flingAnimator = null
                                        }
                                    })
                                    start()
                                }
                            }
                        }
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager?.addView(pinnedView, params)
            
            // --- Beautiful Pin Animation ---
            pinnedView.scaleX = 0f
            pinnedView.scaleY = 0f
            pinnedView.rotation = -15f
            pinnedView.alpha = 0f
            pinnedView.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .rotation(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
                
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showPinnedActions(view: View, bitmap: Bitmap, params: WindowManager.LayoutParams, callback: (View) -> Unit) {
        // Placeholder for pinned actions menu - implement as needed
    }
}
