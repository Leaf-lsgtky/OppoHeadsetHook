package moe.chenxy.oppoheadset.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.chenxy.oppoheadset.Constants

/**
 * 小米蓝牙 Hook - 显示小米风格的通知和 Toast
 *
 * 参考 HyperPods 的 MiBluetoothToastHook 实现
 *
 * 功能：
 * 1. Hook MiuiBluetoothNotification 创建电量通知
 * 2. 注册广播接收器接收电量更新
 * 3. 显示设备通知
 */
class MiBluetoothHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MiBluetoothHook"

        @Volatile
        var appContext: Context? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.PKG_NAME_MI_BLUETOOTH) {
            return
        }

        Log.i(TAG, "Hooking ${Constants.PKG_NAME_MI_BLUETOOTH}")
        sendLog("开始 Hook 小米蓝牙")

        // Hook MiuiBluetoothNotification
        hookMiuiBluetoothNotification(lpparam)
    }

    /**
     * Hook MiuiBluetoothNotification 构造函数
     */
    private fun hookMiuiBluetoothNotification(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val notificationClass = XposedHelpers.findClass(
                Constants.HookTarget.MIUI_BT_NOTIFICATION,
                lpparam.classLoader
            )

            // Hook 构造函数
            for (constructor in notificationClass.constructors) {
                if (constructor.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                                if (context != null && appContext == null) {
                                    appContext = context
                                    Log.i(TAG, "MiuiBluetoothNotification context obtained")
                                    sendLog("获取到小米蓝牙 Context")

                                    // 注册广播接收器
                                    registerBroadcastReceiver(context)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG: Error in constructor hook: ${e.message}")
                            }
                        }
                    })
                    Log.i(TAG, "MiuiBluetoothNotification constructor hooked")
                    sendLog("成功 Hook MiuiBluetoothNotification")
                    break
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook MiuiBluetoothNotification: ${e.message}")
            sendLog("Hook MiuiBluetoothNotification 失败: ${e.message}")
            // 尝试通过 Application hook
            hookApplication(lpparam)
        }
    }

    /**
     * 备用方案：Hook Application
     */
    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    private var initialized = false

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (initialized) return
                        val app = param.thisObject as? Context ?: return
                        if (app.packageName != Constants.PKG_NAME_MI_BLUETOOTH) return

                        initialized = true
                        appContext = app
                        Log.i(TAG, "Application context obtained (fallback)")
                        sendLog("通过 Application 获取 Context")
                        registerBroadcastReceiver(app)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Application: ${e.message}")
        }
    }

    /**
     * 注册广播接收器
     */
    private fun registerBroadcastReceiver(context: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    ctx ?: return
                    intent ?: return

                    when (intent.action) {
                        Constants.Action.OPPO_SHOW_STRONG_TOAST -> {
                            handleShowToast(ctx, intent)
                        }
                        Constants.Action.OPPO_UPDATE_NOTIFICATION -> {
                            handleUpdateNotification(ctx, intent)
                        }
                        Constants.Action.OPPO_CANCEL_NOTIFICATION -> {
                            handleCancelNotification(ctx, intent)
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Constants.Action.OPPO_SHOW_STRONG_TOAST)
                addAction(Constants.Action.OPPO_UPDATE_NOTIFICATION)
                addAction(Constants.Action.OPPO_CANCEL_NOTIFICATION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            Log.i(TAG, "Broadcast receiver registered")
            sendLog("广播接收器已注册")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to register receiver: ${e.message}")
        }
    }

    /**
     * 处理显示 Toast
     */
    private fun handleShowToast(context: Context, intent: Intent) {
        try {
            val left = intent.getIntExtra("left", -1)
            val right = intent.getIntExtra("right", -1)
            val box = intent.getIntExtra("box", -1)
            val deviceName = intent.getStringExtra("name") ?: "OPPO Headset"

            Log.d(TAG, "Show toast: L=$left R=$right B=$box name=$deviceName")
            sendLog("显示 Toast: L=$left R=$right B=$box")

            // 使用小米的 Strong Toast API 显示电量
            showStrongToast(context, left, right, box, deviceName)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error showing toast: ${e.message}")
        }
    }

    /**
     * 处理更新通知
     */
    @SuppressLint("MissingPermission")
    private fun handleUpdateNotification(context: Context, intent: Intent) {
        try {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("device", BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("device")
            }

            val left = intent.getIntExtra("left", -1)
            val right = intent.getIntExtra("right", -1)
            val box = intent.getIntExtra("box", -1)
            val leftCharging = intent.getBooleanExtra("leftCharging", false)
            val rightCharging = intent.getBooleanExtra("rightCharging", false)
            val boxCharging = intent.getBooleanExtra("boxCharging", false)

            if (device == null) {
                Log.w(TAG, "Device is null in update notification")
                return
            }

            Log.d(TAG, "Update notification: L=$left R=$right B=$box")
            sendLog("更新通知: L=$left R=$right B=$box")

            createNotification(context, device, left, right, box, leftCharging, rightCharging, boxCharging)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error updating notification: ${e.message}")
        }
    }

    /**
     * 处理取消通知
     */
    private fun handleCancelNotification(context: Context, intent: Intent) {
        try {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("device", BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("device")
            }

            if (device != null) {
                cancelNotification(context, device)
                Log.d(TAG, "Notification cancelled for ${device.address}")
                sendLog("取消通知: ${device.name}")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error cancelling notification: ${e.message}")
        }
    }

    /**
     * 显示 Strong Toast（小米风格）
     */
    private fun showStrongToast(context: Context, left: Int, right: Int, box: Int, deviceName: String) {
        try {
            // 构建显示内容
            val contentBuilder = StringBuilder()
            if (left >= 0) contentBuilder.append("L: $left%")
            if (right >= 0) {
                if (contentBuilder.isNotEmpty()) contentBuilder.append(" | ")
                contentBuilder.append("R: $right%")
            }
            if (box >= 0) {
                if (contentBuilder.isNotEmpty()) contentBuilder.append(" | ")
                contentBuilder.append("盒: $box%")
            }

            val content = contentBuilder.toString()
            if (content.isEmpty()) return

            // 尝试使用小米的 Strong Toast API
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            if (service != null) {
                val bundle = Bundle().apply {
                    putString("package_name", Constants.PKG_NAME_MI_BLUETOOTH)
                    putString("strong_toast_category", "text_bitmap")
                    putString("param", "{\"left\":{\"textParams\":{\"text\":\"$deviceName\",\"textColor\":-1}},\"right\":{\"textParams\":{\"text\":\"$content\",\"textColor\":-1}}}")
                    putLong("duration", 3000)
                }

                try {
                    service.javaClass.getMethod(
                        "setStatus",
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(service, 1, "strong_toast_action", bundle)
                    Log.d(TAG, "Strong toast shown")
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to show strong toast, using fallback")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error showing strong toast: ${e.message}")
        }
    }

    /**
     * 创建通知
     */
    @SuppressLint("MissingPermission")
    private fun createNotification(
        context: Context,
        device: BluetoothDevice,
        left: Int,
        right: Int,
        box: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        boxCharging: Boolean
    ) {
        try {
            val address = device.address
            var alias = device.alias
            if (alias.isNullOrEmpty()) {
                alias = device.name ?: "OPPO Headset"
            }

            // 构建通知内容
            val contentBuilder = StringBuilder()
            if (box >= 0) {
                contentBuilder.append("盒子：$box%")
                if (boxCharging) contentBuilder.append(" ⚡")
                contentBuilder.append("\n")
            }
            if (left >= 0) {
                contentBuilder.append("左耳：$left%")
                if (leftCharging) contentBuilder.append(" ⚡")
            }
            if (right >= 0) {
                if (left >= 0) contentBuilder.append(" | ")
                contentBuilder.append("右耳：$right%")
                if (rightCharging) contentBuilder.append(" ⚡")
            }

            val content = contentBuilder.toString()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 创建通知渠道
            val channelId = "OppoHeadset$address"
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    alias,
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            // 创建断开连接 Action
            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                val bundle = Bundle().apply { putParcelable("Device", device) }
                putExtra("btData", bundle)
                putExtra("disconnect", "1")
                identifier = "BTHeadset$address"
            }
            val disconnectPendingIntent = PendingIntent.getBroadcast(
                context, 0, disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val disconnectAction = Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.stat_sys_data_bluetooth),
                "断开",
                disconnectPendingIntent
            ).build()

            // 创建点击 Intent
            val clickIntent = Intent().apply {
                setClassName(Constants.PKG_NAME_HEYTAP, "com.heytap.headset.ui.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val clickPendingIntent = PendingIntent.getActivity(
                context, 0, clickIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

            // 创建删除 Intent
            val deleteIntent = Intent("com.android.bluetooth.headset.notification.cancle").apply {
                putExtra("android.bluetooth.device.extra.DEVICE", device)
            }
            val deletePendingIntent = PendingIntent.getBroadcast(
                context, 0, deleteIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 构建通知
            val extras = Bundle().apply {
                putBoolean("miui.showAction", true)
            }

            val notification = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setWhen(0L)
                .setTicker(alias)
                .setContentTitle(alias)
                .setContentText(content)
                .setContentIntent(clickPendingIntent)
                .setDeleteIntent(deletePendingIntent)
                .setExtras(extras)
                .addAction(disconnectAction)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()

            // 使用 notifyAsUser 发送通知
            try {
                val userAll = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL") as UserHandle
                XposedHelpers.callMethod(
                    notificationManager,
                    "notifyAsUser",
                    "BTHeadset$address",
                    10003,
                    notification,
                    userAll
                )
            } catch (e: Throwable) {
                // Fallback: 使用普通 notify
                notificationManager.notify("BTHeadset$address", 10003, notification)
            }

            Log.d(TAG, "Notification created for $alias")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to create notification: ${e.message}")
        }
    }

    /**
     * 取消通知
     */
    private fun cancelNotification(context: Context, device: BluetoothDevice) {
        try {
            val address = device.address
            if (address.isNotEmpty()) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                try {
                    val userAll = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL") as UserHandle
                    XposedHelpers.callMethod(
                        notificationManager,
                        "cancelAsUser",
                        "BTHeadset$address",
                        10003,
                        userAll
                    )
                } catch (e: Throwable) {
                    notificationManager.cancel("BTHeadset$address", 10003)
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to cancel notification: ${e.message}")
        }
    }

    /**
     * 发送日志到主界面
     */
    private fun sendLog(message: String) {
        try {
            val context = appContext ?: return
            Intent(Constants.Action.OPPO_LOG).apply {
                putExtra("log", "[MiBT] $message")
                putExtra("time", System.currentTimeMillis())
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            // 忽略
        }
    }
}
