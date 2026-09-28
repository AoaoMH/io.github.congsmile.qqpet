package com.copilot.qqpet.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.copilot.qqpet.HookEntry
import com.copilot.qqpet.ui.QQSettingDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object QQSettingInjector {

    private const val TAG = "QQSettingInjector"
    @Volatile
    private var isHooked = false

    fun inject(classLoader: ClassLoader) {
        if (isHooked) return

        val providerClassNames = listOf(
            "com.tencent.mobileqq.setting.main.b",
            "com.tencent.mobileqq.setting.main.MainSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.NewSettingConfigProvider"
        )

        for (className in providerClassNames) {
            val providerCls = try {
                Class.forName(className, false, classLoader)
            } catch (t: Throwable) {
                continue
            }

            val getListMethod = providerCls.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 &&
                        Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        List::class.java.isAssignableFrom(m.returnType)
            } ?: continue

            XposedBridge.hookMethod(getListMethod, object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                @SuppressLint("DiscouragedApi")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return
                    val groupList = param.result as? MutableList<Any> ?: return
                    if (groupList.isEmpty()) return

                    try {
                        // 使用通用条目处理器 com.tencent.mobileqq.setting.processor.i
                        val itemCls = Class.forName("com.tencent.mobileqq.setting.processor.i", false, classLoader)
                        XposedBridge.log("[$TAG] 目标处理器 com.tencent.mobileqq.setting.processor.i:")
                        XposedBridge.log("[$TAG]   构造函数: ${itemCls.constructors.map { c -> c.parameterTypes.map { it.simpleName } }}")
                        XposedBridge.log("[$TAG]   字段: ${itemCls.declaredFields.map { "${it.name}:${it.type.simpleName}" }}")
                        XposedBridge.log("[$TAG]   方法: ${itemCls.declaredMethods.map { "${it.name}(${it.parameterTypes.map { p -> p.simpleName }})->${it.returnType.simpleName}" }}")

                        val sampleGroup = groupList.first()
                        val groupCls = sampleGroup.javaClass

                        // 查找通用图标
                        var iconRes = ctx.resources.getIdentifier("qui_tuning", "drawable", ctx.packageName)
                        if (iconRes == 0) {
                            iconRes = ctx.resources.getIdentifier("qq_setting_me_icon", "drawable", ctx.packageName)
                        }

                        // 尝试实例化 com.tencent.mobileqq.setting.processor.i
                        var newItem: Any? = null
                        for (c in itemCls.constructors) {
                            c.isAccessible = true
                            try {
                                val pTypes = c.parameterTypes
                                val args = arrayOfNulls<Any>(pTypes.size)
                                for (i in pTypes.indices) {
                                    val pt = pTypes[i]
                                    args[i] = when {
                                        Context::class.java.isAssignableFrom(pt) -> ctx
                                        pt == Integer.TYPE -> if (i == 1) 999520 else iconRes
                                        CharSequence::class.java.isAssignableFrom(pt) -> "Q宠后台伴侣"
                                        pt == java.lang.String::class.java -> "纯后台免打开全自动调度"
                                        pt == java.lang.Boolean.TYPE -> true
                                        else -> null
                                    }
                                }
                                newItem = c.newInstance(*args)
                                XposedBridge.log("[$TAG] 成功创建 processor.i 实例 (args count=${pTypes.size})！")
                                break
                            } catch (e: Throwable) {
                                XposedBridge.log("[$TAG] 构造失败: ${e.message}")
                            }
                        }

                        if (newItem == null) return

                        // 给标题字段 g 赋值 "Q宠后台伴侣"
                        for (f in itemCls.declaredFields) {
                            f.isAccessible = true
                            if (f.name == "g" && (CharSequence::class.java.isAssignableFrom(f.type) || f.type == String::class.java)) {
                                f.set(newItem, "Q宠后台伴侣")
                                XposedBridge.log("[$TAG] 赋值字段 g = Q宠后台伴侣")
                            }
                            if (f.name == "h" && (CharSequence::class.java.isAssignableFrom(f.type) || f.type == String::class.java)) {
                                f.set(newItem, "纯后台全自动调度")
                                XposedBridge.log("[$TAG] 赋值字段 h = 纯后台全自动调度")
                            }
                        }

                        // 绑定点击回调 (查找接收 Function0 或 OnClickListener 的方法)
                        val clickListenerMethod = itemCls.declaredMethods.firstOrNull { m ->
                            m.parameterTypes.size == 1 && (
                                    m.parameterTypes[0].name.contains("Function0") ||
                                    m.parameterTypes[0].name.contains("OnClickListener")
                            )
                        }

                        if (clickListenerMethod != null) {
                            val paramType = clickListenerMethod.parameterTypes[0]
                            val clickProxy = Proxy.newProxyInstance(
                                classLoader,
                                arrayOf(paramType)
                            ) { _, method, _ ->
                                if (method.name == "invoke" || method.name == "onClick") {
                                    onSettingEntryClick(ctx)
                                    val unitCls = classLoader.loadClass("kotlin.Unit")
                                    return@newProxyInstance unitCls.getField("INSTANCE").get(null)
                                }
                                null
                            }
                            clickListenerMethod.invoke(newItem, clickProxy)
                            XposedBridge.log("[$TAG] 成功绑定点击代理！")
                        }

                        // 构造 SettingGroup 包装 newItem
                        for (c in groupCls.constructors) {
                            c.isAccessible = true
                            try {
                                val pTypes = c.parameterTypes
                                if (pTypes.isNotEmpty() && List::class.java.isAssignableFrom(pTypes[0])) {
                                    val singleItemList = listOf(newItem)
                                    val args = arrayOfNulls<Any>(pTypes.size)
                                    args[0] = singleItemList
                                    for (i in 1 until pTypes.size) {
                                        val pt = pTypes[i]
                                        args[i] = when {
                                            CharSequence::class.java.isAssignableFrom(pt) -> ""
                                            pt == Integer.TYPE -> 6
                                            else -> null
                                        }
                                    }
                                    val newGroup = c.newInstance(*args)
                                    // 插入在第 2 个位置（紧随账号安全等关键项之后）
                                    groupList.add(2, newGroup)
                                    XposedBridge.log("[$TAG] 🎯 完美插入「Q宠后台伴侣」专属卡片！")
                                    break
                                }
                            } catch (_: Throwable) {}
                        }

                    } catch (t: Throwable) {
                        XposedBridge.log("[$TAG] 挂载异常: ${Log.getStackTraceString(t)}")
                    }
                }
            })

            isHooked = true
            break
        }
    }

    private fun onSettingEntryClick(context: Context) {
        XposedBridge.log("[$TAG] ⚡ 用户在 QQ 设置中点击了「Q宠后台伴侣」！")
        try {
            HookEntry.globalEngine?.startBackgroundLoop(context.applicationContext)
            if (context is Activity) {
                context.runOnUiThread {
                    QQSettingDialog.show(context, HookEntry.globalEngine)
                }
            } else {
                val intent = Intent().apply {
                    setClassName("com.copilot.qqpet", "com.copilot.qqpet.ui.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 调起伴侣控制弹窗失败: ${t.message}")
        }
    }
}
