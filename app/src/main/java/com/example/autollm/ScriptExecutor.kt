package com.example.autollm

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Function

class ScriptExecutor(private val autoService: AutoService) {
    
    private val llmClient = LLMClient()

    fun execute(script: String) {
        val rh = Context.enter()
        rh.optimizationLevel = -1 // Interpret mode, better for Android
        try {
            val scope: Scriptable = rh.initStandardObjects()

            // Expose objects
            val deviceObj = Context.javaToJS(DeviceWrapper(autoService), scope)
            ScriptableObject.putProperty(scope, "device", deviceObj)
            
            val httpObj = Context.javaToJS(HttpWrapper(llmClient), scope)
            ScriptableObject.putProperty(scope, "http", httpObj)
            
             // Print/Log function
            val logFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    val msg = args?.joinToString(" ") { it.toString() } ?: ""
                    Log.d("AutoJS", msg)
                    return Context.getUndefinedValue()
                }
            }
             ScriptableObject.putProperty(scope, "print", logFunc)

            rh.evaluateString(scope, script, "AutoScript", 1, null)
            
        } catch (e: Exception) {
            Log.e("ScriptExecutor", "Error executing script", e)
        } finally {
            Context.exit()
        }
    }
    
    // Abstract class helper for functions
    open class BaseFunction : org.mozilla.javascript.BaseFunction()

    // Wrapper classes to expose safe API to JS
    class DeviceWrapper(private val service: AutoService) {
        fun dumpNodes(): String = service.dumpUI()
        fun click(x: Int, y: Int) = service.performClick(x.toFloat(), y.toFloat())
        fun home() = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        fun back() = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    class HttpWrapper(private val client: LLMClient) {
        fun post(url: String, headers: Any?, body: String): String {
             // Convert JS headers object to Map
             val headerMap = mutableMapOf<String, String>()
             if (headers is Scriptable) {
                 for (id in headers.ids) {
                     val key = id.toString()
                     val value = headers.get(key, headers).toString()
                     headerMap[key] = value
                 }
             }
             return client.post(url, headerMap, body)
        }
    }
}
