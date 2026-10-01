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
import com.copilot.qqpet.engine.StealthScheduler
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.hook.QQSettingInjector
import com.copilot.qqpet.protocol.PacketSniffer
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        @Volatile
        private var isSplashHooked = false
        private var isReceiverRegistered = false
        @Volatile
        private var loginPollJob: Job? = null
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

        HookLog.log(TAG, "成功注入 QQ 主进程: ${lpparam.processName}, PID=${android.os.Process.myPid()}")

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
                        HookLog.log(TAG, "BaseApplicationImpl.onCreate 触发, classLoader=$appLoader")
                        initEngineAndReceiver(app, appLoader, "BaseApplicationImpl.onCreate")
                        hookSplashActivity(appLoader)
                        QQSettingInjector.inject(appLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook BaseApplicationImpl 异常: ${t.message}")
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
                        hookSplashActivity(context.classLoader)
                        QQSettingInjector.inject(context.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook MobileQQ.onCreate 异常: ${t.message}")
        }

        // 挂钩 3: 仅针对 QQ 主界面 SplashActivity.onResume 触发保活与会话校准，坚决不挂钩全局 Activity 基类
        hookSplashActivity(lpparam.classLoader)
    }

    private fun hookSplashActivity(classLoader: ClassLoader) {
        if (isSplashHooked) return
        try {
            val splashCls = classLoader.loadClass("com.tencent.mobileqq.activity.SplashActivity")
            XposedHelpers.findAndHookMethod(
                splashCls,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            val appContext = activity.applicationContext ?: activity
                            globalEngine?.verifyAndSyncAccountSession(appContext)
                            globalEngine?.startBackgroundLoop(appContext)
                        }
                    }
                }
            )
            isSplashHooked = true
            HookLog.log(TAG, "已成功挂钩 SplashActivity.onResume 主界面保活")
        } catch (_: Throwable) {}
    }

    private fun initEngineAndReceiver(context: Context, classLoader: ClassLoader, from: String) {
        val appContext = context.applicationContext ?: context
        try {
            val prefs = appContext.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            HookLog.isDebugEnabled = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
        } catch (_: Throwable) {}

        // 底层图灵盾与协议安全上报保持 100% 原生纯净放行，防范设备指纹缺失与探针超时引发的云端踢出

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
                    HookLog.log(TAG, "冒险探索发包内核就绪 (来源: $from, 类: ${QQPetDirectBridge.resolvedDelegateClass?.name})")
                } else if (globalEngine == null) {
                    globalBridge = bridge
                    globalEngine = PetAdventureEngine(bridge).apply {
                        reloadConfig(appContext)
                    }
                    HookLog.log(TAG, "发包内核暂未就绪，等待后续分包触发 (来源: $from)")
                }
            } catch (t: Throwable) {
                HookLog.log(TAG, "初始化发包内核失败: ${t.message}")
            }
        }

        // 动态挂载原生 OIDB 发包智能自学习嗅探器
        try {
            PacketSniffer.install(classLoader, appContext)
        } catch (t: Throwable) {
            HookLog.log(TAG, "启动 PacketSniffer 异常: ${t.message}")
        }

        if (!isReceiverRegistered) {
            registerAdventureReceiver(appContext)
            isReceiverRegistered = true
            HookLog.log(TAG, "跨进程广播接收器注册就绪 (来源: $from)")
            globalEngine?.sendReadySignal(appContext)
        }

        // 检查登录状态并启动后台循环（支持异步重试探测）
        checkLoginAndStartLoop(appContext, classLoader, from)
    }

    private fun checkLoginAndStartLoop(appContext: Context, classLoader: ClassLoader, from: String) {
        // 1. 即时检测：如果此时已登录，直接拉起
        if (tryStartLoopIfLoggedIn(appContext, classLoader, from)) {
            return
        }

        // 2. 异步轮询探测：宿主冷启动时，AccountRuntime 鉴权模块通常在 onCreate 之后数秒异步就绪
        if (loginPollJob?.isActive == true) return
        loginPollJob = CoroutineScope(Dispatchers.IO).launch {
            val retryDelays = longArrayOf(1500L, 3000L, 5000L, 8000L, 12000L, 20000L, 30000L)
            for (delayMs in retryDelays) {
                delay(delayMs)
                if (PetAdventureEngine.isLoopRunning) break
                val started = tryStartLoopIfLoggedIn(appContext, classLoader, "异步复检:$from")
                if (started) break
            }
        }
    }

    private fun tryStartLoopIfLoggedIn(appContext: Context, classLoader: ClassLoader, from: String): Boolean {
        try {
            val mobileQQClass = XposedHelpers.findClass("mqq.app.MobileQQ", classLoader)
            val sMobileQQ = XposedHelpers.getStaticObjectField(mobileQQClass, "sMobileQQ") ?: return false
            val runtime = XposedHelpers.callMethod(sMobileQQ, "peekAppRuntime") ?: return false
            val isLogin = XposedHelpers.callMethod(runtime, "isLogin") as? Boolean ?: false
            if (isLogin) {
                val uin = XposedHelpers.callMethod(runtime, "getCurrentAccountUin") as? String
                HookLog.log(TAG, "QQ 账号已登录: UIN=$uin (来源: $from)，自动启动后台常驻探险轮询！")
                globalEngine?.startBackgroundLoop(appContext)
                return true
            }
        } catch (t: Throwable) {
            HookLog.log(TAG, "检查登录状态异常: ${t.message}")
        }
        return false
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
                   val callingPkg = intent.`package`
                   if (callingPkg != null && callingPkg != MODULE_PACKAGE && callingPkg != TARGET_PACKAGE) {
                       HookLog.log(TAG, "拒收未受信任来源的跨进程广播: $callingPkg")
                       return
                   }
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
                         val humanLikeSleep = if (intent.hasExtra("extra_human_like_sleep")) intent.getBooleanExtra("extra_human_like_sleep", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
                         val nightSleep = if (intent.hasExtra("extra_night_sleep_mode")) intent.getBooleanExtra("extra_night_sleep_mode", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
                         val screenOffSilent = if (intent.hasExtra("extra_screen_off_silent")) intent.getBooleanExtra("extra_screen_off_silent", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
                         val hideSetting = if (intent.hasExtra("extra_hide_qq_setting_entry")) intent.getBooleanExtra("extra_hide_qq_setting_entry", false) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
                        val debugLog = if (intent.hasExtra("extra_debug_log")) intent.getBooleanExtra("extra_debug_log", false) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_DEBUG_LOG, false)
                         val hireFriend = if (intent.hasExtra("extra_hire_friend_enabled")) intent.getBooleanExtra("extra_hire_friend_enabled", true) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
                         val hireUinsCsv = if (intent.hasExtra("extra_hire_friend_uins")) (intent.getStringExtra("extra_hire_friend_uins") ?: "") else (prefs.getString(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIRE_FRIEND_UINS, "") ?: "")
                         val friendCareEnabled = if (intent.hasExtra("extra_friend_care_enabled")) intent.getBooleanExtra("extra_friend_care_enabled", false) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
                        val friendCareEnergy = if (intent.hasExtra("extra_friend_care_energy_threshold")) intent.getIntExtra("extra_friend_care_energy_threshold", 60) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
                        val friendCareClean = if (intent.hasExtra("extra_friend_care_clean_threshold")) intent.getIntExtra("extra_friend_care_clean_threshold", 60) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
                        val autoPk = if (intent.hasExtra("extra_auto_pk")) intent.getBooleanExtra("extra_auto_pk", false) else prefs.getBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_AUTO_PK, false)
                        val pkBlacklistUinsCsv = if (intent.hasExtra("extra_pk_blacklist_uins")) (intent.getStringExtra("extra_pk_blacklist_uins") ?: "") else (prefs.getString(com.copilot.qqpet.ui.PreferencesHelper.KEY_PK_BLACKLIST_UINS, "") ?: "")
                        val hiredRecallProgress = if (intent.hasExtra("extra_hired_recall_progress")) intent.getIntExtra("extra_hired_recall_progress", 72) else prefs.getInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)

                       HookLog.isDebugEnabled = debugLog

                        globalEngine?.updateConfig(study, work, care, adv, settle, likeBack, claimCoinBag, fatigueToAdv, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration, careEnergyThreshold, careCleanThreshold, humanLikeSleep, nightSleep, screenOffSilent, hideSetting, debugLog, hireFriend, hireUinsCsv, friendCareEnabled, friendCareEnergy, friendCareClean, autoPk, pkBlacklistUinsCsv, hiredRecallProgress)
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
                             if (intent.hasExtra("extra_human_like_sleep")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, humanLikeSleep)
                             if (intent.hasExtra("extra_night_sleep_mode")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_NIGHT_SLEEP_MODE, nightSleep)
                             if (intent.hasExtra("extra_screen_off_silent")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_SCREEN_OFF_SILENT, screenOffSilent)
                             if (intent.hasExtra("extra_hide_qq_setting_entry")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, hideSetting)
                             if (intent.hasExtra("extra_debug_log")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_DEBUG_LOG, debugLog)
                             if (intent.hasExtra("extra_hire_friend_enabled")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, hireFriend)
                             if (intent.hasExtra("extra_hire_friend_uins")) editor.putString(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIRE_FRIEND_UINS, hireUinsCsv)
                            if (intent.hasExtra("extra_friend_care_enabled")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_FRIEND_CARE_ENABLED, friendCareEnabled)
                            if (intent.hasExtra("extra_friend_care_energy_threshold")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, friendCareEnergy)
                            if (intent.hasExtra("extra_friend_care_clean_threshold")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, friendCareClean)
                            if (intent.hasExtra("extra_auto_pk")) editor.putBoolean(com.copilot.qqpet.ui.PreferencesHelper.KEY_AUTO_PK, autoPk)
                            if (intent.hasExtra("extra_pk_blacklist_uins")) editor.putString(com.copilot.qqpet.ui.PreferencesHelper.KEY_PK_BLACKLIST_UINS, pkBlacklistUinsCsv)
                            if (intent.hasExtra("extra_hired_recall_progress")) editor.putInt(com.copilot.qqpet.ui.PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, hiredRecallProgress)
                            editor.commit()
                        } catch (_: Throwable) {}
                          HookLog.log(TAG, "跨进程配置更新: 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 拟人休眠=$humanLikeSleep, 隐身=$hideSetting, 调试日志=$debugLog")
                           globalEngine?.sendLog(ctx, "⚙️ [配置已同步] 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 拟人休眠=$humanLikeSleep, 阶段=$schoolStage, 工种=$workType, 雇佣召回=${if (hiredRecallProgress > 0) "${hiredRecallProgress}%" else "关闭"}"); com.copilot.qqpet.engine.WakeLockHelper.wakeUpImmediately()
                       }
                        ACTION_TRIGGER_ACTION -> {
                            val action = intent.getStringExtra(PetAdventureEngine.EXTRA_ACTION) ?: "cycle"
                            HookLog.log(TAG, "收到动作指令: $action")
                            if (action == "query_work_places" || action == "query_account_status") {
                                globalEngine?.preloadAndBroadcastAccountStatus(ctx)
                            } else {
                                globalEngine?.runAction(ctx, action)
                            }
                        }
                        ACTION_TRIGGER_ADVENTURE -> {
                            HookLog.log(TAG, "收到一键测试冒险探索指令！")
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
            HookLog.log(TAG, "广播接收器注册成功")
        } catch (t: Throwable) {
            HookLog.log(TAG, "注册广播失败: ${t.message}")
        }
    }
}
