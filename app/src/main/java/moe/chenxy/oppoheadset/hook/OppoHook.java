package moe.chenxy.oppoheadset.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.RemoteViews;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * OPPO 耳机 Hook 模块 (Java 版本)
 *
 * 目标应用: com.heytap.headset (欢律 App)
 *
 * 功能:
 * 1. Hook KeepAliveFgService.d 方法获取电量和 MAC 地址
 * 2. 接收 SystemUI 控制指令，反射调用控制中心切换降噪模式
 * 3. 通过广播向 SystemUI 发送电量信息
 */
public class OppoHook implements IXposedHookLoadPackage {

    private static final String TAG = "OppoHook";

    // 包名常量
    private static final String PKG_HEYTAP = "com.heytap.headset";
    private static final String PKG_SYSTEMUI = "com.android.systemui";

    // 广播 Action
    private static final String ACTION_BATTERY_UPDATE = "com.xiaomi.controlcenter.OPPO_BATTERY_UPDATE";
    private static final String ACTION_SWITCH_MODE = "com.xiaomi.controlcenter.OPPO_ACTION_SWITCH_MODE";

    // Hook 目标类名
    private static final String CLASS_KEEP_ALIVE_SERVICE = "com.heytap.headset.service.KeepAliveFgService";
    private static final String CLASS_CONTROL_CENTER = "com.oplus.melody.model.repository.earphone.AbstractC0772b";

    // 状态变量
    private static volatile String currentMacAddress = "";
    private static volatile Object controlCenterInstance = null;
    private static volatile Context appContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_HEYTAP.equals(lpparam.packageName)) {
            return;
        }

        Log.i(TAG, "Hooking " + PKG_HEYTAP);

        // Hook Application.onCreate 获取 Context
        hookApplicationOnCreate(lpparam);

        // Hook KeepAliveFgService.d 获取电量数据
        hookKeepAliveFgService(lpparam);

        // 获取控制中心单例
        hookControlCenterSingleton(lpparam);
    }

    /**
     * Hook Application.onCreate 以获取 Context 并注册广播接收器
     */
    private void hookApplicationOnCreate(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Context app = (Context) param.thisObject;
                            appContext = app;
                            Log.i(TAG, "Application context obtained");

                            // 注册广播接收器
                            registerControlReceiver(app);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook Application.onCreate: " + t.getMessage());
        }
    }

    /**
     * 注册广播接收器，接收 SystemUI 发来的控制指令
     */
    private void registerControlReceiver(Context context) {
        HandlerThread handlerThread = new HandlerThread("oppo_headset_receiver");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_SWITCH_MODE.equals(intent.getAction())) {
                    int mode = intent.getIntExtra("mode", 0);
                    Log.i(TAG, "Received control command: switch mode to " + mode);

                    // 执行降噪模式切换
                    switchAncMode(mode);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_SWITCH_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, null, handler);
        }

        Log.i(TAG, "Control receiver registered");
    }

    /**
     * Hook KeepAliveFgService.d 方法获取电量和 MAC 地址
     *
     * 方法签名: d(RemoteViews views, Object dataModel)
     * dataModel 是混淆类 (原名可能是 l4.C1150b)
     */
    private void hookKeepAliveFgService(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> serviceClass = XposedHelpers.findClass(CLASS_KEEP_ALIVE_SERVICE, lpparam.classLoader);

            // 使用 Object.class 作为第二个参数类型（处理混淆类）
            XposedHelpers.findAndHookMethod(
                    serviceClass,
                    "d",
                    RemoteViews.class,
                    Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object dataModel = param.args[1];
                            if (dataModel == null) return;

                            try {
                                // 提取电量数据
                                BatteryData data = extractBatteryData(dataModel);
                                if (data != null) {
                                    // 发送广播给 SystemUI
                                    sendBatteryBroadcast(data);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": Error extracting battery data: " + t.getMessage());
                            }
                        }
                    }
            );

            Log.i(TAG, "KeepAliveFgService.d hooked successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook KeepAliveFgService.d: " + t.getMessage());
            // 尝试备用 Hook 方案
            tryAlternativeHook(lpparam);
        }
    }

    /**
     * 备用 Hook 方案 - 遍历所有名为 d 的方法
     */
    private void tryAlternativeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> serviceClass = XposedHelpers.findClass(CLASS_KEEP_ALIVE_SERVICE, lpparam.classLoader);

            for (Method method : serviceClass.getDeclaredMethods()) {
                if ("d".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    Class<?> firstParam = method.getParameterTypes()[0];
                    if (RemoteViews.class.isAssignableFrom(firstParam) ||
                            firstParam.getName().contains("RemoteViews")) {

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object dataModel = param.args[1];
                                if (dataModel == null) return;

                                try {
                                    BatteryData data = extractBatteryData(dataModel);
                                    if (data != null) {
                                        sendBatteryBroadcast(data);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": Error in alternative hook: " + t.getMessage());
                                }
                            }
                        });

                        Log.i(TAG, "Alternative hook applied");
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Alternative hook also failed: " + t.getMessage());
        }
    }

    /**
     * 从数据模型中提取电量数据
     *
     * 反射调用:
     * - getAddress() -> MAC 地址
     * - isSpp() -> 连接类型
     * - getLeftBattery() / getHeadsetLeftBattery() -> 左耳电量
     * - getRightBattery() / getHeadsetRightBattery() -> 右耳电量
     * - getBoxBattery() / getHeadsetBoxBattery() -> 盒子电量
     */
    private BatteryData extractBatteryData(Object dataModel) {
        try {
            // 获取 MAC 地址
            String mac = invokeMethod(dataModel, "getAddress", "");
            if (mac != null && !mac.isEmpty()) {
                currentMacAddress = mac;
            }

            // 获取连接类型
            boolean isSpp = invokeMethod(dataModel, "isSpp", false);

            // 获取电量 - 尝试多种方法名
            int leftBattery = getBatteryValue(dataModel, "getLeftBattery", "getHeadsetLeftBattery");
            int rightBattery = getBatteryValue(dataModel, "getRightBattery", "getHeadsetRightBattery");
            int boxBattery = getBatteryValue(dataModel, "getBoxBattery", "getHeadsetBoxBattery");

            Log.d(TAG, "Battery: L=" + leftBattery + " R=" + rightBattery + " B=" + boxBattery + " MAC=" + currentMacAddress);

            return new BatteryData(leftBattery, rightBattery, boxBattery, currentMacAddress, isSpp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": extractBatteryData error: " + t.getMessage());
            return null;
        }
    }

    /**
     * 尝试多个方法名获取电量值
     */
    private int getBatteryValue(Object obj, String... methodNames) {
        for (String name : methodNames) {
            Object result = invokeMethodSafe(obj, name);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
        }
        return -1;
    }

    /**
     * 安全调用反射方法
     */
    @SuppressWarnings("unchecked")
    private <T> T invokeMethod(Object obj, String methodName, T defaultValue) {
        Object result = invokeMethodSafe(obj, methodName);
        if (result != null) {
            try {
                return (T) result;
            } catch (ClassCastException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 安全调用反射方法（无默认值）
     */
    private Object invokeMethodSafe(Object obj, String methodName) {
        try {
            Method method = obj.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(obj);
        } catch (NoSuchMethodException e) {
            // 尝试 getDeclaredMethod
            try {
                Method method = obj.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(obj);
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 发送电量广播给 SystemUI
     */
    private void sendBatteryBroadcast(BatteryData data) {
        Context context = appContext;
        if (context == null) return;

        Intent intent = new Intent(ACTION_BATTERY_UPDATE);
        intent.putExtra("left", data.leftBattery);
        intent.putExtra("right", data.rightBattery);
        intent.putExtra("box", data.boxBattery);
        intent.putExtra("mac", data.macAddress);
        intent.putExtra("isSpp", data.isSpp);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setPackage(PKG_SYSTEMUI);

        context.sendBroadcast(intent);
        Log.d(TAG, "Battery broadcast sent");
    }

    /**
     * Hook 控制中心单例类获取实例
     *
     * 类名: com.oplus.melody.model.repository.earphone.AbstractC0772b
     * 单例方法: H()
     */
    private void hookControlCenterSingleton(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> singletonClass = XposedHelpers.findClass(CLASS_CONTROL_CENTER, lpparam.classLoader);

            // Hook H() 方法获取单例实例
            XposedHelpers.findAndHookMethod(
                    singletonClass,
                    "H",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            controlCenterInstance = param.getResult();
                            Log.d(TAG, "Control center instance obtained");
                        }
                    }
            );

            Log.i(TAG, "Control center singleton hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook control center: " + t.getMessage());
            // 尝试查找混淆后的类
            tryFindControlCenterClass(lpparam);
        }
    }

    /**
     * 尝试查找控制中心类（处理混淆情况）
     */
    private void tryFindControlCenterClass(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] possibleClassNames = {
                CLASS_CONTROL_CENTER,
                "l4.b",
                "com.oplus.melody.model.repository.earphone.b"
        };

        for (String className : possibleClassNames) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);

                // 查找返回自身类型的静态无参方法（单例模式）
                for (Method method : clazz.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) &&
                            method.getParameterTypes().length == 0 &&
                            method.getReturnType() == clazz) {

                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                controlCenterInstance = param.getResult();
                                Log.d(TAG, "Control center instance found");
                            }
                        });

                        Log.i(TAG, "Found control center class: " + className);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 切换降噪模式
     *
     * 调用方法: n0(int mode, String macAddress)
     * 模式代码: 0=关闭, 1=通透, 4=强降噪
     */
    private void switchAncMode(int mode) {
        Object instance = controlCenterInstance;
        if (instance == null) {
            Log.w(TAG, "Control center instance is null");
            tryGetControlCenterInstance();
            return;
        }

        if (currentMacAddress == null || currentMacAddress.isEmpty()) {
            Log.w(TAG, "MAC address is empty");
            return;
        }

        try {
            Class<?> clazz = instance.getClass();

            // 尝试找到 n0 方法
            Method method = null;
            try {
                method = clazz.getMethod("n0", int.class, String.class);
            } catch (NoSuchMethodException e) {
                // 尝试查找其他可能的方法
                for (Method m : clazz.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 &&
                            params[0] == int.class &&
                            params[1] == String.class) {
                        method = m;
                        break;
                    }
                }
            }

            if (method != null) {
                method.setAccessible(true);
                method.invoke(instance, mode, currentMacAddress);
                Log.i(TAG, "ANC mode switched to " + mode + " for " + currentMacAddress);
            } else {
                XposedBridge.log(TAG + ": Cannot find method to switch ANC mode");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error switching ANC mode: " + t.getMessage());
        }
    }

    /**
     * 尝试获取控制中心单例实例
     */
    private void tryGetControlCenterInstance() {
        Context context = appContext;
        if (context == null) return;

        String[] possibleClassNames = {CLASS_CONTROL_CENTER, "l4.b"};

        for (String className : possibleClassNames) {
            try {
                Class<?> clazz = Class.forName(className, true, context.getClassLoader());

                // 尝试调用 H() 方法
                try {
                    Method hMethod = clazz.getMethod("H");
                    controlCenterInstance = hMethod.invoke(null);
                    if (controlCenterInstance != null) {
                        Log.i(TAG, "Got control center instance via H()");
                        return;
                    }
                } catch (NoSuchMethodException e) {
                    // 查找其他单例获取方法
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (Modifier.isStatic(method.getModifiers()) &&
                                method.getParameterTypes().length == 0 &&
                                method.getReturnType() == clazz) {

                            method.setAccessible(true);
                            controlCenterInstance = method.invoke(null);
                            if (controlCenterInstance != null) {
                                Log.i(TAG, "Got control center instance via " + method.getName());
                                return;
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Error getting control center: " + t.getMessage());
            }
        }
    }

    /**
     * 电量数据内部类
     */
    private static class BatteryData {
        final int leftBattery;
        final int rightBattery;
        final int boxBattery;
        final String macAddress;
        final boolean isSpp;

        BatteryData(int left, int right, int box, String mac, boolean spp) {
            this.leftBattery = left;
            this.rightBattery = right;
            this.boxBattery = box;
            this.macAddress = mac;
            this.isSpp = spp;
        }
    }
}
