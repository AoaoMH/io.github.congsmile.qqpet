package com.copilot.qqpet.protocol

import android.util.Base64
import com.copilot.qqpet.hook.HookLog as Log
import com.copilot.qqpet.engine.AccountSessionGuard
import java.nio.charset.StandardCharsets
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * QQ 宠物宿主反射发包桥接器
 * 基于 Android 手机 QQ 内部 PetPbDelegate / delegate.l 的 OIDB / SSO 发包通道
 */
class QQPetDirectBridge(private val classLoader: ClassLoader) {

    data class SelectEvent(
        val eventName: String,
        val subEventType: Long,
        val canDo: Boolean,
        val level: Int = 0,
        val cost: String = "",
        val costTime: String = "",
        val reward: String = "",
        val rewardExtra: String = "",
        val eventTips: String = "",
        val isOwnerNeedCare: Boolean = false,
        val isFatigued: Boolean = false
    )

   data class SchoolStageInfo(
       val stage: Int,
       val title: String,
       val limitStatus: Int,
       val isGraduated: Boolean = false,
       val lockReason: String = ""
   )

    data class SecondMapDetails(
        val code: Int,
        val currentStage: Int,
        val lastSubEvent: Long,
        val stages: List<SchoolStageInfo>,
        val power: Long = 0L,
        val intel: Long = 0L,
        val charm: Long = 0L
    )

   data class LikeMember(
       val uin: Long,
       val nick: String,
       val headerUrl: String,
       val timestamp: Long,
       val desc: String,
       val canLikeBack: Boolean,
       val petId: String = ""
   )

   data class PetAttributes(
       val energy: Float,
       val maxEnergy: Float = 100f,
       val clean: Float,
       val maxClean: Float = 100f,
       val mood: Float = 0f
   )

   data class BathItemConfig(
       val itemId: String,
       val name: String,
       val gold: Int,
       val cleanValue: Int,
       val defaultPurchaseCount: Int
   )

   data class BathResult(
       val code: Int,
       val newClean: Int,
       val addedClean: Int,
       val remainBalance: Int,
       val isFullClean: Boolean,
       val errorMsg: String? = null
   )

   data class FriendCoinBagInfo(
       val friendUin: Long,
       val friendNick: String,
       val friendPetId: String,
       val petNick: String,
       val coinbagId: String
   )

   data class SnatchCoinBagResult(
       val code: Int,
       val coinbagId: String,
       val gotGold: Long,
       val status: Int,
       val alreadyOpened: Boolean,
       val errorMsg: String? = null
   )

   data class ProcessStoryFatigueResult(
       val code: Int,
       val isFatigued: Boolean,
       val tipText: String? = null,
       val eventType: Int = 0,
       val errorMsg: String? = null,
       val isHired: Boolean = false
   )

   data class HireableFriend(
       val uin: Long,
       val friendNick: String,
       val petNick: String,
       val petId: String,
       val power: Long = 0L,
       val intel: Long = 0L,
       val charm: Long = 0L,
       val isIdle: Boolean = true,
       val remainingSec: Long = 0L
   ) {
       val totalAttr: Long get() = power + intel + charm
   }

   data class FoodInventoryItem(
       val itemId: String,
       val name: String,
       val balance: Int,
       val energyValue: Int = 20
   )

  data class FeedDetailResult(
      val code: Int,
      val feedState: Int = 0,
      val tipText: String? = null,
      val errorMsg: String? = null
  )

   data class PkStatusInfo(
       val canPk: Boolean,
       val rawStatus: Int,
       val ongoingStoryId: String? = null,
       val remainingSec: Long = 0L
   )

   data class PkBattleResult(
       val code: Int,
       val storyId: String?,
       val myPower: Int = 0,
       val oppPower: Int = 0,
       val myNick: String = "",
       val oppNick: String = "",
       val isWin: Boolean = false,
       val leftDurationSec: Long = 0L,
       val errorMsg: String? = null
   )

   data class PkSettleResult(
       val code: Int,
       val goldEarned: Long = 0L,
       val title: String? = null,
       val desc: String? = null,
       val errorMsg: String? = null
   )

  companion object {
       private const val TAG = "QQPetDirectBridge"
        private const val INTERFACE_CLASS = "com.tencent.ergo.hostdelegate.pb.PetPbDelegate"
        private const val OBSERVER_CLASS = "com.tencent.ergo.hostdelegate.pb.PetPbDelegate\$a"
        private const val DELEGATE_PKG = "com.tencent.mobileqq.qqpet.delegate."

        @Volatile
        var resolvedDelegateClass: Class<*>? = null
            private set
        @Volatile
        var resolvedSendMethodName: String = "c"
            private set
        @Volatile
        var cachedPetAttributes: PetAttributes? = null
            private set
        @Volatile
        var lastFatigueDetected: Boolean = false
            private set
        @Volatile
        var lastFatigueTip: String? = null
            private set
        @Volatile
        var lastSelectEventsFatigued: Boolean = false
            private set
        @Volatile
        var lastSelectEventsFatigueTip: String? = null
            private set

       fun clearStaticRuntimeCache() {
           cachedPetAttributes = null
           lastFatigueDetected = false
           lastFatigueTip = null
           lastSelectEventsFatigued = false
           lastSelectEventsFatigueTip = null
       }

        private val ENERGY_COST_REGEX = Regex("""体力\d*\(当前(\d+)\)""")
        private val CLEAN_COST_REGEX = Regex("""清洁\d*\(当前(\d+)\)""")

        fun parseCurrentAttrsFromCost(costText: String?): Pair<Float?, Float?> {
            if (costText.isNullOrBlank()) return Pair(null, null)
            val energyMatch = ENERGY_COST_REGEX.find(costText)
            val cleanMatch = CLEAN_COST_REGEX.find(costText)
            val energy = energyMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val clean = cleanMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
            return Pair(energy, clean)
        }

        fun updateCachedAttributesFromCost(costText: String?) {
            val (curEnergy, curClean) = parseCurrentAttrsFromCost(costText)
            if (curEnergy != null || curClean != null) {
                val old = cachedPetAttributes
                val newEnergy = curEnergy ?: old?.energy ?: 0f
                val newClean = curClean ?: old?.clean ?: 0f
                val maxEnergy = old?.maxEnergy ?: 100f
                val maxClean = old?.maxClean ?: 100f
                val mood = old?.mood ?: 100f
                cachedPetAttributes = PetAttributes(newEnergy, maxEnergy, newClean, maxClean, mood)
                Log.d(TAG, "从官方岗位/课程 cost 顺风车同步三围: 体力=$newEnergy/$maxEnergy, 清洁=$newClean/$maxClean")
            }
        }

       fun containsFatigueKeyword(text: String?): Boolean {
           if (text.isNullOrEmpty()) return false
           return text.contains("疲惫") ||
               text.contains("收益减少") ||
               text.contains("收益降低") ||
               text.contains("干不动") ||
               text.contains("学不进去") ||
               text.contains("%E7%96%B2%E6%83%AB", ignoreCase = true)
       }

       fun findDelegateClass(classLoader: ClassLoader): Pair<Class<*>?, Method?> {
            try {
                val observerCls = Class.forName(OBSERVER_CLASS, true, classLoader)
                val interfaceCls = Class.forName(INTERFACE_CLASS, true, classLoader)

                // 优先测试最可能的混淆类名，再遍历全部小写字母
                val candidates = linkedSetOf('m', 'l', 'n', 'k', 'o', 'p', 'j', 'i')
                for (ch in 'a'..'z') {
                    candidates.add(ch)
                }

                for (ch in candidates) {
                    val className = "$DELEGATE_PKG$ch"
                    try {
                        val cls = Class.forName(className, true, classLoader)
                        if (interfaceCls.isAssignableFrom(cls) && !cls.isInterface) {
                            var targetMethod: Method? = null
                            for (m in cls.methods) {
                                val params = m.parameterTypes
                                if (params.size == 5 &&
                                    params[0] == ByteArray::class.java &&
                                    params[1] == String::class.java &&
                                    (params[2] == Int::class.javaPrimitiveType || params[2] == Integer::class.java) &&
                                    (params[3] == Int::class.javaPrimitiveType || params[3] == Integer::class.java) &&
                                    (observerCls.isAssignableFrom(params[4]) || params[4] == Any::class.java)
                                ) {
                                    targetMethod = m
                                    if (m.name == "c") break
                                }
                            }
                            if (targetMethod != null) {
                                resolvedDelegateClass = cls
                                resolvedSendMethodName = targetMethod.name
                                Log.i(TAG, "🎯 动态自适应命中 QQ 宠物原生发包代理类: $className, 发包方法: ${targetMethod.name}")
                                return Pair(cls, targetMethod)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "探测 PetPbDelegate 接口或观察者失败: ${t.message}")
            }
            return Pair(null, null)
        }
    }

    private var delegateInstance: Any? = null
    private var sendOidbMethod: Method? = null
    private var observerClass: Class<*>? = null
    @Volatile
    var isInternalSending = false
    var isReady: Boolean = false
        private set

    init {
        try {
            observerClass = Class.forName(OBSERVER_CLASS, true, classLoader)
            val (cls, method) = findDelegateClass(classLoader)
            if (cls != null && method != null) {
                val constructor: Constructor<*> = cls.getDeclaredConstructor().apply {
                    isAccessible = true
                }
                delegateInstance = constructor.newInstance()
                sendOidbMethod = method
                isReady = true
                Log.d(TAG, "✅ 成功反射挂载 QQ 宠物原生发包代理: ${cls.name}")
            } else {
                Log.e(TAG, "❌ 未能动态发现实现 PetPbDelegate 的发包代理类")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "反射 QQ 发包代理失败: ${t.message}", t)
        }
    }

    /**
     * 实时从宿主 MobileQQ 运行时获取当前登录的 QQ 号 (UIN)
     * 若未登录或过渡态返回 "0"，则统一返回空字符串 ""
     */
    fun getCurrentRuntimeUin(): String {
        try {
            val mobileQQClass = Class.forName("mqq.app.MobileQQ", true, classLoader)
            val sMobileQQField = mobileQQClass.getField("sMobileQQ")
            val sMobileQQ = sMobileQQField.get(null)
            if (sMobileQQ != null) {
                val peekMethod = sMobileQQ.javaClass.getMethod("peekAppRuntime")
                val runtime = peekMethod.invoke(sMobileQQ)
                if (runtime != null) {
                    val uinMethod = runtime.javaClass.getMethod("getCurrentAccountUin")
                    val uin = (uinMethod.invoke(runtime) as? String)?.trim()
                    if (AccountSessionGuard.isValidUin(uin)) {
                        return uin!!
                    }
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    /**
     * 解析主人 UIN (优先从 base64 宠物ID 解析，兜底从宿主 MobileQQ 运行时获取)
     */
    fun resolveUin(petId: String): String {
        val fromPetId = AccountSessionGuard.extractOwnerUinFromPetId(petId)
        if (fromPetId.isNotEmpty()) return fromPetId
        val runtimeUin = getCurrentRuntimeUin()
        if (runtimeUin.isNotEmpty()) return runtimeUin
        try {
            val decoded = String(Base64.decode(petId, Base64.DEFAULT), StandardCharsets.UTF_8)
            val uinPart = decoded.substringBefore("-")
            if (uinPart.isNotEmpty() && uinPart.all { it.isDigit() }) {
                return uinPart
            }
        } catch (_: Throwable) {}

        try {
            val mobileQQClass = Class.forName("mqq.app.MobileQQ", true, classLoader)
            val sMobileQQField = mobileQQClass.getField("sMobileQQ")
            val sMobileQQ = sMobileQQField.get(null)
            if (sMobileQQ != null) {
                val peekMethod = sMobileQQ.javaClass.getMethod("peekAppRuntime")
                val runtime = peekMethod.invoke(sMobileQQ)
                if (runtime != null) {
                    val uinMethod = runtime.javaClass.getMethod("getCurrentAccountUin")
                    val uin = uinMethod.invoke(runtime) as? String
                    if (!uin.isNullOrEmpty()) return uin
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    fun sendOidb(
        commandName: String,
        command: Int,
        subCommand: Int,
        request: ByteArray,
        callback: (code: Int, data: ByteArray?, errorMsg: String?) -> Unit
    ) {
        if (!isReady || delegateInstance == null || sendOidbMethod == null || observerClass == null) {
            callback(-1, null, "发包代理未就绪")
            return
        }
        try {
            isInternalSending = true
            val observer = Proxy.newProxyInstance(
                classLoader,
                arrayOf(observerClass)
            ) { _, method, args ->
                if (method.name == "onResult" && args != null && args.isNotEmpty()) {
                    val code = (args[0] as? Number)?.toInt() ?: -1
                    val data = args.getOrNull(1) as? ByteArray
                    val bundle = args.getOrNull(2) as? android.os.Bundle
                    val errorMsg = bundle?.getString("data_error_msg") ?: bundle?.getString("error_msg")
                    callback(code, data, errorMsg)
                }
                null
            }
            sendOidbMethod?.invoke(
                delegateInstance,
                request,
                commandName,
                command,
                subCommand,
                observer
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendOidb 执行反射调用异常: ${t.message}", t)
            callback(-2, null, t.message)
        } finally {
            isInternalSending = false
        }
    }

    /**
     * 查询本人宠物（获取 petId）
     */
    fun queryOwnPet(callback: (code: Int, petId: String?, rawData: ByteArray?) -> Unit) {
        sendOidb("OidbSvcTrpcTcp.0x95e1_0", 38369, 0, ByteArray(0)) { code, data, _ ->
            var petId: String? = null
            if (code == 0 && data != null) {
                val petBytes = ProtoWire.firstBytes(data, 1)
                petId = ProtoWire.firstString(petBytes, 101)
            }
            callback(code, petId, data)
        }
    }

   /**
    * 查询当前故事/任务状态（倒计时）
    */
    fun queryStoryStatus(
        petId: String,
        callback: (code: Int, remainingSec: Long?, totalSec: Long?, activeStoryId: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, petId)
            .writeVarint(2, 0L)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x975a_1", 38746, 1, body) { code, data, _ ->
            var remaining: Long? = null
            var total: Long? = null
            var storyId: String? = null
            if (code == 0 && data != null) {
                val subInfo = ProtoWire.firstBytes(data, 1)
                if (subInfo != null) {
                    val status = ProtoWire.firstVarint(subInfo, 1) ?: 0L
                    if (status != 0L) {
                        remaining = ProtoWire.firstVarint(subInfo, 2) ?: 0L
                        total = ProtoWire.firstVarint(subInfo, 3) ?: 0L
                    }
                }
                storyId = ProtoWire.firstString(data, 2)
            }
            callback(code, remaining, total, storyId)
        }
    }

    /**
     * 查询当前外出事件进行中详情与疲惫减益胶囊提示（官方 0x975f_1 / 38751 协议）
     */
    fun queryProcessStoryInfo(
        storyId: String,
        petId: String,
        callback: (ProcessStoryFatigueResult) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeString(2, petId)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x975f_1", 38751, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val eventType = (ProtoWire.firstVarint(data, 5) ?: 0L).toInt()
                val tipBytes = ProtoWire.firstBytes(data, 17)
                val tipContent = ProtoWire.firstString(tipBytes, 2)?.trim() ?: ""
                val tipExtra = ProtoWire.firstString(tipBytes, 3)?.trim() ?: ""
                val tipMarkdown = ProtoWire.firstString(tipBytes, 4)?.trim() ?: ""

               val allStrings = ProtoWire.extractAllStrings(data)
               val matchedStr = listOf(tipContent, tipMarkdown, tipExtra).firstOrNull {
                   containsFatigueKeyword(it)
               } ?: allStrings.firstOrNull {
                   containsFatigueKeyword(it)
               }

               val fatigued = !matchedStr.isNullOrEmpty()
                val displayTip = matchedStr
                    ?.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
                    ?.replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), "")
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.ifEmpty { tipContent.ifEmpty { "疲惫，收益减少" } }

               lastFatigueDetected = fatigued
                lastFatigueTip = if (fatigued) displayTip else null

                Log.i(
                    TAG,
                    "queryProcessStoryInfo 回包: storyId=$storyId, eventType=$eventType, fatigued=$fatigued, tip='$displayTip', rawTip=(content='$tipContent', md='$tipMarkdown')"
                )
                val isHired = allStrings.any { s ->
                    s.contains("被雇佣") || s.contains("雇佣者") || s.contains("被雇佣者") || s.contains("基础工资") || s.contains("加成奖金") || s.contains("可获得基础工资")
                }
                callback(ProcessStoryFatigueResult(0, fatigued, displayTip, eventType, null, isHired))
            } else {
                Log.w(TAG, "queryProcessStoryInfo 失败: storyId=$storyId, code=$code, err=$errorMsg")
                callback(ProcessStoryFatigueResult(code, false, null, 0, errorMsg, false))
            }
        }
    }

    /**
     * 动态解析合法的食物道具 ID (从官方 PetHomeResourceManager 映射表或默认 9990032L 提取)
     */
    fun resolveFoodId(): Long {
        try {
            val mgrCls = classLoader.loadClass("com.tencent.ergo.view.mainpage.util.PetHomeResourceManager")
            val mgrInst = mgrCls.getField("a").get(null)
            val mObj = mgrCls.getMethod("j").invoke(mgrInst)
            if (mObj != null) {
                val aField = mObj.javaClass.getField("a")
                val map = aField.get(mObj) as? Map<*, *>
                if (map != null && map.isNotEmpty()) {
                    for (entry in map.values) {
                        if (entry != null) {
                            val arr = entry.javaClass.getField("a").get(entry) as? Array<*>
                            if (arr != null && arr.isNotEmpty()) {
                                val first = arr[0]
                                if (first != null) {
                                    val strVal = first.javaClass.getField("a").get(first) as? String
                                    val fId = strVal?.toLongOrNull()
                                    if (fId != null && fId > 0L) {
                                        Log.d(TAG, "从 PetHomeResourceManager 成功解析到动态 foodId: $fId")
                                        return fId
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "从 PetHomeResourceManager 获取动态 foodId 失败: ${t.message}")
        }
        return 9990032L
    }

  /**
   * 自动喂食 (腾讯官方 0x992d_1 协议体 zh5.b)
   * Tag 1: petUin (给自己喂食传 ""，给好友宠物喂食传 friendUin.toString())
   * Tag 4: petId (目标宠物 ID)
   * Tag 5: foodId (食物 ID)
   * Tag 10: 行为扩展 Message
   * Tag 11: foodItemId (背包道具 ID，如来自 0x9949_1 的 Tag 4)
   */
  fun feed(
      petId: String,
      foodId: Long = 0L,
      petUin: String = "",
      foodItemId: String = "",
      callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
  ) {
      val targetFoodId = if (foodId > 0L) foodId else resolveFoodId()
      var bodyBytes: ByteArray? = null
      if (petUin.isEmpty() && foodItemId.isEmpty()) {
          try {
              val bCls = classLoader.loadClass("zh5.b")
              val bInst = bCls.newInstance()
              bCls.getField("a").set(bInst, "")
              bCls.getField("b").set(bInst, "")
              bCls.getField("c").set(bInst, "")
              bCls.getField("d").set(bInst, petId)
              bCls.getField("e").set(bInst, targetFoodId.toInt())
              val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
              val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
              bodyBytes = toByteArrayMethod.invoke(null, bInst) as ByteArray
              Log.d(TAG, "通过 zh5.b 反射构造喂食包成功: petId=$petId, foodId=$targetFoodId")
          } catch (t: Throwable) {
              Log.w(TAG, "zh5.b 反射未就绪: ${t.message}，使用 ProtoWire 编码")
          }
      }

      if (bodyBytes == null) {
          val extBytes = ProtoWire.message()
              .writeVarint(6, 1L)
              .writeVarint(13, 0L)
              .toByteArray()
          val msg = ProtoWire.message()
              .writeString(1, petUin)
              .writeString(2, "")
              .writeString(3, "")
              .writeString(4, petId)
              .writeVarint(5, targetFoodId)
              .writeBytes(10, extBytes)
          if (foodItemId.isNotEmpty()) {
              msg.writeString(11, foodItemId)
          }
          bodyBytes = msg.toByteArray()
      }
      sendOidb("OidbSvcTrpcTcp.0x992d_1", 39213, 1, bodyBytes) { code, data, err -> callback(code, data, err) }
  }

  /**
   * 带回包状态解析的喂食接口（支持自身与好友宠物投喂，解析 Ly4/b Tag 1 feedState 与 Tag 3 tipText）
   */
  fun feedDetailed(
      petId: String,
      foodId: Long = 0L,
      petUin: String = "",
      foodItemId: String = "",
      callback: (FeedDetailResult) -> Unit
  ) {
      feed(petId, foodId, petUin, foodItemId) { code, data, err ->
          var feedState = 0
          var tipText: String? = null
          if (data != null) {
              feedState = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
              tipText = ProtoWire.firstString(data, 3)?.takeIf { it.isNotBlank() }
          }
          Log.i(
              TAG,
              "🥣 feedDetailed 回包: petId=$petId, petUin=$petUin, foodItemId=$foodItemId, code=$code, feedState=$feedState, tip=$tipText, err=$err"
          )
          callback(FeedDetailResult(code, feedState, tipText, err))
      }
  }

  /**
   * 查询喂食次数与背包食物库存列表 (官方 0x9949_1 / 39241 协议，解析 Tag 1 remain, Tag 2 total, Tag 4 FoodInventoryInfo)
   */
  fun fetchFoodInventory(
      callback: (code: Int, remain: Int, total: Int, items: List<FoodInventoryItem>) -> Unit
  ) {
      sendOidb("OidbSvcTrpcTcp.0x9949_1", 39241, 1, ByteArray(0)) { code, data, err ->
          val items = mutableListOf<FoodInventoryItem>()
          var remain = 0
          var total = 0
          if (code == 0 && data != null) {
              remain = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
              total = (ProtoWire.firstVarint(data, 2) ?: 0L).toInt()
              val itemNodes = ProtoWire.allBytes(data, 4)
              for (node in itemNodes) {
                  val name = ProtoWire.firstString(node, 1) ?: "爱心饼干"
                  val balance = (ProtoWire.firstVarint(node, 2) ?: 0L).toInt()
                  val itemId = ProtoWire.firstString(node, 4) ?: ""
                  val energyVal = (ProtoWire.firstVarint(node, 7) ?: 20L).toInt()
                  if (itemId.isNotEmpty()) {
                      items.add(FoodInventoryItem(itemId, name, balance, if (energyVal > 0) energyVal else 20))
                  }
              }
              Log.i(TAG, "🥣 fetchFoodInventory 成功: remain=$remain, total=$total, items=$items")
          } else {
              Log.w(TAG, "🥣 fetchFoodInventory 失败: code=$code, err=$err")
          }
          callback(code, remain, total, items)
      }
  }

    /**
     * 查询当日剩余喂食次数 (官方 0x9949_1 协议通道)
     */
    fun queryFeedTimes(callback: (code: Int, remain: Int, total: Int) -> Unit) {
        var bodyBytes: ByteArray? = null
        try {
            val dCls = classLoader.loadClass("zh5.d")
            val dInst = dCls.newInstance()
            val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            bodyBytes = toByteArrayMethod.invoke(null, dInst) as ByteArray
        } catch (_: Throwable) {}

        if (bodyBytes == null) {
            bodyBytes = ByteArray(0)
        }

        sendOidb("OidbSvcTrpcTcp.0x9949_1", 39241, 1, bodyBytes) { code, data, err ->
            if (code == 0 && data != null) {
                try {
                    val eCls = classLoader.loadClass("zh5.e")
                    val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
                    val mergeFromMethod = nanoCls.getMethod("mergeFrom", nanoCls, ByteArray::class.java)
                    val eInst = mergeFromMethod.invoke(null, eCls.newInstance(), data)
                    val remain = eCls.getField("a").getInt(eInst)
                    val total = eCls.getField("b").getInt(eInst)
                    Log.i(TAG, "📊 查询喂食状态回包: remain=$remain, total=$total")
                    callback(0, remain, total)
                    return@sendOidb
                } catch (t: Throwable) {
                    val remain = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
                    val total = (ProtoWire.firstVarint(data, 2) ?: 0L).toInt()
                    Log.i(TAG, "📊 ProtoWire 解析喂食状态回包: remain=$remain, total=$total")
                    callback(0, remain, total)
                    return@sendOidb
                }
            }
            Log.w(TAG, "📊 查询喂食状态失败: code=$code, err=$err")
            callback(code, 0, 0)
        }
    }

   /**
    * 自动洗澡 / 清洁 (官方 0x96a6_1 行为事件通道)
    * EPage: 5000 (E_PET_WASH)
    * EEventType: 500 (E_EVENT_WASH)
    * ESubEvent: 501 (E_SUBEVENT_WASH_CLEAN_PROGRESS)
    * cleanValue: 100
    */
  fun bath(
      petId: String,
      cleanValue: Int = 100,
      stage: Int = 2,
      petUin: String = "",
      callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
  ) {
      var bodyBytes: ByteArray? = null
      val now = System.currentTimeMillis()
      try {
          val dCls = classLoader.loadClass("ci5.d")
          val dInst = dCls.newInstance()
          dCls.getField("a").set(dInst, petId)
          dCls.getField("b").set(dInst, petUin)
          val jCls = classLoader.loadClass("uh5.j")
          val jInst = jCls.newInstance()
          jCls.getField("a").set(jInst, 5000)
          jCls.getField("b").set(jInst, 500)
          jCls.getField("c").set(jInst, 501)
          dCls.getField("c").set(dInst, jInst)
          val iCls = classLoader.loadClass("uh5.i")
          val iInst = iCls.newInstance()
          iCls.getField("b").set(iInst, now - 3000L)
          iCls.getField("h").set(iInst, 1)
          dCls.getField("d").set(dInst, iInst)
          val bCls = classLoader.loadClass("ci5.b")
          val bInst = bCls.newInstance()
          bCls.getField("d").set(bInst, cleanValue)
          dCls.getField("e").set(dInst, bInst)
          val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
          val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
          bodyBytes = toByteArrayMethod.invoke(null, dInst) as ByteArray
          Log.d(TAG, "通过 ci5.d 反射构造清洁上报包成功 (cleanValue=$cleanValue, stage=$stage)")
      } catch (t: Throwable) {
          Log.w(TAG, "ci5.d 反射未就绪: ${t.message}，使用 ProtoWire 编码")
      }

      if (bodyBytes == null) {
          val pathBytes = ProtoWire.message()
              .writeVarint(1, 5000L)
              .writeVarint(2, 500L)
              .writeVarint(3, 501L)
              .toByteArray()
          val exeExtBytes = ProtoWire.message()
              .writeVarint(7, now - 3000L)
              .writeVarint(13, 1L)
              .toByteArray()
          val extBytes = ProtoWire.message()
              .writeVarint(5, cleanValue.toLong())
              .toByteArray()
          bodyBytes = ProtoWire.message()
              .writeString(1, petId)
              .writeString(2, petUin)
              .writeBytes(3, pathBytes)
              .writeBytes(4, exeExtBytes)
              .writeBytes(5, extBytes)
              .toByteArray()
      }
      sendOidb("OidbSvcTrpcTcp.0x96a6_1", 38566, 1, bodyBytes) { code, data, err -> callback(code, data, err) }
  }

   /**
    * 查询香皂商品配置 (官方 0x9bf1_1 / 39921 协议)
    */
   fun fetchBathItemConfig(callback: (code: Int, items: List<BathItemConfig>) -> Unit) {
       sendOidb("OidbSvcTrpcTcp.0x9bf1_1", 39921, 1, ByteArray(0)) { code, data, err ->
           val list = mutableListOf<BathItemConfig>()
           if (code == 0 && data != null) {
               val itemBytesList = ProtoWire.allBytes(data, 1)
               for (bBytes in itemBytesList) {
                   val name = ProtoWire.firstString(bBytes, 1) ?: "香皂片"
                   val itemId = ProtoWire.firstString(bBytes, 2) ?: ""
                   val gold = (ProtoWire.firstVarint(bBytes, 5) ?: 5L).toInt()
                   val cleanVal = (ProtoWire.firstVarint(bBytes, 6) ?: 10L).toInt()
                   val defBuy = (ProtoWire.firstVarint(bBytes, 8) ?: 5L).toInt()
                   if (itemId.isNotEmpty()) {
                       list.add(BathItemConfig(itemId, name, gold, cleanVal, defBuy))
                   }
               }
               Log.i(TAG, "🧼 fetchBathItemConfig 成功: count=${list.size}, items=$list")
           } else {
               Log.w(TAG, "🧼 fetchBathItemConfig 失败: code=$code, err=$err")
           }
           callback(code, list)
       }
   }

   /**
    * 查询背包香皂库存 (官方 0x9bf2_1 / 39922 协议)
    */
   fun fetchBathInventory(callback: (code: Int, balances: Map<String, Int>) -> Unit) {
       sendOidb("OidbSvcTrpcTcp.0x9bf2_1", 39922, 1, ByteArray(0)) { code, data, err ->
           val map = linkedMapOf<String, Int>()
           if (code == 0 && data != null) {
               val invInfoBytes = ProtoWire.firstBytes(data, 1)
               val itemBytesList = ProtoWire.allBytes(invInfoBytes, 1)
               for (cBytes in itemBytesList) {
                   val itemId = ProtoWire.firstString(cBytes, 1) ?: ""
                   val balance = (ProtoWire.firstVarint(cBytes, 2) ?: 0L).toInt()
                   if (itemId.isNotEmpty()) {
                       map[itemId] = balance
                   }
               }
               Log.i(TAG, "🧼 fetchBathInventory 成功: balances=$map")
           } else {
               Log.w(TAG, "🧼 fetchBathInventory 失败: code=$code, err=$err")
           }
           callback(code, map)
       }
   }

   /**
    * 自动购买香皂/商城道具 (官方 0x9bd0_0 / 39888 协议, appId=355, 香皂 scene=21, 食物 scene=12)
    */
   fun buyBathItem(
       petId: String,
       itemId: String,
       count: Int = 5,
       scene: Long = 21L,
       callback: (code: Int, orderResult: Int, errorMsg: String?) -> Unit
   ) {
       val itemIdLong = itemId.toLongOrNull() ?: 2010104L
       val userInfoBytes = ProtoWire.message()
           .writeVarint(1, 1L)
           .writeVarint(2, 1001L)
           .writeString(3, petId)
           .toByteArray()
       val mallItemBytes = ProtoWire.message()
           .writeVarint(1, 355L)
           .writeVarint(2, itemIdLong)
           .writeVarint(3, count.toLong())
           .toByteArray()
       val bodyBytes = ProtoWire.message()
           .writeBytes(1, userInfoBytes)
           .writeVarint(2, 1001L)
           .writeBytes(3, mallItemBytes)
           .writeVarint(4, scene)
           .toByteArray()

       sendOidb("OidbSvcTrpcTcp.0x9bd0_0", 39888, 0, bodyBytes) { code, data, err ->
           val orderResult = if (code == 0 && data != null) {
               (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
           } else 0
           Log.i(TAG, "🛒 buyBathItem (itemId=$itemId, count=$count) 回包: code=$code, orderResult=$orderResult, err=$err")
           callback(code, orderResult, err)
       }
   }

   /**
    * 执行单次搓澡消耗香皂 (官方 0x9bf3_1 / 39923 真实增加清洁度协议)
    * Tag 1: petId (目标宠物 ID)
    * Tag 2: itemId (香皂道具 ID，如 "2010104")
    * Tag 3: useNum (1)
    * Tag 4: petUin (给自己洗澡传 ""，给好友宠物洗澡传 friendUin.toString())
    */
   fun doBathOnce(
       petId: String,
       itemId: String,
       useNum: Int = 1,
       petUin: String = "",
       callback: (BathResult) -> Unit
   ) {
       val bodyBytes = ProtoWire.message()
           .writeString(1, petId)
           .writeString(2, itemId)
           .writeVarint(3, useNum.toLong())
           .writeString(4, petUin)
           .toByteArray()

       sendOidb("OidbSvcTrpcTcp.0x9bf3_1", 39923, 1, bodyBytes) { code, data, err ->
           if (code == 0 && data != null) {
               val newClean = (ProtoWire.firstVarint(data, 1) ?: 0L).toInt()
               val addedClean = (ProtoWire.firstVarint(data, 2) ?: 0L).toInt()
               val remainBalance = (ProtoWire.firstVarint(data, 3) ?: 0L).toInt()
               val isFullClean = (ProtoWire.firstVarint(data, 4) ?: 0L) != 0L
               if (petUin.isEmpty()) {
                   cachedPetAttributes?.let { old ->
                       cachedPetAttributes = old.copy(clean = newClean.toFloat())
                   }
               }
               Log.i(TAG, "🧼 doBathOnce 成功 (petId=$petId, petUin=$petUin): newClean=$newClean, added=$addedClean, remain=$remainBalance, isFull=$isFullClean")
               callback(BathResult(0, newClean, addedClean, remainBalance, isFullClean, null))
           } else {
               Log.w(TAG, "🧼 doBathOnce 失败 (petId=$petId, petUin=$petUin): code=$code, err=$err")
               callback(BathResult(code, -1, 0, -1, false, err))
           }
       }
   }

   /**
    * 权威查询宠物实时三围属性 (官方 0x96f2_1 / 38642 协议)
    * Tag 1 (displayValue): Tag 1 = feeling(心情), Tag 2 = hunger(体力), Tag 3 = clean(清洁)
    */
   fun queryPetAttributes(
       petId: String,
       isSelf: Boolean = true,
       callback: (code: Int, attrs: PetAttributes?) -> Unit
   ) {
       val bodyBytes = ProtoWire.message()
           .writeString(1, petId)
           .toByteArray()
       sendOidb("OidbSvcTrpcTcp.0x96f2_1", 38642, 1, bodyBytes) { code, data, err ->
           if (code == 0 && data != null) {
               val displayBytes = ProtoWire.firstBytes(data, 1)
               if (displayBytes != null) {
                   val feelingBytes = ProtoWire.firstBytes(displayBytes, 1)
                   val hungerBytes = ProtoWire.firstBytes(displayBytes, 2)
                   val cleanBytes = ProtoWire.firstBytes(displayBytes, 3)

                   val moodCur = if (feelingBytes != null) (ProtoWire.firstFloat(feelingBytes, 3) ?: 0f) else 0f
                   val energyMax = if (hungerBytes != null) (ProtoWire.firstFloat(hungerBytes, 2) ?: 100f) else 100f
                   val energyCur = if (hungerBytes != null) (ProtoWire.firstFloat(hungerBytes, 3) ?: 0f) else 0f
                   val cleanMax = if (cleanBytes != null) (ProtoWire.firstFloat(cleanBytes, 2) ?: 100f) else 100f
                   val cleanCur = if (cleanBytes != null) (ProtoWire.firstFloat(cleanBytes, 3) ?: 0f) else 0f

                   val attrs = PetAttributes(
                       energy = energyCur,
                       maxEnergy = if (energyMax > 0f) energyMax else 100f,
                       clean = cleanCur,
                       maxClean = if (cleanMax > 0f) cleanMax else 100f,
                       mood = moodCur
                   )
                   if (isSelf) {
                       cachedPetAttributes = attrs
                   }
                   Log.i(TAG, "📊 [0x96f2_1] 实时三围 (petId=$petId, isSelf=$isSelf): 体力=${energyCur.toInt()}/${attrs.maxEnergy.toInt()}, 清洁=${cleanCur.toInt()}/${attrs.maxClean.toInt()}, 心情=${moodCur.toInt()}")
                   callback(0, attrs)
                   return@sendOidb
               }
           }
           Log.w(TAG, "📊 [0x96f2_1] 查询实时三围失败 (petId=$petId, isSelf=$isSelf): code=$code, err=$err")
           callback(code, if (isSelf) cachedPetAttributes else null)
       }
   }

   /**
    * 自动购买食物 (官方 0x99df_1 / 39391 协议)
    * tag 1: 购买数量 (Varint, 默认 5L)
    * tag 2: petId (String)
    * tag 3: 道具分类 (String, "1" 代表爱心饼干, 单价 5 金币)
    */
   fun buyFood(
       petId: String,
       count: Long = 5L,
       itemType: String = "1",
       callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
   ) {
       val body = ProtoWire.message()
           .writeVarint(1, count)
           .writeString(2, petId)
           .writeString(3, itemType)
           .toByteArray()

       sendOidb("OidbSvcTrpcTcp.0x99df_1", 39391, 1, body) { code, data, errorMsg ->
           Log.i(TAG, "buyFood 购买食物 (itemType=$itemType, count=$count) 回包: code=$code, err=$errorMsg")
           callback(code, data, errorMsg)
       }
   }

   /**
    * 主动触发宠物全量资料与属性刷新 (官方 0x99f2_1 / 39410 协议)
    * 空包请求触发服务端回传全量 profile 并刷新 DisplayValueManager 单例
    */
   fun refreshProfile(callback: ((code: Int) -> Unit)? = null) {
       sendOidb("OidbSvcTrpcTcp.0x99f2_1", 39410, 1, ByteArray(0)) { code, _, _ ->
           Log.d(TAG, "refreshProfile 触发状态同步回包: code=$code")
           callback?.invoke(code)
       }
   }

   /**
    * 从宿主 DisplayValueManager 单例实时提取宠物当前体力与清洁度
    */
   fun getPetAttributes(petId: String): PetAttributes? {
       try {
           val mgrCls = classLoader.loadClass("com.tencent.ergo.user.DisplayValueManager")
           val mgrInst = mgrCls.getField("a").get(null) ?: return null
           val cMethod = mgrCls.getMethod("c")
           val liveData = cMethod.invoke(mgrInst) ?: return null
           val displayObj = liveData.javaClass.getMethod("getValue").invoke(liveData) ?: return null

           var energy = -1f
           var maxEnergy = 100f
           var clean = -1f
           var maxClean = 100f
           var mood = 0f

           for (m in displayObj.javaClass.methods) {
               if (m.parameterTypes.isEmpty() && m.returnType.name.endsWith("\$c")) {
                   val cVal = m.invoke(displayObj)
                   if (cVal != null) {
                       val cur = (cVal.javaClass.getMethod("b").invoke(cVal) as? Number)?.toFloat() ?: 0f
                       val max = (cVal.javaClass.getMethod("d").invoke(cVal) as? Number)?.toFloat() ?: 100f
                       when (m.name) {
                           "f" -> { energy = cur; maxEnergy = max }
                           "c" -> { clean = cur; maxClean = max }
                           "d" -> { mood = cur }
                       }
                   }
               }
           }
           if (energy >= 0f || clean >= 0f) {
               Log.d(TAG, "实时读取到宠物属性: energy=$energy/$maxEnergy, clean=$clean/$maxClean, mood=$mood")
               val attrs = PetAttributes(energy, maxEnergy, clean, maxClean, mood)
               cachedPetAttributes = attrs
               return attrs
           }
       } catch (t: Throwable) {
           Log.w(TAG, "反射读取宠物属性异常: ${t.message}")
       }
       return cachedPetAttributes
   }

   /**
    * 任务结算收工
    */
    fun settleStory(storyId: String, petId: String, callback: (code: Int, rawData: ByteArray?) -> Unit) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeVarint(2, 1000L)
            .writeString(3, petId)
            .writeVarint(100, 2L)
            .toByteArray()
        sendOidb("OidbSvcTrpcTcp.0x9760_1", 38752, 1, body) { code, data, _ -> callback(code, data) }
    }

    /**
     * 提前中断并召回宠物回家 (官方 doInterruptSettle，CMD 38752，tag 4 = 3)
     */
    fun recallStory(storyId: String, petId: String, callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeVarint(2, 6000L)
            .writeString(3, petId)
            .writeVarint(4, 3L) // 3 代表提前召回中断
            .writeVarint(100, 2L)
            .toByteArray()
        sendOidb("OidbSvcTrpcTcp.0x9760_1", 38752, 1, body) { code, data, errorMsg ->
            callback(code, data, errorMsg)
        }
    }

    /**
     * 发起冒险探索
     */
   fun startAdventure(
       petId: String,
       adventureName: String = "森林探险",
       subEventType: Long = 6701L,
       callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
   ) {
       startSceneTask(6700L, petId, adventureName, subEventType, "", 0L, callback)
   }

   /**
    * 发起兼职打工
    */
   fun startWork(
       petId: String,
       jobName: String = "小镇兼职",
       page: Long = 6400L,
       subEventType: Long = 6401L,
       hiredPetId: String = "",
        hiredUin: Long = 0L,
       callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
   ) {
        startSceneTask(page, petId, jobName, subEventType, hiredPetId, hiredUin, callback)
   }

   /**
     * 发起进阶学习
     */
   fun startSchool(
       petId: String,
       courseName: String = "基础学园课程",
       page: Long = 6100L,
       subEventType: Long = 6101L,
       callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
   ) {
        startSceneTask(page, petId, courseName, subEventType, "", 0L, callback)
   }

   private fun startSceneTask(
       page: Long,
       petId: String,
       taskName: String,
       subEventType: Long,
       hiredPetId: String = "",
        hiredUin: Long = 0L,
       callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
   ) {
        val msg = ProtoWire.message()
            .writeVarint(1, page)
            .writeString(2, petId)
            .writeString(3, "")
        if (hiredPetId.isNotBlank() && hiredUin > 0L) {
            val reqUserInfo = ProtoWire.message()
                .writeString(1, hiredPetId)
                .writeString(2, hiredUin.toString())
                .toByteArray()
            msg.writeBytes(4, reqUserInfo)
        }
        val body = msg
            .writeString(6, taskName)
            .writeVarint(7, subEventType)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x975e_1", 38750, 1, body) { code, data, errorMsg ->
            val storyId = ProtoWire.firstString(data, 1)
            callback(code, storyId, data, errorMsg)
        }
    }

    /**
     * 查询学园/打工小镇二级地图信息 (官方 0x9b60_1 协议)
     * 获取当前处于哪一阶段学园 (如 1 初级, 2 中级, 3 高级) 与上次子事件
     */
    fun querySecondMapInfo(
        eventType: Long = 6100L,
        petId: String,
        callback: (code: Int, schoolStage: Int, lastSubEvent: Long, rawData: ByteArray?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, eventType)
            .writeString(2, petId)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x9b60_1", 39776, 1, body) { code, data, _ ->
            var stage = 0
            var lastSub = 0L
            if (code == 0 && data != null) {
                stage = (ProtoWire.firstVarint(data, 4) ?: 0L).toInt()
                lastSub = ProtoWire.firstVarint(data, 5) ?: 0L
            Log.i(TAG, "querySecondMapInfo 成功: eventType=$eventType, schoolStage=$stage, lastSubEvent=$lastSub")
        }
        callback(code, stage, lastSub, data)
    }
}

    /**
     * 查询二级地图全量详情与账号属性 (阶段列表、解锁状态、毕业标识、三大主属性)
     */
    fun querySecondMapInfoDetails(
        eventType: Long = 6100L,
        petId: String,
        callback: (SecondMapDetails) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, eventType)
            .writeString(2, petId)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x9b60_1", 39776, 1, body) { code, data, _ ->
            var stage = 0
            var lastSub = 0L
            val stageList = mutableListOf<SchoolStageInfo>()
           var power = 0L
           var intel = 0L
           var charm = 0L
           if (code == 0 && data != null) {
               Log.i(TAG, "querySecondMapInfoDetails raw: eventType=$eventType, fields=${ProtoWire.dumpFields(data)}")
               stage = (ProtoWire.firstVarint(data, 4) ?: 0L).toInt()
               lastSub = ProtoWire.firstVarint(data, 5) ?: 0L
               val itemBytesList = ProtoWire.allBytes(data, 1)
               for (itemBytes in itemBytesList) {
                   Log.i(TAG, "querySecondMapInfoDetails item: eventType=$eventType, fields=${ProtoWire.dumpFields(itemBytes)}")
                  val title = ProtoWire.firstString(itemBytes, 1) ?: ""
                  val limitStatus = (ProtoWire.firstVarint(itemBytes, 4) ?: 0L).toInt()
                   val lockReason = ProtoWire.firstString(itemBytes, 5) ?: ""
                   val id = (ProtoWire.firstVarint(itemBytes, 20) ?: ProtoWire.firstVarint(itemBytes, 21) ?: 0L).toInt()
                   val graduated = (ProtoWire.firstVarint(itemBytes, 23) ?: 0L) != 0L
                   if (id > 0) {
                       stageList.add(SchoolStageInfo(id, title, limitStatus, graduated, lockReason))
                   }
               }
                val attrBytes = ProtoWire.firstBytes(data, 2)
                if (attrBytes != null) {
                    val pBytes = ProtoWire.firstBytes(attrBytes, 1)
                    if (pBytes != null) power = ProtoWire.firstVarint(pBytes, 3) ?: 0L
                    val iBytes = ProtoWire.firstBytes(attrBytes, 2)
                    if (iBytes != null) intel = ProtoWire.firstVarint(iBytes, 3) ?: 0L
                    val cBytes = ProtoWire.firstBytes(attrBytes, 3)
                    if (cBytes != null) charm = ProtoWire.firstVarint(cBytes, 3) ?: 0L
                }
                Log.i(TAG, "querySecondMapInfoDetails 成功: stage=$stage, stagesCount=${stageList.size}, attr=(p=$power, i=$intel, c=$charm)")
            }
            callback(SecondMapDetails(code, stage, lastSub, stageList, power, intel, charm))
        }
    }

/**
 * 动态拉取当前阶段开放的课程或工种列表 (官方 0x9ab2_1 协议)
     * 解析出真实的 eventName, subEventType, canDo
     */
    fun querySelectEvents(
        eventType: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0,
        callback: (code: Int, events: List<SelectEvent>, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        lastSelectEventsFatigued = false
        lastSelectEventsFatigueTip = null
        val msg = ProtoWire.message()
            .writeVarint(1, eventType)
            .writeString(2, petId)
            .writeString(3, "")
            .writeString(4, "")
        if (careerType > 0) {
            msg.writeVarint(10, careerType.toLong())
        }
        if (schoolStage > 0) {
            msg.writeVarint(11, schoolStage.toLong())
        }
        msg.writeVarint(100, 2L)
        val body = msg.toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x9ab2_1", 39602, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val list = mutableListOf<SelectEvent>()
                var foundFatigueTip: String? = null
                val itemBytesList = ProtoWire.allBytes(data, 1)
                for (itemBytes in itemBytesList) {
                    val name = ProtoWire.firstString(itemBytes, 1) ?: ""
                    val sub = ProtoWire.firstVarint(itemBytes, 52) ?: 0L
                    val can = (ProtoWire.firstVarint(itemBytes, 50) ?: 0L) != 0L
                    val level = (ProtoWire.firstVarint(itemBytes, 3) ?: 0L).toInt()
                    val cost = ProtoWire.firstString(itemBytes, 6) ?: ""
                    val costTime = ProtoWire.firstString(itemBytes, 7) ?: ""
                    val reward = ProtoWire.firstString(itemBytes, 8) ?: ""
                    val rewardExtra = ProtoWire.firstString(itemBytes, 9) ?: ""
                    val eventTips = ProtoWire.firstString(itemBytes, 17) ?: ""
                    val isOwnerNeedCare = (ProtoWire.firstVarint(itemBytes, 18) ?: 0L) != 0L
                    val itemFatigueHit = listOf(eventTips, rewardExtra, reward).firstOrNull {
                        containsFatigueKeyword(it)
                    }
                    val itemIsFatigued = itemFatigueHit != null
                    if (foundFatigueTip == null) {
                        if (itemFatigueHit != null) foundFatigueTip = itemFatigueHit
                    }
                    Log.d(TAG, "[$eventType-EventItem] name='$name', sub=$sub, can=$can, level=$level, cost='$cost', time='$costTime', reward='$reward', extra='$rewardExtra', tips='$eventTips', needCare=$isOwnerNeedCare")
                   if (cost.isNotEmpty()) {
                       updateCachedAttributesFromCost(cost)
                   }
                   if (name.isNotEmpty() && sub > 0L) {
                       list.add(SelectEvent(name, sub, can, level, cost, costTime, reward, rewardExtra, eventTips, isOwnerNeedCare, itemIsFatigued))
                   }
               }
               if (foundFatigueTip == null) {
                    val rawTip = ProtoWire.extractAllStrings(data).firstOrNull {
                        containsFatigueKeyword(it)
                    }
                   if (rawTip != null) {
                       foundFatigueTip = rawTip
                           .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
                           .replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), "")
                           .replace(Regex("\\s+"), " ")
                           .trim()
                   }
              }
               // 仅当列表内所有可选事件均带疲惫提示（或列表为空但回包全局含疲惫提示）时才标记全局疲惫
               lastSelectEventsFatigued = if (list.isNotEmpty()) {
                   list.all { it.isFatigued }
               } else {
                   !foundFatigueTip.isNullOrEmpty()
               }
                lastSelectEventsFatigueTip = foundFatigueTip
                Log.i(TAG, "querySelectEvents 回包: eventType=$eventType, stage=$schoolStage, career=$careerType, 解析到 ${list.size} 个可用事件")
                callback(0, list, data, null)
            } else {
                Log.w(TAG, "querySelectEvents 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), data, errorMsg)
            }
        }
    }

    /**
     * 拉取踩踩/访客记录（谁踩了我，官方 0x985e_0 / 39006 协议）
     */
    fun fetchLikeList(
        extra: String = "",
        callback: (code: Int, members: List<LikeMember>, hasMore: Boolean, nextExtra: String, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, extra)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x985e_0", 39006, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val memberList = mutableListOf<LikeMember>()
                val itemBytesList = ProtoWire.allBytes(data, 1)
                for (itemBytes in itemBytesList) {
                    val userProfileBytes = ProtoWire.firstBytes(itemBytes, 1)
                    val uin = ProtoWire.firstVarint(userProfileBytes, 1) ?: 0L
                    val nick = ProtoWire.firstString(userProfileBytes, 2) ?: ""
                    val headerUrl = ProtoWire.firstString(userProfileBytes, 3) ?: ""
                    val ts = ProtoWire.firstVarint(itemBytes, 2) ?: 0L

                    val descBytes = ProtoWire.firstBytes(itemBytes, 3)
                    val contentBytesList = ProtoWire.allBytes(descBytes, 1)
                    val descSb = StringBuilder()
                    for (cBytes in contentBytesList) {
                        val text = ProtoWire.firstString(cBytes, 1) ?: ""
                        descSb.append(text)
                    }

                   val friendPetBytes = ProtoWire.firstBytes(itemBytes, 7)
                   var petId = ""
                   var canLikeBack = true
                   if (friendPetBytes != null) {
                       val profileBytes = ProtoWire.firstBytes(friendPetBytes, 1)
                       petId = ProtoWire.firstString(profileBytes, 101) ?: ""
                   }

                   // 检查条目自身的 Tag 6 (SparkBrief) 或 Tag 7 内嵌的 Tag 10 (SparkBrief)
                   val sparkBriefBytes = ProtoWire.firstBytes(itemBytes, 6)
                       ?: if (friendPetBytes != null) ProtoWire.firstBytes(friendPetBytes, 10) else null
                   if (sparkBriefBytes != null) {
                       // Tag 10 为 selfLikedToday (Boolean: 1 代表今日已踩过)
                       val selfLikedToday = (ProtoWire.firstVarint(sparkBriefBytes, 10) ?: 0L) != 0L
                       canLikeBack = !selfLikedToday
                   } else if (friendPetBytes != null) {
                       canLikeBack = (ProtoWire.firstVarint(friendPetBytes, 8) ?: 0L) != 1L
                   }
                   if (uin > 0L) {
                       memberList.add(LikeMember(uin, nick, headerUrl, ts, descSb.toString(), canLikeBack, petId))
                    }
                }
                val hasMore = (ProtoWire.firstVarint(data, 2) ?: 0L) != 0L
                val nextExtra = ProtoWire.firstString(data, 5) ?: ""
                Log.i(TAG, "fetchLikeList 回包: 解析到 ${memberList.size} 位来踩访客, hasMore=$hasMore")
                callback(0, memberList, hasMore, nextExtra, data, null)
            } else {
                Log.w(TAG, "fetchLikeList 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), false, "", data, errorMsg)
            }
        }
    }

    /**
     * 发送踩踩/回踩指定好友宠物（官方 0x985b_0 / 39003 协议）
     */
    fun sendLike(
        targetUin: Long,
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, targetUin)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x985b_0", 39003, 0, body) { code, data, errorMsg ->
            Log.i(TAG, "sendLike 踩踩目标 $targetUin 结果: code=$code, err=$errorMsg")
            callback(code, data, errorMsg)
        }
    }

    /**
     * 查询某好友的踩踩状态（官方 0x985c_0 / 39004 协议）
     */
    fun queryLikeCount(
        targetUin: Long,
        callback: (code: Int, alreadyLiked: Boolean, likeCount: String, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, targetUin)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x985c_0", 39004, 0, body) { code, data, errorMsg ->
            var alreadyLiked = false
            var likeCount = "0"
            if (code == 0 && data != null) {
                likeCount = ProtoWire.firstString(data, 1) ?: "0"
                alreadyLiked = (ProtoWire.firstVarint(data, 2) ?: 0L) != 0L
            }
            callback(code, alreadyLiked, likeCount, data, errorMsg)
        }
    }

    /**
     * 拉取好友宠物列表并提取携带福袋 (CoinBag) 的好友（官方 0x985d_0 / 39005 协议）
     */
    fun fetchFriendCoinBags(
        cookie: String = "",
        callback: (
            code: Int,
            bags: List<FriendCoinBagInfo>,
            totalFriendsInPage: Int,
            hasMore: Boolean,
            nextCookie: String,
            errorMsg: String?
        ) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, cookie)
            .writeVarint(2, 1L)
            .writeVarint(3, 0L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x985d_0", 39005, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val bagList = mutableListOf<FriendCoinBagInfo>()
                val allFriendNodes = mutableListOf<ByteArray>()
                allFriendNodes.addAll(ProtoWire.allBytes(data, 1))
                ProtoWire.firstBytes(data, 6)?.let { allFriendNodes.add(it) }

                for (nodeBytes in allFriendNodes) {
                    val profileBytes = ProtoWire.firstBytes(nodeBytes, 1)
                    val petNick = ProtoWire.firstString(profileBytes, 1) ?: ""
                    val friendPetId = ProtoWire.firstString(profileBytes, 8)
                        ?: ProtoWire.firstString(profileBytes, 101)
                        ?: ""

                    val userBytes = ProtoWire.firstBytes(nodeBytes, 2)
                    val friendUin = ProtoWire.firstVarint(userBytes, 1) ?: 0L
                    val friendNick = ProtoWire.firstString(userBytes, 2) ?: ""

                    val coinBagBytes = ProtoWire.firstBytes(nodeBytes, 21)
                    val coinbagId = ProtoWire.firstString(coinBagBytes, 1)?.trim() ?: ""
                    if (coinbagId.isNotEmpty()) {
                        bagList.add(
                            FriendCoinBagInfo(
                                friendUin = friendUin,
                                friendNick = friendNick,
                                friendPetId = friendPetId,
                                petNick = petNick,
                                coinbagId = coinbagId
                            )
                        )
                    }
                }
                val nextCookie = ProtoWire.firstString(data, 2) ?: ""
                val hasMore = (ProtoWire.firstVarint(data, 3) ?: 0L) != 0L
                Log.i(
                    TAG,
                    "fetchFriendCoinBags 回包: 本页好友=${allFriendNodes.size}, 发现福袋=${bagList.size}, hasMore=$hasMore"
                )
                callback(0, bagList, allFriendNodes.size, hasMore, nextCookie, null)
            } else {
                Log.w(TAG, "fetchFriendCoinBags 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), 0, false, "", errorMsg)
            }
        }
    }

    /**
     * 分页拉取养宠好友列表，用于打工雇佣好友选择与匹配（官方 0x985d_0 / 39005 协议）
     */
    fun fetchPetFriendsPage(
        cookie: String = "",
        callback: (
            code: Int,
            friends: List<HireableFriend>,
            hasMore: Boolean,
            nextCookie: String,
            errorMsg: String?
        ) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, cookie)
            .writeVarint(2, 1L)
            .writeVarint(3, 0L)
            .toByteArray()

        val currentOwnUin = getCurrentRuntimeUin()
        sendOidb("OidbSvcTrpcTcp.0x985d_0", 39005, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val list = mutableListOf<HireableFriend>()
                val friendNodes = ProtoWire.allBytes(data, 1)
                for (nodeBytes in friendNodes) {
                    val userBytes = ProtoWire.firstBytes(nodeBytes, 2)
                    val friendUin = ProtoWire.firstVarint(userBytes, 1) ?: 0L
                    if (friendUin <= 0L || (currentOwnUin.isNotEmpty() && friendUin.toString() == currentOwnUin)) {
                        continue
                    }
                    val friendNick = ProtoWire.firstString(userBytes, 2)?.trim().orEmpty()

                    val profileBytes = ProtoWire.firstBytes(nodeBytes, 1)
                    val petNick = ProtoWire.firstString(profileBytes, 1)?.trim().orEmpty()
                    var friendPetId = ProtoWire.firstString(profileBytes, 8)?.trim()
                        ?: ProtoWire.firstString(profileBytes, 101)?.trim()
                        ?: ""
                    if (friendPetId.isEmpty() && profileBytes != null) {
                        val candidates = ProtoWire.extractAllStrings(profileBytes)
                        friendPetId = candidates.firstOrNull { str ->
                            AccountSessionGuard.extractOwnerUinFromPetId(str) == friendUin.toString()
                        }.orEmpty()
                    }
                    if (friendPetId.isNotEmpty()) {
                        list.add(
                            HireableFriend(
                                uin = friendUin,
                                friendNick = friendNick,
                                petNick = petNick,
                                petId = friendPetId
                            )
                        )
                    }
                }
                val nextCookie = ProtoWire.firstString(data, 2) ?: ""
                val hasMore = (ProtoWire.firstVarint(data, 3) ?: 0L) != 0L
                Log.i(TAG, "fetchPetFriendsPage 回包: 解析到 ${list.size} 位可雇佣好友, hasMore=$hasMore")
                callback(0, list, hasMore, nextCookie, null)
            } else {
                Log.w(TAG, "fetchPetFriendsPage 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), false, "", errorMsg)
            }
        }
    }

    /**
     * 拆取/领取好友福袋金币（官方 0x9d71_0 / 40305 协议）
     */
    fun snatchCoinBag(
        ownPetId: String,
        coinbagId: String,
        callback: (SnatchCoinBagResult) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, ownPetId)
            .writeString(2, "")
            .writeString(3, coinbagId)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x9d71_0", 40305, 0, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val detailBytes = ProtoWire.firstBytes(data, 1)
                val bagBytes = ProtoWire.firstBytes(detailBytes, 1)
                val status = (ProtoWire.firstVarint(bagBytes, 5) ?: 0L).toInt()
                val alreadyOpened = (ProtoWire.firstVarint(bagBytes, 31) ?: 0L) != 0L

                val snatchInfoBytes = ProtoWire.firstBytes(data, 2)
                var gotGold = ProtoWire.firstVarint(snatchInfoBytes, 4) ?: 0L
                if (gotGold <= 0L && detailBytes != null) {
                    val snatchList = ProtoWire.allBytes(detailBytes, 2)
                    var sum = 0L
                    for (sBytes in snatchList) {
                        val pid = ProtoWire.firstString(sBytes, 2) ?: ""
                        if (pid == ownPetId) {
                            sum += (ProtoWire.firstVarint(sBytes, 4) ?: 0L)
                        }
                    }
                    gotGold = sum
                }
                Log.i(
                    TAG,
                    "snatchCoinBag 成功: bagId=$coinbagId, gotGold=$gotGold, status=$status, alreadyOpened=$alreadyOpened"
                )
               callback(SnatchCoinBagResult(0, coinbagId, gotGold, status, alreadyOpened, null))
           } else {
               Log.w(TAG, "snatchCoinBag 回包: bagId=$coinbagId, code=$code, err=$errorMsg")
               callback(SnatchCoinBagResult(code, coinbagId, 0L, 0, false, errorMsg))
           }
       }
   }

    /**
     * 查询好友小宠对战状态（官方 0x9875_1 / 39029 协议）
     */
    fun queryFriendPkStatus(
        friendUin: Long,
        friendPetId: String,
        ownPetId: String,
        callback: (code: Int, info: PkStatusInfo?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, friendPetId)
            .writeString(2, friendUin.toString())
            .writeString(3, ownPetId)
            .writeVarint(4, 0L)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x9875_1", 39029, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val pkStatusBytes = ProtoWire.firstBytes(data, 2)
                val rawStatus = (ProtoWire.firstVarint(pkStatusBytes, 1) ?: 0L).toInt()
                val countDown = ProtoWire.firstVarint(pkStatusBytes, 3) ?: 0L
                val storyId = ProtoWire.firstString(pkStatusBytes, 5)
                val canPk = (rawStatus == 100 || rawStatus == 300)
                Log.i(TAG, "queryFriendPkStatus 成功: uin=$friendUin, rawStatus=$rawStatus, canPk=$canPk, storyId=$storyId, countDown=$countDown")
                callback(0, PkStatusInfo(canPk, rawStatus, storyId, countDown), null)
            } else {
                Log.w(TAG, "queryFriendPkStatus 回包: code=$code, err=$errorMsg")
                callback(code, null, errorMsg)
            }
        }
    }

    /**
     * 发起与好友小宠的 PK 对决（官方 0x975e_1 / 38750 协议，eventType=6900）
     */
    fun startPkBattle(
        targetUin: Long,
        targetPetId: String,
        ownPetId: String,
        bodyguardId: String = "",
        callback: (PkBattleResult) -> Unit
    ) {
        val targetUserInfo = ProtoWire.message()
            .writeString(1, targetPetId)
            .writeString(2, targetUin.toString())
            .toByteArray()

        val msg = ProtoWire.message()
            .writeVarint(1, 6900L)
            .writeString(2, ownPetId)
            .writeString(3, "")
            .writeBytes(4, targetUserInfo)
        if (bodyguardId.isNotBlank()) {
            msg.writeString(5, bodyguardId)
        }
        val body = msg
            .writeString(6, "PK")
            .writeVarint(7, 6901L)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x975e_1", 38750, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
                val storyId = ProtoWire.firstString(data, 1)
                val statusInfoBytes = ProtoWire.firstBytes(data, 4)
                val leftDuration = ProtoWire.firstVarint(statusInfoBytes, 7) ?: 0L

                val battleInfoBytes = ProtoWire.firstBytes(data, 5)
                val mySideBytes = ProtoWire.firstBytes(battleInfoBytes, 1)
                val oppSideBytes = ProtoWire.firstBytes(battleInfoBytes, 2)

                val myPower = (ProtoWire.firstVarint(mySideBytes, 3) ?: 0L).toInt()
                val myNick = ProtoWire.firstString(mySideBytes, 5) ?: ""
                val oppPower = (ProtoWire.firstVarint(oppSideBytes, 3) ?: 0L).toInt()
                val oppNick = ProtoWire.firstString(oppSideBytes, 5) ?: ""
                val isWin = myPower > oppPower

                Log.i(TAG, "startPkBattle 成功: storyId=$storyId, 我方战力=$myPower($myNick), 对方战力=$oppPower($oppNick), 判定=${if (isWin) "胜利" else "惜败"}, 倒计时=${leftDuration}s")
                callback(PkBattleResult(0, storyId, myPower, oppPower, myNick, oppNick, isWin, leftDuration, null))
            } else {
                Log.w(TAG, "startPkBattle 回包失败: code=$code, err=$errorMsg")
                callback(PkBattleResult(code, null, errorMsg = errorMsg))
            }
        }
    }

    /**
     * 对决完成结算与领奖（官方 0x9760_1 / 38752 协议，page=6000）
     */
    fun settlePkBattle(
        storyId: String,
        ownPetId: String,
        callback: (PkSettleResult) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeString(1, storyId)
            .writeVarint(2, 6000L)
            .writeString(3, ownPetId)
            .writeVarint(4, 0L)
            .writeVarint(100, 2L)
            .toByteArray()

        sendOidb("OidbSvcTrpcTcp.0x9760_1", 38752, 1, body) { code, data, errorMsg ->
            if (code == 0 && data != null) {
               val endInfoBytes = ProtoWire.firstBytes(data, 1)
               val pkEndBytes = ProtoWire.firstBytes(endInfoBytes, 18)
               val title = ProtoWire.firstString(pkEndBytes, 1) ?: ProtoWire.firstString(endInfoBytes, 6)
               val desc = ProtoWire.firstString(pkEndBytes, 2) ?: ProtoWire.firstString(endInfoBytes, 7)
                var goldEarned = ProtoWire.firstVarint(pkEndBytes, 3) ?: ProtoWire.firstVarint(endInfoBytes, 3) ?: 0L
                if (goldEarned <= 0L || goldEarned > 1_000_000L) {
                    val candidateGold = ProtoWire.firstVarint(endInfoBytes, 4) ?: 0L
                    goldEarned = if (candidateGold in 1..1_000_000L) candidateGold else 0L
                }
               Log.i(TAG, "settlePkBattle 成功: storyId=$storyId, title=$title, desc=$desc, 获得金币=$goldEarned")
                callback(PkSettleResult(0, goldEarned, title, desc, null))
            } else {
                Log.w(TAG, "settlePkBattle 回包: storyId=$storyId, code=$code, err=$errorMsg")
                callback(PkSettleResult(code, 0L, null, null, errorMsg))
            }
        }
    }
}
