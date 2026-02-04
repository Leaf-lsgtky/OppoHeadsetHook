package moe.chenxy.oppoheadset.hook

import android.bluetooth.BluetoothDevice
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
 * 2. 接收 SystemUI 控制指令，反射调用控制中心切换降噪模式
 * 3. 通过广播向 SystemUI 和小米蓝牙发送电量信息
 */
class OppoHeadsetHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "OppoHeadsetHook"

        // 当前连接的设备信息
        @Volatile
        var currentMacAddress: String = ""

        @Volatile
        var currentDeviceName: String = ""

        @Volatile
        var currentDevice: BluetoothDevice? = null

        // 控制中心单例实例
        @Volatile
        var controlCenterInstance: Any? = null

        // 应用 Context
        @Volatile
        var appContext: Context? = null

        // 上次发送的电量（避免重复发送）
        private var lastLeftBattery = -1
        private var lastRightBattery = -1
        private var lastBoxBattery = -1
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_HEYTAP) {
            return
        }

        Log.i(TAG, "Hooking ${Constants.PKG_NAME_HEYTAP}")
        sendLog("开始 Hook 欢律 App")

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
            // 尝试 hook 欢律 App 的 Application 类
            val appClassNames = listOf(
                "com.heytap.headset.MelodyApplication",
                "com.oplus.melody.MelodyApplication",
                "com.heytap.headset.app.HeadsetApplication"
            )

            var hooked = false
            for (className in appClassNames) {
                try {
                    XposedHelpers.findAndHookMethod(
                        className,
                        lpparam.classLoader,
                        "onCreate",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val app = param.thisObject as Context
                                if (appContext == null) {
                                    appContext = app
                                    Log.i(TAG, "Application context obtained from $className")
                                    sendLog("获取到欢律 Context")
                                    registerControlReceiver(app)
                                }
                            }
                        }
                    )
                    hooked = true
                    Log.i(TAG, "Hooked $className.onCreate")
                    sendLog("成功 Hook $className")
                    break
                } catch (e: Throwable) {
                    continue
                }
            }

            // 如果特定类不存在，使用通用 Application hook
            if (!hooked) {
                XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
                    object : XC_MethodHook() {
                        private var contextObtained = false

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (contextObtained) return

                            val app = param.thisObject as Context
                            if (app.packageName == Constants.PKG_NAME_HEYTAP) {
                                appContext = app
                                contextObtained = true
                                Log.i(TAG, "Application context obtained (fallback)")
                                sendLog("获取到欢律 Context (fallback)")
                                registerControlReceiver(app)
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Application.onCreate: ${e.message}")
        }
    }

    /**
     * 注册广播接收器，接收控制指令
     */
    private fun registerControlReceiver(context: Context) {
        val handlerThread = HandlerThread("oppo_headset_receiver").apply { start() }
        val handler = Handler(handlerThread.looper)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Constants.Action.OPPO_ACTION_SWITCH_MODE -> {
                        val mode = intent.getIntExtra("mode", Constants.AncMode.OFF)
                        Log.i(TAG, "Received control command: switch mode to $mode")
                        sendLog("收到控制命令: 切换模式到 $mode")
                        switchAncMode(mode)
                    }
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
        sendLog("控制接收器已注册")
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

            // 遍历所有名为 d 的方法，找到处理电量的方法
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
                                        // 检查是否有变化，避免重复发送
                                        if (batteryData.leftBattery != lastLeftBattery ||
                                            batteryData.rightBattery != lastRightBattery ||
                                            batteryData.boxBattery != lastBoxBattery) {

                                            lastLeftBattery = batteryData.leftBattery
                                            lastRightBattery = batteryData.rightBattery
                                            lastBoxBattery = batteryData.boxBattery

                                            // 发送广播
                                            sendBatteryBroadcasts(batteryData)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG: Error extracting battery data: ${e.message}")
                                }
                            }
                        })

                        Log.i(TAG, "KeepAliveFgService.d hooked")
                        sendLog("成功 Hook KeepAliveFgService.d")
                        return
                    }
                }
            }

            // 如果没有找到合适的方法，尝试直接 hook
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "d",
                RemoteViews::class.java,
                Object::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dataModel = param.args[1] ?: return

                        try {
                            val batteryData = extractBatteryData(dataModel)
                            if (batteryData != null) {
                                if (batteryData.leftBattery != lastLeftBattery ||
                                    batteryData.rightBattery != lastRightBattery ||
                                    batteryData.boxBattery != lastBoxBattery) {

                                    lastLeftBattery = batteryData.leftBattery
                                    lastRightBattery = batteryData.rightBattery
                                    lastBoxBattery = batteryData.boxBattery

                                    sendBatteryBroadcasts(batteryData)
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in battery hook: ${e.message}")
                        }
                    }
                }
            )

            Log.i(TAG, "KeepAliveFgService.d hooked (direct)")
            sendLog("成功 Hook KeepAliveFgService.d (direct)")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook KeepAliveFgService.d: ${e.message}")
            sendLog("Hook KeepAliveFgService 失败: ${e.message}")
        }
    }

    /**
     * 从数据模型中提取电量数据
     */
    private fun extractBatteryData(dataModel: Any): BatteryData? {
        return try {
            // 获取 MAC 地址
            val macAddress = tryInvokeMethod(dataModel, "getAddress") as? String ?: ""
            if (macAddress.isNotEmpty()) {
                currentMacAddress = macAddress
            }

            // 获取设备名称
            val deviceName = tryInvokeMethod(dataModel, "getDeviceName") as? String
                ?: tryInvokeMethod(dataModel, "getName") as? String ?: ""
            if (deviceName.isNotEmpty()) {
                currentDeviceName = deviceName
            }

            // 判断连接类型
            val isSpp = tryInvokeMethod(dataModel, "isSpp") as? Boolean ?: false

            // 获取电量 - 尝试多种方法名
            val leftBattery = tryGetBattery(dataModel, "getLeftBattery", "getHeadsetLeftBattery", "getLeftPower")
            val rightBattery = tryGetBattery(dataModel, "getRightBattery", "getHeadsetRightBattery", "getRightPower")
            val boxBattery = tryGetBattery(dataModel, "getBoxBattery", "getHeadsetBoxBattery", "getCasePower")

            // 获取充电状态
            val leftCharging = tryInvokeMethod(dataModel, "isLeftCharging") as? Boolean ?: false
            val rightCharging = tryInvokeMethod(dataModel, "isRightCharging") as? Boolean ?: false
            val boxCharging = tryInvokeMethod(dataModel, "isBoxCharging") as? Boolean
                ?: tryInvokeMethod(dataModel, "isCaseCharging") as? Boolean ?: false

            Log.d(TAG, "Battery data: L=$leftBattery R=$rightBattery B=$boxBattery MAC=$currentMacAddress")

            BatteryData(
                leftBattery = leftBattery,
                rightBattery = rightBattery,
                boxBattery = boxBattery,
                macAddress = currentMacAddress,
                deviceName = currentDeviceName,
                isSpp = isSpp,
                leftCharging = leftCharging,
                rightCharging = rightCharging,
                boxCharging = boxCharging
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
     * 发送电量广播给 SystemUI 和小米蓝牙
     */
    private fun sendBatteryBroadcasts(data: BatteryData) {
        val context = appContext ?: return

        sendLog("发送电量广播: L=${data.leftBattery} R=${data.rightBattery} B=${data.boxBattery}")

        // 发送给 SystemUI
        Intent(Constants.Action.OPPO_BATTERY_UPDATE).apply {
            putExtra("left", data.leftBattery)
            putExtra("right", data.rightBattery)
            putExtra("box", data.boxBattery)
            putExtra("mac", data.macAddress)
            putExtra("name", data.deviceName)
            putExtra("isSpp", data.isSpp)
            putExtra("leftCharging", data.leftCharging)
            putExtra("rightCharging", data.rightCharging)
            putExtra("boxCharging", data.boxCharging)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }

        // 发送给小米蓝牙显示通知
        Intent(Constants.Action.OPPO_UPDATE_NOTIFICATION).apply {
            putExtra("left", data.leftBattery)
            putExtra("right", data.rightBattery)
            putExtra("box", data.boxBattery)
            putExtra("leftCharging", data.leftCharging)
            putExtra("rightCharging", data.rightCharging)
            putExtra("boxCharging", data.boxCharging)
            putExtra("name", data.deviceName)
            // 需要传递 BluetoothDevice，但我们可能没有直接的引用
            // 使用 MAC 地址代替
            putExtra("mac", data.macAddress)
            `package` = Constants.PKG_NAME_MI_BLUETOOTH
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }

        // 显示 Strong Toast（首次连接时）
        if (lastLeftBattery == -1 && lastRightBattery == -1) {
            Intent(Constants.Action.OPPO_SHOW_STRONG_TOAST).apply {
                putExtra("left", data.leftBattery)
                putExtra("right", data.rightBattery)
                putExtra("box", data.boxBattery)
                putExtra("name", data.deviceName)
                `package` = Constants.PKG_NAME_MI_BLUETOOTH
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }

        Log.d(TAG, "Battery broadcasts sent")
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
            sendLog("成功 Hook 控制中心单例")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook control center singleton: ${e.message}")
            tryFindControlCenterClass(lpparam)
        }
    }

    /**
     * 尝试查找控制中心类（处理混淆情况）
     */
    private fun tryFindControlCenterClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val possibleClassNames = listOf(
                "com.oplus.melody.model.repository.earphone.AbstractC0772b",
                "l4.b",
                "com.oplus.melody.model.repository.earphone.b"
            )

            for (className in possibleClassNames) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

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
                            sendLog("找到控制中心类: $className")
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
            Log.w(TAG, "Control center instance is null")
            sendLog("控制中心实例为空")
            tryGetControlCenterInstance()
            return
        }

        if (currentMacAddress.isEmpty()) {
            Log.w(TAG, "MAC address is empty")
            sendLog("MAC 地址为空")
            return
        }

        try {
            val clazz = instance.javaClass

            val method = try {
                clazz.getMethod("n0", Int::class.javaPrimitiveType, String::class.java)
            } catch (e: NoSuchMethodException) {
                clazz.declaredMethods.find { m ->
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == String::class.java
                }
            }

            if (method != null) {
                method.isAccessible = true
                method.invoke(instance, mode, currentMacAddress)
                Log.i(TAG, "ANC mode switched to $mode")
                sendLog("降噪模式已切换到 $mode")
            } else {
                XposedBridge.log("$TAG: Cannot find method to switch ANC mode")
                sendLog("找不到切换降噪模式的方法")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error switching ANC mode: ${e.message}")
            sendLog("切换降噪模式失败: ${e.message}")
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

                    try {
                        val hMethod = clazz.getMethod("H")
                        controlCenterInstance = hMethod.invoke(null)
                        if (controlCenterInstance != null) {
                            Log.i(TAG, "Got control center instance via H()")
                            return
                        }
                    } catch (e: NoSuchMethodException) {
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
     * 发送日志到主界面
     */
    private fun sendLog(message: String) {
        try {
            val context = appContext ?: return
            Intent(Constants.Action.OPPO_LOG).apply {
                putExtra("log", "[HeyTap] $message")
                putExtra("time", System.currentTimeMillis())
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            // 忽略
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
        val deviceName: String,
        val isSpp: Boolean,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val boxCharging: Boolean = false
    )
}
