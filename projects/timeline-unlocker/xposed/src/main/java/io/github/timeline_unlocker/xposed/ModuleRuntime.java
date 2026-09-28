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
 *       {@link XposedInterface#getRemotePreferences} is <b>read-only</b> — perfect for reading the
 *       switch once at Maps startup.</li>
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
