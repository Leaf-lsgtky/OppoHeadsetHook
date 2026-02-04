package moe.chenxy.oppoheadset.hook

import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * 蓝牙服务 Hook - 监听 A2DP 连接状态
 *
 * 参考 HyperPods 的 HeadsetStateDispatcher 实现
 *
 * 功能：
 * 1. Hook A2dpService.handleConnectionStateChanged 监听连接状态
 * 2. 当检测到 OPPO 耳机连接时，显示状态栏图标
 * 3. 发送广播通知其他组件
 */
class BluetoothHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BluetoothHook"

        // 当前连接的 OPPO 耳机
        @Volatile
        var connectedDevice: BluetoothDevice? = null

        @Volatile
        var isConnected: Boolean = false

        // 应用 Context
        @Volatile
        var appContext: Context? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_BLUETOOTH) {
            return
        }

        Log.i(TAG, "Hooking ${Constants.PKG_NAME_BLUETOOTH}")
        sendLog("开始 Hook 蓝牙服务")

        // Hook A2dpService 监听连接状态
        hookA2dpService(lpparam)

        // 注册广播接收器
        hookBluetoothApplication(lpparam)
    }

    /**
     * Hook Application 获取 Context 并注册广播
     */
    private fun hookBluetoothApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    private var initialized = false

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (initialized) return
                        val app = param.thisObject as? Context ?: return
                        if (app.packageName != Constants.PKG_NAME_BLUETOOTH) return

                        initialized = true
                        appContext = app
                        Log.i(TAG, "Bluetooth Application context obtained")
                        sendLog("获取到蓝牙服务 Context")

                        // 注册广播接收器
                        registerMacRequestReceiver(app)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Application: ${e.message}")
        }
    }

    /**
     * 注册 MAC 地址请求广播接收器
     */
    private fun registerMacRequestReceiver(context: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Constants.Action.OPPO_GET_MAC) {
                        val device = connectedDevice
                        if (device != null) {
                            Intent(Constants.Action.OPPO_MAC_RECEIVED).apply {
                                putExtra("mac", device.address)
                                putExtra("name", device.name ?: "OPPO Headset")
                                `package` = Constants.PKG_NAME_SYSTEMUI
                                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                ctx?.sendBroadcast(this)
                            }
                            Log.d(TAG, "Sent MAC address: ${device.address}")
                        }
                    }
                }
            }

            val filter = IntentFilter(Constants.Action.OPPO_GET_MAC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            Log.i(TAG, "MAC request receiver registered")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to register receiver: ${e.message}")
        }
    }

    /**
     * Hook A2dpService.handleConnectionStateChanged
     * 参考 HyperPods 实现
     */
    private fun hookA2dpService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val a2dpServiceClass = XposedHelpers.findClass(
                Constants.HookTarget.A2DP_SERVICE,
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                a2dpServiceClass,
                "handleConnectionStateChanged",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val device = param.args[0] as? BluetoothDevice ?: return
                            val fromState = param.args[1] as Int
                            val toState = param.args[2] as Int

                            if (fromState == toState) return

                            val handler = XposedHelpers.getObjectField(param.thisObject, "mHandler") as? Handler
                            val context = param.thisObject as? ContextWrapper ?: return

                            handler?.post {
                                handleConnectionStateChange(context, device, toState)
                            } ?: run {
                                handleConnectionStateChange(context, device, toState)
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in handleConnectionStateChanged: ${e.message}")
                        }
                    }
                }
            )

            Log.i(TAG, "A2dpService.handleConnectionStateChanged hooked")
            sendLog("成功 Hook A2dpService")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook A2dpService: ${e.message}")
            sendLog("Hook A2dpService 失败: ${e.message}")
            // 尝试备用方法
            tryAlternativeA2dpHook(lpparam)
        }
    }

    /**
     * 备用 A2DP Hook 方案
     */
    private fun tryAlternativeA2dpHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val a2dpServiceClass = XposedHelpers.findClass(
                Constants.HookTarget.A2DP_SERVICE,
                lpparam.classLoader
            )

            // 尝试 hook 不同的方法签名
            for (method in a2dpServiceClass.declaredMethods) {
                if (method.name == "handleConnectionStateChanged" ||
                    method.name == "connectionStateChanged") {

                    val paramTypes = method.parameterTypes
                    if (paramTypes.size >= 2 &&
                        paramTypes[0] == BluetoothDevice::class.java) {

                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val device = param.args[0] as? BluetoothDevice ?: return
                                    // 尝试获取状态参数
                                    val toState = when {
                                        paramTypes.size >= 3 -> param.args[2] as? Int ?: return
                                        paramTypes.size >= 2 && paramTypes[1] == Int::class.javaPrimitiveType ->
                                            param.args[1] as? Int ?: return
                                        else -> return
                                    }

                                    val context = param.thisObject as? ContextWrapper ?: appContext ?: return
                                    handleConnectionStateChange(context, device, toState)
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG: Error in alternative hook: ${e.message}")
                                }
                            }
                        })

                        Log.i(TAG, "Alternative A2DP hook applied: ${method.name}")
                        sendLog("使用备用方法 Hook A2DP: ${method.name}")
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Alternative A2DP hook failed: ${e.message}")
        }
    }

    /**
     * 处理连接状态变化
     */
    private fun handleConnectionStateChange(context: Context, device: BluetoothDevice, state: Int) {
        try {
            val deviceName = device.name ?: "Unknown"
            val isOppoHeadset = isOppoHeadset(device)

            Log.d(TAG, "Connection state change: device=$deviceName, state=$state, isOppo=$isOppoHeadset")

            if (!isOppoHeadset) {
                Log.d(TAG, "Not an OPPO headset, ignoring")
                return
            }

            sendLog("OPPO 耳机连接状态变化: $deviceName, state=$state")

            when (state) {
                BluetoothHeadset.STATE_CONNECTED -> {
                    Log.i(TAG, "OPPO headset connected: $deviceName")
                    sendLog("OPPO 耳机已连接: $deviceName")

                    connectedDevice = device
                    isConnected = true

                    // 显示状态栏图标
                    showStatusBarIcon(context, true)

                    // 发送连接广播
                    sendConnectionBroadcast(context, device, true)
                }

                BluetoothHeadset.STATE_DISCONNECTING,
                BluetoothHeadset.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) {
                        Log.i(TAG, "OPPO headset disconnected: $deviceName")
                        sendLog("OPPO 耳机已断开: $deviceName")

                        // 隐藏状态栏图标
                        showStatusBarIcon(context, false)

                        // 发送断开广播
                        sendConnectionBroadcast(context, device, false)

                        // 取消通知
                        sendCancelNotification(context, device)

                        connectedDevice = null
                        isConnected = false
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error handling connection state: ${e.message}")
        }
    }

    /**
     * 判断是否是 OPPO 耳机
     * 这里通过设备名称判断，因为 OPPO 耳机没有特定的 UUID
     */
    private fun isOppoHeadset(device: BluetoothDevice): Boolean {
        val name = device.name?.lowercase() ?: return false

        // OPPO 耳机名称特征
        val oppoKeywords = listOf(
            "oppo", "enco", "o-free", "realme buds",
            "oneplus buds", "一加", "真我"
        )

        return oppoKeywords.any { name.contains(it) }
    }

    /**
     * 显示/隐藏状态栏图标
     */
    private fun showStatusBarIcon(context: Context, show: Boolean) {
        try {
            val statusBarManager = context.getSystemService("statusbar") as? StatusBarManager
            if (statusBarManager != null) {
                XposedHelpers.callMethod(statusBarManager, "setIconVisibility", "wireless_headset", show)
                Log.d(TAG, "Status bar icon visibility: $show")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to set status bar icon: ${e.message}")
        }
    }

    /**
     * 发送连接状态广播
     */
    private fun sendConnectionBroadcast(context: Context, device: BluetoothDevice, connected: Boolean) {
        Intent(Constants.Action.OPPO_CONNECTION_STATE).apply {
            putExtra("connected", connected)
            putExtra("mac", device.address)
            putExtra("name", device.name ?: "OPPO Headset")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

            // 发送给所有相关组件
            context.sendBroadcast(this)
        }
    }

    /**
     * 发送取消通知广播
     */
    private fun sendCancelNotification(context: Context, device: BluetoothDevice) {
        Intent(Constants.Action.OPPO_CANCEL_NOTIFICATION).apply {
            putExtra("device", device)
            `package` = Constants.PKG_NAME_MI_BLUETOOTH
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    /**
     * 发送日志到主界面
     */
    private fun sendLog(message: String) {
        try {
            val context = appContext ?: return
            Intent(Constants.Action.OPPO_LOG).apply {
                putExtra("log", "[BT] $message")
                putExtra("time", System.currentTimeMillis())
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            // 忽略
        }
    }
}
