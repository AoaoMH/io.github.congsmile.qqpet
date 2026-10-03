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
import com.copilot.qqpet.engine.WakeLockHelper
import com.copilot.qqpet.hook.HookLog
import com.copilot.qqpet.hook.QQSettingInjector
import com.copilot.qqpet.hook.TinkerBlocker
import com.copilot.qqpet.protocol.PacketSniffer
import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.PreferencesHelper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*

class HookEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "QQPetCopilot"
        const val TARGET_PACKAGE = "com.tencent.mobileqq"
        const val MODULE_PACKAGE = "io.github.congsmile.qqpet"
        const val MAIN_ACTIVITY_CLASS = "com.copilot.qqpet.ui.MainActivity"

        const val ACTION_TRIGGER_ADVENTURE = "io.github.congsmile.qqpet.ACTION_TRIGGER_ADVENTURE"
        const val ACTION_TRIGGER_ACTION = "io.github.congsmile.qqpet.ACTION_TRIGGER_ACTION"
        const val ACTION_UPDATE_CONFIG = "io.github.congsmile.qqpet.ACTION_UPDATE_CONFIG"
        const val ACTION_PING = "io.github.congsmile.qqpet.ACTION_PING"
        const val ACTION_PONG = "io.github.congsmile.qqpet.ACTION_PONG"

        @Volatile
        var instance: HookEntry? = null
            private set

        @Volatile
        private var isSplashHooked = false
        private var isReceiverRegistered = false
        private var lastRegisteredContext: Context? = null
        private var adventureReceiver: BroadcastReceiver? = null
        @Volatile
        private var loginPollJob: Job? = null
        @Volatile
        var globalEngine: PetAdventureEngine? = null
        @Volatile
        var globalBridge: QQPetDirectBridge? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 本模块自身激活自检 Hook (针对支持模块自身作用域的框架)
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                XposedHelpers.findAndHookMethod(
                    MAIN_ACTIVITY_CLASS,
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
                )
                HookLog.log(TAG, "已成功挂钩自身 isModuleActive 返回 true (API 82)")
            } catch (t: Throwable) {
                HookLog.log(TAG, "Hook isModuleActive 异常: ${t.message}")
            }
            return
        }

        // 2. 仅拦截目标应用 QQ
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }

        instance = this
        HookLog.log(TAG, "成功注入 QQ 进程: ${lpparam.processName}, PID=${android.os.Process.myPid()} (API 82 经典引擎)")
        TinkerBlocker.install(lpparam.classLoader)

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

        // 挂钩 3: 针对通用 Activity.onCreate 提供超轻量单次设置项保底注入
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!QQSettingInjector.isHooked) {
                            val activity = param.thisObject as? Activity
                            if (activity != null && activity.packageName == TARGET_PACKAGE) {
                                QQSettingInjector.inject(activity.classLoader)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.log(TAG, "Hook Activity.onCreate 设置保底异常: ${t.message}")
        }

        // 挂钩 4: 针对 QQ 主界面 SplashActivity 触发保活、设置注入与会话校准
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
                            QQSettingInjector.inject(activity.classLoader)
                            globalEngine?.verifyAndSyncAccountSession(appContext)
                            globalEngine?.startBackgroundLoop(appContext)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                splashCls,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName == TARGET_PACKAGE) {
                            QQSettingInjector.inject(activity.classLoader)
                        }
                    }
                }
            )
            isSplashHooked = true
            HookLog.log(TAG, "已成功挂钩 SplashActivity 主界面保活与设置项注入 (API 82)")
        } catch (_: Throwable) {}
    }

    private fun initEngineAndReceiver(context: Context, classLoader: ClassLoader, from: String) {
        val appContext = context.applicationContext ?: context

        try {
            val prefs = appContext.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
            HookLog.isDebugEnabled = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
        } catch (_: Throwable) {}

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

        try {
            PacketSniffer.install(classLoader, appContext)
        } catch (t: Throwable) {
            HookLog.log(TAG, "启动 PacketSniffer 异常: ${t.message}")
        }

        TinkerBlocker.install(classLoader, appContext)

        if (!isReceiverRegistered) {
            registerAdventureReceiver(appContext)
            isReceiverRegistered = true
            HookLog.log(TAG, "跨进程广播接收器注册就绪 (来源: $from)")
            globalEngine?.sendReadySignal(appContext)
            sendPong(appContext, "内核启动")
        }

        checkLoginAndStartLoop(appContext, classLoader, from)
    }

    private fun checkLoginAndStartLoop(appContext: Context, classLoader: ClassLoader, from: String) {
        if (tryStartLoopIfLoggedIn(appContext, classLoader, from)) {
            return
        }

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
            val mobileQQClass = classLoader.loadClass("mqq.app.MobileQQ")
            val sMobileQQField = mobileQQClass.getDeclaredField("sMobileQQ").apply { isAccessible = true }
            val sMobileQQ = sMobileQQField.get(null) ?: return false
            val peekMethod = sMobileQQ.javaClass.getMethod("peekAppRuntime")
            val runtime = peekMethod.invoke(sMobileQQ) ?: return false
            val isLoginMethod = runtime.javaClass.getMethod("isLogin")
            val isLogin = isLoginMethod.invoke(runtime) as? Boolean ?: false
            if (isLogin) {
                val getUinMethod = runtime.javaClass.getMethod("getCurrentAccountUin")
                val uin = getUinMethod.invoke(runtime) as? String
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
        lastRegisteredContext = context
        val filter = IntentFilter().apply {
            addAction(ACTION_TRIGGER_ADVENTURE)
            addAction(ACTION_TRIGGER_ACTION)
            addAction(ACTION_UPDATE_CONFIG)
            addAction(ACTION_PING)
        }
        adventureReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PING -> {
                        HookLog.log(TAG, "收到伴侣 Ping 探测广播，立即回传 Pong 确认激活！")
                        sendPong(ctx, "收到Ping")
                    }
                    ACTION_UPDATE_CONFIG -> {
                        val prefs = ctx.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        val study = if (intent.hasExtra("extra_study")) intent.getBooleanExtra("extra_study", true) else prefs.getBoolean("key_study", true)
                        val work = if (intent.hasExtra("extra_work")) intent.getBooleanExtra("extra_work", true) else prefs.getBoolean("key_work", true)
                        val care = if (intent.hasExtra("extra_care")) intent.getBooleanExtra("extra_care", true) else prefs.getBoolean("key_care", true)
                        val adv = if (intent.hasExtra("extra_adventure")) intent.getBooleanExtra("extra_adventure", false) else prefs.getBoolean("key_adventure", false)
                        val settle = if (intent.hasExtra("extra_settle")) intent.getBooleanExtra("extra_settle", true) else prefs.getBoolean("key_settle", true)
                        val likeBack = if (intent.hasExtra("extra_like_back")) intent.getBooleanExtra("extra_like_back", true) else prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
                        val claimBag = if (intent.hasExtra("extra_claim_coinbag")) intent.getBooleanExtra("extra_claim_coinbag", true) else prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
                        val fatigueToAdv = if (intent.hasExtra("extra_fatigue_to_adventure")) intent.getBooleanExtra("extra_fatigue_to_adventure", true) else prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
                        val studyMode = if (intent.hasExtra("extra_study_mode")) intent.getIntExtra("extra_study_mode", 0) else prefs.getInt("key_study_mode", 0)
                        val workMode = if (intent.hasExtra("extra_work_mode")) intent.getIntExtra("extra_work_mode", 0) else prefs.getInt("key_work_mode", 0)
                        val schoolStage = if (intent.hasExtra("extra_school_stage")) intent.getIntExtra("extra_school_stage", 0) else prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
                        val courseSubject = if (intent.hasExtra("extra_course_subject")) intent.getIntExtra("extra_course_subject", 0) else prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
                        val courseDuration = if (intent.hasExtra("extra_course_duration")) intent.getIntExtra("extra_course_duration", 0) else prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
                        val workType = if (intent.hasExtra("extra_work_type")) intent.getIntExtra("extra_work_type", 0) else prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
                        val workDuration = if (intent.hasExtra("extra_work_duration")) intent.getIntExtra("extra_work_duration", 0) else prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
                        val careEnergyThreshold = if (intent.hasExtra("extra_care_energy_threshold")) intent.getIntExtra("extra_care_energy_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
                        val careCleanThreshold = if (intent.hasExtra("extra_care_clean_threshold")) intent.getIntExtra("extra_care_clean_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
                        val humanLikeSleep = if (intent.hasExtra("extra_human_like_sleep")) intent.getBooleanExtra("extra_human_like_sleep", true) else prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
                        val nightSleep = if (intent.hasExtra("extra_night_sleep_mode")) intent.getBooleanExtra("extra_night_sleep_mode", true) else prefs.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
                        val screenOffSilent = if (intent.hasExtra("extra_screen_off_silent")) intent.getBooleanExtra("extra_screen_off_silent", true) else prefs.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
                        val hideSetting = if (intent.hasExtra("extra_hide_qq_setting_entry")) intent.getBooleanExtra("extra_hide_qq_setting_entry", false) else prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
                        val debugLog = if (intent.hasExtra("extra_debug_log")) intent.getBooleanExtra("extra_debug_log", false) else prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
                        val hireFriend = if (intent.hasExtra("extra_hire_friend_enabled")) intent.getBooleanExtra("extra_hire_friend_enabled", true) else prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
                        val hireUinsCsv = if (intent.hasExtra("extra_hire_friend_uins")) (intent.getStringExtra("extra_hire_friend_uins") ?: "") else (prefs.getString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, "") ?: "")
                        val friendCareEnabled = if (intent.hasExtra("extra_friend_care_enabled")) intent.getBooleanExtra("extra_friend_care_enabled", false) else prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
                        val friendCareEnergy = if (intent.hasExtra("extra_friend_care_energy_threshold")) intent.getIntExtra("extra_friend_care_energy_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
                        val friendCareClean = if (intent.hasExtra("extra_friend_care_clean_threshold")) intent.getIntExtra("extra_friend_care_clean_threshold", 60) else prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
                        val autoPk = if (intent.hasExtra("extra_auto_pk")) intent.getBooleanExtra("extra_auto_pk", false) else prefs.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
                        val pkBlacklistUinsCsv = if (intent.hasExtra("extra_pk_blacklist_uins")) (intent.getStringExtra("extra_pk_blacklist_uins") ?: "") else (prefs.getString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, "") ?: "")
                        val hiredRecallProgress = if (intent.hasExtra("extra_hired_recall_progress")) intent.getIntExtra("extra_hired_recall_progress", 72) else prefs.getInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, 72)
                        val activeVisit = if (intent.hasExtra("extra_active_visit")) intent.getBooleanExtra("extra_active_visit", true) else prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, true)
                        val activeVisitFriends = if (intent.hasExtra("extra_active_visit_friends")) intent.getBooleanExtra("extra_active_visit_friends", true) else prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, true)
                        val activeVisitStrangers = if (intent.hasExtra("extra_active_visit_strangers")) intent.getBooleanExtra("extra_active_visit_strangers", true) else prefs.getBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, true)
                        val activeVisitDailyLimit = if (intent.hasExtra("extra_active_visit_daily_limit")) intent.getIntExtra("extra_active_visit_daily_limit", 20) else prefs.getInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, 20)
                        val disableTinker = if (intent.hasExtra("extra_disable_tinker_patch")) intent.getBooleanExtra("extra_disable_tinker_patch", false) else prefs.getBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, false)

                        globalEngine?.updateConfig(
                            study = study,
                            work = work,
                            care = care,
                            adventure = adv,
                            settle = settle,
                            likeBack = likeBack,
                            claimCoinBag = claimBag,
                            fatigueToAdventure = fatigueToAdv,
                            studyMode = studyMode,
                            workMode = workMode,
                            schoolStage = schoolStage,
                            courseSubject = courseSubject,
                            courseDuration = courseDuration,
                            workType = workType,
                            workDuration = workDuration,
                            careEnergyThreshold = careEnergyThreshold,
                            careCleanThreshold = careCleanThreshold,
                            humanLikeSleep = humanLikeSleep,
                            nightSleepMode = nightSleep,
                            screenOffSilent = screenOffSilent,
                            hideQQSettingEntry = hideSetting,
                            debugLog = debugLog,
                            hireFriend = hireFriend,
                            hireFriendUinsCsv = hireUinsCsv,
                            friendCareEnabled = friendCareEnabled,
                            friendCareEnergyThreshold = friendCareEnergy,
                            friendCareCleanThreshold = friendCareClean,
                            autoPk = autoPk,
                            pkBlacklistUinsCsv = pkBlacklistUinsCsv,
                            hiredRecallProgress = hiredRecallProgress,
                            activeVisit = activeVisit,
                            activeVisitFriends = activeVisitFriends,
                            activeVisitStrangers = activeVisitStrangers,
                            activeVisitDailyLimit = activeVisitDailyLimit
                        )

                        try {
                            if (intent.hasExtra("extra_study")) editor.putBoolean("key_study", study)
                            if (intent.hasExtra("extra_work")) editor.putBoolean("key_work", work)
                            if (intent.hasExtra("extra_care")) editor.putBoolean("key_care", care)
                            if (intent.hasExtra("extra_adventure")) editor.putBoolean("key_adventure", adv)
                            if (intent.hasExtra("extra_settle")) editor.putBoolean("key_settle", settle)
                            if (intent.hasExtra("extra_like_back")) editor.putBoolean(PreferencesHelper.KEY_LIKE_BACK, likeBack)
                            if (intent.hasExtra("extra_claim_coinbag")) editor.putBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, claimBag)
                            if (intent.hasExtra("extra_fatigue_to_adventure")) editor.putBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, fatigueToAdv)
                            if (intent.hasExtra("extra_study_mode")) editor.putInt("key_study_mode", studyMode)
                            if (intent.hasExtra("extra_work_mode")) editor.putInt("key_work_mode", workMode)
                            if (intent.hasExtra("extra_school_stage")) editor.putInt(PreferencesHelper.KEY_SCHOOL_STAGE, schoolStage)
                            if (intent.hasExtra("extra_course_subject")) editor.putInt(PreferencesHelper.KEY_COURSE_SUBJECT, courseSubject)
                            if (intent.hasExtra("extra_course_duration")) editor.putInt(PreferencesHelper.KEY_COURSE_DURATION, courseDuration)
                            if (intent.hasExtra("extra_work_type")) editor.putInt(PreferencesHelper.KEY_WORK_TYPE, workType)
                            if (intent.hasExtra("extra_work_duration")) editor.putInt(PreferencesHelper.KEY_WORK_DURATION, workDuration)
                            if (intent.hasExtra("extra_care_energy_threshold")) editor.putInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, careEnergyThreshold)
                            if (intent.hasExtra("extra_care_clean_threshold")) editor.putInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, careCleanThreshold)
                            if (intent.hasExtra("extra_human_like_sleep")) editor.putBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, humanLikeSleep)
                            if (intent.hasExtra("extra_night_sleep_mode")) editor.putBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, nightSleep)
                            if (intent.hasExtra("extra_screen_off_silent")) editor.putBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, screenOffSilent)
                            if (intent.hasExtra("extra_hide_qq_setting_entry")) editor.putBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, hideSetting)
                            if (intent.hasExtra("extra_debug_log")) editor.putBoolean(PreferencesHelper.KEY_DEBUG_LOG, debugLog)
                            if (intent.hasExtra("extra_hire_friend_enabled")) editor.putBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, hireFriend)
                            if (intent.hasExtra("extra_hire_friend_uins")) editor.putString(PreferencesHelper.KEY_HIRE_FRIEND_UINS, hireUinsCsv)
                            if (intent.hasExtra("extra_friend_care_enabled")) editor.putBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, friendCareEnabled)
                            if (intent.hasExtra("extra_friend_care_energy_threshold")) editor.putInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, friendCareEnergy)
                            if (intent.hasExtra("extra_friend_care_clean_threshold")) editor.putInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, friendCareClean)
                            if (intent.hasExtra("extra_auto_pk")) editor.putBoolean(PreferencesHelper.KEY_AUTO_PK, autoPk)
                            if (intent.hasExtra("extra_pk_blacklist_uins")) editor.putString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, pkBlacklistUinsCsv)
                            if (intent.hasExtra("extra_hired_recall_progress")) editor.putInt(PreferencesHelper.KEY_HIRED_RECALL_PROGRESS, hiredRecallProgress)
                            if (intent.hasExtra("extra_active_visit")) editor.putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_ENABLED, activeVisit)
                            if (intent.hasExtra("extra_active_visit_friends")) editor.putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_FRIENDS, activeVisitFriends)
                            if (intent.hasExtra("extra_active_visit_strangers")) editor.putBoolean(PreferencesHelper.KEY_ACTIVE_VISIT_STRANGERS, activeVisitStrangers)
                            if (intent.hasExtra("extra_active_visit_daily_limit")) editor.putInt(PreferencesHelper.KEY_ACTIVE_VISIT_DAILY_LIMIT, activeVisitDailyLimit)
                            if (intent.hasExtra("extra_disable_tinker_patch")) editor.putBoolean(PreferencesHelper.KEY_DISABLE_TINKER_PATCH, disableTinker)
                            editor.commit()
                        } catch (_: Throwable) {}
                        HookLog.log(TAG, "跨进程配置更新: 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 拟人休眠=$humanLikeSleep, 隐身=$hideSetting, 调试日志=$debugLog")
                        globalEngine?.sendLog(ctx, "⚙️ [配置已同步] 学习=$study, 打工=$work, 照顾=$care, 冒险=$adv, 结算=$settle, 拟人休眠=$humanLikeSleep, 阶段=$schoolStage, 工种=$workType, 雇佣召回=${if (hiredRecallProgress > 0) "${hiredRecallProgress}%" else "关闭"}"); WakeLockHelper.wakeUpImmediately()
                    }
                    ACTION_TRIGGER_ACTION -> {
                        val action = intent.getStringExtra(PetAdventureEngine.EXTRA_ACTION) ?: "cycle"
                        HookLog.log(TAG, "收到动作指令: $action")
                        if (action == "query_work_places" || action == "query_account_status") {
                            globalEngine?.preloadAndBroadcastAccountStatus(ctx)
                        } else {
                            globalEngine?.runAction(ctx, action)
                        }
                        sendPong(ctx, "执行指令:$action")
                    }
                    ACTION_TRIGGER_ADVENTURE -> {
                        HookLog.log(TAG, "收到一键测试冒险探索指令！")
                        globalEngine?.runAction(ctx, "adventure")
                        sendPong(ctx, "触发冒险")
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(adventureReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(adventureReceiver, filter)
        }
    }

    private fun sendPong(context: Context, reason: String) {
        try {
            val pongIntent = Intent(ACTION_PONG).apply {
                setPackage(MODULE_PACKAGE)
                putExtra("extra_time", System.currentTimeMillis())
                putExtra("extra_reason", reason)
                putExtra("extra_engine_ready", globalBridge?.isReady == true)
                putExtra("extra_loop_running", PetAdventureEngine.isLoopRunning)
            }
            context.sendBroadcast(pongIntent)
        } catch (t: Throwable) {
            HookLog.log(TAG, "回传 Pong 异常: ${t.message}")
        }
    }

    private fun unregisterAdventureReceiver() {
        if (isReceiverRegistered && adventureReceiver != null) {
            try {
                lastRegisteredContext?.unregisterReceiver(adventureReceiver)
            } catch (_: Throwable) {}
            adventureReceiver = null
            isReceiverRegistered = false
        }
    }
}
