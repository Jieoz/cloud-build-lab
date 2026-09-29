package io.github.timeline_unlocker.xposed;

import android.app.Application;
import android.content.Context;
import android.location.Location;

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
            // Maps skips Timeline while any of these reads still look like a China SIM.
            // Country iso alone (log54) left 46002/46000 in place and the entry stayed
            // closed. Spoof the operator codes too. Do not rewrite live Location here:
            // stacking a GCJ shift on Maps' own correction moved the blue dot.
            hookSemanticLocationPoint(cl);
            hookTimelineReads(cl, false);
            hookTelephonyManager(cl);
            hookTimelineGate(cl);
        } else {
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
                            if (PKG_MAPS.equals(pkg)) reportTimelineClasses(context.getClassLoader());
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
     * Maps stops applying its own GCJ-02 correction once the SIM looks like {@code us}.
     * Convert the live location in this process so the blue dot stays on the road.
     * GMS and GSF keep real WGS-84 for uploads.
     */
    private void hookLocationGcj02() {
        final ThreadLocal<LocationTransformState> state =
                ThreadLocal.withInitial(LocationTransformState::new);
        try {
            hook(Location.class.getDeclaredMethod("getLatitude")).intercept(chain -> {
                LocationTransformState current = state.get();
                if (current.inHook) return chain.proceed();
                current.inHook = true;
                try {
                    Location loc = (Location) chain.getThisObject();
                    double lat = (Double) chain.proceed();
                    current.cache.update(loc, lat, loc.getLongitude());
                    return current.cache.transformedLatitude();
                } finally {
                    current.inHook = false;
                }
            });
            hook(Location.class.getDeclaredMethod("getLongitude")).intercept(chain -> {
                LocationTransformState current = state.get();
                if (current.inHook) return chain.proceed();
                current.inHook = true;
                try {
                    Location loc = (Location) chain.getThisObject();
                    double lng = (Double) chain.proceed();
                    current.cache.update(loc, loc.getLatitude(), lng);
                    return current.cache.transformedLongitude();
                } finally {
                    current.inHook = false;
                }
            });
            log("Location GCJ-02 transform hooks installed");
        } catch (Throwable t) {
            log("hook Location lat/lng failed: %s", t);
        }
    }

    private static final class LocationTransformState {
        private final LocationTransformCache cache = new LocationTransformCache();
        private boolean inHook;
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

    /**
     * The only method that constructs TimelineWrapper is obfuscated {@code aklx.a}.
     * Its body contains the constructor invoke, but Maps returns before reaching it.
     * Record the values that method actually reads, then whether the constructor ran.
     * Nothing here walks the APK dex; the method is hooked only when Maps calls it.
     */
    private void hookTimelineGate(ClassLoader cl) {
        Class<?> gate;
        try {
            gate = cl.loadClass("aklx");
        } catch (Throwable t) {
            log("timeline gate class missing: %s", t.getClass().getSimpleName());
            return;
        }
        int hooked = 0;
        for (Method method : gate.getDeclaredMethods()) {
            if (!method.getName().equals("a")) continue;
            try {
                hook(method).intercept(chain -> {
                    log("timeline gate entered %s", method);
                    Object result = chain.proceed();
                    log("timeline gate returned %s opened=%s result=%s",
                            method, timelineOpened, result);
                    return result;
                });
                hooked++;
            } catch (Throwable t) {
                log("timeline gate hook failed: %s", t.getClass().getSimpleName());
            }
        }
        log("timeline gate methods %d", hooked);
        reportGateReads(cl, gate);
    }

    /**
     * aklx.a can construct TimelineWrapper and this launch never entered it. Decode only that
     * one method and log the country/operator reads it contains. Do not scan the rest of the dex.
     */
    private void reportGateReads(ClassLoader cl, Class<?> gate) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) {
                log("timeline gate reads path failed");
                return;
            }
            String apk = (String) app.getClass().getMethod("getPackageCodePath").invoke(app);
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
            try {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                int logged = 0;
                while (entries.hasMoreElements() && logged == 0) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                    java.io.InputStream in = zip.getInputStream(entry);
                    byte[] dex = in.readAllBytes();
                    in.close();
                    java.util.List<String> calls = DexTypes.invokesOf(dex, "aklx", "a", "TimelineWrapper", 24);
                    for (String call : calls) {
                        String lower = call.toLowerCase(java.util.Locale.US);
                        if (!(lower.contains("country") || lower.contains("operator")
                                || lower.contains("mcc") || lower.contains("mnc")
                                || lower.contains("sim") || lower.contains("timeline"))) continue;
                        log("timeline gate read: %s", call);
                        logged++;
                    }
                    if (!calls.isEmpty() && logged == 0) log("timeline gate reads: none of %d", calls.size());
                }
            } finally {
                zip.close();
            }
        } catch (Throwable t) {
            log("timeline gate reads failed: %s", t.getClass().getSimpleName());
        }
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
        // log33 kept the timeline and the aligned map by spoofing only these two
        // reads inside GMS/GSF. Operator codes and SubscriptionInfo were added
        // later and did not bring the entry back.
        spoofString(tm, "getSimCountryIso", FAKE_ISO);
        spoofString(tm, "getSimCountryIsoForPhone", FAKE_ISO);
        spoofString(tm, "getNetworkCountryIso", FAKE_ISO);
        spoofString(tm, "getNetworkCountryIsoForPhone", FAKE_ISO);
        spoofString(tm, "getSimOperator", FAKE_MCC_MNC);
        spoofString(tm, "getSimOperatorNumeric", FAKE_MCC_MNC);
        spoofString(tm, "getSimOperatorNumericForPhone", FAKE_MCC_MNC);
        spoofString(tm, "getNetworkOperator", FAKE_MCC_MNC);
        spoofString(tm, "getNetworkOperatorForPhone", FAKE_MCC_MNC);
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
