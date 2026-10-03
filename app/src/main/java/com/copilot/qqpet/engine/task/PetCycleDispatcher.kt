package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.engine.model.StudyDispatchParam
import com.copilot.qqpet.engine.model.WorkDispatchParam
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlin.coroutines.resume

/**
 * 负责主循环任务的分发、自适应学业、打工、探险与即时指令路由
 */
object PetCycleDispatcher {

    suspend fun dispatchNextAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Long {
        val available = mutableListOf<String>()
        if (PetAdventureEngine.enableStudy) available.add("study")
        if (PetAdventureEngine.enableWork) available.add("work")
        if (PetAdventureEngine.enableAdventure) available.add("adventure")
        if (available.isEmpty()) return 30000L

        val target = available[PetAdventureEngine.roundRobinCursor % available.size]
        PetAdventureEngine.roundRobinCursor = (PetAdventureEngine.roundRobinCursor + 1) % available.size
        executeAction(context, bridge, petId, target)
        return 5000L
    }

    suspend fun executeAction(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String,
        action: String
    ) {
        when (action) {
            "study", "school" -> {
                val ok = dispatchStudy(context, bridge, petId)
                showToast(context, if (ok) "已成功安排学园课程修习" else "课程开课未生效，详情见日志")
            }
            "work" -> {
                val ok = dispatchWork(context, bridge, petId)
                showToast(context, if (ok) "已成功安排兼职打工派遣" else "打工开工未生效，详情见日志")
            }
            "adventure" -> {
                val ok = dispatchAdventure(context, bridge, petId)
                showToast(context, if (ok) "已成功启程森林探险巡航" else "探险启程未生效，详情见日志")
            }
            "care" -> {
                PetCareTask.feedWithAutoBuyAwait(context, bridge, petId) { PetAdventureEngine.sendLog(context, it) }
                PetCareTask.bathWithAutoBuyAwait(context, bridge, petId) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发小宠进食与洗澡巡检")
            }
            "feed" -> {
                PetCareTask.feedWithAutoBuyAwait(context, bridge, petId) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发小宠进食补充体力")
            }
            "bath" -> {
                PetCareTask.bathWithAutoBuyAwait(context, bridge, petId) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发小宠沐浴恢复清洁")
            }
            "like_back" -> {
                PetSocialTask.executeAutoLikeBack(context, bridge, PetAdventureEngine.currentActiveUin, PetAdventureEngine.cachedHireableFriends) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已完成访客回踩送心")
            }
            "active_visit" -> {
                PetSocialTask.executeAutoLikeBack(context, bridge, PetAdventureEngine.currentActiveUin, PetAdventureEngine.cachedHireableFriends) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "已触发主动串门送心")
            }
            "claim_coinbag", "coinbag" -> {
                val claimed = PetSocialTask.claimSelfCoinBagIfNeeded(context, bridge, petId, PetAdventureEngine.currentActiveUin, true) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, if (claimed) "🎉 成功拆开地面金币福袋！" else "小窝地面暂无掉落金币福袋")
            }
            "settle" -> PetAdventureEngine.lastActiveStoryId?.let { sId ->
                PetHiredRecallTask.settleStoryAwait(bridge, sId, petId)
                showToast(context, "已发起探险收益结算")
            } ?: showToast(context, "当前暂无待结算任务")
            "pk_auto" -> {
                val candidates = PetPkTask.collectPkCandidates(context, bridge, petId, PetAdventureEngine.currentActiveUin, PetAdventureEngine.cachedHireableFriends)
                val count = PetPkTask.executeSinglePk(context, bridge, petId, PetAdventureEngine.currentActiveUin, candidates, 999999L, 0L) { PetAdventureEngine.sendLog(context, it) }
                showToast(context, "自动 PK 挑战已执行 (今日第 $count 场)")
            }
            "recall" -> {
                PetAdventureEngine.lastActiveStoryId?.let { sId ->
                    PetHiredRecallTask.recallStoryAwait(bridge, sId, petId)
                    showToast(context, "已发起宠物返程召回")
                } ?: showToast(context, "小宠当前未在外出派遣状态")
            }
        }
    }

    private fun showToast(context: Context, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun dispatchStudy(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        val param = StudyDispatchParam(
            studyMode = PetAdventureEngine.prefStudyMode,
            customSchoolStage = PetAdventureEngine.prefCustomSchoolStage,
            customCourseSubject = PetAdventureEngine.prefCustomCourseSubject,
            customCourseDuration = PetAdventureEngine.prefCustomCourseDuration,
            enableFatigueToAdventure = PetAdventureEngine.enableFatigueToAdventure,
            studyAttributeCursor = PetAdventureEngine.studyAttributeCursor,
            learnedStudySubEvent = PetAdventureEngine.learnedStudySubEvent,
            learnedStudyName = PetAdventureEngine.learnedStudyName
        )
        val res = PetStudyTask.executeAdaptiveStudy(bridge, petId, param) { PetAdventureEngine.sendLog(context, it) }
        if (res.isSuccess) {
            PetAdventureEngine.lastActiveStoryId = res.storyId
            PetAdventureEngine.currentTaskTypeName = "进阶修习中 (${res.courseName ?: "学园课程"})"
            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
            PetAdventureEngine.currentStatusText = "正在进修 ${res.courseName ?: "学园课程"}"
            PetAdventureEngine.sendLog(context, "🎉 [开课成功] 顺利开启 ${res.courseName}！StoryID: ${res.storyId}，学分高速增长中")
            PetAdventureEngine.studyAttributeCursor++
            return true
        }
        if (res.isFatigued && PetAdventureEngine.enableFatigueToAdventure) {
            PetAdventureEngine.sendLog(context, "😴 [疲惫避让] 学园课程标记为疲惫 (${res.fatigueTip ?: "收益降低"})，智能避让转入森林探险...")
            dispatchAdventure(context, bridge, petId)
            return true
        }
        PetAdventureEngine.sendLog(context, "⚠️ [学业调度] 本轮选课未成功开课 (${res.errorMsg ?: "服务端拒绝"})")
        return false
    }

    private suspend fun dispatchWork(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        val hireCandidates = PetWorkTask.selectBestHireCandidatesAwait(
            context = context, bridge = bridge, ownPetId = petId, currentUin = PetAdventureEngine.currentActiveUin,
            enableHireFriend = PetAdventureEngine.enableHireFriend, cachedFriends = PetAdventureEngine.cachedHireableFriends
        ) { PetAdventureEngine.sendLog(context, it) }

        val param = WorkDispatchParam(
            workMode = PetAdventureEngine.prefWorkMode,
            customWorkType = PetAdventureEngine.prefCustomWorkType,
            customWorkDuration = PetAdventureEngine.prefCustomWorkDuration,
            enableHireFriend = PetAdventureEngine.enableHireFriend,
            enableFatigueToAdventure = PetAdventureEngine.enableFatigueToAdventure,
            cachedWorkPlaces = PetAdventureEngine.cachedWorkPlaces,
            hireCandidates = hireCandidates,
            workJobCursor = PetAdventureEngine.workJobCursor,
            learnedWorkSubEvent = PetAdventureEngine.learnedWorkSubEvent,
            learnedWorkName = PetAdventureEngine.learnedWorkName
        )
        val res = PetAdaptiveWorkTask.executeAdaptiveWork(context, bridge, petId, param) { PetAdventureEngine.sendLog(context, it) }
        if (res.isSuccess) {
            PetAdventureEngine.lastActiveStoryId = res.storyId
            PetAdventureEngine.selfDispatchedWorkStoryId = res.storyId
            val hireSuffix = if (res.hiredFriend != null) " · 雇佣:${res.hiredFriend.friendNick.ifEmpty { res.hiredFriend.uin.toString() }}" else ""
            PetAdventureEngine.currentTaskTypeName = "打工中 · ${res.placeName ?: "小镇"} (${res.jobName ?: "兼职"}$hireSuffix)"
            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
            PetAdventureEngine.currentStatusText = "正在 ${res.placeName ?: "小镇"} 进行 ${res.jobName ?: "兼职"}$hireSuffix"
            if (res.hiredFriend != null) {
                PetAdventureEngine.sendLog(context, "🎉 [雇佣打工成功] 顺利雇佣好友「${res.hiredFriend.friendNick.ifEmpty { res.hiredFriend.uin.toString() }}」协同开工 ${res.placeName} - ${res.jobName}！StoryID: ${res.storyId}")
            } else {
                PetAdventureEngine.sendLog(context, "🎉 [打工成功] 顺利开工 ${res.placeName} - ${res.jobName}！StoryID: ${res.storyId}，勤劳致富中")
            }
            PetAdventureEngine.workJobCursor++
            return true
        }
        if (res.isFatigued && PetAdventureEngine.enableFatigueToAdventure) {
            PetAdventureEngine.sendLog(context, "😴 [疲惫避让] 打工岗位标记为疲惫 (${res.fatigueTip ?: "收益降低"})，智能避让转入森林探险...")
            dispatchAdventure(context, bridge, petId)
            return true
        }
        PetAdventureEngine.sendLog(context, "⚠️ [打工调度] 本轮打工未成功开工 (${res.errorMsg ?: "服务端拒绝"})")
        return false
    }

    private suspend fun dispatchAdventure(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        PetAdventureEngine.currentStatusText = "森林探险启程中..."
        PetAdventureEngine.sendLog(context, "🌲 [探险启程] 正在前往神秘森林发起探险巡航...")
        val adventureRes: Pair<Int, String?>? = kotlinx.coroutines.withTimeoutOrNull(8000L) {
            kotlin.coroutines.suspendCoroutine<Pair<Int, String?>> { cont ->
                bridge.startAdventure(petId) { code, storyId, _, _ -> cont.resume(Pair(code, storyId)) }
            }
        }
        val code = adventureRes?.first ?: -99
        val storyId = adventureRes?.second
        if (code == 0 && !storyId.isNullOrEmpty()) {
            PetAdventureEngine.lastActiveStoryId = storyId
            PetAdventureEngine.currentTaskTypeName = "森林探险中"
            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + 1800 * 1000L
            PetAdventureEngine.currentStatusText = "正在神秘森林探险寻宝中"
            PetAdventureEngine.sendLog(context, "🎉 [探险成功] 顺利踏入神秘森林！StoryID: $storyId，奇遇宝藏探索中")
            return true
        }
        PetAdventureEngine.sendLog(context, "⚠️ [探险回包] 森林探险启程未生效 (code=$code)")
        return false
    }
}
