package com.example.autollm

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * TaskPlanner - 根据用户需求生成任务执行计划（含页面校验关键词）
 */
class TaskPlanner(private val llmClient: LLMClient) {

    data class TaskStep(
        val description: String,
        val expectedKeywords: List<String> // 校验当前页面是否正确
    )

    data class TaskPlan(
        val id: String,
        val name: String,
        val task: String,
        val steps: List<TaskStep>,
        var currentStepIndex: Int = 0,
        var scheduledTime: String? = null
    ) {
        fun currentStep(): TaskStep? = steps.getOrNull(currentStepIndex)
        fun isCompleted(): Boolean = currentStepIndex >= steps.size
        fun progress(): String = "${currentStepIndex + 1}/${steps.size}"
        
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("name", name)
                put("task", task)
                put("currentStepIndex", currentStepIndex)
                put("scheduledTime", scheduledTime ?: "")
                
                val stepsArray = JSONArray()
                for (step in steps) {
                    val stepObj = JSONObject()
                    stepObj.put("description", step.description)
                    stepObj.put("expectedKeywords", JSONArray(step.expectedKeywords))
                    stepsArray.put(stepObj)
                }
                put("steps", stepsArray)
            }
        }
        
        companion object {
            fun fromJson(json: JSONObject): TaskPlan {
                val steps = mutableListOf<TaskStep>()
                val stepsArray = json.getJSONArray("steps")
                for (i in 0 until stepsArray.length()) {
                    val stepObj = stepsArray.getJSONObject(i)
                    val keywords = mutableListOf<String>()
                    val keywordsArray = stepObj.optJSONArray("expectedKeywords")
                    if (keywordsArray != null) {
                        for (j in 0 until keywordsArray.length()) {
                            keywords.add(keywordsArray.getString(j))
                        }
                    }
                    steps.add(TaskStep(
                        description = stepObj.getString("description"),
                        expectedKeywords = keywords
                    ))
                }
                
                val scheduledTime = json.optString("scheduledTime", "")
                
                return TaskPlan(
                    id = json.optString("id", System.currentTimeMillis().toString()),
                    name = json.optString("name", "未命名任务"),
                    task = json.getString("task"),
                    steps = steps,
                    currentStepIndex = json.optInt("currentStepIndex", 0),
                    scheduledTime = scheduledTime.ifEmpty { null }
                )
            }
        }
    }

    // onProgress: 用来实时显示生成内容的原始内容
    fun generatePlan(userRequest: String, onProgress: (String) -> Unit): TaskPlan? {
        onProgress("正在请求 LLM 生成计划...\n")
        
        val systemPrompt = """你是一个 Android 手机自动化任务规划师。
根据用户的需求，生成一个清晰的执行步骤列表，每个步骤包含描述和预期页面关键词。

【规则】
1. 步骤要具体、可执行
2. 每个步骤应该对应一个明确的界面操作
3. expectedKeywords 用于校验是否在正确页面（填写该页面应该出现的文字）
4. 步骤数量控制在 3-10 步
5. 只输出 JSON，不要有其他文字

【输出格式】
{
  "name": "任务简称",
  "task": "任务完整描述",
  "steps": [
    {
      "description": "步骤描述",
      "expectedKeywords": ["关键词1", "关键词2"]
    }
  ]
}"""

        val userPrompt = "用户需求：$userRequest"

        return try {
            val accumulatedText = StringBuilder()
            
            // 使用流式调用
            val response = llmClient.streamChat(listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            )) { token ->
                accumulatedText.append(token)
                onProgress(accumulatedText.toString())
            }
            
            onProgress(response) // 确保最后显示完整的
            
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd)
                val json = JSONObject(jsonStr)
                
                json.put("id", System.currentTimeMillis().toString())
                
                val plan = TaskPlan.fromJson(json)
                plan
            } else {
                onProgress("\n无法解析 JSON")
                null
            }
        } catch (e: Exception) {
            onProgress("\n生成失败: ${e.message}")
            Log.e("TaskPlanner", "Failed to generate plan", e)
            null
        }
    }
}
