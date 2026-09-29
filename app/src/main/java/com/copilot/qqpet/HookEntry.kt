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
        const val MODULE_PACKAGE = "io.github.congsmile.qqpet"
        // 注意：applicationId 已变更，但编译期 namespace 仍是 com.copilot.qqpet，
        // 因此组件类名不能跟随 MODULE_PACKAGE，必须保持真实类路径。
        private const val MAIN_ACTIVITY_CLASS = "com.copilot.qqpet.ui.MainActivity"
        const val ACTION_TRIGGER_ADVENTURE = "io.github.congsmile.qqpet.ACTION_TRIGGER_ADVENTURE"
        const val ACTION_TRIGGER_ACTION = "io.github.congsmile.qqpet.ACTION_TRIGGER_ACTION"
        const val ACTION_UPDATE_CONFIG = "io.github.congsmile.qqpet.ACTION_UPDATE_CONFIG"

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
                MAIN_ACTIVITY_CLASS,
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

        if (globalEngine == null || globalBridge?.isReady != true) {
            try {
                val bridge = QQPetDirectBridge(classLoader)
                if (bridge.isReady) {
                    globalBridge = bridge
                    if (globalEngine == null) {
                        globalEngine = PetAdventureEngine(bridge).apply {
                            reloadConfig(appContext)
                        }
                    } else {
                        globalEngine?.updateBridge(bridge)
                    }
                    XposedBridge.log("[$TAG] 冒险探索发包内核就绪 (来源: $from, 类: ${QQPetDirectBridge.resolvedDelegateClass?.name})")
                } else if (globalEngine == null) {
                    globalBridge = bridge
                    globalEngine = PetAdventureEngine(bridge).apply {
                        reloadConfig(appContext)
                    }
                    XposedBridge.log("[$TAG] 发包内核暂未就绪，等待后续分包触发 (来源: $from)")
                }
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
                           val prefs = ctx.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                           val study = if (intent.hasExtra("extra_study")) intent.getBooleanExtra("extra_study", true) else prefs.getBoolean("key_study", true)
                           val work = if (intent.hasExtra("extra_work")) intent.getBooleanExtra("extra_work", true) else prefs.getBoolean("key_work", true)
                           val care = if (intent.hasExtra("extra_care")) intent.getBooleanExtra("extra_care", true) else prefs.getBoolean("key_care", true)
                           val adv = if (intent.hasExtra("extra_adventure")) intent.getBooleanExtra("extra_adventure", false) else prefs.getBoolean("key_adventure", false)
                           val settle = if (intent.hasExtra("extra_settle")) intent.getBooleanExtra("extra_settle", true) else prefs.getBoolean("key_settle", true)
                           val likeBack = if (intent.hasExtra("extra_like_back")) intent.getBooleanExtra("extra_like_back", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_LIKE_BACK, true)
                           val claimCoinBag = if (intent.hasExtra("extra_claim_coinbag")) intent.getBooleanExtra("extra_claim_coinbag", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_CLAIM_COINBAG, true)
                           val fatigueToAdv = if (intent.hasExtra("extra_fatigue_to_adventure")) intent.getBooleanExtra("extra_fatigue_to_adventure", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
                           val studyMode = if (intent.hasExtra("extra_study_mode")) intent.getIntExtra("extra_study_mode", 0) else prefs.getInt("key_study_mode", 0)
                           val workMode = if (intent.hasExtra("extra_work_mode")) intent.getIntExtra("extra_work_mode", 0) else prefs.getInt("key_work_mode", 0)
                           val schoolStage = if (intent.hasExtra("extra_school_stage")) intent.getIntExtra("extra_school_stage", 0) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_SCHOOL_STAGE, 0)
                           val courseSubject = if (intent.hasExtra("extra_course_subject")) intent.getIntExtra("extra_course_subject", 0) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_COURSE_SUBJECT, 0)
                           val courseDuration = if (intent.hasExtra("extra_course_duration")) intent.getIntExtra("extra_course_duration", 0) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_COURSE_DURATION, 0)
                           val workType = if (intent.hasExtra("extra_work_type")) intent.getIntExtra("extra_work_type", 0) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_WORK_TYPE, 0)
                           val workDuration = if (intent.hasExtra("extra_work_duration")) intent.getIntExtra("extra_work_duration", 0) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_WORK_DURATION, 0)
                           val careEnergyThreshold = if (intent.hasExtra("extra_care_energy_threshold")) intent.getIntExtra("extra_care_energy_threshold", 60) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
                           val careCleanThreshold = if (intent.hasExtra("extra_care_clean_threshold")) intent.getIntExtra("extra_care_clean_threshold", 60) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)

                           globalEngine?.updateConfig(study, work, care, adv, settle, likeBack, claimCoinBag, fatigueToAdv, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration, careEnergyThreshold, careCleanThreshold)
                           try {
                               val editor = prefs.edit()
                               if (intent.hasExtra("extra_study")) editor.putBoolean("key_study", study)
                               if (intent.hasExtra("extra_work")) editor.putBoolean("key_work", work)
                               if (intent.hasExtra("extra_care")) editor.putBoolean("key_care", care)
                               if (intent.hasExtra("extra_adventure")) editor.putBoolean("key_adventure", adv)
                               if (intent.hasExtra("extra_settle")) editor.putBoolean("key_settle", settle)
                               if (intent.hasExtra("extra_like_back")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_LIKE_BACK, likeBack)
                               if (intent.hasExtra("extra_claim_coinbag")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_CLAIM_COINBAG, claimCoinBag)
                               if (intent.hasExtra("extra_fatigue_to_adventure")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, fatigueToAdv)
                               if (intent.hasExtra("extra_study_mode")) editor.putInt("key_study_mode", studyMode)
                               if (intent.hasExtra("extra_work_mode")) editor.putInt("key_work_mode", workMode)
                               if (intent.hasExtra("extra_school_stage")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_SCHOOL_STAGE, schoolStage)
                               if (intent.hasExtra("extra_course_subject")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_COURSE_SUBJECT, courseSubject)
                               if (intent.hasExtra("extra_course_duration")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_COURSE_DURATION, courseDuration)
                               if (intent.hasExtra("extra_work_type")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_WORK_TYPE, workType)
                               if (intent.hasExtra("extra_work_duration")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_WORK_DURATION, workDuration)
                               if (intent.hasExtra("extra_care_energy_threshold")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, careEnergyThreshold)
                               if (intent.hasExtra("extra_care_clean_threshold")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, careCleanThreshold)
                               editor.commit()
                           } catch (_: Throwable) {}
                          XposedBridge.log("[$TAG] 跨进程配置更新: 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 阶段=$schoolStage, 科目=$courseSubject, 课时=$courseDuration, 工种=$workType, 工时=$workDuration, 体力阈值=$careEnergyThreshold, 清洁阈值=$careCleanThreshold")
                           globalEngine?.sendLog(ctx, "⚙️ [配置已同步] 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 阶段=$schoolStage, 科目=$courseSubject, 课时=$courseDuration, 工种=$workType, 工时=$workDuration, 体力阈值=$careEnergyThreshold, 清洁阈值=$careCleanThreshold")
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
