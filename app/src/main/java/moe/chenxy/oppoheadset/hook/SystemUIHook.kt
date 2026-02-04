package moe.chenxy.oppoheadset.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * SystemUI Hook
 *
 * 功能：
 * 1. 获取 Plugin ClassLoader → Hook DeviceInfoWrapper.performClicked
 *    让用户点击设备卡片时发送降噪切换指令（循环切换），而不是跳转
 * 2. 接收欢律广播的电量数据，记录 MAC 地址以识别卡片
 *
 * 重要：不干扰设备卡片的显示逻辑，只拦截点击行为
 */
class SystemUIHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SystemUIHook"

        @Volatile var leftBatt = -1
        @Volatile var rightBatt = -1
        @Volatile var boxBatt = -1
        @Volatile var mac = ""
        @Volatile var connected = false
        @Volatile var currentAncMode = Constants.AncMode.OFF

        @Volatile var systemUIContext: Context? = null
        @Volatile private var pluginCL: ClassLoader? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_SYSTEMUI) return

        Log.i(TAG, "=== SystemUIHook start ===")
        sendLog("模块加载到 SystemUI 进程")

        hookSystemUIApp(lpparam)
        hookPluginLoader(lpparam)
    }

    // ============================================================
    // 1. 获取 SystemUI Context + 注册接收器
    // ============================================================
    private fun hookSystemUIApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.SystemUIApplication",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        systemUIContext = param.thisObject as Context
                        sendLog("获取到 SystemUI Context")
                        registerBatteryReceiver(systemUIContext!!)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hookSystemUIApp failed: $t")
        }
    }

    /**
     * 接收欢律发来的电量广播
     */
    private fun registerBatteryReceiver(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                if (intent.action == Constants.Action.BATTERY_UPDATE) {
                    leftBatt = intent.getIntExtra("left", -1)
                    rightBatt = intent.getIntExtra("right", -1)
                    boxBatt = intent.getIntExtra("box", -1)
                    mac = intent.getStringExtra("mac") ?: ""
                    connected = leftBatt >= 0 || rightBatt >= 0

                    Log.d(TAG, "收到电量: L=$leftBatt R=$rightBatt B=$boxBatt MAC=$mac")
                    sendLog("电量: L=$leftBatt R=$rightBatt B=$boxBatt")
                }
            }
        }
        val filter = IntentFilter(Constants.Action.BATTERY_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        sendLog("电量接收器已注册")
    }

    // ============================================================
    // 2. Hook Plugin ClassLoader (和 HyperPods 一致)
    // ============================================================
    private fun hookPluginLoader(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pluginInstanceClass = XposedHelpers.findClass(
                "com.android.systemui.shared.plugins.PluginInstance",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                pluginInstanceClass,
                "loadPlugin",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = XposedHelpers.callMethod(param.thisObject, "getPackage")
                            if (pkg != "miui.systemui.plugin") return

                            val factory = XposedHelpers.getObjectField(param.thisObject, "mPluginFactory")
                            val clFactory = XposedHelpers.getObjectField(factory, "mClassLoaderFactory")
                            val cl = XposedHelpers.callMethod(clFactory, "get") as ClassLoader

                            if (pluginCL != cl) {
                                pluginCL = cl
                                sendLog("获取到 Plugin ClassLoader")
                                hookDeviceCard(cl)
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: loadPlugin hook error: $t")
                        }
                    }
                }
            )
            sendLog("成功 Hook PluginInstance.loadPlugin")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hookPluginLoader failed: $t")
            sendLog("Hook PluginInstance 失败: $t")
        }
    }

    // ============================================================
    // 3. Hook DeviceInfoWrapper.performClicked
    //    点击时循环切换降噪模式，而不是打开其他 App
    // ============================================================
    private fun hookDeviceCard(cl: ClassLoader) {
        try {
            val wrapperClass = XposedHelpers.findClass(
                "miui.systemui.devicecenter.devices.DeviceInfoWrapper", cl
            )

            XposedHelpers.findAndHookMethod(
                wrapperClass,
                "performClicked",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = param.args[0] as? Context ?: return
                            val info = XposedHelpers.callMethod(param.thisObject, "getDeviceInfo")
                                ?: return
                            val id = XposedHelpers.callMethod(info, "getId") as? String ?: return
                            val type = XposedHelpers.callMethod(info, "getDeviceType") as? String
                                ?: return

                            // 只处理 third_headset
                            if (type != "third_headset") return
                            // MAC 匹配检查
                            if (!connected || mac.isEmpty()) return
                            if (!id.equals(mac, ignoreCase = true)) return

                            Log.i(TAG, "OPPO 耳机卡片被点击，切换降噪模式")
                            sendLog("卡片被点击，切换降噪")

                            // 循环切换: OFF -> TRANSPARENCY -> STRONG_ANC -> OFF
                            currentAncMode = when (currentAncMode) {
                                Constants.AncMode.OFF -> Constants.AncMode.TRANSPARENCY
                                Constants.AncMode.TRANSPARENCY -> Constants.AncMode.STRONG_ANC
                                else -> Constants.AncMode.OFF
                            }

                            sendLog("发送降噪指令: mode=$currentAncMode")

                            // 发广播给欢律
                            ctx.sendBroadcast(Intent(Constants.Action.SWITCH_MODE).apply {
                                putExtra("mode", currentAncMode)
                            })

                            // 拦截默认行为
                            param.result = null
                        } catch (t: Throwable) {
                            // 出错不拦截，让默认行为继续
                            XposedBridge.log("$TAG: performClicked error: $t")
                        }
                    }
                }
            )

            sendLog("成功 Hook DeviceInfoWrapper.performClicked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hookDeviceCard failed: $t")
            sendLog("Hook DeviceInfoWrapper 失败: $t")
        }
    }

    private fun sendLog(msg: String) {
        try {
            systemUIContext?.sendBroadcast(Intent(Constants.Action.LOG).apply {
                putExtra("log", "[SystemUI] $msg")
                putExtra("time", System.currentTimeMillis())
            })
        } catch (_: Throwable) {}
    }
}
