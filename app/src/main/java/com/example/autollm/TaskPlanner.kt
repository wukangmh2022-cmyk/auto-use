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
6. 描述步骤时不要画蛇添足描述图标颜色、形状（如“点击红心”），直接说功能（如“点击点赞”）即可，省token
5. 只输出 JSON，不要有其他文字

【输出格式】
{
  "name": "任务简称",
  "task": "任务完整描述",
  "steps": [
    {
      "description": "简练的操作描述（无视觉形容词）",
      "expectedKeywords": ["关键词1", "关键词2"]
    }
  ]
}"""

        val userPrompt = "用户需求：$userRequest"

        return try {
            callLLM(systemPrompt, userPrompt, onProgress)
        } catch (e: Exception) {
            onProgress("\n生成失败: ${e.message}")
            Log.e("TaskPlanner", "Failed to generate plan", e)
            null
        }
    }

    /**
     * 根据用户反馈修正现有计划
     */
    fun refinePlan(currentPlan: TaskPlan, userFeedback: String, onProgress: (String) -> Unit): TaskPlan? {
        onProgress("正在根据反馈修正计划...\n")

        val systemPrompt = """你是一个 Android 手机自动化任务规划师。
用户对之前的计划提出了修改意见。请根据原计划和用户反馈，生成一个新的修正版计划。

【规则】
1. 保持原计划中合理的部分，仅修改不合理或用户指出的部分
2. 格式与原计划完全一致，只输出 JSON
3. 步骤数量控制在 3-10 步
4. 描述步骤时不要画蛇添足描述图标颜色、形状，直接说功能即可
5. 这很重要：不要修改原计划中正确的步骤描述，除非用户明确要求

【输出格式】
{
  "name": "任务简称",
  "task": "任务完整描述",
  "steps": [
    {
      "description": "简练的操作描述（无视觉形容词）",
      "expectedKeywords": ["关键词"]
    }
  ]
}"""

        val userPrompt = """原计划:
${currentPlan.toJson()}

用户反馈:
$userFeedback

请输出修正后的 JSON 计划。"""

        return try {
            callLLM(systemPrompt, userPrompt, onProgress)
        } catch (e: Exception) {
            onProgress("\n修正失败: ${e.message}")
            Log.e("TaskPlanner", "Failed to refine plan", e)
            null
        }
    }

    private fun callLLM(sys: String, user: String, onProgress: (String) -> Unit): TaskPlan? {
        val accumulatedText = StringBuilder()
        
        val response = llmClient.streamChat(listOf(
            mapOf("role" to "system", "content" to sys),
            mapOf("role" to "user", "content" to user)
        )) { token ->
            accumulatedText.append(token)
            onProgress(accumulatedText.toString())
        }
        
        onProgress(response)
        
        val jsonStart = response.indexOf("{")
        val jsonEnd = response.lastIndexOf("}") + 1
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val jsonStr = response.substring(jsonStart, jsonEnd)
            val json = JSONObject(jsonStr)
            
            // 保持 ID 不变或生成新的? 这里生成新的比较稳妥，或者沿用旧的看需求。
            // 还是生成新的吧，当做一个全新计划。
            json.put("id", System.currentTimeMillis().toString())
            
            return TaskPlan.fromJson(json)
        } else {
            onProgress("\n无法解析 JSON")
            return null
        }
    }
}
