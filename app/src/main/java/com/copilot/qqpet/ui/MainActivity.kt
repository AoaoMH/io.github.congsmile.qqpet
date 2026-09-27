package com.copilot.qqpet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initStatusCard()
        initSwitches()
        initActionButtons()
        registerLogReceiver()
    }

    override fun onResume() {
        super.onResume()
        syncConfigToQQ()
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
            binding.tvStatusTitle.text = getString(R.string.status_active)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvStatusTitle.text = getString(R.string.status_inactive)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
    }

    private fun initActionButtons() {
        binding.btnRunCycle.setOnClickListener {
            sendActionToQQ("cycle", "全流程策略调度循环")
        }
        binding.btnTestCare.setOnClickListener {
            sendActionToQQ("care", "照顾实测 (喂食+洗澡)")
        }
        binding.btnTestWork.setOnClickListener {
            sendActionToQQ("work", "兼职打工实测")
        }
        binding.btnTestSchool.setOnClickListener {
            sendActionToQQ("school", "进阶学习实测")
        }
        binding.btnTestAdventure.setOnClickListener {
            sendActionToQQ("adventure", "森林探险实测")
        }
        binding.btnTestSettle.setOnClickListener {
            sendActionToQQ("settle", "收益结算实测")
        }
        binding.btnClearLogs.setOnClickListener {
            binding.tvEngineLogs.text = "日志已清空，等待下次测试..."
        }
    }

    private fun sendActionToQQ(action: String, actionName: String) {
        appendLog("👉 [指令] 已向 QQ 下发「$actionName」广播...")
        try {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra(PetAdventureEngine.EXTRA_ACTION, action)
            }
            sendBroadcast(intent)
            Toast.makeText(this, "已下发 $actionName 指令", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            appendLog("❌ [异常] 下发指令失败: ${t.message}")
        }
    }

    private fun registerLogReceiver() {
        val filter = IntentFilter(PetAdventureEngine.ACTION_ENGINE_LOG)
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val msg = intent.getStringExtra(PetAdventureEngine.EXTRA_LOG_TEXT) ?: return
                appendLog(msg)
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

        // 读取初始值（默认全开启）
        binding.switchStudy.isChecked = prefs.getBoolean(PreferencesHelper.KEY_STUDY, true)
        binding.switchWork.isChecked = prefs.getBoolean(PreferencesHelper.KEY_WORK, true)
        binding.switchCare.isChecked = prefs.getBoolean(PreferencesHelper.KEY_CARE, true)
        binding.switchAdventure.isChecked = prefs.getBoolean(PreferencesHelper.KEY_ADVENTURE, true)
        binding.switchSettle.isChecked = prefs.getBoolean(PreferencesHelper.KEY_SETTLE, true)

        // 绑定修改监听
        binding.switchStudy.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PreferencesHelper.KEY_STUDY, isChecked).apply()
            syncConfigToQQ()
        }
        binding.switchWork.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PreferencesHelper.KEY_WORK, isChecked).apply()
            syncConfigToQQ()
        }
        binding.switchCare.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PreferencesHelper.KEY_CARE, isChecked).apply()
            syncConfigToQQ()
        }
        binding.switchAdventure.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PreferencesHelper.KEY_ADVENTURE, isChecked).apply()
            syncConfigToQQ()
        }
        binding.switchSettle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PreferencesHelper.KEY_SETTLE, isChecked).apply()
            syncConfigToQQ()
        }
    }

    private fun syncConfigToQQ() {
        val prefs = PreferencesHelper.getPrefs(this)
        val study = prefs.getBoolean(PreferencesHelper.KEY_STUDY, true)
        val work = prefs.getBoolean(PreferencesHelper.KEY_WORK, true)
        val care = prefs.getBoolean(PreferencesHelper.KEY_CARE, true)
        val adv = prefs.getBoolean(PreferencesHelper.KEY_ADVENTURE, true)
        val settle = prefs.getBoolean(PreferencesHelper.KEY_SETTLE, true)
        val studyMode = prefs.getInt(PreferencesHelper.KEY_STUDY_MODE, 0)
        val workMode = prefs.getInt(PreferencesHelper.KEY_WORK_MODE, 0)

        try {
            val intent = Intent(HookEntry.ACTION_UPDATE_CONFIG).apply {
                setPackage(HookEntry.TARGET_PACKAGE)
                putExtra("extra_study", study)
                putExtra("extra_work", work)
                putExtra("extra_care", care)
                putExtra("extra_adventure", adv)
                putExtra("extra_settle", settle)
                putExtra("extra_study_mode", studyMode)
                putExtra("extra_work_mode", workMode)
            }
            sendBroadcast(intent)
        } catch (t: Throwable) {
            appendLog("❌ [同步失败] 无法下发配置广播: ${t.message}")
        }
    }
}
