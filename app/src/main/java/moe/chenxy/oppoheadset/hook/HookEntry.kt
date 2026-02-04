package moe.chenxy.oppoheadset.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * Xposed 模块入口
 *
 * Hook 以下应用：
 * 1. com.android.systemui - 融合设备中心集成
 * 2. com.android.bluetooth - 监听蓝牙连接状态
 * 3. com.xiaomi.bluetooth - 显示小米风格的通知和 Toast
 * 4. com.heytap.headset - 获取 OPPO 耳机电量和控制降噪
 */
class HookEntry : IXposedHookLoadPackage {

    private val systemUIHook = SystemUIHook()
    private val bluetoothHook = BluetoothHook()
    private val miBluetoothHook = MiBluetoothHook()
    private val oppoHeadsetHook = OppoHeadsetHook()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            Constants.PKG_NAME_SELF -> {
                // Hook 自身的 isModuleActive() 方法返回 true
                hookSelfModuleStatus(lpparam)
            }
            Constants.PKG_NAME_SYSTEMUI -> {
                XposedBridge.log("OppoHeadsetHook: Loading hook for ${Constants.PKG_NAME_SYSTEMUI}")
                systemUIHook.handleLoadPackage(lpparam)
            }
            Constants.PKG_NAME_BLUETOOTH -> {
                XposedBridge.log("OppoHeadsetHook: Loading hook for ${Constants.PKG_NAME_BLUETOOTH}")
                bluetoothHook.handleLoadPackage(lpparam)
            }
            Constants.PKG_NAME_MI_BLUETOOTH -> {
                XposedBridge.log("OppoHeadsetHook: Loading hook for ${Constants.PKG_NAME_MI_BLUETOOTH}")
                miBluetoothHook.handleLoadPackage(lpparam)
            }
            Constants.PKG_NAME_HEYTAP -> {
                XposedBridge.log("OppoHeadsetHook: Loading hook for ${Constants.PKG_NAME_HEYTAP}")
                oppoHeadsetHook.handleLoadPackage(lpparam)
            }
        }
    }

    private fun hookSelfModuleStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "moe.chenxy.oppoheadset.MainActivity",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            XposedBridge.log("OppoHeadsetHook: Module status hook applied")
        } catch (e: Throwable) {
            XposedBridge.log("OppoHeadsetHook: Failed to hook module status: ${e.message}")
        }
    }
}
