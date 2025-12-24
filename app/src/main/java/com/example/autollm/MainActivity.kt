package com.example.autollm

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.*
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
    private lateinit var btnSave: Button
    private lateinit var etRequest: EditText
    private lateinit var llSavedTasks: LinearLayout
    
    private lateinit var taskRepository: TaskRepository
    private lateinit var taskScheduler: TaskScheduler
    
    private val handler = Handler(Looper.getMainLooper())
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        taskRepository = TaskRepository(this)
        taskScheduler = TaskScheduler(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvPlan = findViewById(R.id.tvPlan)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSave = findViewById(R.id.btnSave)
        etRequest = findViewById(R.id.etRequest)
        llSavedTasks = findViewById(R.id.llSavedTasks)
        
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
            
            val request = etRequest.text.toString()
            if (request.isEmpty()) {
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
        
        btnSave.setOnClickListener {
            val plan = AutoService.instance?.getCurrentPlan()
            if (plan == null) {
                Toast.makeText(this, "没有可保存的任务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 弹出定时设置对话框
            showScheduleDialog(plan)
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
            handler.post { 
                updatePlanDisplay(plan) 
                btnSave.isEnabled = plan != null
            }
        }
        
        // Periodic status update
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateStatus()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
        
        // Load saved tasks
        refreshSavedTasks()
    }
    
    private fun showScheduleDialog(plan: TaskPlanner.TaskPlan) {
        val options = arrayOf("只保存（手动启动）", "设置定时启动")
        
        AlertDialog.Builder(this)
            .setTitle("保存任务: ${plan.name}")
            .setItems(options) { _, which ->
                if (which == 0) {
                    // 只保存
                    plan.scheduledTime = null
                    taskRepository.savePlan(plan)
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    refreshSavedTasks()
                } else {
                    // 设置定时
                    TimePickerDialog(this, { _, hour, minute ->
                        plan.scheduledTime = String.format("%02d:%02d", hour, minute)
                        taskRepository.savePlan(plan)
                        taskScheduler.scheduleTask(plan)
                        Toast.makeText(this, "已保存，将在 ${plan.scheduledTime} 自动执行", Toast.LENGTH_SHORT).show()
                        refreshSavedTasks()
                    }, 8, 0, true).show()
                }
            }
            .show()
    }
    
    private fun refreshSavedTasks() {
        llSavedTasks.removeAllViews()
        
        val tasks = taskRepository.loadAllPlans()
        if (tasks.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "无已保存任务"
                setTextColor(0xFF999999.toInt())
                setPadding(16, 8, 16, 8)
            }
            llSavedTasks.addView(emptyText)
            return
        }
        
        for (task in tasks) {
            val btn = Button(this).apply {
                text = task.name + (task.scheduledTime?.let { "\n⏰$it" } ?: "")
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(16, 8, 16, 8)
                
                setOnClickListener {
                    // 执行任务
                    val service = AutoService.instance
                    if (service == null) {
                        Toast.makeText(this@MainActivity, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    // 重置进度
                    task.currentStepIndex = 0
                    logBuilder.clear()
                    tvLog.text = ""
                    
                    service.startWithPlan(task)
                    updateUI(true)
                }
                
                setOnLongClickListener {
                    // 长按删除
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除任务")
                        .setMessage("确定删除 '${task.name}'?")
                        .setPositiveButton("删除") { _, _ ->
                            taskRepository.deletePlan(task.id)
                            taskScheduler.cancelTask(task.id)
                            refreshSavedTasks()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            llSavedTasks.addView(btn)
        }
    }

    private fun updatePlanDisplay(plan: TaskPlanner.TaskPlan?) {
        if (plan == null) {
            tvPlan.text = "等待生成计划..."
            return
        }
        
        val sb = StringBuilder()
        sb.append("【${plan.name}】\n")
        
        plan.steps.forEachIndexed { index, step ->
            val marker = when {
                index < plan.currentStepIndex -> "✓"
                index == plan.currentStepIndex -> "→"
                else -> "○"
            }
            sb.append("$marker ${index + 1}. ${step.description}\n")
        }
        
        tvPlan.text = sb.toString()
    }

    private fun updateStatus() {
        val service = AutoService.instance
        val statusText = when {
            service == null -> "无障碍未开启"
            AutoService.isRunning -> "执行中..."
            else -> "已就绪"
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

    override fun onResume() {
        super.onResume()
        refreshSavedTasks()
    }

    override fun onDestroy() {
        super.onDestroy()
        AutoService.onLogCallback = null
        AutoService.onPlanCallback = null
    }
}
