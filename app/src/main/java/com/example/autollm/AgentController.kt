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

    /**
     * 阶段一：生成计划
     */
    fun generatePlan(userRequest: String): Boolean {
        history.clear()
        currentPlan = taskPlanner.generatePlan(userRequest, onLog)
        onPlanUpdated?.invoke(currentPlan)
        return currentPlan != null
    }

    /**
     * 阶段二：执行一步
     */
    fun executeStep(): Boolean {
        val plan = currentPlan ?: run {
            log("没有可执行的计划")
            return false
        }
        
        if (plan.isCompleted()) {
            log("所有步骤已完成！")
            return false
        }

        try {
            // 1. Dump UI
            val uiJson = autoService.dumpUI()
            log("[${plan.progress()}] 获取界面: ${countNodes(uiJson)} 个节点")

            // 2. Build prompt with plan context
            val prompt = buildPrompt(uiJson, plan)
            
            // 3. Call LLM
            log("请求 LLM...")
            val response = llmClient.chat(listOf(
                mapOf("role" to "system", "content" to getSystemPrompt()),
                mapOf("role" to "user", "content" to prompt)
            ))
            log("LLM 响应: ${response.take(100)}...")

            // 4. Parse action
            val action = parseAction(response)
            if (action == null) {
                log("无法解析操作")
                return true
            }

            // 5. Execute action
            val actionType = action.optString("action", "")
            val stepCompleted = action.optBoolean("step_completed", false)
            
            log("执行操作: $actionType" + if (stepCompleted) " (步骤完成)" else "")
            
            when (actionType) {
                "click" -> {
                    val x = action.optInt("x", 0)
                    val y = action.optInt("y", 0)
                    autoService.performClick(x.toFloat(), y.toFloat())
                    addHistory("点击了 ($x, $y)")
                }
                "back" -> {
                    autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    addHistory("按了返回键")
                }
                "home" -> {
                    autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    addHistory("按了Home键")
                }
                "wait" -> {
                    val seconds = action.optInt("seconds", 2)
                    log("等待 ${seconds} 秒")
                    Thread.sleep(seconds * 1000L)
                    addHistory("等待了 ${seconds} 秒")
                }
                "scroll_down" -> {
                    // Simple scroll gesture
                    val screenHeight = 2000 // Approximate
                    autoService.performSwipe(540f, 1500f, 540f, 500f)
                    addHistory("向下滑动")
                }
                "scroll_up" -> {
                    autoService.performSwipe(540f, 500f, 540f, 1500f)
                    addHistory("向上滑动")
                }
                "done" -> {
                    val reason = action.optString("reason", "任务完成")
                    log("任务完成: $reason")
                    currentPlan = null
                    onPlanUpdated?.invoke(null)
                    return false
                }
            }
            
            // 6. Advance to next step if completed
            if (stepCompleted) {
                plan.currentStepIndex++
                history.clear() // Clear history for new step
                onPlanUpdated?.invoke(plan)
                
                if (plan.isCompleted()) {
                    log("🎉 所有步骤已完成！")
                    return false
                } else {
                    log("进入下一步: ${plan.currentStep()}")
                }
            }
            
            return true // Continue loop
            
        } catch (e: Exception) {
            log("错误: ${e.message}")
            Log.e("AgentController", "Error in executeStep", e)
            return true
        }
    }

    private fun getSystemPrompt(): String {
        return """你是一个 Android 手机自动化执行助手。根据任务计划和当前界面，决定下一步操作。

【可用操作】
- {"action":"click","x":数字,"y":数字,"step_completed":布尔} - 点击坐标
- {"action":"back","step_completed":布尔} - 返回键
- {"action":"home","step_completed":布尔} - Home键
- {"action":"wait","seconds":数字,"step_completed":布尔} - 等待
- {"action":"scroll_down","step_completed":布尔} - 向下滑动
- {"action":"scroll_up","step_completed":布尔} - 向上滑动
- {"action":"done","reason":"原因"} - 整个任务完成

【规则】
1. 只输出一个 JSON 对象，不要有其他文字
2. 点击时，x 和 y 应该是元素边界框的中心点（左+右）/2 和（上+下）/2
3. 当前步骤完成后，设置 "step_completed": true
4. 如果界面不是预期的，尝试导航到正确界面
5. 如果整个任务已完成，使用 done"""
    }

    private fun buildPrompt(uiJson: String, plan: TaskPlanner.TaskPlan): String {
        val stepsText = plan.steps.mapIndexed { i, step ->
            val marker = when {
                i < plan.currentStepIndex -> "✓"
                i == plan.currentStepIndex -> "→"
                else -> " "
            }
            "$marker ${i + 1}. $step"
        }.joinToString("\n")
        
        val historyText = if (history.isEmpty()) {
            "无"
        } else {
            history.mapIndexed { i, h -> "${i + 1}. $h" }.joinToString("\n")
        }
        
        return """【任务】${plan.task}

【执行计划】
$stepsText

【当前步骤】${plan.currentStep()}

【本步骤已执行的操作】
$historyText

【当前界面元素】
$uiJson

请根据当前步骤和界面，输出下一步操作的 JSON。如果当前步骤已完成，设置 step_completed: true。"""
    }

    private fun parseAction(response: String): JSONObject? {
        return try {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd)
                JSONObject(jsonStr)
            } else {
                null
            }
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
        return try {
            JSONArray(json).length()
        } catch (e: Exception) {
            0
        }
    }

    private fun log(msg: String) {
        Log.d("AgentController", msg)
        onLog(msg)
    }
}
