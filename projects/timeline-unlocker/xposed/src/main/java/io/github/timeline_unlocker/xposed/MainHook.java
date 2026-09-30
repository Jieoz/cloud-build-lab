package io.github.timeline_unlocker.xposed;

import android.app.Application;
import android.content.Context;
import android.location.Location;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.zip.ZipFile;

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
            // log33 baseline: history points only. Every Maps-side spoof (telephony iso,
            // operators, the bvsu/bvsu.i guess) changed nothing except risking the blue dot.
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
                                watchTimelineGate(context);
                                watchEntryGate(context);
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
     * One bounded startup scan of the installed Maps APK: hook the method that really
     * constructs TimelineWrapper, and one caller above it. Logs return type and primitive
     * args when those methods run. No spoof, no live location, no whole-dex callee walk.
     */
    private void watchTimelineGate(Context context) {
        String apk = context.getApplicationInfo().sourceDir;
        byte[] dex = dexContaining(apk, "TimelineWrapper");
        if (dex == null) {
            log("timeline gate: no dex names TimelineWrapper");
            return;
        }
        DexTypes.Creator maker = DexTypes.findCreator(dex);
        if (maker == null) {
            log("timeline gate: no decodable TimelineWrapper.<init> caller");
            return;
        }
        armGate(context.getClassLoader(), maker, "maker");
        ClassLoader cl = context.getClassLoader();
        watchMembers(cl, "com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper");
        java.util.List<String> refs = invokersOf(apk,
                "Lcom/google/android/apps/gmm/mapsactivity/instant/TimelineWrapper;", "<init>", 12);
        log("timeline gate refs: %d %s", refs.size(), refs);
        for (String descriptor : maker.params) {
            if (descriptor == null || descriptor.length() < 4 || descriptor.charAt(0) != 'L') continue;
            String binary = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            if (binary.startsWith("com.google.common.")) continue;
            watchMembers(cl, binary);
        }
        DexTypes.Creator caller = DexTypes.findInvoker(dex, maker.owner, maker.name, maker.signature());
        if (caller == null) {
            log("timeline gate: no decodable caller of %s", maker.signature());
            return;
        }
        armGate(context.getClassLoader(), caller, "caller");
    }

    private void armGate(ClassLoader cl, DexTypes.Creator found, String role) {
        String binary = found.owner.length() > 1 && found.owner.charAt(0) == 'L'
                && found.owner.charAt(found.owner.length() - 1) == ';'
                ? found.owner.substring(1, found.owner.length() - 1).replace('/', '.')
                : found.owner;
        try {
            Class<?> type = cl.loadClass(binary);
            Method chosen = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(found.name)) {
                    chosen = method;
                    break;
                }
            }
            if (chosen == null && !found.params.isEmpty()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (sameParams(method, found.params)) {
                        chosen = method;
                        break;
                    }
                }
            }
            if (chosen == null) {
                StringBuilder seen = new StringBuilder();
                int n = 0;
                for (Method method : type.getDeclaredMethods()) {
                    if (n++ >= 12) break;
                    if (seen.length() > 0) seen.append(',');
                    seen.append(method.getName()).append('/').append(method.getParameterTypes().length);
                }
                log("timeline gate %s: %s dex=%s arity=%d armed=0 methods=%s",
                        role, binary, found.name, found.arity, seen);
                return;
            }
            String live = chosen.getName();
            Method target = chosen;
            hook(target).intercept(chain -> {
                Object result = chain.proceed();
                noteGate(TimelineProbe.call(binary, live, found.ret,
                        chain.getArgs().toArray(), result));
                return result;
            });
            log("timeline gate %s: %s dex=%s arity=%d armed=1 name=%s/%d calls=%s",
                    role, binary, found.name, found.arity, live,
                    target.getParameterTypes().length, shownCalls(found));
        } catch (Throwable t) {
            log("timeline gate %s failed: %s (%s)", role, binary, t.getClass().getSimpleName());
        }
    }

    /** Every declared method of one gate class, with the caller recorded in the line. */
    private void watchMembers(ClassLoader cl, String binary) {
        try {
            Class<?> type = cl.loadClass(binary);
            int armed = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (armed >= 12) break;
                Method target = method;
                hook(target).intercept(chain -> {
                    Object result = chain.proceed();
                    noteGate(memberLine(binary, target, chain.getThisObject(), chain.getArgs().size(), result));
                    return result;
                });
                armed++;
            }
            log("timeline gate members: %s armed=%d", binary, armed);
        } catch (Throwable t) {
            log("timeline gate members failed: %s (%s)", binary, t.getClass().getSimpleName());
        }
    }

    private static String memberLine(String owner, Method method, Object self, int argc, Object result) {
        String from = "none";
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 0; i < stack.length && i < 30; i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("io.github.timeline_unlocker") || cls.startsWith("io.github.libxposed")
                    || cls.startsWith("de.robv.android.xposed") || cls.startsWith("org.lsposed")
                    || cls.startsWith("java.") || cls.startsWith("dalvik.")) continue;
            from = cls + "." + stack[i].getMethodName();
            break;
        }
        String ret = result instanceof Boolean || result instanceof Integer || result instanceof Long
                ? String.valueOf(result) : result == null ? "null" : result.getClass().getSimpleName();
        return "timeline member " + owner + "." + method.getName() + "/" + method.getParameterTypes().length
                + " from=" + from + " args=" + argc
                + " self=" + (self == null ? "null" : self.getClass().getSimpleName()) + " -> " + ret;
    }

    /** Dex type descriptor vs the live parameter class. */
    private static boolean sameParams(Method method, java.util.List<String> descriptors) {
        Class<?>[] live = method.getParameterTypes();
        if (live.length != descriptors.size()) return false;
        for (int i = 0; i < live.length; i++) {
            if (!descriptors.get(i).equals(descriptorOf(live[i]))) return false;
        }
        return true;
    }

    private static String descriptorOf(Class<?> type) {
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == void.class) return "V";
        if (type.isArray()) return "[" + descriptorOf(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /** First few invokes inside the found method, so a silent hook still shows what it calls. */
    private static String shownCalls(DexTypes.Creator found) {
        StringBuilder out = new StringBuilder();
        int n = Math.min(6, found.calls.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) out.append(" | ");
            String row = found.calls.get(i);
            out.append(row.length() > 80 ? row.substring(0, 80) : row);
        }
        return out.toString();
    }

    /** The first classes*.dex in the installed APK whose type table names TimelineWrapper. */
    private static byte[] dexContaining(String apk, String needle) {
        if (apk == null) return null;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                if (entry.getSize() <= 0 || entry.getSize() > 48L * 1024 * 1024) continue;
                byte[] bytes = readEntry(zip, entry);
                if (DexTypes.countDescriptorContaining(bytes, needle) > 0) return bytes;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Methods that invoke the target, across every classes*.dex. A caller in a later dex
     * is invisible to a scan of the dex that defines the type. Each row is prefixed with
     * the dex file name. Capped, and each dex is decoded on its own.
     */
    private static java.util.List<String> invokersOf(String apk, String owner, String name, int cap) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (apk == null) return found;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && found.size() < cap) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String file = entry.getName();
                if (!file.startsWith("classes") || !file.endsWith(".dex")) continue;
                if (entry.getSize() <= 0 || entry.getSize() > 48L * 1024 * 1024) continue;
                for (String row : DexTypes.allInvokers(readEntry(zip, entry), owner, name, cap - found.size())) {
                    found.add(file + " " + row);
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    private static byte[] readEntry(ZipFile zip, java.util.zip.ZipEntry entry) throws java.io.IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
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

    private static final int MAX_PROBE_LINES = 80;

    private final java.util.Set<String> probed = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());
    private final java.util.Set<Object> refreshed = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
    private volatile boolean timelineOpened;

    /**
     * Logs the value Maps computes for the Timeline menu entry. The deciding class is found by
     * shape on the installed APK, so an obfuscated rename does not break it. One line per call:
     * the boxed flag, the entry list size, and the boolean the menu actually reads.
     */
    private void watchEntryGate(Context context) {
        try {
            java.util.List<String> apks = new java.util.ArrayList<>();
            apks.add(context.getApplicationInfo().sourceDir);
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (splits != null) java.util.Collections.addAll(apks, splits);
            String desc = null;
            String hitApk = null;
            String rowInserter = null;
            int dexes = 0, unreadable = 0;
            for (String apk : apks) {
                if (desc != null || apk == null) break;
                try (ZipFile zip = new ZipFile(apk)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements() && desc == null) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                        if (entry.getSize() <= 0 || entry.getSize() > 48L * 1024 * 1024) continue;
                        dexes++;
                        byte[] bytes = readEntry(zip, entry);
                        if (bytes == null) { unreadable++; continue; }
                        desc = DexTypes.findEntryGate(bytes);
                        if (desc != null) {
                            hitApk = apk;
                            rowInserter = DexTypes.findRowInserter(bytes, desc);
                        }
                    }
                } catch (Throwable ignored) {
                    unreadable++;
                }
            }
            if (desc == null) {
                log("timeline entry gate: not found (apks=%d dexes=%d unreadable=%d)", apks.size(), dexes, unreadable);
                return;
            }
            String binary = desc.substring(1, desc.length() - 1).replace('/', '.');
            Class<?> type = context.getClassLoader().loadClass(binary);
            int armed = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                if (method.getReturnType() != boolean.class) continue;
                Method target = method;
                hook(target).intercept(chain -> {
                    Object self = chain.getThisObject();
                    String before = entryState(self);
                    Object result = chain.proceed();
                    log("timeline entry %s -> %s (%s)", target.getName(), result, before);
                    if (Boolean.FALSE.equals(result) && dataReady(self)) nudgeRefresh(self);
                    return result;
                });
                armed++;
            }
            log("timeline entry gate: %s armed=%d apk=%s", binary, armed,
                    hitApk == null ? "?" : hitApk.substring(hitApk.lastIndexOf('/') + 1));
            watchRowInserter(context, rowInserter);
        } catch (Throwable t) {
            log("timeline entry gate failed: %s", t.getClass().getSimpleName());
        }
    }

    /** True once the gate holds both the boxed flag and the entry-point payload. */
    private static boolean dataReady(Object self) {
        boolean flag = false, payload = false;
        for (Class<?> type = self.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(self);
                    if (value instanceof Boolean) flag = true;
                    else if (value != null && !(value instanceof String) && !(value instanceof Number)
                            && value.getClass().getMethod("size") != null) payload = true;
                } catch (Throwable ignored) {
                }
            }
        }
        return flag && payload;
    }

    /** Re-runs the gate's refresh callback on the main thread, once per payload. */
    private void nudgeRefresh(Object self) {
        Runnable callback = null;
        for (Class<?> type = self.getClass(); type != null && type != Object.class && callback == null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() != Runnable.class) continue;
                try {
                    field.setAccessible(true);
                    callback = (Runnable) field.get(self);
                } catch (Throwable ignored) {
                }
            }
        }
        if (callback == null || !refreshed.add(self)) return;
        Runnable task = callback;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                task.run();
                log("timeline refresh posted");
            } catch (Throwable t) {
                log("timeline refresh failed: %s", t.getClass().getSimpleName());
            }
        });
    }

    /** Logs each call of the method that inserts the Timeline row, and whether it got a row. */
    private void watchRowInserter(Context context, String rowInserter) {
        try {
            if (rowInserter == null || !rowInserter.contains("->")) {
                log("timeline row: not found");
                return;
            }
            String owner = rowInserter.substring(1, rowInserter.indexOf(';')).replace('/', '.');
            String name = rowInserter.substring(rowInserter.indexOf("->") + 2);
            Class<?> type = context.getClassLoader().loadClass(owner);
            int armed = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    int rows = -1;
                    try {
                        java.lang.reflect.Method size = result.getClass().getMethod("size");
                        Object n = size.invoke(result);
                        if (n instanceof Integer) rows = (Integer) n;
                    } catch (Throwable ignored) {
                    }
                    log("timeline row %s -> %s size=%d", name, result == null ? "null" : result.getClass().getSimpleName(), rows);
                    return result;
                });
                armed++;
            }
            log("timeline row: %s armed=%d", rowInserter, armed);
        } catch (Throwable t) {
            log("timeline row failed: %s", t.getClass().getSimpleName());
        }
    }

    /** Every instance field on the gate object, so a return flip can be matched to a field. */
    private static String entryState(Object self) {
        return entryState(self, 0);
    }

    private static String entryState(Object self, int depth) {
        if (self == null) return "self=null";
        StringBuilder out = new StringBuilder();
        for (Class<?> type = self.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (out.length() > 0) out.append(' ');
                out.append(field.getName()).append(':');
                try {
                    field.setAccessible(true);
                    Object value = field.get(self);
                    if (value == null) {
                        out.append(field.getType().getSimpleName()).append("=null");
                    } else if (value instanceof Boolean || value instanceof Number || value instanceof String) {
                        String text = String.valueOf(value);
                        out.append(text.length() > 24 ? text.substring(0, 24) : text);
                    } else if (field.getName().equals("g") && depth == 0) {
                        out.append('{').append(entryState(value, depth + 1)).append('}');
                    } else if (field.getName().equals("b") && depth == 1 && value instanceof java.util.List) {
                        java.util.List<?> items = (java.util.List<?>) value;
                        out.append('[');
                        for (int n = 0; n < items.size() && n < 8; n++) {
                            if (n > 0) out.append(' ');
                            out.append(entryState(items.get(n), depth + 1));
                        }
                        out.append(']');
                    } else {
                        String sized = null;
                        try {
                            sized = value.getClass().getSimpleName() + "#"
                                    + value.getClass().getMethod("size").invoke(value);
                        } catch (Throwable ignored) {
                        }
                        out.append(sized != null ? sized : value.getClass().getSimpleName());
                    }
                } catch (Throwable ignored) {
                    out.append("err");
                }
                if (out.length() > 280) return out.append('…').toString();
            }
        }
        return out.length() == 0 ? "nofields" : out.toString();
    }

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

    private void noteGate(String line) {
        if (probed.size() >= MAX_PROBE_LINES || !probed.add(line)) return;
        log("%s", line);
    }
}
