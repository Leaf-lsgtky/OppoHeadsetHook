package moe.chenxy.oppoheadset.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import moe.chenxy.oppoheadset.Constants

/**
 * OPPO 耳机控制器 - 用于 SystemUI 集成
 *
 * 功能:
 * 1. 接收欢律 App 发来的电量广播
 * 2. 发送控制指令给欢律 App 切换降噪模式
 * 3. 提供回调接口供 UI 层更新显示
 */
class OppoHeadsetController(private val context: Context) {

    companion object {
        private const val TAG = "OppoHeadsetController"
    }

    // 电量数据
    var leftBattery: Int = -1
        private set
    var rightBattery: Int = -1
        private set
    var boxBattery: Int = -1
        private set
    var macAddress: String = ""
        private set
    var isConnected: Boolean = false
        private set

    // 回调接口
    private var batteryCallback: BatteryUpdateCallback? = null

    // 处理线程
    private val handlerThread = HandlerThread("oppo_controller_thread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    // 广播接收器
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.Action.OPPO_BATTERY_UPDATE -> {
                    handleBatteryUpdate(intent)
                }
                Constants.Action.OPPO_CONNECTION_STATE -> {
                    handleConnectionStateChange(intent)
                }
            }
        }
    }

    /**
     * 电量更新回调接口
     */
    interface BatteryUpdateCallback {
        fun onBatteryUpdated(left: Int, right: Int, box: Int, mac: String)
        fun onConnectionStateChanged(connected: Boolean)
    }

    /**
     * 初始化控制器，注册广播接收器
     */
    fun initialize() {
        val filter = IntentFilter().apply {
            addAction(Constants.Action.OPPO_BATTERY_UPDATE)
            addAction(Constants.Action.OPPO_CONNECTION_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(batteryReceiver, filter, null, handler)
        }

        Log.i(TAG, "OppoHeadsetController initialized")
    }

    /**
     * 销毁控制器，注销广播接收器
     */
    fun destroy() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
        handlerThread.quitSafely()
        Log.i(TAG, "OppoHeadsetController destroyed")
    }

    /**
     * 设置电量更新回调
     */
    fun setBatteryCallback(callback: BatteryUpdateCallback?) {
        this.batteryCallback = callback
    }

    /**
     * 处理电量更新广播
     */
    private fun handleBatteryUpdate(intent: Intent) {
        leftBattery = intent.getIntExtra("left", -1)
        rightBattery = intent.getIntExtra("right", -1)
        boxBattery = intent.getIntExtra("box", -1)
        macAddress = intent.getStringExtra("mac") ?: ""

        val wasConnected = isConnected
        isConnected = leftBattery >= 0 || rightBattery >= 0

        Log.d(TAG, "Battery update: left=$leftBattery, right=$rightBattery, box=$boxBattery, mac=$macAddress")

        // 通知 UI 更新
        batteryCallback?.onBatteryUpdated(leftBattery, rightBattery, boxBattery, macAddress)

        // 如果连接状态变化，也通知
        if (wasConnected != isConnected) {
            batteryCallback?.onConnectionStateChanged(isConnected)
        }
    }

    /**
     * 处理连接状态变化
     */
    private fun handleConnectionStateChange(intent: Intent) {
        isConnected = intent.getBooleanExtra("connected", false)
        Log.d(TAG, "Connection state changed: $isConnected")

        if (!isConnected) {
            // 重置电量数据
            leftBattery = -1
            rightBattery = -1
            boxBattery = -1
            macAddress = ""
        }

        batteryCallback?.onConnectionStateChanged(isConnected)
    }

    /**
     * 切换降噪模式
     * @param mode 模式代码: 0=关闭, 1=通透, 4=强降噪
     */
    fun switchMode(mode: Int) {
        if (!isConnected || macAddress.isEmpty()) {
            Log.w(TAG, "Cannot switch mode: not connected or no MAC address")
            return
        }

        Intent(Constants.Action.OPPO_ACTION_SWITCH_MODE).apply {
            putExtra("mode", mode)
            putExtra("mac", macAddress)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            `package` = Constants.PKG_NAME_HEYTAP
            context.sendBroadcast(this)
        }

        Log.i(TAG, "Sent mode switch command: mode=$mode, mac=$macAddress")
    }

    /**
     * 切换到关闭模式
     */
    fun setModeOff() = switchMode(Constants.AncMode.OFF)

    /**
     * 切换到通透模式
     */
    fun setModeTransparency() = switchMode(Constants.AncMode.TRANSPARENCY)

    /**
     * 切换到强降噪模式
     */
    fun setModeStrongAnc() = switchMode(Constants.AncMode.STRONG_ANC)

    /**
     * 循环切换模式: 关闭 -> 通透 -> 强降噪 -> 关闭
     */
    private var currentMode = Constants.AncMode.OFF

    fun cycleMode() {
        currentMode = when (currentMode) {
            Constants.AncMode.OFF -> Constants.AncMode.TRANSPARENCY
            Constants.AncMode.TRANSPARENCY -> Constants.AncMode.STRONG_ANC
            Constants.AncMode.STRONG_ANC -> Constants.AncMode.OFF
            else -> Constants.AncMode.OFF
        }
        switchMode(currentMode)
    }
}
