package com.example.autollm

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

class AgentController(
    private val autoService: AutoService,
    private val onLog: (String) -> Unit
) {
    private val llmClient = LLMClient()
    private val history = mutableListOf<String>()
    private val maxHistorySize = 5
    
    private var userGoal: String = "探索界面，找到可以交互的元素"

    fun setGoal(goal: String) {
        userGoal = goal
        history.clear()
        log("目标已设置: $goal")
    }

    fun runOnce(): Boolean {
        try {
            // 1. Dump UI
            val uiJson = autoService.dumpUI()
            log("获取界面: ${countNodes(uiJson)} 个节点")

            // 2. Build prompt
            val prompt = buildPrompt(uiJson)
            
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
            log("执行操作: $actionType")
            
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
                "done" -> {
                    val reason = action.optString("reason", "任务完成")
                    log("任务完成: $reason")
                    return false // Stop loop
                }
                else -> {
                    log("未知操作: $actionType")
                }
            }
            
            return true // Continue loop
            
        } catch (e: Exception) {
            log("错误: ${e.message}")
            Log.e("AgentController", "Error in runOnce", e)
            return true
        }
    }

    private fun getSystemPrompt(): String {
        return """你是一个 Android 手机自动化助手。根据当前界面和历史操作，决定下一步操作。

【可用操作】
- {"action":"click","x":数字,"y":数字} - 点击坐标
- {"action":"back"} - 返回键
- {"action":"home"} - Home键
- {"action":"wait","seconds":数字} - 等待
- {"action":"done","reason":"原因"} - 任务完成

【规则】
1. 只输出一个 JSON 对象，不要有其他文字
2. 点击时，x 和 y 应该是元素边界框的中心点
3. 如果界面没有变化，尝试其他操作或等待
4. 如果任务已完成或无法继续，使用 done"""
    }

    private fun buildPrompt(uiJson: String): String {
        val historyText = if (history.isEmpty()) {
            "无"
        } else {
            history.mapIndexed { i, h -> "${i + 1}. $h" }.joinToString("\n")
        }
        
        return """【当前目标】
$userGoal

【最近操作】
$historyText

【当前界面元素】
$uiJson

请分析界面，输出下一步操作的 JSON。"""
    }

    private fun parseAction(response: String): JSONObject? {
        return try {
            // Try to extract JSON from response
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
