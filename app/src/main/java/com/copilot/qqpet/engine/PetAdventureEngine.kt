package com.copilot.qqpet.engine

import android.content.Context
import android.content.Intent
import com.copilot.qqpet.hook.HookLog as Log
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
class PetAdventureEngine(private var bridge: QQPetDirectBridge) {

    companion object {
        private const val TAG = "PetAdventureEngine"
        private const val NETWORK_TIMEOUT_MS = 8000L
        const val ACTION_ENGINE_LOG = "io.github.congsmile.qqpet.ACTION_ENGINE_LOG"
        const val ACTION_TRIGGER_ACTION = "io.github.congsmile.qqpet.ACTION_TRIGGER_ACTION"
        const val ACTION_UPDATE_CONFIG = "io.github.congsmile.qqpet.ACTION_UPDATE_CONFIG"

        fun calculateHiredProgress(totalSec: Long, remainingSec: Long): Double {
            if (totalSec <= 0L) return 0.0
            val safeRem = remainingSec.coerceIn(0L, totalSec)
            val elapsed = totalSec - safeRem
            return (elapsed.toDouble() / totalSec.toDouble()) * 100.0
        }

        fun shouldTriggerHiredRecall(currentProgress: Double, targetThreshold: Int): Boolean {
            if (targetThreshold <= 0) return false
            return currentProgress >= targetThreshold.toDouble()
        }

        fun isHiredTask(strings: Collection<String>): Boolean {
            val keywords = listOf("被雇佣", "雇佣者", "被雇佣者", "基础工资", "加成奖金", "可获得基础工资")
            return strings.any { s -> keywords.any { k -> s.contains(k) } }
        }
        const val ACTION_SYNC_WORK_PLACES = "io.github.congsmile.qqpet.ACTION_SYNC_WORK_PLACES"
        const val ACTION_SYNC_ACCOUNT_STATUS = "io.github.congsmile.qqpet.ACTION_SYNC_ACCOUNT_STATUS"
        const val EXTRA_LOG_TEXT = "extra_log_text"
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_WORK_PLACES_JSON = "extra_work_places_json"
        const val EXTRA_SCHOOL_DETAILS_JSON = "extra_school_details_json"

        var cachedPetId: String? = null
        var lastActiveStoryId: String? = null
        @Volatile
        var currentActiveUin: String = ""
        @Volatile
        var isLoopRunning = false
        @Volatile
        var lastFatigueSwitchTimeMillis: Long = 0L
        private const val FATIGUE_SWITCH_COOLDOWN_MS = 10 * 60 * 1000L

        // 跨进程配置开关（默认全开）
        @Volatile var enableStudy = true
        @Volatile var enableWork = true
        @Volatile var enableCare = true
        @Volatile var enableAdventure = false
        @Volatile var enableSettle = true
       @Volatile var enableLikeBack = true
       @Volatile var enableClaimCoinBag = true
       @Volatile var enableFatigueToAdventure = true
        @Volatile var enableAutoPk = false
        @Volatile var lastPkTimeMillis: Long = 0L
        @Volatile var pkCooldownMillis: Long = 60 * 1000L
     @Volatile var prefHumanLikeSleep = true
     @Volatile var prefNightSleepMode = true
      @Volatile var prefScreenOffSilent = true
      @Volatile var prefHideQQSettingEntry = false
      @Volatile var prefDebugLog = false
       @Volatile var enableHireFriend = true
       @Volatile var prefHireFriendUinsCsv = ""
       @Volatile var prefPkBlacklistUinsCsv = ""
       @Volatile var prefHiredRecallProgress = 72
       @Volatile var cachedHireableFriends: List<QQPetDirectBridge.HireableFriend> = emptyList()
       @Volatile var enableFriendCare = false
       @Volatile var prefFriendCareEnergyThreshold = 60
       @Volatile var prefFriendCareCleanThreshold = 60

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
       @Volatile var prefCareEnergyThreshold = 60 // 体力进食阈值 (低于设定值立即进食)
       @Volatile var prefCareCleanThreshold = 60 // 清洁洗澡阈值 (低于设定值立即沐浴)

      @Volatile var lastCareTimeMillis: Long = 0L
       @Volatile var lastLikeBackTimeMillis: Long = 0L
       @Volatile var lastCoinBagTimeMillis: Long = 0L
       @Volatile var lastFriendCareTimeMillis: Long = 0L
       private val todayLikedUins = java.util.Collections.synchronizedSet(HashSet<Long>())
       @Volatile private var lastLikeDayKey = ""
       private val todayClaimedBagIds = java.util.Collections.synchronizedSet(HashSet<String>())
       @Volatile private var lastCoinBagDayKey = ""
       @Volatile private var coinBagDailyLimitReached = false

      private fun currentDayKey(): String {
          val cal = java.util.Calendar.getInstance()
          return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
      }

       fun getDailyPkCount(context: Context): Int {
           val todayKey = currentDayKey()
           val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
           val dateKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_DATE, currentActiveUin)
           val countKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_COUNT, currentActiveUin)
           val savedDate = prefs.getString(dateKey, "") ?: ""
           return if (savedDate == todayKey) {
               prefs.getInt(countKey, 0)
           } else {
               prefs.edit()
                   .putString(dateKey, todayKey)
                   .putInt(countKey, 0)
                   .commit()
               0
           }
       }

       fun incrementDailyPkCount(context: Context): Int {
           val todayKey = currentDayKey()
           val current = getDailyPkCount(context)
           val next = current + 1
           val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
           prefs.edit()
               .putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_DATE, currentActiveUin), todayKey)
               .putInt(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_DAILY_COUNT, currentActiveUin), next)
               .commit()
           return next
       }

      private fun syncTodayLikedUins(context: Context) {
           val todayKey = currentDayKey()
           if (lastLikeDayKey != todayKey) {
               todayLikedUins.clear()
               lastLikeDayKey = todayKey
           }
           try {
               val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
               val dateKey = AccountSessionGuard.scopedKey("key_liked_uins_date", currentActiveUin)
               val csvKey = AccountSessionGuard.scopedKey("key_liked_uins_csv", currentActiveUin)
               val savedDay = prefs.getString(dateKey, "") ?: ""
               if (savedDay == todayKey) {
                   val csv = prefs.getString(csvKey, "") ?: ""
                   if (csv.isNotEmpty()) {
                       csv.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { todayLikedUins.add(it) }
                   }
               } else if (savedDay.isNotEmpty()) {
                   prefs.edit().putString(dateKey, todayKey).putString(csvKey, "").commit()
               }
           } catch (_: Throwable) {}
       }

       private fun markFriendLikedToday(context: Context, uin: Long) {
           val todayKey = currentDayKey()
           if (lastLikeDayKey != todayKey) {
               todayLikedUins.clear()
               lastLikeDayKey = todayKey
           }
           todayLikedUins.add(uin)
           try {
               val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
               val dateKey = AccountSessionGuard.scopedKey("key_liked_uins_date", currentActiveUin)
               val csvKey = AccountSessionGuard.scopedKey("key_liked_uins_csv", currentActiveUin)
               val csv = synchronized(todayLikedUins) { todayLikedUins.joinToString(",") }
               prefs.edit()
                   .putString(dateKey, todayKey)
                   .putString(csvKey, csv)
                   .commit()
           } catch (_: Throwable) {}
       }

       private fun syncTodayClaimedBags(context: Context) {
           val todayKey = currentDayKey()
           if (lastCoinBagDayKey != todayKey) {
               todayClaimedBagIds.clear()
               coinBagDailyLimitReached = false
               lastCoinBagDayKey = todayKey
           }
           try {
               val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
               val dateKey = AccountSessionGuard.scopedKey("key_coinbag_date", currentActiveUin)
               val csvKey = AccountSessionGuard.scopedKey("key_coinbag_ids_csv", currentActiveUin)
               val limitKey = AccountSessionGuard.scopedKey("key_coinbag_limit_reached", currentActiveUin)
               val savedDay = prefs.getString(dateKey, "") ?: ""
               if (savedDay == todayKey) {
                   val csv = prefs.getString(csvKey, "") ?: ""
                   if (csv.isNotEmpty()) {
                       csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { todayClaimedBagIds.add(it) }
                   }
                   coinBagDailyLimitReached = prefs.getBoolean(limitKey, false)
               } else if (savedDay.isNotEmpty()) {
                   prefs.edit()
                       .putString(dateKey, todayKey)
                       .putString(csvKey, "")
                       .putBoolean(limitKey, false)
                       .commit()
               }
           } catch (_: Throwable) {}
       }

       private fun markCoinBagHandledToday(context: Context, bagId: String, limitReached: Boolean = false) {
           val todayKey = currentDayKey()
           if (lastCoinBagDayKey != todayKey) {
               todayClaimedBagIds.clear()
               coinBagDailyLimitReached = false
               lastCoinBagDayKey = todayKey
           }
           if (bagId.isNotEmpty()) {
               todayClaimedBagIds.add(bagId)
           }
           if (limitReached) {
               coinBagDailyLimitReached = true
           }
           try {
               val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
               val dateKey = AccountSessionGuard.scopedKey("key_coinbag_date", currentActiveUin)
               val csvKey = AccountSessionGuard.scopedKey("key_coinbag_ids_csv", currentActiveUin)
               val limitKey = AccountSessionGuard.scopedKey("key_coinbag_limit_reached", currentActiveUin)
               val csv = synchronized(todayClaimedBagIds) { todayClaimedBagIds.joinToString(",") }
               prefs.edit()
                   .putString(dateKey, todayKey)
                   .putString(csvKey, csv)
                   .putBoolean(limitKey, coinBagDailyLimitReached)
                   .commit()
           } catch (_: Throwable) {}
       }

       /**
        * 清空与特定账号强绑定的内存态缓存（切换大小号时调用，防止大号三围/已毕业/StoryID 串到小号）
        */
       fun clearAccountBoundMemoryCache() {
           cachedPetId = null
           lastActiveStoryId = null
           currentTaskEndTimeMillis = 0L
           currentTaskTypeName = "进阶修习中"
           cachedSchoolDetails = null
           cachedWorkPlaces = null
           cachedWorkJobs = null
           cachedSchoolCourses = null
           learnedStudySubEvent = null
           learnedStudyName = null
           learnedWorkSubEvent = null
           learnedWorkName = null
          todayLikedUins.clear()
          todayClaimedBagIds.clear()
          coinBagDailyLimitReached = false
          cachedHireableFriends = emptyList()
          QQPetDirectBridge.clearStaticRuntimeCache()
      }

      fun parseHireFriendUins(csv: String = prefHireFriendUinsCsv): Set<Long> {
          if (csv.isBlank()) return emptySet()
          return csv.split(",")
              .mapNotNull { it.trim().toLongOrNull() }
              .filter { it > 0L }
              .toSet()
      }

      fun loadSavedHireFriendUins(context: Context): Set<Long> {
          return try {
              val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
              val scopedKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_UINS, currentActiveUin)
              val csv = prefs.getString(scopedKey, null)
                  ?: prefs.getString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, "")
                  ?: ""
              prefHireFriendUinsCsv = csv
              parseHireFriendUins(csv)
          } catch (_: Throwable) {
              parseHireFriendUins(prefHireFriendUinsCsv)
          }
      }

      fun saveHireFriendUins(context: Context, uins: Collection<Long>) {
          val cleanSet = uins.filter { it > 0L }.toSet()
          val csv = cleanSet.joinToString(",")
          prefHireFriendUinsCsv = csv
          try {
              val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
              val editor = prefs.edit()
                  .putString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, csv)
              if (AccountSessionGuard.isValidUin(currentActiveUin)) {
                  editor.putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_UINS, currentActiveUin), csv)
              }
              editor.commit()
          } catch (_: Throwable) {}
      }

      fun parsePkBlacklistUins(csv: String = prefPkBlacklistUinsCsv): Set<Long> {
          if (csv.isBlank()) return emptySet()
          return csv.split(",")
              .mapNotNull { it.trim().toLongOrNull() }
              .filter { it > 0L }
              .toSet()
      }

      fun loadSavedPkBlacklistUins(context: Context): Set<Long> {
          return try {
              val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
              val scopedKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_BLACKLIST_UINS, currentActiveUin)
              val csv = prefs.getString(scopedKey, null)
                  ?: prefs.getString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, "")
                  ?: ""
              prefPkBlacklistUinsCsv = csv
              parsePkBlacklistUins(csv)
          } catch (_: Throwable) {
              parsePkBlacklistUins(prefPkBlacklistUinsCsv)
          }
      }

      fun savePkBlacklistUins(context: Context, uins: Collection<Long>) {
          val cleanSet = uins.filter { it > 0L }.toSet()
          val csv = cleanSet.joinToString(",")
          prefPkBlacklistUinsCsv = csv
          try {
              val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
              val editor = prefs.edit()
                  .putString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, csv)
              if (AccountSessionGuard.isValidUin(currentActiveUin)) {
                  editor.putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_PK_BLACKLIST_UINS, currentActiveUin), csv)
              }
              editor.commit()
          } catch (_: Throwable) {}
      }

      fun loadCachedHireableFriends(context: Context): List<QQPetDirectBridge.HireableFriend> {
          if (cachedHireableFriends.isNotEmpty()) return cachedHireableFriends
          try {
              val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
              val scopedKey = AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, currentActiveUin)
              val raw = prefs.getString(scopedKey, null)
                  ?: prefs.getString(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, "")
                  ?: ""
              if (raw.isNotBlank()) {
                  val list = raw.lines().mapNotNull { line ->
                      val parts = line.split("\t")
                      val uin = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                      val fNick = parts.getOrNull(1).orEmpty()
                      val pNick = parts.getOrNull(2).orEmpty()
                      val petId = parts.getOrNull(3).orEmpty()
                      val power = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                      val intel = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                      val charm = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                      val idle = parts.getOrNull(7)?.toBooleanStrictOrNull() ?: true
                      val rem = parts.getOrNull(8)?.toLongOrNull() ?: 0L
                      if (uin > 0L && petId.isNotEmpty()) {
                          QQPetDirectBridge.HireableFriend(uin, fNick, pNick, petId, power, intel, charm, idle, rem)
                      } else null
                  }
                  if (list.isNotEmpty()) {
                      cachedHireableFriends = list
                      return list
                  }
              }
          } catch (_: Throwable) {}
          return cachedHireableFriends
      }

      fun saveCachedHireableFriends(context: Context, list: List<QQPetDirectBridge.HireableFriend>) {
          cachedHireableFriends = list
          try {
              val raw = list.joinToString("\n") { f ->
                  val safeFNick = f.friendNick.replace("\t", " ").replace("\n", " ")
                  val safePNick = f.petNick.replace("\t", " ").replace("\n", " ")
                  "${f.uin}\t$safeFNick\t$safePNick\t${f.petId}\t${f.power}\t${f.intel}\t${f.charm}\t${f.isIdle}\t${f.remainingSec}"
              }
              val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
              val editor = prefs.edit().putString(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, raw)
              if (AccountSessionGuard.isValidUin(currentActiveUin)) {
                  editor.putString(AccountSessionGuard.scopedKey(PreferencesHelper.KEY_HIRE_FRIEND_CACHE, currentActiveUin), raw)
              }
              editor.commit()
          } catch (_: Throwable) {}
      }

       /**
        * 将验证通过的本人 petId 同时写入当前 UIN 专属分桶与兼容键
        */
       fun saveScopedPetId(context: Context, petId: String, runtimeUin: String? = null) {
           if (petId.isBlank()) return
           val ownerUin = AccountSessionGuard.extractOwnerUinFromPetId(petId)
               .ifEmpty { runtimeUin?.trim().orEmpty() }
           cachedPetId = petId
           if (AccountSessionGuard.isValidUin(ownerUin)) {
               currentActiveUin = ownerUin
           }
           try {
               val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
               val editor = prefs.edit().putString("key_cached_pet_id", petId)
               if (AccountSessionGuard.isValidUin(ownerUin)) {
                   editor.putString(AccountSessionGuard.scopedKey("key_cached_pet_id", ownerUin), petId)
               }
               editor.commit()
           } catch (_: Throwable) {}
       }

       // 动态嗅探学习到的最新课程与工种（持久化）
       @Volatile var learnedStudySubEvent: Long? = null
        @Volatile var learnedStudyName: String? = null
        @Volatile var learnedWorkSubEvent: Long? = null
        @Volatile var learnedWorkName: String? = null

       // 当前账号已解锁数据缓存（用于 UI 实时置灰/解锁判断）
       @Volatile var cachedSchoolDetails: QQPetDirectBridge.SecondMapDetails? = null
       @Volatile var cachedWorkPlaces: QQPetDirectBridge.SecondMapDetails? = null
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

    /**
     * 校验当前 QQ 登录账号 (UIN) 与内存/本地缓存的 petId 是否归属同一账号。
     * - 若检测到切换了账号（例如从大号切到小号），立即清空大号残留的 petId、三围、学园毕业状态与任务倒计时；
     * - 若同一账号重登或重启，平滑复用该账号分桶下的 petId 缓存，零额外开销。
     */
    fun verifyAndSyncAccountSession(context: Context): String {
        val liveUin = bridge.getCurrentRuntimeUin()
        val prefs = try {
            context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }

        val memPetId = cachedPetId
        val uinChanged = AccountSessionGuard.isValidUin(liveUin) &&
            AccountSessionGuard.isValidUin(currentActiveUin) &&
            liveUin != currentActiveUin
        val petIdMismatch = !memPetId.isNullOrBlank() &&
            !AccountSessionGuard.isPetIdBelongingToUin(memPetId, liveUin)

        if (uinChanged || petIdMismatch) {
            val oldOwner = AccountSessionGuard.extractOwnerUinFromPetId(memPetId).ifEmpty { currentActiveUin }
            Log.w(TAG, "🔄 检测到 QQ 账号切换 ($oldOwner -> $liveUin)，立即清理旧账号缓存并切换分桶！")
            clearAccountBoundMemoryCache()
            currentActiveUin = liveUin
        } else if (AccountSessionGuard.isValidUin(liveUin)) {
            currentActiveUin = liveUin
        }

        val scopedKey = AccountSessionGuard.scopedKey("key_cached_pet_id", liveUin)
        val scopedSaved = prefs?.getString(scopedKey, null)
        val legacySaved = prefs?.getString("key_cached_pet_id", null)

        val resolvedPetId = AccountSessionGuard.resolveActivePetId(
            currentRuntimeUin = liveUin,
            memoryPetId = cachedPetId,
            scopedSavedPetId = scopedSaved,
            legacySavedPetId = legacySaved
        )

        if (!resolvedPetId.isNullOrEmpty()) {
            saveScopedPetId(context, resolvedPetId, liveUin)
        } else {
            cachedPetId = null
        }

        return liveUin
    }

    fun reloadConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            enableStudy = prefs.getBoolean("key_study", true)
            enableWork = prefs.getBoolean("key_work", true)
            enableCare = prefs.getBoolean("key_care", true)
            enableAdventure = prefs.getBoolean("key_adventure", false)
            enableSettle = prefs.getBoolean("key_settle", true)
           enableLikeBack = prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
           enableClaimCoinBag = prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
           enableFatigueToAdventure = prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
            enableAutoPk = prefs.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
           prefStudyMode = prefs.getInt("key_study_mode", 0)
            prefWorkMode = prefs.getInt("key_work_mode", 0)
            prefCustomSchoolStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
            prefCustomCourseSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
            prefCustomCourseDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
           prefCustomWorkType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
           prefCustomWorkDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
           prefCareEnergyThreshold = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
           prefCareCleanThreshold = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
          prefHumanLikeSleep = prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
          prefNightSleepMode = prefs.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
          prefScreenOffSilent = prefs.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
          prefHideQQSettingEntry = prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
          prefDebugLog = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
           enableHireFriend = prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
           enableFriendCare = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
           prefFriendCareEnergyThreshold = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
           prefFriendCareCleanThreshold = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
           com.copilot.qqpet.hook.HookLog.isDebugEnabled = prefDebugLog

           val liveUin = verifyAndSyncAccountSession(context)
           loadSavedHireFriendUins(context)
           loadCachedHireableFriends(context)
           loadSavedPkBlacklistUins(context)
           if (!prefs.contains(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS)) {
               try { prefs.edit().putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72).commit() } catch (_: Throwable) {}
           }
           prefHiredRecallProgress = prefs.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)

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

            val taskEndKey = AccountSessionGuard.scopedKey("key_task_end_time", liveUin)
            val taskTypeKey = AccountSessionGuard.scopedKey("key_task_type", liveUin)
            val savedEndTime = prefs.getLong(taskEndKey, prefs.getLong("key_task_end_time", 0L))
            val savedTaskType = prefs.getString(taskTypeKey, prefs.getString("key_task_type", null))
            if (savedEndTime > System.currentTimeMillis() && !savedTaskType.isNullOrEmpty()) {
                currentTaskEndTimeMillis = savedEndTime
                currentTaskTypeName = savedTaskType
            }
            syncTodayLikedUins(context)
            syncTodayClaimedBags(context)

            Log.i(TAG, "从 SharedPreferences 重新载入配置: 学习=$enableStudy, 打工=$enableWork, 照顾=$enableCare, 冒险=$enableAdventure, 结算=$enableSettle, 学习模式=$prefStudyMode, 打工模式=$prefWorkMode, petId=$cachedPetId, 已学课程=$learnedStudyName($learnedStudySubEvent)")
        } catch (t: Throwable) {
            Log.e(TAG, "加载配置异常: ${t.message}")
        }
    }

    fun updateBridge(newBridge: QQPetDirectBridge) {
        if (newBridge.isReady) {
            this.bridge = newBridge
            Log.i(TAG, "已同步更新发包代理实例为就绪状态")
        }
    }

    fun updateConfig(
        study: Boolean,
        work: Boolean,
        care: Boolean,
        adventure: Boolean,
        settle: Boolean,
        likeBack: Boolean = enableLikeBack,
        claimCoinBag: Boolean = enableClaimCoinBag,
        fatigueToAdventure: Boolean = enableFatigueToAdventure,
        studyMode: Int = prefStudyMode,
        workMode: Int = prefWorkMode,
       schoolStage: Int = prefCustomSchoolStage,
       courseSubject: Int = prefCustomCourseSubject,
       courseDuration: Int = prefCustomCourseDuration,
       workType: Int = prefCustomWorkType,
       workDuration: Int = prefCustomWorkDuration,
       careEnergyThreshold: Int = prefCareEnergyThreshold,
       careCleanThreshold: Int = prefCareCleanThreshold,
      humanLikeSleep: Boolean = prefHumanLikeSleep,
      nightSleepMode: Boolean = prefNightSleepMode,
      screenOffSilent: Boolean = prefScreenOffSilent,
      hideQQSettingEntry: Boolean = prefHideQQSettingEntry,
      debugLog: Boolean = prefDebugLog,
       hireFriend: Boolean = enableHireFriend,
       hireFriendUinsCsv: String = prefHireFriendUinsCsv,
      friendCareEnabled: Boolean = enableFriendCare,
      friendCareEnergyThreshold: Int = prefFriendCareEnergyThreshold,
       friendCareCleanThreshold: Int = prefFriendCareCleanThreshold,
       autoPk: Boolean = enableAutoPk,
       pkBlacklistUinsCsv: String = prefPkBlacklistUinsCsv,
       hiredRecallProgress: Int = prefHiredRecallProgress
  ) {
      enableStudy = study
      enableWork = work
      enableCare = care
      enableAdventure = adventure
      enableSettle = settle
      enableLikeBack = likeBack
      enableClaimCoinBag = claimCoinBag
      enableFatigueToAdventure = fatigueToAdventure
       enableAutoPk = autoPk
      prefStudyMode = studyMode
       prefWorkMode = workMode
       prefCustomSchoolStage = schoolStage
       prefCustomCourseSubject = courseSubject
       prefCustomCourseDuration = courseDuration
       prefCustomWorkType = workType
       prefCustomWorkDuration = workDuration
       prefCareEnergyThreshold = careEnergyThreshold
       prefCareCleanThreshold = careCleanThreshold
      prefHumanLikeSleep = humanLikeSleep
      prefNightSleepMode = nightSleepMode
      prefScreenOffSilent = screenOffSilent
      prefHideQQSettingEntry = hideQQSettingEntry
      prefDebugLog = debugLog
       enableHireFriend = hireFriend
       prefHireFriendUinsCsv = hireFriendUinsCsv
       enableFriendCare = friendCareEnabled
       prefFriendCareEnergyThreshold = friendCareEnergyThreshold
       prefFriendCareCleanThreshold = friendCareCleanThreshold
       prefPkBlacklistUinsCsv = pkBlacklistUinsCsv
       prefHiredRecallProgress = hiredRecallProgress; WakeLockHelper.wakeUpImmediately()
       com.copilot.qqpet.hook.HookLog.isDebugEnabled = debugLog
       Log.d(TAG, "配置已更新: 学习=$study, 打工=$work, 照顾=$care, 冒险=$adventure, 结算=$settle, 学校阶段=$schoolStage, 科目=$courseSubject, 课时=$courseDuration, 工种=$workType, 工时=$workDuration, 体力阈值=$careEnergyThreshold, 清洁阈值=$careCleanThreshold, 好友照料=$friendCareEnabled($friendCareEnergyThreshold/$friendCareCleanThreshold), 雇佣召回=${if (hiredRecallProgress > 0) "${hiredRecallProgress}%" else "关闭"}")
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
                    val nextSleepMs = try {
                        WakeLockHelper.withExecutionWakeLock(context, "MasterCycle", 40_000L) {
                            executeMasterCycle(context)
                        }
                    } catch (t: Throwable) {
                        currentStatusText = "巡检异常，稍后重试"
                        val errDelay = StealthScheduler.calculateIdleCycleDelayMillis(prefHumanLikeSleep)
                        val errSec = errDelay / 1000L
                        sendLog(context, "⚠️ [异常] 巡检报错: ${t.message}，拟人休眠 ${errSec} 秒后重试")
                        errDelay
                    }

                    if (!isLoopRunning) break
                    WakeLockHelper.sleepWithAlarmWakeup(context, nextSleepMs)
                }
            } finally {
                isLoopRunning = false
                Log.w(TAG, "后台循环已退出，重置 isLoopRunning 为 false")
            }
        }
    }

    private suspend fun executeMasterCycle(context: Context): Long {
        reloadConfig(context)
       if (prefNightSleepMode && StealthScheduler.isNightSilentWindow(true)) {
           val sleepMs = StealthScheduler.calculateNightSleepMillis()
           val hours = sleepMs / (3600 * 1000L)
           val mins = (sleepMs % (3600 * 1000L)) / (60 * 1000L)
           currentStatusText = "夜间拟人静默中 · 早晨恢复"
           sendLog(context, "🌙 [夜间静默] 当前处于深夜防风控窗口 (01:30~06:30)，暂停所有后台唤醒与轮转，预计 ${hours}小时${mins}分后恢复")
           return sleepMs
       }
        if (prefScreenOffSilent && !StealthScheduler.isScreenInteractive(context)) {
            val sleepSec = StealthScheduler.calculateIdleCycleDelayMillis(prefHumanLikeSleep) / 1000L
            currentStatusText = "熄屏拟人静默中 · 亮屏恢复"
            sendLog(context, "📱 [熄屏静默] 当前设备屏幕已熄灭，暂停主动请求，拟人休眠 ${sleepSec} 秒直至亮屏")
            return sleepSec * 1000L
        }
       if (!bridge.isReady) {
            currentStatusText = "发包代理连接中..."
            sendLog(context, "⏳ [挂起] QQ 内部发包代理尚未就绪，等待 10 秒...")
            return 10 * 1000L
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
                return 30 * 1000L
            }
            saveScopedPetId(context, fetchedId)
            petId = fetchedId
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
                    storyStatus.storyId?.startsWith("6400") == true -> "小镇打工中"
                    storyStatus.storyId.startsWith("6700") -> "森林探险中"
                    else -> "任务执行中"
                }
                currentTaskTypeName = taskType
                currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
                currentStatusText = "$taskType · 剩余 ${mins}分${secs}秒"
                try {
                    val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                    val liveUin = currentActiveUin
                    prefs.edit()
                        .putLong("key_task_end_time", currentTaskEndTimeMillis)
                        .putString("key_task_type", currentTaskTypeName)
                        .putLong(AccountSessionGuard.scopedKey("key_task_end_time", liveUin), currentTaskEndTimeMillis)
                        .putString(AccountSessionGuard.scopedKey("key_task_type", liveUin), currentTaskTypeName)
                        .commit()
                } catch (_: Throwable) {}

                sendLog(context, "⏳ [状态] 宠物正在$taskType (StoryID: ${storyStatus.storyId})，剩余 $mins 分 $secs 秒 (总计 ${storyStatus.total ?: 0} 秒)")

                // 2.3 检查是否处于好友被雇佣打工中，若开启「被雇佣提前召回」且进度达标，立即执行收益抢跑召回
                val totalSec = storyStatus.total ?: 0L
                val curProgress = calculateHiredProgress(totalSec, rem)
                val isWorkStory = storyStatus.storyId?.startsWith("6400") == true
                if (prefHiredRecallProgress > 0 && totalSec > 0L && isWorkStory) {
                    val processInfo = queryProcessStoryInfoAwait(storyStatus.storyId, petId)
                    val isHiredOrWork = (processInfo.code == 0 && processInfo.isHired) || isWorkStory
                    if (isHiredOrWork) {
                        val targetThresh = prefHiredRecallProgress
                        val progressInt = curProgress.toInt()
                        currentStatusText = "打工中 · 进度 ${progressInt}% · 目标 ${targetThresh}%"
                        sendLog(context, "💼 [打工召回监控] 小宠正处于小镇打工/被雇佣中，当前进度: ${progressInt}% (剩余 ${mins}分${secs}秒)，设定召回阈值: ${targetThresh}%")
                        if (shouldTriggerHiredRecall(curProgress, targetThresh)) {
                            sendLog(context, "💰 [雇佣收益抢跑] 当前打工进度 ${progressInt}% 已达到设定目标 ${targetThresh}%！正在执行提前召回以抢得满额基础工资与最高增益分成...")
                            val (rCode, rErr) = recallStoryAwait(storyStatus.storyId, petId)
                            if (rCode == 0) {
                                sendLog(context, "🎉 [提前召回成功] 宠物已提前回家，正在领取雇佣收益...")
                                delay(800L)
                                val (sCode, _) = settleStoryAwait(storyStatus.storyId, petId)
                                if (sCode == 0) {
                                    sendLog(context, "✅ [雇佣收益入账] 基础工资与最高加成奖金已全额入账！")
                                }
                                lastActiveStoryId = null
                                currentTaskEndTimeMillis = 0L
                                currentTaskTypeName = "已召回回家 (空闲)"
                                currentStatusText = "雇佣收益已锁定 · 待命"
                                return 4000L
                            } else {
                                sendLog(context, "⚠️ [提前召回重试] 召回指令返回 code=$rCode, 说明: ${rErr ?: "未知"}")
                            }
                        }
                    }
                }

                // 2.5 若开启「疲惫时自动转冒险」，且当前正在学习(6100)或打工(6400)，实时检测是否带有疲惫减益 Buff
                if (enableFatigueToAdventure && (storyStatus.storyId.startsWith("6100") || storyStatus.storyId?.startsWith("6400") == true)) {
                    val fatigueRes = queryProcessStoryInfoAwait(storyStatus.storyId, petId)
                    if (fatigueRes.code == 0 && fatigueRes.isFatigued) {
                        val switched = handleFatigueSwitchToAdventure(
                            context = context,
                            petId = petId,
                            activeStoryId = storyStatus.storyId,
                            currentTaskLabel = taskType,
                            tipText = fatigueRes.tipText
                        )
                        if (switched) {
                            return 5 * 1000L
                        }
                    }
                }
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
            } else if (codeSettle == 135075) {
                sendLog(context, "🔄 [结算自愈] 检测到 code=135075 (状态冲突或跨号切换)，正在重新校准真实宠物 ID...")
                recoverFrom135075(context)
            } else {
                sendLog(context, "ℹ️ [结算] 结算回包: code=$codeSettle (无需结算或已领取)")
                lastActiveStoryId = null
                currentTaskEndTimeMillis = 0L
            }
           delay(2000L)
       }

       // 4. 【多业务错峰平摊调度】杜绝瞬时并发突发请求，单周期最多处理 1 项维护性工作
       val now = System.currentTimeMillis()
       var maintenanceDispatched = false

       // 4.1 自动照顾：喂食 + 洗澡 (自身体力与清洁度守护)
       if (enableCare) {
           bridge.refreshProfile()
           val attrs = queryPetAttributesAwait(petId) ?: bridge.getPetAttributes(petId)

           if (attrs != null) {
               val curEnergy = attrs.energy
               val maxEnergy = attrs.maxEnergy
               val curClean = attrs.clean
               val maxClean = attrs.maxClean

               val needFeed = curEnergy in 0.0f..<prefCareEnergyThreshold.toFloat()
               val needBath = curClean in 0.0f..<prefCareCleanThreshold.toFloat()

               if (needFeed || needBath || (now - lastCareTimeMillis > 5 * 60 * 1000L)) {
                   lastCareTimeMillis = now
                   maintenanceDispatched = true

                   if (needFeed) {
                       currentStatusText = "体力偏低 · 立即自动喂食"
                       val (tCode, remain, total) = queryFeedTimesAwait()
                       if (tCode == 0 && total > 0 && remain <= 0) {
                           sendLog(context, "ℹ️ [进食守护] 当前体力 ${curEnergy.toInt()}/${maxEnergy.toInt()} (低于设定阈值 $prefCareEnergyThreshold)，今日喂食次数已用尽")
                       } else {
                           val countDesc = if (tCode == 0 && total > 0) " (今日剩余 $remain/$total 次)" else ""
                           sendLog(context, "🥣 [进食守护] 当前体力 ${curEnergy.toInt()}/${maxEnergy.toInt()} 低于设定阈值 ($prefCareEnergyThreshold)，立即自动喂食$countDesc...")
                           val (fCode, fErr) = feedWithAutoBuyAwait(context, petId)
                           sendLog(context, if (fCode == 0) "✅ [进食守护] 喂食成功！体力已恢复" else "ℹ️ [进食守护] 喂食回包 code=$fCode ${fErr ?: ""}")
                           delay(1200L)
                           bridge.refreshProfile()
                       }
                   }

                   if (needBath) {
                       currentStatusText = "身体脏了 · 立即沐浴清洁"
                       sendLog(context, "🧼 [沐浴守护] 当前清洁度 ${curClean.toInt()}/${maxClean.toInt()} 低于设定阈值 ($prefCareCleanThreshold)，立即香皂沐浴...")
                       val bathRes = bathWithAutoBuyAwait(context, petId)
                       if (bathRes.code == 0) {
                           sendLog(context, "✅ [沐浴守护] 洗澡清洁成功！当前清洁度已升至 ${bathRes.newClean}/${maxClean.toInt()}")
                       } else {
                           sendLog(context, "ℹ️ [沐浴守护] 洗澡回包 code=${bathRes.code} ${bathRes.errorMsg ?: ""}")
                       }
                       delay(1200L)
                       bridge.refreshProfile()
                   }
               }
           } else {
               if (now - lastCareTimeMillis > 3 * 60 * 1000L) {
                   lastCareTimeMillis = now
                   maintenanceDispatched = true
                   currentStatusText = "日常照顾 (喂食+清洁)..."
                   val (tCode, remain, total) = queryFeedTimesAwait()
                   if (tCode == 0 && total > 0 && remain <= 0) {
                       sendLog(context, "ℹ️ [照顾] 今日喂食次数已用尽 (剩余 $remain/$total 次)，跳过喂食")
                   } else {
                       val countDesc = if (tCode == 0 && total > 0) " (今日剩余 $remain/$total 次)" else ""
                       sendLog(context, "🥣 [照顾] 自动喂食补充体力$countDesc...")
                       val (fCode, fErr) = feedWithAutoBuyAwait(context, petId)
                       sendLog(context, if (fCode == 0) "✅ [照顾] 喂食成功！" else "ℹ️ [照顾] 喂食回包 code=$fCode ${fErr ?: ""}")
                       delay(1200L)
                   }

                   sendLog(context, "🧼 [照顾] 自动香皂沐浴提升清洁...")
                   val bathRes = bathWithAutoBuyAwait(context, petId)
                   if (bathRes.code == 0) {
                       sendLog(context, "✅ [照顾] 洗澡成功！当前清洁度: ${bathRes.newClean}/100")
                   } else {
                       sendLog(context, "ℹ️ [照顾] 洗澡回包 code=${bathRes.code} ${bathRes.errorMsg ?: ""}")
                   }
                   delay(1200L)
               }
           }
       }

       // 4.2 若本轮未执行自身照顾，按错峰间隔检查好友福袋
       if (!maintenanceDispatched && enableClaimCoinBag && (now - lastCoinBagTimeMillis > 5 * 60 * 1000L)) {
           lastCoinBagTimeMillis = now
           maintenanceDispatched = true
           executeAutoClaimCoinBag(context, petId, isManual = false)
           delay(1200L)
       }

       // 4.3 若本轮未执行其他维护，按错峰间隔检查访客回踩
       if (!maintenanceDispatched && enableLikeBack && (now - lastLikeBackTimeMillis > 6 * 60 * 1000L)) {
           lastLikeBackTimeMillis = now
           maintenanceDispatched = true
           executeAutoLikeBack(context)
           delay(1200L)
       }

      // 4.4 若本轮未执行其他维护，按错峰间隔检查好友宠物照料 (单轮平摊最多照料 3 位好友)
      if (!maintenanceDispatched && enableFriendCare && (now - lastFriendCareTimeMillis >= 10 * 60 * 1000L)) {
          lastFriendCareTimeMillis = now
          maintenanceDispatched = true
          executeAutoFriendCare(context, petId, isManual = false)
          delay(1200L)
      }

       // 4.5 若本轮未执行其他维护，检查每日自动PK (上限10场，只打打得过的，1~3分钟随机CD)
       if (!maintenanceDispatched && enableAutoPk && getDailyPkCount(context) < 10 && (now - lastPkTimeMillis >= pkCooldownMillis)) {
           lastPkTimeMillis = now
           maintenanceDispatched = true
           val completedCount = executeAutoPkSingleMatch(context, petId, isManual = false)
           if (completedCount in 1..9) {
               val nextCdSec = java.util.concurrent.ThreadLocalRandom.current().nextLong(60L, 180L)
               pkCooldownMillis = nextCdSec * 1000L
               sendLog(context, "⏱️ [自动PK] 本场对决结算完毕，随机冷却拟人休眠 ${nextCdSec} 秒 (1~3分钟) 后进入下一场...")
           } else if (completedCount >= 10) {
               sendLog(context, "🎉 [自动PK] 今日 10 场对决挑战已全部打满，明日将自动重置！")
           }
           delay(1200L)
       }

      // 若当前仍有任务在身，不触发新的外出，睡眠 30 秒以保持倒计时和状态动态刷新
       if (hasActiveTask) {
           val rem = storyStatus.remaining ?: 30L
           val total = storyStatus.total ?: 0L
           var sleepSec = StealthScheduler.calculateTaskSleepSeconds(rem, prefHumanLikeSleep)
           // 若小宠正在打工且开启了提前召回，休眠时间必须精准对齐召回阈值点，达标后绝不可死睡
           if (prefHiredRecallProgress > 0 && total > 0L && storyStatus.storyId?.startsWith("6400") == true) {
               val targetElapsedSec = (total * prefHiredRecallProgress) / 100L
               val currentElapsedSec = total - rem
               val neededSec = targetElapsedSec - currentElapsedSec
               if (neededSec > 0L) {
                   // 未到阈值，睡到目标点（15~120秒微调），避免睡过头
                   sleepSec = neededSec.coerceIn(15L, 120L)
               } else {
                   // 已达到或超过设定阈值！立即采用 10~20 秒拟人微休快速执行召回，绝不回退至数小时长沉睡
                   sleepSec = if (prefHumanLikeSleep) kotlin.random.Random.nextLong(10L, 21L) else 10L
               }
           }
           val minText = String.format(java.util.Locale.CHINA, "%.1f", sleepSec / 60.0)
           sendLog(context, "⏳ [在途任务] 宠物正在进行任务中，精准拟人休眠 ${sleepSec} 秒 (~${minText} 分钟) 直至完成")
           return sleepSec * 1000L
       }

        // 5. 【Round-Robin 智能轮转核心】在已启用的任务中无缝轮换，杜绝互斥冲突
        val availableTasks = mutableListOf<String>()
        if (enableStudy) availableTasks.add("study")
        if (enableWork) availableTasks.add("work")
        if (enableAdventure) availableTasks.add("adventure")

        if (availableTasks.isEmpty()) {
            currentStatusText = "外出开关已全部关闭 · 静默待命"
            sendLog(context, "😴 [轮询] 当前外出项目（打工/学习/探险）全被关闭，10 分钟后再次巡检")
            return 10 * 60 * 1000L
        }

        // 取出当前轮次的任务
        val targetTask = availableTasks[roundRobinCursor % availableTasks.size]
        roundRobinCursor = (roundRobinCursor + 1) % availableTasks.size

        when (targetTask) {
            "study" -> {
                val ok = dispatchAdaptiveStudy(context, petId)
                if (ok) {
                    return 5 * 1000L
                }
            }
            "work" -> {
                val ok = dispatchAdaptiveWork(context, petId)
                if (ok) {
                    return 5 * 1000L
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
                        return 5 * 1000L
                    } else if (codeAdv == 135075) {
                        sendLog(context, "🔄 [探险自愈] 服务端返回 135075，正在校准当前账号宠物 ID 与在途状态...")
                        recoverFrom135075(context)
                    } else {
                        sendLog(context, "ℹ️ [探险跳过] 回包 code=$codeAdv")
                    }
                }
            }
        }

       currentStatusText = "等待下次调度"
       val delayMillis = StealthScheduler.calculateIdleCycleDelayMillis(prefHumanLikeSleep)
       val sec = delayMillis / 1000L
       val minText = String.format(java.util.Locale.CHINA, "%.1f", sec / 60.0)
       currentStatusText = "等待下次调度 (~${minText}分钟)"
       sendLog(context, "😴 [拟人休眠] 当前轮次完成，拟人休眠 ${sec} 秒 (~${minText} 分钟) 后继续自适应调度")
       return delayMillis
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
                else -> {
                    if (enableFatigueToAdventure) {
                        availableCourses.filter { !it.isFatigued }.ifEmpty { availableCourses }
                    } else {
                        availableCourses
                    }
                }
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

            if (enableFatigueToAdventure && targetCourse.isFatigued) {
                val switched = handleFatigueSwitchToAdventure(
                    context = context,
                    petId = petId,
                    activeStoryId = null,
                    currentTaskLabel = "学园选课 (${targetCourse.eventName})",
                    tipText = targetCourse.eventTips.ifEmpty { QQPetDirectBridge.lastSelectEventsFatigueTip }
                )
                return switched
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
                if (enableFatigueToAdventure) {
                    delay(600L)
                    val fatigueRes = queryProcessStoryInfoAwait(storyId, petId)
                    if (fatigueRes.code == 0 && fatigueRes.isFatigued) {
                        handleFatigueSwitchToAdventure(context, petId, storyId, "进修 ${targetCourse.eventName}", fatigueRes.tipText)
                    }
                }

                return true
           } else if (codeSchool == 135075) {
               sendLog(context, "🔄 [选课自愈] 服务端返回 135075，正在重新同步当前账号宠物 ID 与在途任务...")
               recoverFrom135075(context)
               return false
           } else {
               sendLog(context, "⚠️ [动态选课] ${targetCourse.eventName} 报名回包 code=$codeSchool, 服务端说明: ${errorMsg ?: "无"}")
           }
       } else {
           sendLog(context, "⚠️ [动态选课] 服务端动态拉取课程回包 code=$evtCode, 尝试候选池保底...")
       }

        // 若处于疲惫状态且开启疲惫转冒险，绝不使用保底候选池强行上学
        if (enableFatigueToAdventure && QQPetDirectBridge.lastSelectEventsFatigued) {
            return false
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
                if (enableFatigueToAdventure) {
                    delay(600L)
                    val fatigueRes = queryProcessStoryInfoAwait(storyId, petId)
                    if (fatigueRes.code == 0 && fatigueRes.isFatigued) {
                        handleFatigueSwitchToAdventure(context, petId, storyId, "进修 ${course.first}", fatigueRes.tipText)
                    }
                }

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

       val (targetCareerType, placeName) = if (prefCustomWorkType <= 0) {
           val unlockedCareers = cachedWorkPlaces?.stages?.filter { it.limitStatus == 0 }
           if (!unlockedCareers.isNullOrEmpty()) {
               val starTower = unlockedCareers.find { it.stage == 3 }
               if (starTower != null) {
                   Pair(3, starTower.title)
               } else {
                   val pick = unlockedCareers[workJobCursor % unlockedCareers.size]
                   workJobCursor = (workJobCursor + 1) % unlockedCareers.size
                   Pair(pick.stage, pick.title)
               }
           } else {
               Pair(3, "星尘魔法塔")
           }
       } else {
           val matched = cachedWorkPlaces?.stages?.find { it.stage == prefCustomWorkType }
           Pair(prefCustomWorkType, matched?.title ?: "小镇场所#$prefCustomWorkType")
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
               else -> {
                   if (enableFatigueToAdventure) {
                       availableJobs.lastOrNull { !it.isFatigued } ?: availableJobs.last()
                   } else {
                       availableJobs.last()
                   }
               }
           }

           if (enableFatigueToAdventure && targetJob.isFatigued) {
               val switched = handleFatigueSwitchToAdventure(
                   context = context,
                   petId = petId,
                   activeStoryId = null,
                   currentTaskLabel = "小镇求职 (${targetJob.eventName})",
                   tipText = targetJob.eventTips.ifEmpty { QQPetDirectBridge.lastSelectEventsFatigueTip }
               )
               return switched
           }

           val hireCandidates = selectBestHireCandidatesAwait(context, petId)
           currentStatusText = "$placeName: ${targetJob.eventName}"
           sendLog(context, "💼 [小镇上岗] 锁定场所: $placeName (Career=$targetCareerType, 工时偏好=$prefCustomWorkDuration) 锁定岗位: ${targetJob.eventName} (工时:${targetJob.costTime}, subEventType=${targetJob.subEventType})，发起启程...")
           val (codeWork, storyId, errorMsg, hiredFriend) = startWorkWithOptionalHireAwait(
               context = context,
               petId = petId,
               jobName = targetJob.eventName,
               page = 6400L,
               subEventType = targetJob.subEventType,
               hireCandidates = hireCandidates
           )
           if (codeWork == 0 && !storyId.isNullOrEmpty()) {
               lastActiveStoryId = storyId
               recordLearnedWorkJob(context, targetJob.eventName, targetJob.subEventType)
               val hireSuffix = if (hiredFriend != null) " · 雇佣:${hiredFriend.friendNick.ifEmpty { hiredFriend.uin.toString() }}" else ""
               currentTaskTypeName = "打工中 · $placeName (${targetJob.eventName}$hireSuffix)"
               currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
               currentStatusText = "正在 $placeName 进行 ${targetJob.eventName}$hireSuffix"
               if (hiredFriend != null) {
                   sendLog(
                       context,
                       "🎉 [雇佣打工成功] 已成功雇佣最高收益空闲好友「${hiredFriend.friendNick.ifEmpty { hiredFriend.uin.toString() }}」(小宠:${hiredFriend.petNick}, 总资质:${hiredFriend.totalAttr}) 协同开工 $placeName - ${targetJob.eventName}！StoryID: $storyId"
                   )
               } else {
                   sendLog(context, "🎉 [打工成功] 顺利开工 $placeName - ${targetJob.eventName}！StoryID: $storyId，勤劳致富中")
               }
               if (enableFatigueToAdventure) {
                   delay(600L)
                   val fatigueRes = queryProcessStoryInfoAwait(storyId, petId)
                   if (fatigueRes.code == 0 && fatigueRes.isFatigued) {
                       handleFatigueSwitchToAdventure(context, petId, storyId, "打工 ${targetJob.eventName}", fatigueRes.tipText)
                   }
               }

               return true
          } else if (codeWork == 135075) {
              sendLog(context, "🔄 [打工自愈] 服务端返回 135075，正在重新同步当前账号宠物 ID 与在途任务...")
              recoverFrom135075(context)
              return false
          } else {
              sendLog(context, "⚠️ [动态打工] ${targetJob.eventName} 开工回包 code=$codeWork, 服务端说明: ${errorMsg ?: "无"}")
          }
       } else {
           sendLog(context, "⚠️ [动态打工] 服务端动态拉取工种回包 code=$evtCode, 尝试候选池保底...")
       }

       // 若处于疲惫状态且开启疲惫转冒险，绝不使用保底候选池强行打工
       if (enableFatigueToAdventure && QQPetDirectBridge.lastSelectEventsFatigued) {
           return false
       }

       val hireCandidates = selectBestHireCandidatesAwait(context, petId)
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
            val (codeWork, storyId, errorMsg, hiredFriend) = startWorkWithOptionalHireAwait(
                context = context,
                petId = petId,
                jobName = job.first,
                page = job.second,
                subEventType = job.third,
                hireCandidates = hireCandidates
            )
            if (codeWork == 0 && !storyId.isNullOrEmpty()) {
                lastActiveStoryId = storyId
                recordLearnedWorkJob(context, job.first, job.third)
                val hireSuffix = if (hiredFriend != null) " (雇佣:${hiredFriend.friendNick.ifEmpty { hiredFriend.uin.toString() }})" else ""
                currentTaskTypeName = "小镇打工中$hireSuffix"
                currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                if (prefWorkMode == 0) {
                    workJobCursor = (workJobCursor + 1) % 3
                }
                currentStatusText = "正在进行 ${job.first} · $modeDesc$hireSuffix"
                if (hiredFriend != null) {
                    sendLog(context, "🎉 [雇佣打工成功] 已协同好友「${hiredFriend.friendNick.ifEmpty { hiredFriend.uin.toString() }}」顺利开工 ${job.first}！StoryID: $storyId")
                } else {
                    sendLog(context, "🎉 [打工成功] 顺利开工 ${job.first}！StoryID: $storyId")
                }
                if (enableFatigueToAdventure) {
                    delay(600L)
                    val fatigueRes = queryProcessStoryInfoAwait(storyId, petId)
                    if (fatigueRes.code == 0 && fatigueRes.isFatigued) {
                        handleFatigueSwitchToAdventure(context, petId, storyId, "打工 ${job.first}", fatigueRes.tipText)
                    }
                }

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
                   bridge.refreshProfile()
                   val attrs = queryPetAttributesAwait(petId) ?: bridge.getPetAttributes(petId)
                   if (attrs != null) {
                       sendLog(context, "📊 [照顾实测] 当前体力: ${attrs.energy.toInt()}/${attrs.maxEnergy.toInt()}, 清洁: ${attrs.clean.toInt()}/${attrs.maxClean.toInt()}, 心情: ${attrs.mood.toInt()}")
                   }
                   val (tCode, remain, total) = queryFeedTimesAwait()
                   if (tCode == 0 && total > 0 && remain <= 0) {
                       sendLog(context, "ℹ️ [照顾实测] 今日喂食次数已用尽 (剩余 $remain/$total 次)，跳过喂食")
                   } else {
                       val countDesc = if (tCode == 0 && total > 0) " (今日剩余 $remain/$total 次)" else ""
                       sendLog(context, "🥣 [照顾实测] 发起喂食补充体力$countDesc...")
                       val (fCode, fErr) = feedWithAutoBuyAwait(context, petId)
                       sendLog(context, if (fCode == 0) "✅ [照顾实测] 喂食成功！体力已补充" else "ℹ️ [照顾实测] 喂食回包 code=$fCode ${fErr ?: ""}")
                       delay(1200L)
                   }
                   sendLog(context, "🧼 [照顾实测] 发起香皂沐浴...")
                   val bathRes = bathWithAutoBuyAwait(context, petId)
                   if (bathRes.code == 0) {
                       sendLog(context, "✅ [照顾实测] 洗澡成功！清洁度已升至 ${bathRes.newClean}/100 (累计 +${bathRes.addedClean})")
                   } else {
                       sendLog(context, "ℹ️ [照顾实测] 洗澡回包 code=${bathRes.code} ${bathRes.errorMsg ?: ""}")
                   }
                   bridge.refreshProfile()
               }
              "work" -> {
                  val petId = ensurePetId(context) ?: return@launch
                  sendLog(context, "💼 [打工实测] 开始触发自适应打工探测流程...")
                  dispatchAdaptiveWork(context, petId)
              }
                "pk_auto", "pk_10" -> {
                    executeAutoPkSession(context, isContinuous = true)
                }
                "pk", "pk_friend" -> {
                    executeAutoPkSingleMatch(context, ensurePetId(context) ?: "", isManual = true)
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
                    } else if (aCode == 135075) {
                        sendLog(context, "🔄 [冒险自愈] 回包 code=135075，正在自动刷新当前账号宠物 ID 并重试...")
                        val newPetId = recoverFrom135075(context)
                        if (!newPetId.isNullOrEmpty() && newPetId != petId) {
                            val (retryCode, retrySid) = startAdventureAwait(newPetId)
                            if (retryCode == 0 && !retrySid.isNullOrEmpty()) {
                                lastActiveStoryId = retrySid
                                currentTaskTypeName = "森林探险中"
                                currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
                                sendLog(context, "🎉 [冒险实测] 切换新号宠物 ID 后探险成功启程！StoryID: $retrySid")
                            }
                        }
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
                   syncTodayLikedUins(context)
                   val toLike = members.filter { it.canLikeBack && !todayLikedUins.contains(it.uin) }
                   if (toLike.isEmpty()) {
                       sendLog(context, "✅ [互踩实测] 来访的 ${members.size} 位好友今日已全部回踩完毕，无需重复操作")
                       return@launch
                   }
                   sendLog(context, "📊 [互踩实测] 成功拉取到 ${members.size} 条来访记录，其中 ${toLike.size} 位好友尚未回踩")
                   var successCount = 0
                   for (m in toLike) {
                       val name = if (m.nick.isNotEmpty()) m.nick else "${m.uin}"
                       sendLog(context, "🐾 [互踩实测] 正在回踩好友: $name (${m.uin})...")
                       val (lCode, lErr) = sendLikeAwait(m.uin)
                       if (lCode == 0 || lCode == 136202) {
                           markFriendLikedToday(context, m.uin)
                           if (lCode == 0) {
                               successCount++
                               sendLog(context, "✅ [互踩实测] 成功回踩好友 $name！")
                               delay(1000L)
                           } else {
                               sendLog(context, "ℹ️ [互踩实测] 好友 $name 今日已互踩过 (已登记防重)")
                               delay(150L)
                           }
                       } else {
                           sendLog(context, "ℹ️ [互踩实测] 回踩好友 $name 回包: code=$lCode ${lErr ?: ""}")
                           delay(600L)
                       }
                   }
                   sendLog(context, "🎉 [互踩实测] 回踩任务完成！共成功回踩 $successCount 位好友")
                }
                "coinbag" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    executeAutoClaimCoinBag(context, petId, isManual = true)
                }
                "friend_care" -> {
                    val petId = ensurePetId(context) ?: return@launch
                    lastFriendCareTimeMillis = System.currentTimeMillis()
                    executeAutoFriendCare(context, petId, isManual = true)
                }
                "query_work_places" -> {
                    preloadAndBroadcastAccountStatus(context)
                }
                "query_account_status" -> {
                    preloadAndBroadcastAccountStatus(context)
                }
                "inspect" -> {
                   val petId = ensurePetId(context) ?: return@launch
                   sendLog(context, "🔍 [全量数据探测] 开始深度抓取官方全学园与全工种配置...")
                   val map6400 = querySecondMapInfoDetailsAwait(6400L, petId)
                   sendLog(context, "🗺️ [6400地图] code=${map6400.code}, stage=${map6400.currentStage}, count=${map6400.stages.size}")
                   for (s in map6400.stages) {
                       sendLog(context, "   📍 stage=${s.stage}, title='${s.title}', limitStatus=${s.limitStatus}, graduated=${s.isGraduated}")
                   }
                   for (stg in 1..4) {
                       val stgName = when (stg) { 1 -> "初级学园"; 2 -> "中级学园"; 3 -> "高级学园"; 4 -> "进修学园"; else -> "$stg" }
                       val (code, events) = querySelectEventsAwait(6100L, petId, schoolStage = stg, careerType = 0)
                       sendLog(context, "📚 [$stgName] code=$code, 课程数=${events.size}:")
                       for (e in events) {
                           sendLog(context, "   📖 ${e.eventName} | sub=${e.subEventType} | 耗时='${e.costTime}' | 消耗='${e.cost}' | 奖励='${e.reward}' | canDo=${e.canDo} | level=${e.level}")
                       }
                   }
                   for (car in 1..8) {
                       val (code, jobs) = querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = car)
                       sendLog(context, "💼 [Career=$car] code=$code, 岗位数=${jobs.size}:")
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
        verifyAndSyncAccountSession(context)
        var petId = cachedPetId
        if (petId.isNullOrEmpty()) {
            sendLog(context, "⏳ [鉴权] 正在锁定本人宠物 ID...")
            val (codePet, fetchedId) = queryOwnPetAwait()
            if (fetchedId.isNullOrEmpty()) {
                sendLog(context, "❌ [鉴权] 获取宠物 ID 失败 (code=$codePet)，请检查 QQ 登录状态")
                return null
            }
            saveScopedPetId(context, fetchedId)
            petId = fetchedId
            sendLog(context, "✅ [鉴权] 锁定宠物 ID: $petId")
        }
        return petId
    }

    /**
     * 当服务端返回 135075（跨号 petId 不属于当前登录 UIN，或存在未同步的在途/待结算任务）时自动自愈：
     * 1. 强制向 0x95e1_0 查询当前真实登录账号的本人 petId；
     * 2. 若发现 petId 已变更（切换了大小号），立即清空旧号缓存并保存新号 petId；
     * 3. 同步查询一次真实在途任务状态 (0x975a_1)，如实恢复倒计时或触发结算。
     */
    private suspend fun recoverFrom135075(context: Context): String? {
        val (_, realPetId) = queryOwnPetAwait()
        if (!realPetId.isNullOrEmpty()) {
            if (realPetId != cachedPetId) {
                Log.w(TAG, "🔄 [135075自愈] 发现真实本人 petId ($realPetId) 与缓存 ($cachedPetId) 不一致，立即刷新切换！")
                clearAccountBoundMemoryCache()
                saveScopedPetId(context, realPetId)
                sendLog(context, "✅ [账号校准] 已自动切换到当前登录账号的宠物 ID: $realPetId")
            }
            val status = queryStoryStatusAwait(realPetId)
            if (status.code == 0 && !status.storyId.isNullOrEmpty()) {
                lastActiveStoryId = status.storyId
                val rem = status.remaining ?: 0L
                if (rem > 0L) {
                    currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
                } else if (enableSettle) {
                    settleStoryAwait(status.storyId, realPetId)
                    lastActiveStoryId = null
                    currentTaskEndTimeMillis = 0L
                }
            }
            return realPetId
        }
        return cachedPetId
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

    suspend fun queryProcessStoryInfoAwait(
        storyId: String,
        petId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.ProcessStoryFatigueResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryProcessStoryInfo(storyId, petId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.ProcessStoryFatigueResult(-99, false, null, 0, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.ProcessStoryFatigueResult(-99, false, null, 0, t.message)
        }

    /**
     * 检测到疲惫减益时：若正在学习/打工则立即取消召回，并直接改派前往神秘森林冒险（6700）直至疲惫 Buff 刷新
     */
   private suspend fun handleFatigueSwitchToAdventure(
       context: Context,
       petId: String,
       activeStoryId: String?,
       currentTaskLabel: String,
       tipText: String?
   ): Boolean {
        val tipDesc = tipText
            ?.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            ?.replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "疲惫，收益减少"
       if (!activeStoryId.isNullOrEmpty()) {
            currentStatusText = "检测到疲惫 · 正在取消$currentTaskLabel"
            sendLog(context, "😫 [疲惫保护] 检测到小宠「$tipDesc」，立即取消当前$currentTaskLabel (StoryID: $activeStoryId) 并转去森林探险...")
            val (recallCode, recallErr) = recallStoryAwait(activeStoryId, petId)
            if (recallCode == 0) {
                lastActiveStoryId = null
                currentTaskEndTimeMillis = 0L
                sendLog(context, "✅ [疲惫保护] 已提前召回结束$currentTaskLabel，正在转头出发神秘森林探险...")
                delay(1200L)
            } else {
                sendLog(context, "⚠️ [疲惫保护] 召回$currentTaskLabel 回包: code=$recallCode ${recallErr ?: ""}")
                return false
            }
        } else {
            currentStatusText = "检测到疲惫 · 自动转去森林探险"
            sendLog(context, "😫 [疲惫保护] 检测到小宠「$tipDesc」，跳过$currentTaskLabel，直接转头前往神秘森林探险...")
        }

        var (advCode, advStoryId) = startAdventureAwait(petId)
        if (advCode != 0 && !activeStoryId.isNullOrEmpty()) {
            // 若召回后仍有待结算状态阻挡出发，自动补发一次结算后再启程探险
            settleStoryAwait(activeStoryId, petId)
            delay(800L)
            val retry = startAdventureAwait(petId)
            advCode = retry.first
            advStoryId = retry.second
        }

        return if (advCode == 0 && !advStoryId.isNullOrEmpty()) {
            lastActiveStoryId = advStoryId
            currentTaskTypeName = "森林探险中 (疲惫恢复)"
            currentTaskEndTimeMillis = System.currentTimeMillis() + 3600 * 1000L
            currentStatusText = "疲惫恢复中 · 森林探险"
            try {
                val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("key_task_end_time", currentTaskEndTimeMillis)
                    .putString("key_task_type", currentTaskTypeName)
                    .commit()
            } catch (_: Throwable) {}
            lastFatigueSwitchTimeMillis = System.currentTimeMillis()
            QQPetDirectBridge.clearStaticRuntimeCache()
            sendLog(context, "🎉 [疲惫转冒险] 成功转去神秘森林探险 (StoryID: $advStoryId)！待疲惫 Buff 刷新消失后将自动恢复学习/打工")
            true
        } else {
            sendLog(context, "⚠️ [疲惫转冒险] 发起森林探险回包 code=$advCode")
            false
        }
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

    suspend fun feedDetailedAwait(
        petId: String,
        foodId: Long = 0L,
        petUin: String = "",
        foodItemId: String = "",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.FeedDetailResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.feedDetailed(petId, foodId, petUin, foodItemId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.FeedDetailResult(-99, 0, null, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.FeedDetailResult(-99, 0, null, t.message)
        }

    suspend fun fetchFoodInventoryAwait(
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, Pair<Int, Int>, List<QQPetDirectBridge.FoodInventoryItem>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchFoodInventory { code, remain, total, items ->
                        if (cont.isActive) cont.resume(Triple(code, Pair(remain, total), items))
                    }
                }
            } ?: Triple(-99, Pair(0, 0), emptyList())
        } catch (_: Throwable) {
            Triple(-99, Pair(0, 0), emptyList())
        }

    suspend fun queryPetAttributesAwait(
        petId: String,
        isSelf: Boolean = true,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.PetAttributes? =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.queryPetAttributes(petId, isSelf) { _, attrs ->
                        if (cont.isActive) cont.resume(attrs)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }

    suspend fun fetchBathItemConfigAwait(
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, List<QQPetDirectBridge.BathItemConfig>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchBathItemConfig { code, items ->
                        if (cont.isActive) cont.resume(Pair(code, items))
                    }
                }
            } ?: Pair(-99, emptyList())
        } catch (_: Throwable) {
            Pair(-99, emptyList())
        }

    suspend fun fetchBathInventoryAwait(
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Pair<Int, Map<String, Int>> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchBathInventory { code, map ->
                        if (cont.isActive) cont.resume(Pair(code, map))
                    }
                }
            } ?: Pair(-99, emptyMap())
        } catch (_: Throwable) {
            Pair(-99, emptyMap())
        }

    suspend fun buyBathItemAwait(
        petId: String,
        itemId: String,
        count: Int = 5,
        scene: Long = 21L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, Int, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.buyBathItem(petId, itemId, count, scene) { code, orderResult, err ->
                        if (cont.isActive) cont.resume(Triple(code, orderResult, err))
                    }
                }
            } ?: Triple(-99, 0, "超时")
        } catch (t: Throwable) {
            Triple(-99, 0, t.message)
        }

    suspend fun doBathOnceAwait(
        petId: String,
        itemId: String,
        useNum: Int = 1,
        petUin: String = "",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.BathResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.doBathOnce(petId, itemId, useNum, petUin) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.BathResult(-99, -1, 0, -1, false, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.BathResult(-99, -1, 0, -1, false, t.message)
        }

    /**
     * 真实香皂沐浴闭环 (0x9bf1_1 查商品 + 0x9bf2_1 查库存 + 0x9bd0_0 自动买香皂 + 0x9bf3_1 循环搓澡至 100)
     */
    suspend fun bathWithAutoBuyAwait(context: Context, petId: String): QQPetDirectBridge.BathResult {
        val attrs = queryPetAttributesAwait(petId) ?: bridge.getPetAttributes(petId)
        val startClean = attrs?.clean?.toInt() ?: -1
        val maxClean = attrs?.maxClean?.toInt()?.takeIf { it > 0 } ?: 100

        val (_, configs) = fetchBathItemConfigAwait()
        val (_, inventory) = fetchBathInventoryAwait()

        val chosenConfig = configs.firstOrNull { it.cleanValue > 0 } ?: configs.firstOrNull()
        val itemId = chosenConfig?.itemId
            ?: inventory.keys.firstOrNull()
            ?: "2010104"
        val itemName = chosenConfig?.name ?: "香皂片"
        val cleanPerSoap = chosenConfig?.cleanValue?.takeIf { it > 0 } ?: 10
        val defaultBuyCount = chosenConfig?.defaultPurchaseCount?.takeIf { it > 0 } ?: 5
        var balance = inventory[itemId] ?: 0

        if (startClean >= maxClean) {
            sendLog(context, "✨ [沐浴检查] 当前清洁度已满 ($startClean/$maxClean)，无需消耗$itemName (库存: $balance)")
            return QQPetDirectBridge.BathResult(0, startClean, 0, balance, true, null)
        }

        var curClean = if (startClean >= 0) startClean else 0
        var totalAdded = 0
        var steps = 0
        val maxSteps = 12

        while (curClean < maxClean && steps < maxSteps) {
            steps++
            if (balance <= 0) {
                val gapClean = (maxClean - curClean).coerceAtLeast(cleanPerSoap)
                val neededSoaps = ((gapClean + cleanPerSoap - 1) / cleanPerSoap).coerceIn(1, 10)
                val buyCount = maxOf(neededSoaps, defaultBuyCount)
                sendLog(context, "🛒 [自动采购] 背包${itemName}不足 (库存 0)，正在自动采购 $buyCount 份${itemName}...")
                val (buyCode, orderResult, buyErr) = buyBathItemAwait(petId, itemId, buyCount)
                if (buyCode == 0 && (orderResult == 1 || orderResult == 0)) {
                    balance += buyCount
                    sendLog(context, "✅ [自动采购] 成功购入 $buyCount 份${itemName}！继续为小宠搓澡...")
                    delay(400L)
                } else {
                    val reason = if (orderResult == 2) "金币不足" else (buyErr ?: "code=$buyCode, orderResult=$orderResult")
                    sendLog(context, "❌ [自动采购] 购买${itemName}失败: $reason")
                    return QQPetDirectBridge.BathResult(
                        if (buyCode != 0) buyCode else -2,
                        curClean,
                        totalAdded,
                        balance,
                        false,
                        "购买${itemName}失败($reason)"
                    )
                }
            }

            val res = doBathOnceAwait(petId, itemId, 1)
            if (res.code != 0) {
                // 若因库存同步延迟导致报错且尚未尝试采购，则置零库存触发下一轮自动采购
                if (balance > 0 && steps == 1) {
                    balance = 0
                    continue
                }
                return QQPetDirectBridge.BathResult(res.code, curClean, totalAdded, balance, false, res.errorMsg)
            }

            curClean = res.newClean
            totalAdded += res.addedClean
            balance = res.remainBalance
            sendLog(context, "🧼 [搓澡进度] 消耗 1 份$itemName (+${res.addedClean}) -> 清洁度 $curClean/$maxClean (剩余库存: $balance)")

            if (res.isFullClean || curClean >= maxClean) {
                break
            }
            delay(450L)
        }

        // 同步上报一次洗浴完成状态并刷新属性缓存
        try { bathAwait(petId) } catch (_: Throwable) {}
        queryPetAttributesAwait(petId)
        return QQPetDirectBridge.BathResult(0, curClean, totalAdded, balance, curClean >= maxClean, null)
    }

 private suspend fun bathAwait(petId: String, petUin: String = "", timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, ByteArray?> =
     try {
         // 官方洗澡行为分两阶段：阶段一进度 50%，阶段二进度 100%
         try {
             withTimeoutOrNull(timeoutMs) {
                 suspendCancellableCoroutine<Unit> { cont ->
                     bridge.bath(petId, cleanValue = 50, stage = 1, petUin = petUin) { _, _, _ ->
                         if (cont.isActive) cont.resume(Unit)
                     }
                 }
             }
         } catch (_: Throwable) {}
          delay(600L)
          withTimeoutOrNull(timeoutMs) {
              suspendCancellableCoroutine { cont ->
                  bridge.bath(petId, cleanValue = 100, stage = 2, petUin = petUin) { code, data, _ ->
                      if (cont.isActive) cont.resume(Pair(code, data))
                  }
              }
          } ?: Pair(-99, null)
      } catch (_: Throwable) {
          Pair(-99, null)
      }

   suspend fun buyFoodAwait(
       petId: String,
       count: Long = 5L,
       itemType: String = "1",
       timeoutMs: Long = NETWORK_TIMEOUT_MS
   ): Pair<Int, String?> =
       try {
           withTimeoutOrNull(timeoutMs) {
               suspendCancellableCoroutine { cont ->
                   bridge.buyFood(petId, count, itemType) { code, _, errorMsg ->
                       if (cont.isActive) cont.resume(Pair(code, errorMsg))
                   }
               }
           } ?: Pair(-99, "超时")
       } catch (t: Throwable) {
           Pair(-99, t.message)
       }

   suspend fun feedWithAutoBuyAwait(context: Context, petId: String): Pair<Int, String?> {
       val (fCode, _) = feedAwait(petId)
       if (fCode == 1000210) {
           sendLog(context, "🛒 [自动采购] 背包饼干不足 (code=1000210)，立即自动采购 5 份爱心饼干...")
           val (buyCode, buyErr) = buyFoodAwait(petId, 5L, "1")
           if (buyCode == 0) {
               sendLog(context, "✅ [自动采购] 5 份爱心饼干采购入库成功！立即为小宠喂食...")
               delay(500L)
               val (retryCode, _) = feedAwait(petId)
               return Pair(retryCode, if (retryCode == 0) null else "重试喂食回包 code=$retryCode")
           } else {
               sendLog(context, "❌ [自动采购] 采购爱心饼干失败: code=$buyCode, 说明: ${buyErr ?: "金币不足或网络异常"}")
               return Pair(fCode, buyErr)
           }
       }
       return Pair(fCode, null)
   }

  private suspend fun startWorkAwait(
       petId: String,
       jobName: String = "小镇兼职",
       page: Long = 6400L,
       subEventType: Long = 6401L,
       hiredPetId: String = "",
       hiredUin: Long = 0L,
       timeoutMs: Long = NETWORK_TIMEOUT_MS
   ): Triple<Int, String?, String?> =
       try {
           withTimeoutOrNull(timeoutMs) {
               suspendCancellableCoroutine { cont ->
                   bridge.startWork(petId, jobName, page, subEventType, hiredPetId, hiredUin) { code, storyId, _, errorMsg ->
                       if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                   }
               }
           } ?: Triple(-99, null, "网络响应超时")
       } catch (t: Throwable) {
            Triple(-99, null, t.message)
        }

    data class WorkStartWithHireResult(
        val code: Int,
        val storyId: String?,
        val errorMsg: String?,
        val hiredFriend: QQPetDirectBridge.HireableFriend?
    )

    private suspend fun startWorkWithOptionalHireAwait(
        context: Context,
        petId: String,
        jobName: String,
        page: Long,
        subEventType: Long,
        hireCandidates: List<QQPetDirectBridge.HireableFriend>
    ): WorkStartWithHireResult {
        if (enableHireFriend && hireCandidates.isNotEmpty()) {
            for (candidate in hireCandidates) {
                val friendLabel = candidate.friendNick.ifEmpty { candidate.uin.toString() }
                sendLog(
                    context,
                    "🤝 [打工雇佣] 正在尝试雇佣空闲最高收益好友「$friendLabel」(QQ:${candidate.uin}, 小宠:${candidate.petNick}, 总资质:${candidate.totalAttr})..."
                )
               val (code, storyId, errMsg) = startWorkAwait(
                   petId = petId,
                   jobName = jobName,
                   page = page,
                   subEventType = subEventType,
                   hiredPetId = candidate.petId,
                   hiredUin = candidate.uin
               )
               if (code == 0 && !storyId.isNullOrEmpty()) {
                    return WorkStartWithHireResult(code, storyId, errMsg, candidate)
                }
                sendLog(
                    context,
                    "ℹ️ [雇佣顺延] 雇佣好友「$friendLabel」未能生效 (code=$code ${errMsg ?: ""})，尝试下一候选或回退单人打工..."
                )
                delay(350L)
            }
        }
        val (soloCode, soloStoryId, soloErr) = startWorkAwait(
            petId = petId,
            jobName = jobName,
            page = page,
            subEventType = subEventType,
            hiredPetId = ""
        )
        return WorkStartWithHireResult(soloCode, soloStoryId, soloErr, null)
    }

    data class PetFriendsPageResult(
        val code: Int,
        val friends: List<QQPetDirectBridge.HireableFriend>,
        val hasMore: Boolean,
        val nextCookie: String,
        val errorMsg: String?
    )

    suspend fun fetchPetFriendsPageAwait(
        cookie: String = "",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): PetFriendsPageResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchPetFriendsPage(cookie) { code, friends, hasMore, nextCookie, err ->
                        if (cont.isActive) {
                            cont.resume(PetFriendsPageResult(code, friends, hasMore, nextCookie, err))
                        }
                    }
                }
           } ?: PetFriendsPageResult(-99, emptyList(), false, "", "超时")
       } catch (t: Throwable) {
           PetFriendsPageResult(-99, emptyList(), false, "", t.message)
       }

    private suspend fun randomHumanDelay(minMs: Long = 1200L, maxMs: Long = 2400L) {
        val span = (maxMs - minMs).coerceAtLeast(1L)
        val ms = minMs + kotlin.random.Random.nextLong(span)
        delay(ms)
    }

   suspend fun enrichFriendDetailsAwait(
       friend: QQPetDirectBridge.HireableFriend
   ): QQPetDirectBridge.HireableFriend {
        if (friend.petId.isBlank()) return friend
        val details = querySecondMapInfoDetailsAwait(6100L, friend.petId)
        val status = queryStoryStatusAwait(friend.petId)
        val rem = status.remaining ?: 0L
        val idle = (status.code != 0) || rem <= 0L
        val p = if (details.code == 0 && details.power > 0L) details.power else friend.power
        val i = if (details.code == 0 && details.intel > 0L) details.intel else friend.intel
        val c = if (details.code == 0 && details.charm > 0L) details.charm else friend.charm
        return friend.copy(
            power = p,
            intel = i,
            charm = c,
            isIdle = idle,
            remainingSec = if (rem > 0L) rem else 0L
        )
    }

    /**
     * 拉取并合并全量养宠好友列表（0x985d_0 好友宠物榜 + 0x985e_0 互动访客列表）
     * 并对已勾选白名单好友与前列好友探测真实三围资质与空闲状态
     */
    suspend fun fetchAllHireableFriendsAwait(
        context: Context,
        enrichSelectedAndTop: Boolean = true
    ): List<QQPetDirectBridge.HireableFriend> {
        val existingMap = loadCachedHireableFriends(context).associateBy { it.uin }.toMutableMap()
        val mergedMap = LinkedHashMap<Long, QQPetDirectBridge.HireableFriend>()
        val ownUin = bridge.getCurrentRuntimeUin().ifEmpty { currentActiveUin }

        var cookie = ""
        var pageCount = 0
        while (pageCount < 10) {
            pageCount++
            val page = fetchPetFriendsPageAwait(cookie)
            if (page.code != 0) break
            for (f in page.friends) {
                if (f.uin <= 0L || (ownUin.isNotEmpty() && f.uin.toString() == ownUin)) continue
                val old = existingMap[f.uin]
                mergedMap[f.uin] = if (old != null) {
                    f.copy(
                        friendNick = f.friendNick.ifEmpty { old.friendNick },
                        petNick = f.petNick.ifEmpty { old.petNick },
                        power = old.power,
                        intel = old.intel,
                        charm = old.charm,
                        isIdle = old.isIdle,
                        remainingSec = old.remainingSec
                    )
                } else f
            }
           if (!page.hasMore || page.nextCookie.isEmpty() || page.nextCookie == cookie) break
           cookie = page.nextCookie
            randomHumanDelay(1000L, 1800L)
       }

       // 补充来访列表中有 petId 的好友
        val (likeCode, likeMembers) = fetchLikeListAwait("")
        if (likeCode == 0) {
            for (m in likeMembers) {
                if (m.uin <= 0L || m.petId.isBlank() || (ownUin.isNotEmpty() && m.uin.toString() == ownUin)) continue
                if (!mergedMap.containsKey(m.uin)) {
                    val old = existingMap[m.uin]
                    mergedMap[m.uin] = QQPetDirectBridge.HireableFriend(
                        uin = m.uin,
                        friendNick = m.nick.ifEmpty { old?.friendNick.orEmpty() },
                        petNick = old?.petNick.orEmpty(),
                        petId = m.petId,
                        power = old?.power ?: 0L,
                        intel = old?.intel ?: 0L,
                        charm = old?.charm ?: 0L,
                        isIdle = old?.isIdle ?: true,
                        remainingSec = old?.remainingSec ?: 0L
                    )
                }
            }
        }

        // 若本次网络未拉到任何新节点，回退保留已有缓存
        if (mergedMap.isEmpty() && existingMap.isNotEmpty()) {
            mergedMap.putAll(existingMap)
        }

       val selectedUins = loadSavedHireFriendUins(context)
       if (enrichSelectedAndTop && mergedMap.isNotEmpty()) {
           val toEnrichUins = LinkedHashSet<Long>()
           for (u in selectedUins) {
               if (mergedMap.containsKey(u)) toEnrichUins.add(u)
           }
            if (toEnrichUins.isEmpty()) {
                for (f in mergedMap.values) {
                    if (toEnrichUins.size >= 3) break
                    if (f.totalAttr <= 0L) {
                        toEnrichUins.add(f.uin)
                    }
                }
            }
           for (u in toEnrichUins) {
               val cur = mergedMap[u] ?: continue
               mergedMap[u] = enrichFriendDetailsAwait(cur)
                randomHumanDelay(1200L, 2200L)
           }
       }

        val sortedList = mergedMap.values.sortedWith(
            compareByDescending<QQPetDirectBridge.HireableFriend> { selectedUins.contains(it.uin) }
                .thenByDescending { it.totalAttr }
                .thenBy { it.uin }
        )
        saveCachedHireableFriends(context, sortedList)
        return sortedList
    }

    /**
     * 在已勾选的好友白名单中：过滤非勾选好友 -> 实时探测空闲状态与真实三围资质 -> 按总资质从高到低返回可雇佣列表
     */
   private suspend fun selectBestHireCandidatesAwait(
       context: Context,
       ownPetId: String
   ): List<QQPetDirectBridge.HireableFriend> {
       if (!enableHireFriend) return emptyList()
       val selectedUins = loadSavedHireFriendUins(context)
       if (selectedUins.isEmpty()) {
           sendLog(context, "ℹ️ [打工雇佣] 已开启雇佣好友，但当前未勾选好友白名单，本次执行单人打工")
           return emptyList()
       }

       var allFriends = loadCachedHireableFriends(context)
       val hasAllValidPets = selectedUins.all { uin -> allFriends.any { it.uin == uin && it.petId.isNotBlank() } }
       if (!hasAllValidPets) {
           allFriends = fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
       }

       val matched = allFriends.filter { it.uin in selectedUins && it.petId.isNotBlank() && it.petId != ownPetId }
        if (matched.isEmpty()) {
            sendLog(context, "ℹ️ [打工雇佣] 已勾选 ${selectedUins.size} 位白名单好友，暂未匹配到有效宠物 ID，本次执行单人打工")
            return emptyList()
        }

        sendLog(context, "🔍 [打工雇佣] 正在检测已勾选的 ${matched.size} 位白名单好友空闲状态与收益资质...")
        val updatedMap = allFriends.associateBy { it.uin }.toMutableMap()
        val liveChecked = mutableListOf<QQPetDirectBridge.HireableFriend>()
        for (friend in matched) {
            val enriched = enrichFriendDetailsAwait(friend)
            updatedMap[enriched.uin] = enriched
            liveChecked.add(enriched)
            val name = enriched.friendNick.ifEmpty { enriched.uin.toString() }
            val stateStr = if (enriched.isIdle) "空闲可雇" else "忙碌中(剩${enriched.remainingSec / 60}分)"
           sendLog(
               context,
               "   👤 好友「$name」(${enriched.uin}) · 小宠:${enriched.petNick.ifEmpty { "未知" }} · 状态:$stateStr · 实测总资质:${enriched.totalAttr} (力${enriched.power}/智${enriched.intel}/魅${enriched.charm})"
           )
            randomHumanDelay(1000L, 2000L)
       }
       saveCachedHireableFriends(context, updatedMap.values.toList())

        val idleCandidates = liveChecked
            .filter { it.isIdle }
            .sortedByDescending { it.totalAttr }

        if (idleCandidates.isEmpty()) {
            sendLog(context, "ℹ️ [打工雇佣] 已勾选的白名单好友当前均在忙碌中，本次自动转为单人打工")
        } else {
            val best = idleCandidates.first()
            val bestName = best.friendNick.ifEmpty { best.uin.toString() }
            sendLog(
                context,
                "🏆 [雇佣优选] 已锁定空闲且收益资质最高的好友：「$bestName」(总资质:${best.totalAttr})"
            )
        }
        return idleCandidates
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

    data class PreloadedPetData(
        val schoolDetails: QQPetDirectBridge.SecondMapDetails?,
        val schoolCourses: List<QQPetDirectBridge.SelectEvent>?,
        val workPlaces: QQPetDirectBridge.SecondMapDetails?,
        val workJobs: List<QQPetDirectBridge.SelectEvent>?
    )

    suspend fun preloadAccountDataAwait(petId: String): PreloadedPetData {
       val details = querySecondMapInfoDetailsAwait(6100L, petId)
       if (details.code == 0) {
           cachedSchoolDetails = details
       }
       val targetStage = if (details.currentStage > 0) details.currentStage else 3
       val (cCode, courses) = querySelectEventsAwait(6100L, petId, schoolStage = targetStage, careerType = 0)
       if (cCode == 0 && courses.isNotEmpty()) {
           cachedSchoolCourses = courses
       }
        val workMap = querySecondMapInfoDetailsAwait(6400L, petId)
        if (workMap.code == 0) {
            cachedWorkPlaces = workMap
        }
        val targetCareer = if (prefCustomWorkType > 0) prefCustomWorkType else 3
       val (jCode, jobs) = querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = targetCareer)
       if (jCode == 0 && jobs.isNotEmpty()) {
           cachedWorkJobs = jobs
       }
       return PreloadedPetData(cachedSchoolDetails, cachedSchoolCourses, cachedWorkPlaces, cachedWorkJobs)
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


   private suspend fun executeAutoLikeBack(context: Context) {
       try {
           val (code, members) = fetchLikeListAwait()
           if (code == 0 && members.isNotEmpty()) {
               syncTodayLikedUins(context)
               val toLike = members.filter { it.canLikeBack && !todayLikedUins.contains(it.uin) }
               val batchLike = toLike.take(5)
               if (batchLike.isNotEmpty()) {
                   var successCount = 0
                   for (m in batchLike) {
                       val name = if (m.nick.isNotEmpty()) m.nick else "${m.uin}"
                       val (lCode, lErr) = sendLikeAwait(m.uin)
                      if (lCode == 0 || lCode == 136202) {
                          markFriendLikedToday(context, m.uin)
                          if (lCode == 0) {
                              successCount++
                              sendLog(context, "✅ [自动互踩] 成功回踩好友 $name！")
                               randomHumanDelay(2000L, 3500L)
                          } else {
                              Log.i(TAG, "自动回踩好友 $name 今日已互踩过 (已登记防重)")
                               randomHumanDelay(1500L, 2500L)
                          }
                      } else {
                          Log.i(TAG, "自动回踩好友 $name 回包: code=$lCode ${lErr ?: ""}")
                           randomHumanDelay(1800L, 2800L)
                      }
                   }
                    if (successCount > 0) {
                        sendLog(context, "🎉 [自动互踩] 本轮自动回踩完成，成功回踩 $successCount 位好友")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "自动回踩巡检异常: ${t.message}")
        }
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

    suspend fun fetchFriendCoinBagsPageAwait(
        cookie: String = "",
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QuintupleCoinBagPage =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.fetchFriendCoinBags(cookie) { code, bags, count, hasMore, nextCookie, err ->
                        if (cont.isActive) {
                            cont.resume(QuintupleCoinBagPage(code, bags, count, hasMore, nextCookie, err))
                        }
                    }
                }
            } ?: QuintupleCoinBagPage(-99, emptyList(), 0, false, "", "超时")
        } catch (t: Throwable) {
            QuintupleCoinBagPage(-99, emptyList(), 0, false, "", t.message)
        }

    data class QuintupleCoinBagPage(
        val code: Int,
        val bags: List<QQPetDirectBridge.FriendCoinBagInfo>,
        val totalFriendsInPage: Int,
        val hasMore: Boolean,
        val nextCookie: String,
        val errorMsg: String?
    )

    suspend fun snatchCoinBagAwait(
        ownPetId: String,
        coinbagId: String,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): QQPetDirectBridge.SnatchCoinBagResult =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.snatchCoinBag(ownPetId, coinbagId) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } ?: QQPetDirectBridge.SnatchCoinBagResult(-99, coinbagId, 0L, 0, false, "超时")
        } catch (t: Throwable) {
            QQPetDirectBridge.SnatchCoinBagResult(-99, coinbagId, 0L, 0, false, t.message)
        }

    private suspend fun executeAutoClaimCoinBag(context: Context, ownPetId: String, isManual: Boolean) {
        try {
            syncTodayClaimedBags(context)
            if (!isManual && coinBagDailyLimitReached) {
                return
            }
            if (isManual) {
                sendLog(context, "🧧 [好友福袋] 正在扫描好友小窝列表，搜寻可领取的福袋...")
            }

            val discoveredBags = LinkedHashMap<String, QQPetDirectBridge.FriendCoinBagInfo>()
            var cookie = ""
            var totalScannedFriends = 0
            var pageCount = 0
            var firstErrorCode = 0
            var firstErrorMsg: String? = null

            while (pageCount < 6) {
                pageCount++
                val page = fetchFriendCoinBagsPageAwait(cookie)
                if (page.code != 0) {
                    if (pageCount == 1) {
                        firstErrorCode = page.code
                        firstErrorMsg = page.errorMsg
                    }
                    break
                }
                totalScannedFriends += page.totalFriendsInPage
                for (b in page.bags) {
                    if (b.coinbagId.isNotEmpty()) {
                        discoveredBags[b.coinbagId] = b
                    }
                }
               if (!page.hasMore || page.nextCookie.isEmpty() || page.nextCookie == cookie) {
                   break
               }
               cookie = page.nextCookie
                randomHumanDelay(1200L, 2000L)
           }

           if (firstErrorCode != 0 && pageCount == 1) {
                if (isManual) {
                    sendLog(context, "❌ [好友福袋] 拉取好友列表失败: code=$firstErrorCode ${firstErrorMsg ?: ""}")
                }
                return
            }

            val allBags = discoveredBags.values.toList()
            if (allBags.isEmpty()) {
                if (isManual) {
                    sendLog(context, "ℹ️ [好友福袋] 已扫描 $totalScannedFriends 位好友小窝，当前暂无好友掉落福袋")
                }
                return
            }

            val pendingBags = if (isManual) {
                allBags
            } else {
                allBags.filter { !todayClaimedBagIds.contains(it.coinbagId) }.take(5)
            }

            if (pendingBags.isEmpty()) {
                return
            }

            sendLog(
                context,
                "🧧 [好友福袋] 扫描 $totalScannedFriends 位好友，发现 ${pendingBags.size} 个福袋：" +
                    pendingBags.joinToString { "${it.friendNick.ifEmpty { it.friendUin.toString() }}(${it.petNick})" }
            )

            var claimedCount = 0
            var totalGold = 0L
            for (bag in pendingBags) {
                val friendName = bag.friendNick.ifEmpty { bag.friendUin.toString() }
                val res = snatchCoinBagAwait(ownPetId, bag.coinbagId)
                when (res.code) {
                    0 -> {
                        markCoinBagHandledToday(context, bag.coinbagId)
                        if (res.gotGold > 0L) {
                            claimedCount++
                            totalGold += res.gotGold
                            sendLog(context, "🎉 [福袋入账] 成功拆开好友 $friendName 的福袋，获得 +${res.gotGold} 金币！")
                        } else {
                            sendLog(context, "ℹ️ [好友福袋] 好友 $friendName 的福袋已拆过或已被领完 (status=${res.status})")
                        }
                    }
                    135098 -> {
                        markCoinBagHandledToday(context, bag.coinbagId, limitReached = true)
                        sendLog(context, "ℹ️ [好友福袋] 今日领取好友福袋次数已达官方上限 (code=135098)")
                        break
                    }
                    135091, 135092, 135096 -> {
                        markCoinBagHandledToday(context, bag.coinbagId)
                        sendLog(context, "ℹ️ [好友福袋] 好友 $friendName 的福袋已被主人收走或已领空 (code=${res.code})")
                    }
                   else -> {
                       markCoinBagHandledToday(context, bag.coinbagId)
                       sendLog(context, "ℹ️ [好友福袋] 拆取 $friendName 福袋回包: code=${res.code} ${res.errorMsg ?: ""}")
                   }
               }
                randomHumanDelay(2200L, 3800L)
           }

           if (claimedCount > 0) {
                sendLog(context, "🧧 [福袋汇总] 本轮成功拆开 $claimedCount 个好友福袋，共计斩获 +$totalGold 金币！")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "自动领取好友福袋异常: ${t.message}")
        }
    }

    /**
     * 为指定好友宠物自动喂食（支持背包库存检测与自动购买食物）
     */
    private suspend fun feedFriendWithAutoBuyAwait(
        context: Context,
        ownPetId: String,
        friend: QQPetDirectBridge.HireableFriend,
        startEnergy: Int,
        maxEnergy: Int
    ): Pair<Boolean, Int> {
        val friendName = friend.friendNick.ifEmpty { friend.uin.toString() }
        val petLabel = if (friend.petNick.isNotEmpty()) "${friendName}的「${friend.petNick}」" else "好友「$friendName」的宠物"
        val friendUinStr = friend.uin.toString()

       val (invCode, _, foodItems) = fetchFoodInventoryAwait()
       val chosenFood = foodItems.firstOrNull { it.balance > 0 } ?: foodItems.firstOrNull()
       var foodItemId = chosenFood?.itemId ?: ""
       val foodName = chosenFood?.name ?: "爱心饼干"
       // 实测官方规则：给好友宠物投喂 1 份爱心饼干实际增加 10 点体力
       val energyPerFeed = 10
       var balance = chosenFood?.balance ?: -1

       if (invCode == 0 && balance == 0) {
           sendLog(context, "🛒 [好友投喂采购] 背包${foodName}库存为 0，正在为投喂$petLabel 自动采购 5 份${foodName}...")
           val (buyCode, buyErr) = buyFoodAwait(ownPetId, 5L, "1")
           if (buyCode == 0) {
               sendLog(context, "✅ [好友投喂采购] 成功采购 5 份${foodName}！")
                randomHumanDelay(1000L, 1800L)
           } else if (foodItemId.isNotEmpty()) {
               val (mallCode, orderRes, _) = buyBathItemAwait(ownPetId, foodItemId, 5, scene = 12L)
               if (mallCode == 0 && (orderRes == 1 || orderRes == 0)) {
                   sendLog(context, "✅ [好友投喂采购] 通过商城通道成功采购 5 份${foodName}！")
                    randomHumanDelay(1000L, 1800L)
               } else {
                    sendLog(context, "⚠️ [好友投喂采购] 采购${foodName}失败: code=$buyCode ${buyErr ?: ""}")
                }
            }
        }

        var curEnergy = startEnergy
        var feedCount = 0
        val maxFeedsPerFriend = 4

        while (curEnergy < maxEnergy && feedCount < maxFeedsPerFriend) {
            var res = feedDetailedAwait(
                petId = friend.petId,
                foodId = 0L,
                petUin = friendUinStr,
                foodItemId = foodItemId
            )

            if (res.code == 1000210) {
                sendLog(context, "🛒 [好友投喂采购] 投喂$petLabel 时背包食物不足 (code=1000210)，正在自动购买 5 份${foodName}...")
                val (buyCode, buyErr) = buyFoodAwait(ownPetId, 5L, "1")
                if (buyCode == 0) {
                    randomHumanDelay(1000L, 1800L)
                    val (_, _, refreshedItems) = fetchFoodInventoryAwait()
                    val refreshedFood = refreshedItems.firstOrNull { it.balance > 0 } ?: refreshedItems.firstOrNull()
                    if (refreshedFood != null && refreshedFood.itemId.isNotEmpty()) {
                        foodItemId = refreshedFood.itemId
                    }
                    res = feedDetailedAwait(
                        petId = friend.petId,
                        foodId = 0L,
                        petUin = friendUinStr,
                        foodItemId = foodItemId
                    )
                } else {
                    sendLog(context, "❌ [好友投喂] 自动购买食物失败: code=$buyCode ${buyErr ?: ""}")
                    break
                }
            }

            if (res.code == 0) {
                if (res.feedState == 1) {
                    sendLog(context, "ℹ️ [好友投喂] $petLabel 已经吃饱啦 (${res.tipText ?: "无需继续投喂"})")
                    break
                }
                feedCount++
                curEnergy = (curEnergy + energyPerFeed).coerceAtMost(maxEnergy)
                val tipSuffix = if (!res.tipText.isNullOrBlank()) " (${res.tipText})" else ""
                sendLog(context, "🥣 [好友投喂] 成功给$petLabel 投喂 1 份$foodName -> 预计体力 $curEnergy/$maxEnergy$tipSuffix")
                if (curEnergy >= prefFriendCareEnergyThreshold) {
                    break
                }
                randomHumanDelay(1200L, 2000L)
            } else {
                val msg = res.tipText ?: res.errorMsg ?: ""
                sendLog(context, "ℹ️ [好友投喂] 投喂$petLabel 回包: code=${res.code} $msg")
                break
            }
        }

        val refreshed = if (feedCount > 0) queryPetAttributesAwait(friend.petId, isSelf = false) else null
        val finalEnergy = refreshed?.energy?.toInt() ?: curEnergy
        return Pair(feedCount > 0, finalEnergy)
    }

    /**
     * 为指定好友宠物自动搓澡清洁（支持背包香皂库存检测与自动购买香皂）
     */
    private suspend fun bathFriendWithAutoBuyAwait(
        context: Context,
        ownPetId: String,
        friend: QQPetDirectBridge.HireableFriend,
        startClean: Int,
        maxClean: Int
    ): QQPetDirectBridge.BathResult {
        val friendName = friend.friendNick.ifEmpty { friend.uin.toString() }
        val petLabel = if (friend.petNick.isNotEmpty()) "${friendName}的「${friend.petNick}」" else "好友「$friendName」的宠物"
        val friendUinStr = friend.uin.toString()

        val (_, configs) = fetchBathItemConfigAwait()
        val (_, inventory) = fetchBathInventoryAwait()

        val chosenConfig = configs.firstOrNull { it.cleanValue > 0 } ?: configs.firstOrNull()
        val itemId = chosenConfig?.itemId
            ?: inventory.keys.firstOrNull()
            ?: "2010104"
        val itemName = chosenConfig?.name ?: "香皂片"
        val cleanPerSoap = chosenConfig?.cleanValue?.takeIf { it > 0 } ?: 10
        val defaultBuyCount = chosenConfig?.defaultPurchaseCount?.takeIf { it > 0 } ?: 5
        var balance = inventory[itemId] ?: 0

        var curClean = startClean.coerceAtLeast(0)
        var totalAdded = 0
        var steps = 0
        val maxSteps = 10

        while (curClean < maxClean && steps < maxSteps) {
            steps++
            if (balance <= 0) {
                val gapClean = (maxClean - curClean).coerceAtLeast(cleanPerSoap)
                val neededSoaps = ((gapClean + cleanPerSoap - 1) / cleanPerSoap).coerceIn(1, 10)
                val buyCount = maxOf(neededSoaps, defaultBuyCount)
                sendLog(context, "🛒 [好友洗护采购] 背包${itemName}不足 (库存 0)，正在自动采购 $buyCount 份${itemName}用于帮$petLabel 洗澡...")
                val (buyCode, orderResult, buyErr) = buyBathItemAwait(ownPetId, itemId, buyCount, scene = 21L)
               if (buyCode == 0 && (orderResult == 1 || orderResult == 0)) {
                   balance += buyCount
                   sendLog(context, "✅ [好友洗护采购] 成功购入 $buyCount 份${itemName}！继续帮$petLabel 搓澡...")
                    randomHumanDelay(1000L, 1800L)
               } else {
                   val reason = if (orderResult == 2) "金币不足" else (buyErr ?: "code=$buyCode, orderResult=$orderResult")
                   sendLog(context, "❌ [好友洗护采购] 购买${itemName}失败: $reason")
                   return QQPetDirectBridge.BathResult(
                       if (buyCode != 0) buyCode else -2,
                       curClean,
                       totalAdded,
                       balance,
                       false,
                       "购买${itemName}失败($reason)"
                   )
               }
           }

           val res = doBathOnceAwait(friend.petId, itemId, 1, petUin = friendUinStr)
           if (res.code != 0) {
               if (balance > 0 && steps == 1) {
                   balance = 0
                   continue
               }
               sendLog(context, "ℹ️ [好友洗澡] 帮$petLabel 搓澡回包: code=${res.code} ${res.errorMsg ?: ""}")
               return QQPetDirectBridge.BathResult(res.code, curClean, totalAdded, balance, false, res.errorMsg)
           }

           curClean = res.newClean
           totalAdded += res.addedClean
           balance = res.remainBalance
           sendLog(context, "🧼 [好友搓澡] 帮$petLabel 消耗 1 份$itemName (+${res.addedClean}) -> 清洁度 $curClean/$maxClean (剩余库存: $balance)")

           if (res.isFullClean || curClean >= maxClean || curClean >= prefFriendCareCleanThreshold) {
               break
           }
            randomHumanDelay(1200L, 2000L)
       }

       if (totalAdded > 0) {
            try { bathAwait(friend.petId, petUin = friendUinStr) } catch (_: Throwable) {}
        }
        return QQPetDirectBridge.BathResult(0, curClean, totalAdded, balance, curClean >= maxClean, null)
    }

    /**
     * 自动巡检全部养宠好友，当好友宠物体力 < prefFriendCareEnergyThreshold 或清洁度 < prefFriendCareCleanThreshold 时自动帮好友喂食与洗澡
     */
    suspend fun executeAutoFriendCare(
        context: Context,
        ownPetId: String,
        isManual: Boolean = false
    ) {
        try {
            sendLog(
                context,
                "🤝 [好友照料] 开始扫描全部养宠好友状态 (触发阈值: 体力<$prefFriendCareEnergyThreshold 喂食, 清洁<$prefFriendCareCleanThreshold 洗澡)..."
            )
          val friends = fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
              .filter { it.uin > 0L && it.petId.isNotBlank() && it.petId != ownPetId }
               .take(if (isManual) 12 else 3) // 单轮平摊最多巡检 3 位好友，彻底避免突发大面积发包被风控时序聚类

          if (friends.isEmpty()) {
               sendLog(context, "ℹ️ [好友照料] 暂未发现可照料的养宠好友")
               return
           }

           var checkedCount = 0
           var fedFriendCount = 0
           var bathedFriendCount = 0

           for (friend in friends) {
               val attrs = queryPetAttributesAwait(friend.petId, isSelf = false)
               if (attrs == null) {
                    randomHumanDelay(1200L, 2000L)
                   continue
               }
               checkedCount++
               val curEnergy = attrs.energy.toInt()
               val maxEnergy = attrs.maxEnergy.toInt().coerceAtLeast(100)
               val curClean = attrs.clean.toInt()
               val maxClean = attrs.maxClean.toInt().coerceAtLeast(100)
               val friendName = friend.friendNick.ifEmpty { friend.uin.toString() }
               val petName = friend.petNick.ifEmpty { "小宠" }

               val needFeed = curEnergy in 0 until prefFriendCareEnergyThreshold
               val needBath = curClean in 0 until prefFriendCareCleanThreshold

               if (isManual || needFeed || needBath) {
                   sendLog(
                       context,
                       "🔎 [好友检测] 好友「$friendName」(${friend.uin}) · $petName：体力 $curEnergy/$maxEnergy，清洁 $curClean/$maxClean" +
                           if (!needFeed && !needBath) " (状态健康，无需照料)" else ""
                   )
               }

               if (needFeed) {
                   sendLog(
                       context,
                       "🥣 [好友喂食] 好友「$friendName」的「$petName」体力 $curEnergy/$maxEnergy 低于阈值 ($prefFriendCareEnergyThreshold)，开始自动投喂..."
                   )
                   val (fedOk, newEnergy) = feedFriendWithAutoBuyAwait(context, ownPetId, friend, curEnergy, maxEnergy)
                   if (fedOk) {
                       fedFriendCount++
                       sendLog(context, "✅ [好友喂食] 已帮好友「$friendName」的「$petName」补充体力至 $newEnergy/$maxEnergy")
                   }
                    randomHumanDelay(2500L, 4500L)
               }

               if (needBath) {
                   sendLog(
                       context,
                       "🧼 [好友洗澡] 好友「$friendName」的「$petName」清洁度 $curClean/$maxClean 低于阈值 ($prefFriendCareCleanThreshold)，开始自动搓澡..."
                   )
                   val bathRes = bathFriendWithAutoBuyAwait(context, ownPetId, friend, curClean, maxClean)
                   if (bathRes.code == 0 && bathRes.addedClean > 0) {
                       bathedFriendCount++
                       sendLog(context, "✅ [好友洗澡] 已帮好友「$friendName」的「$petName」洗香香，清洁度升至 ${bathRes.newClean}/$maxClean")
                   }
                    randomHumanDelay(1500L, 2500L)
               }

                randomHumanDelay(1500L, 2800L)
           }

            sendLog(
                context,
                "🎉 [好友照料汇总] 本轮共检测 $checkedCount 位养宠好友，成功帮 $fedFriendCount 位好友喂食、帮 $bathedFriendCount 位好友洗澡！"
            )
       } catch (t: Throwable) {
          Log.w(TAG, "自动照料好友宠物异常: ${t.message}")
          if (isManual) {
              sendLog(context, "⚠️ [好友照料] 执行异常: ${t.message}")
          }
      }
  }

    data class PkCandidate(
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

    suspend fun getOwnTotalAttributesAwait(ownPetId: String): Triple<Long, Long, Long> {
        val details = querySecondMapInfoDetailsAwait(6100L, ownPetId)
        if (details.code == 0 && (details.power > 0 || details.intel > 0 || details.charm > 0)) {
            return Triple(details.power, details.intel, details.charm)
        }
        val cached = cachedSchoolDetails
        if (cached != null && (cached.power > 0 || cached.intel > 0 || cached.charm > 0)) {
            return Triple(cached.power, cached.intel, cached.charm)
        }
        // 真机已验证基准 (武力 630, 智力 2551, 魅力 1083，总计 4264)
        return Triple(630L, 2551L, 1083L)
    }

    suspend fun collectPkCandidatesAwait(context: Context, ownPetId: String): List<PkCandidate> {
        val list = mutableListOf<PkCandidate>()
        val seenUins = mutableSetOf<Long>()
        val ownUinStr = bridge.getCurrentRuntimeUin().ifEmpty { currentActiveUin }
        val ownUin = ownUinStr.toLongOrNull() ?: 0L

        // 1. 好友池：从雇佣好友缓存或网络拉取
        var friends = loadCachedHireableFriends(context)
        if (friends.isEmpty()) {
            friends = fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
        }
        for (f in friends) {
            if (f.uin <= 0L || f.uin == ownUin || seenUins.contains(f.uin) || f.petId.isBlank()) continue
            seenUins.add(f.uin)
            list.add(
                PkCandidate(
                    uin = f.uin,
                    petId = f.petId,
                    userNick = f.friendNick.ifEmpty { "好友_${f.uin}" },
                    petNick = f.petNick.ifEmpty { "小宠" },
                    power = f.power,
                    intel = f.intel,
                    charm = f.charm,
                    isFriend = true
                )
            )
        }

        // 3. 陌生人/访客池：从访客回踩列表提取
        try {
            val (likeCode, likeMembers) = fetchLikeListAwait("")
            if (likeCode == 0) {
                for (m in likeMembers) {
                    if (m.uin <= 0L || m.uin == ownUin || seenUins.contains(m.uin) || m.petId.isBlank()) continue
                    seenUins.add(m.uin)
                    list.add(
                        PkCandidate(
                            uin = m.uin,
                            petId = m.petId,
                            userNick = m.nick.ifEmpty { "访客_${m.uin}" },
                            petNick = "小宠",
                            power = 0L,
                            intel = 0L,
                            charm = 0L,
                            isFriend = false
                        )
                    )
                }
            }
        } catch (_: Throwable) {}

        return list
    }

    /**
     * 执行单场 PK 对决，严格筛选三维低于我方的对手
     * @return 返回更新后的今日已完成 PK 场次；若未打或失败返回当前场次
     */
    suspend fun executeAutoPkSingleMatch(
        context: Context,
        ownPetIdParam: String,
        isManual: Boolean,
        specificTargetUin: Long = 0L
    ): Int {
        val currentCount = getDailyPkCount(context)
        if (currentCount >= 10) {
            sendLog(context, "🎉 [自动PK] 今日 10 场 PK 挑战额度已满 ($currentCount/10 场)，无需继续挑战")
            return currentCount
        }

        val ownPetId = ownPetIdParam.ifEmpty { ensurePetId(context) ?: "" }
        if (ownPetId.isEmpty()) {
            sendLog(context, "⚠️ [自动PK] 未能锁定本人小宠 ID，暂缓发起挑战")
            return currentCount
        }

        // 检查自身体力与清洁度 (每次固定扣除 5 体力 + 5 清洁)
        val attrs = queryPetAttributesAwait(ownPetId) ?: bridge.getPetAttributes(ownPetId)
        if (attrs != null) {
            if (attrs.energy < 5 || attrs.clean < 5) {
                sendLog(context, "⚠️ [自动PK] 体力或清洁不足 5 点 (当前: 体力=${attrs.energy.toInt()}, 清洁=${attrs.clean.toInt()})，正在自动补充...")
                if (attrs.energy < 5) feedWithAutoBuyAwait(context, ownPetId)
                if (attrs.clean < 5) bathWithAutoBuyAwait(context, ownPetId)
                delay(1200L)
            }
        }

        // 测算我方自身三维属性
        val (myPower, myIntel, myCharm) = getOwnTotalAttributesAwait(ownPetId)
        val myTotal = myPower + myIntel + myCharm
        sendLog(context, "📊 [PK三维自检] 本人小宠战力: 武力=$myPower, 智力=$myIntel, 魅力=$myCharm -> 综合三维=$myTotal")

        // 搜集候选对手池 (不限好友或陌生人)
        val allCandidates = collectPkCandidatesAwait(context, ownPetId)
        val candidatePool = if (specificTargetUin > 0L) {
            allCandidates.filter { it.uin == specificTargetUin }
        } else {
            allCandidates
        }

        if (candidatePool.isEmpty()) {
            sendLog(context, "⚠️ [自动PK] 候选对手池为空，暂未发现可挑战对象")
            return currentCount
        }

        val blacklistUins = loadSavedPkBlacklistUins(context)
        // 遍历候选对手，只打自己打得过的 (三维属性低于自己，且不在免战黑名单中)
        for (cand in candidatePool) {
            if (cand.uin > 0L && blacklistUins.contains(cand.uin)) {
                sendLog(context, "🚫 [自动PK] 跳过黑名单免战对手「${cand.userNick}」(QQ: ${cand.uin})")
                continue
            }
            // 若该对手三维未初始化，尝试动态探测
            if (cand.power == 0L && cand.intel == 0L && cand.charm == 0L) {
                val details = querySecondMapInfoDetailsAwait(6100L, cand.petId)
                if (details.code == 0) {
                    cand.power = details.power
                    cand.intel = details.intel
                    cand.charm = details.charm
                }
            }

            val oppTotal = cand.totalAttr
            // 核心判定：只打三维总和低于我方的对手
            if (oppTotal > myTotal) {
                sendLog(context, "⏩ [自动PK] 跳过高战对手「${cand.userNick}」(三维: $oppTotal > 我方: $myTotal)")
                continue
            }

            val roleType = if (cand.isFriend) "好友" else "陌生访客"
            val targetLabel = "$roleType「${cand.userNick}」的小宠「${cand.petNick}」"
            sendLog(context, "🎯 [对手锁定] 选中碾压对手: $targetLabel (对手三维: $oppTotal <= 我方: $myTotal)")

            // 1. 查询对手状态 0x9875_1
            val pkStatus = suspendCancellableCoroutine<QQPetDirectBridge.PkStatusInfo?> { cont ->
                bridge.queryFriendPkStatus(cand.uin, cand.petId, ownPetId) { code, info, _ ->
                    if (cont.isActive) {
                        cont.resume(if (code == 0) info else null)
                    }
                }
            }

            if (pkStatus != null) {
                if (pkStatus.rawStatus == 300 && !pkStatus.ongoingStoryId.isNullOrEmpty()) {
                    sendLog(context, "⏳ [自动PK] 发现历史未结算对决 (storyId=${pkStatus.ongoingStoryId})，正在领取收益...")
                    val settleRes = suspendCancellableCoroutine<QQPetDirectBridge.PkSettleResult> { cont ->
                        bridge.settlePkBattle(pkStatus.ongoingStoryId, ownPetId) { res ->
                            if (cont.isActive) cont.resume(res)
                        }
                    }
                    if (settleRes.code == 0) {
                        sendLog(context, "🎉 [自动PK] 历史对决结算完成！获得金币: +${settleRes.goldEarned}")
                    }
                    delay(1200L)
                }

                if (!pkStatus.canPk && pkStatus.rawStatus != 100 && pkStatus.rawStatus != 300) {
                    sendLog(context, "ℹ️ [自动PK] 对手 $targetLabel 当前不可对决 (rawStatus=${pkStatus.rawStatus})，寻找下一位...")
                    continue
                }
            }

            // 2. 发起真实对决 0x975e_1
            sendLog(context, "🚀 [自动PK] 正在发起战斗挑战 (0x975e_1 / eventType=6900)...")
            val battleRes = suspendCancellableCoroutine<QQPetDirectBridge.PkBattleResult> { cont ->
                bridge.startPkBattle(cand.uin, cand.petId, ownPetId) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }

            if (battleRes.code != 0 || battleRes.storyId.isNullOrEmpty()) {
                sendLog(context, "⚠️ [自动PK] 对决回包: code=${battleRes.code}, err=${battleRes.errorMsg ?: "暂不可战"}，跳过")
                continue
            }

            val outcomeStr = if (battleRes.isWin) "🎉 战斗大捷！" else "💥 战斗惜败"
            sendLog(
                context,
                "⚔️ [对决进行中] 我方「${battleRes.myNick}」战力 ${battleRes.myPower} VS 对方「${battleRes.oppNick}」战力 ${battleRes.oppPower} -> 判定: $outcomeStr (storyId=${battleRes.storyId})"
            )

            // 3. 倒计时等待结算
            val waitSec = if (battleRes.leftDurationSec in 1..25) battleRes.leftDurationSec else 5L
            sendLog(context, "⏳ [自动PK] 等待战斗结算倒计时 ${waitSec} 秒...")
            delay(waitSec * 1000L + 500L)

            // 4. 领奖结算 0x9760_1
            val settleRes = suspendCancellableCoroutine<QQPetDirectBridge.PkSettleResult> { cont ->
                bridge.settlePkBattle(battleRes.storyId, ownPetId) { res ->
                    if (cont.isActive) cont.resume(res)
                }
            }

           val newCount = incrementDailyPkCount(context)
           if (settleRes.code == 0) {
               val titleStr = settleRes.title?.ifEmpty { outcomeStr } ?: outcomeStr
               val descStr = if (!settleRes.desc.isNullOrEmpty()) " · ${settleRes.desc}" else ""
                val goldStr = if (settleRes.goldEarned in 1..1_000_000L) "，斩获金币: +${settleRes.goldEarned}" else ""
                sendLog(context, "🏅 [PK结算] 第 $newCount/10 场对决完成: $titleStr$descStr$goldStr！")
           } else {
               sendLog(context, "ℹ️ [PK结算] 第 $newCount/10 场对决已记录 (结算回包 code=${settleRes.code})")
           }

            bridge.refreshProfile()
            return newCount
        }

        sendLog(context, "ℹ️ [自动PK] 遍历完成，暂未发现可安全对决的对手")
        return currentCount
    }

    /**
     * 连续执行每日 PK 对决流程（每天打满 10 场，每次冷却 1~3 分钟随机时间）
     */
    suspend fun executeAutoPkSession(context: Context, isContinuous: Boolean = true) {
        val ownPetId = ensurePetId(context)
        if (ownPetId.isNullOrEmpty()) {
            sendLog(context, "⚠️ [自动PK] 未能锁定本人小宠 ID，终止对决流程")
            return
        }

        var completedCount = getDailyPkCount(context)
        if (completedCount >= 10) {
            sendLog(context, "🎉 [自动PK] 今日 10 场 PK 挑战已全部完成 ($completedCount/10 场)，明日将自动重置！")
            return
        }

        sendLog(context, "⚔️ [自动PK启动] 当前今日已完成 $completedCount/10 场，开始巡检对手并执行稳赢对决...")

        while (completedCount < 10) {
            val afterCount = executeAutoPkSingleMatch(context, ownPetId, isManual = true)
            if (afterCount <= completedCount) {
                sendLog(context, "ℹ️ [自动PK] 当前无合适对手或对手均处于不可战状态，暂缓本轮对决")
                break
            }
            completedCount = afterCount

            if (completedCount >= 10) {
                sendLog(context, "🎉 [自动PK圆满达成] 今日 10 场 PK 挑战已全部打满 (10/10 场)，金币已入账！")
                break
            }

            if (!isContinuous) {
                break
            }

            // 每次 CD 1min~3min 随机时间 (60~180 秒)
            val sleepSec = java.util.concurrent.ThreadLocalRandom.current().nextLong(60L, 180L)
            val minText = String.format(java.util.Locale.CHINA, "%.1f", sleepSec / 60.0)
            sendLog(context, "⏱️ [PK拟人冷却] 第 $completedCount/10 场完成，拟人休眠 ${sleepSec} 秒 (~${minText} 分钟) 后进入下一场...")
            delay(sleepSec * 1000L)
        }
    }

    /**
     * 与指定好友宠物发起 PK 对决全流程实测
     */
    suspend fun executePkWithFriend(
        context: Context,
        targetUin: Long,
        targetFriendNick: String = "",
        targetPetNick: String = "",
        targetPetIdParam: String = ""
    ) {
        val petId = ensurePetId(context) ?: return
        executeAutoPkSingleMatch(context, petId, isManual = true, specificTargetUin = targetUin)
    }

    fun sendLog(context: Context, message: String) {
        Log.i(TAG, message)
        try {
            val intent = Intent(ACTION_ENGINE_LOG).apply {
                setPackage("io.github.congsmile.qqpet")
                putExtra(EXTRA_LOG_TEXT, message)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "发送广播日志失败: ${t.message}")
        }
    }

    fun broadcastAccountStatus(context: Context) {
        try {
            val intent = Intent(ACTION_SYNC_ACCOUNT_STATUS).apply {
                setPackage("io.github.congsmile.qqpet")
            }
            cachedWorkPlaces?.let { places ->
                val workArray = org.json.JSONArray()
                for (s in places.stages) {
                    val obj = org.json.JSONObject().apply {
                        put("stage", s.stage)
                        put("title", s.title)
                        put("limitStatus", s.limitStatus)
                        put("isGraduated", s.isGraduated)
                        put("lockReason", s.lockReason)
                    }
                    workArray.put(obj)
                }
                intent.putExtra(EXTRA_WORK_PLACES_JSON, workArray.toString())
            }
            cachedSchoolDetails?.let { school ->
                val schoolArray = org.json.JSONArray()
                for (s in school.stages) {
                    val obj = org.json.JSONObject().apply {
                        put("stage", s.stage)
                        put("title", s.title)
                        put("limitStatus", s.limitStatus)
                        put("isGraduated", s.isGraduated)
                        put("lockReason", s.lockReason)
                    }
                    schoolArray.put(obj)
                }
                intent.putExtra(EXTRA_SCHOOL_DETAILS_JSON, schoolArray.toString())
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "广播账户动态数据失败: ${t.message}")
        }
    }

    fun preloadAndBroadcastAccountStatus(context: Context) {
        if (cachedWorkPlaces != null && cachedSchoolDetails != null) {
            broadcastAccountStatus(context)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                var petId = cachedPetId
                if (petId.isNullOrEmpty()) {
                    val (_, fetchedId) = queryOwnPetAwait()
                    petId = fetchedId
                }
                if (petId.isNullOrEmpty()) return@launch
                val schoolMap = querySecondMapInfoDetailsAwait(6100L, petId)
                if (schoolMap.code == 0) {
                    cachedSchoolDetails = schoolMap
                }
                val workMap = querySecondMapInfoDetailsAwait(6400L, petId)
                if (workMap.code == 0) {
                    cachedWorkPlaces = workMap
                }
                broadcastAccountStatus(context)
            } catch (t: Throwable) {
                Log.w(TAG, "拉取并广播动态状态异常: ${t.message}")
            }
        }
    }
}
