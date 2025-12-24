package com.example.autollm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

class AutoService : AccessibilityService() {

    private var scriptExecutor: ScriptExecutor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoService", "Service Connected")
        scriptExecutor = ScriptExecutor(this)
        
        // Start the loop loop
        isRunning = true
        startLoop()
    }
    
    // Simple loop to run script periodically (naive "24h" approach)
    private fun startLoop() {
        if (!isRunning) return
        
        // Run in a background thread to avoid blocking main thread (Accessibility)
        Thread {
            try {
                val prefs = getSharedPreferences("AutoLLM", MODE_PRIVATE)
                val script = prefs.getString("script", "")
                if (!script.isNullOrEmpty()) {
                    scriptExecutor?.execute(script)
                }
            } catch (e: Exception) {
                Log.e("AutoService", "Loop error", e)
            } finally {
                 // Schedule next run after 5 seconds (configurable in future)
                 handler.postDelayed({ startLoop() }, 5000)
            }
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Optional: Trigger script on specific events instead of polling
    }

    override fun onInterrupt() {
        Log.d("AutoService", "Service Interrupted")
        isRunning = false
    }

    // --- Exposed API for Script ---

    fun dumpUI(): String {
        val root = rootInActiveWindow ?: return "[]"
        val list = JSONArray()
        collectNodes(root, list)
        return list.toString()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, list: JSONArray) {
        if (node.isVisibleToUser) { // only visible nodes
            val info = JSONObject()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            // Only add useful nodes (has text or is clickable)
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || node.isClickable) {
                info.put("txt", text ?: "")
                info.put("desc", desc ?: "")
                info.put("id", node.viewIdResourceName ?: "")
                info.put("cls", node.className)
                info.put("bnds", "${'$'}{rect.left},${'$'}{rect.top},${'$'}{rect.right},${'$'}{rect.bottom}")
                info.put("clk", node.isClickable)
                list.put(info)
            }
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, list) }
        }
    }

    fun performClick(x: Float, y: Float) {
        Log.d("AutoService", "Clicking at $x, $y")
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(builder.build(), null, null)
    }
}
