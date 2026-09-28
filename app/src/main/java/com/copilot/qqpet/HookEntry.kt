package com.copilot.qqpet

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.hook.QQSettingInjector
import com.copilot.qqpet.protocol.PacketSniffer
import com.copilot.qqpet.protocol.QQPetDirectBridge
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "QQPetCopilot"
        const val TARGET_PACKAGE = "com.tencent.mobileqq"
        const val MODULE_PACKAGE = "com.copilot.qqpet"
        const val ACTION_TRIGGER_ADVENTURE = "com.copilot.qqpet.ACTION_TRIGGER_ADVENTURE"
        const val ACTION_TRIGGER_ACTION = "com.copilot.qqpet.ACTION_TRIGGER_ACTION"
        const val ACTION_UPDATE_CONFIG = "com.copilot.qqpet.ACTION_UPDATE_CONFIG"

        private var isReceiverRegistered = false
        @Volatile
        var globalEngine: PetAdventureEngine? = null
        @Volatile
        var globalBridge: QQPetDirectBridge? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 本模块自身激活自检 Hook
        if (lpparam.packageName == MODULE_PACKAGE) {
            XposedHelpers.findAndHookMethod(
                "$MODULE_PACKAGE.ui.MainActivity",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            return
        }

        // 2. 仅拦截目标应用 QQ 的主进程
        if (lpparam.packageName != TARGET_PACKAGE || lpparam.processName != TARGET_PACKAGE) {
            return
        }

        XposedBridge.log("[$TAG] 成功注入 QQ 主进程: ${lpparam.processName}, PID=${android.os.Process.myPid()}")

        // 挂钩 1: BaseApplicationImpl.onCreate (获取真实分包完成后的 ClassLoader)
        try {
            val baseAppCls = lpparam.classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            XposedHelpers.findAndHookMethod(
                baseAppCls,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Context ?: return
                        val appLoader = app.classLoader
                        XposedBridge.log("[$TAG] BaseApplicationImpl.onCreate 触发, classLoader=$appLoader")
                        initEngineAndReceiver(app, appLoader, "BaseApplicationImpl.onCreate")
                        QQSettingInjector.inject(appLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Hook BaseApplicationImpl 异常: ${t.message}")
        }

        // 挂钩 2: MobileQQ.onCreate
        try {
            XposedHelpers.findAndHookMethod(
                "mqq.app.MobileQQ",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        initEngineAndReceiver(context, context.classLoader, "MobileQQ.onCreate")
                        QQSettingInjector.inject(context.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Hook MobileQQ.onCreate 异常: ${t.message}")
        }

        // 挂钩 3: Activity.onCreate (界面层最高优先级，动态注入设置项提供者)
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            initEngineAndReceiver(activity, activity.classLoader, "Activity.onCreate: ${activity.javaClass.simpleName}")
                            QQSettingInjector.inject(activity.classLoader)
                            globalEngine?.startBackgroundLoop(activity.applicationContext)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Hook Activity.onCreate 异常: ${t.message}")
        }

        // 挂钩 4: Activity.onResume (切回前台保活触发)
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            globalEngine?.startBackgroundLoop(activity.applicationContext)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun initEngineAndReceiver(context: Context, classLoader: ClassLoader, from: String) {
        val appContext = context.applicationContext ?: context

        if (globalEngine == null) {
            try {
                val bridge = QQPetDirectBridge(classLoader)
                globalBridge = bridge
                globalEngine = PetAdventureEngine(bridge).apply {
                    reloadConfig(appContext)
                }
                XposedBridge.log("[$TAG] 冒险探索发包内核初始化成功 (来源: $from)")
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] 初始化发包内核失败: ${t.message}")
            }
        }

        // 动态挂载原生 OIDB 发包智能自学习嗅探器
        try {
            PacketSniffer.install(classLoader, appContext)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 启动 PacketSniffer 异常: ${t.message}")
        }

        if (!isReceiverRegistered) {
            registerAdventureReceiver(appContext)
            isReceiverRegistered = true
            XposedBridge.log("[$TAG] 跨进程广播接收器注册就绪 (来源: $from)")
            globalEngine?.sendReadySignal(appContext)
        }

        // 检查登录状态并启动后台循环
        try {
            val mobileQQClass = XposedHelpers.findClass("mqq.app.MobileQQ", classLoader)
            val sMobileQQ = XposedHelpers.getStaticObjectField(mobileQQClass, "sMobileQQ")
            if (sMobileQQ != null) {
                val runtime = XposedHelpers.callMethod(sMobileQQ, "peekAppRuntime")
                if (runtime != null) {
                    val isLogin = XposedHelpers.callMethod(runtime, "isLogin") as? Boolean ?: false
                    if (isLogin) {
                        val uin = XposedHelpers.callMethod(runtime, "getCurrentAccountUin") as? String
                        XposedBridge.log("[$TAG] QQ 账号已登录: UIN=$uin，自动启动后台常驻探险轮询！")
                        globalEngine?.startBackgroundLoop(appContext)
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 检查登录状态异常: ${t.message}")
        }
    }

    private fun registerAdventureReceiver(context: Context) {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_TRIGGER_ADVENTURE)
                addAction(ACTION_TRIGGER_ACTION)
                addAction(ACTION_UPDATE_CONFIG)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_UPDATE_CONFIG -> {
                            val study = intent.getBooleanExtra("extra_study", true)
                            val work = intent.getBooleanExtra("extra_work", true)
                            val care = intent.getBooleanExtra("extra_care", true)
                            val adv = intent.getBooleanExtra("extra_adventure", true)
                            val settle = intent.getBooleanExtra("extra_settle", true)
                            val likeBack = intent.getBooleanExtra("extra_like_back", true)
                            val studyMode = intent.getIntExtra("extra_study_mode", 0)
                            val workMode = intent.getIntExtra("extra_work_mode", 0)
                            val schoolStage = intent.getIntExtra("extra_school_stage", 0)
                            val courseSubject = intent.getIntExtra("extra_course_subject", 0)
                            val courseDuration = intent.getIntExtra("extra_course_duration", 0)
                            val workType = intent.getIntExtra("extra_work_type", 0)
                            val workDuration = intent.getIntExtra("extra_work_duration", 0)
                            globalEngine?.updateConfig(study, work, care, adv, settle, likeBack, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration)
                            try {
                                val prefs = ctx.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putBoolean("key_study", study)
                                    .putBoolean("key_work", work)
                                    .putBoolean("key_care", care)
                                    .putBoolean("key_adventure", adv)
                                    .putBoolean("key_settle", settle)
                                    .putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_LIKE_BACK, likeBack)
                                    .putInt("key_study_mode", studyMode)
                                    .putInt("key_work_mode", workMode)
                                    .putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_SCHOOL_STAGE, schoolStage)
                                    .putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_COURSE_SUBJECT, courseSubject)
                                    .putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_COURSE_DURATION, courseDuration)
                                    .putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_WORK_TYPE, workType)
                                    .putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_WORK_DURATION, workDuration)
                                    .commit()
                            } catch (_: Throwable) {}
                            XposedBridge.log("[$TAG] 跨进程配置更新: 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 阶段=$schoolStage, 科目=$courseSubject, 课时=$courseDuration, 工种=$workType, 工时=$workDuration")
                            globalEngine?.sendLog(ctx, "⚙️ [配置已同步] 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 阶段=$schoolStage, 科目=$courseSubject, 课时=$courseDuration, 工种=$workType, 工时=$workDuration")
                        }
                        ACTION_TRIGGER_ACTION -> {
                            val action = intent.getStringExtra(PetAdventureEngine.EXTRA_ACTION) ?: "cycle"
                            XposedBridge.log("[$TAG] 收到动作指令: $action")
                            globalEngine?.runAction(ctx, action)
                        }
                        ACTION_TRIGGER_ADVENTURE -> {
                            XposedBridge.log("[$TAG] 收到一键测试冒险探索指令！")
                            globalEngine?.runAction(ctx, "adventure")
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            XposedBridge.log("[$TAG] 广播接收器注册成功")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 注册广播失败: ${t.message}")
        }
    }
}
