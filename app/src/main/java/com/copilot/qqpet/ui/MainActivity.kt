package com.copilot.qqpet.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.SharedPreferences
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.R
import com.copilot.qqpet.databinding.ActivityMainBinding
import com.copilot.qqpet.engine.PetAdventureEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var logReceiver: BroadcastReceiver? = null
    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var workSegmentControl: AppleSegmentedControl? = null
    private var schoolSegmentControl: AppleSegmentedControl? = null
    private val currentWorkPlaceOptions = mutableListOf<WorkPlaceOption>()
    private val schoolStageOptions = mutableListOf<SegmentItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initStatusCard()
        initOfficialGroupCard()
        initSwitches()
        initCustomSelectionPanels()
        initActionButtons()
        registerLogReceiver()
    }

    override fun onResume() {
        super.onResume()
        try {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra(PetAdventureEngine.EXTRA_ACTION, "query_account_status")
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        logReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 检查模块激活状态（未被 Hook 时返回 false，激活后被 HookEntry 篡改为 true）
     */
    fun isModuleActive(): Boolean = false

    private fun initStatusCard() {
        val active = isModuleActive()
        if (active) {
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
            binding.tvStatusTitle.text = getString(R.string.status_active)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            binding.tvStatusTitle.text = getString(R.string.status_inactive)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
    }

    private fun initOfficialGroupCard() {
        binding.cardGroup.applyApplePressEffect()
        binding.btnJoinGroup.applyApplePressEffect()

        val joinAction = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            joinOfficialQQGroup()
        }
        binding.cardGroup.setOnClickListener(joinAction)
        binding.btnJoinGroup.setOnClickListener(joinAction)
    }

    private fun joinOfficialQQGroup(groupKey: String = "FY6w7PMH2c", groupNum: String = "1087942084") {
        try {
            val uri = Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$groupKey")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/$groupKey")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            } catch (_: Throwable) {
                Toast.makeText(this, "QQ 群号已复制: $groupNum", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initActionButtons() {
        binding.btnRunCycle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("cycle", "全流程策略调度循环")
        }
        binding.btnTestCare.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("care", "照顾实测 (喂食+洗澡)")
        }
        binding.btnTestWork.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("work", "兼职打工实测")
        }
        binding.btnTestSchool.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("school", "进阶学习实测")
        }
        binding.btnTestAdventure.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("adventure", "森林探险实测")
        }
       binding.btnTestSettle.setOnClickListener {
           it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
           sendActionToQQ("settle", "收益结算实测")
       }
       binding.btnTestFriendCare.setOnClickListener {
           it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
           sendActionToQQ("friend_care", "帮全部好友喂食与洗澡")
       }
        binding.btnTestPk.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendActionToQQ("pk_auto", "自动 PK 挑战 (10场实测)")
        }
       binding.btnClearLogs.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.tvEngineLogs.text = "日志已清空，等待下次测试..."
        }

        // 注入 iOS 物理触觉微动效
        binding.btnRunCycle.applyApplePressEffect()
        binding.btnTestCare.applyApplePressEffect()
        binding.btnTestWork.applyApplePressEffect()
        binding.btnTestSchool.applyApplePressEffect()
        binding.btnTestAdventure.applyApplePressEffect()
        binding.btnTestSettle.applyApplePressEffect()
        binding.btnTestFriendCare.applyApplePressEffect()
        binding.btnTestPk.applyApplePressEffect()
        binding.btnClearLogs.applyApplePressEffect()
    }

    private fun sendActionToQQ(action: String, actionName: String) {
        appendLog("[指令] 已向 QQ 下发「$actionName」调度广播...")
        try {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra(PetAdventureEngine.EXTRA_ACTION, action)
            }
            sendBroadcast(intent)
            Toast.makeText(this, "已下发 $actionName 指令", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            appendLog("[异常] 下发指令失败: ${t.message}")
        }
    }

    private fun registerLogReceiver() {
        val filter = IntentFilter().apply {
            addAction(PetAdventureEngine.ACTION_ENGINE_LOG)
            addAction(PetAdventureEngine.ACTION_SYNC_WORK_PLACES)
            addAction(PetAdventureEngine.ACTION_SYNC_ACCOUNT_STATUS)
        }
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    PetAdventureEngine.ACTION_SYNC_ACCOUNT_STATUS -> {
                        val workJson = intent.getStringExtra(PetAdventureEngine.EXTRA_WORK_PLACES_JSON)
                        if (!workJson.isNullOrEmpty()) {
                            updateWorkPlacesFromRemote(workJson)
                        }
                        val schoolJson = intent.getStringExtra(PetAdventureEngine.EXTRA_SCHOOL_DETAILS_JSON)
                        if (!schoolJson.isNullOrEmpty()) {
                            updateSchoolStagesFromRemote(schoolJson)
                        }
                    }
                    PetAdventureEngine.ACTION_SYNC_WORK_PLACES -> {
                        val jsonStr = intent.getStringExtra(PetAdventureEngine.EXTRA_WORK_PLACES_JSON)
                        if (!jsonStr.isNullOrEmpty()) {
                            updateWorkPlacesFromRemote(jsonStr)
                        }
                    }
                    else -> {
                        val msg = intent.getStringExtra(PetAdventureEngine.EXTRA_LOG_TEXT) ?: return
                        appendLog(msg)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    private fun appendLog(line: String) {
        val time = logDateFormat.format(Date())
        val currentText = binding.tvEngineLogs.text.toString()
        val newText = if (currentText.startsWith("等待") || currentText.startsWith("日志已清空")) {
            "[$time] $line"
        } else {
            "$currentText\n[$time] $line"
        }
        binding.tvEngineLogs.text = newText
    }

    private fun initSwitches() {
        val prefs = PreferencesHelper.getPrefs(this)

        // 读取初始值
        val studyOn = prefs.getBoolean(PreferencesHelper.KEY_STUDY, true)
        val workOn = prefs.getBoolean(PreferencesHelper.KEY_WORK, true)
        val careOn = prefs.getBoolean(PreferencesHelper.KEY_CARE, true)
        val advOn = prefs.getBoolean(PreferencesHelper.KEY_ADVENTURE, false)
        val fatigueToAdvOn = prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
        val settleOn = prefs.getBoolean(PreferencesHelper.KEY_SETTLE, true)
        val likeBackOn = prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
        val claimBagOn = prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
        val hireFriendOn = prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
        val friendCareOn = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)

        binding.switchStudy.isChecked = studyOn
        binding.switchWork.isChecked = workOn
        binding.switchCare.isChecked = careOn
        binding.switchAdventure.isChecked = advOn
        binding.switchFatigueToAdv.isChecked = fatigueToAdvOn
        binding.switchSettle.isChecked = settleOn
        binding.switchLikeBack.isChecked = likeBackOn
        binding.switchClaimCoinBag.isChecked = claimBagOn
        binding.switchHireFriend.isChecked = hireFriendOn
        binding.switchFriendCare.isChecked = friendCareOn
        binding.switchAutoPk.isChecked = prefs.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)

       binding.switchHumanLikeSleep.isChecked = prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
       binding.switchNightSleep.isChecked = prefs.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
       binding.switchScreenOffSilent.isChecked = prefs.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
       binding.switchHideQQSetting.isChecked = prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
       binding.switchDebugLog.isChecked = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)

        // 初始展开状态
        binding.layoutStudyOptions.visibility = if (studyOn) View.VISIBLE else View.GONE
        binding.layoutWorkOptions.visibility = if (workOn) View.VISIBLE else View.GONE
        binding.layoutCareOptions.visibility = if (careOn) View.VISIBLE else View.GONE
        binding.layoutAdvOptions.visibility = if (advOn) View.VISIBLE else View.GONE
        binding.layoutFriendCareOptions.visibility = if (friendCareOn) View.VISIBLE else View.GONE

        // 绑定修改监听与展开/折叠微动效
        binding.switchStudy.setOnCheckedChangeListener { _, isChecked ->
            binding.switchStudy.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_STUDY, isChecked).apply()
            animateExpandCollapse(binding.layoutStudyOptions, isChecked)
            syncConfigToQQ()
        }

        binding.switchWork.setOnCheckedChangeListener { _, isChecked ->
            binding.switchWork.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_WORK, isChecked).apply()
            animateExpandCollapse(binding.layoutWorkOptions, isChecked)
            syncConfigToQQ()
        }

        binding.switchCare.setOnCheckedChangeListener { _, isChecked ->
            binding.switchCare.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_CARE, isChecked).apply()
            animateExpandCollapse(binding.layoutCareOptions, isChecked)
            syncConfigToQQ()
        }

        binding.switchAdventure.setOnCheckedChangeListener { _, isChecked ->
            binding.switchAdventure.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_ADVENTURE, isChecked).apply()
            animateExpandCollapse(binding.layoutAdvOptions, isChecked)
            syncConfigToQQ()
        }

        binding.switchFatigueToAdv.setOnCheckedChangeListener { _, isChecked ->
            binding.switchFatigueToAdv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, isChecked).apply()
            syncConfigToQQ()
        }

        binding.switchSettle.setOnCheckedChangeListener { _, isChecked ->
            binding.switchSettle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_SETTLE, isChecked).apply()
            syncConfigToQQ()
        }

        binding.switchLikeBack.setOnCheckedChangeListener { _, isChecked ->
            binding.switchLikeBack.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_LIKE_BACK, isChecked).apply()
            syncConfigToQQ()
        }

        binding.switchClaimCoinBag.setOnCheckedChangeListener { _, isChecked ->
            binding.switchClaimCoinBag.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, isChecked).apply()
            syncConfigToQQ()
        }

        binding.switchHireFriend.setOnCheckedChangeListener { _, isChecked ->
            binding.switchHireFriend.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, isChecked).apply()
            syncConfigToQQ()
        }

        binding.switchFriendCare.setOnCheckedChangeListener { _, isChecked ->
            binding.switchFriendCare.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, isChecked).apply()
            animateExpandCollapse(binding.layoutFriendCareOptions, isChecked)
            syncConfigToQQ()
        }

        binding.switchAutoPk.setOnCheckedChangeListener { _, isChecked ->
            binding.switchAutoPk.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_AUTO_PK, isChecked).apply()
            syncConfigToQQ()
        }

        binding.switchHumanLikeSleep.setOnCheckedChangeListener { _, isChecked ->
            binding.switchHumanLikeSleep.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, isChecked).apply()
            syncConfigToQQ()
        }
       binding.switchNightSleep.setOnCheckedChangeListener { _, isChecked ->
           binding.switchNightSleep.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
           prefs.edit().putBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, isChecked).apply()
           syncConfigToQQ()
       }
       binding.switchScreenOffSilent.setOnCheckedChangeListener { _, isChecked ->
           binding.switchScreenOffSilent.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
           prefs.edit().putBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, isChecked).apply()
           syncConfigToQQ()
       }
       binding.switchHideQQSetting.setOnCheckedChangeListener { _, isChecked ->
            binding.switchHideQQSetting.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, isChecked).apply()
            syncConfigToQQ()
        }
        binding.switchDebugLog.setOnCheckedChangeListener { _, isChecked ->
            binding.switchDebugLog.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            prefs.edit().putBoolean(PreferencesHelper.KEY_DEBUG_LOG, isChecked).apply()
            syncConfigToQQ()
        }
    }

    private fun initCustomSelectionPanels() {
        val prefs = PreferencesHelper.getPrefs(this)

        // 1. 学园阶段分段器
        loadCachedOrFallbackSchoolStages(prefs)
        val currentStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val selStageIndex = currentStage.coerceIn(0, schoolStageOptions.size - 1)
        schoolSegmentControl = AppleSegmentedControl(
            this,
            schoolStageOptions,
            selectedIndex = selStageIndex,
            isScrollable = true
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_SCHOOL_STAGE, index).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerSchoolStage.removeAllViews()
        binding.containerSchoolStage.addView(schoolSegmentControl)

        // 2. 学习科目分段器 (横向滑动)
        val subjectItems = listOf("智能自适应", "文科", "理科", "武科", "魔法", "艺术", "体育")
        val currentSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val subjectControl = AppleSegmentedControl(
            this,
            subjectItems,
            selectedIndex = currentSubject.coerceIn(0, 6),
            isScrollable = true
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_SUBJECT, index).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerCourseSubject.removeAllViews()
        binding.containerCourseSubject.addView(subjectControl)

        // 3. 学习时长分段器
        val studyDurationItems = listOf("智能自适应", "短课(1h)", "常规(2h)", "深度(3h)")
        val currentStudyDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val studyDurationControl = AppleSegmentedControl(
            this,
            studyDurationItems,
            selectedIndex = currentStudyDuration.coerceIn(0, 3)
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_DURATION, index).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerCourseDuration.removeAllViews()
        binding.containerCourseDuration.addView(studyDurationControl)

        // 4. 打工场所分段器 (支持动态名称与横向滑动)
        loadCachedOrFallbackWorkPlaces(prefs)
        val currentWorkType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val selWorkIndex = currentWorkPlaceOptions.indexOfFirst { it.careerId == currentWorkType }.let { if (it >= 0) it else 0 }
        val segmentItems = currentWorkPlaceOptions.map { SegmentItem(it.title, it.enabled, it.disabledTip) }
        workSegmentControl = AppleSegmentedControl(
            this,
            segmentItems,
            selectedIndex = selWorkIndex,
            isScrollable = true
        ) { index ->
            val targetCareerId = currentWorkPlaceOptions.getOrNull(index)?.careerId ?: 0
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_TYPE, targetCareerId).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerWorkType.removeAllViews()
        binding.containerWorkType.addView(workSegmentControl)

        // 5. 打工时长分段器
        val workDurationItems = listOf("智能自适应", "短工(30m)", "常规(1h)", "长工(2h)")
        val currentWorkDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val workDurationControl = AppleSegmentedControl(
            this,
            workDurationItems,
            selectedIndex = currentWorkDuration.coerceIn(0, 3)
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_DURATION, index).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerWorkDuration.removeAllViews()
        binding.containerWorkDuration.addView(workDurationControl)

        // 6. 自主进食/洗澡阈值分段器 (完全对齐 QQ 设置页数值: 40, 60, 80, 90)
        val thresholdLabels = listOf("低于40", "低于60", "低于80", "低于90")
        val thresholdValues = listOf(40, 60, 80, 90)

        val currentEnergy = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
        val selEnergyIndex = thresholdValues.indexOf(currentEnergy).let { if (it >= 0) it else 1 }
        val careEnergyControl = AppleSegmentedControl(
            this,
            thresholdLabels,
            selectedIndex = selEnergyIndex
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, thresholdValues[index]).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerCareEnergy.removeAllViews()
        binding.containerCareEnergy.addView(careEnergyControl)

        val currentClean = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
        val selCleanIndex = thresholdValues.indexOf(currentClean).let { if (it >= 0) it else 1 }
        val careCleanControl = AppleSegmentedControl(
            this,
            thresholdLabels,
            selectedIndex = selCleanIndex
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, thresholdValues[index]).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerCareClean.removeAllViews()
        binding.containerCareClean.addView(careCleanControl)

        // 7. 好友照顾阈值分段器 (完全对齐 QQ 设置页数值: 40, 60, 80, 90)
        val currentFriendEnergy = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
        val selFriendEnergyIndex = thresholdValues.indexOf(currentFriendEnergy).let { if (it >= 0) it else 1 }
        val friendEnergyControl = AppleSegmentedControl(
            this,
            thresholdLabels,
            selectedIndex = selFriendEnergyIndex
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, thresholdValues[index]).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerFriendEnergy.removeAllViews()
        binding.containerFriendEnergy.addView(friendEnergyControl)

        val currentFriendClean = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
        val selFriendCleanIndex = thresholdValues.indexOf(currentFriendClean).let { if (it >= 0) it else 1 }
        val friendCleanControl = AppleSegmentedControl(
            this,
            thresholdLabels,
            selectedIndex = selFriendCleanIndex
        ) { index ->
            prefs.edit().putInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, thresholdValues[index]).apply()
            updateSummaries(prefs)
            syncConfigToQQ()
        }
        binding.containerFriendClean.removeAllViews()
        binding.containerFriendClean.addView(friendCleanControl)

        // 初始化刷新各 Summary 标签
        updateSummaries(prefs)
    }

    private fun updateSummaries(prefs: SharedPreferences) {
        // 学习状态摘要
        val stage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val matchedStage = schoolStageOptions.getOrNull(stage)
        val stageText = if (stage <= 0) "自适应学园" else (matchedStage?.title?.replace("(已毕业)", "")?.replace("(未解锁)", "") ?: "学园#$stage")
        val duration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val durationText = when (duration) {
            1 -> "1小时"
            2 -> "2小时"
            3 -> "3小时"
            else -> "自适应"
        }
        binding.tvStudySummary.text = "$stageText · $durationText · 自动就读"

        // 打工状态摘要
        val workType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val matchedOption = currentWorkPlaceOptions.find { it.careerId == workType }
        val workTypeText = if (workType <= 0) "智能收益最高" else (matchedOption?.title?.replace("(锁)", "") ?: "专属打工#$workType")
        val workDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val workDurationText = when (workDuration) {
            1 -> "短工(30分)"
            2 -> "常规(1小时)"
            3 -> "长工(2小时)"
            else -> "自适应"
        }
        binding.tvWorkSummary.text = "$workTypeText · $workDurationText · 轮换兼职"

        // 照顾状态摘要
        val energyTh = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
        val cleanTh = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
        binding.tvCareSummary.text = "低于${energyTh}体力自动进食 / 低于${cleanTh}清洁自动洗澡"

        // 好友照顾摘要
        val fEnergyTh = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
        val fCleanTh = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
        binding.tvFriendCareSummary.text = "巡检好友低于${fEnergyTh}体力喂食 / 低于${fCleanTh}清洁洗澡"
    }

    private fun loadCachedOrFallbackWorkPlaces(prefs: SharedPreferences) {
        currentWorkPlaceOptions.clear()
        val cachedJson = prefs.getString("key_cached_work_places_json", null)
        if (!cachedJson.isNullOrEmpty()) {
            try {
                val jsonArray = org.json.JSONArray(cachedJson)
                currentWorkPlaceOptions.add(WorkPlaceOption(0, "智能推荐", enabled = true))
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val stage = obj.getInt("stage")
                    val rawTitle = obj.getString("title").trim()
                    val limitStatus = obj.getInt("limitStatus")
                    val lockReason = obj.optString("lockReason", "")
                    val isLocked = (limitStatus != 0)
                    val realTitle = if (rawTitle.isNotEmpty() && rawTitle != "???") rawTitle else "隐藏职业"
                    val displayTitle = if (isLocked) "$realTitle(锁)" else realTitle
                    val tip = if (isLocked) (if (lockReason.isNotEmpty()) lockReason else "还没有解锁这个职业") else null
                    currentWorkPlaceOptions.add(WorkPlaceOption(stage, displayTitle, enabled = !isLocked, disabledTip = tip))
                }
                return
            } catch (_: Throwable) {}
        }

        // 默认备选场所
        currentWorkPlaceOptions.addAll(listOf(
            WorkPlaceOption(0, "智能推荐", enabled = true),
            WorkPlaceOption(1, "彩虹画室", enabled = true),
            WorkPlaceOption(2, "迷雾侦探所", enabled = true),
            WorkPlaceOption(3, "星尘魔法塔", enabled = true),
            WorkPlaceOption(4, "咕噜厨房", enabled = true),
            WorkPlaceOption(5, "隐藏工坊(锁)", enabled = false, disabledTip = "还没有解锁这个职业"),
            WorkPlaceOption(6, "云朵梦舍", enabled = true),
            WorkPlaceOption(7, "隐藏工坊(锁)", enabled = false, disabledTip = "还没有解锁这个职业"),
            WorkPlaceOption(8, "风铃旅社", enabled = true)
        ))
    }

    private fun updateWorkPlacesFromRemote(jsonStr: String) {
        try {
            val prefs = PreferencesHelper.getPrefs(this)
            prefs.edit().putString("key_cached_work_places_json", jsonStr).apply()
            loadCachedOrFallbackWorkPlaces(prefs)
            val currentWorkType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
            val selWorkIndex = currentWorkPlaceOptions.indexOfFirst { it.careerId == currentWorkType }.let { if (it >= 0) it else 0 }
            val segmentItems = currentWorkPlaceOptions.map { SegmentItem(it.title, it.enabled, it.disabledTip) }
            runOnUiThread {
                workSegmentControl?.rebuildItems(segmentItems, selWorkIndex)
                updateSummaries(prefs)
                appendLog("[动态识别] 已从 QQ 账号同步最新场所 (${currentWorkPlaceOptions.size - 1}个地点)")
            }
        } catch (t: Throwable) {
            appendLog("[场所同步异常] ${t.message}")
        }
    }

    private fun loadCachedOrFallbackSchoolStages(prefs: SharedPreferences) {
        schoolStageOptions.clear()
        schoolStageOptions.add(SegmentItem("自适应", enabled = true))
        val stageNames = listOf("初级", "中级", "高级", "进修")
        val cachedJson = prefs.getString("key_cached_school_details_json", null)
        if (!cachedJson.isNullOrEmpty()) {
            try {
                val jsonArray = org.json.JSONArray(cachedJson)
                val stageMap = mutableMapOf<Int, org.json.JSONObject>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    stageMap[obj.getInt("stage")] = obj
                }
                for (s in 1..4) {
                    val name = stageNames[s - 1]
                    val sObj = stageMap[s]
                    val isGrad = sObj?.optBoolean("isGraduated", false) ?: false
                    val limitStatus = sObj?.optInt("limitStatus", 0) ?: 0
                    val isLocked = (limitStatus != 0 && limitStatus != 2)
                    val enabled = !isGrad && !isLocked
                    val title = when {
                        isGrad -> "$name(已毕业)"
                        isLocked -> "$name(未解锁)"
                        else -> name
                    }
                    val tip = when {
                        isGrad -> "${name}学园已毕业（腾讯规则禁止重复就读）"
                        isLocked -> "${name}学园尚未解锁（需先完成前序学业修习）"
                        else -> null
                    }
                    schoolStageOptions.add(SegmentItem(title, enabled = enabled, disabledTip = tip))
                }
                return
            } catch (_: Throwable) {}
        }

        // 默认备选
        for (name in stageNames) {
            schoolStageOptions.add(SegmentItem(name, enabled = true))
        }
    }

    private fun updateSchoolStagesFromRemote(jsonStr: String) {
        try {
            val prefs = PreferencesHelper.getPrefs(this)
            prefs.edit().putString("key_cached_school_details_json", jsonStr).apply()
            loadCachedOrFallbackSchoolStages(prefs)
            val currentStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
            val validStageIndex = if (currentStage in schoolStageOptions.indices && schoolStageOptions[currentStage].enabled) {
                currentStage
            } else {
                0
            }
            if (validStageIndex != currentStage) {
                prefs.edit().putInt(PreferencesHelper.KEY_SCHOOL_STAGE, validStageIndex).apply()
            }
            runOnUiThread {
                schoolSegmentControl?.rebuildItems(schoolStageOptions, validStageIndex)
                updateSummaries(prefs)
                appendLog("[动态识别] 已从 QQ 账号同步学园毕业状态")
            }
        } catch (t: Throwable) {
            appendLog("[学园同步异常] ${t.message}")
        }
    }

    private fun animateExpandCollapse(view: View, expand: Boolean) {
        if (expand) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .withEndAction {
                    view.visibility = View.GONE
                }
                .start()
        }
    }

    private fun syncConfigToQQ() {
        val prefs = PreferencesHelper.getPrefs(this)
        val study = prefs.getBoolean(PreferencesHelper.KEY_STUDY, true)
        val work = prefs.getBoolean(PreferencesHelper.KEY_WORK, true)
        val care = prefs.getBoolean(PreferencesHelper.KEY_CARE, true)
        val friendCare = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
        val friendCareEnergy = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
        val friendCareClean = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)
        val adv = prefs.getBoolean(PreferencesHelper.KEY_ADVENTURE, false)
        val settle = prefs.getBoolean(PreferencesHelper.KEY_SETTLE, true)
        val likeBack = prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
        val claimCoinBag = prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
        val fatigueToAdv = prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
        val schoolStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val courseSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val courseDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val workType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val workDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val careEnergy = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
       val careClean = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
       val hireFriend = prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
       val autoPk = prefs.getBoolean(PreferencesHelper.KEY_AUTO_PK, false)
       val pkBlacklistCsv = prefs.getString(PreferencesHelper.KEY_PK_BLACKLIST_UINS, "") ?: ""
      val humanLikeSleep = prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
       val nightSleep = prefs.getBoolean(PreferencesHelper.KEY_NIGHT_SLEEP_MODE, true)
       val screenOffSilent = prefs.getBoolean(PreferencesHelper.KEY_SCREEN_OFF_SILENT, true)
       val hideSetting = prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
       val debugLog = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)

        try {
            val intent = Intent(HookEntry.ACTION_UPDATE_CONFIG).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra("extra_study", study)
                putExtra("extra_work", work)
                putExtra("extra_care", care)
                putExtra("extra_friend_care_enabled", friendCare)
                putExtra("extra_friend_care_energy_threshold", friendCareEnergy)
                putExtra("extra_friend_care_clean_threshold", friendCareClean)
                putExtra("extra_adventure", adv)
                putExtra("extra_settle", settle)
                putExtra("extra_like_back", likeBack)
                putExtra("extra_claim_coinbag", claimCoinBag)
                putExtra("extra_fatigue_to_adventure", fatigueToAdv)
                putExtra("extra_school_stage", schoolStage)
                putExtra("extra_course_subject", courseSubject)
                putExtra("extra_course_duration", courseDuration)
                putExtra("extra_work_type", workType)
                putExtra("extra_work_duration", workDuration)
                putExtra("extra_care_energy_threshold", careEnergy)
               putExtra("extra_care_clean_threshold", careClean)
               putExtra("extra_hire_friend_enabled", hireFriend)
               putExtra("extra_auto_pk", autoPk)
               putExtra("extra_pk_blacklist_uins", pkBlacklistCsv)
               putExtra("extra_human_like_sleep", humanLikeSleep)
               putExtra("extra_night_sleep_mode", nightSleep)
               putExtra("extra_screen_off_silent", screenOffSilent)
               putExtra("extra_hide_qq_setting_entry", hideSetting)
               putExtra("extra_debug_log", debugLog)
            }
            sendBroadcast(intent)
        } catch (t: Throwable) {
            appendLog("[同步失败] 无法下发配置广播: ${t.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.applyApplePressEffect() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(100)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(120)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }
}
