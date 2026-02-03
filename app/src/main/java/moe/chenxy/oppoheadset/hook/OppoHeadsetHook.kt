package moe.chenxy.oppoheadset.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.RemoteViews
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * OPPO 耳机 Hook 模块 - 用于 Hook 欢律 App
 *
 * 功能:
 * 1. Hook KeepAliveFgService.d 方法获取电量和 MAC 地址
 * 2. 接收 SystemUI 控制指令，反射调用 AbstractC0772b 切换降噪模式
 * 3. 通过广播向 SystemUI 发送电量信息
 */
class OppoHeadsetHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "OppoHeadsetHook"

        // 保存当前连接的 MAC 地址
        @Volatile
        var currentMacAddress: String = ""

        // 控制中心单例实例
        @Volatile
        var controlCenterInstance: Any? = null

        // 应用 Context
        @Volatile
        var appContext: Context? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_HEYTAP) {
            return
        }

        Log.i(TAG, "Hooking ${Constants.PKG_NAME_HEYTAP}")

        // Hook Application.onCreate 以获取 Context 并注册广播接收器
        hookApplicationOnCreate(lpparam)

        // Hook KeepAliveFgService.d 方法获取电量数据
        hookKeepAliveFgService(lpparam)

        // 尝试获取控制中心单例
        hookControlCenterSingleton(lpparam)
    }

    /**
     * Hook Application.onCreate 以获取 Context
     */
    private fun hookApplicationOnCreate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Context
                        appContext = app
                        Log.i(TAG, "Application context obtained")

                        // 注册广播接收器监听 SystemUI 的控制指令
                        registerControlReceiver(app)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Application.onCreate: ${e.message}")
        }
    }

    /**
     * 注册广播接收器，接收 SystemUI 发来的控制指令
     */
    private fun registerControlReceiver(context: Context) {
        val handlerThread = HandlerThread("oppo_headset_receiver").apply { start() }
        val handler = Handler(handlerThread.looper)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Constants.Action.OPPO_ACTION_SWITCH_MODE) {
                    val mode = intent.getIntExtra("mode", Constants.AncMode.OFF)
                    Log.i(TAG, "Received control command: switch mode to $mode")

                    // 执行降噪模式切换
                    switchAncMode(mode)
                }
            }
        }

        val filter = IntentFilter(Constants.Action.OPPO_ACTION_SWITCH_MODE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }

        Log.i(TAG, "Control receiver registered")
    }

    /**
     * Hook KeepAliveFgService.d 方法获取电量和 MAC 地址
     */
    private fun hookKeepAliveFgService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val serviceClass = XposedHelpers.findClass(
                Constants.HookTarget.KEEP_ALIVE_SERVICE,
                lpparam.classLoader
            )

            // 由于数据模型类是混淆的，使用 Object.class 作为参数类型
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "d",
                RemoteViews::class.java,
                Object::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dataModel = param.args[1] ?: return

                        try {
                            // 通过反射获取数据
                            val batteryData = extractBatteryData(dataModel)

                            if (batteryData != null) {
                                // 发送广播给 SystemUI
                                sendBatteryUpdateBroadcast(batteryData)
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error extracting battery data: ${e.message}")
                        }
                    }
                }
            )

            Log.i(TAG, "KeepAliveFgService.d hooked successfully")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook KeepAliveFgService.d: ${e.message}")
            // 尝试其他方式 Hook（处理方法签名可能不同的情况）
            tryAlternativeHook(lpparam)
        }
    }

    /**
     * 备用 Hook 方案 - 遍历所有名为 d 的方法
     */
    private fun tryAlternativeHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val serviceClass = XposedHelpers.findClass(
                Constants.HookTarget.KEEP_ALIVE_SERVICE,
                lpparam.classLoader
            )

            // 遍历所有方法，找到参数为 RemoteViews 和 Object 的方法
            for (method in serviceClass.declaredMethods) {
                if (method.name == "d" && method.parameterTypes.size == 2) {
                    val firstParam = method.parameterTypes[0]
                    if (firstParam == RemoteViews::class.java ||
                        firstParam.name.contains("RemoteViews")) {

                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val dataModel = param.args[1] ?: return

                                try {
                                    val batteryData = extractBatteryData(dataModel)
                                    if (batteryData != null) {
                                        sendBatteryUpdateBroadcast(batteryData)
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG: Error in alternative hook: ${e.message}")
                                }
                            }
                        })

                        Log.i(TAG, "Alternative hook applied to method: ${method.name}")
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Alternative hook also failed: ${e.message}")
        }
    }

    /**
     * 从数据模型中提取电量数据
     */
    private fun extractBatteryData(dataModel: Any): BatteryData? {
        return try {
            val clazz = dataModel.javaClass

            // 获取 MAC 地址
            val macAddress = tryInvokeMethod(dataModel, "getAddress") as? String ?: ""
            if (macAddress.isNotEmpty()) {
                currentMacAddress = macAddress
            }

            // 判断连接类型
            val isSpp = tryInvokeMethod(dataModel, "isSpp") as? Boolean ?: false

            // 获取电量 - 尝试多种方法名
            val leftBattery = tryGetBattery(dataModel, "getLeftBattery", "getHeadsetLeftBattery")
            val rightBattery = tryGetBattery(dataModel, "getRightBattery", "getHeadsetRightBattery")
            val boxBattery = tryGetBattery(dataModel, "getBoxBattery", "getHeadsetBoxBattery")

            Log.d(TAG, "Battery data: left=$leftBattery, right=$rightBattery, box=$boxBattery, mac=$macAddress")

            BatteryData(
                leftBattery = leftBattery,
                rightBattery = rightBattery,
                boxBattery = boxBattery,
                macAddress = currentMacAddress,
                isSpp = isSpp
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: extractBatteryData error: ${e.message}")
            null
        }
    }

    /**
     * 尝试调用方法获取电量值
     */
    private fun tryGetBattery(obj: Any, vararg methodNames: String): Int {
        for (name in methodNames) {
            val result = tryInvokeMethod(obj, name)
            if (result is Number) {
                return result.toInt()
            }
        }
        return -1
    }

    /**
     * 安全地调用反射方法
     */
    private fun tryInvokeMethod(obj: Any, methodName: String): Any? {
        return try {
            val method = obj.javaClass.getMethod(methodName)
            method.isAccessible = true
            method.invoke(obj)
        } catch (e: NoSuchMethodException) {
            // 尝试 getDeclaredMethod
            try {
                val method = obj.javaClass.getDeclaredMethod(methodName)
                method.isAccessible = true
                method.invoke(obj)
            } catch (e2: Throwable) {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 发送电量更新广播给 SystemUI
     */
    private fun sendBatteryUpdateBroadcast(data: BatteryData) {
        val context = appContext ?: return

        Intent(Constants.Action.OPPO_BATTERY_UPDATE).apply {
            putExtra("left", data.leftBattery)
            putExtra("right", data.rightBattery)
            putExtra("box", data.boxBattery)
            putExtra("mac", data.macAddress)
            putExtra("isSpp", data.isSpp)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            // 发送给 SystemUI
            `package` = Constants.PKG_NAME_SYSTEMUI
            context.sendBroadcast(this)
        }

        Log.d(TAG, "Battery broadcast sent: $data")
    }

    /**
     * Hook 控制中心单例类
     */
    private fun hookControlCenterSingleton(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val singletonClass = XposedHelpers.findClass(
                Constants.HookTarget.CONTROL_CENTER_SINGLETON,
                lpparam.classLoader
            )

            // Hook H() 方法获取单例实例
            XposedHelpers.findAndHookMethod(
                singletonClass,
                "H",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        controlCenterInstance = param.result
                        Log.d(TAG, "Control center instance obtained")
                    }
                }
            )

            Log.i(TAG, "Control center singleton hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook control center singleton: ${e.message}")
            // 尝试查找实际类名（可能是混淆后的类）
            tryFindControlCenterClass(lpparam)
        }
    }

    /**
     * 尝试查找控制中心类（处理混淆情况）
     */
    private fun tryFindControlCenterClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 尝试常见的混淆模式
            val possibleClassNames = listOf(
                "com.oplus.melody.model.repository.earphone.AbstractC0772b",
                "l4.b",  // 可能的混淆名
                "com.oplus.melody.model.repository.earphone.b"
            )

            for (className in possibleClassNames) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                    // 查找获取单例的静态方法
                    for (method in clazz.declaredMethods) {
                        if (java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == clazz) {

                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    controlCenterInstance = param.result
                                    Log.d(TAG, "Control center instance found via alternative method")
                                }
                            })

                            Log.i(TAG, "Found control center class: $className")
                            return
                        }
                    }
                } catch (e: ClassNotFoundException) {
                    continue
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: tryFindControlCenterClass error: ${e.message}")
        }
    }

    /**
     * 切换降噪模式
     */
    private fun switchAncMode(mode: Int) {
        val instance = controlCenterInstance
        if (instance == null) {
            Log.w(TAG, "Control center instance is null, trying to get it")
            // 尝试重新获取单例
            tryGetControlCenterInstance()
            return
        }

        if (currentMacAddress.isEmpty()) {
            Log.w(TAG, "MAC address is empty, cannot switch mode")
            return
        }

        try {
            // 调用 n0(int mode, String macAddress) 方法
            val clazz = instance.javaClass

            // 尝试找到 n0 方法
            val method = try {
                clazz.getMethod("n0", Int::class.javaPrimitiveType, String::class.java)
            } catch (e: NoSuchMethodException) {
                // 尝试其他可能的方法名
                clazz.declaredMethods.find { m ->
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == String::class.java
                }
            }

            if (method != null) {
                method.isAccessible = true
                method.invoke(instance, mode, currentMacAddress)
                Log.i(TAG, "ANC mode switched to $mode for device $currentMacAddress")
            } else {
                XposedBridge.log("$TAG: Cannot find method to switch ANC mode")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error switching ANC mode: ${e.message}")
        }
    }

    /**
     * 尝试获取控制中心单例实例
     */
    private fun tryGetControlCenterInstance() {
        try {
            val context = appContext ?: return
            val classLoader = context.classLoader

            val possibleClassNames = listOf(
                Constants.HookTarget.CONTROL_CENTER_SINGLETON,
                "l4.b"
            )

            for (className in possibleClassNames) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)

                    // 尝试调用 H() 方法
                    try {
                        val hMethod = clazz.getMethod("H")
                        controlCenterInstance = hMethod.invoke(null)
                        if (controlCenterInstance != null) {
                            Log.i(TAG, "Got control center instance via H()")
                            return
                        }
                    } catch (e: NoSuchMethodException) {
                        // 查找其他可能的单例获取方法
                        for (method in clazz.declaredMethods) {
                            if (java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                                method.parameterTypes.isEmpty() &&
                                method.returnType == clazz) {

                                method.isAccessible = true
                                controlCenterInstance = method.invoke(null)
                                if (controlCenterInstance != null) {
                                    Log.i(TAG, "Got control center instance via ${method.name}()")
                                    return
                                }
                            }
                        }
                    }
                } catch (e: ClassNotFoundException) {
                    continue
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: tryGetControlCenterInstance error: ${e.message}")
        }
    }

    /**
     * 电量数据类
     */
    data class BatteryData(
        val leftBattery: Int,
        val rightBattery: Int,
        val boxBattery: Int,
        val macAddress: String,
        val isSpp: Boolean
    )
}
