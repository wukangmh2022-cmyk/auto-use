package com.example.autollm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * TaskRepository - 任务持久化存储
 */
class TaskRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    
    fun savePlan(plan: TaskPlanner.TaskPlan) {
        val tasks = loadAllPlans().toMutableList()
        
        // Update existing or add new
        val existingIndex = tasks.indexOfFirst { it.id == plan.id }
        if (existingIndex >= 0) {
            tasks[existingIndex] = plan
        } else {
            tasks.add(plan)
        }
        
        saveAllPlans(tasks)
    }
    
    fun deletePlan(planId: String) {
        val tasks = loadAllPlans().filter { it.id != planId }
        saveAllPlans(tasks)
    }
    
    fun loadAllPlans(): List<TaskPlanner.TaskPlan> {
        val jsonStr = prefs.getString("saved_tasks", "[]") ?: "[]"
        val result = mutableListOf<TaskPlanner.TaskPlan>()
        
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                result.add(TaskPlanner.TaskPlan.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return result
    }
    
    fun loadPlanById(id: String): TaskPlanner.TaskPlan? {
        return loadAllPlans().find { it.id == id }
    }
    
    fun getScheduledPlans(): List<TaskPlanner.TaskPlan> {
        return loadAllPlans().filter { it.scheduledTime != null }
    }
    
    private fun saveAllPlans(plans: List<TaskPlanner.TaskPlan>) {
        val array = JSONArray()
        for (plan in plans) {
            array.put(plan.toJson())
        }
        prefs.edit().putString("saved_tasks", array.toString()).apply()
    }
}
