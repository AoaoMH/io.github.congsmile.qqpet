package com.copilot.qqpet.hook

import com.copilot.qqpet.hook.HookLog as Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * QQ 协议安全防护盾：
 * 1. 深度拦截腾讯安全风控与环境告密上报包 (trpc.o3.report / trpc.o3.mobile_security / OidbSvc.0xd79)
 * 2. 具备严格的返回类型推断与保护，绝不使用空指针改写基本类型，彻底杜绝 ClassCastException
 * 3. 挂钩 NTKickProcessor.b 防御服务端强制踢下线，守护本地登录会话
 */
object NetworkSecurityShield {

    private const val TAG = "NetworkSecurityShield"
    @Volatile
    private var isInstalled = false

    // 仅拦截纯告密与风险上报命令，绝不拦截 wtlogin.*、turing 握手等正常登录鉴权指令
    private val BLOCK_COMMANDS = arrayOf(
        "trpc.o3.report",
        "trpc.o3.mobile_security",
        "trpc.ilive_cdn.report",
        "OidbSvc.0xd79"
    )

    private fun shouldBlock(cmd: String?): Boolean {
        if (cmd.isNullOrEmpty()) return false
        for (target in BLOCK_COMMANDS) {
            if (cmd.contains(target)) return true
        }
        return false
    }

    fun install(classLoader: ClassLoader) {
        if (isInstalled) return
        isInstalled = true

       hookChannelProxyExt(classLoader)
       hookMsfCore(classLoader)
       hookKickBackstop(classLoader)
       hookTuringWrapper(classLoader)
        hookTuringRiskDetect(classLoader)
        hookTuringDID(classLoader)
        hookChannelReport(classLoader)
        hookTuringInit(classLoader)
        Log.d(TAG, "🛡️ 网络告密阻断盾与防踢下线保护层已就绪")
    }

    private fun hookChannelProxyExt(classLoader: ClassLoader) {
        val targetMethods = arrayOf("sendMessage", "sendMessageInner", "send")
        val clsName = "com.tencent.mobileqq.channel.ChannelProxyExt"

        val proxyCls = try {
            Class.forName(clsName, false, classLoader)
        } catch (_: Throwable) {
            return
        }

        for (m in proxyCls.declaredMethods) {
            if (m.name in targetMethods) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val cmd = param.args.firstOrNull() as? String ?: return
                            if (shouldBlock(cmd)) {
                                val currentMethod = param.method as? Method
                                val retType = currentMethod?.returnType ?: java.lang.Void.TYPE
                                if (retType.isPrimitive && retType != java.lang.Void.TYPE) {
                                    return
                                }
                                Log.d(TAG, "🛡️ [安全盾拦截] 阻断 ChannelProxyExt 上报: $cmd")

                                try {
                                    val cmClass = XposedHelpers.findClass("com.tencent.mobileqq.channel.ChannelManager", classLoader)
                                    val cmInstance = XposedHelpers.callStaticMethod(cmClass, "getInstance")
                                    val callbackId = if (param.args.size >= 3 && param.args[2] is Long) param.args[2] as Long else 0L
                                    try {
                                        XposedHelpers.callMethod(cmInstance, "onNativeReceive", cmd, ByteArray(0), true, 1000, callbackId)
                                    } catch (_: Throwable) {
                                        XposedHelpers.callMethod(cmInstance, "onNativeReceive", cmd, ByteArray(0), 0L)
                                    }
                                } catch (_: Throwable) {}

                                param.result = null
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookMsfCore(classLoader: ClassLoader) {
        val msfClsName = "com.tencent.mobileqq.msf.core.MsfCore"
        val msfCls = try {
            Class.forName(msfClsName, false, classLoader)
        } catch (_: Throwable) {
            return
        }

        for (m in msfCls.declaredMethods) {
            if (m.name == "sendSsoMsg") {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val toServiceMsg = param.args.firstOrNull() ?: return
                            val cmdObj = try {
                                XposedHelpers.callMethod(toServiceMsg, "getServiceCmd")
                            } catch (_: Throwable) {
                                null
                            }
                            val cmd = cmdObj?.toString()
                            if (shouldBlock(cmd)) {
                                val currentMethod = param.method as? Method
                                val retType = currentMethod?.returnType ?: java.lang.Void.TYPE
                                Log.d(TAG, "🛡️ [安全盾拦截] 阻断 MsfCore SSO 上报: $cmd (类型: ${retType.name})")
                                if (retType == java.lang.Integer.TYPE) {
                                    var seq = 0
                                    try {
                                        seq = XposedHelpers.callMethod(toServiceMsg, "getRequestSsoSeq") as Int
                                    } catch (_: Throwable) {}
                                    param.result = seq
                                } else if (retType == java.lang.Long.TYPE) {
                                    var seq = 0L
                                    try {
                                        seq = (XposedHelpers.callMethod(toServiceMsg, "getRequestSsoSeq") as Number).toLong()
                                    } catch (_: Throwable) {}
                                    param.result = seq
                                } else if (retType == java.lang.Void.TYPE) {
                                    param.result = null
                                } else {
                                    param.result = null
                                }
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookKickBackstop(classLoader: ClassLoader) {
        try {
            val kickCls = classLoader.loadClass("com.tencent.mobileqq.kick.NTKickProcessor")
            for (m in kickCls.declaredMethods) {
                if (m.name == "b" && m.returnType == java.lang.Void.TYPE) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            Log.d(TAG, "🛡️ [防踢兜底] 成功压制 NTKickProcessor.b 服务端踢下线，保护当前会话！")
                            param.result = null
                        }
                    })
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookTuringWrapper(classLoader: ClassLoader) {
        try {
            val turingCls = classLoader.loadClass("com.tencent.mobileqq.dt.model.TuringWrapper")
            for (m in turingCls.declaredMethods) {
                if ((m.name == "b" || m.name == "c") && m.returnType == java.lang.Void.TYPE) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 拦截图灵盾核心风险探测接口：reqRiskDetectV2 返回 RiskDetectResp 对象。
     * 图灵 SDK 内部对响应对象做了防御性空值校验，置空即可阻断异常环境评分上报。
     */
    private fun hookTuringRiskDetect(classLoader: ClassLoader) {
        val classes = arrayOf(
            "com.tencent.tfd.sdk.wxa.TuringRiskService",
            "com.tencent.turingfd.sdk.xq.TuringRiskService"
        )
        for (clsName in classes) {
            try {
                val cls = Class.forName(clsName, false, classLoader)
                for (m in cls.declaredMethods) {
                    if (m.name == "reqRiskDetectV2" && !m.returnType.isPrimitive) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                Log.d(TAG, "🛡️ [图灵拦截] 压制图灵核心风险检测: $clsName.reqRiskDetectV2")
                                param.result = null
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 拦截图灵设备指纹获取：getTuringDID / getTuringDIDCached 返回 ITuringDID，getTuringDIDAsync 返回 void。
     */
    private fun hookTuringDID(classLoader: ClassLoader) {
        val classes = arrayOf(
            "com.tencent.tfd.sdk.wxa.TuringIDService",
            "com.tencent.turingfd.sdk.xq.TuringIDService"
        )
        val targetMethods = setOf("getTuringDID", "getTuringDIDAsync", "getTuringDIDCached")
        for (clsName in classes) {
            try {
                val cls = Class.forName(clsName, false, classLoader)
                for (m in cls.declaredMethods) {
                    if (m.name in targetMethods && (!m.returnType.isPrimitive || m.returnType == java.lang.Void.TYPE)) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                Log.d(TAG, "🛡️ [图灵拦截] 阻断设备指纹获取: $clsName.${m.name}")
                                param.result = null
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 拦截 ChannelManager.checkMethod() 上报通道装载入口 (void 返回值)
     */
    private fun hookChannelReport(classLoader: ClassLoader) {
        try {
            val cmCls = Class.forName("com.tencent.mobileqq.channel.ChannelManager", false, classLoader)
            for (m in cmCls.declaredMethods) {
                if (m.name == "checkMethod" && m.returnType == java.lang.Void.TYPE) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 拦截图灵 SDK 初始化入口与外挂探测扫描
     */
    private fun hookTuringInit(classLoader: ClassLoader) {
        val turingClasses = arrayOf(
            "com.tencent.turingfd.sdk.xq.Pomegranate",
            "com.tencent.turingfd.sdk.xq.Blueberry",
            "com.tencent.turingcam.oqKCa"
        )
        for (clsName in turingClasses) {
            try {
                val cls = Class.forName(clsName, false, classLoader)
                for (m in cls.declaredMethods) {
                    if (m.name == "a" && (!m.returnType.isPrimitive || m.returnType == java.lang.Void.TYPE)) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = null
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}
