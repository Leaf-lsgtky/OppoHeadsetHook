package moe.chenxy.oppoheadset

/**
 * OPPO 耳机控制常量定义
 * 严格遵循逆向情报
 */
object Constants {
    // 包名
    const val PKG_NAME_SELF = "moe.chenxy.oppoheadset"
    const val PKG_NAME_HEYTAP = "com.heytap.headset"
    const val PKG_NAME_SYSTEMUI = "com.android.systemui"

    // 广播 Action（严格遵循协议约定）
    object Action {
        // SystemUI 接收电量
        const val BATTERY_UPDATE = "com.xiaomi.controlcenter.OPPO_BATTERY_UPDATE"
        // SystemUI 发送控制
        const val SWITCH_MODE = "com.xiaomi.controlcenter.OPPO_ACTION_SWITCH_MODE"
        // 日志广播（内部使用）
        const val LOG = "moe.chenxy.oppoheadset.LOG"
    }

    // 降噪模式（严格遵循逆向情报）
    object AncMode {
        const val OFF = 0           // 关闭
        const val TRANSPARENCY = 1  // 通透模式
        const val STRONG_ANC = 4    // 强降噪模式
    }

    // Hook 目标类名（严格遵循逆向情报）
    object HookTarget {
        // 获取电量的 Service
        const val KEEP_ALIVE_SERVICE = "com.heytap.headset.service.KeepAliveFgService"
        // 控制降噪的单例类
        const val CONTROL_SINGLETON = "com.oplus.melody.model.repository.earphone.AbstractC0772b"
    }
}
