package com.copilot.qqpet.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Q宠后台全功能自动化调度引擎 (v1.0.22)
 * 采用 Round-Robin 时间片智能轮转算法，彻底解决外出任务（打工/学习/探险）互斥冲突：
 * 1. 任务完成 -> 自动结算收益 -> 自动喂食沐浴补充体力清洁
 * 2. 依据 Round-Robin 游标在已启用的任务队列中顺序轮换：打工 -> 学习 -> 探险 -> 打工
 * 3. 具备【自适应年级探测】与【前台发包智能嗅探学习】双重保障，彻底根除 135010 课程配置为空问题
 * 4. 具备【绝对到期时间戳 + 秒级动态刷新支持】，杜绝界面时间不更新/假死视觉错觉
 * 5. 具备【网络请求 8s 熔断保护与本地缓存保活】，杜绝无超时永久挂起导致的协程死锁
 */
class PetAdventureEngine(private val bridge: QQPetDirectBridge) {

    companion object {
        private const val TAG = "PetAdventureEngine"
        private const val NETWORK_TIMEOUT_MS = 8000L
        const val ACTION_ENGINE_LOG = "com.copilot.qqpet.ACTION_ENGINE_LOG"
        const val ACTION_TRIGGER_ACTION = "com.copilot.qqpet.ACTION_TRIGGER_ACTION"
        const val ACTION_UPDATE_CONFIG = "com.copilot.qqpet.ACTION_UPDATE_CONFIG"
        const val EXTRA_LOG_TEXT = "extra_log_text"
        const val EXTRA_ACTION = "extra_action"

        var cachedPetId: String? = null
        var lastActiveStoryId: String? = null
        @Volatile
        var isLoopRunning = false

        // 跨进程配置开关（默认全开）
        @Volatile var enableStudy = true
        @Volatile var enableWork = true
        @Volatile var enableCare = true
        @Volatile var enableAdventure = false
        @Volatile var enableSettle = true

        // 实时状态文本与轮转游标
        @Volatile var currentStatusText = "全自动守护中 · 一刻不停三维轮转"
        @Volatile var currentTaskEndTimeMillis: Long = 0L
        @Volatile var currentTaskTypeName: String = "进阶修习中"

        @Volatile var roundRobinCursor = 0
        @Volatile var studyAttributeCursor = 0
        @Volatile var workJobCursor = 0

        // 学习与打工自选模式：0=均衡轮转, 1=专攻智力/文职, 2=专攻力量/体力, 3=专攻魅力/演艺
        @Volatile var prefStudyMode = 0
        @Volatile var prefWorkMode = 0
        @Volatile var prefCustomSchoolStage = 0 // 0: 自适应当前最高, 1: 初级, 2: 中级, 3: 高级, 4: 进修
        @Volatile var prefCustomCourseSubject = 0 // 0: 智能轮换, 1: 智力, 2: 力量, 3: 魅力
        @Volatile var prefCustomCourseDuration = 0 // 0: 任意时长, 1: 基础短课(10-45m), 2: 进阶长课(1-2.25h)
        @Volatile var prefCustomWorkType = 0 // 0: 演艺文化(最高收益), 1: 文职商业, 2: 体力搬运, 3: 三业轮换
        @Volatile var prefCustomWorkDuration = 0 // 0: 智能时长, 1: 10分钟(极速), 2: 45分钟(短工), 3: 2小时(中工), 4: 4小时(长工)

        @Volatile var lastCareTimeMillis: Long = 0L

        // 动态嗅探学习到的最新课程与工种（持久化）
        @Volatile var learnedStudySubEvent: Long? = null
        @Volatile var learnedStudyName: String? = null
        @Volatile var learnedWorkSubEvent: Long? = null
        @Volatile var learnedWorkName: String? = null

        // 当前账号已解锁数据缓存（用于 UI 实时置灰/解锁判断）
        @Volatile var cachedSchoolDetails: QQPetDirectBridge.SecondMapDetails? = null
        @Volatile var cachedWorkJobs: List<QQPetDirectBridge.SelectEvent>? = null
        @Volatile var cachedSchoolCourses: List<QQPetDirectBridge.SelectEvent>? = null

        fun getLiveRemainingSeconds(): Long {
            if (currentTaskEndTimeMillis <= 0L) return 0L
            val diff = (currentTaskEndTimeMillis - System.currentTimeMillis()) / 1000L
            return if (diff > 0) diff else 0L
        }

        fun formatLiveStatusText(): String {
            val sec = getLiveRemainingSeconds()
            if (sec <= 0L) {
                if (currentTaskEndTimeMillis > 0L) {
                    currentTaskEndTimeMillis = 0L
                    return "任务已修毕 · 正在自动结算收益..."
                }
                return currentStatusText
            }
            val m = sec / 60
            val s = sec % 60
            return "$currentTaskTypeName · 剩余 ${m}分${s}秒"
        }

        // 学园课程全阶段候选池 (包含初级 6101~6103、中级 6104~6106 / 6111~6113 等)
        val CANDIDATE_COURSES_INTELLECT = listOf(
            Triple("星空观察课", 6100L, 6101L),
            Triple("智力(文化课程)", 6100L, 6101L),
            Triple("文化学园初阶", 6100L, 6101L),
            Triple("中级智力课", 6100L, 6101L),
            Triple("", 6100L, 6101L)
        )
        val CANDIDATE_COURSES_STRENGTH = listOf(
            Triple("料理实验课", 6100L, 6201L),
            Triple("力量修习", 6100L, 6201L),
            Triple("体能锻炼初阶", 6100L, 6201L),
            Triple("中级力量课", 6100L, 6201L),
            Triple("", 6100L, 6201L)
        )
        val CANDIDATE_COURSES_CHARM = listOf(
            Triple("奇想夏令营", 6100L, 6301L),
            Triple("艺科修习", 6100L, 6301L),
            Triple("艺术修养初阶", 6100L, 6301L),
            Triple("中级魅力课", 6100L, 6301L),
            Triple("", 6100L, 6301L)
        )

        // 打工小镇全阶段工种候选池 (page 严格统一为 6400L)
        val CANDIDATE_JOBS_CLERK = listOf(
            Triple("星尘魔法塔", 6400L, 6401L),
            Triple("迷雾侦探所", 6400L, 6401L),
            Triple("小镇文职", 6400L, 6401L),
            Triple("小镇文职(10分)", 6400L, 6401L),
            Triple("图书管理(10分)", 6400L, 6401L),
            Triple("图书管理", 6400L, 6401L),
            Triple("文职兼职", 6400L, 6401L),
            Triple("", 6400L, 6401L)
        )
        val CANDIDATE_JOBS_PHYSICAL = listOf(
            Triple("风铃旅社", 6400L, 6501L),
            Triple("咕噜厨房", 6400L, 6501L),
            Triple("竹影武馆", 6400L, 6501L),
            Triple("小镇体力", 6400L, 6501L),
            Triple("小镇体力(10分)", 6400L, 6501L),
            Triple("搬运兼职(45分)", 6400L, 6501L),
            Triple("小镇搬运工(2小时)", 6400L, 6408L),
            Triple("", 6400L, 6501L)
        )
        val CANDIDATE_JOBS_PERFORM = listOf(
            Triple("彩虹画室", 6400L, 6601L),
            Triple("云朵梦舍", 6400L, 6601L),
            Triple("闪耀星屋", 6400L, 6601L),
            Triple("小镇演艺", 6400L, 6601L),
            Triple("小镇演艺(10分)", 6400L, 6601L),
            Triple("戏剧参演(45分)", 6400L, 6601L),
            Triple("舞台助理(2小时)", 6400L, 6601L),
            Triple("", 6400L, 6601L)
        )

        fun recordLearnedStudyCourse(context: Context, name: String, subEventType: Long) {
            learnedStudySubEvent = subEventType
            learnedStudyName = name
            try {
                val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("key_learned_study_sub", subEventType)
                    .putString("key_learned_study_name", name)
                    .commit()
                Log.i(TAG, "已成功持久化学习课程: $name (subEventType=$subEventType)")
            } catch (t: Throwable) {
                Log.e(TAG, "持久化学习课程失败: ${t.message}")
            }
        }

        fun recordLearnedWorkJob(context: Context, name: String, subEventType: Long) {
            learnedWorkSubEvent = subEventType
            learnedWorkName = name
            try {
                val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("key_learned_work_sub", subEventType)
                    .putString("key_learned_work_name", name)
                    .commit()
                Log.i(TAG, "已成功持久化打工工种: $name (subEventType=$subEventType)")
            } catch (t: Throwable) {
                Log.e(TAG, "持久化打工工种失败: ${t.message}")
            }
        }
    }

    data class StoryStatusResult(
        val code: Int,
        val remaining: Long?,
        val total: Long?,
        val storyId: String?
    )

    fun reloadConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            enableStudy = prefs.getBoolean("key_study", true)
            enableWork = prefs.getBoolean("key_work", true)
            enableCare = prefs.getBoolean("key_care", true)
            enableAdventure = prefs.getBoolean("key_adventure", false)
            enableSettle = prefs.getBoolean("key_settle", true)
            prefStudyMode = prefs.getInt("key_study_mode", 0)
            prefWorkMode = prefs.getInt("key_work_mode", 0)
            prefCustomSchoolStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
            prefCustomCourseSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
            prefCustomCourseDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
            prefCustomWorkType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
            prefCustomWorkDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)

            val savedPetId = prefs.getString("key_cached_pet_id", null)
            if (!savedPetId.isNullOrEmpty()) {
                cachedPetId = savedPetId
            }

            val lSub = prefs.getLong("key_learned_study_sub", 0L)
            if (lSub in listOf(6101L, 6201L, 6301L)) {
                learnedStudySubEvent = lSub
                learnedStudyName = prefs.getString("key_learned_study_name", null)
            } else {
                learnedStudySubEvent = null
                learnedStudyName = null
                prefs.edit().remove("key_learned_study_sub").remove("key_learned_study_name").commit()
            }
            val wSub = prefs.getLong("key_learned_work_sub", 0L)
            if (wSub in listOf(6401L, 6501L, 6601L)) {
                learnedWorkSubEvent = wSub
                learnedWorkName = prefs.getString("key_learned_work_name", null)
            } else {
                learnedWorkSubEvent = null
                learnedWorkName = null
                prefs.edit().remove("key_learned_work_sub").remove("key_learned_work_name").commit()
            }

            val savedEndTime = prefs.getLong("key_task_end_time", 0L)
            val savedTaskType = prefs.getString("key_task_type", null)
            if (savedEndTime > System.currentTimeMillis() && !savedTaskType.isNullOrEmpty()) {
                currentTaskEndTimeMillis = savedEndTime
                currentTaskTypeName = savedTaskType
            }

            Log.i(TAG, "从 SharedPreferences 重新载入配置: 学习=$enableStudy, 打工=$enableWork, 照顾=$enableCare, 冒险=$enableAdventure, 结算=$enableSettle, 学习模式=$prefStudyMode, 打工模式=$prefWorkMode, petId=$cachedPetId, 已学课程=$learnedStudyName($learnedStudySubEvent)")
        } catch (t: Throwable) {
            Log.e(TAG, "加载配置异常: ${t.message}")
        }
    }

    fun updateConfig(
        study: Boolean,
        work: Boolean,
        care: Boolean,
        adventure: Boolean,
        settle: Boolean,
        studyMode: Int = prefStudyMode,
        workMode: Int = prefWorkMode,
        schoolStage: Int = prefCustomSchoolStage,
        courseSubject: Int = prefCustomCourseSubject,
        courseDuration: Int = prefCustomCourseDuration,
        workType: Int = prefCustomWorkType,
        workDuration: Int = prefCustomWorkDuration
    ) {
        enableStudy = study
        enableWork = work
        enableCare = care
        enableAdventure = adventure
        enableSettle = settle
        prefStudyMode = studyMode
        prefWorkMode = workMode
        prefCustomSchoolStage = schoolStage
        prefCustomCourseSubject = courseSubject
        prefCustomCourseDuration = courseDuration
        prefCustomWorkType = workType
        prefCustomWorkDuration = workDuration
        Log.d(TAG, "配置已更新: 学习=$study, 打工=$work, 照顾=$care, 冒险=$adventure, 结算=$settle, 学校阶段=$schoolStage, 科目=$courseSubject, 课时=$courseDuration, 工种=$workType, 工时=$workDuration")
    }

    fun sendReadySignal(context: Context) {
        sendLog(context, "🟢 [QQ内核已就绪] 宿主发包代理与全功能自动化引擎已全部连通！")
    }

    fun startBackgroundLoop(context: Context) {
        if (isLoopRunning) return
        isLoopRunning = true

        reloadConfig(context)
        CoroutineScope(Dispatchers.IO).launch {
            sendLog(context, "🤖 [后台循环] Q宠全能巡检协程已激活 (Round-Robin 均衡轮换模式)！")
            try {
                while (isLoopRunning) {
                    try {
                        executeMasterCycle(context)
                    } catch (t: Throwable) {
                        currentStatusText = "巡检异常，稍后重试"
                        sendLog(context, "⚠️ [异常] 巡检报错: ${t.message}，15秒后重试")
                        delay(15 * 1000L)
                    }
                }
            } finally {
                isLoopRunning = false
                Log.w(TAG, "后台循环已退出，重置 isLoopRunning 为 false")
            }
        }
    }

    private suspend fun executeMasterCycle(context: Context) {
        reloadConfig(context)
        if (!bridge.isReady) {
            currentStatusText = "发包代理连接中..."
            sendLog(context, "⏳ [挂起] QQ 内部发包代理尚未就绪，等待 10 秒...")
            delay(10 * 1000L)
            return
        }

        // 1. 获取宠物 ID
        var petId = cachedPetId
        if (petId.isNullOrEmpty()) {
            currentStatusText = "正在锁定宠物 ID..."
            sendLog(context, "⏳ [巡检] 正在获取本人宠物 ID...")
            val (codePet, fetchedId) = queryOwnPetAwait()
            if (fetchedId.isNullOrEmpty()) {
                currentStatusText = "获取宠物 ID 失败 (code=$codePet)"
                sendLog(context, "❌ [巡检] 获取宠物 ID 失败 (code=$codePet)，30 秒后重试")
                delay(30 * 1000L)
                return
            }
            cachedPetId = fetchedId
            petId = fetchedId
            try {
                val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("key_cached_pet_id", fetchedId).commit()
            } catch (_: Throwable) {}
            sendLog(context, "✅ [巡检] 成功锁定宠物 ID: $petId")
        }

        // 2. 检查当前故事倒计时
        val storyStatus = queryStoryStatusAwait(petId)
        val hasActiveTask = if (storyStatus.code == 0) {
            val rem = storyStatus.remaining
            if (!storyStatus.storyId.isNullOrEmpty() && rem != null && rem > 0) {
                lastActiveStoryId = storyStatus.storyId
                val mins = rem / 60
                val secs = rem % 60
                val taskType = when {
                    storyStatus.storyId.startsWith("6100") -> "进阶修习中"
                    storyStatus.storyId.startsWith("6400") -> "小镇打工中"
                    storyStatus.storyId.startsWith("6700") -> "森林探险中"
                    else -> "任务执行中"
                }
                currentTaskTypeName = taskType
                currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
                currentStatusText = "$taskType · 剩余 ${mins}分${secs}秒"
                try {
                    val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong("key_task_end_time", currentTaskEndTimeMillis)
                        .putString("key_task_type", currentTaskTypeName)
                        .commit()
                } catch (_: Throwable) {}

                sendLog(context, "⏳ [状态] 宠物正在$taskType (StoryID: ${storyStatus.storyId})，剩余 $mins 分 $secs 秒 (总计 ${storyStatus.total ?: 0} 秒)")
                true
            } else {
                if (!storyStatus.storyId.isNullOrEmpty() && lastActiveStoryId == null) {
                    lastActiveStoryId = storyStatus.storyId
                }
                false
            }
        } else {
            false
        }

        // 3. 若任务已结束且开启了自动结算，执行结算
        val pendingStoryId = lastActiveStoryId ?: storyStatus.storyId
        if (!hasActiveTask && enableSettle && !pendingStoryId.isNullOrEmpty()) {
            currentStatusText = "正在自动结算收益..."
            sendLog(context, "🎁 [结算] 自动发起收益与经验结算 (StoryID: $pendingStoryId)...")
            val (codeSettle, _) = settleStoryAwait(pendingStoryId, petId)
            if (codeSettle == 0) {
                sendLog(context, "✅ [结算] 收益结算成功！金币与经验已入账")
                lastActiveStoryId = null
                currentTaskEndTimeMillis = 0L
            } else if (codeSettle == 135004) {
                sendLog(context, "⏳ [结算] 服务端返回 135004 (任务进行中尚未到达结算时间)")
            } else {
                sendLog(context, "ℹ️ [结算] 结算回包: code=$codeSettle (无需结算或已领取)")
                lastActiveStoryId = null
                currentTaskEndTimeMillis = 0L
            }
            delay(2000L)
        }

        // 4. 自动照顾：喂食 + 洗澡 (周期性守护，无论是否在任务中，均定期进行照顾补充体力与清洁度)
        if (enableCare) {
            val now = System.currentTimeMillis()
            if (now - lastCareTimeMillis > 3 * 60 * 1000L) { // 每3分钟执行一次常规照顾
                lastCareTimeMillis = now
                currentStatusText = "日常照顾 (喂食+清洁)..."
                val (tCode, remain, total) = queryFeedTimesAwait()
                if (tCode == 0 && total > 0 && remain <= 0) {
                    sendLog(context, "ℹ️ [照顾] 今日喂食次数已用尽 (剩余 $remain/$total 次)，跳过喂食")
                } else {
                    val countDesc = if (tCode == 0 && total > 0) " (今日剩余 $remain/$total 次)" else ""
                    sendLog(context, "🥣 [照顾] 自动喂食补充体力$countDesc...")
                    val (fCode, _) = feedAwait(petId)
                    sendLog(context, if (fCode == 0) "✅ [照顾] 喂食成功！" else "ℹ️ [照顾] 喂食回包 code=$fCode")
                    delay(1500L)
                }

                sendLog(context, "🧼 [照顾] 自动香皂沐浴提升清洁...")
                val (bCode, _) = bathAwait(petId)
                sendLog(context, if (bCode == 0) "✅ [照顾] 洗澡成功！" else "ℹ️ [照顾] 洗澡回包 code=$bCode")
                delay(1500L)
            }
        }

        // 若当前仍有任务在身，不触发新的外出，睡眠 30 秒以保持倒计时和状态动态刷新
        if (hasActiveTask) {
            val rem = storyStatus.remaining ?: 30L
            val sleepSec = minOf(rem + 2, 30L)
            delay(sleepSec * 1000L)
            return
        }

        // 5. 【Round-Robin 智能轮转核心】在已启用的任务中无缝轮换，杜绝互斥冲突
        val availableTasks = mutableListOf<String>()
        if (enableStudy) availableTasks.add("study")
        if (enableWork) availableTasks.add("work")
        if (enableAdventure) availableTasks.add("adventure")

        if (availableTasks.isEmpty()) {
            currentStatusText = "外出开关已全部关闭 · 静默待命"
            sendLog(context, "😴 [轮询] 当前外出项目（打工/学习/探险）全被关闭，10 分钟后再次巡检")
            delay(10 * 60 * 1000L)
            return
        }

        // 取出当前轮次的任务
        val targetTask = availableTasks[roundRobinCursor % availableTasks.size]
        roundRobinCursor = (roundRobinCursor + 1) % availableTasks.size

        when (targetTask) {
            "study" -> {
                val ok = dispatchAdaptiveStudy(context, petId)
                if (ok) {
                    delay(5 * 1000L)
                    return
                }
            }
            "work" -> {
                val ok = dispatchAdaptiveWork(context, petId)
                if (ok) {
                    delay(5 * 1000L)
                    return
                }
            }
            "adventure" -> {
                if (enableAdventure) {
                    currentStatusText = "一刻不停: 森林探险"
                    sendLog(context, "🚀 [外出历练] 发起神秘森林探险...")
                    val (codeAdv, storyId) = startAdventureAwait(petId)
                    if (codeAdv == 0 && !storyId.isNullOrEmpty()) {
                        lastActiveStoryId = storyId
                        currentTaskTypeName = "森林探险中"
                        currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                        currentStatusText = "野外探险中 · 搜寻秘宝"
                        sendLog(context, "🎉 [探险成功] 顺利启程！StoryID: $storyId")
                        delay(5 * 1000L)
                        return
                    } else {
                        sendLog(context, "ℹ️ [探险跳过] 回包 code=$codeAdv")
                    }
                }
            }
        }

        currentStatusText = "等待下次调度"
        sendLog(context, "😴 [轮询] 当前轮次完成，15 秒后继续自适应调度")
        delay(15 * 1000L)
    }

    /**
     * 智能自适应选课调度：优先已学习课程，结合自选模式与多阶段候选池探测
     */
    private suspend fun dispatchAdaptiveStudy(context: Context, petId: String): Boolean {
        // 第一阶段：通过官方 0x9ab2_1 动态拉取当前学园开放的真实课程库
        val modeDesc = when (prefStudyMode) {
            1 -> "专攻智力"
            2 -> "专攻力量"
            3 -> "专攻魅力"
            else -> "均衡轮转"
        }
        currentStatusText = "动态选课中: $modeDesc"
        sendLog(context, "📚 [动态选课] ($modeDesc) 正在向服务端拉取当前阶段开放课程...")

        // 先查询当前处于第几阶段学园 (1初级, 2中级, 3高级, 4进修)
        val (_, mapStage, _) = querySecondMapInfoAwait(6100L, petId)
        val targetStage = when {
            prefCustomSchoolStage > 0 -> prefCustomSchoolStage
            mapStage > 0 -> mapStage
            else -> 2 // 兜底中级学园
        }
        val stageName = when (targetStage) {
            1 -> "初级学园"
            2 -> "中级学园"
            3 -> "高级学园"
            4 -> "进修学园"
            else -> "第${targetStage}阶段学园"
        }
        sendLog(context, "📚 [学园阶段] 锁定目标学园: $stageName (stage=$targetStage, 自定义=${prefCustomSchoolStage > 0})")

        val (evtCode, dynamicEvents) = querySelectEventsAwait(6100L, petId, schoolStage = targetStage, careerType = 0)
        if (evtCode == 0 && dynamicEvents.isNotEmpty()) {
            sendLog(context, "📚 [课程拉取] 服务端返回 ${dynamicEvents.size} 门课程: " + dynamicEvents.joinToString { "${it.eventName}(${it.costTime},${it.reward.take(6)},can=${it.canDo})" })
            
            // 优先筛选满足条件 (canDo == true) 的课程
            val availableCourses = dynamicEvents.filter { it.canDo }.ifEmpty { dynamicEvents }
            
            // 1. 根据时长偏好筛选：1=基础短课(10-45m), 2=进阶长课(1-2.25h)
            val durationFiltered = when (prefCustomCourseDuration) {
                1 -> availableCourses.filter { it.costTime.contains("分") && !it.costTime.contains("90") && !it.costTime.contains("135") }
                2 -> availableCourses.filter { it.costTime.contains("小时") || it.costTime.contains("90") || it.costTime.contains("135") }
                else -> availableCourses
            }.ifEmpty { availableCourses }

            // 2. 根据官方 reward 字段精准挑选目标科目课程
            val targetCourse = when (prefCustomCourseSubject) {
                1 -> durationFiltered.find { it.reward.contains("智力") } ?: durationFiltered.first()
                2 -> durationFiltered.find { it.reward.contains("力量") } ?: durationFiltered.first()
                3 -> durationFiltered.find { it.reward.contains("魅力") } ?: durationFiltered.first()
                else -> {
                    val idx = (studyAttributeCursor % durationFiltered.size)
                    studyAttributeCursor = (studyAttributeCursor + 1) % durationFiltered.size
                    durationFiltered[idx]
                }
            }

            currentStatusText = "报名课程: ${targetCourse.eventName}"
            sendLog(context, "📚 [学园报名] ($modeDesc) 锁定课程: ${targetCourse.eventName} (时长:${targetCourse.costTime}, 奖励:${targetCourse.reward.take(8)}, canDo=${targetCourse.canDo})，发起启程...")
            val (codeSchool, storyId, errorMsg) = startSchoolAwait(petId, targetCourse.eventName, 6100L, targetCourse.subEventType)
            if (codeSchool == 0 && !storyId.isNullOrEmpty()) {
                lastActiveStoryId = storyId
                recordLearnedStudyCourse(context, targetCourse.eventName, targetCourse.subEventType)
                currentTaskTypeName = "进阶修习中 (${targetCourse.eventName})"
                currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                currentStatusText = "正在进修 ${targetCourse.eventName} · $modeDesc"
                sendLog(context, "🎉 [开课成功] 顺利开启 ${targetCourse.eventName}！StoryID: $storyId，学分高速增长中")
                return true
            } else {
                sendLog(context, "⚠️ [动态选课] ${targetCourse.eventName} 报名回包 code=$codeSchool, 服务端说明: ${errorMsg ?: "无"}")
            }
        } else {
            sendLog(context, "⚠️ [动态选课] 服务端动态拉取课程回包 code=$evtCode, 尝试候选池保底...")
        }

        // 第二阶段：候选池兜底机制
        val candidatePool = mutableListOf<Triple<String, Long, Long>>()

        val learnedSub = learnedStudySubEvent
        val learnedName = learnedStudyName
        if (learnedSub != null && learnedSub > 0L) {
            candidatePool.add(Triple(learnedName ?: "当前学园主修课程", 6100L, learnedSub))
        }

        when (prefStudyMode) {
            1 -> candidatePool.addAll(CANDIDATE_COURSES_INTELLECT)
            2 -> candidatePool.addAll(CANDIDATE_COURSES_STRENGTH)
            3 -> candidatePool.addAll(CANDIDATE_COURSES_CHARM)
            else -> {
                val allDirections = listOf(
                    CANDIDATE_COURSES_INTELLECT,
                    CANDIDATE_COURSES_STRENGTH,
                    CANDIDATE_COURSES_CHARM
                )
                val curDir = allDirections[studyAttributeCursor % allDirections.size]
                candidatePool.addAll(curDir)
            }
        }

        val distinctCandidates = candidatePool.distinctBy { Pair(it.first, it.third) }

        for (course in distinctCandidates) {
            val modeDesc = when (prefStudyMode) {
                1 -> "专攻智力"
                2 -> "专攻力量"
                3 -> "专攻魅力"
                else -> "均衡轮转"
            }
            currentStatusText = "自适应进修: $modeDesc · ${course.first}"
            sendLog(context, "📚 [学园修行] ($modeDesc) 尝试修习 ${course.first} (subEvent=${course.third})...")
            val (codeSchool, storyId, errorMsg) = startSchoolAwait(petId, course.first, course.second, course.third)
            if (codeSchool == 0 && !storyId.isNullOrEmpty()) {
                lastActiveStoryId = storyId
                recordLearnedStudyCourse(context, course.first, course.third)
                currentTaskTypeName = "进阶修习中"
                currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                if (prefStudyMode == 0) {
                    studyAttributeCursor = (studyAttributeCursor + 1) % 3
                }
                currentStatusText = "正在进修 ${course.first} · $modeDesc"
                sendLog(context, "🎉 [开课成功] 顺利开启 ${course.first}！StoryID: $storyId，属性与学分高速增长中")
                return true
            } else {
                sendLog(context, "ℹ️ [课程探测] ${course.first} 回包 code=$codeSchool, 服务端说明: ${errorMsg ?: "无"}")
                delay(1200L)
            }
        }
        return false
    }

    /**
     * 智能自适应打工调度：优先已学习工种，结合自选模式与多阶段候选池探测
     */
    private suspend fun dispatchAdaptiveWork(context: Context, petId: String): Boolean {
        // 第一阶段：通过官方 0x9ab2_1 动态拉取当前小镇开放的工种库
        val modeDesc = when (prefWorkMode) {
            1 -> "专攻文职"
            2 -> "专攻体力"
            3 -> "专攻演艺"
            else -> "均衡兼职"
        }
        currentStatusText = "动态求职中: $modeDesc"
        sendLog(context, "💼 [动态求职] ($modeDesc) 正在向服务端拉取打工小镇岗位列表...")

        val targetCareerType = when (prefCustomWorkType) {
            0 -> 3 // 演艺文化 (官方实测收益最高: 10m/77, 45m/312, 2h/509, 4h/564)
            1 -> 1 // 文职商业 (10m/71, 45m/288, 2h/469, 4h/520)
            2 -> 2 // 体力搬运 (10m/65, 45m/263, 2h/429, 4h/476)
            3 -> {
                val c = (workJobCursor % 3) + 1
                workJobCursor = (workJobCursor + 1) % 3
                c
            }
            else -> {
                val c = (workJobCursor % 3) + 1
                workJobCursor = (workJobCursor + 1) % 3
                c
            }
        }

        val (evtCode, dynamicJobs) = querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = targetCareerType)
        if (evtCode == 0 && dynamicJobs.isNotEmpty()) {
            sendLog(context, "💼 [岗位拉取] 服务端返回 ${dynamicJobs.size} 个工种: " + dynamicJobs.joinToString { "${it.eventName}(${it.costTime},can=${it.canDo})" })
            val availableJobs = dynamicJobs.filter { it.canDo }.ifEmpty { dynamicJobs }
            // 根据工时偏好精准挑选 (官方阶梯: 10分钟 / 45分钟 / 2小时 / 4小时)
            val targetJob = when (prefCustomWorkDuration) {
                1 -> availableJobs.find { it.costTime.contains("10") } ?: availableJobs.first()
                2 -> availableJobs.find { it.costTime.contains("45") } ?: availableJobs.first()
                3 -> availableJobs.find { it.costTime.contains("2小时") } ?: availableJobs.first()
                4 -> availableJobs.find { it.costTime.contains("4小时") } ?: availableJobs.first()
                else -> availableJobs.last() // 默认最长工时高效挂机
            }

            currentStatusText = "小镇上岗: ${targetJob.eventName}"
            sendLog(context, "💼 [小镇上岗] ($modeDesc) 锁定岗位: ${targetJob.eventName} (工时:${targetJob.costTime}, subEventType=${targetJob.subEventType}, canDo=${targetJob.canDo})，发起启程...")
            val (codeWork, storyId, errorMsg) = startWorkAwait(petId, targetJob.eventName, 6400L, targetJob.subEventType)
            if (codeWork == 0 && !storyId.isNullOrEmpty()) {
                lastActiveStoryId = storyId
                recordLearnedWorkJob(context, targetJob.eventName, targetJob.subEventType)
                currentTaskTypeName = "小镇打工中 (${targetJob.eventName})"
                currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                currentStatusText = "正在进行 ${targetJob.eventName} · $modeDesc"
                sendLog(context, "🎉 [打工成功] 顺利开工 ${targetJob.eventName}！StoryID: $storyId，勤劳致富中")
                return true
            } else {
                sendLog(context, "⚠️ [动态打工] ${targetJob.eventName} 开工回包 code=$codeWork, 服务端说明: ${errorMsg ?: "无"}")
            }
        } else {
            sendLog(context, "⚠️ [动态打工] 服务端动态拉取工种回包 code=$evtCode, 尝试候选池保底...")
        }

        // 第二阶段：候选池兜底机制
        val candidatePool = mutableListOf<Triple<String, Long, Long>>()

        val learnedSub = learnedWorkSubEvent
        val learnedName = learnedWorkName
        if (learnedSub != null && learnedSub > 0L) {
            candidatePool.add(Triple(learnedName ?: "当前小镇兼职", 6400L, learnedSub))
        }

        when (prefWorkMode) {
            1 -> candidatePool.addAll(CANDIDATE_JOBS_CLERK)
            2 -> candidatePool.addAll(CANDIDATE_JOBS_PHYSICAL)
            3 -> candidatePool.addAll(CANDIDATE_JOBS_PERFORM)
            else -> {
                val allDirections = listOf(
                    CANDIDATE_JOBS_CLERK,
                    CANDIDATE_JOBS_PHYSICAL,
                    CANDIDATE_JOBS_PERFORM
                )
                val curDir = allDirections[workJobCursor % allDirections.size]
                candidatePool.addAll(curDir)
            }
        }

        val distinctCandidates = candidatePool.distinctBy { Pair(it.first, it.third) }

        for (job in distinctCandidates) {
            val modeDesc = when (prefWorkMode) {
                1 -> "专攻文职"
                2 -> "专攻体力"
                3 -> "专攻演艺"
                else -> "均衡兼职"
            }
            currentStatusText = "自适应打工: $modeDesc · ${job.first}"
            sendLog(context, "💼 [勤工俭学] ($modeDesc) 尝试发起 ${job.first} (subEvent=${job.third})...")
            val (codeWork, storyId, errorMsg) = startWorkAwait(petId, job.first, job.second, job.third)
            if (codeWork == 0 && !storyId.isNullOrEmpty()) {
                lastActiveStoryId = storyId
                recordLearnedWorkJob(context, job.first, job.third)
                currentTaskTypeName = "小镇打工中"
                currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                if (prefWorkMode == 0) {
                    workJobCursor = (workJobCursor + 1) % 3
                }
                currentStatusText = "正在进行 ${job.first} · $modeDesc"
                sendLog(context, "🎉 [打工成功] 顺利开工 ${job.first}！StoryID: $storyId")
                return true
            } else {
                sendLog(context, "ℹ️ [工种探测] ${job.first} 回包 code=$codeWork, 服务端说明: ${errorMsg ?: "无"}")
                delay(1200L)
            }
        }
        return false
    }

    fun runAdventureFlow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            sendLog(context, "👉 [手动] 收到指令，立即触发一轮全自动巡检流程...")
            executeMasterCycle(context)
        }
    }

    fun runAction(context: Context, action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                "cycle" -> {
                    sendLog(context, "👉 [指令] 立即触发全流程策略调度循环...")
                    executeMasterCycle(context)
                }
                "care" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    val (tCode, remain, total) = queryFeedTimesAwait()
                    if (tCode == 0 && total > 0 && remain <= 0) {
                        sendLog(context, "ℹ️ [照顾实测] 今日喂食次数已用尽 (剩余 $remain/$total 次)，跳过喂食")
                    } else {
                        val countDesc = if (tCode == 0 && total > 0) " (今日剩余 $remain/$total 次)" else ""
                        sendLog(context, "🥣 [照顾实测] 发起喂食补充体力$countDesc...")
                        val (fCode, _) = feedAwait(petId)
                        sendLog(context, if (fCode == 0) "✅ [照顾实测] 喂食成功！体力已补充" else "ℹ️ [照顾实测] 喂食回包 code=$fCode")
                        delay(1500L)
                    }
                    sendLog(context, "🧼 [照顾实测] 发起香皂沐浴...")
                    val (bCode, _) = bathAwait(petId)
                    sendLog(context, if (bCode == 0) "✅ [照顾实测] 洗澡成功！清洁度已提升" else "ℹ️ [照顾实测] 洗澡回包 code=$bCode")
                }
                "work" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    sendLog(context, "💼 [打工实测] 开始触发自适应打工探测流程...")
                    dispatchAdaptiveWork(context, petId)
                }
                "school" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    sendLog(context, "📚 [学习实测] 开始触发自适应学园选课探测流程...")
                    dispatchAdaptiveStudy(context, petId)
                }
                "adventure" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    sendLog(context, "🚀 [冒险实测] 发起森林探险...")
                    val (aCode, storyId) = startAdventureAwait(petId)
                    if (aCode == 0 && !storyId.isNullOrEmpty()) {
                        lastActiveStoryId = storyId
                        currentTaskTypeName = "森林探险中"
                        currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                        sendLog(context, "🎉 [冒险实测] 冒险探索成功启程！StoryID: $storyId")
                    } else {
                        sendLog(context, "ℹ️ [冒险实测] 探险回包 code=$aCode (可能正在其他任务中)")
                    }
                }
                "settle" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    val sid = lastActiveStoryId
                    if (sid.isNullOrEmpty()) {
                        sendLog(context, "⚠️ [结算实测] 暂无记录中的活跃 StoryID，正在查询当前任务进度...")
                        val (code, remaining, total) = queryStoryStatusAwait(petId)
                        sendLog(context, "ℹ️ [结算实测] 任务状态查询: code=$code, 剩余=${remaining ?: 0}秒, 总计=${total ?: 0}秒")
                    } else {
                        sendLog(context, "🎁 [结算实测] 正在结算 StoryID: $sid ...")
                        val (sCode, _) = settleStoryAwait(sid, petId)
                        if (sCode == 0) {
                            sendLog(context, "✅ [结算实测] 收益结算成功！金币与经验已到账")
                            lastActiveStoryId = null
                            currentTaskEndTimeMillis = 0L
                        } else {
                            sendLog(context, "ℹ️ [结算实测] 结算回包: code=$sCode")
                        }
                    }
                }
                "recall" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    sendLog(context, "🚨 [召回指令] 正在查询宠物当前执行中的任务...")
                    val status = queryStoryStatusAwait(petId)
                    val activeSid = status.storyId ?: lastActiveStoryId
                    if (activeSid.isNullOrEmpty()) {
                        sendLog(context, "ℹ️ [召回指令] 宠物当前处于空闲状态，无需召回")
                    } else {
                        sendLog(context, "🚨 [召回指令] 正在向官方发送提前召回指令 (StoryID: $activeSid)...")
                        val (code, errMsg) = recallStoryAwait(activeSid, petId)
                       if (code == 0) {
                           lastActiveStoryId = null
                           currentTaskEndTimeMillis = 0L
                           currentTaskTypeName = "已召回回家 (空闲)"
                           currentStatusText = "已安全召回回家 · 待命"
                           sendLog(context, "✅ [召回成功] 宠物已提前回家！当前状态已重置为空闲")
                       } else {
                           sendLog(context, "⚠️ [召回失败] 服务端返回 code=$code, 说明: ${errMsg ?: "未知"}")
                       }
                   }
               }
                "like_back" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    sendLog(context, "🐾 [互踩实测] 正在拉取来踩过我家的小伙伴访客记录...")
                    val (code, members) = fetchLikeListAwait()
                    if (code != 0) {
                        sendLog(context, "❌ [互踩实测] 拉取访客列表失败: code=$code")
                        return@launch
                    }
                    if (members.isEmpty()) {
                        sendLog(context, "ℹ️ [互踩实测] 暂无访客来踩记录")
                        return@launch
                    }
                    val toLike = members.filter { it.canLikeBack }
                    sendLog(context, "📊 [互踩实测] 成功拉取到 ${members.size} 条来访记录，其中 ${toLike.size} 位好友尚未回踩")
                    var successCount = 0
                    for (m in toLike) {
                        val name = if (m.nick.isNotEmpty()) m.nick else "${m.uin}"
                        sendLog(context, "🐾 [互踩实测] 正在回踩好友: $name (${m.uin})...")
                        val (lCode, _) = sendLikeAwait(m.uin)
                        if (lCode == 0) {
                            successCount++
                            sendLog(context, "✅ [互踩实测] 成功回踩好友 $name！")
                        } else {
                            sendLog(context, "ℹ️ [互踩实测] 回踩好友 $name 回包: code=$lCode")
                        }
                        delay(1200L)
                    }
                    sendLog(context, "🎉 [互踩实测] 回踩任务完成！共成功回踩 $successCount 位好友")
                }
                "inspect" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    sendLog(context, "🔍 [全量数据探测] 开始深度抓取官方全学园与全工种配置...")
                    for (stg in 1..4) {
                        val stgName = when (stg) { 1 -> "初级学园"; 2 -> "中级学园"; 3 -> "高级学园"; 4 -> "进修学园"; else -> "$stg" }
                        val (code, events) = querySelectEventsAwait(6100L, petId, schoolStage = stg, careerType = 0)
                        sendLog(context, "📚 [$stgName] code=$code, 课程数=${events.size}:")
                        for (e in events) {
                            sendLog(context, "   📖 ${e.eventName} | sub=${e.subEventType} | 耗时='${e.costTime}' | 消耗='${e.cost}' | 奖励='${e.reward}' | canDo=${e.canDo} | level=${e.level}")
                        }
                    }
                    for (car in 1..3) {
                        val carName = when (car) { 1 -> "文职商业"; 2 -> "体力搬运"; 3 -> "演艺文化"; else -> "$car" }
                        val (code, jobs) = querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = car)
                        sendLog(context, "💼 [$carName] code=$code, 岗位数=${jobs.size}:")
                        for (j in jobs) {
                            sendLog(context, "   🔨 ${j.eventName} | sub=${j.subEventType} | 耗时='${j.costTime}' | 消耗='${j.cost}' | 奖励='${j.reward}' | canDo=${j.canDo} | level=${j.level}")
                        }
                    }
                    sendLog(context, "✅ [全量数据探测] 抓取完成！")
                }
                else -> {
                    sendLog(context, "❓ [未知指令] action=$action")
                }
            }
        }
    }

    private suspend fun ensurePetId(context: Context): String? {
        if (!bridge.isReady) {
            sendLog(context, "❌ [错误] QQ 发包代理尚未就绪，请稍候重试")
            return null
        }
        var petId = cachedPetId
        if (petId.isNullOrEmpty()) {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            val savedPetId = prefs.getString("key_cached_pet_id", null)
            if (!savedPetId.isNullOrEmpty()) {
                cachedPetId = savedPetId
                return savedPetId
            }
            sendLog(context, "⏳ [鉴权] 正在锁定本人宠物 ID...")
            val (codePet, fetchedId) = queryOwnPetAwait()
            if (fetchedId.isNullOrEmpty()) {
                sendLog(context, "❌ [鉴权] 获取宠物 ID 失败 (code=$codePet)，请检查 QQ 登录状态")
                return null
            }
            cachedPetId = fetchedId
            petId = fetchedId
            try {
                prefs.edit().putString("key_cached_pet_id", fetchedId).commit()
            } catch (_: Throwable) {}
            sendLog(context, "✅ [鉴权] 锁定宠物 ID: $petId")
        }
        return petId
    }

    suspend fun queryOwnPetAwait(timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryOwnPet { code, petId, _ ->
                        if (cont.isActive) cont.resume(Pair(code, petId))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    suspend fun queryStoryStatusAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): StoryStatusResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryStoryStatus(petId) { code, remaining, total, activeStoryId ->
                        if (cont.isActive) cont.resume(StoryStatusResult(code, remaining, total, activeStoryId))
                    }
                }
            } ?: StoryStatusResult(-99, null, null, null)
        } catch (_: Throwable) {
            StoryStatusResult(-99, null, null, null)
        }

   private suspend fun settleStoryAwait(storyId: String, petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, ByteArray?> =
       try {
           withTimeoutOrNull(timeoutMs) {
               suspendCancellableCoroutine { cont ->
                   bridge.settleStory(storyId, petId) { code, data ->
                       if (cont.isActive) cont.resume(Pair(code, data))
                   }
               }
           } ?: Pair(-99, null)
       } catch (_: Throwable) {
           Pair(-99, null)
       }

    private suspend fun recallStoryAwait(storyId: String, petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.recallStory(storyId, petId) { code, _, errorMsg ->
                        if (cont.isActive) cont.resume(Pair(code, errorMsg))
                    }
                }
            } ?: Pair(-99, "超时")
        } catch (t: Throwable) {
            Pair(-99, t.message)
        }

   suspend fun queryFeedTimesAwait(timeoutMs: Long = NETWORK_TIMEOUT_MS): Triple<Int, Int, Int> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryFeedTimes { code, remain, total ->
                        if (cont.isActive) cont.resume(Triple(code, remain, total))
                    }
                }
            } ?: Triple(-99, 0, 0)
        } catch (_: Throwable) {
            Triple(-99, 0, 0)
        }

    private suspend fun feedAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, ByteArray?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.feed(petId) { code, data, _ ->
                        if (cont.isActive) cont.resume(Pair(code, data))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    private suspend fun bathAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, ByteArray?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.bath(petId) { code, data, _ ->
                        if (cont.isActive) cont.resume(Pair(code, data))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    private suspend fun startWorkAwait(
        petId: String,
        jobName: String = "小镇兼职",
        page: Long = 6400L,
        subEventType: Long = 6401L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, String?, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startWork(petId, jobName, page, subEventType) { code, storyId, _, errorMsg ->
                        if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                    }
                }
            } ?: Triple(-99, null, "网络响应超时")
        } catch (t: Throwable) {
            Triple(-99, null, t.message)
        }

    private suspend fun startSchoolAwait(
        petId: String,
        courseName: String = "基础学园课程",
        page: Long = 6100L,
        subEventType: Long = 6101L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, String?, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startSchool(petId, courseName, page, subEventType) { code, storyId, _, errorMsg ->
                        if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                    }
                }
            } ?: Triple(-99, null, "网络响应超时")
        } catch (t: Throwable) {
            Triple(-99, null, t.message)
        }

    private suspend fun startAdventureAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startAdventure(petId) { code, storyId, _, _ ->
                        if (cont.isActive) cont.resume(Pair(code, storyId))
                    }
                }
            } ?: Pair(-99, null)
        } catch (_: Throwable) {
            Pair(-99, null)
        }

    suspend fun querySecondMapInfoAwait(
        eventType: Long = 6100L,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, Int, Long> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.querySecondMapInfo(eventType, petId) { code, stage, lastSub, _ ->
                        if (cont.isActive) cont.resume(Triple(code, stage, lastSub))
                    }
                }
           } ?: Triple(-99, 0, 0L)
       } catch (_: Throwable) {
           Triple(-99, 0, 0L)
       }

    suspend fun querySecondMapInfoDetailsAwait(
        eventType: Long = 6100L,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.SecondMapDetails =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.querySecondMapInfoDetails(eventType, petId) { details ->
                        if (cont.isActive) cont.resume(details)
                    }
                }
            } ?: QQPetDirectBridge.SecondMapDetails(-99, 0, 0L, emptyList())
        } catch (_: Throwable) {
            QQPetDirectBridge.SecondMapDetails(-99, 0, 0L, emptyList())
        }

    suspend fun preloadAccountDataAwait(petId: String): Triple<QQPetDirectBridge.SecondMapDetails?, List<QQPetDirectBridge.SelectEvent>?, List<QQPetDirectBridge.SelectEvent>?> {
        val details = querySecondMapInfoDetailsAwait(6100L, petId)
        if (details.code == 0) {
            cachedSchoolDetails = details
        }
        val targetStage = if (details.currentStage > 0) details.currentStage else 3
        val (cCode, courses) = querySelectEventsAwait(6100L, petId, schoolStage = targetStage, careerType = 0)
        if (cCode == 0 && courses.isNotEmpty()) {
            cachedSchoolCourses = courses
        }
        val targetCareer = when (prefCustomWorkType) { 0 -> 3; 1 -> 1; 2 -> 2; else -> 3 }
        val (jCode, jobs) = querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = targetCareer)
        if (jCode == 0 && jobs.isNotEmpty()) {
            cachedWorkJobs = jobs
        }
        return Triple(cachedSchoolDetails, cachedSchoolCourses, cachedWorkJobs)
    }

    suspend fun querySelectEventsAwait(
        eventType: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, List<QQPetDirectBridge.SelectEvent>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.querySelectEvents(eventType, petId, schoolStage, careerType) { code, events, _, _ ->
                        if (cont.isActive) cont.resume(Pair(code, events))
                    }
                }
            } ?: Pair(-99, emptyList())
        } catch (_: Throwable) {
            Pair(-99, emptyList())
        }

    suspend fun fetchLikeListAwait(extra: String = "", timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchLikeList(extra) { code, members, _, _, _, _ ->
                        if (cont.isActive) cont.resume(Pair(code, members))
                    }
                }
            } ?: Pair(-99, emptyList())
        } catch (_: Throwable) {
            Pair(-99, emptyList())
        }

    suspend fun sendLikeAwait(targetUin: Long, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.sendLike(targetUin) { code, _, err ->
                        if (cont.isActive) cont.resume(Pair(code, err))
                    }
                }
            } ?: Pair(-99, "超时")
        } catch (t: Throwable) {
            Pair(-99, t.message)
        }

    fun sendLog(context: Context, message: String) {
        Log.i(TAG, message)
        try {
            val intent = Intent(ACTION_ENGINE_LOG).apply {
                setPackage("com.copilot.qqpet")
                putExtra(EXTRA_LOG_TEXT, message)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "发送广播日志失败: ${t.message}")
        }
    }
}
