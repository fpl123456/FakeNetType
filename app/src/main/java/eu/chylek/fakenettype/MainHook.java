package eu.chylek.fakenettype;

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
 * 兼容 Android 6 - Android 16 (API 23-36)
 * 主要策略：Hook NetworkCapabilities（Android 6+ 主流 API）
 *           同时保留 NetworkInfo hook 以兼容旧 App
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeNetType";
    private static final String MODULE_PKG = "eu.chylek.fakenettype";

    // 网络类型常量
    private static final int TYPE_ETHERNET = 9;
    private static final int TYPE_WIFI = 1;
    private static final int TYPE_MOBILE = 0;
    private static final int TYPE_VPN = 17;
    private static final int TYPE_BLUETOOTH = 7;

    // Transport 类型（对应 NetworkCapabilities 常量）
    private static final int TRANSPORT_WIFI = 1;
    private static final int TRANSPORT_CELLULAR = 0;
    private static final int TRANSPORT_ETHERNET = 3;
    private static final int TRANSPORT_BLUETOOTH = 2;
    private static final int TRANSPORT_VPN = 4;

    // 虚假网速起始时间戳
    private static final long T0 = System.currentTimeMillis();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> sFakeBytes =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ===== 配置读取 =====
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

    private int transportFromFakeType(int fakeType) {
        switch (fakeType) {
            case TYPE_WIFI:      return TRANSPORT_WIFI;
            case TYPE_ETHERNET:   return TRANSPORT_ETHERNET;
            case TYPE_VPN:        return TRANSPORT_VPN;
            case TYPE_BLUETOOTH:  return TRANSPORT_BLUETOOTH;
            default:              return TRANSPORT_CELLULAR;
        }
    }

    private String typeIntToName(int type) {
        switch (type) {
            case TYPE_WIFI:      return "WIFI";
            case TYPE_MOBILE:    return "MOBILE";
            case TYPE_ETHERNET:  return "ETHERNET";
            case TYPE_VPN:       return "VPN";
            case TYPE_BLUETOOTH: return "BLUETOOTH";
            default:             return "ETHERNET";
        }
    }

    // ===== 构造虚假 NetworkCapabilities（Android 6+ 核心）=====
    private Object createFakeCapabilities(int fakeType, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ncClass = XposedHelpers.findClass("android.net.NetworkCapabilities",
                    lpparam.classLoader);
            Object caps;

            // Android 13+ 用 Builder；低版本直接 new + 反射调方法
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+)：NetworkCapabilities.Builder
                Class<?> builderClass = XposedHelpers.findClass(
                        "android.net.NetworkCapabilities$Builder", lpparam.classLoader);
                Object builder = XposedHelpers.newInstance(builderClass);
                int transport = transportFromFakeType(fakeType);
                XposedHelpers.callMethod(builder, "addTransportType", transport);
                XposedHelpers.callMethod(builder, "setCapability",
                        NetworkCapabilities.NET_CAPABILITY_INTERNET);
                XposedHelpers.callMethod(builder, "setCapability",
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                caps = XposedHelpers.callMethod(builder, "build");
            } else {
                // Android 6-12：直接构造 + 反射调 addTransportType / addCapability
                caps = XposedHelpers.newInstance(ncClass);
                int transport = transportFromFakeType(fakeType);
                // addTransportType(int)
                XposedHelpers.callMethod(caps, "addTransportType", transport);
                // addCapability(int) — 用反射兼容不同版本
                try {
                    XposedHelpers.callMethod(caps, "addCapability",
                            NetworkCapabilities.NET_CAPABILITY_INTERNET);
                } catch (Throwable t1) {
                    // Android 6-9 用 setCapability 或不可访问，跳过
                }
            }
            return caps;
        } catch (Throwable t) {
            Log.e(TAG, "createFakeCapabilities error", t);
            return null;
        }
    }

    // ===== 构造虚假 NetworkInfo（兼容旧 App，Android 9- 可用）=====
    private Object createFakeNetworkInfo(int fakeType) {
        // Android 9+ 移除了 NetworkInfo 构造，尝试用 Parcel 反序列化
        try {
            Object info = createNetworkInfoViaParcel(fakeType);
            if (info != null) return info;
        } catch (Throwable ignored) {}
        // 降级：直接 new（Android 8 及以下有效）
        try {
            return XposedHelpers.newInstance(
                    NetworkInfo.class, fakeType, 0,
                    typeIntToName(fakeType), "");
        } catch (Throwable t) {
            Log.e(TAG, "createFakeNetworkInfo failed, skip NetworkInfo hooks", t);
            return null;
        }
    }

    // 用 Parcel 创建 NetworkInfo（Android 9+ 兼容）
    private Object createNetworkInfoViaParcel(int fakeType) throws Throwable {
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            p.writeInt(fakeType);           // type
            p.writeInt(0);                  // subtype
            p.writeString(typeIntToName(fakeType)); // typeName
            p.writeString("");               // subtypeName
            p.writeInt(2);                  // state: CONNECTED (ordinal=2)
            p.writeInt(1);                  // mIsConnected = true
            p.writeInt(1);                  // mIsAvailable = true
            p.writeInt(1);                  // mIsFailover = false
            p.writeInt(0);                  // mReason (null → 0)
            p.writeInt(0);                  // mExtraInfo (null → 0)
            p.setDataPosition(0);
            Object info = XposedHelpers.callStaticMethod(
                    NetworkInfo.class, "CREATOR").getClass()
                    .getMethod("createFromParcel", android.os.Parcel.class)
                    .invoke(null, p);
            return info;
        } finally {
            p.recycle();
        }
    }

    // ===== 虚假网速 =====
    private long calcFakeBytes(long speedMbps, boolean isTx, int uid) {
        double bytesPerMs = (speedMbps * 1024.0 * 1024.0) / 8000.0;
        long elapsedMs = System.currentTimeMillis() - T0;
        double jitter = 0.9 + Math.random() * 0.2;
        long fakeBytes = (long) (bytesPerMs * elapsedMs * jitter);
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

        XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, "config");
        prefs.makeWorldReadable();

        if (!shouldFake(pkg, prefs)) return;

        final int fakeType = getFakeType(prefs);
        final boolean fakeSpeed = isFakeSpeed(prefs);
        final int fakeUpMbps = getFakeUpSpeed(prefs);
        final int fakeDownMbps = getFakeDownSpeed(prefs);
        final boolean debug = isDebug(prefs);

        if (debug) {
            XposedBridge.log(TAG + " | package loaded: " + pkg
                    + " | fakeType=" + typeIntToName(fakeType)
                    + " | fakeSpeed=" + fakeSpeed);
        }

        Class<?> cmClass = XposedHelpers.findClass(
                "android.net.ConnectivityManager", lpparam.classLoader);

        // ===== Hook A：getActiveNetwork + getNetworkCapabilities（Android 6+ 主流）=====
        // 这是 Android 10+ App 获取网络信息的主要路径
        try {
            XposedHelpers.findAndHookMethod(cmClass, "getActiveNetwork",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fakeNet = XposedHelpers.newInstance(Network.class, 1);
                                param.setResult(fakeNet);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            if (debug) XposedBridge.log(TAG + " | hook getActiveNetwork failed: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(cmClass, "getNetworkCapabilities",
                    Network.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object fakeCaps = createFakeCapabilities(fakeType, lpparam);
                            if (fakeCaps != null) {
                                param.setResult(fakeCaps);
                                if (debug) XposedBridge.log(TAG + " | " + pkg
                                        + " | getNetworkCapabilities → faked");
                            }
                        }
                    });
        } catch (Throwable t) {
            if (debug) XposedBridge.log(TAG + " | hook getNetworkCapabilities failed: " + t.getMessage());
        }

        // ===== Hook B：getAllNetworks =====
        try {
            XposedHelpers.findAndHookMethod(cmClass, "getAllNetworks",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fakeNet = XposedHelpers.newInstance(Network.class, 1);
                                param.setResult(new Network[]{(Network) fakeNet});
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {}

        // ===== Hook C：getActiveNetworkInfo（兼容旧 App）=====
        // Android 16 上此方法可能已被移除或返回 null，但部分旧 App 仍调用
        try {
            XposedHelpers.findAndHookMethod(cmClass, "getActiveNetworkInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object fake = createFakeNetworkInfo(fakeType);
                            if (fake != null) {
                                param.setResult(fake);
                                if (debug) XposedBridge.log(TAG + " | " + pkg
                                        + " | getActiveNetworkInfo → " + typeIntToName(fakeType));
                            }
                        }
                    });
        } catch (Throwable t) {
            if (debug) XposedBridge.log(TAG + " | hook getActiveNetworkInfo failed: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(cmClass, "getNetworkInfo",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object fake = createFakeNetworkInfo(fakeType);
                            if (fake != null) {
                                param.setResult(fake);
                            }
                        }
                    });
        } catch (Throwable t) {}

        // ===== Hook D：TrafficStats 虚假网速 =====
        if (fakeSpeed) {
            hookTrafficStats(lpparam, pkg, fakeUpMbps, fakeDownMbps, debug);
        }

        if (debug) {
            XposedBridge.log(TAG + " | all hooks installed for: " + pkg);
        }
    }

    private void hookTrafficStats(XC_LoadPackage.LoadPackageParam lpparam,
                                  String pkg, int upMbps, int downMbps, boolean debug) {
        try {
            Class<?> ts = XposedHelpers.findClass(
                    "android.net.TrafficStats", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(ts, "getTotalRxBytes",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(calcFakeBytes(downMbps, false, -1));
                        }
                    });

            XposedHelpers.findAndHookMethod(ts, "getTotalTxBytes",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(calcFakeBytes(upMbps, true, -1));
                        }
                    });

            // getUidRx/TxBytes（部分测速 App 用反射调用）
            try {
                XposedHelpers.findAndHookMethod(ts, "getUidRxBytes",
                        int.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                int uid = (int) param.args[0];
                                param.setResult(calcFakeBytes(downMbps, false, uid));
                            }
                        });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(ts, "getUidTxBytes",
                        int.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                int uid = (int) param.args[0];
                                param.setResult(calcFakeBytes(upMbps, true, uid));
                            }
                        });
            } catch (Throwable ignored) {}

            if (debug) {
                XposedBridge.log(TAG + " | " + pkg
                        + " | TrafficStats hooks installed (up=" + upMbps
                        + "Mbps, down=" + downMbps + "Mbps)");
            }
        } catch (Throwable t) {
            if (debug) XposedBridge.log(TAG + " | TrafficStats hook failed: " + t.getMessage());
        }
    }
}
