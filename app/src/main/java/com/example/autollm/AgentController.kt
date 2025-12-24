package com.example.autollm

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

class AgentController(
    private val autoService: AutoService,
    private val onLog: (String) -> Unit
) {
    private val llmClient = LLMClient()
    private val taskPlanner = TaskPlanner(llmClient)
    private val history = mutableListOf<String>()
    
    private var visionEnabled = false
    private var totalTokens = 0
    var onTokenUsage: ((Int) -> Unit)? = null
    
    init {
        llmClient.onTokenUsage = { usage ->
            totalTokens += usage
            onTokenUsage?.invoke(totalTokens)
        }
    }
    
    fun setVisionMode(enabled: Boolean) {
        visionEnabled = enabled
    }

    private val maxHistorySize = 5
    
    // 状态监测变量
    private val stateActionHistory = mutableListOf<String>()
    private var lastUiHashForStuck: Int = 0
    private var reasoningLevel: Int = 0

    var currentPlan: TaskPlanner.TaskPlan? = null
        private set
    
    var onPlanUpdated: ((TaskPlanner.TaskPlan?) -> Unit)? = null

    /**
     * 直接执行已有计划
     */
    fun executePlan(plan: TaskPlanner.TaskPlan) {
        currentPlan = plan
        history.clear()
        stateActionHistory.clear()
        lastUiHashForStuck = 0
        reasoningLevel = 0
        onPlanUpdated?.invoke(plan)
        log("加载任务: ${plan.name}")
    }

    /**
     * 阶段一：生成计划
     */
    fun generatePlan(userRequest: String): Boolean {
        history.clear()
        stateActionHistory.clear()
        lastUiHashForStuck = 0
        reasoningLevel = 0
        
        // TaskPlanner.generatePlan 需要两个参数
        val plan = taskPlanner.generatePlan(userRequest) { msg ->
            // 转发生成进度的原始文本到日志（或专用显示）
            if (msg.length < 100) onLog(msg) 
        }
        
        currentPlan = plan
        onPlanUpdated?.invoke(plan)
        return plan != null
    }

    /**
     * 阶段二：执行一步（含页面校验和启发式推理控制）
     */
    fun executeStep(): Boolean {
        val plan = currentPlan ?: return false
        if (plan.isCompleted()) return false

        try {
            // 1. 获取 UI 情况 (含 UI 变化检测)
            val uiJson = autoService.dumpUI()
            
            if (uiJson == "SAME") {
                log("界面未变化，休眠中...")
                Thread.sleep(2000)
                return true
            }

            val currentUiHash = uiJson.hashCode()
            val nodeCount = countNodes(uiJson)
            
            // 启发式：重置或保持卡顿监测
            if (currentUiHash != lastUiHashForStuck) {
                stateActionHistory.clear()
                lastUiHashForStuck = currentUiHash
                // 界面变了，初步降低推理等级（除非由于节点多仍需等级1）
                reasoningLevel = if (nodeCount > 35) 1 else 0
            }

            log("[${plan.progress()}] 界面: ${compressUiLog(uiJson)}")

            // 2. 页面校验
            val currentStep = plan.currentStep()
            if (currentStep != null && currentStep.expectedKeywords.isNotEmpty()) {
                if (!validatePage(uiJson, currentStep.expectedKeywords)) {
                    log("⚠️ 页面不匹配: ${currentStep.expectedKeywords}")
                }
            }
            
            // 视情况截图
            var screenshot: String? = null
            if (visionEnabled) {
                screenshot = autoService.captureScreenshotBase64()
            }

            // 3. 构建 Prompt 并调用获取操作
            val promptText = buildPrompt(uiJson, plan)
            
            val userContent: Any = if (screenshot != null) {
                listOf(
                    mapOf("type" to "text", "text" to promptText),
                    mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$screenshot"))
                )
            } else {
                promptText
            }

            val response = llmClient.chat(listOf(
                mapOf("role" to "system", "content" to getSystemPrompt(reasoningLevel, screenshot != null)),
                mapOf("role" to "user", "content" to userContent)
            ))

            // 4. 解析响应
            val action = parseAction(response) ?: return true
            
            // 5. 动作重复性检查（识别原地拨号）
            val actionKey = action.optString("action", "") + ":" + action.optString("b", "")
            if (stateActionHistory.contains(actionKey)) {
                reasoningLevel = 2 // 确定重复了，下一轮强制解析障碍
                log("❗ 检测到重复动作，启用深度分析模式")
            }
            stateActionHistory.add(actionKey)

            // 显示思维内容
            val thought = action.optString("th", "")
            if (thought.isNotEmpty()) {
                log("🤔 $thought")
            }

            // 6. 执行物理操作
            val actionType = action.optString("action", "")
            val stepCompleted = action.optBoolean("step_completed", false)
            
            log("动作: $actionType" + if (stepCompleted) " (步完)" else "")
            
            when (actionType) {
                "click" -> {
                    val coords = action.optString("b", "0,0").split(",")
                    if (coords.size == 2) {
                        autoService.performClick(coords[0].toFloat(), coords[1].toFloat())
                        addHistory("点击 $coords")
                    }
                }
                "back" -> autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK).also { addHistory("返回") }
                "home" -> autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME).also { addHistory("主页") }
                "wait" -> {
                    val sec = action.optInt("s", 2)
                    Thread.sleep(sec * 1000L)
                    addHistory("等待 ${sec}s")
                }
                "scroll_down" -> autoService.performSwipe(540f, 500f, 540f, 1500f).also { addHistory("下滑") }
                "scroll_up" -> autoService.performSwipe(540f, 1500f, 540f, 500f).also { addHistory("上滑") }
                "done" -> {
                    log("任务完成: ${action.optString("r", "完成")}")
                    currentPlan = null
                    onPlanUpdated?.invoke(null)
                    return false
                }
            }
            
            // 7. 更新计划进度
            if (stepCompleted) {
                plan.currentStepIndex++
                history.clear()
                stateActionHistory.clear() // 步骤推进，清空重复检测
                onPlanUpdated?.invoke(plan)
                
                if (plan.isCompleted()) {
                    log("🎉 任务全部完成")
                    return false
                } else {
                    log("进入下一步: ${plan.currentStep()?.description}")
                }
            }
            
            return true
            
        } catch (e: Exception) {
            log("执行异常: ${e.message}")
            return true
        }
    }

    private fun validatePage(uiJson: String, keywords: List<String>): Boolean {
        val uiText = uiJson.lowercase()
        return keywords.any { keyword -> uiText.contains(keyword.lowercase()) }
    }

    private fun getSystemPrompt(level: Int, hasVision: Boolean = false): String {
        val thinkingGuide = when(level) {
            2 -> "⚠️原地打转中！必须在 th 中深度分析界面障碍，找出正确元素，严禁重复上一步错误动作。"
            1 -> "界面复杂，请在 th 中条理化分析目标元素后再操作。"
            else -> "th简述推理(10字内)。"
        }
        val visionGuide = if (hasVision) "5.参考截图补充界面细节。" else ""
        
        return """Android助手。协议:
- t:文本, d:描述, i:ID, c:类名, b:中心点(x,y), k:1(点)
操作(JSON):
- {"th":"想","action":"click","b":"x,y","step_completed":布尔}
- {"th":"想","action":"back/wait/home/done/scroll_down/up"...}
规则: 1.只回JSON 2.$thinkingGuide 3.优先点带t/d元素 4.步完设step_completed:true $visionGuide"""
    }

    private fun buildPrompt(uiJson: String, plan: TaskPlanner.TaskPlan): String {
        val currentStep = plan.currentStep()
        val hist = if (history.isEmpty()) "" else "\n近况:${history.joinToString()}"
        
        return """任务:${plan.task}
进度:${plan.progress()} 目标:${currentStep?.description}
界面:$uiJson$hist"""
    }

    private fun parseAction(response: String): JSONObject? {
        return try {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONObject(response.substring(jsonStart, jsonEnd))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun addHistory(action: String) {
        history.add(action)
        if (history.size > maxHistorySize) history.removeAt(0)
    }

    private fun countNodes(json: String): Int {
        return try { JSONArray(json).length() } catch (e: Exception) { 0 }
    }

    private fun compressUiLog(json: String): String {
        return try {
            val ja = JSONArray(json)
            val sb = StringBuilder("(${ja.length()}个) ")
            for (i in 0 until ja.length()) {
                val obj = ja.getJSONObject(i)
                val txt = obj.optString("t")
                val desc = obj.optString("d")
                val label = if (txt.isNotEmpty()) txt else desc
                if (label.isNotEmpty()) sb.append("[$label] ")
            }
            if (sb.length > 200) sb.substring(0, 200) + "..." else sb.toString()
        } catch (e: Exception) {
            "解析错误"
        }
    }

    private fun log(msg: String) {
        Log.d("AgentController", msg)
        onLog(msg)
    }
}
