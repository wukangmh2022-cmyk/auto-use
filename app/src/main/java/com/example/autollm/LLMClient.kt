package com.example.autollm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

class LLMClient {
    companion object {
        private const val API_KEY_URL = "http://47.108.203.64/trans/1.txt"
        private const val LLM_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        private const val MODEL = "doubao-seed-1-6-lite-251015"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    fun chat(messages: List<Map<String, String>>): String {
        val apiKey = fetchApiKey()
        
        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg["role"])
            msgObj.put("content", msg["content"])
            messagesArray.put(msgObj)
        }
        
        val requestBody = JSONObject().apply {
            put("model", MODEL)
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
            
            // Parse response
            val json = JSONObject(body)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) throw IOException("No choices in response")
            
            val message = choices.getJSONObject(0).getJSONObject("message")
            return message.getString("content")
        }
    }
}
