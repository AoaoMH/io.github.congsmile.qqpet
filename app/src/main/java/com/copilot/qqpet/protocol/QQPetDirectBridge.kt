package com.copilot.qqpet.protocol

import android.util.Base64
import android.util.Log
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
        val reward: String = ""
    )

    data class SchoolStageInfo(
        val stage: Int,
        val title: String,
        val limitStatus: Int,
        val isGraduated: Boolean
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

    companion object {
        private const val TAG = "QQPetDirectBridge"
        private const val DELEGATE_CLASS = "com.tencent.mobileqq.qqpet.delegate.l"
        private const val OBSERVER_CLASS = "com.tencent.ergo.hostdelegate.pb.PetPbDelegate\$a"
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
            val delegateCls = Class.forName(DELEGATE_CLASS, true, classLoader)
            observerClass = Class.forName(OBSERVER_CLASS, true, classLoader)
            val constructor: Constructor<*> = delegateCls.getDeclaredConstructor().apply {
                isAccessible = true
            }
            delegateInstance = constructor.newInstance()
            sendOidbMethod = delegateCls.getMethod(
                "c",
                ByteArray::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                observerClass
            )
            isReady = true
            Log.d(TAG, "成功反射挂载 QQ 宠物原生发包代理 delegate.l")
        } catch (t: Throwable) {
            Log.e(TAG, "反射 QQ 发包代理失败: ${t.message}", t)
        }
    }

    /**
     * 解析主人 UIN (优先从 base64 宠物ID 解析，兜底从宿主 MobileQQ 运行时获取)
     */
    fun resolveUin(petId: String): String {
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
                try {
                    val rspCls = classLoader.loadClass("gi5.j")
                    val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
                    val mergeMethod = nanoCls.getMethod("mergeFrom", nanoCls, ByteArray::class.java)
                    val inst = rspCls.newInstance()
                    val parsed = mergeMethod.invoke(null, inst, data)
                    val sb = StringBuilder("gi5.j 字段:")
                    for (f in rspCls.declaredFields) {
                        f.isAccessible = true
                        val v = f.get(parsed)
                        sb.append(" ${f.name}=").append(v)
                    }
                    Log.i(TAG, sb.toString())
                } catch (t: Throwable) {
                    Log.e(TAG, "反射 gi5.j 异常: ${t.message}")
                }
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
     */
    fun feed(
        petId: String,
        foodId: Long = 0L,
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val targetFoodId = if (foodId > 0L) foodId else resolveFoodId()
        val uin = resolveUin(petId)
        var bodyBytes: ByteArray? = null
        try {
            val bCls = classLoader.loadClass("zh5.b")
            val bInst = bCls.newInstance()
            bCls.getField("a").set(bInst, uin)
            bCls.getField("b").set(bInst, "")
            bCls.getField("c").set(bInst, "")
            bCls.getField("d").set(bInst, petId)
            bCls.getField("e").set(bInst, targetFoodId.toInt())
            val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            bodyBytes = toByteArrayMethod.invoke(null, bInst) as ByteArray
            Log.d(TAG, "通过 zh5.b 反射构造喂食包成功: uin=$uin, petId=$petId, foodId=$targetFoodId")
        } catch (t: Throwable) {
            Log.w(TAG, "zh5.b 反射未就绪: ${t.message}，使用 ProtoWire 编码")
        }

        if (bodyBytes == null) {
            bodyBytes = ProtoWire.message()
                .writeString(1, uin)
                .writeString(2, "")
                .writeString(3, "")
                .writeString(4, petId)
                .writeVarint(5, targetFoodId)
                .toByteArray()
        }
        sendOidb("OidbSvcTrpcTcp.0x992d_1", 39213, 1, bodyBytes) { code, data, err -> callback(code, data, err) }
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
        callback: (code: Int, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val uin = resolveUin(petId)
        var bodyBytes: ByteArray? = null
        try {
            val dCls = classLoader.loadClass("ci5.d")
            val dInst = dCls.newInstance()
            dCls.getField("a").set(dInst, petId)
            dCls.getField("b").set(dInst, uin)
            val jCls = classLoader.loadClass("uh5.j")
            val jInst = jCls.newInstance()
            jCls.getField("a").set(jInst, 5000)
            jCls.getField("b").set(jInst, 500)
            jCls.getField("c").set(jInst, 501)
            dCls.getField("c").set(dInst, jInst)
            val bCls = classLoader.loadClass("ci5.b")
            val bInst = bCls.newInstance()
            bCls.getField("d").set(bInst, cleanValue)
            dCls.getField("e").set(dInst, bInst)
            val nanoCls = classLoader.loadClass("com.google.protobuf.nano.MessageNano")
            val toByteArrayMethod = nanoCls.getMethod("toByteArray", nanoCls)
            bodyBytes = toByteArrayMethod.invoke(null, dInst) as ByteArray
            Log.d(TAG, "通过 ci5.d 反射构造清洁上报包成功")
        } catch (t: Throwable) {
            Log.w(TAG, "ci5.d 反射未就绪: ${t.message}，使用 ProtoWire 编码")
        }

        if (bodyBytes == null) {
            val pathBytes = ProtoWire.message()
                .writeVarint(1, 5000L)
                .writeVarint(2, 500L)
                .writeVarint(3, 501L)
                .toByteArray()
            val extBytes = ProtoWire.message()
                .writeVarint(4, cleanValue.toLong())
                .toByteArray()
            bodyBytes = ProtoWire.message()
                .writeString(1, petId)
                .writeString(2, uin)
                .writeBytes(3, pathBytes)
                .writeBytes(5, extBytes)
                .toByteArray()
        }
        sendOidb("OidbSvcTrpcTcp.0x96a6_1", 38566, 1, bodyBytes) { code, data, err -> callback(code, data, err) }
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
        startSceneTask(6700L, petId, adventureName, subEventType, callback)
    }

    /**
     * 发起兼职打工
     */
    fun startWork(
        petId: String,
        jobName: String = "小镇兼职",
        page: Long = 6400L,
        subEventType: Long = 6401L,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        startSceneTask(page, petId, jobName, subEventType, callback)
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
        startSceneTask(page, petId, courseName, subEventType, callback)
    }

    private fun startSceneTask(
        page: Long,
        petId: String,
        taskName: String,
        subEventType: Long,
        callback: (code: Int, storyId: String?, rawData: ByteArray?, errorMsg: String?) -> Unit
    ) {
        val body = ProtoWire.message()
            .writeVarint(1, page)
            .writeString(2, petId)
            .writeString(3, "")
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
                stage = (ProtoWire.firstVarint(data, 4) ?: 0L).toInt()
                lastSub = ProtoWire.firstVarint(data, 5) ?: 0L
                val itemBytesList = ProtoWire.allBytes(data, 1)
                for (itemBytes in itemBytesList) {
                    val title = ProtoWire.firstString(itemBytes, 1) ?: ""
                    val limitStatus = (ProtoWire.firstVarint(itemBytes, 4) ?: 0L).toInt()
                    val stg = (ProtoWire.firstVarint(itemBytes, 21) ?: 0L).toInt()
                    val graduated = (ProtoWire.firstVarint(itemBytes, 23) ?: 0L) != 0L
                    if (stg > 0) {
                        stageList.add(SchoolStageInfo(stg, title, limitStatus, graduated))
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
                val itemBytesList = ProtoWire.allBytes(data, 1)
                for (itemBytes in itemBytesList) {
                    val name = ProtoWire.firstString(itemBytes, 1) ?: ""
                    val sub = ProtoWire.firstVarint(itemBytes, 52) ?: 0L
                    val can = (ProtoWire.firstVarint(itemBytes, 50) ?: 0L) != 0L
                    val level = (ProtoWire.firstVarint(itemBytes, 3) ?: 0L).toInt()
                    val cost = ProtoWire.firstString(itemBytes, 6) ?: ""
                    val costTime = ProtoWire.firstString(itemBytes, 7) ?: ""
                    val reward = ProtoWire.firstString(itemBytes, 8) ?: ""
                    Log.d(TAG, "[$eventType-EventItem] name='$name', sub=$sub, can=$can, level=$level, cost='$cost', time='$costTime', reward='$reward'")
                    if (name.isNotEmpty() && sub > 0L) {
                        list.add(SelectEvent(name, sub, can, level, cost, costTime, reward))
                    }
                }
                Log.i(TAG, "querySelectEvents 回包: eventType=$eventType, stage=$schoolStage, career=$careerType, 解析到 ${list.size} 个可用事件")
                callback(0, list, data, null)
            } else {
                Log.w(TAG, "querySelectEvents 失败: code=$code, err=$errorMsg")
                callback(code, emptyList(), data, errorMsg)
            }
        }
    }
}
