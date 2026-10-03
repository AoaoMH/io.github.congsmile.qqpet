package com.copilot.qqpet.engine.task

import android.content.Context
import com.copilot.qqpet.engine.ActiveVisitHelper
import com.copilot.qqpet.engine.state.AccountSessionStore
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责访客回踩、主动串门点赞、自家与好友金币福袋拾取，以及好友宠物代喂代洗
 */
object PetSocialTask {

    private const val NETWORK_TIMEOUT_MS = 8000L

    suspend fun fetchLikeListAwait(bridge: QQPetDirectBridge, extra: String = ""): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchLikeList(extra) { code, members, _, _, _, _ ->
                        if (cont.isActive) cont.resume(Pair(code, members))
                    }
                }
            } ?: Pair(-99, emptyList())
        } catch (_: Throwable) {
            Pair(-99, emptyList())
        }

    suspend fun sendLikeAwait(bridge: QQPetDirectBridge, targetUin: Long): Pair<Int, String?> =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.sendLike(targetUin) { code, _, errorMsg ->
                        if (cont.isActive) cont.resume(Pair(code, errorMsg))
                    }
                }
            } ?: Pair(-99, "超时")
        } catch (t: Throwable) {
            Pair(-99, t.message)
        }

    suspend fun executeAutoLikeBack(
        context: Context,
        bridge: QQPetDirectBridge,
        currentUin: String,
        cachedFriends: List<QQPetDirectBridge.HireableFriend>,
        onLog: (String) -> Unit
    ) {
        val (code, members) = fetchLikeListAwait(bridge)
        if (code != 0 || members.isEmpty()) return
        AccountSessionStore.syncTodayLikedUins(context, currentUin)
        val friendUins = cachedFriends.map { it.uin }.filter { it > 0L }.toSet()
        val ownUin = currentUin.toLongOrNull() ?: 0L
        val newStrangers = ActiveVisitHelper.extractStrangersFromVisitors(members.map { it.uin }, ownUin, friendUins)
        if (newStrangers.isNotEmpty()) AccountSessionStore.recordStrangersToPool(context, currentUin, newStrangers)

        val toLike = members.filter { it.canLikeBack && !AccountSessionStore.isFriendLikedToday(context, currentUin, it.uin) }.take(5)
        var successCount = 0
        for (m in toLike) {
            val isFr = friendUins.contains(m.uin)
            val typeDesc = if (isFr) "好友" else "陌生访客"
            val name = if (m.nick.isNotEmpty()) m.nick else "$typeDesc(${m.uin})"
            val (lCode, _) = sendLikeAwait(bridge, m.uin)
            if (lCode == 0 || lCode == 136202) {
                AccountSessionStore.markFriendLikedToday(context, currentUin, m.uin)
                if (lCode == 0) {
                    successCount++
                    onLog("✅ [自动回踩] 成功回赠$typeDesc $name！")
                    delay(2500L)
                } else {
                    delay(1800L)
                }
            }
        }
        if (successCount > 0) onLog("🎉 [自动回踩] 本轮来访回赠完成，成功回礼 $successCount 位小伙伴 (好友+陌生人)")
    }

    suspend fun snatchCoinBagAwait(bridge: QQPetDirectBridge, ownPetId: String, bagId: String): QQPetDirectBridge.SnatchCoinBagResult =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.snatchCoinBag(ownPetId, bagId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.SnatchCoinBagResult(-99, bagId, 0L, 0, false, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.SnatchCoinBagResult(-99, bagId, 0L, 0, false, t.message)
        }

    data class FriendCoinBagsPage(
        val code: Int,
        val bags: List<QQPetDirectBridge.FriendCoinBagInfo>,
        val totalFriends: Int,
        val hasMore: Boolean,
        val nextCookie: String,
        val errorMsg: String?
    )

    suspend fun fetchFriendCoinBagsPageAwait(
        bridge: QQPetDirectBridge,
        cookie: String = ""
    ): FriendCoinBagsPage =
        try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchFriendCoinBags(cookie) { code, bags, count, hasMore, nextCookie, err ->
                        if (cont.isActive) cont.resume(FriendCoinBagsPage(code, bags, count, hasMore, nextCookie, err))
                    }
                }
            } ?: FriendCoinBagsPage(-99, emptyList(), 0, false, "", "超时")
        } catch (t: Throwable) {
            FriendCoinBagsPage(-99, emptyList(), 0, false, "", t.message)
        }

    suspend fun fetchFriendCoinBagsAwait(
        bridge: QQPetDirectBridge,
        cookie: String = ""
    ): Pair<Int, List<QQPetDirectBridge.FriendCoinBagInfo>> {
        val page = fetchFriendCoinBagsPageAwait(bridge, cookie)
        return Pair(page.code, page.bags)
    }

    suspend fun fetchAllFriendCoinBagsAwait(
        bridge: QQPetDirectBridge,
        maxPages: Int = 6
    ): List<QQPetDirectBridge.FriendCoinBagInfo> {
        val discoveredBags = LinkedHashMap<String, QQPetDirectBridge.FriendCoinBagInfo>()
        var cookie = ""
        var pageCount = 0
        while (pageCount < maxPages) {
            pageCount++
            val page = fetchFriendCoinBagsPageAwait(bridge, cookie)
            if (page.code != 0) break
            for (b in page.bags) {
                if (b.coinbagId.isNotEmpty()) {
                    discoveredBags[b.coinbagId] = b
                }
            }
            if (discoveredBags.values.any { it.isSelf }) {
                break
            }
            if (!page.hasMore || page.nextCookie.isEmpty() || page.nextCookie == cookie) {
                break
            }
            cookie = page.nextCookie
            delay(150L)
        }
        return discoveredBags.values.toList()
    }

    suspend fun refreshOwnCoinBagFromProfileAwait(bridge: QQPetDirectBridge): String? =
        try {
            withTimeoutOrNull(4000L) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryOwnPet { _, _, _ ->
                        if (cont.isActive) cont.resume(QQPetDirectBridge.cachedOwnCoinBagId)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

    suspend fun claimSelfCoinBagIfNeeded(
        context: Context,
        bridge: QQPetDirectBridge,
        ownPetId: String,
        currentUin: String,
        isManual: Boolean,
        onLog: (String) -> Unit
    ): Boolean {
        var selfBagId = QQPetDirectBridge.cachedOwnCoinBagId?.trim().orEmpty()
        if (selfBagId.isEmpty()) {
            selfBagId = refreshOwnCoinBagFromProfileAwait(bridge)?.trim().orEmpty()
        }
        val bags = fetchAllFriendCoinBagsAwait(bridge)
        if (bags.isNotEmpty()) {
            val ownBag = bags.firstOrNull { it.isSelf && it.coinbagId.isNotEmpty() }
            if (ownBag != null) {
                selfBagId = ownBag.coinbagId
            } else if (selfBagId.isEmpty()) {
                val matchUinBag = bags.firstOrNull { it.friendUin.toString() == currentUin }
                selfBagId = matchUinBag?.coinbagId ?: if (isManual) bags.first().coinbagId else ""
            }
        }

        if (selfBagId.isNotEmpty()) {
            if (isManual || !AccountSessionStore.isCoinBagClaimedToday(context, currentUin, selfBagId)) {
                onLog("🧧 [金币福袋] 锁定地面福袋 ($selfBagId)，正在自动拾取拆领...")
                val res = snatchCoinBagAwait(bridge, ownPetId, selfBagId)
                if (res.code == 0 || res.code in listOf(135091, 135092, 135096)) {
                    AccountSessionStore.markCoinBagHandledToday(context, currentUin, selfBagId)
                    QQPetDirectBridge.cachedOwnCoinBagId = null
                    if (res.code == 0 && res.gotGold > 0L) {
                        onLog("🎉 [金币福袋] 成功拆开地面金币福袋，斩获 +${res.gotGold} 金币！")
                    } else if (res.code == 0) {
                        onLog("🎉 [金币福袋] 成功开启地面金币福袋！")
                    } else {
                        onLog("ℹ️ [金币福袋] 福袋今日已开启或已领完 (${res.code})")
                    }
                    return true
                } else {
                    onLog("⚠️ [金币福袋] 拾取福袋未成功 (code=${res.code}, err=${res.errorMsg ?: "服务端拒绝"})")
                }
                delay(1800L)
            }
        } else if (isManual) {
            onLog("ℹ️ [金币福袋] 当前小窝地面未发现可领取的金币福袋")
        }
        return false
    }
}
