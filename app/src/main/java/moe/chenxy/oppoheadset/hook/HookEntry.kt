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
 * 只 Hook 两个应用：
 * 1. com.heytap.headset - 获取电量、接收控制指令、执行降噪切换
 * 2. com.android.systemui - 接收电量、在控制中心显示控制按钮
 */
class HookEntry : IXposedHookLoadPackage {

    private val heytapHook = HeytapHook()
    private val systemUIHook = SystemUIHook()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            Constants.PKG_NAME_SELF -> {
                hookSelfModuleStatus(lpparam)
            }
            Constants.PKG_NAME_HEYTAP -> {
                XposedBridge.log("OppoHeadsetHook: Hooking ${Constants.PKG_NAME_HEYTAP}")
                heytapHook.handleLoadPackage(lpparam)
            }
            Constants.PKG_NAME_SYSTEMUI -> {
                XposedBridge.log("OppoHeadsetHook: Hooking ${Constants.PKG_NAME_SYSTEMUI}")
                systemUIHook.handleLoadPackage(lpparam)
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
        } catch (e: Throwable) {
            XposedBridge.log("OppoHeadsetHook: Failed to hook module status")
        }
    }
}
