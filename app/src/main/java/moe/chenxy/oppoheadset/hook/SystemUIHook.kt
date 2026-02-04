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
 * 参考 HyperPods 的 SystemUIPluginHook 和 DeviceCardHook 实现
 *
 * 功能：
 * 1. Hook PluginInstance.loadPlugin() 获取 miui.systemui.plugin 的 ClassLoader
 * 2. 使用该 ClassLoader hook DeviceInfoWrapper.performClicked 拦截设备卡片点击
 * 3. 注册广播接收器接收电量数据
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
        var deviceName: String = ""

        @Volatile
        var isOppoHeadsetConnected: Boolean = false

        // SystemUI Context
        @Volatile
        var systemUIContext: Context? = null

        // Plugin ClassLoader
        @Volatile
        private var pluginClassLoader: ClassLoader? = null

        // MainPanelController 实例
        @Volatile
        private var panelController: Any? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_SYSTEMUI) {
            return
        }

        Log.i(TAG, "Hooking ${Constants.PKG_NAME_SYSTEMUI}")
        sendLog("开始 Hook SystemUI")

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
                        sendLog("获取到 SystemUI Context")

                        // 注册广播接收器
                        registerReceivers(app)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SystemUIApplication: ${e.message}")
        }
    }

    /**
     * Hook Plugin 加载器获取 miui.systemui.plugin 的 ClassLoader
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
                                    sendLog("获取到 Plugin ClassLoader")
                                    pluginClassLoader = clsLoader
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
            sendLog("成功 Hook PluginInstance")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook PluginInstance: ${e.message}")
            sendLog("Hook PluginInstance 失败: ${e.message}")
        }
    }

    /**
     * 初始化 Plugin Hook
     */
    private fun initPluginHook(classLoader: ClassLoader) {
        try {
            // Hook MainPanelController 获取实例
            hookMainPanelController(classLoader)

            // Hook DeviceInfoWrapper
            hookDeviceInfoWrapper(classLoader)

            Log.i(TAG, "Plugin hooks initialized")
            sendLog("Plugin Hook 初始化完成")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize plugin hooks: ${e.message}")
        }
    }

    /**
     * Hook MainPanelController 获取实例用于隐藏控制中心
     */
    private fun hookMainPanelController(classLoader: ClassLoader) {
        try {
            val panelControllerClass = classLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.MainPanelController"
            )

            XposedHelpers.findAndHookMethod(
                panelControllerClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        panelController = param.thisObject
                        Log.d(TAG, "MainPanelController instance obtained")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook MainPanelController: ${e.message}")
        }
    }

    /**
     * 隐藏控制中心面板
     */
    private fun hidePanel() {
        try {
            panelController?.let {
                XposedHelpers.callMethod(it, "exitOrHide")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to hide panel: ${e.message}")
        }
    }

    /**
     * Hook DeviceInfoWrapper.performClicked
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

                            // 检查是否是我们跟踪的 OPPO 耳机
                            if (!isOppoHeadsetConnected || macAddress.isEmpty()) {
                                return
                            }

                            // 请求 MAC 地址确认
                            requestMacConfirmation(context)

                            // 等待 MAC 确认（最多 500ms）
                            var waitCount = 10
                            while (macAddress.isEmpty() && waitCount-- > 0) {
                                Thread.sleep(50)
                            }

                            if (deviceId.equals(macAddress, ignoreCase = true)) {
                                Log.i(TAG, "OPPO headset clicked, opening HeyTap")
                                sendLog("点击 OPPO 耳机卡片，打开欢律")

                                val intent = Intent().apply {
                                    setClassName(Constants.PKG_NAME_HEYTAP, "com.heytap.headset.ui.MainActivity")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }

                                try {
                                    context.startActivity(intent)
                                    hidePanel()
                                    param.result = null
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Failed to start HeyTap: ${e.message}")
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in performClicked: ${e.message}")
                        }
                    }
                }
            )

            Log.i(TAG, "DeviceInfoWrapper.performClicked hooked")
            sendLog("成功 Hook DeviceInfoWrapper")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook DeviceInfoWrapper: ${e.message}")
            sendLog("Hook DeviceInfoWrapper 失败: ${e.message}")
        }
    }

    /**
     * 请求 MAC 地址确认
     */
    private fun requestMacConfirmation(context: Context) {
        Intent(Constants.Action.OPPO_GET_MAC).apply {
            `package` = Constants.PKG_NAME_BLUETOOTH
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    /**
     * 注册广播接收器
     */
    private fun registerReceivers(context: Context) {
        val handlerThread = HandlerThread("oppo_systemui_receiver").apply { start() }
        val handler = Handler(handlerThread.looper)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return

                when (intent.action) {
                    Constants.Action.OPPO_BATTERY_UPDATE -> {
                        leftBattery = intent.getIntExtra("left", -1)
                        rightBattery = intent.getIntExtra("right", -1)
                        boxBattery = intent.getIntExtra("box", -1)
                        macAddress = intent.getStringExtra("mac") ?: ""
                        deviceName = intent.getStringExtra("name") ?: ""
                        isOppoHeadsetConnected = leftBattery >= 0 || rightBattery >= 0

                        Log.d(TAG, "Battery update: L=$leftBattery R=$rightBattery B=$boxBattery MAC=$macAddress")
                        sendLog("电量更新: L=$leftBattery R=$rightBattery B=$boxBattery")
                    }

                    Constants.Action.OPPO_CONNECTION_STATE -> {
                        val connected = intent.getBooleanExtra("connected", false)
                        macAddress = intent.getStringExtra("mac") ?: ""
                        deviceName = intent.getStringExtra("name") ?: ""
                        isOppoHeadsetConnected = connected

                        Log.d(TAG, "Connection state: connected=$connected, mac=$macAddress")
                        sendLog("连接状态: ${if (connected) "已连接" else "已断开"} $deviceName")

                        if (!connected) {
                            leftBattery = -1
                            rightBattery = -1
                            boxBattery = -1
                        }
                    }

                    Constants.Action.OPPO_MAC_RECEIVED -> {
                        macAddress = intent.getStringExtra("mac") ?: ""
                        deviceName = intent.getStringExtra("name") ?: ""
                        Log.d(TAG, "MAC received: $macAddress")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Constants.Action.OPPO_BATTERY_UPDATE)
            addAction(Constants.Action.OPPO_CONNECTION_STATE)
            addAction(Constants.Action.OPPO_MAC_RECEIVED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }

        Log.i(TAG, "Receivers registered in SystemUI")
        sendLog("广播接收器已注册")
    }

    /**
     * 发送日志到主界面
     */
    private fun sendLog(message: String) {
        try {
            val context = systemUIContext ?: return
            Intent(Constants.Action.OPPO_LOG).apply {
                putExtra("log", "[UI] $message")
                putExtra("time", System.currentTimeMillis())
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            // 忽略
        }
    }
}
