package com.example.autollm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val btnSaveConfig = findViewById<Button>(R.id.btnSaveConfig)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        val etScript = findViewById<EditText>(R.id.etScript)
        val btnUpdateScript = findViewById<Button>(R.id.btnUpdateScript)

        val prefs = getSharedPreferences("AutoLLM", Context.MODE_PRIVATE)

        // Load saved state
        etApiKey.setText(prefs.getString("api_key", ""))
        etScript.setText(prefs.getString("script", getDefaultScript()))

        btnSaveConfig.setOnClickListener {
            val apiKey = etApiKey.text.toString()
            prefs.edit().putString("api_key", apiKey).apply()
            Toast.makeText(this, "Configuration Saved", Toast.LENGTH_SHORT).show()
        }

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        
        btnUpdateScript.setOnClickListener {
             val script = etScript.text.toString()
             prefs.edit().putString("script", script).apply()
             // Also notify service to reload script if running? 
             // Ideally we send an intent or the service observes shared prefs.
             // For simplicity, the service will read prefs on each loop or when needed.
             Toast.makeText(this, "Script Updated", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Default JS script template
    private fun getDefaultScript(): String {
        return """
            // AutoLLM Default Script
            // Available Globals:
            // device.dumpNodes() -> returns JSON string of UI
            // device.click(x, y)
            // device.home()
            // device.back()
            // http.post(url, headersMap, bodyString) -> returns string response
            
            function run() {
               // 1. Dump UI
               var ui = device.dumpNodes();
               
               // 2. Call LLM (Mocked for now in NetworkClient, but here is how you'd call it)
               // var res = http.post("https://api.openai.com/v1/chat/completions", ...);
               
               // For MVP, let's just log
               print("Current UI Node Count: " + ui.length);
            }
            
            run();
        """.trimIndent()
    }
}
