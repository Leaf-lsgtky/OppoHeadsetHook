package moe.chenxy.oppoheadset.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * SystemUI Hook - 在小米控制中心集成 OPPO 耳机控制
 *
 * 工作原理：
 * 1. Hook PluginInstance.loadPlugin() 获取 miui.systemui.plugin 的 ClassLoader
 * 2. 使用该 ClassLoader hook DeviceInfoWrapper.performClicked 拦截设备卡片点击
 * 3. 注册广播接收器接收欢律 App 发来的电量数据
 */
class SystemUIHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SystemUIHook"

        // OPPO 耳机电量数据
        @Volatile
        var leftBattery: Int = -1

        @Volatile
        var rightBattery: Int = -1

        @Volatile
        var boxBattery: Int = -1

        @Volatile
        var macAddress: String = ""

        @Volatile
        var isOppoHeadsetConnected: Boolean = false

        // SystemUI Context
        @Volatile
        var systemUIContext: Context? = null

        // Plugin ClassLoader
        @Volatile
        private var pluginClassLoader: ClassLoader? = null

        // 电量更新回调
        var onBatteryUpdate: ((left: Int, right: Int, box: Int, mac: String) -> Unit)? = null
    }

    private var lpparam: XC_LoadPackage.LoadPackageParam? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_SYSTEMUI) {
            return
        }

        this.lpparam = lpparam
        Log.i(TAG, "Hooking ${Constants.PKG_NAME_SYSTEMUI}")

        // Hook SystemUI 启动，注册广播接收器
        hookSystemUIInit(lpparam)

        // Hook Plugin 加载，获取 plugin ClassLoader
        hookPluginLoader(lpparam)
    }

    /**
     * Hook SystemUI 初始化
     */
    private fun hookSystemUIInit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Context
                        systemUIContext = app
                        Log.i(TAG, "SystemUI context obtained")

                        // 注册广播接收器
                        registerBatteryReceiver(app)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SystemUIApplication: ${e.message}")
        }
    }

    /**
     * Hook Plugin 加载器获取 miui.systemui.plugin 的 ClassLoader
     * 参考 HyperPods 的实现
     */
    private fun hookPluginLoader(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pluginInstanceClass = XposedHelpers.findClass(
                "com.android.systemui.shared.plugins.PluginInstance",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                pluginInstanceClass,
                "loadPlugin",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkgName = XposedHelpers.callMethod(param.thisObject, "getPackage")
                            if (pkgName == "miui.systemui.plugin") {
                                val factory = XposedHelpers.getObjectField(param.thisObject, "mPluginFactory")
                                val classLoaderFactory = XposedHelpers.getObjectField(factory, "mClassLoaderFactory")
                                val clsLoader = XposedHelpers.callMethod(classLoaderFactory, "get") as ClassLoader

                                if (pluginClassLoader != clsLoader) {
                                    Log.i(TAG, "Got miui.systemui.plugin ClassLoader")
                                    pluginClassLoader = clsLoader
                                    // 初始化 Plugin Hook
                                    initPluginHook(clsLoader)
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in loadPlugin hook: ${e.message}")
                        }
                    }
                }
            )

            Log.i(TAG, "PluginInstance.loadPlugin hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook PluginInstance: ${e.message}")
            // 可能是旧版本 SystemUI，尝试其他方式
            tryAlternativePluginHook(lpparam)
        }
    }

    /**
     * 备用 Plugin Hook 方案（适用于旧版本）
     */
    private fun tryAlternativePluginHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 尝试 Hook PluginManagerImpl
            val pluginManagerClass = XposedHelpers.findClass(
                "com.android.systemui.shared.plugins.PluginManagerImpl",
                lpparam.classLoader
            )

            for (method in pluginManagerClass.declaredMethods) {
                if (method.name == "onPluginLoaded" || method.name == "handleLoadPlugin") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                // 尝试从参数中获取 ClassLoader
                                for (arg in param.args) {
                                    if (arg is ClassLoader) {
                                        tryInitPluginHookWithClassLoader(arg)
                                    }
                                }
                            } catch (e: Throwable) {
                                // 忽略错误
                            }
                        }
                    })
                    Log.i(TAG, "Alternative plugin hook applied: ${method.name}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Alternative plugin hook also failed: ${e.message}")
        }
    }

    /**
     * 尝试使用给定的 ClassLoader 初始化 Plugin Hook
     */
    private fun tryInitPluginHookWithClassLoader(classLoader: ClassLoader) {
        try {
            // 检查是否是 miui.systemui.plugin 的 ClassLoader
            val testClass = classLoader.loadClass("miui.systemui.devicecenter.devices.DeviceInfoWrapper")
            if (testClass != null && pluginClassLoader != classLoader) {
                Log.i(TAG, "Found miui.systemui.plugin ClassLoader via alternative method")
                pluginClassLoader = classLoader
                initPluginHook(classLoader)
            }
        } catch (e: ClassNotFoundException) {
            // 不是正确的 ClassLoader，忽略
        }
    }

    /**
     * 初始化 Plugin Hook
     * 使用 plugin ClassLoader hook DeviceInfoWrapper
     */
    private fun initPluginHook(classLoader: ClassLoader) {
        try {
            hookDeviceInfoWrapper(classLoader)
            Log.i(TAG, "Plugin hooks initialized")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize plugin hooks: ${e.message}")
        }
    }

    /**
     * Hook DeviceInfoWrapper.performClicked
     * 拦截设备卡片点击事件
     *
     * 注意：这个 hook 必须非常小心，不能影响原有的设备卡片显示
     */
    private fun hookDeviceInfoWrapper(classLoader: ClassLoader) {
        try {
            val deviceInfoWrapperClass = XposedHelpers.findClass(
                "miui.systemui.devicecenter.devices.DeviceInfoWrapper",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                deviceInfoWrapperClass,
                "performClicked",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 使用 try-catch 确保不会影响原有功能
                        try {
                            val context = param.args[0] as? Context ?: return
                            val deviceInfo = XposedHelpers.callMethod(param.thisObject, "getDeviceInfo") ?: return
                            val deviceId = XposedHelpers.callMethod(deviceInfo, "getId") as? String ?: return
                            val deviceType = XposedHelpers.callMethod(deviceInfo, "getDeviceType") as? String ?: return

                            Log.d(TAG, "performClicked: id=$deviceId, type=$deviceType, trackedMac=$macAddress")

                            // 只处理第三方耳机类型
                            if (deviceType != "third_headset") {
                                return
                            }

                            // 只有当我们正在跟踪 OPPO 耳机且 MAC 地址匹配时才拦截
                            if (!isOppoHeadsetConnected || macAddress.isEmpty()) {
                                Log.d(TAG, "OPPO headset not tracked, letting default behavior")
                                return
                            }

                            // 检查设备 ID 是否与我们跟踪的 MAC 地址匹配
                            if (deviceId.equals(macAddress, ignoreCase = true)) {
                                Log.i(TAG, "OPPO headset clicked, attempting to open control UI")

                                // 尝试打开欢律 App
                                val intent = Intent().apply {
                                    putExtra("mac", macAddress)
                                    putExtra("left", leftBattery)
                                    putExtra("right", rightBattery)
                                    putExtra("box", boxBattery)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    setClassName(Constants.PKG_NAME_HEYTAP, "com.heytap.headset.ui.MainActivity")
                                }

                                try {
                                    context.startActivity(intent)
                                    Log.i(TAG, "Successfully started HeyTap activity")
                                    // 只有成功启动后才阻止默认行为
                                    param.result = null
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Failed to start HeyTap activity: ${e.message}, falling back to default")
                                    // 失败时不设置 result，让默认行为继续
                                }
                            }
                        } catch (e: Throwable) {
                            // 发生任何异常都不要影响原有功能
                            XposedBridge.log("$TAG: Error in performClicked hook (non-fatal): ${e.message}")
                        }
                    }
                }
            )

            Log.i(TAG, "DeviceInfoWrapper.performClicked hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook DeviceInfoWrapper: ${e.message}")
        }
    }

    /**
     * 隐藏控制中心面板
     */
    private fun hideControlPanel() {
        try {
            val panelControllerClass = pluginClassLoader?.loadClass(
                "miui.systemui.controlcenter.panel.main.MainPanelController"
            )
            // 尝试调用 exitOrHide 方法
            // 注意：这需要获取到 panelController 实例，可能需要额外的 hook
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to hide control panel: ${e.message}")
        }
    }

    /**
     * 注册电量广播接收器
     */
    private fun registerBatteryReceiver(context: Context) {
        val handlerThread = HandlerThread("oppo_battery_receiver").apply { start() }
        val handler = Handler(handlerThread.looper)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Constants.Action.OPPO_BATTERY_UPDATE -> {
                        leftBattery = intent.getIntExtra("left", -1)
                        rightBattery = intent.getIntExtra("right", -1)
                        boxBattery = intent.getIntExtra("box", -1)
                        macAddress = intent.getStringExtra("mac") ?: ""
                        isOppoHeadsetConnected = leftBattery >= 0 || rightBattery >= 0

                        Log.d(TAG, "OPPO battery update: L=$leftBattery R=$rightBattery B=$boxBattery MAC=$macAddress")

                        // 通知回调
                        onBatteryUpdate?.invoke(leftBattery, rightBattery, boxBattery, macAddress)
                    }
                }
            }
        }

        val filter = IntentFilter(Constants.Action.OPPO_BATTERY_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }

        Log.i(TAG, "Battery receiver registered in SystemUI")
    }

    /**
     * 发送降噪模式切换指令
     */
    fun switchAncMode(mode: Int) {
        val context = systemUIContext
        if (context == null) {
            Log.w(TAG, "SystemUI context is null")
            return
        }

        if (macAddress.isEmpty()) {
            Log.w(TAG, "MAC address is empty")
            return
        }

        Intent(Constants.Action.OPPO_ACTION_SWITCH_MODE).apply {
            putExtra("mode", mode)
            putExtra("mac", macAddress)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            `package` = Constants.PKG_NAME_HEYTAP
            context.sendBroadcast(this)
        }

        Log.i(TAG, "Sent ANC mode switch: $mode to $macAddress")
    }
}
