package io.github.timeline_unlocker.xposed;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Bridge between the module's settings UI and the hooked Maps process (libxposed API 102).
 *
 * <p>LSPosed exposes <b>two different interfaces</b>, and they are not interchangeable:</p>
 * <ul>
 *   <li><b>Read side (hooked host, e.g. Maps):</b> the module is loaded into the host process, so
 *       {@link MainHook#onModuleLoaded} binds an {@link XposedInterface}. Its
 *       {@link XposedInterface#getRemotePreferences} is <b>read-only</b>, and LSPosed pushes edits to it, so the
 *       switch is followed live (see {@link #watchSwitch}).</li>
 *   <li><b>Write side (module's own app/UI process):</b> the module package is NOT in its own
 *       scope, so {@code onModuleLoaded} never fires here and no {@link XposedInterface} exists.
 *       The UI reaches the framework through {@link XposedService} instead — delivered by the
 *       {@code XposedProvider} in the libxposed-service AAR via
 *       {@link XposedServiceHelper#registerListener}. Only {@link XposedService#getRemotePreferences}
 *       is writable, and the value it commits is what the Maps process later reads.</li>
 * </ul>
 *
 * <p>This is the exact split pixelify / XVC use. Writing through the hook interface from the UI
 * process — what log35 did — silently fails: the interface is null there and, even when present,
 * read-only. That is why the switch could not be turned on.</p>
 *
 * <p>Everything fails closed: no framework / no service means the switch reads OFF and a write
 * reports failure rather than throwing.</p>
 */
public final class ModuleRuntime {

    /** Hook-side interface, bound in the hooked host process only. Read-only prefs. */
    private static volatile XposedInterface framework;

    /** Service-side interface, bound in the module's own process only. Writable prefs. */
    private static volatile XposedService service;

    private static volatile boolean listenerRegistered;

    private ModuleRuntime() {}

    // ---- read side (hooked host process) ------------------------------------------------

    /** Called from {@link MainHook#onModuleLoaded} in the hooked host process. */
    static void bind(XposedInterface base) {
        framework = base;
    }

    /** Mirror one line into the LSPosed module log (Manager -> Logs). Never throws. */
    static void frameworkLog(String message) {
        try {
            XposedInterface base = framework;
            if (base != null) base.log(android.util.Log.INFO, "TimelineUnlocker", message);
        } catch (Throwable ignored) {
        }
    }

    /** "LSPosed 1.x (code)" for the log header; empty when not in a hooked process. */
    static String frameworkLine() {
        try {
            XposedInterface base = framework;
            if (base == null) return "";
            return base.getFrameworkName() + " " + base.getFrameworkVersion()
                    + " (" + base.getFrameworkVersionCode() + ") api=" + base.getApiVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Read a boolean switch once from the host process. Defaults to false on any failure.
     * Uses the read-only hook interface.
     */
    static boolean switchOn(String prefsName, String key) {
        try {
            XposedInterface base = framework;
            if (base == null) return false;
            SharedPreferences prefs = base.getRemotePreferences(prefsName);
            return prefs.getBoolean(key, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read a long from an already-open remote prefs object. Defaults to 0 on any failure. */
    static long readLong(SharedPreferences prefs, String key) {
        try {
            return prefs.getLong(key, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Read a long by prefs name in the hooked process. Defaults to 0 on any failure. */
    static long readLong(String prefsName, String key) {
        try {
            XposedInterface base = framework;
            if (base == null) return 0;
            return readLong(base.getRemotePreferences(prefsName), key);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Callback for {@link #watchSwitch}. */
    interface SwitchListener {
        void onSwitch(boolean on);
    }

    /**
     * Follow a boolean switch live in the hooked process. LSPosed delivers remote-preference
     * edits from the module app to every subscribed process; the returned object must be kept
     * strongly reachable. Returns null when the framework is not bound.
     */
    static Object watchSwitch(String prefsName, String key, SwitchListener listener) {
        return watchKey(prefsName, key, p -> listener.onSwitch(p.getBoolean(key, false)));
    }

    /** Callback for {@link #watchKey}. */
    interface KeyListener {
        void onChange(SharedPreferences prefs);
    }

    /**
     * Run {@code listener} whenever the module app edits {@code key}. Only edits fire it, never
     * the value already stored when the process started. Returns null when not bound.
     */
    static Object watchKey(String prefsName, String key, KeyListener listener) {
        try {
            XposedInterface base = framework;
            if (base == null) return null;
            SharedPreferences prefs = base.getRemotePreferences(prefsName);
            SharedPreferences.OnSharedPreferenceChangeListener l = (p, changed) -> {
                // changed == null is the "cleared" signal on newer Android.
                if (changed == null || key.equals(changed)) {
                    try {
                        listener.onChange(p);
                    } catch (Throwable ignored) {
                    }
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(l);
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- write side (module's own app/UI process) ---------------------------------------

    /**
     * Start listening for the Xposed service. Idempotent; call once from the settings UI. The
     * service binds asynchronously shortly after the process starts (only when the module is
     * activated in LSPosed), so the UI enables its controls once {@link #serviceReady()} is true.
     */
    public static synchronized void startServiceBinding() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
            }

            @Override
            public void onServiceDied(XposedService dead) {
                if (service == dead) service = null;
            }
        });
    }

    /** True when the writable Xposed service is connected in this (module app) process. */
    public static boolean serviceReady() {
        return service != null;
    }

    /**
     * Read the switch from the module's own process for UI display, through the writable service's
     * remote group (the same group Maps reads). Defaults to false on any failure.
     */
    public static boolean readSwitch(String prefsName, String key) {
        try {
            XposedService bound = service;
            if (bound == null) return false;
            SharedPreferences prefs = bound.getRemotePreferences(prefsName);
            return prefs.getBoolean(key, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Write a fresh open-request timestamp; Maps consumes it once and runs the opener ladder
     * (captured veneer method first, deep links last). Returns false only when the write failed.
     */
    public static boolean requestOpen(String prefsName, String key) {
        try {
            XposedService bound = service;
            if (bound == null) return false;
            SharedPreferences prefs = bound.getRemotePreferences(prefsName);
            return prefs.edit().putLong(key, System.currentTimeMillis()).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ask every hooked process to restart itself: writes a fresh timestamp, which LSPosed pushes
     * to every process listening on that key. No root involved.
     */
    public static boolean requestReload(String prefsName, String key) {
        try {
            XposedService bound = service;
            if (bound == null) return false;
            SharedPreferences prefs = bound.getRemotePreferences(prefsName);
            return prefs.edit().putLong(key, System.currentTimeMillis()).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** One hooked process as the framework sees it. */
    public static final class Target {
        public final String process;
        public final long loadedVersion;
        final io.github.libxposed.service.HookedTarget raw;

        Target(String process, long loadedVersion, io.github.libxposed.service.HookedTarget raw) {
            this.process = process;
            this.loadedVersion = loadedVersion;
            this.raw = raw;
        }
    }

    /**
     * Ask the framework to hot-reload the module in one hooked process. The daemon thaws a cached
     * (frozen) process first, so this reaches processes a preference push cannot wake. The module
     * answers by restarting the process ({@link MainHook#onHotReloading}). Builds before 4.8 do
     * not answer and refuse. Returns false if the request could not be sent.
     */
    public static boolean hotReload(Target target) {
        try {
            XposedService bound = service;
            if (bound == null || target.raw == null) return false;
            bound.hotReloadModule(target.raw, null, (t, r) -> { });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Hooked processes running right now, with the module version each one has loaded. */
    public static java.util.List<Target> runningTargets() {
        java.util.List<Target> out = new java.util.ArrayList<>();
        try {
            XposedService bound = service;
            if (bound == null) return out;
            for (io.github.libxposed.service.HookedTarget t : bound.getRunningTargets()) {
                out.add(new Target(t.getProcessName(), t.getLoadedVersionCode(), t));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Write the switch from the module's own process through the Xposed service. Returns true only
     * when the value was committed to the remote group the host reads.
     */
    public static boolean writeSwitch(String prefsName, String key, boolean value) {
        try {
            XposedService bound = service;
            if (bound == null) return false;
            SharedPreferences prefs = bound.getRemotePreferences(prefsName);
            return prefs.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }
}
