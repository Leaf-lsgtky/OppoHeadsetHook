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

        // 电量更新回调
        var onBatteryUpdate: ((left: Int, right: Int, box: Int, mac: String) -> Unit)? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_SYSTEMUI) {
            return
        }

        Log.i(TAG, "Hooking ${Constants.PKG_NAME_SYSTEMUI}")

        // Hook SystemUI 启动，注册广播接收器
        hookSystemUIInit(lpparam)
    }

    /**
     * Hook SystemUI 初始化
     */
    private fun hookSystemUIInit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook SystemUIApplication
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
