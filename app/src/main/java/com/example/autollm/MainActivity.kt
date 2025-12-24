package com.example.autollm

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPlan: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvTokenStats: TextView 
    private lateinit var spVisionMode: Spinner 
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSave: Button
    private lateinit var btnConfirm: Button
    
    // Modification UI
    private lateinit var llModifyContainer: LinearLayout
    private lateinit var btnModify: TextView 
    private lateinit var llModifyInput: LinearLayout
    private lateinit var etModifyInput: EditText
    private lateinit var btnSendModify: Button
    
    private lateinit var etRequest: EditText
    private lateinit var llSavedTasks: LinearLayout
    
    private lateinit var taskRepository: TaskRepository
    private lateinit var taskScheduler: TaskScheduler
    
    private val handler = Handler(Looper.getMainLooper())
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    // 待确认的计划
    private var pendingPlan: TaskPlanner.TaskPlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        taskRepository = TaskRepository(this)
        taskScheduler = TaskScheduler(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvPlan = findViewById(R.id.tvPlan)
        tvLog = findViewById(R.id.tvLog)
        tvTokenStats = findViewById(R.id.tvTokenStats)
        spVisionMode = findViewById(R.id.spVisionMode)
        scrollLog = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSave = findViewById(R.id.btnSave)
        btnConfirm = findViewById(R.id.btnConfirm)
        
        // Modify Section
        llModifyContainer = findViewById(R.id.llModifyContainer)
        btnModify = findViewById(R.id.btnModify)
        llModifyInput = findViewById(R.id.llModifyInput)
        etModifyInput = findViewById(R.id.etModifyInput)
        btnSendModify = findViewById(R.id.btnSendModify)
        
        etRequest = findViewById(R.id.etRequest)
        llSavedTasks = findViewById(R.id.llSavedTasks)
        
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        
        spVisionMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                AutoService.instance?.setVisionMode(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Callback for token stats
        AutoService.onTokenUpdateCallback = { totalTokens ->
            handler.post {
                val cost = totalTokens * 0.000003 // Est 3 RMB / 1M
                val costStr = String.format("%.4f", cost)
                tvTokenStats.text = "Token: $totalTokens (¥$costStr)"
            }
        }

        // 生成计划（不自动执行）
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
            btnStart.isEnabled = false
            btnConfirm.isEnabled = false
            btnSave.isEnabled = false
            llModifyContainer.visibility = View.GONE
            
            // 只生成计划，不执行
            Thread {
                val llmClient = LLMClient()
                val planner = TaskPlanner(llmClient)
                
                // 实时更新 tvPlan 显示生成过程
                val plan = planner.generatePlan(request) { partialText ->
                    val cleanText = partialText.replace("\\n", "\n") // Simple cleanup
                    handler.post { 
                        tvPlan.text = cleanText
                    }
                }
                
                handler.post {
                    btnStart.isEnabled = true
                    if (plan != null) {
                        pendingPlan = plan
                        updatePlanDisplay(plan)
                        btnConfirm.isEnabled = true
                        btnSave.isEnabled = true
                        
                        // Show modify option
                        llModifyContainer.visibility = View.VISIBLE
                        btnModify.visibility = View.VISIBLE
                        llModifyInput.visibility = View.GONE
                        
                        appendLog("计划已生成，请确认或修改")
                    } else {
                        tvPlan.text = "计划生成失败，请重试"
                    }
                }
            }.start()
        }

        // 确认并执行
        btnConfirm.setOnClickListener {
            val service = AutoService.instance
            if (service == null) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val plan = pendingPlan
            if (plan == null) {
                Toast.makeText(this, "没有待执行的计划", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            service.startWithPlan(plan)
            pendingPlan = null
            btnConfirm.isEnabled = false
            updateUI(true)
        }

        // 修改计划 - 点击展开输入框
        btnModify.setOnClickListener {
            btnModify.visibility = View.GONE
            llModifyInput.visibility = View.VISIBLE
        }

        // 发送修改建议
        btnSendModify.setOnClickListener {
            val plan = pendingPlan ?: return@setOnClickListener
            val feedback = etModifyInput.text.toString()
            if (feedback.isNotEmpty()) {
                performRefinePlan(plan, feedback)
            }
        }

        btnStop.setOnClickListener {
            AutoService.instance?.stopAgent()
            updateUI(false)
        }
        
        // 直接保存
        btnSave.setOnClickListener {
            val plan = pendingPlan ?: AutoService.instance?.getCurrentPlan()
            if (plan == null) {
                Toast.makeText(this, "没有可保存的任务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            taskRepository.savePlan(plan)
            Toast.makeText(this, "已保存: ${plan.name}", Toast.LENGTH_SHORT).show()
            refreshSavedTasks()
        }

        // Logs
        val btnCopyLog = findViewById<Button>(R.id.btnCopyLog)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)
        
        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("AutoLLM Log", logBuilder.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
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
        
        refreshSavedTasks()
    }
    
    // ... helper methods for dialogs (showTaskDetailDialog, showScheduleDialog, etc.) ...
    // Since I'm rewriting the whole file, I need to include them.
    
    private fun showTaskDetailDialog(task: TaskPlanner.TaskPlan) {
        val stepsText = task.steps.mapIndexed { i, step -> 
            "${i + 1}. ${step.description}" 
        }.joinToString("\n")
        
        val scheduleText = task.scheduledTime?.let { "⏰ 定时: $it" } ?: "未设置定时"
        
        val message = """
任务: ${task.name}

步骤:
$stepsText

$scheduleText
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("任务详情")
            .setMessage(message)
            .setPositiveButton("执行") { _, _ ->
                val service = AutoService.instance
                if (service == null) {
                    Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                task.currentStepIndex = 0
                logBuilder.clear()
                tvLog.text = ""
                service.startWithPlan(task)
                updateUI(true)
            }
            .setNeutralButton("设置定时") { _, _ ->
                showScheduleDialog(task)
            }
            .setNegativeButton("删除") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("确定删除 '${task.name}'?")
                    .setPositiveButton("删除") { _, _ ->
                        taskRepository.deletePlan(task.id)
                        taskScheduler.cancelTask(task.id)
                        refreshSavedTasks()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }
    
    private fun showScheduleDialog(task: TaskPlanner.TaskPlan) {
        TimePickerDialog(this, { _, hour, minute ->
            task.scheduledTime = String.format("%02d:%02d", hour, minute)
            taskRepository.savePlan(task)
            taskScheduler.scheduleTask(task)
            Toast.makeText(this, "已设置定时 ${task.scheduledTime}", Toast.LENGTH_SHORT).show()
            refreshSavedTasks()
        }, 8, 0, true).show()
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
                    showTaskDetailDialog(task)
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
            pendingPlan != null -> "待确认"
            else -> "已就绪"
        }
        tvStatus.text = statusText
        
        btnStart.isEnabled = service != null && !AutoService.isRunning
        btnStop.isEnabled = AutoService.isRunning
    }

    private fun performRefinePlan(currentPlan: TaskPlanner.TaskPlan, feedback: String) {
        tvPlan.text = "正在根据反馈修正计划..."
        logBuilder.clear()
        tvLog.text = ""
        btnStart.isEnabled = false
        btnConfirm.isEnabled = false
        btnSave.isEnabled = false
        llModifyContainer.visibility = View.GONE // Hide during modification
        
        Thread {
            val planner = TaskPlanner(LLMClient())
            val newPlan = planner.refinePlan(currentPlan, feedback) { partialText ->
                val cleanText = partialText.replace("\\n", "\n")
                handler.post { tvPlan.text = cleanText }
            }
            
            handler.post {
                btnStart.isEnabled = true
                if (newPlan != null) {
                    pendingPlan = newPlan
                    updatePlanDisplay(newPlan)
                    btnConfirm.isEnabled = true
                    btnSave.isEnabled = true
                    
                    // Reset Modify UI
                    etModifyInput.setText("")
                    llModifyContainer.visibility = View.VISIBLE
                    llModifyInput.visibility = View.GONE
                    btnModify.visibility = View.VISIBLE
                    
                    appendLog("计划已修正，请确认")
                } else {
                    tvPlan.text = "修正失败，请重试。\n原计划保持不变。"
                    updatePlanDisplay(currentPlan)
                    btnConfirm.isEnabled = true
                    btnSave.isEnabled = true
                    llModifyContainer.visibility = View.VISIBLE // Show again
                }
            }
        }.start()
    }

    private fun updateUI(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        btnConfirm.isEnabled = !running && pendingPlan != null
        btnSave.isEnabled = !running && pendingPlan != null
        
        if (!running && pendingPlan != null) {
            llModifyContainer.visibility = View.VISIBLE
            // Reset to show button if needed
            if (llModifyInput.visibility != View.VISIBLE) {
                btnModify.visibility = View.VISIBLE
            }
        } else {
            llModifyContainer.visibility = View.GONE
        }
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
