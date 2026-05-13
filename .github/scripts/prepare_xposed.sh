#!/bin/bash
set -e

mkdir -p app/libs

# Attempt 1: LSPosed prebuilt
curl -fsSL "https://github.com/LSPosed/LSPosed.github.io/raw/main/art/build/82/XposedBridgeApi.jar" -o app/libs/XposedBridgeAPI.jar && echo "Downloaded from LSPosed" || true

# Attempt 2: rovo89 art/api/90
if [ ! -s app/libs/XposedBridgeAPI.jar ]; then
  curl -fsSL "https://github.com/rovo89/XposedBridge/raw/art/api/90/XposedBridgeApi.jar" -o app/libs/XposedBridgeAPI.jar && echo "Downloaded from rovo89/art/90" || true
fi

# Attempt 3: rovo89 master branch
if [ ! -s app/libs/XposedBridgeAPI.jar ]; then
  curl -fsSL "https://github.com/rovo89/XposedBridge/raw/master/art/api/90/XposedBridgeApi.jar" -o app/libs/XposedBridgeAPI.jar && echo "Downloaded from rovo89/master" || true
fi

# Validate JAR
if [ -s app/libs/XposedBridgeAPI.jar ] && file app/libs/XposedBridgeAPI.jar | grep -qi "zip\|jar"; then
  echo "Valid XposedBridgeAPI.jar found"
  ls -la app/libs/XposedBridgeAPI.jar
  exit 0
fi

echo "All downloads failed, generating stub JAR..."

rm -f app/libs/XposedBridgeAPI.jar
mkdir -p stub_src/de/robv/android/xposed stub_src/de/robv/android/xposed/callbacks

# Write stub Java source files
cat > stub_src/de/robv/android/xposed/IXposedHookLoadPackage.java <<'EOF'
package de.robv.android.xposed;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
EOF

cat > stub_src/de/robv/android/xposed/XC_LoadPackage.java <<'EOF'
package de.robv.android.xposed;
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public Object appInfo;
        public Object loadedApk;
    }
}
EOF

cat > stub_src/de/robv/android/xposed/XC_MethodHook.java <<'EOF'
package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { throwable = t; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
EOF

cat > stub_src/de/robv/android/xposed/XposedBridge.java <<'EOF'
package de.robv.android.xposed;
public class XposedBridge {
    public static void log(String msg) { System.out.println("[Xposed] " + msg); }
    public static Object invokeOriginalMethod(Object method, Object thisObject, Object[] args) throws Throwable { return null; }
}
EOF

cat > stub_src/de/robv/android/xposed/XposedHelpers.java <<'EOF'
package de.robv.android.xposed;
import java.lang.reflect.*;
public class XposedHelpers {
    public static Object getObjectField(Object obj, String field) {
        try { Field f = obj.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(obj); } catch (Exception e) { return null; }
    }
    public static void setObjectField(Object obj, String field, Object value) {
        try { Field f = obj.getClass().getDeclaredField(field); f.setAccessible(true); f.set(obj, value); } catch (Exception ignored) {}
    }
    public static void setIntField(Object obj, String field, int value) {
        try { Field f = obj.getClass().getDeclaredField(field); f.setAccessible(true); f.setInt(obj, value); } catch (Exception ignored) {}
    }
    public static void setBooleanField(Object obj, String field, boolean value) {
        try { Field f = obj.getClass().getDeclaredField(field); f.setAccessible(true); f.setBoolean(obj, value); } catch (Exception ignored) {}
    }
    public static Object callMethod(Object obj, String method, Object... args) {
        try {
            for (Method m : obj.getClass().getDeclaredMethods())
                if (m.getName().equals(method) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(obj, args); }
        } catch (Exception ignored) {}
        return null;
    }
    public static Object callStaticMethod(Class<?> clazz, String method, Object... args) {
        try {
            for (Method m : clazz.getDeclaredMethods())
                if (m.getName().equals(method) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(null, args); }
        } catch (Exception ignored) {}
        return null;
    }
    public static Class<?> findClass(String className, ClassLoader cl) {
        try { return Class.forName(className, false, cl); } catch (Exception e) { return null; }
    }
    public static Object newInstance(Class<?> clazz, Object... args) {
        try {
            for (Constructor<?> c : clazz.getDeclaredConstructors())
                if (c.getParameterCount() == args.length) { c.setAccessible(true); return c.newInstance(args); }
        } catch (Exception ignored) {}
        return null;
    }
    public static Object findAndHookMethod(Class<?> clazz, String methodName, Object... paramTypes) { return null; }
}
EOF

cat > stub_src/de/robv/android/xposed/XSharedPreferences.java <<'EOF'
package de.robv.android.xposed;
import java.util.Set;
import java.util.HashSet;
/**
 * Standalone stub - does NOT extend android.content.SharedPreferences
 * so it compiles without Android SDK on the build host.
 */
public class XSharedPreferences {
    public XSharedPreferences(String pkg, String name) {}
    public void makeWorldReadable() {}
    public void reload() {}
    public String getString(String k, String d) { return d; }
    public int getInt(String k, int d) { return d; }
    public boolean getBoolean(String k, boolean d) { return d; }
    public long getLong(String k, long d) { return d; }
    public float getFloat(String k, float d) { return d; }
    public Set<String> getStringSet(String k, Set<String> d) { return d == null ? new HashSet<>() : d; }
    public boolean contains(String k) { return false; }
}
EOF

cat > stub_src/de/robv/android/xposed/callbacks/package-info.java <<'EOF'
package de.robv.android.xposed.callbacks;
EOF

# Compile stub sources
OUT=stub_out
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" stub_src/de/robv/android/xposed/*.java stub_src/de/robv/android/xposed/callbacks/*.java 2>&1
jar cf app/libs/XposedBridgeAPI.jar -C "$OUT" de
echo "Stub XposedBridgeAPI.jar created"
ls -la app/libs/XposedBridgeAPI.jar
