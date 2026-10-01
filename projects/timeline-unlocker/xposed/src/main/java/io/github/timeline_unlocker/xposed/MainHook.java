package io.github.timeline_unlocker.xposed;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * libxposed (API 102) entry. Declared in META-INF/xposed/java_init.list; scope in scope.list.
 *
 * <p>Behaviour is unchanged from the legacy build:</p>
 * <ul>
 *   <li>GMS / GSF: force SIM country iso to {@code us} and rewrite {@code gsm.*operator.*}
 *       system properties, so the Timeline entry appears. Real country stays out of Maps.</li>
 *   <li>Maps: rewrite Timeline history points ({@code PlaceCandidate$Point}) WGS-84 -> GCJ-02 so
 *       the mainland road / satellite layers line up, and bind the diagnostic log.</li>
 * </ul>
 *
 * <p>The diagnostic log is off by default. Its switch is a plain private SharedPreference written
 * by the module UI ({@link LogExportActivity}) and read <b>once</b>, read-only, by the Maps process
 * through {@link XposedInterface#getRemotePreferences}. No broadcast, no polling, no wake-ups.</p>
 */
public class MainHook extends XposedModule {

    private static final String PKG_GMS = "com.google.android.gms";
    private static final String PKG_GSF = "com.google.android.gsf";
    private static final String PKG_MAPS = "com.google.android.apps.maps";

    private static final String FAKE_MCC_MNC = "310030";
    private static final String FAKE_ISO = "us";

    private final java.util.List<String> early = new java.util.ArrayList<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        ModuleRuntime.bind(this);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!PKG_GMS.equals(pkg) && !PKG_GSF.equals(pkg) && !PKG_MAPS.equals(pkg)) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        log("loading package: %s", pkg);

        bindLog(cl, pkg);
        if (PKG_MAPS.equals(pkg)) {
            // log33/log60 pair: Maps keeps reading its real SIM, so it keeps its own GCJ-02
            // correction for the live dot. The module only shifts history points.
            hookSemanticLocationPoint(cl);
            hookTimelineReads(cl, false);
        } else {
            // GMS/GSF decide the entry; log33 proved the two iso reads + system properties
            // are the working pair. Keep exactly that.
            hookTimelineReads(cl, true);
            hookTelephonyManager(cl);
            hookSystemProperties(cl);
        }
    }

    private void log(String fmt, Object... args) {
        String message = String.format(fmt, args);
        synchronized (early) {
            if (early.size() >= 200) early.remove(0);
            early.add(message);
        }
        DiagLog.line(message);
    }

    private java.util.List<String> drainEarly() {
        synchronized (early) {
            java.util.List<String> copy = new java.util.ArrayList<>(early);
            early.clear();
            return copy;
        }
    }

    /** True in the package's main process, so the log binds once and not in every child process. */
    private static boolean isMainProcess(String pkg) {
        try {
            String proc = Application.getProcessName();
            return proc == null || proc.equals(pkg);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- Maps: bind the diagnostic log on Application.onCreate ----------------------------------

    private void bindLog(ClassLoader cl, String pkg) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Application) {
                        Context context = (Application) self;
                        if (pkg.equals(context.getPackageName()) && isMainProcess(pkg)) {
                            boolean on = ModuleRuntime.switchOn(DiagLog.PREFS_NAME, DiagLog.KEY_ON);
                            DiagLog.bind(context, on);
                            if (PKG_MAPS.equals(pkg)) {
                                reportTimelineClasses(context.getClassLoader());
                            }
                            if (on) {
                                for (String message : drainEarly()) DiagLog.line(message);
                            } else {
                                // Off: release the startup buffer instead of holding it for the
                                // process lifetime. Nothing will ever read it while off.
                                drainEarly();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            log("bind log failed: %s", t);
        }
    }

    // ---- Maps: Timeline history point GCJ-02 rewrite -------------------------------------------

    private void hookSemanticLocationPoint(ClassLoader cl) {
        Class<?> point;
        try {
            point = cl.loadClass("com.google.android.gms.semanticlocation.PlaceCandidate$Point");
        } catch (Throwable t) {
            log("PlaceCandidate$Point not found: %s", t);
            return;
        }
        int n = 0;
        for (Constructor<?> ctor : point.getDeclaredConstructors()) {
            try {
                hook(ctor).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length >= 2
                            && args[0] instanceof Integer && args[1] instanceof Integer) {
                        int latE7 = (Integer) args[0];
                        int lngE7 = (Integer) args[1];
                        if (latE7 >= -900_000_000 && latE7 <= 900_000_000
                                && lngE7 >= -1_800_000_000 && lngE7 <= 1_800_000_000) {
                            double lat = latE7 / 1e7;
                            double lng = lngE7 / 1e7;
                            if (CoordTransform.shouldApplyGcj02(lat, lng)) {
                                double[] gcj = CoordTransform.wgs84ToGcj02(lat, lng);
                                args[0] = (int) Math.round(gcj[0] * 1e7);
                                args[1] = (int) Math.round(gcj[1] * 1e7);
                                return chain.proceed(args);
                            }
                        }
                    }
                    return chain.proceed();
                });
                n++;
            } catch (Throwable t) {
                log("hook PlaceCandidate$Point ctor failed: %s", t);
            }
        }
        log("PlaceCandidate$Point GCJ-02 transform installed (%d ctor)", n);
    }

    /**
     * Counts Timeline-named classes in the Maps APK on this device. Obfuscated builds do not
     * keep com.google.android.apps.maps.timeline.*, so a fixed name would report "absent" for a
     * screen that is still there. history = PlaceCandidate loaded; ui = a Timeline* class exists.
     */
    private void reportTimelineClasses(ClassLoader cl) {
        // loadClass forces the type in, so it is not "the screen opened".
        // TimelineWrapper is the UI object. A constructor hook fires when Maps creates it.
        String binary = "com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper";
        log("timeline candidate: %s", binary);
        int armed = 0;
        try {
            Class<?> type = cl.loadClass(binary);
            Constructor<?>[] ctors = type.getDeclaredConstructors();
            for (Constructor<?> ctor : ctors) {
                hook(ctor).intercept(chain -> {
                    timelineOpened = true;
                    noteProbe("ctor", binary, "new", "opened");
                    return chain.proceed();
                });
            }
            armed = ctors.length == 0 ? 0 : 1;
            if (ctors.length == 0) log("timeline no-ctor: %s", binary);
        } catch (Throwable t) {
            log("timeline watch failed: %s (%s)", binary, t.getClass().getSimpleName());
        }
        log("timeline watching %d/1", armed);
    }

    // ---- GMS / GSF: SIM country iso -> us -------------------------------------------------------

    private void hookTelephonyManager(ClassLoader cl) {
        Class<?> tm;
        try {
            tm = cl.loadClass("android.telephony.TelephonyManager");
        } catch (Throwable t) {
            log("TelephonyManager not found: %s", t);
            return;
        }
        // log33 pair: the two country-iso reads plus the system properties. Operator
        // spoofs (log49+) never produced the entry; do not re-add them silently.
        spoofString(tm, "getSimCountryIso", FAKE_ISO);
        spoofString(tm, "getSimCountryIsoForPhone", FAKE_ISO);
    }

    private void spoofString(Class<?> clazz, String name, String value) {
        hookAllReturning(clazz, name, value);
    }

    private void hookAllReturning(Class<?> clazz, String name, Object value) {
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            if (!canReturn(method.getReturnType(), value)) continue;
            try {
                hook(method).intercept(chain -> {
                    Object raw = chain.proceed();
                    noteProbe(clazz.getSimpleName(), name, raw, "raw");
                    return value;
                });
                hooked++;
            } catch (Throwable t) {
                log("hooking %s.%s failed: %s", clazz.getSimpleName(), name, t);
            }
        }
        if (hooked > 0) {
            log("hooked %d overload(s) of %s.%s -> %s", hooked, clazz.getSimpleName(), name, value);
        }
    }

    private static boolean canReturn(Class<?> returnType, Object value) {
        if (returnType.isInstance(value)) return true;
        return (returnType == int.class && value instanceof Integer)
                || (returnType == long.class && value instanceof Long)
                || (returnType == boolean.class && value instanceof Boolean);
    }

    // ---- GMS / GSF: gsm.*operator.* system properties ------------------------------------------

    private void hookSystemProperties(ClassLoader cl) {
        Class<?> sp;
        try {
            sp = cl.loadClass("android.os.SystemProperties");
        } catch (Throwable t) {
            log("SystemProperties not found: %s", t);
            return;
        }
        try {
            hook(sp.getDeclaredMethod("get", String.class)).intercept(chain -> {
                String key = (String) chain.getArg(0);
                String fake = SpoofedSystemProperties.valueFor(key, FAKE_MCC_MNC, FAKE_ISO);
                if (fake != null) {
                    noteProbe("SystemProperties", "get " + key, chain.proceed(), "raw");
                    return fake;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log("hook SystemProperties.get(String) failed: %s", t);
        }
        try {
            hook(sp.getDeclaredMethod("get", String.class, String.class)).intercept(chain -> {
                String key = (String) chain.getArg(0);
                String fake = SpoofedSystemProperties.valueFor(key, FAKE_MCC_MNC, FAKE_ISO);
                if (fake != null) {
                    noteProbe("SystemProperties", "get " + key, chain.proceed(), "raw");
                    return fake;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log("hook SystemProperties.get(String,String) failed: %s", t);
        }
    }

    // ---- Timeline entry evidence ---------------------------------------------------------------
    //
    // The entry disappears when Maps (or GMS) still reads a non-US country. The spoof hooks
    // above only cover two TelephonyManager methods inside GMS/GSF, and they log the install,
    // not the value the host later reads. This pass watches every country/operator read in
    // Maps, GMS and GSF and writes one line per distinct answer, capped, so a missing entry
    // can be told apart from "the module never loaded in GMS".

    private static final int MAX_PROBE_LINES = 40;

    private final java.util.Set<String> probed = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());
    private volatile boolean timelineOpened;

    private void hookTimelineReads(ClassLoader cl, boolean gmsSide) {
        String[] names = gmsSide
                ? new String[]{
                    "android.telephony.TelephonyManager",
                    "android.telephony.SubscriptionInfo",
                    "android.telephony.SubscriptionManager"}
                : new String[]{
                    "android.telephony.TelephonyManager",
                    "android.telephony.SubscriptionInfo",
                    "android.telephony.SubscriptionManager",
                    "android.os.SystemProperties"};
        int watched = 0;
        for (String name : names) {
            Class<?> type;
            try {
                type = cl.loadClass(name);
            } catch (Throwable t) {
                log("timeline probe class missing: %s (%s)", name, t.getClass().getSimpleName());
                continue;
            }
            String simple = type.getSimpleName();
            for (Method method : type.getDeclaredMethods()) {
                if (!TimelineProbe.relevant(name, method.getName())) continue;
                if (method.getReturnType() == void.class) continue;
                try {
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        noteProbe(simple, method.getName(), result, "seen");
                        return result;
                    });
                    watched++;
                } catch (Throwable t) {
                    log("timeline probe hook failed: %s.%s (%s)",
                            type.getSimpleName(), method.getName(), t.getClass().getSimpleName());
                }
            }
        }
        log("timeline probe watching %d method(s) in %s", watched, gmsSide ? "gms" : "maps");
    }

    private void noteProbe(String owner, String member, Object result, String kind) {
        String value = result == null ? "null" : String.valueOf(result);
        String key = kind + " " + owner + "." + member + "=" + value;
        if (probed.size() >= MAX_PROBE_LINES || !probed.add(key)) return;
        log("%s %s", kind, TimelineProbe.line(owner, member, value));
    }
}
