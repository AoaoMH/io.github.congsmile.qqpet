package com.copilot.qqpet.ui

import com.copilot.qqpet.protocol.QQPetDirectBridge
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
 * 分段项状态模型 (支持项目级置灰、禁用与未解锁提示)
 */
data class SegmentItem(
    val title: String,
    val enabled: Boolean = true,
    val disabledTip: String? = null
)

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
    private val itemStates = mutableListOf<SegmentItem>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#EBEBED"))
            cornerRadius = dp(8f)
        }
        setPadding(dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt())

        items.forEachIndexed { index, title ->
            itemStates.add(SegmentItem(title, enabled = true))
            val tv = TextView(context).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(2f).toInt(), dp(5.5f).toInt(), dp(2f).toInt(), dp(5.5f).toInt())
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
                setOnClickListener {
                    val state = itemStates.getOrNull(index)
                    if (state != null && !state.enabled) {
                        val tip = state.disabledTip ?: "该选项尚未解锁"
                        android.widget.Toast.makeText(context, tip, android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
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

    fun updateItemStates(newStates: List<SegmentItem>) {
        if (newStates.size != itemStates.size) return
        for (i in newStates.indices) {
            itemStates[i] = newStates[i]
            val tv = textViews.getOrNull(i) ?: continue
            tv.text = newStates[i].title
            tv.alpha = if (newStates[i].enabled) 1.0f else 0.38f
        }
        if (selectedIndex in itemStates.indices && !itemStates[selectedIndex].enabled) {
            val fallback = itemStates.indexOfFirst { it.enabled }.takeIf { it >= 0 } ?: 0
            selectedIndex = fallback
            onItemSelected(selectedIndex)
        }
        updateSelection()
    }

    private fun updateSelection() {
        textViews.forEachIndexed { idx, tv ->
            val isItemEnabled = itemStates.getOrNull(idx)?.enabled ?: true
            if (idx == selectedIndex) {
                tv.setTextColor(Color.parseColor("#1C1C1E"))
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                tv.background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(6.5f)
                    setStroke(dp(0.5f).toInt(), Color.parseColor("#12000000"))
                }
                tv.elevation = dp(1.5f)
                tv.alpha = 1.0f
            } else {
                tv.setTextColor(if (isItemEnabled) Color.parseColor("#8E8E93") else Color.parseColor("#AEAEB2"))
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tv.background = null
                tv.elevation = 0f
                tv.alpha = if (isItemEnabled) 1.0f else 0.38f
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

        data class RowControls(
            val descView: TextView,
            val primarySeg: AppleSegmentedControl?,
            val secondarySeg: AppleSegmentedControl?,
            val tertiarySeg: AppleSegmentedControl?
        )

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
            secondarySegmentedItems: List<String>? = null,
            secondarySegmentedPrefKey: String? = null,
            secondaryTitle: String? = null,
            tertiarySegmentedItems: List<String>? = null,
            tertiarySegmentedPrefKey: String? = null,
            tertiaryTitle: String? = null,
            descMapper: ((Int) -> String)? = null
        ): RowControls {
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

            var secSegControl: AppleSegmentedControl? = null
            var segControl: AppleSegmentedControl? = null
            if (segmentedItems != null && segmentedPrefKey != null) {
                segControl = AppleSegmentedControl(context, segmentedItems, currentMode) { selectedIndex ->
                    prefs.edit().putInt(segmentedPrefKey, selectedIndex).commit()
                    descMapper?.let { dView.text = it(selectedIndex) }
                    syncConfig(prefs, engine, context)
                    if (segmentedPrefKey == PreferencesHelper.KEY_WORK_TYPE) {
                        val active = engine ?: HookEntry.globalEngine
                        if (active != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val petId = PetAdventureEngine.cachedPetId ?: active.queryOwnPetAwait().second
                                if (!petId.isNullOrEmpty()) {
                                    val career = when (selectedIndex) { 0 -> 3; 1 -> 1; 2 -> 2; else -> 3 }
                                    val (jCode, jobs) = active.querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = career)
                                    if (jCode == 0 && jobs.isNotEmpty()) {
                                        PetAdventureEngine.cachedWorkJobs = jobs
                                        mainHandler.post {
                                            val j10 = jobs.find { it.costTime.contains("10") }
                                            val j45 = jobs.find { it.costTime.contains("45") }
                                            val j2h = jobs.find { it.costTime.contains("2小时") }
                                            val j4h = jobs.find { it.costTime.contains("4小时") }
                                            val can10 = j10?.canDo ?: true
                                            val can45 = j45?.canDo ?: true
                                            val can2h = j2h?.canDo ?: true
                                            val can4h = j4h?.canDo ?: true
                                            val jobItems = listOf(
                                                SegmentItem("智能挂机", enabled = true),
                                                SegmentItem(if (can10) "10分钟" else "10分(锁)", enabled = can10, disabledTip = if (!can10) "10分钟兼职暂未解锁" else null),
                                                SegmentItem(if (can45) "45分钟" else "45分(锁)", enabled = can45, disabledTip = if (!can45) "45分钟兼职暂未解锁" else null),
                                                SegmentItem(if (can2h) "2小时" else "2小时(锁)", enabled = can2h, disabledTip = if (!can2h) "2小时兼职暂未解锁" else null),
                                                SegmentItem(if (can4h) "4小时" else "4小时(锁)", enabled = can4h, disabledTip = if (!can4h) "4小时兼职暂未解锁" else null)
                                            )
                                            secSegControl?.updateItemStates(jobItems)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (secondarySegmentedItems != null && secondarySegmentedPrefKey != null) {
                val currentSecMode = prefs.getInt(secondarySegmentedPrefKey, 0)
                secSegControl = AppleSegmentedControl(context, secondarySegmentedItems, currentSecMode) { selectedIndex ->
                    prefs.edit().putInt(secondarySegmentedPrefKey, selectedIndex).commit()
                    syncConfig(prefs, engine, context)
                }
            }

            var terSegControl: AppleSegmentedControl? = null
            if (tertiarySegmentedItems != null && tertiarySegmentedPrefKey != null) {
                val currentTerMode = prefs.getInt(tertiarySegmentedPrefKey, 0)
                terSegControl = AppleSegmentedControl(context, tertiarySegmentedItems, currentTerMode) { selectedIndex ->
                    prefs.edit().putInt(tertiarySegmentedPrefKey, selectedIndex).commit()
                    syncConfig(prefs, engine, context)
                }
            }

            val initialChecked = prefs.getBoolean(prefKey, defaultVal)
            val sw = AppleSwitchView(context).apply {
                setCheckedImmediately(initialChecked)
                onCheckedChangeListener = { isChecked ->
                    prefs.edit().putBoolean(prefKey, isChecked).commit()
                    segControl?.setControlEnabled(isChecked)
                    secSegControl?.setControlEnabled(isChecked)
                    terSegControl?.setControlEnabled(isChecked)
                    syncConfig(prefs, engine, context)
                }
            }
            segControl?.setControlEnabled(initialChecked)
            secSegControl?.setControlEnabled(initialChecked)
            terSegControl?.setControlEnabled(initialChecked)
            row.addView(sw)
            switchesCard.addView(row)

            if (segControl != null) {
                val segContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 48), 0, 0, if (secSegControl != null) dp(context, 6) else dp(context, 10))
                    addView(segControl)
                }
                switchesCard.addView(segContainer)
            }

            if (secSegControl != null) {
                val secContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 48), 0, 0, if (terSegControl != null) dp(context, 6) else dp(context, 10))
                    if (!secondaryTitle.isNullOrEmpty()) {
                        val stView = TextView(context).apply {
                            text = secondaryTitle
                            textSize = 11.5f
                            setTextColor(Color.parseColor("#8E8E93"))
                            setPadding(0, 0, 0, dp(context, 4))
                        }
                        addView(stView)
                    }
                    addView(secSegControl)
                }
                switchesCard.addView(secContainer)
            }

            if (terSegControl != null) {
                val terContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 48), 0, 0, dp(context, 10))
                    if (!tertiaryTitle.isNullOrEmpty()) {
                        val ttView = TextView(context).apply {
                            text = tertiaryTitle
                            textSize = 11.5f
                            setTextColor(Color.parseColor("#8E8E93"))
                            setPadding(0, 0, 0, dp(context, 4))
                        }
                        addView(ttView)
                    }
                    addView(terSegControl)
                }
                switchesCard.addView(terContainer)
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
            return RowControls(dView, segControl, secSegControl, terSegControl)
        }

        val studyControls = createAppleStyleRow(
            badgeIcon = "📖",
            badgeColor = "#FF9500",
            title = "进阶学力研修",
            desc = "学力达标自动学习进阶技能",
            prefKey = "key_study",
            defaultVal = true,
            isLast = false,
            segmentedItems = listOf("自适应", "初级", "中级", "高级", "进修"),
            segmentedPrefKey = PreferencesHelper.KEY_SCHOOL_STAGE,
            secondarySegmentedItems = listOf("智能轮换", "智力(文科)", "力量(体育)", "魅力(艺术)"),
            secondarySegmentedPrefKey = PreferencesHelper.KEY_COURSE_SUBJECT,
            secondaryTitle = "专攻科目偏好 (按官方属性加点过滤)",
            tertiarySegmentedItems = listOf("任意课时", "基础短课(10-45m)", "进阶长课(1-2.25h)"),
            tertiarySegmentedPrefKey = PreferencesHelper.KEY_COURSE_DURATION,
            tertiaryTitle = "学园课时偏好 (官方阶梯时长)",
            descMapper = { stage ->
                when (stage) {
                    1 -> "就读初级学园 (初阶课程/基础打底)"
                    2 -> "就读中级学园 (进阶课程/技能专精)"
                    3 -> "就读高级学园 (高阶深造/学府殿堂)"
                    4 -> "就读进修学园 (最高学府/极限强化)"
                    else -> "智能自适应 (自动就读已解锁最高学园)"
                }
            }
        )

        val workControls = createAppleStyleRow(
            badgeIcon = "💼",
            badgeColor = "#34C759",
            title = "全自动打工派遣",
            desc = "元气饱满自动参与打工赚金币",
            prefKey = "key_work",
            defaultVal = true,
            isLast = false,
            segmentedItems = listOf("演艺文化(高收)", "文职商业", "体力搬运", "三业轮换"),
            segmentedPrefKey = PreferencesHelper.KEY_WORK_TYPE,
            secondarySegmentedItems = listOf("智能挂机", "10分钟", "45分钟", "2小时", "4小时"),
            secondarySegmentedPrefKey = PreferencesHelper.KEY_WORK_DURATION,
            secondaryTitle = "打工时长偏好 (官方实测阶梯工时)",
            descMapper = { mode ->
                when (mode) {
                    0 -> "演艺文化行业 (官方最高收益 · 结界除灵)"
                    1 -> "文职商业行业 (招牌底稿 · 稳定进账)"
                    2 -> "体力搬运行业 (守候拼图 · 体能锻炼)"
                    else -> "三业轮流派遣 (演艺->文职->体力循环)"
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
            setPadding(0, 0, 0, dp(context, 8))
        }
        row3.addView(createSecondaryButton("💰 结算并领取探险收益", "settle", isFullWidth = true))
        root.addView(row3)

        // 强力即时召回按钮 (官方 0x9760_1 中断召回)
        val recallButton = Button(context).apply {
            text = "🚨 立即召回宠物回家 (中断学习/打工)"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF3B30"))
                cornerRadius = dp(context, 12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 44)
            ).apply {
                setMargins(0, 0, 0, dp(context, 14))
            }
            setOnClickListener {
                triggerAction(context, engine, "recall")
                mainHandler.postDelayed({
                    subtitleView.text = PetAdventureEngine.formatLiveStatusText()
                }, 1000L)
            }
        }
        root.addView(recallButton)

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

        val applyUnlockStates: (QQPetDirectBridge.SecondMapDetails?, List<QQPetDirectBridge.SelectEvent>?, List<QQPetDirectBridge.SelectEvent>?) -> Unit = { details, courses, jobs ->
            if (details != null && details.code == 0) {
                val curStage = details.currentStage
                val stageItems = mutableListOf<SegmentItem>()
                // 0: 自适应
                stageItems.add(SegmentItem("自适应", enabled = true))

                // 1: 初级
                val s1 = details.stages.find { it.stage == 1 }
                val s1Grad = curStage > 1 || (s1?.isGraduated == true)
                val s1Enable = curStage == 1
                stageItems.add(
                    SegmentItem(
                        if (s1Grad) "初级(已毕业)" else "初级",
                        enabled = s1Enable,
                        disabledTip = if (s1Grad) "初级学园已毕业（腾讯规则禁止重复就读）" else if (!s1Enable) "初级学园尚未解锁" else null
                    )
                )

                // 2: 中级
                val s2 = details.stages.find { it.stage == 2 }
                val s2Grad = curStage > 2 || (s2?.isGraduated == true)
                val s2Enable = curStage == 2
                stageItems.add(
                    SegmentItem(
                        if (s2Grad) "中级(已毕业)" else if (curStage < 2) "中级(未解锁)" else "中级",
                        enabled = s2Enable,
                        disabledTip = if (s2Grad) "中级学园已毕业（腾讯规则禁止重复就读）" else if (curStage < 2) "中级学园尚未解锁（需先完成初级修习）" else null
                    )
                )

                // 3: 高级
                val s3 = details.stages.find { it.stage == 3 }
                val s3Grad = curStage > 3 || (s3?.isGraduated == true)
                val s3Enable = curStage == 3
                stageItems.add(
                    SegmentItem(
                        if (s3Grad) "高级(已毕业)" else if (curStage < 3) "高级(未解锁)" else "高级",
                        enabled = s3Enable,
                        disabledTip = if (s3Grad) "高级学园已毕业" else if (curStage < 3) "高级学园尚未解锁（需先完成中级深造）" else null
                    )
                )

                // 4: 进修
                val s4Enable = curStage == 4
                stageItems.add(
                    SegmentItem(
                        if (!s4Enable) "进修(未解锁)" else "进修",
                        enabled = s4Enable,
                        disabledTip = if (!s4Enable) "进修学园尚未解锁（需先完成高级学府深造）" else null
                    )
                )

                studyControls.primarySeg?.updateItemStates(stageItems)

                val curStageName = when (curStage) {
                    1 -> "初级学园"
                    2 -> "中级学园"
                    3 -> "高级学园"
                    4 -> "进修学园"
                    else -> "第${curStage}阶段学园"
                }
                val attrPart = if (details.power > 0 || details.intel > 0 || details.charm > 0) {
                    " · 力量${details.power} 智力${details.intel} 魅力${details.charm}"
                } else ""
                val savedStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
                studyControls.descView.text = if (savedStage == 0) {
                    "智能自适应: 当前就读 $curStageName$attrPart"
                } else {
                    "已锁定 $curStageName (已解锁最高学府)$attrPart"
                }
            }

            if (!jobs.isNullOrEmpty()) {
                val j10 = jobs.find { it.costTime.contains("10") }
                val j45 = jobs.find { it.costTime.contains("45") }
                val j2h = jobs.find { it.costTime.contains("2小时") }
                val j4h = jobs.find { it.costTime.contains("4小时") }

                val can10 = j10?.canDo ?: true
                val can45 = j45?.canDo ?: true
                val can2h = j2h?.canDo ?: true
                val can4h = j4h?.canDo ?: true

                val jobItems = listOf(
                    SegmentItem("智能挂机", enabled = true),
                    SegmentItem(if (can10) "10分钟" else "10分(锁)", enabled = can10, disabledTip = if (!can10) "10分钟兼职暂未解锁" else null),
                    SegmentItem(if (can45) "45分钟" else "45分(锁)", enabled = can45, disabledTip = if (!can45) "45分钟兼职暂未解锁" else null),
                    SegmentItem(if (can2h) "2小时" else "2小时(锁)", enabled = can2h, disabledTip = if (!can2h) "2小时兼职暂未解锁" else null),
                    SegmentItem(if (can4h) "4小时" else "4小时(锁)", enabled = can4h, disabledTip = if (!can4h) "4小时兼职暂未解锁" else null)
                )
                workControls.secondarySeg?.updateItemStates(jobItems)

                val unlockedCount = listOf(can10, can45, can2h, can4h).count { it }
                val workSummary = if (unlockedCount == 4) "全部4阶工时均已解锁" else "已解锁 $unlockedCount/4 阶工时"
                val curType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
                val typeName = when (curType) { 0 -> "演艺文化(高收)"; 1 -> "文职商业"; 2 -> "体力搬运"; else -> "三业轮换" }
                workControls.descView.text = "$typeName · $workSummary (极速/短工/中工/长工)"
            }
        }

        // 立即应用内存中已有的解锁缓存（0ms 秒开无白屏）
        applyUnlockStates(
            PetAdventureEngine.cachedSchoolDetails,
            PetAdventureEngine.cachedSchoolCourses,
            PetAdventureEngine.cachedWorkJobs
        )

        // 弹窗拉起时即刻同步服务端最新进度与倒计时
        val activeEngine = engine ?: HookEntry.globalEngine
        if (activeEngine != null) {
            activeEngine.startBackgroundLoop(context.applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                val petId = PetAdventureEngine.cachedPetId ?: activeEngine.queryOwnPetAwait().second
                if (!petId.isNullOrEmpty()) {
                    // 率先抓取账号全量学业与打工解锁数据
                    val (details, courses, jobs) = activeEngine.preloadAccountDataAwait(petId)
                    mainHandler.post {
                        applyUnlockStates(details, courses, jobs)
                    }

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
        val schoolStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val courseSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val courseDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val workType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val workDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)

        HookEntry.globalEngine?.updateConfig(study, work, care, adv, settle, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration)
        if (engine != null && engine !== HookEntry.globalEngine) {
            engine.updateConfig(study, work, care, adv, settle, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration)
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
            putExtra("extra_school_stage", schoolStage)
            putExtra("extra_course_subject", courseSubject)
            putExtra("extra_course_duration", courseDuration)
            putExtra("extra_work_type", workType)
            putExtra("extra_work_duration", workDuration)
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
