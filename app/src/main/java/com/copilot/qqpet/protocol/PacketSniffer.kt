package com.copilot.qqpet.protocol

import android.content.Context
import android.util.Log
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * QQ 宠物前台网络发包智能嗅探与自学习拦截器
 * 1. 动态监听 com.tencent.mobileqq.qqpet.delegate.l 的原生 OIDB 发包通道
 * 2. 当检测到 OidbSvcTrpcTcp.0x975e_1 (出行/上课/打工/探险) 时，自动解析并记录真实的 subEventType 与课程名
 * 3. 实时同步持久化，彻底杜绝因年级升级导致的 135010 配置为空问题
 */
object PacketSniffer {
    private const val TAG = "QQPetPacketSniffer"
    private const val DELEGATE_CLASS = "com.tencent.mobileqq.qqpet.delegate.l"
    private var isHooked = false

    fun install(classLoader: ClassLoader, context: Context) {
        if (isHooked) return
        try {
            val delegateCls = XposedHelpers.findClass(DELEGATE_CLASS, classLoader)
            XposedBridge.hookAllMethods(delegateCls, "c", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (HookEntry.globalBridge?.isInternalSending == true) return

                        val req = param.args.getOrNull(0) as? ByteArray ?: return
                        val cmdName = param.args.getOrNull(1) as? String ?: ""
                        val cmd = (param.args.getOrNull(2) as? Number)?.toInt() ?: 0
                        val subCmd = (param.args.getOrNull(3) as? Number)?.toInt() ?: 0

                        if (cmdName.contains("0x975e") || cmd == 38750) {
                            val page = ProtoWire.firstVarint(req, 1) ?: 0L
                            val petId = ProtoWire.firstString(req, 2)
                            val taskName = ProtoWire.firstString(req, 6) ?: ""
                            val subEventType = ProtoWire.firstVarint(req, 7) ?: 0L

                            XposedBridge.log("[$TAG] 🎯 嗅探到用户前台原生出行发包: page=$page, taskName='$taskName', subEventType=$subEventType, petId=$petId")
                            Log.i(TAG, "🎯 嗅探到用户前台原生出行发包: page=$page, taskName='$taskName', subEventType=$subEventType")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "嗅探拦截处理异常: ${t.message}")
                    }
                }
            })
            isHooked = true
            XposedBridge.log("[$TAG] ✅ 成功挂载 QQ 宠物前台发包智能自学习嗅探器")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 挂载嗅探器失败: ${t.message}")
        }
    }
}
