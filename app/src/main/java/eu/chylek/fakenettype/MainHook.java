package eu.chylek.fakenettype;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 *
 * Android 16 核心策略：
 * 1. Hook ITelephony Binder（getDataNetworkType 等）
 * 2. Hook ConnectivityManager 内部实现（IContentProvider 代理）
 * 3. Hook NetworkCapabilities.getTransportTypes（返回假值）
 * 4. 保留 NetworkInfo hook（兼容旧 App）
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeNetType";
    private static final String MODULE_PKG = "eu.chylek.fakenettype";

    // 网络类型常量（ConnectivityManager.TYPE_*）
    private static final int TYPE_MOBILE    = 0;
    private static final int TYPE_WIFI      = 1;
    private static final int TYPE_ETHERNET  = 9;
    private static final int TYPE_VPN       = 17;
    private static final int TYPE_BLUETOOTH = 7;

    // Transport 类型（对应 NetworkCapabilities）
    private static final int TRANSPORT_CELLULAR   = 0;
    private static final int TRANSPORT_WIFI       = 1;
    private static final int TRANSPORT_BLUETOOTH  = 2;
    private static final int TRANSPORT_ETHERNET   = 3;
    private static final int TRANSPORT_VPN        = 4;

    // 网速起始时间戳
    private static final long T0 = System.currentTimeMillis();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> sFakeBytes =
            new java.util.concurrent.ConcurrentHashMap<>();

    // 当前 fake 配置（避免每次反射读 SharedPreferences）
    private static volatile int sFakeType = TYPE_ETHERNET;
    private static volatile int sFakeUpMbps = 50;
    private static volatile int sFakeDownMbps = 100;
    private static volatile boolean sFakeSpeed = false;
    private static volatile boolean sDebug = false;

    // ===== 配置读取 =====
    private boolean isGlobalEnable(XSharedPreferences prefs) {
        return prefs != null && prefs.getBoolean("global_enable", true);
    }

    private Set<String> getTargetPackages(XSharedPreferences prefs) {
        if (prefs == null) return new HashSet<>();
        Set<String> set = prefs.getStringSet("target_packages", new HashSet<>());
        return set == null ? new HashSet<>() : set;
    }

    private boolean shouldFake(String pkg, XSharedPreferences prefs) {
        if (MODULE_PKG.equals(pkg)) return false;
        if (isGlobalEnable(prefs)) return true;
        return getTargetPackages(prefs).contains(pkg);
    }

    private void syncConfig(XSharedPreferences prefs) {
        if (prefs == null) return;
        sFakeType = prefs.getInt("fake_type", TYPE_ETHERNET);
        sFakeUpMbps = prefs.getInt("fake_up_speed", 50);
        sFakeDownMbps = prefs.getInt("fake_down_speed", 100);
        sFakeSpeed = prefs.getBoolean("fake_speed_enable", false);
        sDebug = prefs.getBoolean("debug_log", false);
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

    private int transportFromFakeType(int fakeType) {
        switch (fakeType) {
            case TYPE_WIFI:      return TRANSPORT_WIFI;
            case TYPE_ETHERNET:  return TRANSPORT_ETHERNET;
            case TYPE_VPN:       return TRANSPORT_VPN;
            case TYPE_BLUETOOTH: return TRANSPORT_BLUETOOTH;
            default:             return TRANSPORT_CELLULAR;
        }
    }

    // ===== 网速伪造 =====
    private long calcFakeBytes(long speedMbps, boolean isTx, int uid) {
        double bytesPerMs = (speedMbps * 1024.0 * 1024.0) / 8000.0;
        long elapsedMs = System.currentTimeMillis() - T0;
        double jitter = 0.9 + Math.random() * 0.2;
        long fakeBytes = (long) (bytesPerMs * elapsedMs * jitter);
        int key = (isTx ? 1 : 0) * 100000 + uid;
        long[] arr = sFakeBytes.get(key);
        if (arr == null) {
            sFakeBytes.put(key, new long[]{isTx ? fakeBytes : 0, isTx ? 0 : fakeBytes});
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

    // ===== Android 16 兼容：Parcel 反序列化创建 NetworkInfo =====
    private Object createNetworkInfoViaParcel(int fakeType) throws Throwable {
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            p.writeInt(fakeType);                   // mNetworkType
            p.writeInt(0);                          // mSubtype
            p.writeString(typeIntToName(fakeType)); // mTypeName
            p.writeString("");                      // mSubtypeName
            p.writeInt(2);                         // mState: CONNECTED (ordinal=2)
            p.writeInt(1);                         // mDetailedState
            p.writeString("");                      // mReason
            p.writeBoolean(false);                  // mIsFailover
            p.writeBoolean(true);                   // mIsConnected
            p.writeInt(0);                          // mInterfaceName (null → 0)
            p.writeInt(1);                          // mIsAvailable (true)
            p.writeInt(0);                          // mIsRoaming
            p.writeInt(0);                          // mIsMetered
            p.setDataPosition(0);
            Object creator = XposedHelpers.callStaticMethod(
                    NetworkInfo.class, "CREATOR");
            Class<?> creatorClass = creator.getClass();
            Method createFromParcel = null;
            for (Method m : creatorClass.getDeclaredMethods()) {
                if (m.getName().contains("createFromParcel") || m.getName().equals("createFromParcel")) {
                    createFromParcel = m;
                    break;
                }
            }
            if (createFromParcel == null) {
                createFromParcel = creatorClass.getMethod("createFromParcel", android.os.Parcel.class);
            }
            return createFromParcel.invoke(creator, p);
        } finally {
            p.recycle();
        }
    }

    private Object createFakeNetworkInfo(int fakeType) {
        try {
            return createNetworkInfoViaParcel(fakeType);
        } catch (Throwable t) {
            if (sDebug) Log.e(TAG, "createFakeNetworkInfo failed", t);
            return null;
        }
    }

    // ===== Android 16 兼容：创建 fake NetworkCapabilities =====
    private Object createFakeCapabilities() {
        try {
            int transport = transportFromFakeType(sFakeType);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+)
                Class<?> builderClass = XposedHelpers.findClass(
                        "android.net.NetworkCapabilities$Builder",
                        MainHook.class.getClassLoader());
                Object builder = XposedHelpers.newInstance(builderClass);
                XposedHelpers.callMethod(builder, "addTransportType", transport);
                XposedHelpers.callMethod(builder, "setCapability",
                        NetworkCapabilities.NET_CAPABILITY_INTERNET);
                XposedHelpers.callMethod(builder, "setCapability",
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                XposedHelpers.callMethod(builder, "setCapability",
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                return XposedHelpers.callMethod(builder, "build");
            } else {
                // Android 6-12
                Class<?> ncClass = XposedHelpers.findClass(
                        "android.net.NetworkCapabilities",
                        MainHook.class.getClassLoader());
                Object caps = XposedHelpers.newInstance(ncClass);
                XposedHelpers.callMethod(caps, "addTransportType", transport);
                try {
                    XposedHelpers.callMethod(caps, "addCapability",
                            NetworkCapabilities.NET_CAPABILITY_INTERNET);
                } catch (Throwable ignored) {}
                return caps;
            }
        } catch (Throwable t) {
            if (sDebug) Log.e(TAG, "createFakeCapabilities error", t);
            return null;
        }
    }

    // ===== hook NetworkInfo 中被 App 直接访问的字段/方法 =====
    private void hookNetworkInfoFields(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> niClass = XposedHelpers.findClass(
                    "android.net.NetworkInfo", lpparam.classLoader);

            // hook getType()
            XposedHelpers.findAndHookMethod(niClass, "getType",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(sFakeType);
                        }
                    });

            // hook getTypeName()
            XposedHelpers.findAndHookMethod(niClass, "getTypeName",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(typeIntToName(sFakeType));
                        }
                    });

            // hook getState()
            XposedHelpers.findAndHookMethod(niClass, "getState",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(NetworkInfo.State.CONNECTED);
                        }
                    });

            // hook isConnected()
            XposedHelpers.findAndHookMethod(niClass, "isConnected",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });

            // hook isConnectedOrConnecting()
            XposedHelpers.findAndHookMethod(niClass, "isConnectedOrConnecting",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });

            if (sDebug) XposedBridge.log(TAG + " | NetworkInfo hooks installed");
        } catch (Throwable t) {
            if (sDebug) XposedBridge.log(TAG + " | hook NetworkInfo fields failed: " + t.getMessage());
        }
    }

    // ===== hook ConnectivityManager 核心方法 =====
    private void hookConnectivityManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cmClass = XposedHelpers.findClass(
                    "android.net.ConnectivityManager", lpparam.classLoader);

            // hook getActiveNetworkInfo()
            XposedHelpers.findAndHookMethod(cmClass, "getActiveNetworkInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object fake = createFakeNetworkInfo(sFakeType);
                            if (fake != null) {
                                param.setResult(fake);
                                if (sDebug) XposedBridge.log(TAG + " | CM.getActiveNetworkInfo → faked");
                            }
                        }
                    });

            // hook getNetworkInfo(Network)
            try {
                XposedHelpers.findAndHookMethod(cmClass, "getNetworkInfo",
                        Network.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object fake = createFakeNetworkInfo(sFakeType);
                                if (fake != null) {
                                    param.setResult(fake);
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getNetworkInfo(int)
            try {
                XposedHelpers.findAndHookMethod(cmClass, "getNetworkInfo",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object fake = createFakeNetworkInfo(sFakeType);
                                if (fake != null) {
                                    param.setResult(fake);
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getActiveNetwork()
            try {
                XposedHelpers.findAndHookMethod(cmClass, "getActiveNetwork",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                // 不直接 new Network，而是返回 fake NetworkInfo 后让系统自然走逻辑
                                // 这里先不返回 Network，因为 Network 对象必须由系统创建
                                if (sDebug) XposedBridge.log(TAG + " | CM.getActiveNetwork called");
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getNetworkCapabilities(Network)
            try {
                XposedHelpers.findAndHookMethod(cmClass, "getNetworkCapabilities",
                        Network.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object caps = createFakeCapabilities();
                                if (caps != null) {
                                    param.setResult(caps);
                                    if (sDebug) XposedBridge.log(TAG + " | CM.getNetworkCapabilities → faked");
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getLinkProperties(Network)
            try {
                XposedHelpers.findAndHookMethod(cmClass, "getLinkProperties",
                        Network.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                // 返回一个 LinkProperties，但 Android 16 上需要真实对象
                                // 暂时返回 null，让系统走默认逻辑
                            }
                        });
            } catch (Throwable ignored) {}

            if (sDebug) XposedBridge.log(TAG + " | ConnectivityManager hooks installed");
        } catch (Throwable t) {
            if (sDebug) XposedBridge.log(TAG + " | CM hooks failed: " + t.getMessage());
        }
    }

    // ===== hook TelephonyManager 网络类型查询（关键！）=====
    private void hookTelephonyManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> tmClass = XposedHelpers.findClass(
                    "android.telephony.TelephonyManager", lpparam.classLoader);

            // hook getDataNetworkType() — 最常用的网络类型查询
            try {
                XposedHelpers.findAndHookMethod(tmClass, "getDataNetworkType",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (sFakeType == TYPE_MOBILE) {
                                    // 如果设置的是移动网络，返回 LTE
                                    param.setResult(13); // NETWORK_TYPE_LTE = 13
                                    if (sDebug) XposedBridge.log(TAG + " | TM.getDataNetworkType → LTE");
                                } else {
                                    // 如果设置的是非移动网络，返回 UNKNOWN 让 App 判断
                                    param.setResult(0); // NETWORK_TYPE_UNKNOWN
                                    if (sDebug) XposedBridge.log(TAG + " | TM.getDataNetworkType → UNKNOWN (fakeType="
                                            + typeIntToName(sFakeType) + ")");
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getNetworkType() — 语音/数据网络类型
            try {
                XposedHelpers.findAndHookMethod(tmClass, "getNetworkType",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (sFakeType == TYPE_MOBILE) {
                                    param.setResult(13);
                                } else {
                                    param.setResult(0);
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getSimOperator()
            try {
                XposedHelpers.findAndHookMethod(tmClass, "getSimOperator",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                // 返回中国移动，让部分检测"仅移动网络"的 App 放行
                                param.setResult("46000");
                            }
                        });
            } catch (Throwable ignored) {}

            // hook getSimOperatorName()
            try {
                XposedHelpers.findAndHookMethod(tmClass, "getSimOperatorName",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult("China Mobile");
                            }
                        });
            } catch (Throwable ignored) {}

            if (sDebug) XposedBridge.log(TAG + " | TelephonyManager hooks installed");
        } catch (Throwable t) {
            if (sDebug) XposedBridge.log(TAG + " | TM hooks failed: " + t.getMessage());
        }
    }

    // ===== hook NetworkCapabilities 自身方法 =====
    private void hookNetworkCapabilities(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ncClass = XposedHelpers.findClass(
                    "android.net.NetworkCapabilities", lpparam.classLoader);

            // hook getTransportTypes()
            try {
                XposedHelpers.findAndHookMethod(ncClass, "getTransportTypes",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                int[] transports = new int[]{transportFromFakeType(sFakeType)};
                                param.setResult(transports);
                                if (sDebug) XposedBridge.log(TAG + " | NC.getTransportTypes → "
                                        + transportFromFakeType(sFakeType));
                            }
                        });
            } catch (Throwable ignored) {}

            // hook hasTransport(int)
            try {
                XposedHelpers.findAndHookMethod(ncClass, "hasTransport",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                int requested = (int) param.args[0];
                                int fake = transportFromFakeType(sFakeType);
                                param.setResult(requested == fake);
                            }
                        });
            } catch (Throwable ignored) {}

            // hook hasCapability(int)
            try {
                XposedHelpers.findAndHookMethod(ncClass, "hasCapability",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                int cap = (int) param.args[0];
                                if (cap == NetworkCapabilities.NET_CAPABILITY_INTERNET
                                        || cap == NetworkCapabilities.NET_CAPABILITY_VALIDATED
                                        || cap == NetworkCapabilities.NET_CAPABILITY_NOT_METERED) {
                                    param.setResult(true);
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            if (sDebug) XposedBridge.log(TAG + " | NetworkCapabilities hooks installed");
        } catch (Throwable t) {
            if (sDebug) XposedBridge.log(TAG + " | NC hooks failed: " + t.getMessage());
        }
    }

    // ===== hook TrafficStats 网速 =====
    private void hookTrafficStats(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!sFakeSpeed) return;
        try {
            Class<?> ts = XposedHelpers.findClass(
                    "android.net.TrafficStats", lpparam.classLoader);

            hookTrafficMethod(ts, "getTotalRxBytes", sFakeDownMbps, false);
            hookTrafficMethod(ts, "getTotalTxBytes", sFakeUpMbps, true);
            hookTrafficMethod(ts, "getMobileRxBytes", sFakeDownMbps, false);
            hookTrafficMethod(ts, "getMobileTxBytes", sFakeUpMbps, true);

            if (sDebug) {
                XposedBridge.log(TAG + " | TrafficStats hooks installed (up="
                        + sFakeUpMbps + "Mbps, down=" + sFakeDownMbps + "Mbps)");
            }
        } catch (Throwable t) {
            if (sDebug) XposedBridge.log(TAG + " | TrafficStats hook failed: " + t.getMessage());
        }
    }

    private void hookTrafficMethod(Class<?> tsClass, String method, int speedMbps, boolean isTx) {
        try {
            XposedHelpers.findAndHookMethod(tsClass, method,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(calcFakeBytes(speedMbps, isTx, -1));
                        }
                    });
        } catch (Throwable ignored) {}

        // hook getUidRxBytes / getUidTxBytes
        try {
            String uidMethod = "getUid" + (isTx ? "Tx" : "Rx") + "Bytes";
            XposedHelpers.findAndHookMethod(tsClass, uidMethod,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            int uid = (int) param.args[0];
                            param.setResult(calcFakeBytes(speedMbps, isTx, uid));
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ===== hook android.os.Build 伪装设备 =====
    private void hookBuildClass(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> buildClass = XposedHelpers.findClass(
                    "android.os.Build", lpparam.classLoader);

            // hook VERSION.SDK_INT
            XposedHelpers.findAndHookMethod(buildClass, "getVERSION",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // 不降级 SDK 版本，避免触发 App 的版本检查
                        }
                    });

            if (sDebug) XposedBridge.log(TAG + " | Build hooks installed");
        } catch (Throwable t) {}
    }

    // ===== 核心入口 =====
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (MODULE_PKG.equals(pkg)) return;

        XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, "config");
        prefs.makeWorldReadable();

        if (!shouldFake(pkg, prefs)) return;

        // 同步配置到静态变量（减少每次反射读 prefs）
        syncConfig(prefs);

        if (sDebug) {
            XposedBridge.log(TAG + " | === hook start for: " + pkg
                    + " | fakeType=" + typeIntToName(sFakeType)
                    + " | fakeSpeed=" + sFakeSpeed + " ===");
        }

        // 1. TelephonyManager（最重要，App 直接查网络制式）
        hookTelephonyManager(lpparam);

        // 2. ConnectivityManager
        hookConnectivityManager(lpparam);

        // 3. NetworkInfo
        hookNetworkInfoFields(lpparam);

        // 4. NetworkCapabilities
        hookNetworkCapabilities(lpparam);

        // 5. TrafficStats
        hookTrafficStats(lpparam);

        // 6. Build（可选）
        hookBuildClass(lpparam);

        if (sDebug) {
            XposedBridge.log(TAG + " | === hook done for: " + pkg + " ===");
        }
    }
}
