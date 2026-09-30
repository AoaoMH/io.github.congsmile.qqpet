package com.copilot.qqpet.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
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
class AppleSwitchView(context: Context, private val isNight: Boolean = false) : View(context) {

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

        val offR = if (isNight) 0x39 else 0xE9
        val offG = if (isNight) 0x39 else 0xE9
        val offB = if (isNight) 0x3D else 0xEB
        val onR = if (isNight) 0x30 else 0x34
        val onG = if (isNight) 0xD1 else 0xC7
        val onB = if (isNight) 0x58 else 0x59

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

data class WorkPlaceOption(
    val careerId: Int,
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
    initialItems: List<Any>,
    private var selectedIndex: Int = 0,
    private val isScrollable: Boolean = false,
    private val isNight: Boolean = false,
    private val onItemSelected: (Int) -> Unit
) : LinearLayout(context) {

    private val textViews = mutableListOf<TextView>()
    private val itemStates = mutableListOf<SegmentItem>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(if (isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#EBEBED"))
            cornerRadius = dp(8f)
        }
        setPadding(dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt(), dp(2.5f).toInt())

        val normalizedItems = initialItems.map {
            when (it) {
                is SegmentItem -> it
                is String -> SegmentItem(it, enabled = true)
                else -> SegmentItem(it.toString(), enabled = true)
            }
        }
        rebuildViews(normalizedItems, selectedIndex)
    }

    private fun rebuildViews(newItems: List<SegmentItem>, newSelected: Int) {
        removeAllViews()
        textViews.clear()
        itemStates.clear()
        selectedIndex = if (newSelected in newItems.indices) newSelected else 0
        newItems.forEachIndexed { index, item ->
            itemStates.add(item)
            val tv = TextView(context).apply {
                text = item.title
                textSize = 12f
                gravity = Gravity.CENTER
                if (isScrollable) {
                    setPadding(dp(11f).toInt(), dp(6.5f).toInt(), dp(11f).toInt(), dp(6.5f).toInt())
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                } else {
                    setPadding(dp(2f).toInt(), dp(6.5f).toInt(), dp(2f).toInt(), dp(6.5f).toInt())
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
                }
                alpha = if (item.enabled) 1.0f else 0.35f
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

    fun rebuildItems(newItems: List<SegmentItem>, newSelectedIndex: Int = 0) {
        rebuildViews(newItems, newSelectedIndex)
    }

    fun setSelection(index: Int) {
        if (index in itemStates.indices && index != selectedIndex) {
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
        if (newStates.size != itemStates.size) {
            rebuildViews(newStates, selectedIndex)
            return
        }
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
                tv.setTextColor(if (isNight) Color.WHITE else Color.parseColor("#1C1C1E"))
                tv.background = GradientDrawable().apply {
                    setColor(if (isNight) Color.parseColor("#636366") else Color.WHITE)
                    cornerRadius = dp(6.5f)
                    if (!isNight) {
                        setStroke(dp(0.5f).toInt(), Color.parseColor("#15000000"))
                    }
                }
            } else {
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                tv.setTextColor(Color.parseColor("#8E8E93"))
                tv.background = null
            }
        }
        if (isScrollable) {
            post {
                val selTv = textViews.getOrNull(selectedIndex)
                if (selTv != null) {
                    (parent as? HorizontalScrollView)?.smoothScrollTo((selTv.left - dp(24f)).toInt().coerceAtLeast(0), 0)
                }
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
data class ThemeColors(
    val isNight: Boolean,
    val pageBg: Int,
    val cardBg: Int,
    val cardBorder: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val sectionHeaderText: Int,
    val chevronText: Int,
    val dividerColor: Int,
    val badgeBg: Int,
    val badgeText: Int,
    val actionPrimaryText: Int,
    val actionBlueText: Int,
    val actionRedText: Int
) {
    companion object {
        fun get(isNight: Boolean): ThemeColors {
            return if (isNight) {
                ThemeColors(
                    isNight = true,
                    pageBg = Color.parseColor("#000000"),
                    cardBg = Color.parseColor("#1C1C1E"),
                    cardBorder = Color.parseColor("#26FFFFFF"),
                    primaryText = Color.parseColor("#FFFFFF"),
                    secondaryText = Color.parseColor("#8E8E93"),
                    sectionHeaderText = Color.parseColor("#8E8E93"),
                    chevronText = Color.parseColor("#545458"),
                    dividerColor = Color.parseColor("#2C2C2E"),
                    badgeBg = Color.parseColor("#173420"),
                    badgeText = Color.parseColor("#32D74B"),
                    actionPrimaryText = Color.parseColor("#FFFFFF"),
                    actionBlueText = Color.parseColor("#0A84FF"),
                    actionRedText = Color.parseColor("#FF453A")
                )
            } else {
                ThemeColors(
                    isNight = false,
                    pageBg = Color.parseColor("#F2F2F7"),
                    cardBg = Color.parseColor("#FFFFFF"),
                    cardBorder = Color.parseColor("#14000000"),
                    primaryText = Color.parseColor("#1C1C1E"),
                    secondaryText = Color.parseColor("#8E8E93"),
                    sectionHeaderText = Color.parseColor("#6C6C70"),
                    chevronText = Color.parseColor("#C7C7CC"),
                    dividerColor = Color.parseColor("#E5E5EA"),
                    badgeBg = Color.parseColor("#EBF9EE"),
                    badgeText = Color.parseColor("#34C759"),
                    actionPrimaryText = Color.parseColor("#1C1C1E"),
                    actionBlueText = Color.parseColor("#007AFF"),
                    actionRedText = Color.parseColor("#FF3B30")
                )
            }
        }
    }
}

object QQSettingDialog {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isNightTheme(context: Context): Boolean {
        // 1. 优先尝试 QQ 官方全局 QQTheme.isNowThemeIsNight()
        try {
            val qqThemeClass = context.classLoader.loadClass("com.tencent.mobileqq.utils.QQTheme")
            val method = qqThemeClass.getMethod("isNowThemeIsNight")
            val res = method.invoke(null) as? Boolean
            if (res != null) return res
        } catch (_: Throwable) {}

        // 2. 备选尝试 ThemeUtil.isNowThemeIsNight
        try {
            val themeUtilClass = context.classLoader.loadClass("com.tencent.mobileqq.vas.theme.api.ThemeUtil")
            for (m in themeUtilClass.methods) {
                if (m.name == "isNowThemeIsNight" && m.parameterTypes.isEmpty()) {
                    val res = m.invoke(null) as? Boolean
                    if (res != null) return res
                }
            }
        } catch (_: Throwable) {}

        // 3. 兜底回退：Android 系统 UI Mode (跟随系统深色模式)
        return try {
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            false
        }
    }

    private fun showConfirmDialog(
        context: Context,
        colors: ThemeColors,
        title: String,
        message: String,
        confirmText: String = "确认执行",
        isDestructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#252528") else Color.WHITE)
                cornerRadius = dp(context, 14).toFloat()
                if (colors.isNight) {
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
            }
            setPadding(0, dp(context, 20), 0, 0)
        }

        val titleTv = TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
            gravity = Gravity.CENTER
            setPadding(dp(context, 22), 0, dp(context, 22), dp(context, 8))
        }
        card.addView(titleTv)

        val msgTv = TextView(context).apply {
            text = message
            textSize = 13.5f
            setTextColor(if (colors.isNight) Color.parseColor("#AEAEB2") else Color.parseColor("#3C3C43"))
            gravity = Gravity.CENTER
            setPadding(dp(context, 22), 0, dp(context, 22), dp(context, 18))
            setLineSpacing(dp(context, 2).toFloat(), 1.15f)
        }
        card.addView(msgTv)

        card.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 46))
        }

        val cancelTv = TextView(context).apply {
            text = "取消"
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (colors.isNight) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            applyTouchSpringEffect(this)
            setOnClickListener {
                dialog.dismiss()
            }
        }
        btnRow.addView(cancelTv)

        btnRow.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
        })

        val confirmColor = if (isDestructive) colors.actionRedText else colors.actionBlueText
        val confirmTv = TextView(context).apply {
            text = confirmText
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(confirmColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            applyTouchSpringEffect(this)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        btnRow.addView(confirmTv)
        card.addView(btnRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.42f)
        }
        dialog.show()
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    fun show(activity: Activity, engine: PetAdventureEngine?) {
        val context = activity
        var dialogInstance: Dialog? = null
        val isNight = isNightTheme(context)
        val colors = ThemeColors.get(isNight)

        // 根布局：全屏底色 (日间 #F2F2F7，夜间 #000000)
        val fullRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ================= 1. 顶部 Navigation Bar (iOS 标准二级顶栏) =================
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.pageBg)
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
            setTextColor(if (colors.isNight) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF"))
            setPadding(0, 0, dp(context, 2), dp(context, 2))
        }
        val backText = TextView(context).apply {
            text = "设置"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (colors.isNight) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF"))
        }
        backBtn.addView(backArrow)
        backBtn.addView(backText)
        topBar.addView(backBtn)

        // 中间标题：Q宠后台伴侣
        val navTitle = TextView(context).apply {
            text = "Q宠后台伴侣"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        topBar.addView(navTitle)

        // 右侧状态胶囊：● 运行中
        val statusPill = TextView(context).apply {
            text = "● 运行中"
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.badgeText)
            setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
            background = GradientDrawable().apply {
                setColor(colors.badgeBg)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        topBar.addView(statusPill)
        fullRoot.addView(topBar)

        // 顶栏底部分割线
        val topDivider = View(context).apply {
            setBackgroundColor(colors.dividerColor)
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
                setColor(colors.cardBg)
                cornerRadius = dp(context, 12).toFloat()
                if (colors.isNight) {
                    setStroke(1, colors.cardBorder)
                }
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
            setTextColor(colors.primaryText)
        }
       (engine ?: HookEntry.globalEngine)?.verifyAndSyncAccountSession(context)
       val statusAttributesText = TextView(context).apply {
           val d = PetAdventureEngine.cachedSchoolDetails
           val petId = PetAdventureEngine.cachedPetId
           val attrs = if (!petId.isNullOrEmpty()) HookEntry.globalBridge?.getPetAttributes(petId) else null
           val attrPrefix = if (d != null && d.code == 0) "小宠资质 · 力量 ${d.power}  智力 ${d.intel}  魅力 ${d.charm}" else "小宠资质 · 实时同步官方属性中"
           val liveCare = if (attrs != null && attrs.energy >= 0f) " · 体力 ${attrs.energy.toInt()} 清洁 ${attrs.clean.toInt()}" else ""
           text = "$attrPrefix$liveCare"
           textSize = 13f
           setTextColor(colors.secondaryText)
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
                setTextColor(colors.sectionHeaderText)
                setPadding(dp(context, 4), 0, 0, dp(context, 7))
            }
            contentLayout.addView(hView)
        }

        fun createGroupCard(): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(colors.cardBg)
                    cornerRadius = dp(context, 12).toFloat()
                    if (colors.isNight) {
                        setStroke(1, colors.cardBorder)
                    }
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
                setBackgroundColor(colors.dividerColor)
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
            setTextColor(colors.primaryText)
        }
        val currentStagePref = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val studySubtitle = TextView(context).apply {
            text = getSchoolStageDesc(currentStagePref)
            textSize = 13f
            setTextColor(colors.secondaryText)
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
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }
        val stageSeg = AppleSegmentedControl(
            context,
            listOf("自适应", "初级", "中级", "高级", "进修"),
            currentStagePref,
            isNight = colors.isNight
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
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        val subjSeg = AppleSegmentedControl(
            context,
            listOf("智能轮换", "智力(文科)", "力量(体育)", "魅力(艺术)"),
            currentSubjPref,
            isNight = colors.isNight
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
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        val durSeg = AppleSegmentedControl(
            context,
            listOf("任意课时", "基础短课(10-45m)", "进阶长课(1-2.25h)"),
            currentDurPref,
            isNight = colors.isNight
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_COURSE_DURATION, sel).commit()
            syncConfig(prefs, engine, context)
        }
        studyPanel.addView(durLabel)
        studyPanel.addView(durSeg)

        val studyInitialChecked = prefs.getBoolean("key_study", true)
        val studySwitch = AppleSwitchView(context, colors.isNight).apply {
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
            setTextColor(colors.primaryText)
        }
        var workPlaceOptions = buildWorkPlaceOptions(PetAdventureEngine.cachedWorkPlaces)
        val currentWorkTypePref = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
        val initialWorkPlaceIndex = workPlaceOptions.indexOfFirst { it.careerId == currentWorkTypePref }.let { if (it >= 0) it else 0 }

        val workSubtitle = TextView(context).apply {
            val curOption = workPlaceOptions.getOrNull(initialWorkPlaceIndex)
            text = getWorkTypeDesc(currentWorkTypePref, curOption?.title)
            textSize = 13f
            setTextColor(colors.secondaryText)
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
            text = "打工场所 (职业小镇动态识别)"
            textSize = 12f
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val updateWorkDurSeg: (AppleSegmentedControl?, List<QQPetDirectBridge.SelectEvent>?) -> Unit = { seg, jobs ->
            if (!jobs.isNullOrEmpty() && seg != null) {
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
                seg.updateItemStates(jobItems)
            }
        }

        val workTypeScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        var workDurSeg: AppleSegmentedControl? = null
        val workTypeSeg = AppleSegmentedControl(
            context,
            workPlaceOptions.map { SegmentItem(it.title, enabled = it.enabled, disabledTip = it.disabledTip) },
            initialWorkPlaceIndex,
            isScrollable = true,
            isNight = colors.isNight
        ) { sel ->
            val opt = workPlaceOptions.getOrNull(sel) ?: return@AppleSegmentedControl
            val selCareer = opt.careerId
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_TYPE, selCareer).commit()
            workSubtitle.text = getWorkTypeDesc(selCareer, opt.title)
            syncConfig(prefs, engine, context)
            val active = engine ?: HookEntry.globalEngine
            if (active != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    active.verifyAndSyncAccountSession(context)
                    val petId = PetAdventureEngine.cachedPetId ?: active.queryOwnPetAwait().second?.also {
                        PetAdventureEngine.saveScopedPetId(context, it)
                    }
                    if (!petId.isNullOrEmpty()) {
                        val career = if (selCareer > 0) selCareer else 3
                        val (jCode, jobs) = active.querySelectEventsAwait(6400L, petId, schoolStage = 0, careerType = career)
                        if (jCode == 0 && jobs.isNotEmpty()) {
                            PetAdventureEngine.cachedWorkJobs = jobs
                            mainHandler.post {
                                updateWorkDurSeg(workDurSeg, jobs)
                            }
                        }
                    }
                }
            }
        }
        workTypeScrollView.addView(
            workTypeSeg,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        workPanel.addView(workTypeLabel)
        workPanel.addView(workTypeScrollView)

        val currentWorkDurPref = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
        val workDurLabel = TextView(context).apply {
            text = "打工时长偏好 (官方实测阶梯工时)"
            textSize = 12f
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        workDurSeg = AppleSegmentedControl(
            context,
            listOf("智能挂机", "10分钟", "45分钟", "2小时", "4小时"),
            currentWorkDurPref,
            isNight = colors.isNight
        ) { sel ->
            prefs.edit().putInt(PreferencesHelper.KEY_WORK_DURATION, sel).commit()
            syncConfig(prefs, engine, context)
        }
        updateWorkDurSeg(workDurSeg, PetAdventureEngine.cachedWorkJobs)
        workPanel.addView(workDurLabel)
        workPanel.addView(workDurSeg)

        // --- 打工雇佣好友设置区块 (勾选白名单 + 名字/QQ号搜索 + 空闲最高收益优选) ---
        workPanel.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(context, 12), 0, dp(context, 4))
            }
        })

        val hireToggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        val hireToggleTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(context, 10), 0)
            }
        }
        val hireToggleTitle = TextView(context).apply {
            text = "打工自动雇佣好友"
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
        }
        val hireToggleDesc = TextView(context).apply {
            text = "仅在已勾选的好友中，默认雇佣空闲且收益最高的好友"
            textSize = 12f
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 2), 0, 0)
        }
        hireToggleTextCol.addView(hireToggleTitle)
        hireToggleTextCol.addView(hireToggleDesc)
        hireToggleRow.addView(hireToggleTextCol)

        val hireInitialChecked = prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
        val hireSwitch = AppleSwitchView(context, colors.isNight).apply {
            setCheckedImmediately(hireInitialChecked)
            onCheckedChangeListener = { isChecked ->
                prefs.edit().putBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, isChecked).commit()
                syncConfig(prefs, engine, context)
            }
        }
        hireToggleRow.addView(hireSwitch)
        workPanel.addView(hireToggleRow)

        val hireWhitelistSummaryTv = TextView(context).apply {
            text = formatHireWhitelistSummary(context)
            textSize = 12f
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 2), 0, 0)
        }
        val hireWhitelistRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7"))
                cornerRadius = dp(context, 9).toFloat()
            }
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(context, 4), 0, dp(context, 2))
            }
            applyTouchSpringEffect(this)
            setOnClickListener {
                showHireFriendWhitelistDialog(context, colors, prefs, engine) {
                    hireWhitelistSummaryTv.text = formatHireWhitelistSummary(context)
                }
            }
        }
        val hireWhitelistCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(0, 0, dp(context, 8), 0)
            }
        }
        val hireWhitelistTitle = TextView(context).apply {
            text = "选择雇佣好友白名单 (支持名字/QQ号搜索)"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionBlueText)
        }
        hireWhitelistCol.addView(hireWhitelistTitle)
        hireWhitelistCol.addView(hireWhitelistSummaryTv)
        val hireWhitelistAction = TextView(context).apply {
            text = "勾选 ›"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionBlueText)
        }
        hireWhitelistRow.addView(hireWhitelistCol)
        hireWhitelistRow.addView(hireWhitelistAction)
        workPanel.addView(hireWhitelistRow)

        val workInitialChecked = prefs.getBoolean("key_work", true)
        val workSwitch = AppleSwitchView(context, colors.isNight).apply {
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
               setTextColor(colors.primaryText)
           }
           val dView = TextView(context).apply {
               text = desc
               textSize = 13f
               setTextColor(colors.secondaryText)
               setPadding(0, dp(context, 2), 0, 0)
           }
           textCol.addView(tView)
           textCol.addView(dView)
           row.addView(textCol)

           val initialChecked = prefs.getBoolean(prefKey, defaultVal)
           val sw = AppleSwitchView(context, colors.isNight).apply {
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

       // --- 条目：自动进食与沐浴 (带体力与清洁自选阈值面板) ---
       val careRow = LinearLayout(context).apply {
           orientation = LinearLayout.HORIZONTAL
           gravity = Gravity.CENTER_VERTICAL
           setPadding(0, dp(context, 13), 0, dp(context, 13))
       }
       val careTextCol = LinearLayout(context).apply {
           orientation = LinearLayout.VERTICAL
           layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
               setMargins(0, 0, dp(context, 10), 0)
           }
       }
       val careTitle = TextView(context).apply {
           text = "自动进食与沐浴"
           textSize = 16f
           typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
           setTextColor(colors.primaryText)
       }

       fun getCareSubtitle(energy: Int, clean: Int): String {
           return "体力低于 $energy 自动进食 · 清洁低于 $clean 自动洗澡"
       }

       var curEnergyThresh = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
       var curCleanThresh = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)

       val careSubtitle = TextView(context).apply {
           text = getCareSubtitle(curEnergyThresh, curCleanThresh)
           textSize = 13f
           setTextColor(colors.secondaryText)
           setPadding(0, dp(context, 2), 0, 0)
       }
       careTextCol.addView(careTitle)
       careTextCol.addView(careSubtitle)
       careRow.addView(careTextCol)

       val carePanel = LinearLayout(context).apply {
           orientation = LinearLayout.VERTICAL
           setPadding(0, 0, 0, dp(context, 12))
       }

       val thresholdValues = listOf(40, 60, 80, 90)
       val thresholdLabels = listOf("低于40", "低于60 (推荐)", "低于80", "低于90")

       val energyLabel = TextView(context).apply {
           text = "进食体力阈值 (缺粮时自动采购爱心饼干)"
           textSize = 12f
           setTextColor(colors.secondaryText)
           setPadding(0, dp(context, 4), 0, dp(context, 4))
       }
       val energyInitialIndex = thresholdValues.indexOf(curEnergyThresh).let { if (it >= 0) it else 1 }
       val energySeg = AppleSegmentedControl(
           context,
           thresholdLabels,
           energyInitialIndex,
           isNight = colors.isNight
       ) { sel ->
           val v = thresholdValues.getOrElse(sel) { 60 }
           curEnergyThresh = v
           prefs.edit().putInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, v).commit()
           careSubtitle.text = getCareSubtitle(curEnergyThresh, curCleanThresh)
           syncConfig(prefs, engine, context)
       }
       carePanel.addView(energyLabel)
       carePanel.addView(energySeg)

       val cleanLabel = TextView(context).apply {
           text = "洗澡清洁阈值 (零消耗温水香皂触控洗护)"
           textSize = 12f
           setTextColor(colors.secondaryText)
           setPadding(0, dp(context, 8), 0, dp(context, 4))
       }
       val cleanInitialIndex = thresholdValues.indexOf(curCleanThresh).let { if (it >= 0) it else 1 }
       val cleanSeg = AppleSegmentedControl(
           context,
           thresholdLabels,
           cleanInitialIndex,
           isNight = colors.isNight
       ) { sel ->
           val v = thresholdValues.getOrElse(sel) { 60 }
           curCleanThresh = v
           prefs.edit().putInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, v).commit()
           careSubtitle.text = getCareSubtitle(curEnergyThresh, curCleanThresh)
           syncConfig(prefs, engine, context)
       }
       carePanel.addView(cleanLabel)
       carePanel.addView(cleanSeg)

       val careInitialChecked = prefs.getBoolean("key_care", true)
       val careSwitch = AppleSwitchView(context, colors.isNight).apply {
           setCheckedImmediately(careInitialChecked)
           onCheckedChangeListener = { isChecked ->
               prefs.edit().putBoolean("key_care", isChecked).commit()
               animateExpandCollapse(carePanel, isChecked)
               syncConfig(prefs, engine, context)
           }
       }
       careRow.addView(careSwitch)
       dailyCard.addView(careRow)
      if (!careInitialChecked) {
          carePanel.visibility = View.GONE
      }
      dailyCard.addView(carePanel)
      dailyCard.addView(createDivider())

      // --- 条目：好友宠物自动喂食与洗澡 (默认关闭，开启后每 10 分钟检测全部好友，支持体力与清洁自选阈值) ---
      val friendCareRow = LinearLayout(context).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(0, dp(context, 13), 0, dp(context, 13))
      }
      val friendCareTextCol = LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
              setMargins(0, 0, dp(context, 10), 0)
          }
      }
      val friendCareTitle = TextView(context).apply {
          text = "好友宠物自动喂食洗澡"
          textSize = 16f
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
          setTextColor(colors.primaryText)
      }

      fun getFriendCareSubtitle(energy: Int, clean: Int): String {
          return "默认照料全部好友 · 体力<$energy 喂食 · 清洁<$clean 洗澡 (每10分钟巡检)"
      }

      var curFriendEnergyThresh = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
      var curFriendCleanThresh = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)

      val friendCareSubtitle = TextView(context).apply {
          text = getFriendCareSubtitle(curFriendEnergyThresh, curFriendCleanThresh)
          textSize = 13f
          setTextColor(colors.secondaryText)
          setPadding(0, dp(context, 2), 0, 0)
      }
      friendCareTextCol.addView(friendCareTitle)
      friendCareTextCol.addView(friendCareSubtitle)
      friendCareRow.addView(friendCareTextCol)

      val friendCarePanel = LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(0, 0, 0, dp(context, 12))
      }

      val friendEnergyLabel = TextView(context).apply {
          text = "好友体力喂食阈值 (低于设定值自动帮好友喂食)"
          textSize = 12f
          setTextColor(colors.secondaryText)
          setPadding(0, dp(context, 4), 0, dp(context, 4))
      }
      val friendEnergyInitialIndex = thresholdValues.indexOf(curFriendEnergyThresh).let { if (it >= 0) it else 1 }
      val friendEnergySeg = AppleSegmentedControl(
          context,
          thresholdLabels,
          friendEnergyInitialIndex,
          isNight = colors.isNight
      ) { sel ->
          val v = thresholdValues.getOrElse(sel) { 60 }
          curFriendEnergyThresh = v
          prefs.edit().putInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, v).commit()
          friendCareSubtitle.text = getFriendCareSubtitle(curFriendEnergyThresh, curFriendCleanThresh)
          syncConfig(prefs, engine, context)
      }
      friendCarePanel.addView(friendEnergyLabel)
      friendCarePanel.addView(friendEnergySeg)

      val friendCleanLabel = TextView(context).apply {
          text = "好友清洁洗澡阈值 (低于设定值自动帮好友搓澡)"
          textSize = 12f
          setTextColor(colors.secondaryText)
          setPadding(0, dp(context, 8), 0, dp(context, 4))
      }
      val friendCleanInitialIndex = thresholdValues.indexOf(curFriendCleanThresh).let { if (it >= 0) it else 1 }
      val friendCleanSeg = AppleSegmentedControl(
          context,
          thresholdLabels,
          friendCleanInitialIndex,
          isNight = colors.isNight
      ) { sel ->
          val v = thresholdValues.getOrElse(sel) { 60 }
          curFriendCleanThresh = v
          prefs.edit().putInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, v).commit()
          friendCareSubtitle.text = getFriendCareSubtitle(curFriendEnergyThresh, curFriendCleanThresh)
          syncConfig(prefs, engine, context)
      }
      friendCarePanel.addView(friendCleanLabel)
      friendCarePanel.addView(friendCleanSeg)

      val friendCareInitialChecked = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
      val friendCareSwitch = AppleSwitchView(context, colors.isNight).apply {
          setCheckedImmediately(friendCareInitialChecked)
          onCheckedChangeListener = { isChecked ->
              prefs.edit().putBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, isChecked).commit()
              animateExpandCollapse(friendCarePanel, isChecked)
              syncConfig(prefs, engine, context)
          }
      }
      friendCareRow.addView(friendCareSwitch)
      dailyCard.addView(friendCareRow)
      if (!friendCareInitialChecked) {
          friendCarePanel.visibility = View.GONE
      }
      dailyCard.addView(friendCarePanel)
      dailyCard.addView(createDivider())

      addSimpleToggleRow(dailyCard, "自动回踩访客", "定时巡检并自动回踩到访过我家的小伙伴", PreferencesHelper.KEY_LIKE_BACK, true, false)
       addSimpleToggleRow(dailyCard, "自动领取好友福袋", "自动扫描好友小窝并拆取掉落的金币福袋", PreferencesHelper.KEY_CLAIM_COINBAG, true, false)
       addSimpleToggleRow(dailyCard, "疲惫时自动转冒险", "检测到疲惫收益减少时，取消打工和学习转去冒险直至恢复", PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true, false)
       addSimpleToggleRow(dailyCard, "神秘森林冒险", "自动深入野外林区探秘与冒险", "key_adventure", false, false)
       addSimpleToggleRow(dailyCard, "探险收益结算", "历练归来自动领取全部掉落收益", "key_settle", true, false)
       addSimpleToggleRow(dailyCard, "动态拟人休眠", "随机1~3分钟非固定周期休眠，有效避免行为时序聚类识别", PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true, false)
       addSimpleToggleRow(dailyCard, "QQ设置页纯净隐身", "仅在QQ内部隐藏本弹窗卡片，可通过伴侣独立App管理", PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false, false)
       addSimpleToggleRow(dailyCard, "调试详细日志", "默认静默，开启后向 XposedBridge 打印详细发包日志", PreferencesHelper.KEY_DEBUG_LOG, false, true)
       contentLayout.addView(dailyCard)

        // ================= 5. 分组三：手动即时指令 (iOS Action List 纯文字) =================
        addSectionHeader("手动即时指令")
        val actionCard = createGroupCard()

        fun addActionItem(
            card: LinearLayout,
            title: String,
            confirmTitle: String,
            confirmMessage: String,
            confirmBtnText: String = "确认执行",
            isDestructive: Boolean = false,
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
                    showConfirmDialog(
                        context = context,
                        colors = colors,
                        title = confirmTitle,
                        message = confirmMessage,
                        confirmText = confirmBtnText,
                        isDestructive = isDestructive,
                        onConfirm = onClick
                    )
                }
            }
            val resolvedTextColor = when (colorHex) {
                "#007AFF" -> colors.actionBlueText
                "#FF3B30" -> colors.actionRedText
                else -> colors.actionPrimaryText
            }
            val tView = TextView(context).apply {
                text = title
                textSize = 15.5f
                typeface = if (isBold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(resolvedTextColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            itemRow.addView(tView)
            card.addView(itemRow)
            if (!isLast) {
                card.addView(createDivider())
            }
        }

        addActionItem(
            card = actionCard,
            title = "立即执行全套巡检与养成",
            confirmTitle = "执行全套巡检与养成？",
            confirmMessage = "将立即同步小宠最新资质与起居状态，按需触发进食洗澡，并依序规划自适应日程。",
            confirmBtnText = "立即执行",
            colorHex = "#007AFF",
            isBold = true,
            isLast = false
        ) {
            triggerAction(context, engine, "cycle")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即派遣打工",
            confirmTitle = "立即派遣打工？",
            confirmMessage = "将根据设定的打工场所与时长偏好，立即为小宠开启新一轮勤劳打工。",
            confirmBtnText = "立即打工",
            colorHex = "#1C1C1E",
            isLast = false
        ) {
            triggerAction(context, engine, "work")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即启程学习",
            confirmTitle = "立即启程学习？",
            confirmMessage = "将根据设定的学府与专攻科目偏好，立即为小宠安排官方课程研修。",
            confirmBtnText = "立即学习",
            colorHex = "#1C1C1E",
            isLast = false
        ) {
            triggerAction(context, engine, "school")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即野外探险",
            confirmTitle = "立即野外探险？",
            confirmMessage = "将立即启程前往神秘森林，开启野外探秘与修行历练。",
            confirmBtnText = "立即探险",
            colorHex = "#1C1C1E",
            isLast = false
        ) {
            triggerAction(context, engine, "adventure")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即结算探险收益",
            confirmTitle = "结算探险收益？",
            confirmMessage = "将立即向服务端请求结算当前探险掉落，领回所有金币、经验与道具奖励。",
            confirmBtnText = "立即结算",
            colorHex = "#1C1C1E",
            isLast = false
        ) {
            triggerAction(context, engine, "settle")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即回踩访客 (互相踩踩)",
            confirmTitle = "立即回踩访客？",
            confirmMessage = "将拉取最近造访小家的好友记录，并依次向未回赠的好友发起回踩送心。",
            confirmBtnText = "立即回踩",
            colorHex = "#007AFF",
            isLast = false
        ) {
            triggerAction(context, engine, "like_back")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即领取好友福袋",
            confirmTitle = "立即领取好友福袋？",
            confirmMessage = "将立即扫描全部好友小窝，发现掉落福袋时自动拆袋领取金币奖励。",
            confirmBtnText = "立即拆福袋",
            colorHex = "#007AFF",
            isLast = false
        ) {
            triggerAction(context, engine, "coinbag")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即帮全部好友喂食与洗澡",
            confirmTitle = "立即帮好友宠物喂食洗澡？",
            confirmMessage = "将立即检测全部养宠好友的实时体力与清洁度，低于设定阈值时自动帮好友喂食与搓澡。",
            confirmBtnText = "立即照料好友",
            colorHex = "#007AFF",
            isLast = false
        ) {
            triggerAction(context, engine, "friend_care")
            mainHandler.postDelayed({
                statusActionText.text = PetAdventureEngine.formatLiveStatusText()
            }, 800L)
        }
        addActionItem(
            card = actionCard,
            title = "立即召回宠物回家 (中断当前打工/学习)",
            confirmTitle = "确认召回宠物回家？",
            confirmMessage = "此操作将强制中断小宠当前正在进行的打工或学习派遣，提前返程回家。",
            confirmBtnText = "确认召回",
            isDestructive = true,
            colorHex = "#FF3B30",
            isBold = true,
            isLast = true
        ) {
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
                        setClassName(HookEntry.MODULE_PACKAGE, "com.copilot.qqpet.ui.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {}
            }
        }
        val moreTitle = TextView(context).apply {
            text = "进入伴侣独立 App 管理更多细节"
            textSize = 15f
            setTextColor(colors.primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val moreArrow = TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(colors.chevronText)
        }
        moreRow.addView(moreTitle)
        moreRow.addView(moreArrow)
        moreCard.addView(moreRow)

        val moreDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(context, 16)
            }
            setBackgroundColor(colors.dividerColor)
        }
        val groupRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 14), 0, dp(context, 14))
            applyTouchSpringEffect(this)
            setOnClickListener {
                val groupUin = "1087942084"
                val nativeUri = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$groupUin&card_type=group&source=qrcode"
                val webUrl = "https://qm.qq.com/q/FY6w7PMH2c"
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(nativeUri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {
                    try {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                    } catch (_: Throwable) {}
                }
            }
        }
        val groupTitle = TextView(context).apply {
            text = "进入官方反馈交流群"
            textSize = 15f
            setTextColor(colors.primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val groupArrow = TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(colors.chevronText)
        }
        groupRow.addView(groupTitle)
        groupRow.addView(groupArrow)

        moreCard.addView(moreDivider)
        moreCard.addView(groupRow)
        contentLayout.addView(moreCard)

        // 外层滚动
        val scrollView = ScrollView(context).apply {
            addView(contentLayout)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        fullRoot.addView(scrollView)

        // 原生二级设置页面 Dialog
        val dialogThemeRes = if (colors.isNight) {
            android.R.style.Theme_DeviceDefault_NoActionBar
        } else {
            android.R.style.Theme_DeviceDefault_Light_NoActionBar
        }
        dialogInstance = object : Dialog(context, dialogThemeRes) {
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
            setBackgroundDrawable(ColorDrawable(colors.pageBg))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            attributes = attributes?.apply {
                dimAmount = 0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = colors.pageBg
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = if (colors.isNight) {
                    decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }

       // 挂载秒级倒计时心跳
       val tickerRunnable = object : Runnable {
           override fun run() {
               statusActionText.text = PetAdventureEngine.formatLiveStatusText()
               val d = PetAdventureEngine.cachedSchoolDetails
               val petId = PetAdventureEngine.cachedPetId
               val attrs = if (!petId.isNullOrEmpty()) HookEntry.globalBridge?.getPetAttributes(petId) else null
               val attrPrefix = if (d != null && d.code == 0) "小宠资质 · 力量 ${d.power}  智力 ${d.intel}  魅力 ${d.charm}" else "小宠资质 · 实时同步官方属性中"
               val liveCare = if (attrs != null && attrs.energy >= 0f) " · 体力 ${attrs.energy.toInt()} 清洁 ${attrs.clean.toInt()}" else ""
               statusAttributesText.text = "$attrPrefix$liveCare"
               mainHandler.postDelayed(this, 1000L)
           }
       }
        mainHandler.post(tickerRunnable)
        dialogInstance.setOnDismissListener {
            mainHandler.removeCallbacks(tickerRunnable)
        }

        // 应用账号数据解锁状态与置灰拦截
        val applyUnlockStates: (PetAdventureEngine.PreloadedPetData) -> Unit = { preloaded ->
            val schoolMap = preloaded.schoolDetails
            if (schoolMap != null && schoolMap.code == 0) {
                val curStage = schoolMap.currentStage
                val stageItems = mutableListOf<SegmentItem>()
                stageItems.add(SegmentItem("自适应", enabled = true))
                val stageNames = listOf("初级", "中级", "高级", "进修")
                for (s in 1..4) {
                    val sInfo = schoolMap.stages.find { it.stage == s }
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
                val liveStagePref = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
                stageSeg.setSelection(liveStagePref)
                subjSeg.setSelection(prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0))
                durSeg.setSelection(prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0))

                if (liveStagePref == 0) {
                    val stageTitle = when (curStage) {
                        1 -> "初级学园"
                        2 -> "中级学园"
                        3 -> "高级学园"
                        4 -> "进修学园"
                        else -> "高级学园"
                    }
                    studySubtitle.text = "已锁定 $stageTitle (已解锁最高学府)"
                } else {
                    studySubtitle.text = getSchoolStageDesc(liveStagePref)
                }
                statusAttributesText.text = "小宠资质 · 力量 ${schoolMap.power}  智力 ${schoolMap.intel}  魅力 ${schoolMap.charm}"
            }

            // 打工小镇全场所动态更新与解锁状态联动
            val workMap = preloaded.workPlaces
            if (workMap != null && workMap.code == 0 && workMap.stages.isNotEmpty()) {
                workPlaceOptions = buildWorkPlaceOptions(workMap)
                val curPref = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
                val matchedIdx = workPlaceOptions.indexOfFirst { it.careerId == curPref }.let { if (it >= 0) it else 0 }
                val segItems = workPlaceOptions.map {
                    SegmentItem(it.title, enabled = it.enabled, disabledTip = it.disabledTip)
                }
                workTypeSeg.rebuildItems(segItems, matchedIdx)
                val activeOption = workPlaceOptions.getOrNull(matchedIdx)
                workSubtitle.text = getWorkTypeDesc(activeOption?.careerId ?: 0, activeOption?.title)
            }

            // 打工岗位工时动态更新
            updateWorkDurSeg(workDurSeg, preloaded.workJobs)
            workDurSeg?.setSelection(prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0))
        }

        applyUnlockStates(
            PetAdventureEngine.PreloadedPetData(
                PetAdventureEngine.cachedSchoolDetails,
                PetAdventureEngine.cachedSchoolCourses,
                PetAdventureEngine.cachedWorkPlaces,
                PetAdventureEngine.cachedWorkJobs
            )
        )

        val activeEngine = engine ?: HookEntry.globalEngine
        if (activeEngine != null) {
            CoroutineScope(Dispatchers.IO).launch {
                activeEngine.verifyAndSyncAccountSession(context)
                val petId = PetAdventureEngine.cachedPetId ?: activeEngine.queryOwnPetAwait().second?.also {
                    PetAdventureEngine.saveScopedPetId(context, it)
                }
                if (!petId.isNullOrEmpty()) {
                    val preloaded = activeEngine.preloadAccountDataAwait(petId)
                    mainHandler.post {
                        applyUnlockStates(preloaded)
                    }
                    if (PetAdventureEngine.loadCachedHireableFriends(context).isEmpty()) {
                        activeEngine.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = false)
                        mainHandler.post {
                            hireWhitelistSummaryTv.text = formatHireWhitelistSummary(context)
                        }
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

    private fun formatHireWhitelistSummary(context: Context): String {
        val selectedUins = PetAdventureEngine.loadSavedHireFriendUins(context)
        if (selectedUins.isEmpty()) {
            return "当前未勾选好友 (未选择的好友不会雇佣 · 点击搜索勾选)"
        }
        val cachedFriends = PetAdventureEngine.loadCachedHireableFriends(context)
        val matchedNames = selectedUins.mapNotNull { uin ->
            val f = cachedFriends.find { it.uin == uin }
            if (f != null && f.friendNick.isNotBlank()) f.friendNick else uin.toString()
        }
        val preview = matchedNames.take(3).joinToString("、")
        val more = if (matchedNames.size > 3) " 等" else ""
        return "已勾选 ${selectedUins.size} 位好友 ($preview$more) · 优先空闲最高收益"
    }

    @SuppressLint("SetTextI18n")
    private fun showHireFriendWhitelistDialog(
        context: Context,
        colors: ThemeColors,
        prefs: android.content.SharedPreferences,
        engine: PetAdventureEngine?,
        onUpdated: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val selectedUins = LinkedHashSet<Long>(PetAdventureEngine.loadSavedHireFriendUins(context))
        val allFriends = mutableListOf<QQPetDirectBridge.HireableFriend>().apply {
            addAll(PetAdventureEngine.loadCachedHireableFriends(context))
        }
        var searchQuery = ""
        var isRefreshing = false

        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#1C1C1E") else Color.WHITE)
                cornerRadius = dp(context, 16).toFloat()
                if (colors.isNight) {
                    setStroke(1, colors.cardBorder)
                }
            }
            setPadding(dp(context, 16), dp(context, 18), dp(context, 16), dp(context, 14))
        }

        val titleTv = TextView(context).apply {
            text = "选择雇佣好友白名单"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.primaryText)
        }
        val subtitleTv = TextView(context).apply {
            text = "未勾选的好友不会雇佣；已勾选好友中默认优先雇佣空闲且总资质最高者。"
            textSize = 12.5f
            setTextColor(colors.secondaryText)
            setPadding(0, dp(context, 4), 0, dp(context, 12))
        }
        rootCard.addView(titleTv)
        rootCard.addView(subtitleTv)

        // 搜索栏 (支持按好友名字、宠物名字或 QQ 号实时过滤)
        val searchBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7"))
                cornerRadius = dp(context, 10).toFloat()
            }
            setPadding(dp(context, 12), dp(context, 6), dp(context, 10), dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 10))
            }
        }

        val clearSearchBtn = TextView(context).apply {
            text = "清空"
            textSize = 12.5f
            setTextColor(colors.actionBlueText)
            setPadding(dp(context, 8), dp(context, 4), dp(context, 4), dp(context, 4))
            visibility = View.GONE
        }

        val searchInput = EditText(context).apply {
            hint = "输入好友名字、宠物名或 QQ 号搜索..."
            textSize = 13.5f
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, dp(context, 4), 0, dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        clearSearchBtn.setOnClickListener {
            searchInput.setText("")
        }
        searchBar.addView(searchInput)
        searchBar.addView(clearSearchBtn)
        rootCard.addView(searchBar)

        // 状态与操作行
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 8))
        }
        val statusInfoTv = TextView(context).apply {
            text = "已勾选 ${selectedUins.size} 人 · 共 ${allFriends.size} 位养宠好友"
            textSize = 12f
            setTextColor(colors.secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val clearSelectedBtn = TextView(context).apply {
            text = "全不选"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionRedText)
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        }
        val refreshBtn = TextView(context).apply {
            text = "刷新好友与资质"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colors.actionBlueText)
            setPadding(dp(context, 8), dp(context, 4), dp(context, 2), dp(context, 4))
        }
        statusRow.addView(statusInfoTv)
        statusRow.addView(clearSelectedBtn)
        statusRow.addView(refreshBtn)
        rootCard.addView(statusRow)

        rootCard.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // 好友滚动列表容器
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val listScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.44f).toInt()
            )
            addView(listContainer)
        }
        rootCard.addView(listScrollView)

        fun persistSelection() {
            PetAdventureEngine.saveHireFriendUins(context, selectedUins)
            syncConfig(prefs, engine, context)
            onUpdated()
        }

        fun renderList() {
            listContainer.removeAllViews()
            val q = searchQuery.trim()
            val filtered = if (q.isEmpty()) {
                allFriends.toList()
            } else {
                allFriends.filter { f ->
                    f.friendNick.contains(q, ignoreCase = true) ||
                        f.petNick.contains(q, ignoreCase = true) ||
                        f.uin.toString().contains(q)
                }
            }

            statusInfoTv.text = if (isRefreshing) {
                "正在同步好友列表与实测资质..."
            } else if (q.isNotEmpty()) {
                "搜索到 ${filtered.size} 人 · 已勾选 ${selectedUins.size} 人"
            } else {
                "已勾选 ${selectedUins.size} 人 · 共 ${allFriends.size} 位养宠好友"
            }

            if (filtered.isEmpty()) {
                val emptyTv = TextView(context).apply {
                    text = if (isRefreshing) {
                        "正在从 QQ 宠物服务端拉取养宠好友列表，请稍候..."
                    } else if (q.isNotEmpty()) {
                        "未找到匹配「$q」的养宠好友，可点击右上角「刷新好友与资质」重试"
                    } else {
                        "暂未加载到养宠好友数据，请点击右上角「刷新好友与资质」"
                    }
                    textSize = 13f
                    setTextColor(colors.secondaryText)
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 16), dp(context, 36), dp(context, 16), dp(context, 36))
                }
                listContainer.addView(emptyTv)
                return
            }

            filtered.forEachIndexed { idx, friend ->
                val isChecked = selectedUins.contains(friend.uin)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 4), dp(context, 11), dp(context, 4), dp(context, 11))
                }

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                        setMargins(0, 0, dp(context, 10), 0)
                    }
                }

                val displayName = friend.friendNick.ifEmpty { "QQ好友" }
                val nameTv = TextView(context).apply {
                    text = "$displayName (${friend.uin})"
                    textSize = 14.5f
                    typeface = Typeface.create("sans-serif-medium", if (isChecked) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(colors.primaryText)
                }

                val petPart = "小宠: ${friend.petNick.ifEmpty { "未知" }}"
                val attrPart = if (friend.totalAttr > 0L) {
                    " · 总资质 ${friend.totalAttr} (力${friend.power}/智${friend.intel}/魅${friend.charm})"
                } else {
                    " · 勾选或刷新探测资质"
                }
                val idlePart = if (friend.totalAttr > 0L || !friend.isIdle) {
                    if (friend.isIdle) " · 空闲" else " · 忙碌中"
                } else ""
                val detailTv = TextView(context).apply {
                    text = "$petPart$attrPart$idlePart"
                    textSize = 12f
                    setTextColor(colors.secondaryText)
                    setPadding(0, dp(context, 2), 0, 0)
                }
                textCol.addView(nameTv)
                textCol.addView(detailTv)

                val checkBadge = TextView(context).apply {
                    text = if (isChecked) "✓ 已选" else "未选"
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(
                        if (isChecked) Color.WHITE
                        else colors.secondaryText
                    )
                    setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
                    background = GradientDrawable().apply {
                        setColor(
                            if (isChecked) {
                                if (colors.isNight) Color.parseColor("#30D158") else Color.parseColor("#34C759")
                            } else {
                                if (colors.isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#EBEBED")
                            }
                        )
                        cornerRadius = dp(context, 8).toFloat()
                    }
                }

                row.addView(textCol)
                row.addView(checkBadge)
                row.setOnClickListener {
                    val nowSelected = if (selectedUins.contains(friend.uin)) {
                        selectedUins.remove(friend.uin)
                        false
                    } else {
                        selectedUins.add(friend.uin)
                        true
                    }
                    persistSelection()
                    renderList()

                    if (nowSelected && friend.totalAttr <= 0L) {
                        val active = engine ?: HookEntry.globalEngine
                        if (active != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val enriched = active.enrichFriendDetailsAwait(friend)
                                val i = allFriends.indexOfFirst { it.uin == friend.uin }
                                if (i >= 0) {
                                    allFriends[i] = enriched
                                    PetAdventureEngine.saveCachedHireableFriends(context, allFriends)
                                }
                                mainHandler.post {
                                    renderList()
                                }
                            }
                        }
                    }
                }

                listContainer.addView(row)
                if (idx < filtered.size - 1) {
                    listContainer.addView(View(context).apply {
                        setBackgroundColor(colors.dividerColor)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                }
            }
        }

        fun triggerRefreshFriends() {
            val active = engine ?: HookEntry.globalEngine
            if (active == null) {
                Toast.makeText(context, "引擎尚未就绪，请稍候再试", Toast.LENGTH_SHORT).show()
                return
            }
            if (isRefreshing) return
            isRefreshing = true
            renderList()
            CoroutineScope(Dispatchers.IO).launch {
                val fetched = active.fetchAllHireableFriendsAwait(context, enrichSelectedAndTop = true)
                mainHandler.post {
                    isRefreshing = false
                    allFriends.clear()
                    allFriends.addAll(fetched)
                    renderList()
                    onUpdated()
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                clearSearchBtn.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        clearSelectedBtn.setOnClickListener {
            if (selectedUins.isNotEmpty()) {
                selectedUins.clear()
                persistSelection()
                renderList()
            }
        }

        refreshBtn.setOnClickListener {
            triggerRefreshFriends()
        }

        rootCard.addView(View(context).apply {
            setBackgroundColor(colors.dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(context, 6), 0, dp(context, 10))
            }
        })

        val doneBtn = TextView(context).apply {
            text = "完成并保存"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 11), 0, dp(context, 11))
            background = GradientDrawable().apply {
                setColor(colors.actionBlueText)
                cornerRadius = dp(context, 10).toFloat()
            }
            applyTouchSpringEffect(this)
            setOnClickListener {
                persistSelection()
                dialog.dismiss()
            }
        }
        rootCard.addView(doneBtn)

        renderList()
        if (allFriends.isEmpty()) {
            triggerRefreshFriends()
        }

        dialog.setContentView(rootCard)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (context.resources.displayMetrics.widthPixels * 0.90f).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
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

    private fun getWorkTypeDesc(careerId: Int, placeTitle: String? = null): String {
        if (careerId <= 0) {
            val starTower = PetAdventureEngine.cachedWorkPlaces?.stages?.find { it.stage == 3 }
            return if (starTower != null && starTower.limitStatus == 0) {
                "智能推荐 · 优先${starTower.title}(最高收益)"
            } else {
                "智能推荐 · 优先最高收益已解锁场所"
            }
        }
        val cleanName = (placeTitle ?: when (careerId) {
            1 -> "彩虹画室"
            2 -> "迷雾侦探所"
            3 -> "星尘魔法塔"
            4 -> "咕噜厨房"
            6 -> "云朵梦舍"
            8 -> "风铃旅社"
            else -> "职业场所#$careerId"
        }).replace("(锁)", "").trim()
        return "$cleanName · 专属场所打工派遣"
    }

    private fun buildWorkPlaceOptions(workDetails: QQPetDirectBridge.SecondMapDetails?): List<WorkPlaceOption> {
        val list = mutableListOf<WorkPlaceOption>()
        list.add(WorkPlaceOption(0, "智能推荐", enabled = true))
        if (workDetails != null && workDetails.code == 0 && workDetails.stages.isNotEmpty()) {
            for (s in workDetails.stages) {
                val isLocked = (s.limitStatus != 0)
                val rawTitle = s.title.trim()
                val realTitle = if (rawTitle.isNotEmpty() && rawTitle != "???") rawTitle else "隐藏职业"
                val displayTitle = if (isLocked) "$realTitle(锁)" else realTitle
                val tip = if (isLocked) (if (s.lockReason.isNotEmpty()) s.lockReason else "还没有解锁这个职业") else null
                list.add(WorkPlaceOption(s.stage, displayTitle, enabled = !isLocked, disabledTip = tip))
            }
        } else {
            list.add(WorkPlaceOption(1, "彩虹画室", enabled = true))
            list.add(WorkPlaceOption(2, "迷雾侦探所", enabled = true))
            list.add(WorkPlaceOption(3, "星尘魔法塔", enabled = true))
            list.add(WorkPlaceOption(4, "咕噜厨房", enabled = true))
            list.add(WorkPlaceOption(5, "隐藏工坊(锁)", enabled = false, disabledTip = "还没有解锁这个职业"))
            list.add(WorkPlaceOption(6, "云朵梦舍", enabled = true))
            list.add(WorkPlaceOption(7, "隐藏工坊(锁)", enabled = false, disabledTip = "还没有解锁这个职业"))
            list.add(WorkPlaceOption(8, "风铃旅社", enabled = true))
        }
        return list
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
        val likeBack = prefs.getBoolean(PreferencesHelper.KEY_LIKE_BACK, true)
        val claimCoinBag = prefs.getBoolean(PreferencesHelper.KEY_CLAIM_COINBAG, true)
        val fatigueToAdv = prefs.getBoolean(PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE, true)
        val studyMode = prefs.getInt("key_study_mode", 0)
        val workMode = prefs.getInt("key_work_mode", 0)
        val schoolStage = prefs.getInt(PreferencesHelper.KEY_SCHOOL_STAGE, 0)
        val courseSubject = prefs.getInt(PreferencesHelper.KEY_COURSE_SUBJECT, 0)
        val courseDuration = prefs.getInt(PreferencesHelper.KEY_COURSE_DURATION, 0)
       val workType = prefs.getInt(PreferencesHelper.KEY_WORK_TYPE, 0)
       val workDuration = prefs.getInt(PreferencesHelper.KEY_WORK_DURATION, 0)
       val careEnergy = prefs.getInt(PreferencesHelper.KEY_CARE_ENERGY_THRESHOLD, 60)
       val careClean = prefs.getInt(PreferencesHelper.KEY_CARE_CLEAN_THRESHOLD, 60)
       val humanLikeSleep = prefs.getBoolean(PreferencesHelper.KEY_HUMAN_LIKE_SLEEP, true)
       val hideQQSetting = prefs.getBoolean(PreferencesHelper.KEY_HIDE_QQ_SETTING_ENTRY, false)
      val debugLog = prefs.getBoolean(PreferencesHelper.KEY_DEBUG_LOG, false)
      val hireFriend = prefs.getBoolean(PreferencesHelper.KEY_HIRE_FRIEND_ENABLED, true)
      val hireUinsCsv = PetAdventureEngine.loadSavedHireFriendUins(context).joinToString(",")
      val friendCareEnabled = prefs.getBoolean(PreferencesHelper.KEY_FRIEND_CARE_ENABLED, false)
      val friendCareEnergy = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_ENERGY_THRESHOLD, 60)
      val friendCareClean = prefs.getInt(PreferencesHelper.KEY_FRIEND_CARE_CLEAN_THRESHOLD, 60)

      HookEntry.globalEngine?.updateConfig(study, work, care, adv, settle, likeBack, claimCoinBag, fatigueToAdv, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration, careEnergy, careClean, humanLikeSleep, hideQQSetting, debugLog, hireFriend, hireUinsCsv, friendCareEnabled, friendCareEnergy, friendCareClean)
      if (engine != null && engine !== HookEntry.globalEngine) {
          engine.updateConfig(study, work, care, adv, settle, likeBack, claimCoinBag, fatigueToAdv, studyMode, workMode, schoolStage, courseSubject, courseDuration, workType, workDuration, careEnergy, careClean, humanLikeSleep, hideQQSetting, debugLog, hireFriend, hireUinsCsv, friendCareEnabled, friendCareEnergy, friendCareClean)
      }
      val intent = Intent(HookEntry.ACTION_UPDATE_CONFIG).apply {
           setPackage("com.tencent.mobileqq")
           putExtra("extra_study", study)
           putExtra("extra_work", work)
           putExtra("extra_care", care)
           putExtra("extra_adventure", adv)
           putExtra("extra_settle", settle)
           putExtra("extra_like_back", likeBack)
           putExtra("extra_claim_coinbag", claimCoinBag)
           putExtra("extra_fatigue_to_adventure", fatigueToAdv)
           putExtra("extra_study_mode", studyMode)
           putExtra("extra_work_mode", workMode)
           putExtra("extra_school_stage", schoolStage)
           putExtra("extra_course_subject", courseSubject)
           putExtra("extra_course_duration", courseDuration)
           putExtra("extra_work_type", workType)
           putExtra("extra_work_duration", workDuration)
           putExtra("extra_care_energy_threshold", careEnergy)
           putExtra("extra_care_clean_threshold", careClean)
           putExtra("extra_human_like_sleep", humanLikeSleep)
           putExtra("extra_hide_qq_setting_entry", hideQQSetting)
          putExtra("extra_debug_log", debugLog)
          putExtra("extra_hire_friend_enabled", hireFriend)
          putExtra("extra_hire_friend_uins", hireUinsCsv)
          putExtra("extra_friend_care_enabled", friendCareEnabled)
          putExtra("extra_friend_care_energy_threshold", friendCareEnergy)
          putExtra("extra_friend_care_clean_threshold", friendCareClean)
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
