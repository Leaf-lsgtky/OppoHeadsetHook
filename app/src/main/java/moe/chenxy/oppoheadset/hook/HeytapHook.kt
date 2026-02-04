package moe.chenxy.oppoheadset.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * 欢律 App Hook
 *
 * 严格遵循逆向情报：
 *
 * 1. Hook KeepAliveFgService.d(RemoteViews, Object) 获取电量 & MAC
 *    数据源对象（参数2）是混淆类，用 Object + 反射操作
 *    - getAddress() → MAC 地址
 *    - isSpp() → 连接类型
 *    - getLeftBattery() / getHeadsetLeftBattery() → 左耳电量
 *    - getRightBattery() / getHeadsetRightBattery() → 右耳电量
 *    - getBoxBattery() / getHeadsetBoxBattery() → 盒子电量
 *
 * 2. 接收 SystemUI 广播 OPPO_ACTION_SWITCH_MODE，调用：
 *    AbstractC0772b.H().n0(mode, macAddress)
 *    mode: 0=关闭, 1=通透, 4=强降噪
 */
class HeytapHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "HeytapHook"

        @Volatile var savedMac: String = ""
        @Volatile var controlInstance: Any? = null
        @Volatile var appContext: Context? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_HEYTAP) return

        Log.i(TAG, "=== HeytapHook start ===")
        sendLog("模块加载到欢律进程")

        hookBatterySource(lpparam)
        hookControlSingleton(lpparam)
        hookAppContext(lpparam)
    }

    // ============================================================
    // 1. Hook KeepAliveFgService.d 获取电量和 MAC
    // ============================================================
    private fun hookBatterySource(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                Constants.HookTarget.KEEP_ALIVE_SERVICE,
                lpparam.classLoader
            )

            // 遍历找到 d(RemoteViews, Object) 方法
            var hooked = false
            for (method in clazz.declaredMethods) {
                if (method.name == "d" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == RemoteViews::class.java
                ) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                onBatteryData(param.args[1] ?: return)
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG: battery hook error: $t")
                            }
                        }
                    })
                    hooked = true
                    Log.i(TAG, "Hooked KeepAliveFgService.d")
                    sendLog("成功 Hook 电量方法")
                    break
                }
            }

            if (!hooked) {
                sendLog("未找到 KeepAliveFgService.d 方法")
                XposedBridge.log("$TAG: KeepAliveFgService.d not found")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hookBatterySource failed: $t")
            sendLog("Hook 电量失败: $t")
        }
    }

    /**
     * 从混淆对象中反射提取电量数据，广播给 SystemUI
     */
    private fun onBatteryData(dataModel: Any) {
        // MAC 地址
        val mac = reflectStr(dataModel, "getAddress") ?: ""
        if (mac.isNotEmpty()) savedMac = mac

        // 电量
        val left = reflectInt(dataModel, "getLeftBattery", "getHeadsetLeftBattery")
        val right = reflectInt(dataModel, "getRightBattery", "getHeadsetRightBattery")
        val box = reflectInt(dataModel, "getBoxBattery", "getHeadsetBoxBattery")

        Log.d(TAG, "电量: L=$left R=$right B=$box MAC=$savedMac")

        // 广播给 SystemUI
        val ctx = appContext ?: return
        val intent = Intent(Constants.Action.BATTERY_UPDATE).apply {
            putExtra("left", left)
            putExtra("right", right)
            putExtra("box", box)
            putExtra("mac", savedMac)
        }
        ctx.sendBroadcast(intent)
    }

    // ============================================================
    // 2. Hook 控制降噪单例 AbstractC0772b.H()
    // ============================================================
    private fun hookControlSingleton(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                Constants.HookTarget.CONTROL_SINGLETON,
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "H",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result != null) {
                            controlInstance = param.result
                        }
                    }
                }
            )

            Log.i(TAG, "Hooked AbstractC0772b.H()")
            sendLog("成功 Hook 降噪单例")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hookControlSingleton failed: $t")
            sendLog("Hook 降噪单例失败: $t")
        }
    }

    /**
     * 执行降噪切换: AbstractC0772b.H().n0(mode, mac)
     */
    private fun switchMode(mode: Int) {
        val inst = controlInstance
        if (inst == null) {
            Log.w(TAG, "controlInstance 为 null，尝试主动获取")
            sendLog("控制实例为空，尝试主动获取")
            tryGetInstance()
            val retried = controlInstance
            if (retried == null) {
                sendLog("获取失败，无法切换")
                return
            }
            doSwitch(retried, mode)
            return
        }
        doSwitch(inst, mode)
    }

    private fun doSwitch(inst: Any, mode: Int) {
        if (savedMac.isEmpty()) {
            sendLog("MAC 为空，无法切换")
            return
        }
        try {
            val method = inst.javaClass.getMethod(
                "n0",
                Int::class.javaPrimitiveType,
                String::class.java
            )
            method.invoke(inst, mode, savedMac)
            Log.i(TAG, "n0($mode, $savedMac) 成功")
            sendLog("切换降噪: mode=$mode mac=$savedMac")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: n0() failed: $t")
            sendLog("n0() 调用失败: $t")
        }
    }

    private fun tryGetInstance() {
        try {
            val ctx = appContext ?: return
            val clazz = XposedHelpers.findClass(
                Constants.HookTarget.CONTROL_SINGLETON,
                ctx.classLoader
            )
            val h = clazz.getMethod("H")
            controlInstance = h.invoke(null)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: tryGetInstance failed: $t")
        }
    }

    // ============================================================
    // 3. 获取 Context 并监听 SystemUI 的控制指令
    // ============================================================
    private fun hookAppContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                private var done = false
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (done) return
                    val ctx = param.thisObject as? Context ?: return
                    if (ctx.packageName != Constants.PKG_NAME_HEYTAP) return
                    done = true
                    appContext = ctx
                    sendLog("获取到欢律 Context")

                    // 注册接收器：监听 SystemUI 发来的控制指令
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            val mode = intent?.getIntExtra("mode", -1) ?: return
                            if (mode < 0) return
                            Log.i(TAG, "收到切换指令: mode=$mode")
                            sendLog("收到切换指令: mode=$mode")
                            switchMode(mode)
                        }
                    }
                    val filter = IntentFilter(Constants.Action.SWITCH_MODE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        ctx.registerReceiver(receiver, filter)
                    }
                    sendLog("已注册控制指令接收器")
                }
            }
        )
    }

    // ============================================================
    // 反射工具
    // ============================================================
    private fun reflectStr(obj: Any, vararg names: String): String? {
        for (name in names) {
            try {
                val m = obj.javaClass.getMethod(name)
                val r = m.invoke(obj)
                if (r is String && r.isNotEmpty()) return r
            } catch (_: Throwable) {}
            try {
                val m = obj.javaClass.getDeclaredMethod(name)
                m.isAccessible = true
                val r = m.invoke(obj)
                if (r is String && r.isNotEmpty()) return r
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun reflectInt(obj: Any, vararg names: String): Int {
        for (name in names) {
            try {
                val m = obj.javaClass.getMethod(name)
                val r = m.invoke(obj)
                if (r is Number) return r.toInt()
            } catch (_: Throwable) {}
            try {
                val m = obj.javaClass.getDeclaredMethod(name)
                m.isAccessible = true
                val r = m.invoke(obj)
                if (r is Number) return r.toInt()
            } catch (_: Throwable) {}
        }
        return -1
    }

    private fun sendLog(msg: String) {
        try {
            appContext?.sendBroadcast(Intent(Constants.Action.LOG).apply {
                putExtra("log", "[欢律] $msg")
                putExtra("time", System.currentTimeMillis())
            })
        } catch (_: Throwable) {}
    }
}
