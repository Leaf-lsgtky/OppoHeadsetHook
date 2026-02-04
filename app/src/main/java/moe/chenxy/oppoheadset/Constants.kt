package moe.chenxy.oppoheadset

/**
 * OPPO 耳机控制常量定义
 */
object Constants {
    const val PKG_NAME_SELF = "moe.chenxy.oppoheadset"
    const val PKG_NAME_HEYTAP = "com.heytap.headset"
    const val PKG_NAME_SYSTEMUI = "com.android.systemui"
    const val PKG_NAME_BLUETOOTH = "com.android.bluetooth"
    const val PKG_NAME_MI_BLUETOOTH = "com.xiaomi.bluetooth"

    // 广播 Action 定义
    object Action {
        // SystemUI 接收电量更新
        const val OPPO_BATTERY_UPDATE = "moe.chenxy.oppoheadset.BATTERY_UPDATE"
        // SystemUI 发送控制指令
        const val OPPO_ACTION_SWITCH_MODE = "moe.chenxy.oppoheadset.SWITCH_MODE"
        // 连接状态变更
        const val OPPO_CONNECTION_STATE = "moe.chenxy.oppoheadset.CONNECTION_STATE"
        // 设备卡片被点击
        const val OPPO_DEVICE_CLICKED = "moe.chenxy.oppoheadset.DEVICE_CLICKED"
        // 显示 Strong Toast
        const val OPPO_SHOW_STRONG_TOAST = "moe.chenxy.oppoheadset.SHOW_STRONG_TOAST"
        // 更新通知
        const val OPPO_UPDATE_NOTIFICATION = "moe.chenxy.oppoheadset.UPDATE_NOTIFICATION"
        // 取消通知
        const val OPPO_CANCEL_NOTIFICATION = "moe.chenxy.oppoheadset.CANCEL_NOTIFICATION"
        // 获取 MAC 地址
        const val OPPO_GET_MAC = "moe.chenxy.oppoheadset.GET_MAC"
        // MAC 地址响应
        const val OPPO_MAC_RECEIVED = "moe.chenxy.oppoheadset.MAC_RECEIVED"
        // 日志广播
        const val OPPO_LOG = "moe.chenxy.oppoheadset.LOG"
    }

    // 降噪模式
    object AncMode {
        const val OFF = 0          // 关闭
        const val TRANSPARENCY = 1  // 通透模式
        const val STRONG_ANC = 4    // 强降噪模式
    }

    // Hook 目标类名
    object HookTarget {
        const val KEEP_ALIVE_SERVICE = "com.heytap.headset.service.KeepAliveFgService"
        const val CONTROL_CENTER_SINGLETON = "com.oplus.melody.model.repository.earphone.AbstractC0772b"
        const val A2DP_SERVICE = "com.android.bluetooth.a2dp.A2dpService"
        const val MIUI_BT_NOTIFICATION = "com.android.bluetooth.ble.app.MiuiBluetoothNotification"
    }

    // OPPO 耳机蓝牙 UUID (需要根据实际情况调整)
    object OppoUUID {
        val HEADSET_UUIDS = hashSetOf(
            "0000110b-0000-1000-8000-00805f9b34fb", // A2DP Audio Sink
            "0000110a-0000-1000-8000-00805f9b34fb", // A2DP Audio Source
            "0000111e-0000-1000-8000-00805f9b34fb", // Handsfree
        )
    }
}
