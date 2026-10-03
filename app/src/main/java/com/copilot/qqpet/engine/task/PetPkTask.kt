package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 负责每日 10 场 PK 自动对决、战力数值裁决、黑名单免战与对手筛选
 */
object PetPkTask {

    data class CandidateItem(
        val uin: Long,
        val petId: String,
        val userNick: String,
        val petNick: String,
        var power: Long = 0L,
        var intel: Long = 0L,
        var charm: Long = 0L,
        val isFriend: Boolean = true
    ) {
        val totalAttr: Long get() = power + intel + charm
    }

    suspend fun collectPkCandidates(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        cachedFriends: List<QQPetDirectBridge.HireableFriend>
    ): List<CandidateItem> {
        val list = mutableListOf<CandidateItem>()
        val seenUins = mutableSetOf<Long>()
        val ownUin = currentUin.toLongOrNull() ?: 0L

        for (f in cachedFriends) {
            if (f.uin <= 0L || f.uin == ownUin || seenUins.contains(f.uin) || f.petId.isBlank()) continue
            seenUins.add(f.uin)
            list.add(CandidateItem(f.uin, f.petId, f.friendNick.ifEmpty { "好友_${f.uin}" }, f.petNick.ifEmpty { "小宠" }, f.power, f.intel, f.charm, true))
        }
        try {
            val likeRes = suspendCancellableCoroutine<List<QQPetDirectBridge.LikeMember>> { cont ->
                bridge.fetchLikeList("") { code, members, _, _, _, _ ->
                    if (cont.isActive) cont.resume(if (code == 0) members else emptyList())
                }
            }
            for (m in likeRes) {
                if (m.uin <= 0L || m.uin == ownUin || seenUins.contains(m.uin) || m.petId.isBlank()) continue
                seenUins.add(m.uin)
                list.add(CandidateItem(m.uin, m.petId, m.nick.ifEmpty { "访客_${m.uin}" }, "小宠", 0L, 0L, 0L, false))
            }
        } catch (_: Throwable) {}
        return list
    }

    private suspend fun challengeOpponent(
        bridge: QQPetDirectBridge,
        ownPetId: String,
        cand: CandidateItem,
        onLog: (String) -> Unit
    ): QQPetDirectBridge.PkSettleResult? {
        val battleRes = suspendCancellableCoroutine<QQPetDirectBridge.PkBattleResult> { cont ->
            bridge.startPkBattle(cand.uin, cand.petId, ownPetId) { res ->
                if (cont.isActive) cont.resume(res)
            }
        }
        if (battleRes.code != 0 || battleRes.storyId.isNullOrEmpty()) {
            onLog("⚠️ [自动PK] 对决回包: code=${battleRes.code}, err=${battleRes.errorMsg ?: "暂不可战"}，跳过")
            return null
        }
        val outcomeStr = if (battleRes.isWin) "🎉 战斗大捷！" else "💥 战斗惜败"
        onLog("⚔️ [对决进行中] 我方「${battleRes.myNick}」战力 ${battleRes.myPower} VS 对方「${battleRes.oppNick}」战力 ${battleRes.oppPower} -> 判定: $outcomeStr")
        val waitSec = if (battleRes.leftDurationSec in 1..25) battleRes.leftDurationSec else 5L
        delay(waitSec * 1000L + 500L)
        return suspendCancellableCoroutine { cont ->
            bridge.settlePkBattle(battleRes.storyId, ownPetId) { res ->
                if (cont.isActive) cont.resume(res)
            }
        }
    }

    suspend fun executeSinglePk(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        candidates: List<CandidateItem>,
        myTotal: Long,
        specificTargetUin: Long = 0L,
        onLog: (String) -> Unit
    ): Int {
        val currentCount = AccountSessionStore.getDailyPkCount(context, currentUin)
        if (currentCount >= 10) return currentCount
        val pool = if (specificTargetUin > 0L) candidates.filter { it.uin == specificTargetUin } else candidates
        val blacklist = AccountSessionStore.loadSavedPkBlacklistUins(context, currentUin)

        for (cand in pool) {
            if (cand.uin > 0L && blacklist.contains(cand.uin)) continue
            if (cand.totalAttr > myTotal) continue
            onLog("🎯 [对手锁定] 选中碾压对手: 「${cand.userNick}」的小宠「${cand.petNick}」(对手三维: ${cand.totalAttr} <= 我方: $myTotal)")
            val settle = challengeOpponent(bridge, ownPetId, cand, onLog)
            if (settle != null) {
                val newCount = AccountSessionStore.incrementDailyPkCount(context, currentUin)
                val goldStr = if (settle.goldEarned in 1..1_000_000L) "，斩获金币: +${settle.goldEarned}" else ""
                onLog("🏅 [PK结算] 第 $newCount/10 场对决完成: ${settle.title ?: "大捷"}$goldStr！")
                bridge.refreshProfile()
                return newCount
            }
        }
        return currentCount
    }
}
