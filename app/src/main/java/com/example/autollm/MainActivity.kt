package com.example.autollm

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPlan: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var etRequest: EditText
    
    private val handler = Handler(Looper.getMainLooper())
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvPlan = findViewById(R.id.tvPlan)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        etRequest = findViewById(R.id.etRequest)
        
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener {
            val service = AutoService.instance
            if (service == null) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val request = etRequest.text.toString().ifEmpty { 
                Toast.makeText(this, "请输入任务需求", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            tvPlan.text = "正在生成计划..."
            logBuilder.clear()
            tvLog.text = ""
            
            service.startAgent(request)
            updateUI(true)
        }

        btnStop.setOnClickListener {
            AutoService.instance?.stopAgent()
            updateUI(false)
        }

        btnClearLog.setOnClickListener {
            logBuilder.clear()
            tvLog.text = ""
        }

        // Setup callbacks
        AutoService.onLogCallback = { msg ->
            handler.post { appendLog(msg) }
        }
        
        AutoService.onPlanCallback = { plan ->
            handler.post { updatePlanDisplay(plan) }
        }
        
        // Periodic status update
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateStatus()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun updatePlanDisplay(plan: TaskPlanner.TaskPlan?) {
        if (plan == null) {
            tvPlan.text = "等待生成计划..."
            return
        }
        
        val sb = StringBuilder()
        sb.append("任务: ${plan.task}\n\n")
        
        plan.steps.forEachIndexed { index, step ->
            val marker = when {
                index < plan.currentStepIndex -> "✓"
                index == plan.currentStepIndex -> "→"
                else -> "○"
            }
            sb.append("$marker ${index + 1}. $step\n")
        }
        
        tvPlan.text = sb.toString()
    }

    private fun updateStatus() {
        val service = AutoService.instance
        val statusText = when {
            service == null -> "状态: 无障碍服务未开启"
            AutoService.isRunning -> "状态: 执行中..."
            else -> "状态: 已就绪"
        }
        tvStatus.text = statusText
        
        btnStart.isEnabled = service != null && !AutoService.isRunning
        btnStop.isEnabled = AutoService.isRunning
    }

    private fun updateUI(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun appendLog(msg: String) {
        val timestamp = dateFormat.format(Date())
        logBuilder.append("[$timestamp] $msg\n")
        tvLog.text = logBuilder.toString()
        scrollLog.post {
            scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AutoService.onLogCallback = null
        AutoService.onPlanCallback = null
    }
}
