package eu.chylek.fakenettype;

import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_LoadPackage;

/**
 * FakeNetType - Xposed/LSPosed 模块
 * 功能：1. 伪造网络类型（以太网/WiFi/移动数据/VPN/蓝牙）
 *       2. 伪造网速（骗过测速软件）
 *       支持全局或按应用配置
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeNetType";
    private static final String MODULE_PKG = "eu.chylek.fakenettype";

    // 网络类型常量（兼容低版本）
    private static final int TYPE_WIFI = 1;
    private static final int TYPE_MOBILE = 0;
    private static final int TYPE_ETHERNET = 9;
    private static final int TYPE_VPN = 17;
    private static final int TYPE_BLUETOOTH = 7;

    // 虚假网速起始时间戳
    private static final long T0 = System.currentTimeMillis();

    // 按UID缓存虚假字节数，模拟递增
    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> sFakeBytes = new java.util.concurrent.ConcurrentHashMap<>();

    // ===== 从 XSharedPreferences 读配置 =====
    private boolean isGlobalEnable(XSharedPreferences prefs) {
        return prefs != null && prefs.getBoolean("global_enable", true);
    }

    private Set<String> getTargetPackages(XSharedPreferences prefs) {
        if (prefs == null) return new HashSet<>();
        return prefs.getStringSet("target_packages", new HashSet<>());
    }

    private int getFakeType(XSharedPreferences prefs) {
        return prefs == null ? TYPE_ETHERNET : prefs.getInt("fake_type", TYPE_ETHERNET);
    }

    private boolean isFakeSpeed(XSharedPreferences prefs) {
        return prefs != null && prefs.getBoolean("fake_speed_enable", false);
    }

    private int getFakeUpSpeed(XSharedPreferences prefs) {
        return prefs == null ? 50 : prefs.getInt("fake_up_speed", 50);
    }

    private int getFakeDownSpeed(XSharedPreferences prefs) {
        return prefs == null ? 100 : prefs.getInt("fake_down_speed", 100);
    }

    private boolean isDebug(XSharedPreferences prefs) {
        return prefs != null && prefs.getBoolean("debug_log", false);
    }

    private boolean shouldFake(String pkg, XSharedPreferences prefs) {
        if (MODULE_PKG.equals(pkg)) return false;
        if (isGlobalEnable(prefs)) return true;
        return getTargetPackages(prefs).contains(pkg);
    }

    // ===== 构造虚假 NetworkInfo =====
    private NetworkInfo createFakeNetworkInfo(int fakeType) {
        try {
            // 使用 NetworkInfo(int type, int subtype, String typeName, String subtypeName)
            NetworkInfo info = new NetworkInfo(fakeType, 0,
                    typeIntToName(fakeType), subtypeIntToName(fakeType));
            // 设置连接状态（通过反射兼容各版本）
            XposedHelpers.setIntField(info, "mState", NetworkInfo.State.CONNECTED.ordinal());
            // 1 = CONNECTED
            XposedHelpers.setBooleanField(info, "mIsConnected", true);
            XposedHelpers.setBooleanField(info, "mIsAvailable", true);
            return info;
        } catch (Throwable t) {
            Log.e(TAG, "createFakeNetworkInfo error", t);
            return null;
        }
    }

    private String typeIntToName(int type) {
        switch (type) {
            case TYPE_WIFI: return "WIFI";
            case TYPE_MOBILE: return "MOBILE";
            case TYPE_ETHERNET: return "ETHERNET";
            case TYPE_VPN: return "VPN";
            case TYPE_BLUETOOTH: return "BLUETOOTH";
            default: return "ETHERNET";
        }
    }

    private String subtypeIntToName(int type) {
        if (type == TYPE_ETHERNET) return "Ethernet";
        if (type == TYPE_WIFI) return "WiFi";
        return "";
    }

    // ===== 构造虚假 NetworkCapabilities (Android 6+) =====
    private Object createFakeCapabilities(int fakeType, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ncClass = XposedHelpers.findClass("android.net.NetworkCapabilities", lpparam.classLoader);
            Object caps = XposedHelpers.newInstance(ncClass);
            // addTransportType(int transportType)
            int transport = transportFromFakeType(fakeType);
            XposedHelpers.callMethod(caps, "addTransportType", transport);
            // addCapability
            XposedHelpers.callMethod(caps, "addCapability", NetworkCapabilities.NET_CAPABILITY_INTERNET);
            XposedHelpers.callMethod(caps, "addCapability", NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            XposedHelpers.callMethod(caps, "addCapability", NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            return caps;
        } catch (Throwable t) {
            Log.e(TAG, "createFakeCapabilities error", t);
            return null;
        }
    }

    private int transportFromFakeType(int fakeType) {
        switch (fakeType) {
            case TYPE_WIFI: return 1;       // TRANSPORT_WIFI
            case TYPE_ETHERNET: return 3;   // TRANSPORT_ETHERNET
            case TYPE_VPN: return 4;        // TRANSPORT_VPN
            case TYPE_BLUETOOTH: return 2; // TRANSPORT_BLUETOOTH
            default: return 0;              // TRANSPORT_CELLULAR
        }
    }

    // ===== 虚假网速计算 =====
    private long calcFakeBytes(long speedMbps, boolean isTx, int uid) {
        // speedMbps 为单位，转换为 bytes/ms
        double bytesPerMs = (speedMbps * 1024.0 * 1024.0) / 8000.0;
        long elapsedMs = System.currentTimeMillis() - T0;
        // ±10% 波动，模拟真实网速
        double jitter = 0.9 + Math.random() * 0.2;
        long fakeBytes = (long) (bytesPerMs * elapsedMs * jitter);
        // 保证单调递增
        long[] arr = sFakeBytes.get(uid);
        if (arr == null) {
            sFakeBytes.put(uid, new long[]{isTx ? fakeBytes : 0, isTx ? 0 : fakeBytes});
            return fakeBytes;
        }
        if (isTx) {
            if (fakeBytes < arr[0]) fakeBytes = arr[0] + 1024;
            arr[0] = fakeBytes;
        } else {
            if (fakeBytes < arr[1]) fakeBytes = arr[1] + 1024;
            arr[1] = fakeBytes;
        }
        return fakeBytes;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (MODULE_PKG.equals(pkg)) return;

        // 使用 XSharedPreferences（LSPosed 支持）
        XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, "config");
        prefs.makeWorldReadable();

        if (!shouldFake(pkg, prefs)) return;

        final int fakeType = getFakeType(prefs);
        final boolean fakeSpeed = isFakeSpeed(prefs);
        final int fakeUpMbps = getFakeUpSpeed(prefs);
        final int fakeDownMbps = getFakeDownSpeed(prefs);
        final boolean debug = isDebug(prefs);

        if (debug) {
            XposedBridge.log(TAG + " | package loaded: " + pkg + " | fakeType=" + fakeType + " | fakeSpeed=" + fakeSpeed);
        }

        // ===== Hook 1: getActiveNetworkInfo (Android 10以下主要) =====
        try {
            Class<?> cm = XposedHelpers.findClass("android.net.ConnectivityManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(cm, "getActiveNetworkInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    NetworkInfo fake = createFakeNetworkInfo(fakeType);
                    if (fake != null) {
                        param.setResult(fake);
                        if (debug) XposedBridge.log(TAG + " | " + pkg + " | getActiveNetworkInfo → " + typeIntToName(fakeType));
                    }
                }
            });
        } catch (Throwable t) {
            if (debug) XposedBridge.log(TAG + " | hook getActiveNetworkInfo failed: " + t.getMessage());
        }

        // ===== Hook 2: getNetworkInfo(int) (Android 10以下) =====
        try {
            Class<?> cm = XposedHelpers.findClass("android.net.ConnectivityManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(cm, "getNetworkInfo", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    NetworkInfo fake = createFakeNetworkInfo(fakeType);
                    if (fake != null) {
                        param.setResult(fake);
                    }
                }
            });
        } catch (Throwable t) {}

        // ===== Hook 3: getActiveNetwork (Android 6+) =====
        try {
            Class<?> cm = XposedHelpers.findClass("android.net.ConnectivityManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(cm, "getActiveNetwork", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 返回 Network(1) 作为假对象
                    try {
                        Object fakeNet = XposedHelpers.newInstance(Network.class, 1);
                        param.setResult(fakeNet);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}

        // ===== Hook 4: getNetworkCapabilities (Android 6+) =====
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Class<?> cm = XposedHelpers.findClass("android.net.ConnectivityManager", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(cm, "getNetworkCapabilities",
                        Network.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object fakeCaps = createFakeCapabilities(fakeType, lpparam);
                        if (fakeCaps != null) {
                            param.setResult(fakeCaps);
                            if (debug) XposedBridge.log(TAG + " | " + pkg + " | getNetworkCapabilities → faked");
                        }
                    }
                });
            } catch (Throwable t) {}
        }

        // ===== Hook 5: getAllNetworks =====
        try {
            Class<?> cm = XposedHelpers.findClass("android.net.ConnectivityManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(cm, "getAllNetworks", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object fakeNet = XposedHelpers.newInstance(Network.class, 1);
                        param.setResult(new Network[]{ (Network) fakeNet });
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}

        // ===== Hook 6: NetworkInfo.isConnected / getType (防御性Hook) =====
        try {
            XposedHelpers.findAndHookMethod(NetworkInfo.class, "getType", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 只Hook我们伪造出来的对象（通过判断调用栈）
                    param.setResult(fakeType);
                }
            });
        } catch (Throwable t) {}

        // ===== Hook 7: TrafficStats - 虚假网速 =====
        if (fakeSpeed) {
            hookTrafficStats(lpparam, pkg, fakeUpMbps, fakeDownMbps, debug);
        }

        if (debug) {
            XposedBridge.log(TAG + " | all hooks installed for: " + pkg);
        }
    }

    private void hookTrafficStats(XC_LoadPackage.LoadPackageParam lpparam, String pkg,
                                  int upMbps, int downMbps, boolean debug) {
        try {
            Class<?> ts = XposedHelpers.findClass("android.net.TrafficStats", lpparam.classLoader);

            // getTotalRxBytes
            XposedHelpers.findAndHookMethod(ts, "getTotalRxBytes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long fake = calcFakeBytes(downMbps, false, -1);
                    param.setResult(fake);
                }
            });

            // getTotalTxBytes
            XposedHelpers.findAndHookMethod(ts, "getTotalTxBytes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    long fake = calcFakeBytes(upMbps, true, -1);
                    param.setResult(fake);
                }
            });

            // getUidRxBytes (Android 8以下，高版本已废弃但部分测速APP仍用反射调用)
            try {
                XposedHelpers.findAndHookMethod(ts, "getUidRxBytes", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = (int) param.args[0];
                        long fake = calcFakeBytes(downMbps, false, uid);
                        param.setResult(fake);
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(ts, "getUidTxBytes", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = (int) param.args[0];
                        long fake = calcFakeBytes(upMbps, true, uid);
                        param.setResult(fake);
                    }
                });
            } catch (Throwable ignored) {}

            if (debug) {
                XposedBridge.log(TAG + " | " + pkg + " | TrafficStats hooks installed (up=" + upMbps + "Mbps, down=" + downMbps + "Mbps)");
            }
        } catch (Throwable t) {
            if (debug) XposedBridge.log(TAG + " | TrafficStats hook failed: " + t.getMessage());
        }
    }
}
