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
        var onTokenUpdateCallback: ((Int) -> Unit)? = null
        
        // 定时任务触发时使用
        var pendingPlanToExecute: TaskPlanner.TaskPlan? = null
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
        
        // Pass token usage to UI
        agentController?.onTokenUsage = { total ->
             onTokenUpdateCallback?.invoke(total)
        }
        
        // 检查是否有待执行的定时任务
        pendingPlanToExecute?.let { plan ->
            pendingPlanToExecute = null
            startWithPlan(plan)
        }
    }
    
    fun setVisionMode(mode: Int) {
        agentController?.setVisionMode(mode)
        val modeName = when(mode) {
            0 -> "纯文本"
            1 -> "视觉辅助"
            2 -> "VLM端到端"
            else -> "未知"
        }
        log("视觉模式: $modeName")
    }

    /**
     * 用新生成的需求启动
     */
    fun startAgent(userRequest: String) {
        if (isRunning) return
        
        log("开始处理: $userRequest")
        
        Thread {
            val success = agentController?.generatePlan(userRequest) ?: false
            
            if (!success) {
                log("计划生成失败")
                return@Thread
            }
            
            handler.post {
                isRunning = true
                startExecutionLoop()
            }
        }.start()
    }
    
    /**
     * 用已有计划启动
     */
    fun startWithPlan(plan: TaskPlanner.TaskPlan) {
        if (isRunning) return
        
        log("加载任务: ${plan.name}")
        agentController?.executePlan(plan)
        
        isRunning = true
        startExecutionLoop()
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
    
    fun getCurrentPlan(): TaskPlanner.TaskPlan? = agentController?.currentPlan

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

    private var lastUiHash: Int = 0

    fun dumpUI(): String {
        val root = rootInActiveWindow ?: return "[]"
        val list = JSONArray()
        collectNodes(root, list)
        val result = list.toString()
        
        // 简单哈希用于变化检测
        val currentHash = result.hashCode()
        if (currentHash == lastUiHash && isRunning) {
            return "SAME" // 特殊标记，表示界面无变化
        }
        lastUiHash = currentHash
        return result
    }

    private fun collectNodes(node: AccessibilityNodeInfo, list: JSONArray) {
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val isClickable = node.isClickable
        
        // 过滤：只有有文字、有描述，或者明确可点击的节点才上传
        if (text.isNotEmpty() || desc.isNotEmpty() || isClickable) {
            val info = JSONObject()
            if (text.isNotEmpty()) info.put("t", text)
            if (desc.isNotEmpty()) info.put("d", desc)
            if (isClickable) info.put("k", 1) // 1 表示 true
            
            // 只有在没有文字/描述但可点击时，才强制上传 ID 和 Class 以便 LLM 猜测
            if (text.isEmpty() && desc.isEmpty()) {
                val id = node.viewIdResourceName?.substringAfterLast("/") ?: ""
                if (id.isNotEmpty()) info.put("i", id)
                val cls = node.className?.toString()?.substringAfterLast(".") ?: ""
                if (cls.isNotEmpty()) info.put("c", cls)
            }
            
            // 坐标精简：只保留中心点
            info.put("b", "${rect.centerX()},${rect.centerY()}")
            
            list.put(info)
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
    
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 500) {
        Log.d("AutoService", "Swiping from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms")
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        dispatchGesture(builder.build(), null, null)
    }
    
    /**
     * 长按操作
     * @param x 坐标
     * @param y 坐标
     * @param durationMs 按住时长（毫秒），默认1000ms
     */
    fun performLongPress(x: Float, y: Float, durationMs: Long = 1000) {
        Log.d("AutoService", "Long pressing at $x, $y for ${durationMs}ms")
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        dispatchGesture(builder.build(), null, null)
    }
    
    /**
     * 拖动操作（从一点拖到另一点）
     * @param startX/Y 起始点
     * @param endX/Y 终点
     * @param durationMs 拖动时长，默认800ms
     */
    fun performDrag(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 800) {
        Log.d("AutoService", "Dragging from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms")
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        dispatchGesture(builder.build(), null, null)
    }

    /**
     * 输入文字到输入框
     * @param text 要输入的文字
     * @param x 输入框坐标
     * @param y 输入框坐标
     */
    fun performInput(text: String, x: Float? = null, y: Float? = null): Boolean {
        Log.d("AutoService", "Input text: '$text' at ($x, $y)")
        
        // 1. 如果提供了坐标，先点击激活输入框
        if (x != null && y != null) {
            performClick(x, y)
            Thread.sleep(500) // 等待输入框获取焦点和键盘弹出
        }
        
        // 2. 查找可编辑节点
        val root = rootInActiveWindow
        if (root == null) {
            log("无法获取窗口根节点")
            return false
        }
        
        // 策略1: 通过坐标查找可编辑节点
        var targetNode: AccessibilityNodeInfo? = null
        if (x != null && y != null) {
            targetNode = findEditableNodeAt(root, x.toInt(), y.toInt())
        }
        
        // 策略2: 查找当前焦点的可编辑节点
        if (targetNode == null) {
            targetNode = findFocusedEditableNode(root)
        }
        
        // 策略3: 找任意可编辑节点
        if (targetNode == null) {
            targetNode = findAnyEditableNode(root)
        }
        
        if (targetNode == null) {
            log("未找到任何可编辑节点")
            return false
        }
        
        log("找到输入框: ${targetNode.className}")
        
        // 尝试设置文本
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        
        if (result) {
            log("输入成功: $text")
            targetNode.recycle()
            return true
        }
        
        // ACTION_SET_TEXT 失败，尝试粘贴
        log("ACTION_SET_TEXT 失败，尝试粘贴")
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("input", text)
        clipboard.setPrimaryClip(clip)
        
        val pasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        targetNode.recycle()
        
        if (pasteResult) {
            log("粘贴成功: $text")
        } else {
            log("粘贴也失败了")
        }
        
        return pasteResult
    }
    
    private fun findEditableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        if (rect.contains(x, y) && node.isEditable) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeAt(child, x, y)
            if (result != null) return result
            child.recycle()
        }
        return null
    }
    
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }
    
    private fun findAnyEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAnyEditableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    // --- Screenshot Utils ---
    
    fun captureScreenshotBase64(): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            log("截图功能需要 Android 11+")
            return null
        }
        
        var resultBase64: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                this.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            resultBase64 = compressBitmap(bitmap)
                            screenshot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.e("AutoService", "Screenshot process failed", e)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("AutoService", "Screenshot failed code: $errorCode")
                        latch.countDown()
                    }
                }
            )
            
            latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e("AutoService", "Take screenshot error", e)
        }
        
        return resultBase64
    }
    
    private fun compressBitmap(bitmap: android.graphics.Bitmap?): String? {
        if (bitmap == null) return null
        // Copy to software bitmap to scale
        val swBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: return null
        
        val maxDim = 512
        val scale = Math.min(maxDim.toFloat() / swBitmap.width, maxDim.toFloat() / swBitmap.height)
        val matrix = android.graphics.Matrix()
        matrix.postScale(scale, scale)
        
        val scaled = android.graphics.Bitmap.createBitmap(swBitmap, 0, 0, swBitmap.width, swBitmap.height, matrix, true)
        
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream)
        val bytes = stream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}
