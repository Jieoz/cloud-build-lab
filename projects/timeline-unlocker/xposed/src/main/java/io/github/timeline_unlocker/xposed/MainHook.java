package io.github.timeline_unlocker.xposed;

import android.app.Application;
import android.content.Context;
import android.location.Location;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String FAKE_MCC_MNC = "310030";
    private static final int FAKE_MCC = 310;
    private static final int FAKE_MNC = 30;
    // TelephonyManager.getSimCountryIso 等在 AOSP 中返回小写；
    // SubscriptionInfo.getCountryIso 的契约是大写（"the country iso in upper case"）。
    private static final String FAKE_ISO = "us";
    private static final String FAKE_ISO_SUBSCRIPTION = "US";

    private static void log(String fmt, Object... args) {
        String message = String.format(fmt, args);
        synchronized (EARLY) {
            if (EARLY.size() >= 200) EARLY.remove(0);
            EARLY.add(message);
        }
        DiagLog.line(message);
    }

    private static final java.util.List<String> EARLY = new java.util.ArrayList<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (!"com.google.android.gms".equals(pkg)
                && !"com.google.android.gsf".equals(pkg)
                && !"com.google.android.apps.maps".equals(pkg)) {
            return;
        }

        log("loading package: %s (process=%s)", pkg, lpparam.processName);

        ClassLoader cl = lpparam.classLoader;
        hookTelephonyManager(cl);
        hookSubscriptionInfo(cl);
        hookSystemProperties(cl);

        // 只在 Maps 进程里给 Location 做 WGS-84 -> GCJ-02 转换，
        // 修正"Maps 把国家当成 us 不再做坐标偏移"造成的小蓝点偏移。
        // GMS / GSF 不应用此转换，它们的位置上传链路使用真实 WGS-84。
        // 调试日志也只在地图进程写，改开关后只需要强停地图。
        if ("com.google.android.apps.maps".equals(pkg)) {
            bindLog(cl, pkg);
        }
    }

    private static java.util.List<String> earlyLines() {
        synchronized (EARLY) {
            java.util.List<String> copy = new java.util.ArrayList<>(EARLY);
            EARLY.clear();
            return copy;
        }
    }

    private void bindLog(ClassLoader cl, String pkg) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Application) param.thisObject;
                    if (!pkg.equals(context.getPackageName())) return;
                    DiagLog.bind(context);
                    android.content.IntentFilter filter = new android.content.IntentFilter(LogSwitchReceiver.ACTION);
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(new LogSwitchReceiver(), filter, android.content.Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(new LogSwitchReceiver(), filter);
                    }
                    for (String message : earlyLines()) DiagLog.line(message);
                }
            });
        } catch (Throwable t) {
            log("bind log failed: %s", t);
        }
    }

    /**
     * 修 Timeline 历史点偏移：hook GMS SemanticLocation 的 PlaceCandidate$Point
     * 构造函数。Maps 反序列化 Timeline 数据（Visit / Activity 起终点）时都走它。
     *
     * 字段约定：lat/lng 以 latitudeE7 (int = double * 1e7) 表示。
     * 我们把 int 转成 double，做 WGS-84 -> GCJ-02，再放回 int。
     *
     * 这条路径只承载 Timeline 的 Visit/Activity 数据，所以不会污染
     * POI / Marker / 路线规划等其他用 LatLng 的地方。
     */
    private void hookSemanticLocationPoint(ClassLoader cl) {
        Class<?> point;
        try {
            point = XposedHelpers.findClass(
                    "com.google.android.gms.semanticlocation.PlaceCandidate$Point", cl);
        } catch (Throwable t) {
            log("PlaceCandidate$Point not found: %s", t);
            return;
        }

        XC_MethodHook ctorHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args.length < 2) return;
                if (!(args[0] instanceof Integer) || !(args[1] instanceof Integer)) return;
                int latE7 = (Integer) args[0];
                int lngE7 = (Integer) args[1];
                if (latE7 < -900_000_000 || latE7 > 900_000_000
                        || lngE7 < -1_800_000_000 || lngE7 > 1_800_000_000) {
                    return;
                }
                double lat = latE7 / 1e7;
                double lng = lngE7 / 1e7;
                if (!CoordTransform.shouldApplyGcj02(lat, lng)) return;
                double[] gcj = CoordTransform.wgs84ToGcj02(lat, lng);
                args[0] = (int) Math.round(gcj[0] * 1e7);
                args[1] = (int) Math.round(gcj[1] * 1e7);
            }
        };

        try {
            int n = XposedBridge.hookAllConstructors(point, ctorHook).size();
            log("PlaceCandidate$Point GCJ-02 transform installed (%d ctor)", n);
        } catch (Throwable t) {
            log("hook PlaceCandidate$Point failed: %s", t);
        }
    }

    /**
     * 在 Maps 进程里把 Location 的 lat/lng 透明转成 GCJ-02。
     * 用 thread-local 守卫避免 getLatitude/getLongitude 互相递归。
     */
    private void hookLocationGcj02() {
        final ThreadLocal<LocationTransformState> state =
                ThreadLocal.withInitial(LocationTransformState::new);
        final java.util.concurrent.atomic.AtomicBoolean sampled =
                new java.util.concurrent.atomic.AtomicBoolean();

        XC_MethodHook sampleHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                LocationTransformState current = state.get();
                if (current.inHook || !sampled.compareAndSet(false, true)) return;
                current.inHook = true;
                try {
                    Location loc = (Location) param.thisObject;
                    double lat = loc.getLatitude();
                    double lng = loc.getLongitude();
                    double[] gcj = CoordTransform.wgs84ToGcj02(lat, lng);
                    log("location sample delta=%.0fm provider=%s",
                            distanceMeters(lat, lng, gcj[0], gcj[1]), loc.getProvider());
                } finally {
                    current.inHook = false;
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Location.class, "getLatitude", sampleHook);
            log("Location GCJ-02 sample only, live position not moved");
        } catch (Throwable t) {
            log("hook Location lat/lng failed: %s", t);
        }
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double lat = Math.toRadians(lat2 - lat1);
        double lng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(lat / 2) * Math.sin(lat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lng / 2) * Math.sin(lng / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static final class LocationTransformState {
        private final LocationTransformCache cache = new LocationTransformCache();
        private boolean inHook;
    }

    private static XC_MethodReplacement constReplacement(final Object value) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }

    private void hookTelephonyManager(ClassLoader cl) {
        Class<?> tm;
        try {
            tm = XposedHelpers.findClass("android.telephony.TelephonyManager", cl);
        } catch (Throwable t) {
            log("TelephonyManager not found: %s", t);
            return;
        }

        // 这些方法可能存在多种重载（无参 / int subId / String callingPackage 等）
        // 用 hookAllMethods 一网打尽，每个都返回伪造值。
        hookAllReturning(tm, "getSimCountryIso", FAKE_ISO);
        hookAllReturning(tm, "getNetworkCountryIso", FAKE_ISO);
        hookAllReturning(tm, "getSimOperator", FAKE_MCC_MNC);
        hookAllReturning(tm, "getNetworkOperator", FAKE_MCC_MNC);
        hookAllReturning(tm, "getSimOperatorNumeric", FAKE_MCC_MNC);
        hookAllReturning(tm, "getNetworkOperatorNumeric", FAKE_MCC_MNC);
        hookAllReturning(tm, "getSimOperatorNumericForPhone", FAKE_MCC_MNC);
        hookAllReturning(tm, "getNetworkOperatorForPhone", FAKE_MCC_MNC);
        hookAllReturning(tm, "getSimCountryIsoForPhone", FAKE_ISO);
        hookAllReturning(tm, "getNetworkCountryIsoForPhone", FAKE_ISO);
    }

    private void hookSubscriptionInfo(ClassLoader cl) {
        Class<?> subInfo;
        try {
            subInfo = XposedHelpers.findClass("android.telephony.SubscriptionInfo", cl);
        } catch (Throwable t) {
            log("SubscriptionInfo not found: %s", t);
            return;
        }

        hookAllReturning(subInfo, "getCountryIso", FAKE_ISO_SUBSCRIPTION);
        hookAllReturning(subInfo, "getMccString", "310");
        hookAllReturning(subInfo, "getMncString", "030");
        hookAllReturning(subInfo, "getMcc", FAKE_MCC);
        hookAllReturning(subInfo, "getMnc", FAKE_MNC);
    }

    private void hookAllReturning(Class<?> clazz, String name, Object value) {
        try {
            int hooked = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(name) && canReturn(method.getReturnType(), value)) {
                    XposedBridge.hookMethod(method, constReplacement(value));
                    hooked++;
                }
            }
            if (hooked > 0) {
                log("hooked %d overload(s) of %s.%s -> %s",
                        hooked, clazz.getSimpleName(), name, value);
            }
        } catch (Throwable t) {
            log("hooking %s.%s failed: %s", clazz.getSimpleName(), name, t);
        }
    }

    private static boolean canReturn(Class<?> returnType, Object value) {
        if (returnType.isInstance(value)) return true;
        return (returnType == int.class && value instanceof Integer)
                || (returnType == long.class && value instanceof Long)
                || (returnType == boolean.class && value instanceof Boolean);
    }

    private void hookSystemProperties(ClassLoader cl) {
        Class<?> sp;
        try {
            sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
        } catch (Throwable t) {
            log("SystemProperties not found: %s", t);
            return;
        }

        // 拦 SystemProperties.get(String) / get(String, String)，
        // 命中 gsm.(sim.)?operator.(numeric|iso-country) 才改值，其他原样返回。
        XC_MethodReplacement spHook = new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                String key = (String) param.args[0];
                String def = param.args.length > 1 ? (String) param.args[1] : "";
                String fake = SpoofedSystemProperties.valueFor(key, FAKE_MCC_MNC, FAKE_ISO);
                if (fake != null) {
                    return fake;
                }
                String original = (String) XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, param.args);
                return original == null ? def : original;
            }
        };

        try {
            XposedHelpers.findAndHookMethod(sp, "get", String.class, spHook);
        } catch (Throwable t) {
            log("hook SystemProperties.get(String) failed: %s", t);
        }
        try {
            XposedHelpers.findAndHookMethod(sp, "get", String.class, String.class, spHook);
        } catch (Throwable t) {
            log("hook SystemProperties.get(String,String) failed: %s", t);
        }
    }

}
