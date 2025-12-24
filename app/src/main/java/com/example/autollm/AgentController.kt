package com.example.autollm

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

class AgentController(
    private val autoService: AutoService,
    private val onLog: (String) -> Unit
) {
    private val llmClient = LLMClient()
    private val taskPlanner = TaskPlanner(llmClient)
    private val history = mutableListOf<String>()
    
    // 0: 纯文本, 1: 视觉辅助, 2: VLM端到端
    private var visionMode = 0
    private var totalTokens = 0
    var onTokenUsage: ((Int) -> Unit)? = null
    
    init {
        llmClient.onTokenUsage = { usage ->
            totalTokens += usage
            onTokenUsage?.invoke(totalTokens)
        }
    }
    
    fun setVisionMode(mode: Int) {
        visionMode = mode
        llmClient.visionMode = mode
        Log.d("AgentController", "Vision mode set to: $mode")
    }

    private val maxHistorySize = 5
    
    // 状态监测变量
    private val stateActionHistory = mutableListOf<String>()
    private var lastUiHashForStuck: Int = 0
    private var reasoningLevel: Int = 0

    var currentPlan: TaskPlanner.TaskPlan? = null
        private set
    
    var onPlanUpdated: ((TaskPlanner.TaskPlan?) -> Unit)? = null

    /**
     * 直接执行已有计划
     */
    fun executePlan(plan: TaskPlanner.TaskPlan) {
        currentPlan = plan
        history.clear()
        stateActionHistory.clear()
        lastUiHashForStuck = 0
        reasoningLevel = 0
        onPlanUpdated?.invoke(plan)
        log("加载任务: ${plan.name}")
    }

    /**
     * 阶段一：生成计划
     */
    fun generatePlan(userRequest: String): Boolean {
        history.clear()
        stateActionHistory.clear()
        lastUiHashForStuck = 0
        reasoningLevel = 0
        
        // TaskPlanner.generatePlan 需要两个参数
        val plan = taskPlanner.generatePlan(userRequest) { msg ->
            // 转发生成进度的原始文本到日志（或专用显示）
            if (msg.length < 100) onLog(msg) 
        }
        
        currentPlan = plan
        onPlanUpdated?.invoke(plan)
        return plan != null
    }

    /**
     * 阶段二：执行一步（含页面校验和启发式推理控制）
     */
    fun executeStep(): Boolean {
        val plan = currentPlan ?: return false
        if (plan.isCompleted()) return false

        try {
            // 1. 获取 UI 情况 (含 UI 变化检测)
            val uiJson = autoService.dumpUI()
            
            if (uiJson == "SAME") {
                log("界面未变化，休眠中...")
                Thread.sleep(2000)
                return true
            }

            val currentUiHash = uiJson.hashCode()
            val nodeCount = countNodes(uiJson)
            
            // 启发式：重置或保持卡顿监测
            if (currentUiHash != lastUiHashForStuck) {
                stateActionHistory.clear()
                lastUiHashForStuck = currentUiHash
                // 界面变了，初步降低推理等级（除非由于节点多仍需等级1）
                reasoningLevel = if (nodeCount > 35) 1 else 0
            }

            if (visionMode == 2) {
                log("[${plan.progress()}] VLM模式: 端到端视觉分析中...")
            } else {
                log("[${plan.progress()}] 界面: ${compressUiLog(uiJson)}")
            }

            // 2. 规则驱动的弹窗预处理（不经过LLM，省Token+避免误点）
            val popupHandled = handlePopupsRuleBased(uiJson)
            if (popupHandled) {
                log("🔧 自动处理了弹窗，继续执行")
                return true // 处理完弹窗后重新获取界面
            }

            // 3. 页面校验
            val currentStep = plan.currentStep()
            if (currentStep != null && currentStep.expectedKeywords.isNotEmpty()) {
                if (!validatePage(uiJson, currentStep.expectedKeywords)) {
                    log("⚠️ 页面不匹配: ${currentStep.expectedKeywords}")
                }
            }
            
            // 视情况截图
            var screenshot: String? = null
            val useScreenshot = visionMode >= 1
            val isEndToEnd = visionMode == 2
            
            if (useScreenshot) {
                screenshot = autoService.captureScreenshotBase64()
            }

            // 4. 构建 Prompt 并调用获取操作
            // VLM端到端模式: 不用UI节点，纯视觉
            val promptText = if (isEndToEnd) {
                buildVLMPrompt(plan)
            } else {
                buildPrompt(uiJson, plan)
            }
            
            val userContent: Any = if (screenshot != null) {
                listOf(
                    mapOf("type" to "text", "text" to promptText),
                    mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$screenshot"))
                )
            } else {
                promptText
            }

            val response = llmClient.chat(listOf(
                mapOf("role" to "system", "content" to getSystemPrompt(reasoningLevel, screenshot != null)),
                mapOf("role" to "user", "content" to userContent)
            ))

            // 4. 解析响应
            val action = parseAction(response) ?: return true
            
            // 5. 动作重复性检查（识别原地拨号）
            val actionKey = action.optString("action", "") + ":" + action.optString("b", "")
            if (stateActionHistory.contains(actionKey)) {
                reasoningLevel = 2 // 确定重复了，下一轮强制解析障碍
                log("❗ 检测到重复动作，启用深度分析模式")
            }
            stateActionHistory.add(actionKey)

            // 显示思维内容
            val thought = action.optString("th", "")
            if (thought.isNotEmpty()) {
                log("🤔 $thought")
            }

            // 6. 执行物理操作
            val actionType = action.optString("action", "")
            val stepCompleted = action.optBoolean("step_completed", false)
            
            log("动作: $actionType" + if (stepCompleted) " (步完)" else "")
            
            when (actionType) {
                "click" -> {
                    // 支持两种坐标格式: b字段(文本模式) 或 x/y字段(VLM模式)
                    val x: Float
                    val y: Float
                    if (action.has("x") && action.has("y")) {
                        x = action.optDouble("x", 0.0).toFloat()
                        y = action.optDouble("y", 0.0).toFloat()
                    } else {
                        val coords = action.optString("b", "0,0").split(",")
                        x = coords.getOrNull(0)?.toFloatOrNull() ?: 0f
                        y = coords.getOrNull(1)?.toFloatOrNull() ?: 0f
                    }
                    autoService.performClick(x, y)
                    addHistory("点击 ($x,$y)")
                }
                "input" -> {
                    val text = action.optString("text", "")
                    val x: Float?
                    val y: Float?
                    if (action.has("x") && action.has("y")) {
                        x = action.optDouble("x", 0.0).toFloat()
                        y = action.optDouble("y", 0.0).toFloat()
                    } else {
                        val coords = action.optString("b", "").split(",")
                        x = coords.getOrNull(0)?.toFloatOrNull()
                        y = coords.getOrNull(1)?.toFloatOrNull()
                    }
                    if (text.isNotEmpty()) {
                        autoService.performInput(text, x, y)
                        addHistory("输入 '$text'")
                    }
                }
                "back" -> autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK).also { addHistory("返回") }
                "home" -> autoService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME).also { addHistory("主页") }
                "wait" -> {
                    val sec = action.optInt("s", 2)
                    Thread.sleep(sec * 1000L)
                    addHistory("等待 ${sec}s")
                }
                "scroll_down" -> autoService.performSwipe(540f, 500f, 540f, 1500f).also { addHistory("下滑") }
                "scroll_up" -> autoService.performSwipe(540f, 1500f, 540f, 500f).also { addHistory("上滑") }
                "scroll_left" -> autoService.performSwipe(900f, 1000f, 180f, 1000f).also { addHistory("左滑") }
                "scroll_right" -> autoService.performSwipe(180f, 1000f, 900f, 1000f).also { addHistory("右滑") }
                "long_press" -> {
                    // 支持 x/y 或 b 坐标格式，以及 duration 参数
                    val x: Float
                    val y: Float
                    if (action.has("x") && action.has("y")) {
                        x = action.optDouble("x", 0.0).toFloat()
                        y = action.optDouble("y", 0.0).toFloat()
                    } else {
                        val coords = action.optString("b", "0,0").split(",")
                        x = coords.getOrNull(0)?.toFloatOrNull() ?: 0f
                        y = coords.getOrNull(1)?.toFloatOrNull() ?: 0f
                    }
                    val durationMs = action.optLong("duration", 1000)
                    autoService.performLongPress(x, y, durationMs)
                    addHistory("长按 ($x,$y) ${durationMs}ms")
                }
                "drag" -> {
                    // 从起点拖到终点
                    val startX = action.optDouble("x", 0.0).toFloat()
                    val startY = action.optDouble("y", 0.0).toFloat()
                    val endX = action.optDouble("endX", 0.0).toFloat()
                    val endY = action.optDouble("endY", 0.0).toFloat()
                    val durationMs = action.optLong("duration", 800)
                    autoService.performDrag(startX, startY, endX, endY, durationMs)
                    addHistory("拖动 ($startX,$startY)->($endX,$endY)")
                }
                "done" -> {
                    log("任务完成: ${action.optString("r", "完成")}")
                    currentPlan = null
                    onPlanUpdated?.invoke(null)
                    return false
                }
            }
            
            // 7. 更新计划进度
            if (stepCompleted) {
                plan.currentStepIndex++
                history.clear()
                stateActionHistory.clear() // 步骤推进，清空重复检测
                onPlanUpdated?.invoke(plan)
                
                if (plan.isCompleted()) {
                    log("🎉 任务全部完成")
                    return false
                } else {
                    log("进入下一步: ${plan.currentStep()?.description}")
                }
            }
            
            return true
            
        } catch (e: Exception) {
            log("执行异常: ${e.message}")
            return true
        }
    }

    private fun validatePage(uiJson: String, keywords: List<String>): Boolean {
        val uiText = uiJson.lowercase()
        return keywords.any { keyword -> uiText.contains(keyword.lowercase()) }
    }

    private fun getSystemPrompt(level: Int, hasVision: Boolean = false): String {
        // Level 0: 最简 Prompt，省 Token
        val basePrompt = """Android助手。协议:t文本,d描述,b坐标,k可点
动作:click,input,back,home,wait,scroll_down/up/left/right,done
格式:{"th":"思考","action":"动作","b":"x,y","text":"输入内容","step_completed":false}
要求:只回JSON,步完设step_completed:true"""

        // Level 1/2: 追加策略
        val strategyPrompt = if (level >= 1) """
策略:
- 打开应用->先home
- 桌面找App:先左右翻页2次,还没找到再下拉搜索
- 列表找不到->scroll_down/up
- 输入框->先click再input
- 登录流程:账号->密码->登录按钮
- 看到"加载中"->wait 2秒
- 多次失败->换路径或搜索""" else ""

        // Level 2: 强制换策略提示
        val urgentHint = if (level >= 2) "\n⚠️卡顿中:必须换策略!" else ""
        
        // 视觉提示
        val visionHint = if (hasVision) "\n有截图,参考视觉识别无标签元素" else ""

        return basePrompt + strategyPrompt + urgentHint + visionHint
    }

    private fun buildPrompt(uiJson: String, plan: TaskPlanner.TaskPlan): String {
        val currentStep = plan.currentStep()
        val hist = if (history.isEmpty()) "" else "\n近况:${history.joinToString()}"
        
        return """任务:${plan.task}
进度:${plan.progress()} 目标:${currentStep?.description}
界面:$uiJson$hist"""
    }

    /**
     * VLM端到端模式的Prompt：不传UI节点，纯视觉
     */
    private fun buildVLMPrompt(plan: TaskPlanner.TaskPlan): String {
        val currentStep = plan.currentStep()
        val hist = if (history.isEmpty()) "" else "\n近况:${history.joinToString()}"
        
        return """你是手机操作助手。仔细观察截图，根据任务目标输出下一步操作。

任务: ${plan.task}
进度: ${plan.progress()} 
当前目标: ${currentStep?.description}$hist

直接从截图识别UI元素位置，输出像素坐标。
格式: {"th":"思考","action":"动作","x":像素X,"y":像素Y,...}
动作列表:
- click: 点击 {"action":"click","x":100,"y":200}
- long_press: 长按 {"action":"long_press","x":100,"y":200,"duration":1000}
- drag: 拖动 {"action":"drag","x":100,"y":200,"endX":300,"endY":400,"duration":800}
- input: 输入 {"action":"input","x":100,"y":200,"text":"内容"}
- back/home/wait/scroll_down/up/left/right/done
完成步骤时设step_completed:true。只回JSON。"""
    }

    private fun parseAction(response: String): JSONObject? {
        return try {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONObject(response.substring(jsonStart, jsonEnd))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun addHistory(action: String) {
        history.add(action)
        if (history.size > maxHistorySize) history.removeAt(0)
    }

    private fun countNodes(json: String): Int {
        return try { JSONArray(json).length() } catch (e: Exception) { 0 }
    }

    private fun compressUiLog(json: String): String {
        return try {
            val ja = JSONArray(json)
            val sb = StringBuilder("(${ja.length()}个) ")
            for (i in 0 until ja.length()) {
                val obj = ja.getJSONObject(i)
                val txt = obj.optString("t")
                val desc = obj.optString("d")
                val label = if (txt.isNotEmpty()) txt else desc
                if (label.isNotEmpty()) sb.append("[$label] ")
            }
            if (sb.length > 200) sb.substring(0, 200) + "..." else sb.toString()
        } catch (e: Exception) {
            "解析错误"
        }
    }

    private fun log(msg: String) {
        Log.d("AgentController", msg)
        onLog(msg)
    }

    /**
     * 规则驱动的弹窗处理（不经过LLM）
     * 返回 true 表示处理了弹窗，需要重新获取界面
     */
    private fun handlePopupsRuleBased(uiJson: String): Boolean {
        try {
            val nodes = JSONArray(uiJson)
            
            // 关闭/取消类弹窗关键词（优先级从高到低）
            val dismissKeywords = listOf(
                "跳过", "关闭", "取消", "不再提示", "稍后", "暂不", "我知道了",
                "以后再说", "不允许", "拒绝", "下次再说", "Skip", "Close", "Cancel", "Deny"
            )
            
            // 允许类按钮（权限请求中优先点击）
            val allowKeywords = listOf("允许", "同意", "确定", "好的", "继续", "Allow", "OK", "Accept")
            
            // 广告/推广类关键词（需要关闭）
            val adIndicators = listOf("广告", "推荐", "立即领取", "限时", "优惠", "红包", "福利")
            
            // 遍历节点，查找弹窗
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val text = node.optString("t", "").lowercase()
                val desc = node.optString("d", "").lowercase()
                val coords = node.optString("b", "")
                val isClickable = node.optInt("k", 0) == 1
                
                if (!isClickable || coords.isEmpty()) continue
                
                val fullText = "$text $desc"
                
                // 检查是否是关闭/取消按钮
                for (keyword in dismissKeywords) {
                    if (fullText.contains(keyword.lowercase())) {
                        val xy = coords.split(",")
                        if (xy.size == 2) {
                            log("🔧 自动关闭弹窗: $keyword")
                            autoService.performClick(xy[0].toFloat(), xy[1].toFloat())
                            Thread.sleep(500)
                            return true
                        }
                    }
                }
            }
            
            // 检查是否有通知栏消息覆盖（通常在顶部）
            // 如果检测到类似通知的元素，向上滑动清除
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val coords = node.optString("b", "")
                if (coords.isEmpty()) continue
                
                val xy = coords.split(",")
                if (xy.size == 2) {
                    val y = xy[1].toFloatOrNull() ?: continue
                    // 如果有可点击元素在屏幕最顶部（y < 100），可能是通知
                    // 这里不自动处理，因为可能误伤状态栏
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e("AgentController", "Popup detection error", e)
            return false
        }
    }
}
