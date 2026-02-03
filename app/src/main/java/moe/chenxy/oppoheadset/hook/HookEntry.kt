package moe.chenxy.oppoheadset.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * Xposed 模块入口
 */
class HookEntry : IXposedHookLoadPackage {

    private val oppoHook = OppoHeadsetHook()
    private val systemUIHook = SystemUIHook()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            Constants.PKG_NAME_SELF -> {
                // Hook 自身的 isModuleActive() 方法返回 true
                hookSelfModuleStatus(lpparam)
            }
            Constants.PKG_NAME_HEYTAP -> {
                XposedBridge.log("OppoHeadsetHook: Loading hook for ${Constants.PKG_NAME_HEYTAP}")
                oppoHook.handleLoadPackage(lpparam)
            }
            Constants.PKG_NAME_SYSTEMUI -> {
                XposedBridge.log("OppoHeadsetHook: Loading hook for ${Constants.PKG_NAME_SYSTEMUI}")
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
            XposedBridge.log("OppoHeadsetHook: Module status hook applied")
        } catch (e: Throwable) {
            XposedBridge.log("OppoHeadsetHook: Failed to hook module status: ${e.message}")
        }
    }
}
