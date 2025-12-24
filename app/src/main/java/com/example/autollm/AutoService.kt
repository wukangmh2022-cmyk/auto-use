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
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

class AutoService : AccessibilityService() {

    companion object {
        var instance: AutoService? = null
        private set
        
        var isRunning = false
        var onLogCallback: ((String) -> Unit)? = null
    }

    private var agentController: AgentController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var loopRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoService", "Service Connected")
        instance = this
        
        agentController = AgentController(this) { msg ->
            onLogCallback?.invoke(msg)
        }
    }

    fun startAgent(goal: String) {
        if (isRunning) return
        isRunning = true
        
        agentController?.setGoal(goal)
        log("Agent 启动")
        
        loopRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                Thread {
                    try {
                        val shouldContinue = agentController?.runOnce() ?: false
                        if (!shouldContinue) {
                            isRunning = false
                            log("Agent 已停止")
                            return@Thread
                        }
                    } catch (e: Exception) {
                        log("错误: ${e.message}")
                        Log.e("AutoService", "Loop error", e)
                    }
                    
                    // Schedule next run after 3 seconds
                    if (isRunning) {
                        handler.postDelayed(loopRunnable!!, 3000)
                    }
                }.start()
            }
        }
        
        handler.post(loopRunnable!!)
    }

    fun stopAgent() {
        isRunning = false
        loopRunnable?.let { handler.removeCallbacks(it) }
        log("Agent 已停止")
    }

    private fun log(msg: String) {
        Log.d("AutoService", msg)
        onLogCallback?.invoke(msg)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used in polling mode
    }

    override fun onInterrupt() {
        Log.d("AutoService", "Service Interrupted")
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
    }

    // --- API for AgentController ---

    fun dumpUI(): String {
        val root = rootInActiveWindow ?: return "[]"
        val list = JSONArray()
        collectNodes(root, list)
        return list.toString()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, list: JSONArray) {
        if (node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || node.isClickable) {
                val info = JSONObject()
                info.put("txt", text ?: "")
                info.put("desc", desc ?: "")
                info.put("id", node.viewIdResourceName ?: "")
                info.put("cls", node.className?.toString()?.substringAfterLast(".") ?: "")
                info.put("bnds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
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
