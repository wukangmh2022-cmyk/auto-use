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
    private val maxHistorySize = 5
    
    var currentPlan: TaskPlanner.TaskPlan? = null
        private set
    
    var onPlanUpdated: ((TaskPlanner.TaskPlan?) -> Unit)? = null

    private var turnsOnCurrentStep = 0
    private var lastStepIndex = -1

    /**
     * 直接执行已有计划
     */
    fun executePlan(plan: TaskPlanner.TaskPlan) {
        currentPlan = plan
        history.clear()
        onPlanUpdated?.invoke(plan)
        log("加载任务: ${plan.name}")
    }

    /**
     * 阶段一：生成计划
     */
    fun generatePlan(userRequest: String): Boolean {
        history.clear()
     * 阶段二：执行一步（含页面校验和变化检测）
     */
    fun executeStep(): Boolean {
        val plan = currentPlan ?: return false
        if (plan.isCompleted()) return false

        try {
            // 1. Dump UI (含变化检测)
            val uiJson = autoService.dumpUI()
            
            if (uiJson == "SAME") {
                log("界面未变化，跳过本次请求...")
                Thread.sleep(2000) // 界面没变时多等一会儿
                return true
            }

            // 监测进度以启用“启发式深度思考”
            if (plan.currentStepIndex == lastStepIndex) {
                turnsOnCurrentStep++
            } else {
                turnsOnCurrentStep = 0
                lastStepIndex = plan.currentStepIndex
            }

            // 启发式逻辑：如果同一步骤执行了 2 次以上，或者界面节点很多，则开启深度思考
            val nodeCount = countNodes(uiJson)
            val reasoningLevel = when {
                turnsOnCurrentStep >= 2 -> 2 // 严重卡顿：必须深度思考
                nodeCount > 30 -> 1         // 界面复杂：建议多想想
                else -> 0                   // 正常
            }

            if (reasoningLevel > 0) {
                log("启发式模式: 等级 $reasoningLevel (卡顿:$turnsOnCurrentStep, 节点:$nodeCount)")
            }

            log("[${plan.progress()}] 界面: ${compressUiLog(uiJson)}")

            // 2. 页面校验
            val currentStep = plan.currentStep()
            if (currentStep != null && currentStep.expectedKeywords.isNotEmpty()) {
                if (!validatePage(uiJson, currentStep.expectedKeywords)) {
                    log("⚠️ 页面不匹配: ${currentStep.expectedKeywords}")
                }
            }

            // 3. Build prompt
            val prompt = buildPrompt(uiJson, plan)
            
            // 4. Call LLM
            val response = llmClient.chat(listOf(
                mapOf("role" to "system", "content" to getSystemPrompt(reasoningLevel)),
                mapOf("role" to "user", "content" to prompt)
            ))

            // 5. Parse and Execute
            val action = parseAction(response) ?: return true
            
            // 记录思维内容
            val thought = action.optString("th", "")
            if (thought.isNotEmpty()) {
                log("🤔 $thought")
            }

            val actionType = action.optString("action", "")
            val stepCompleted = action.optBoolean("step_completed", false)
            
            log("执行: $actionType" + if (stepCompleted) " (步完)" else "")
            
            when (actionType) {
                "click" -> {
                    val coords = action.optString("b", "0,0").split(",")
                    if (coords.size == 2) {
                        autoService.performClick(coords[0].toFloat(), coords[1].toFloat())
                        addHistory("点击了 ${action.optString("b")}")
                    }
                }
                "back" -> autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK).also { addHistory("返回") }
                "home" -> autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME).also { addHistory("主页") }
                "wait" -> {
                    val sec = action.optInt("s", 2)
                    Thread.sleep(sec * 1000L)
                    addHistory("等待 ${sec}s")
                }
                "scroll_down" -> autoService.performSwipe(540f, 1500f, 540f, 500f).also { addHistory("下滑") }
                "scroll_up" -> autoService.performSwipe(540f, 500f, 540f, 1500f).also { addHistory("上滑") }
                "done" -> {
                    log("任务完成: ${action.optString("r", "完成")}")
                    currentPlan = null
                    onPlanUpdated?.invoke(null)
                    return false
                }
            }
            
            if (stepCompleted) {
                plan.currentStepIndex++
                history.clear()
                onPlanUpdated?.invoke(plan)
                log("下一步: ${plan.currentStep()?.description ?: "结束"}")
                return !plan.isCompleted()
            }
            
            return true
            
        } catch (e: Exception) {
            log("错误: ${e.message}")
            return true
        }
    }

    /**
     * 页面校验：检查界面是否包含预期关键词
     */
    private fun validatePage(uiJson: String, keywords: List<String>): Boolean {
        val uiText = uiJson.lowercase()
        return keywords.any { keyword ->
            uiText.contains(keyword.lowercase())
        }
    }

    private fun getSystemPrompt(level: Int): String {
        val thinkingGuide = when(level) {
            2 -> "⚠️检测到你已连续尝试多次未果。必须在 th 中深度分析当前界面障碍，排除路径错误，找出真正可点击的元素，不准重复错误动作。"
            1 -> "界面较复杂。请在 th 中条理化分析目标元素位置后再行动。"
            else -> "th简述推理(建议10字内)。"
        }
        
        return """Android助手。协议:
- t:文本, d:描述, i:ID, c:类名, b:中心点(x,y), k:1(可点)
操作(JSON):
- {"th":"思维","action":"click","b":"x,y","step_completed":布尔}
- {"th":"思维","action":"back/wait/home/done/scroll_down/up"...}
规则: 1.只回JSON 2.$thinkingGuide 3.优先点带t/d的元素 4.步完设step_completed:true"""
    }

    private fun buildPrompt(uiJson: String, plan: TaskPlanner.TaskPlan): String {
        val currentStep = plan.currentStep()
        val historyText = if (history.isEmpty()) "" else "\n近况:${history.joinToString()}"
        
        return """任务:${plan.task}
进度:${plan.progress()} 目标:${currentStep?.description}
界面:$uiJson$historyText"""
    }

    private fun parseAction(response: String): JSONObject? {
        return try {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONObject(response.substring(jsonStart, jsonEnd))
            } else null
        } catch (e: Exception) {
            Log.e("AgentController", "Failed to parse action", e)
            null
        }
    }

    private fun addHistory(action: String) {
        history.add(action)
        if (history.size > maxHistorySize) {
            history.removeAt(0)
        }
    }

    private fun countNodes(json: String): Int {
        return try { JSONArray(json).length() } catch (e: Exception) { 0 }
    }

    private fun compressUiLog(json: String): String {
        try {
            val ja = JSONArray(json)
            val sb = StringBuilder()
            sb.append("(${ja.length()}个) ")
            for (i in 0 until ja.length()) {
                val obj = ja.getJSONObject(i)
                val txt = obj.optString("t")
                val desc = obj.optString("d")
                val label = if (txt.isNotEmpty()) txt else desc
                if (label.isNotEmpty()) {
                    sb.append("[$label] ")
                }
            }
            return if (sb.length > 200) sb.substring(0, 200) + "..." else sb.toString()
        } catch (e: Exception) {
            return "解析错误"
        }
    }

    private fun log(msg: String) {
        Log.d("AgentController", msg)
        onLog(msg)
    }
}
