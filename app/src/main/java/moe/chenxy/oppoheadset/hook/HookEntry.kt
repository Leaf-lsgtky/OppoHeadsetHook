package moe.chenxy.oppoheadset.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
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
}
