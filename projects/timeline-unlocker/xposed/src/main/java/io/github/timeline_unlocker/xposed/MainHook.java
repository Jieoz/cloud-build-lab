package io.github.timeline_unlocker.xposed;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
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
 * <p>The diagnostic log is off by default. Its switch lives in libxposed remote preferences,
 * written by the module UI ({@link LogExportActivity}) and read by every hooked process through
 * {@link XposedInterface#getRemotePreferences}. The framework pushes changes, so the switch takes
 * effect live. No broadcast, no polling, no wake-ups.</p>
 */
public class MainHook extends XposedModule {

    private static final String PKG_GMS = "com.google.android.gms";
    private static final String PKG_GSF = "com.google.android.gsf";
    private static final String PKG_MAPS = "com.google.android.apps.maps";

    private static final String FAKE_MCC_MNC = "310030";
    private static final String FAKE_ISO = "us";
    /** Android carrier id of AT&T, the owner of 310030 (China Mobile is 1435). */
    private static final int FAKE_CARRIER_ID = 1187;

    private final java.util.List<String> early = new java.util.ArrayList<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        ModuleRuntime.bind(this);
    }

    /**
     * The module app's reload button asks the framework to hot-reload this process. Swapping hooks
     * in place would not re-run onPackageReady, so the reload is answered the same way as the
     * preference push: restart the process. The framework thaws a frozen process before calling
     * this, which is what makes it reach cached Play services processes. Returning false refuses
     * the in-place swap; the process is gone a moment later either way.
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        restartSelf();
        return false;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!PKG_GMS.equals(pkg) && !PKG_GSF.equals(pkg) && !PKG_MAPS.equals(pkg)) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        String loading = String.format("loading package: %s first=%s process=%s",
                pkg, param.isFirstPackage(), processName());
        if (!DiagLog.isEnabled()) ModuleRuntime.frameworkLog(loading);
        log("%s", loading);

        if (reloadListener == null) {
            // Registered here, not on Application create: it needs no Context, so it works in
            // every hooked process even if the log never binds there.
            reloadListener = ModuleRuntime.watchKey(DiagLog.PREFS_NAME,
                    DiagLog.reloadKeyFor(processName()), prefs -> restartSelf());
        }
        bindLog(cl, pkg);
        // The Application may already exist when the package is reported (GMS side processes);
        // then the create hook never fires, so bind now.
        Application current = currentApplication();
        if (current != null) onApplication(current, pkg);
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
        if (DiagLog.isEnabled()) {
            DiagLog.line(message);
            return;
        }
        // Log off: keep the newest lines so switching it on later still shows startup.
        synchronized (early) {
            if (early.size() >= 200) early.remove(0);
            early.add(message);
        }
    }

    private java.util.List<String> drainEarly() {
        synchronized (early) {
            java.util.List<String> copy = new java.util.ArrayList<>(early);
            early.clear();
            return copy;
        }
    }

    private static Application currentApplication() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String processName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                String proc = Application.getProcessName();
                if (proc != null && !proc.isEmpty()) return proc;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    // ---- every hooked process: bind the diagnostic log on Application.onCreate ---------------

    /**
     * Binds the log in every process of the three packages, not only the main one: Timeline and
     * location history run in GMS side processes (gms.persistent, gms.unstable, ...). The bind
     * only fires for the process's own Application, so a package loaded into another app's
     * process (GMS code inside Maps) keeps writing under the host process tag.
     *
     * <p>The switch is live: LSPosed pushes remote-preference changes to every hooked process,
     * so turning the log on in the module app starts writing right away, with the buffered
     * startup lines first. No reboot and no app restart.</p>
     */
    private void bindLog(ClassLoader cl, String pkg) {
        // Instrumentation.callApplicationOnCreate runs for every Application, whether or not the
        // app's own onCreate calls super. Hooking Application.onCreate missed GMS: its Application
        // never reaches the base method, so the GMS process never bound the log (4.2-log90).
        try {
            Method call = android.app.Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            hook(call).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object app = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (app instanceof Application) onApplication((Application) app, pkg);
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            log("bind log failed: %s", t);
        }
    }

    private synchronized void onApplication(Application app, String pkg) {
        if (logBound || !pkg.equals(app.getPackageName())) return;
        logBound = true;
        logContext = app;
        logPkg = pkg;
        boolean on = ModuleRuntime.switchOn(DiagLog.PREFS_NAME, DiagLog.KEY_ON);
        ModuleRuntime.frameworkLog("[" + processName() + "] bound log=" + on);
        applySwitch(on);
        switchListener = ModuleRuntime.watchSwitch(
                DiagLog.PREFS_NAME, DiagLog.KEY_ON, this::applySwitch);
    }

    /**
     * Restart this process so the current module build hooks it from the start. A process may
     * always kill itself, so this needs no root: Play services processes are brought back by the
     * system, and the module app reopens Maps. Short delay so the log line reaches disk.
     */
    /** Name and pid of every running process with this UID; hooked or not. */
    private static String uidProcesses() {
        StringBuilder out = new StringBuilder();
        try {
            Application app = currentApplication();
            if (app == null) return "?";
            android.app.ActivityManager am = app.getSystemService(android.app.ActivityManager.class);
            int uid = android.os.Process.myUid();
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.uid != uid) continue;
                if (out.length() > 0) out.append(", ");
                out.append(p.processName).append('(').append(p.pid).append(')');
            }
        } catch (Throwable t) {
            return "error " + t;
        }
        return out.toString();
    }

    private void restartSelf() {
        int me = android.os.Process.myPid();
        java.util.List<Integer> siblings = siblingPids(me);
        String line = "reload requested: restarting " + processName() + " pid=" + me
                + " siblings=" + siblings + " uid processes: " + uidProcesses();
        ModuleRuntime.frameworkLog(line);
        log("%s", line);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            // Processes sharing a UID may kill each other (Process.killProcess contract). This is
            // how a process running the new build takes down siblings still running an old build
            // that cannot hear the request: Play services' processes all share one UID.
            for (int pid : siblings) android.os.Process.killProcess(pid);
            android.os.Process.killProcess(me);
        }, "timeline-unlocker-reload");
        t.setDaemon(true);
        t.start();
    }

    /** Other running processes with this process's UID (only those are visible and killable). */
    private static java.util.List<Integer> siblingPids(int me) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        try {
            Application app = currentApplication();
            if (app == null) return out;
            android.app.ActivityManager am = app.getSystemService(android.app.ActivityManager.class);
            int uid = android.os.Process.myUid();
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.uid == uid && p.pid != me) out.add(p.pid);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Turn the log on/off in this process. Safe to call repeatedly from any thread. */
    private synchronized void applySwitch(boolean on) {
        Context context = logContext;
        if (context == null || on == DiagLog.isEnabled()) return;
        DiagLog.bind(context, on, processName());
        if (!on) return;
        DiagLog.line(header(context));
        if (PKG_GMS.equals(processName())) {
            DiagLog.line("uid processes: " + uidProcesses());
        }
        for (String message : drainEarly()) DiagLog.line(message);
        if (PKG_MAPS.equals(logPkg) && !mapsWatchArmed) {
            mapsWatchArmed = true;
            reportTimelineClasses(context.getClassLoader());
            watchActivities();
        }
    }

    private volatile Context logContext;
    private volatile String logPkg;
    private volatile boolean mapsWatchArmed;
    /** Strong reference: some frameworks keep listeners weakly. */
    private Object switchListener;
    private volatile Object reloadListener;
    private volatile boolean logBound;

    private static String header(Context context) {
        String host = "?";
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            host = info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable ignored) {
        }
        return "header module=" + BuildConfig.VERSION_NAME + " host=" + context.getPackageName()
                + " " + host + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " device=" + android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL
                + " framework=" + ModuleRuntime.frameworkLine();
    }

    /**
     * Maps only, log switch on only: one line per Activity create / new intent / resume with the
     * intent action and data. This is how the log shows what a Timeline deep link actually
     * delivered and which screen Maps put on top, without any dex scan.
     */
    private void watchActivities() {
        try {
            Class<?> activity = android.app.Activity.class;
            hook(activity.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                noteActivity("resume", chain.getThisObject(), null);
                return result;
            });
            hook(activity.getDeclaredMethod("onNewIntent", android.content.Intent.class)).intercept(chain -> {
                Object result = chain.proceed();
                Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                noteActivity("new-intent", chain.getThisObject(), arg);
                return result;
            });
            log("activity watch armed");
        } catch (Throwable t) {
            log("activity watch failed: %s", t.getClass().getSimpleName());
        }
    }

    private static final long[] SCAN_DELAYS_MS = {2_000, 8_000, 20_000};

    /**
     * After each Maps resume, look for Timeline text on screen. This turns "is the entry there"
     * and "did the deep link land on Timeline" into log lines instead of a verbal report, without
     * depending on obfuscated class names.
     */
    private void scheduleEntryScan(android.app.Activity activity) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        java.lang.ref.WeakReference<android.app.Activity> ref = new java.lang.ref.WeakReference<>(activity);
        for (long delay : SCAN_DELAYS_MS) {
            h.postDelayed(() -> {
                android.app.Activity a = ref.get();
                if (a == null || a.isFinishing() || !DiagLog.isEnabled()) return;
                try {
                    android.view.View root = a.getWindow().getDecorView();
                    java.util.List<String> texts = new java.util.ArrayList<>();
                    int[] views = {0};
                    collectTexts(root, texts, views);
                    EntryScan.Result r = EntryScan.evaluate(texts);
                    log("entry scan %s t=%ds views=%d %s", a.getClass().getSimpleName(),
                            delay / 1000, views[0], r.line());
                } catch (Throwable t) {
                    log("entry scan failed: %s", t.getClass().getSimpleName());
                }
            }, delay);
        }
    }

    private static void collectTexts(android.view.View v, java.util.List<String> out, int[] count) {
        if (v == null || count[0] > 5000) return;
        count[0]++;
        if (v.getVisibility() != android.view.View.VISIBLE) return;
        CharSequence desc = v.getContentDescription();
        if (desc != null && desc.length() > 0) out.add(desc.toString());
        if (v instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) v).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out, count);
        }
    }

    private void noteActivity(String kind, Object self, Object explicit) {
        try {
            if (!(self instanceof android.app.Activity)) return;
            if ("resume".equals(kind)) scheduleEntryScan((android.app.Activity) self);
            android.content.Intent intent = explicit instanceof android.content.Intent
                    ? (android.content.Intent) explicit : ((android.app.Activity) self).getIntent();
            log("activity %s %s %s", kind, self.getClass().getName(), ActivityLine.describe(
                    intent == null ? null : intent.getAction(),
                    intent == null ? null : intent.getDataString(),
                    intent == null || intent.getComponent() == null ? null
                            : intent.getComponent().getClassName()));
        } catch (Throwable ignored) {
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
        // GMS/GSF read a whole US subscription: country, operator and network. Maps is never
        // touched (it keeps reading cn and keeps its own GCJ-02 correction for the live dot), so
        // none of this reaches alignment. 4.8 logs showed GMS reading iso=us but operator 46002
        // and carrier id 1435 (China Mobile): a half-US identity.
        spoofString(tm, "getSimCountryIso", FAKE_ISO);
        spoofString(tm, "getSimCountryIsoForPhone", FAKE_ISO);
        spoofString(tm, "getNetworkCountryIso", FAKE_ISO);
        spoofString(tm, "getNetworkCountryIsoForPhone", FAKE_ISO);
        spoofString(tm, "getSimOperator", FAKE_MCC_MNC);
        spoofString(tm, "getSimOperatorNumeric", FAKE_MCC_MNC);
        spoofString(tm, "getSimOperatorNumericForPhone", FAKE_MCC_MNC);
        spoofString(tm, "getNetworkOperator", FAKE_MCC_MNC);
        spoofString(tm, "getNetworkOperatorForPhone", FAKE_MCC_MNC);
        hookAllReturning(tm, "getSimCarrierId", FAKE_CARRIER_ID);
        hookSubscriptionInfo(cl);
    }

    private void hookSubscriptionInfo(ClassLoader cl) {
        Class<?> si;
        try {
            si = cl.loadClass("android.telephony.SubscriptionInfo");
        } catch (Throwable t) {
            log("SubscriptionInfo not found: %s", t);
            return;
        }
        spoofString(si, "getCountryIso", FAKE_ISO);
        hookAllReturning(si, "getMcc", 310);
        hookAllReturning(si, "getMnc", 30);
        spoofString(si, "getMccString", "310");
        spoofString(si, "getMncString", "030");
        hookAllReturning(si, "getCarrierId", FAKE_CARRIER_ID);
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
                        // Name the property key / slot so "SystemProperties.get -> retcn" says which.
                        Object first = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        String member = first instanceof String || first instanceof Integer
                                ? method.getName() + "(" + first + ")" : method.getName();
                        noteProbe(simple, member, result, "seen");
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
