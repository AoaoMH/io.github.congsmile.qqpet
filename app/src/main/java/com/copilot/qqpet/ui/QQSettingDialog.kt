package com.copilot.qqpet.ui

import android.animation.ValueAnimator
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.engine.PetAdventureEngine
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 纯代码自绘制的 iOS 标准 Toggle 控件 (AppleSwitchView)
 * 物理尺寸：51dp x 31dp (符合 iOS Human Interface Guidelines)
 * 开启背景：#34C759，关闭背景：#E9E9EB，滑块：纯白圆润 + 柔和微投影
 */
class AppleSwitchView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(2f), 0f, dp(1f), Color.parseColor("#25000000"))
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12000000")
    }

    private val rect = RectF()
    private var progress = 1.0f
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
            duration = 240
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                progress = va.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
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

        val offR = 0xE9; val offG = 0xE9; val offB = 0xEB
        val onR = 0x34; val onG = 0xC7; val onB = 0x59

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
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
 * 纯文字自绘制的 iOS 标准 Segmented Control (纯文字分段药丸选择器)
 * 底座：#EBEBED，选中白色高亮卡片：#FFFFFF，未解锁置灰与点击拦截
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
                setPadding(dp(2f).toInt(), dp(6.5f).toInt(), dp(2f).toInt(), dp(6.5f).toInt())
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
                setOnClickListener {
                    val state = itemStates.getOrNull(index)
                    if (state != null && !state.enabled) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val tip = state.disabledTip ?: "该选项尚未解锁"
                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (selectedIndex != index) {
                        selectedIndex = index
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        alpha = if (enabled) 1.0f else 0.38f
        isEnabled = enabled
        textViews.forEach { it.isEnabled = enabled }
    }

    fun updateItemStates(newStates: List<SegmentItem>) {
        if (newStates.size != itemStates.size) return
        for (i in newStates.indices) {
            itemStates[i] = newStates[i]
            val tv = textViews.getOrNull(i) ?: continue
            tv.text = newStates[i].title
            tv.alpha = if (newStates[i].enabled) 1.0f else 0.35f
        }
    }

    private fun updateSelection() {
        for (i in textViews.indices) {
            val tv = textViews[i]
            val isSel = (i == selectedIndex)
            if (isSel) {
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                tv.setTextColor(Color.parseColor("#1C1C1E"))
                tv.background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(6.5f)
                    setStroke(dp(0.5f).toInt(), Color.parseColor("#15000000"))
                }
            } else {
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tv.setTextColor(Color.parseColor("#8E8E93"))
                tv.background = null
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
 * 遵循 Apple Design 顶级审美标准的 QQ 原生二级设置页面 (v1.0.28)
 * 1. 纯正二级设置页面：全屏沉浸，顶部携带返回导航栏，右滑/返回键平滑退场
 * 2. 纯文字排版（Typography）：移除全部 Emoji 与位图，依赖字阶、字重与卡片间距构建呼吸感
 * 3. 物理触感与弹簧动效：触控瞬间微缩反馈 (Response)，分段器平滑切换，子设置平滑展开/折叠
 * 4. 0ms 秒开无白屏：秒级复用内存数据渲染，后台静默异步刷新校验
 */
object QQSettingDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    fun show(activity: Activity, engine: PetAdventureEngine?) {
        val context = activity
        var dialogInstance: Dialog? = null

        // 根布局：全屏浅灰底色 (#F2F2F7, iOS 系统标准)
        val fullRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ================= 1. 顶部 Navigation Bar (iOS 标准二级顶栏) =================
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            setPadding(dp(context, 12), dp(context, 8), dp(context, 16), dp(context, 10))
        }

        // 返回按钮：纯文字 "‹ 设置"，带触控物理微缩动效
        val backBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4), dp(context, 4), dp(context, 10), dp(context, 4))
            applyTouchSpringEffect(this)
            setOnClickListener {
                dismissWithAnimation(fullRoot, dialogInstance)
            }
        }

        val backArrow = TextView(context).apply {
            text = "‹"
            textSize = 24f
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(0, 0, dp(context, 2), dp(context, 2))
        }
        val backText = TextView(context).apply {
            text = "设置"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#007AFF"))
        }
        backBtn.addView(backArrow)
        backBtn.addView(backText)
        topBar.addView(backBtn)

        // 中间标题：Q宠后台伴侣
        val navTitle = TextView(context).apply {
            text = "Q宠后台伴侣"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        topBar.addView(navTitle)

        // 右侧状态胶囊：● 运行中
        val statusPill = TextView(context).apply {
            text = "● 运行中"
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#34C759"))
            setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EBF9EE"))
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        topBar.addView(statusPill)
        fullRoot.addView(topBar)

        // 顶栏底部分割线
        val topDivider = View(context).apply {
            setBackgroundColor(Color.parseColor("#E5E5EA"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        fullRoot.addView(topDivider)

        // ================= 2. 页面主体滚动容器 (Grouped List) =================
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 36))
        }

        // --- 头部实时状态卡片 ---
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(context, 12).toFloat()
            }
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 16))
            }
        }

        val statusActionText = TextView(context).apply {
            text = PetAdventureEngine.formatLiveStatusText()
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        val statusAttributesText = TextView(context).apply {
            val d = PetAdventureEngine.cachedSchoolDetails
            text = if (d != null && d.code == 0) {
                "小宠资质 · 力量 ${d.power}  智力 ${d.intel}  魅力 ${d.charm}"
            } else {
                "小宠资质 · 实时同步官方属性中"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 4), 0, 0)
        }
        statusCard.addView(statusActionText)
        statusCard.addView(statusAttributesText)
        contentLayout.addView(statusCard)

        val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)

        // --- 分组辅助方法 ---
        fun addSectionHeader(title: String) {
            val hView = TextView(context).apply {
                text = title
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor("#6C6C70"))
                setPadding(dp(context, 4), 0, 0, dp(context, 7))
            }
            contentLayout.addView(hView)
        }

        fun createGroupCard(): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(context, 12).toFloat()
                }
                setPadding(dp(context, 16), 0, dp(context, 16), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(context, 18))
                }
            }
        }

        fun createDivider(): View {
            return View(context).apply {
                setBackgroundColor(Color.parseColor("#E5E5EA"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            }
        }

        // ================= 3. 分组一：自动轮转调度 =================
        addSectionHeader("自动轮转调度")
        val autoGroupCard = createGroupCard()

        // --- 条目 1：进阶学力研修 ---
        val studyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 13), 0, dp(context, 13))
        }
        val studyTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(context, 10), 0)
            }
        }
        val studyTitle = TextView(context).apply {
            text = "进阶学力研修"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        val currentStagePref = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val studySubtitle = TextView(context).apply {
            text = getSchoolStageDesc(currentStagePref)
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 2), 0, 0)
        }
        studyTextCol.addView(studyTitle)
        studyTextCol.addView(studySubtitle)
        studyRow.addView(studyTextCol)

        // 学历配置折叠面板 (纯文字分段器)
        val studyPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(context, 12))
        }

        // 阶段分段器
        val stageLabel = TextView(context).apply {
            text = "学园阶段"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }
        val stageSeg = AppleSegmentedControl(
            context,
            listOf("自适应", "初级", "中级", "高级", "进修"),
            currentStagePref
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_SCHOOL_STAGE, sel).commit()
            studySubtitle.text = getSchoolStageDesc(sel)
            syncConfig(prefs, engine, context)
        }
        studyPanel.addView(stageLabel)
        studyPanel.addView(stageSeg)

        // 科目分段器
        val currentSubjPref = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val subjLabel = TextView(context).apply {
            text = "专攻科目 (按官方属性加点)"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        val subjSeg = AppleSegmentedControl(
            context,
            listOf("智能轮换", "智力(文科)", "力量(体育)", "魅力(艺术)"),
            currentSubjPref
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_SUBJECT, sel).commit()
            syncConfig(prefs, engine, context)
        }
        studyPanel.addView(subjLabel)
        studyPanel.addView(subjSeg)

        // 课时分段器
        val currentDurPref = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
        val durLabel = TextView(context).apply {
            text = "课时时长偏好"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        val durSeg = AppleSegmentedControl(
            context,
            listOf("任意课时", "基础短课(10-45m)", "进阶长课(1-2.25h)"),
            currentDurPref
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_DURATION, sel).commit()
            syncConfig(prefs, engine, context)
        }
        studyPanel.addView(durLabel)
        studyPanel.addView(durSeg)

        val studyInitialChecked = prefs.getBoolean("key_study", true)
        val studySwitch = AppleSwitchView(context).apply {
            setCheckedImmediately(studyInitialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean("key_study", isChecked).commit()
                animateExpandCollapse(studyPanel, isChecked)
                syncConfig(prefs, engine, context)
            }
        }
        studyRow.addView(studySwitch)
        autoGroupCard.addView(studyRow)
        if (!studyInitialChecked) {
            studyPanel.visibility = View.GONE
        }
        autoGroupCard.addView(studyPanel)
        autoGroupCard.addView(createDivider())

        // --- 条目 2：全自动打工派遣 ---
        val workRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 13), 0, dp(context, 13))
        }
        val workTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(context, 10), 0)
            }
        }
        val workTitle = TextView(context).apply {
            text = "全自动打工派遣"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        val currentWorkTypePref = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val workSubtitle = TextView(context).apply {
            text = getWorkTypeDesc(currentWorkTypePref)
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 2), 0, 0)
        }
        workTextCol.addView(workTitle)
        workTextCol.addView(workSubtitle)
        workRow.addView(workTextCol)

        // 打工配置折叠面板 (纯文字分段器)
        val workPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(context, 12))
        }

        val workTypeLabel = TextView(context).apply {
            text = "行业偏好"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }
        var workDurSeg: AppleSegmentedControl? = null
        val workTypeSeg = AppleSegmentedControl(
            context,
            listOf("演艺文化(高收)", "文职商业", "体力搬运", "三业轮换"),
            currentWorkTypePref
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_TYPE, sel).commit()
            workSubtitle.text = getWorkTypeDesc(sel)
            syncConfig(prefs, engine, context)
            val active = engine ?: HookEntry.globalEngine
            if (active != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val petId = PetAdventureEngine.cachedPetId ?: active.queryOwnPetAwait().second
                    if (!petId.isNullOrEmpty()) {
                        val career = when (sel) { 0 -> 3; 1 -> 1; 2 -> 2; else -> 3 }
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
                                workDurSeg?.updateItemStates(jobItems)
                            }
                        }
                    }
                }
            }
        }
        workPanel.addView(workTypeLabel)
        workPanel.addView(workTypeSeg)

        val currentWorkDurPref = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val workDurLabel = TextView(context).apply {
            text = "打工时长偏好 (官方实测阶梯工时)"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        workDurSeg = AppleSegmentedControl(
            context,
            listOf("智能挂机", "10分钟", "45分钟", "2小时", "4小时"),
            currentWorkDurPref
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_DURATION, sel).commit()
            syncConfig(prefs, engine, context)
        }
        workPanel.addView(workDurLabel)
        workPanel.addView(workDurSeg)

        val workInitialChecked = prefs.getBoolean("key_work", true)
        val workSwitch = AppleSwitchView(context).apply {
            setCheckedImmediately(workInitialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean("key_work", isChecked).commit()
                animateExpandCollapse(workPanel, isChecked)
                syncConfig(prefs, engine, context)
            }
        }
        workRow.addView(workSwitch)
        autoGroupCard.addView(workRow)
        if (!workInitialChecked) {
            workPanel.visibility = View.GONE
        }
        autoGroupCard.addView(workPanel)
        contentLayout.addView(autoGroupCard)

        // ================= 4. 分组二：日常起居与历练 =================
        addSectionHeader("日常起居与历练")
        val dailyCard = createGroupCard()

        fun addSimpleToggleRow(
            card: LinearLayout,
            title: String,
            desc: String,
            prefKey: String,
            defaultVal: Boolean,
            isLast: Boolean
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 12), 0, dp(context, 12))
            }
            val textCol = LinearLayout(context).apply {
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
            val dView = TextView(context).apply {
                text = desc
                textSize = 13f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(0, dp(context, 2), 0, 0)
            }
            textCol.addView(tView)
            textCol.addView(dView)
            row.addView(textCol)

            val initialChecked = prefs.getBoolean(prefKey, defaultVal)
            val sw = AppleSwitchView(context).apply {
                setCheckedImmediately(initialChecked)
                onCheckedChangeListener = { isChecked ->
                    prefs.edit().putBoolean(prefKey, isChecked).commit()
                    syncConfig(prefs, engine, context)
                }
            }
            row.addView(sw)
            card.addView(row)
            if (!isLast) {
                card.addView(createDivider())
            }
        }

        addSimpleToggleRow(dailyCard, "自动进食与沐浴", "饥饿肮脏时自动进食、洗澡沐浴", "key_care", true, false)
        addSimpleToggleRow(dailyCard, "神秘森林冒险", "自动深入野外林区探秘与冒险", "key_adventure", false, false)
        addSimpleToggleRow(dailyCard, "探险收益结算", "历练归来自动领取全部掉落收益", "key_settle", true, true)
        contentLayout.addView(dailyCard)

        // ================= 5. 分组三：手动即时指令 (iOS Action List 纯文字) =================
        addSectionHeader("手动即时指令")
        val actionCard = createGroupCard()

        fun addActionItem(
            card: LinearLayout,
            title: String,
            colorHex: String = "#1C1C1E",
            isBold: Boolean = false,
            isLast: Boolean = false,
            onClick: () -> Unit
        ) {
            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 14), 0, dp(context, 14))
                applyTouchSpringEffect(this)
                setOnClickListener {
                    onClick()
                }
            }
            val tView = TextView(context).apply {
                text = title
                textSize = 15.5f
                typeface = if (isBold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor(colorHex))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            itemRow.addView(tView)
            card.addView(itemRow)
            if (!isLast) {
                card.addView(createDivider())
            }
        }

        addActionItem(actionCard, "立即执行全套巡检与养成", colorHex = "#007AFF", isBold = true, isLast = false) {
            triggerAction(context, engine, "cycle")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(actionCard, "立即派遣打工", colorHex = "#1C1C1E", isLast = false) {
            triggerAction(context, engine, "work")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(actionCard, "立即启程学习", colorHex = "#1C1C1E", isLast = false) {
            triggerAction(context, engine, "school")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(actionCard, "立即野外探险", colorHex = "#1C1C1E", isLast = false) {
            triggerAction(context, engine, "adventure")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(actionCard, "立即结算探险收益", colorHex = "#1C1C1E", isLast = false) {
            triggerAction(context, engine, "settle")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(actionCard, "立即召回宠物回家 (中断当前打工/学习)", colorHex = "#FF3B30", isBold = true, isLast = true) {
            triggerAction(context, engine, "recall")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        contentLayout.addView(actionCard)

        // ================= 6. 分组四：更多 =================
        addSectionHeader("更多")
        val moreCard = createGroupCard()
        val moreRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 14), 0, dp(context, 14))
            applyTouchSpringEffect(this)
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
        val moreTitle = TextView(context).apply {
            text = "进入伴侣独立 App 管理更多细节"
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val moreArrow = TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(Color.parseColor("#C7C7CC"))
        }
        moreRow.addView(moreTitle)
        moreRow.addView(moreArrow)
        moreCard.addView(moreRow)
        contentLayout.addView(moreCard)

        // 外层滚动
        val scrollView = ScrollView(context).apply {
            addView(contentLayout)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        fullRoot.addView(scrollView)

        // 原生二级设置页面 Dialog
        dialogInstance = object : Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                dismissWithAnimation(fullRoot, this)
            }
        }.apply {
            setContentView(fullRoot)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        dialogInstance.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#F2F2F7")))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            attributes = attributes?.apply {
                dimAmount = 0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = Color.parseColor("#F2F2F7")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        // 挂载秒级倒计时心跳
        val tickerRunnable = object : Runnable {
            override fun run() {
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
                val d = PetAdventureEngine.cachedSchoolDetails
                if (d != null && d.code == 0) {
                    statusAttributesText.text = "小宠资质 · 力量 ${d.power}  智力 ${d.intel}  魅力 ${d.charm}"
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.post(tickerRunnable)
        dialogInstance.setOnDismissListener {
            mainHandler.removeCallbacks(tickerRunnable)
        }

        // 应用账号数据解锁状态与置灰拦截
        val applyUnlockStates: (QQPetDirectBridge.SecondMapDetails?, List<QQPetDirectBridge.SelectEvent>?, List<QQPetDirectBridge.SelectEvent>?) -> Unit = { details, _, jobs ->
            if (details != null && details.code == 0) {
                val curStage = details.currentStage
                val stageItems = mutableListOf<SegmentItem>()
                stageItems.add(SegmentItem("自适应", enabled = true))
                val stageNames = listOf("初级", "中级", "高级", "进修")
                for (s in 1..4) {
                    val sInfo = details.stages.find { it.stage == s }
                    val name = stageNames[s - 1]
                    val isGrad = sInfo?.isGraduated == true
                    val isLocked = (sInfo?.limitStatus ?: 0) != 0 && (sInfo?.limitStatus ?: 0) != 2
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
                    stageItems.add(SegmentItem(title, enabled = enabled, disabledTip = tip))
                }
                stageSeg.updateItemStates(stageItems)

                if (currentStagePref == 0) {
                    val stageTitle = when (curStage) {
                        1 -> "初级学园"
                        2 -> "中级学园"
                        3 -> "高级学园"
                        4 -> "进修学园"
                        else -> "高级学园"
                    }
                    studySubtitle.text = "已锁定 $stageTitle (已解锁最高学府)"
                }
                statusAttributesText.text = "小宠资质 · 力量 ${details.power}  智力 ${details.intel}  魅力 ${details.charm}"
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
                    SegmentItem(if (can10) "10分钟" else "10分(锁)", enabled = can10, disabledTip = if (!can10) "10分钟兼职暂未满足解锁条件" else null),
                    SegmentItem(if (can45) "45分钟" else "45分(锁)", enabled = can45, disabledTip = if (!can45) "45分钟兼职暂未满足解锁条件" else null),
                    SegmentItem(if (can2h) "2小时" else "2小时(锁)", enabled = can2h, disabledTip = if (!can2h) "2小时兼职暂未满足解锁条件" else null),
                    SegmentItem(if (can4h) "4小时" else "4小时(锁)", enabled = can4h, disabledTip = if (!can4h) "4小时兼职暂未满足解锁条件" else null)
                )
                workDurSeg?.updateItemStates(jobItems)
            }
        }

        applyUnlockStates(PetAdventureEngine.cachedSchoolDetails, PetAdventureEngine.cachedSchoolCourses, PetAdventureEngine.cachedWorkJobs)

        val activeEngine = engine ?: HookEntry.globalEngine
        if (activeEngine != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val petId = PetAdventureEngine.cachedPetId ?: activeEngine.queryOwnPetAwait().second
                if (!petId.isNullOrEmpty()) {
                    val (d, c, j) = activeEngine.preloadAccountDataAwait(petId)
                    mainHandler.post {
                        applyUnlockStates(d, c, j)
                    }
                }
            }
        }

        // 打开全屏二级页面：从屏幕右侧平滑滑入 (Spring Slide-in)
        dialogInstance.show()
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        fullRoot.translationX = screenWidth
        fullRoot.animate()
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator(2.0f))
            .start()
    }

    /**
     * 退出二级页面：平滑向右滑出 (Spring Slide-out)
     */
    private fun dismissWithAnimation(rootView: View, dialog: Dialog?) {
        val screenWidth = rootView.context.resources.displayMetrics.widthPixels.toFloat()
        rootView.animate()
            .translationX(screenWidth)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2.0f))
            .withEndAction {
                try {
                    dialog?.dismiss()
                } catch (_: Throwable) {}
            }
            .start()
    }

    /**
     * 为视图应用 iOS 物理触控瞬时缩放反馈 (Response: kill latency)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun applyTouchSpringEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator(2.0f)).start()
                }
            }
            false
        }
    }

    /**
     * 子面板平滑折叠/展开动画 (Interruptible Spring Animation)
     */
    private fun animateExpandCollapse(view: View, expand: Boolean) {
        if (expand) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.measure(
                View.MeasureSpec.makeMeasureSpec(view.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = if (view.measuredHeight > 0) view.measuredHeight else dp(view.context, 160)
            view.layoutParams.height = 0
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { va ->
                    val f = va.animatedValue as Float
                    view.layoutParams.height = (targetHeight * f).toInt()
                    view.alpha = f
                    view.requestLayout()
                    if (f >= 1.0f) {
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            anim.start()
        } else {
            val startHeight = view.height
            val anim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 200
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { va ->
                    val f = va.animatedValue as Float
                    view.layoutParams.height = (startHeight * f).toInt()
                    view.alpha = f
                    view.requestLayout()
                    if (f <= 0f) {
                        view.visibility = View.GONE
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            anim.start()
        }
    }

    private fun getSchoolStageDesc(stage: Int): String {
        return when (stage) {
            1 -> "就读初级学园 · 初阶打底"
            2 -> "就读中级学园 · 技能专精"
            3 -> "就读高级学园 · 高阶深造"
            4 -> "就读进修学园 · 最高学府"
            else -> "智能自适应 · 自动就读已解锁最高学府"
        }
    }

    private fun getWorkTypeDesc(mode: Int): String {
        return when (mode) {
            0 -> "演艺文化行业 · 最高收益"
            1 -> "文职商业行业 · 稳定收益"
            2 -> "体力搬运行业 · 体能锻炼"
            else -> "三行业循环派遣"
        }
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
