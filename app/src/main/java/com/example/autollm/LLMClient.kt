package com.example.autollm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

class LLMClient {
    companion object {
        private const val API_KEY_URL = "http://47.108.203.64/trans/1.txt"
        private const val LLM_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        private const val MODEL_TEXT = "doubao-seed-1-6-lite-251015"
        private const val MODEL_VISION = "doubao-seed-1-6-vision-250815"
    }
    
    // 0: 纯文本, 1: 视觉辅助(用文本模型), 2: VLM端到端(用视觉模型)
    var visionMode: Int = 0
    
    private fun getCurrentModel(): String {
        return if (visionMode == 2) MODEL_VISION else MODEL_TEXT
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Ensure stream is not buffered forever
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    
    private var cachedApiKey: String? = null

    fun fetchApiKey(): String {
        cachedApiKey?.let { return it }
        
        val request = Request.Builder()
            .url(API_KEY_URL)
            .get()
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to fetch API key: $response")
            val key = response.body?.string()?.trim() ?: throw IOException("Empty API key")
            cachedApiKey = key
            return key
        }
    }

    fun chat(messages: List<Map<String, Any>>): String {
        val apiKey = fetchApiKey()
        
        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg["role"])
            val content = msg["content"]
            if (content is List<*>) {
                val contentArray = JSONArray()
                for (item in content) {
                    if (item is Map<*, *>) {
                        contentArray.put(JSONObject(item as Map<*, *>))
                    }
                }
                msgObj.put("content", contentArray)
            } else {
                msgObj.put("content", content.toString())
            }
            messagesArray.put(msgObj)
        }
        
        val requestBody = JSONObject().apply {
            put("model", getCurrentModel())
            put("messages", messagesArray)
        }
        
        val request = Request.Builder()
            .url(LLM_BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA))
            .build()
        
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("LLM request failed: ${response.code} - $body")
            }
            
            val json = JSONObject(body)
            
            // Track usage
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                val total = usage.optInt("total_tokens", 0)
                onTokenUsage?.invoke(total)
            }
            
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) throw IOException("No choices in response")
            
            val message = choices.getJSONObject(0).getJSONObject("message")
            return message.getString("content")
        }
    }
    
    var onTokenUsage: ((Int) -> Unit)? = null
    
    /**
     * 流式调用
     * onToken: 回调收到的新文本片段
     * 返回: 完整的响应文本
     */
    fun streamChat(messages: List<Map<String, String>>, onToken: (String) -> Unit): String {
        val apiKey = fetchApiKey()
        
        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg["role"])
            msgObj.put("content", msg["content"])
            messagesArray.put(msgObj)
        }
        
        val requestBody = JSONObject().apply {
            put("model", getCurrentModel())
            put("messages", messagesArray)
            put("stream", true)
        }

        val request = Request.Builder()
            .url(LLM_BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA))
            .build()
            
        val fullResponse = StringBuilder()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("LLM stream request failed: ${response.code}")
            }
            
            val source = response.body?.source() ?: throw IOException("No response body")
            
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                
                if (line.startsWith("data: ")) {
                    val data = line.substring(6).trim()
                    if (data == "[DONE]") break
                    
                    try {
                        val json = JSONObject(data)
                        val choices = json.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content")
                            
                            if (!content.isNullOrEmpty()) {
                                fullResponse.append(content)
                                onToken(content)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors for interim chunks
                    }
                }
            }
        }
        
        return fullResponse.toString()
    }
}
