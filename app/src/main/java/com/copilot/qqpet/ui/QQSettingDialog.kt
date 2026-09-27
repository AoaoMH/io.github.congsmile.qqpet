package com.copilot.qqpet.ui

import android.animation.ValueAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine

/**
 * 纯代码自绘制的 iOS 标准 Toggle 控件 (AppleSwitchView)
 * 物理尺寸：51dp x 31dp (符合 iOS Human Interface Guidelines)
 * 开启背景：#34C759
 * 关闭背景：#E9E9EB
 * 滑块：纯白圆润 + 柔和投影
 */
class AppleSwitchView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(2f), 0f, dp(1f), Color.parseColor("#30000000"))
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15000000")
    }

    private val rect = RectF()
    private var progress = 1.0f // 0f 为关闭，1f 为开启
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                animateTo(if (value) 1.0f else 0.0f)
            }
        }

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    fun setCheckedImmediately(checked: Boolean) {
        isChecked = checked
        progress = if (checked) 1.0f else 0.0f
        invalidate()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = dp(51f).toInt()
        val h = dp(31f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        rect.set(0f, 0f, w, h)

        val offR = 0xE9
        val offG = 0xE9
        val offB = 0xEB
        val onR = 0x34
        val onG = 0xC7
        val onB = 0x59

        val curR = (offR + (onR - offR) * progress).toInt()
        val curG = (offG + (onG - offG) * progress).toInt()
        val curB = (offB + (onB - offB) * progress).toInt()

        bgPaint.color = Color.rgb(curR, curG, curB)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        val pad = dp(2f)
        val thumbRadius = (h - pad * 2) / 2f
        val startX = pad + thumbRadius
        val endX = w - pad - thumbRadius
        val thumbX = startX + (endX - startX) * progress
        val thumbY = h / 2f

        canvas.drawCircle(thumbX, thumbY + dp(0.8f), thumbRadius, shadowPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            isChecked = !isChecked
            onCheckedChangeListener?.invoke(isChecked)
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
        }
        return true
    }

    private fun dp(v: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            context.resources.displayMetrics
        )
    }
}

/**
 * 纯代码自绘制的 iOS 标准 Segmented Control (分段药丸选择器)
 * 纯灰底座 (#EBEBED) + 纯白高亮卡片 (#FFFFFF) + 柔和投影
 */
class AppleSegmentedControl(
    context: Context,
    private val items: List<String>,
    private var selectedIndex: Int = 0,
    private val onItemSelected: (Int) -> Unit
) : LinearLayout(context) {

    private val textViews = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#EBEBED"))
            cornerRadius = dp(8f)
        }
        setPadding(dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt())

        items.forEachIndexed { index, title ->
            val tv = TextView(context).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(2f).toInt(), dp(5.5f).toInt(), dp(2f).toInt(), dp(5.5f).toInt())
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
                setOnClickListener {
                    if (selectedIndex != index) {
                        selectedIndex = index
                        updateSelection()
                        onItemSelected(index)
                        playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    }
                }
            }
            textViews.add(tv)
            addView(tv)
        }
        updateSelection()
    }

    fun setSelection(index: Int) {
        if (index in items.indices && index != selectedIndex) {
            selectedIndex = index
            updateSelection()
        }
    }

    fun setControlEnabled(enabled: Boolean) {
        alpha = if (enabled) 1.0f else 0.4f
        isEnabled = enabled
        textViews.forEach { it.isEnabled = enabled }
    }

    private fun updateSelection() {
        textViews.forEachIndexed { idx, tv ->
            if (idx == selectedIndex) {
                tv.setTextColor(Color.parseColor("#1C1C1E"))
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                tv.background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(6.5f)
                    setStroke(dp(0.5f).toInt(), Color.parseColor("#12000000"))
                }
                tv.elevation = dp(1.5f)
            } else {
                tv.setTextColor(Color.parseColor("#8E8E93"))
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tv.background = null
                tv.elevation = 0f
            }
        }
    }

    private fun dp(v: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            context.resources.displayMetrics
        )
    }
}

/**
 * 遵循 Apple Design 顶级审美标准的 QQ 原生伴侣控制面板 (v1.0.19)
 * 1. 黄金排版层级：主标题 21sp Bold、副标 13sp、正文 16sp、说明 12.5sp
 * 2. 独立绘制的 iOS Toggle Switch，彻底免疫安卓主题与 ROM 污染
 * 3. 对称稳重的 2x2+1 次级操作卡片，深灰字色回归典雅质感
 * 4. 24dp 纯白圆角无边框浮岛设计
 * 5. 独创秒级动态倒计时心跳循环，时间每秒平滑跳动，杜绝视觉停滞与假死错觉
 */
object QQSettingDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetTextI18n")
    fun show(activity: Activity, engine: PetAdventureEngine?) {
        val context = activity

        // 弹窗主体卡片 (纯白背景 + 24dp 柔和圆角)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 22))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(context, 24).toFloat()
            }
        }

        // ================= 1. 顶部 Header =================
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 6))
        }

        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val mainTitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "Q宠后台伴侣"
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
        }

        val statusDot = TextView(context).apply {
            text = "● 运行中"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#34C759"))
            setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EBF9EE"))
                cornerRadius = dp(context, 12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(context, 8), 0, 0, 0)
            }
        }

        mainTitleRow.addView(titleView)
        mainTitleRow.addView(statusDot)
        titleColumn.addView(mainTitleRow)

        val subtitleView = TextView(context).apply {
            text = PetAdventureEngine.formatLiveStatusText()
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 4), 0, 0)
        }
        titleColumn.addView(subtitleView)
        headerLayout.addView(titleColumn)

        var dialogInstance: Dialog? = null
        val closeCircle = TextView(context).apply {
            text = "✕"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F7"))
                cornerRadius = dp(context, 15).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 30), dp(context, 30))
            setOnClickListener { dialogInstance?.dismiss() }
        }
        headerLayout.addView(closeCircle)
        root.addView(headerLayout)

        val spacer1 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 14)
            )
        }
        root.addView(spacer1)

        // ================= 2. 自动化配置分组 (iOS Grouped Section) =================
        val sectionTitle1 = TextView(context).apply {
            text = "自动化功能 · 智能轮转均衡调度 (Round-Robin)"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(dp(context, 4), 0, 0, dp(context, 8))
        }
        root.addView(sectionTitle1)

        val switchesCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F9FAFB"))
                cornerRadius = dp(context, 18).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#ECECEE"))
            }
            setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
        }

        val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)

        fun createAppleStyleRow(
            badgeIcon: String,
            badgeColor: String,
            title: String,
            desc: String,
            prefKey: String,
            defaultVal: Boolean,
            isLast: Boolean,
            segmentedItems: List<String>? = null,
            segmentedPrefKey: String? = null,
            descMapper: ((Int) -> String)? = null
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 11), 0, dp(context, 11))
            }

            val iconBadge = TextView(context).apply {
                text = badgeIcon
                textSize = 17f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(badgeColor))
                    cornerRadius = dp(context, 10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                    setMargins(0, 0, dp(context, 12), 0)
                }
            }
            row.addView(iconBadge)

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(0, 0, dp(context, 10), 0)
                }
            }
            val tView = TextView(context).apply {
                text = title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.parseColor("#1C1C1E"))
            }
            val currentMode = if (segmentedPrefKey != null) prefs.getInt(segmentedPrefKey, 0) else 0
            val dView = TextView(context).apply {
                text = if (descMapper != null && segmentedPrefKey != null) descMapper(currentMode) else desc
                textSize = 12.5f
                setTextColor(Color.parseColor("#6C6C70"))
                setPadding(0, dp(context, 2), 0, 0)
            }
            textLayout.addView(tView)
            textLayout.addView(dView)
            row.addView(textLayout)

            var segControl: AppleSegmentedControl? = null
            if (segmentedItems != null && segmentedPrefKey != null) {
                segControl = AppleSegmentedControl(context, segmentedItems, currentMode) { selectedIndex ->
                    prefs.edit().putInt(segmentedPrefKey, selectedIndex).commit()
                    descMapper?.let { dView.text = it(selectedIndex) }
                    syncConfig(prefs, engine, context)
                }
            }

            val initialChecked = prefs.getBoolean(prefKey, defaultVal)
            val sw = AppleSwitchView(context).apply {
                setCheckedImmediately(initialChecked)
                onCheckedChangeListener = { isChecked ->
                    prefs.edit().putBoolean(prefKey, isChecked).commit()
                    segControl?.setControlEnabled(isChecked)
                    syncConfig(prefs, engine, context)
                }
            }
            segControl?.setControlEnabled(initialChecked)
            row.addView(sw)
            switchesCard.addView(row)

            if (segControl != null) {
                val segContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 48), 0, 0, dp(context, 10))
                    addView(segControl)
                }
                switchesCard.addView(segContainer)
            }

            if (!isLast) {
                val divider = View(context).apply {
                    setBackgroundColor(Color.parseColor("#EAEAEC"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(dp(context, 48), 0, 0, 0)
                    }
                }
                switchesCard.addView(divider)
            }
        }

        createAppleStyleRow(
            badgeIcon = "📖",
            badgeColor = "#FF9500",
            title = "进阶学力研修",
            desc = "学力达标自动学习进阶技能",
            prefKey = "key_study",
            defaultVal = true,
            isLast = false,
            segmentedItems = listOf("均衡", "智力", "力量", "魅力"),
            segmentedPrefKey = "key_study_mode",
            descMapper = { mode ->
                when (mode) {
                    1 -> "专攻智力课程 (文化学园·专精智力)"
                    2 -> "专攻力量锻炼 (体能学园·专精武力)"
                    3 -> "专攻魅力修养 (艺术学园·专精艺术)"
                    else -> "均衡轮转修习 (智力->力量->魅力循环)"
                }
            }
        )

        createAppleStyleRow(
            badgeIcon = "💼",
            badgeColor = "#34C759",
            title = "全自动打工派遣",
            desc = "元气饱满自动参与打工赚金币",
            prefKey = "key_work",
            defaultVal = true,
            isLast = false,
            segmentedItems = listOf("均衡", "文职", "体力", "演艺"),
            segmentedPrefKey = "key_work_mode",
            descMapper = { mode ->
                when (mode) {
                    1 -> "专攻文化兼职 (图书管理/文员兼职)"
                    2 -> "专攻体力兼职 (小镇搬运/工地锻炼)"
                    3 -> "专攻演艺兼职 (戏剧参演/舞台演艺)"
                    else -> "均衡轮流派遣 (文化->体力->演艺兼职)"
                }
            }
        )

        createAppleStyleRow("🍗", "#007AFF", "自动喂食与清洁", "饥饿肮脏时自动进食、洗澡沐浴", "key_care", true, false)
        createAppleStyleRow("🌲", "#5856D6", "神秘森林冒险", "自动深入野外林区探秘与冒险", "key_adventure", false, false)
        createAppleStyleRow("💰", "#AF52DE", "探险收益结算", "历练归来自动领取全部掉落收益", "key_settle", true, true)

        root.addView(switchesCard)

        // ================= 3. 即时操作区 (Apple Action Buttons) =================
        val sectionTitle2 = TextView(context).apply {
            text = "即时指令"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(dp(context, 4), dp(context, 16), 0, dp(context, 8))
        }
        root.addView(sectionTitle2)

        val cycleMainButton = Button(context).apply {
            text = "⚡ 一键执行全套巡检与养成"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF"))
                cornerRadius = dp(context, 14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            ).apply {
                setMargins(0, 0, 0, dp(context, 10))
            }
            setOnClickListener {
                triggerAction(context, engine, "cycle")
                mainHandler.postDelayed({
                    subtitleView.text = PetAdventureEngine.formatLiveStatusText()
                }, 1000L)
            }
        }
        root.addView(cycleMainButton)

        fun createSecondaryButton(label: String, actionName: String, isFullWidth: Boolean = false): Button {
            return Button(context).apply {
                text = label
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.parseColor("#1C1C1E"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2F2F7"))
                    cornerRadius = dp(context, 12).toFloat()
                }
                layoutParams = if (isFullWidth) {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 42))
                } else {
                    LinearLayout.LayoutParams(0, dp(context, 42), 1.0f).apply {
                        setMargins(dp(context, 4), 0, dp(context, 4), 0)
                    }
                }
                setOnClickListener {
                    triggerAction(context, engine, actionName)
                    mainHandler.postDelayed({
                        subtitleView.text = PetAdventureEngine.formatLiveStatusText()
                    }, 1000L)
                }
            }
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        row1.addView(createSecondaryButton("🌲 森林探险", "adventure"))
        row1.addView(createSecondaryButton("🍗 喂食清洁", "care"))
        root.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        row2.addView(createSecondaryButton("💼 派遣打工", "work"))
        row2.addView(createSecondaryButton("📖 进阶学习", "school"))
        root.addView(row2)

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(context, 14))
        }
        row3.addView(createSecondaryButton("💰 结算并领取探险收益", "settle", isFullWidth = true))
        root.addView(row3)

        // ================= 4. 底部外链 =================
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 4), 0, 0)
        }

        val openAppText = TextView(context).apply {
            text = "进入伴侣独立 App 管理更多细节 →"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
            setOnClickListener {
                try {
                    val intent = Intent().apply {
                        setClassName("com.copilot.qqpet", "com.copilot.qqpet.ui.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {}
            }
        }
        bottomBar.addView(openAppText)
        root.addView(bottomBar)

        val scrollView = ScrollView(context).apply {
            addView(root)
            isVerticalScrollBarEnabled = false
        }

        dialogInstance = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setContentView(scrollView)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        // 挂载秒级平滑倒计时心跳，只要弹窗在前台，每秒计算一次并自然递减
        val tickerRunnable = object : Runnable {
            override fun run() {
                val liveText = PetAdventureEngine.formatLiveStatusText()
                subtitleView.text = liveText
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.post(tickerRunnable)

        // 弹窗拉起时即刻同步服务端最新进度与倒计时
        val activeEngine = engine ?: HookEntry.globalEngine
        if (activeEngine != null) {
            activeEngine.startBackgroundLoop(context.applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                val petId = PetAdventureEngine.cachedPetId ?: activeEngine.queryOwnPetAwait().second
                if (!petId.isNullOrEmpty()) {
                    val status = activeEngine.queryStoryStatusAwait(petId)
                    if (status.code == 0) {
                        val rem = status.remaining
                        if (!status.storyId.isNullOrEmpty() && rem != null && rem > 0) {
                            PetAdventureEngine.lastActiveStoryId = status.storyId
                            PetAdventureEngine.currentTaskEndTimeMillis = System.currentTimeMillis() + rem * 1000L
                            PetAdventureEngine.currentTaskTypeName = when {
                                status.storyId.startsWith("6100") -> "进阶修习中"
                                status.storyId.startsWith("6400") -> "小镇打工中"
                                status.storyId.startsWith("6700") -> "森林探险中"
                                else -> "任务执行中"
                            }
                        } else {
                            PetAdventureEngine.currentTaskEndTimeMillis = 0L
                            PetAdventureEngine.currentStatusText = "全自动守护中 · 空闲待命"
                        }
                        mainHandler.post {
                            subtitleView.text = PetAdventureEngine.formatLiveStatusText()
                        }
                    }
                }
            }
        }

        dialogInstance.setOnDismissListener {
            mainHandler.removeCallbacks(tickerRunnable)
        }

        dialogInstance.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setBackgroundColor(Color.TRANSPARENT)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes?.apply {
                dimAmount = 0.5f
            }
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.90).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialogInstance.show()
    }

    private fun triggerAction(context: Context, engine: PetAdventureEngine?, action: String) {
        if (engine != null) {
            engine.runAction(context, action)
        } else {
            val intent = Intent(HookEntry.ACTION_TRIGGER_ACTION).apply {
                setPackage("com.tencent.mobileqq")
                putExtra(PetAdventureEngine.EXTRA_ACTION, action)
            }
            context.sendBroadcast(intent)
        }
    }

    private fun syncConfig(prefs: android.content.SharedPreferences, engine: PetAdventureEngine?, context: Context) {
        val study = prefs.getBoolean("key_study", true)
        val work = prefs.getBoolean("key_work", true)
        val care = prefs.getBoolean("key_care", true)
        val adv = prefs.getBoolean("key_adventure", false)
        val settle = prefs.getBoolean("key_settle", true)
        val studyMode = prefs.getInt("key_study_mode", 0)
        val workMode = prefs.getInt("key_work_mode", 0)

        HookEntry.globalEngine?.updateConfig(study, work, care, adv, settle, studyMode, workMode)
        if (engine != null && engine !== HookEntry.globalEngine) {
            engine.updateConfig(study, work, care, adv, settle, studyMode, workMode)
        }
        val intent = Intent(HookEntry.ACTION_UPDATE_CONFIG).apply {
            setPackage("com.tencent.mobileqq")
            putExtra("extra_study", study)
            putExtra("extra_work", work)
            putExtra("extra_care", care)
            putExtra("extra_adventure", adv)
            putExtra("extra_settle", settle)
            putExtra("extra_study_mode", studyMode)
            putExtra("extra_work_mode", workMode)
        }
        context.sendBroadcast(intent)
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
