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

    companion object {
        var instance: AutoService? = null
            private set
        
        var isRunning = false
        var onLogCallback: ((String) -> Unit)? = null
        var onPlanCallback: ((TaskPlanner.TaskPlan?) -> Unit)? = null
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
        
        agentController?.onPlanUpdated = { plan ->
            handler.post { onPlanCallback?.invoke(plan) }
        }
    }

    fun startAgent(userRequest: String) {
        if (isRunning) return
        
        log("开始处理: $userRequest")
        
        // Phase 1: Generate plan (in background)
        Thread {
            val success = agentController?.generatePlan(userRequest) ?: false
            
            if (!success) {
                log("计划生成失败")
                return@Thread
            }
            
            // Phase 2: Start execution loop
            handler.post {
                isRunning = true
                startExecutionLoop()
            }
        }.start()
    }
    
    private fun startExecutionLoop() {
        loopRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                Thread {
                    try {
                        val shouldContinue = agentController?.executeStep() ?: false
                        if (!shouldContinue) {
                            isRunning = false
                            log("任务完成")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
    
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        Log.d("AutoService", "Swiping from ($startX, $startY) to ($endX, $endY)")
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 500))
        dispatchGesture(builder.build(), null, null)
    }
}
