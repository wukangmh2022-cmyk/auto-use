package com.example.autollm

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.Calendar

/**
 * TaskScheduler - 定时任务调度
 */
class TaskScheduler(private val context: Context) {
    
    companion object {
        const val WORK_TAG = "autollm_scheduled_task"
        const val KEY_TASK_ID = "task_id"
    }
    
    private val workManager = WorkManager.getInstance(context)
    
    fun scheduleTask(plan: TaskPlanner.TaskPlan) {
        val scheduledTime = plan.scheduledTime ?: return
        
        // Parse time "HH:mm"
        val parts = scheduledTime.split(":")
        if (parts.size != 2) return
        
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return
        
        // Calculate delay until next occurrence
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }
        
        // If target time has passed today, schedule for tomorrow
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        val delayMillis = target.timeInMillis - now.timeInMillis
        
        val workRequest = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInputData(workDataOf(KEY_TASK_ID to plan.id))
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag("${WORK_TAG}_${plan.id}")
            .build()
        
        workManager.enqueueUniqueWork(
            "task_${plan.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
    
    fun cancelTask(planId: String) {
        workManager.cancelUniqueWork("task_$planId")
    }
    
    fun cancelAllTasks() {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }
}

/**
 * TaskWorker - WorkManager Worker 执行定时任务
 */
class TaskWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        val taskId = inputData.getString(TaskScheduler.KEY_TASK_ID) ?: return Result.failure()
        
        val repository = TaskRepository(applicationContext)
        val plan = repository.loadPlanById(taskId) ?: return Result.failure()
        
        // Reset progress for re-execution
        plan.currentStepIndex = 0
        repository.savePlan(plan)
        
        // Notify the service to start (if running)
        AutoService.instance?.let { service ->
            // Start with the saved plan
            AutoService.pendingPlanToExecute = plan
            AutoService.onLogCallback?.invoke("定时任务触发: ${plan.name}")
        }
        
        // Re-schedule for next day if still has scheduledTime
        if (plan.scheduledTime != null) {
            TaskScheduler(applicationContext).scheduleTask(plan)
        }
        
        return Result.success()
    }
}
