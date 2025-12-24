package com.example.autollm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class LLMClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun post(url: String, headersMap: Map<String, String>?, bodyString: String): String {
        val requestBuilder = Request.Builder().url(url)
        
        headersMap?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        
        val body = bodyString.toRequestBody(JSON)
        requestBuilder.post(body)

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${'$'}response")
            return response.body?.string() ?: ""
        }
    }
}
