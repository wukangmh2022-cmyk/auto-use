package com.example.autollm

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * TaskPlanner - 根据用户需求生成任务执行计划
 */
class TaskPlanner(private val llmClient: LLMClient) {

    data class TaskPlan(
        val task: String,
        val steps: List<String>,
        var currentStepIndex: Int = 0
    ) {
        fun currentStep(): String? = steps.getOrNull(currentStepIndex)
        fun isCompleted(): Boolean = currentStepIndex >= steps.size
        fun progress(): String = "${currentStepIndex + 1}/${steps.size}"
        
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("task", task)
                put("steps", JSONArray(steps))
                put("currentStepIndex", currentStepIndex)
            }
        }
        
        companion object {
            fun fromJson(json: JSONObject): TaskPlan {
                val steps = mutableListOf<String>()
                val stepsArray = json.getJSONArray("steps")
                for (i in 0 until stepsArray.length()) {
                    steps.add(stepsArray.getString(i))
                }
                return TaskPlan(
                    task = json.getString("task"),
                    steps = steps,
                    currentStepIndex = json.optInt("currentStepIndex", 0)
                )
            }
        }
    }

    fun generatePlan(userRequest: String, onLog: (String) -> Unit): TaskPlan? {
        onLog("正在生成任务计划...")
        
        val systemPrompt = """你是一个 Android 手机自动化任务规划师。
根据用户的需求，生成一个清晰的执行步骤列表。

【规则】
1. 步骤要具体、可执行
2. 每个步骤应该对应一个明确的界面操作
3. 步骤数量控制在 3-10 步
4. 只输出 JSON，不要有其他文字

【输出格式】
{
  "task": "任务名称",
  "steps": [
    "步骤1描述",
    "步骤2描述",
    ...
  ]
}"""

        val userPrompt = "用户需求：$userRequest"

        return try {
            val response = llmClient.chat(listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ))
            
            onLog("LLM 响应: ${response.take(200)}...")
            
            // Parse JSON
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd)
                val json = JSONObject(jsonStr)
                val plan = TaskPlan.fromJson(json)
                onLog("计划生成成功: ${plan.steps.size} 个步骤")
                plan
            } else {
                onLog("无法解析计划 JSON")
                null
            }
        } catch (e: Exception) {
            onLog("生成计划失败: ${e.message}")
            Log.e("TaskPlanner", "Failed to generate plan", e)
            null
        }
    }
}
