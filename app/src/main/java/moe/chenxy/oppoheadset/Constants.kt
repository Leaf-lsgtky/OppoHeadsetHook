package moe.chenxy.oppoheadset

/**
 * OPPO 耳机控制常量定义
 */
object Constants {
    const val PKG_NAME_SELF = "moe.chenxy.oppoheadset"
    const val PKG_NAME_HEYTAP = "com.heytap.headset"
    const val PKG_NAME_SYSTEMUI = "com.android.systemui"

    // 广播 Action 定义
    object Action {
        // SystemUI 接收电量更新
        const val OPPO_BATTERY_UPDATE = "com.xiaomi.controlcenter.OPPO_BATTERY_UPDATE"
        // SystemUI 发送控制指令
        const val OPPO_ACTION_SWITCH_MODE = "com.xiaomi.controlcenter.OPPO_ACTION_SWITCH_MODE"
        // 连接状态变更
        const val OPPO_CONNECTION_STATE = "com.xiaomi.controlcenter.OPPO_CONNECTION_STATE"
        // 设备卡片被点击
        const val OPPO_DEVICE_CLICKED = "com.xiaomi.controlcenter.OPPO_DEVICE_CLICKED"
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
    }
}
