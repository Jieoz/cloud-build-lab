package io.github.timeline_unlocker.xposed;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedInterface;

/**
 * Access to the module's settings through libxposed remote preferences (API 102).
 *
 * <p>LSPosed loads this module into its <b>own</b> app process as well as into each scoped host,
 * so {@link MainHook#onModuleLoaded} binds the framework in both. In the module's own process the
 * remote-preferences group is writable, so {@link LogExportActivity} writes the switch there; in a
 * scoped host it is read-only, so the hook reads it via {@link #switchOn}. This is the same bridge
 * pixelify / XVC use — no world-readable XML (removed in LSPosed 2.3), no broadcast, no polling.</p>
 *
 * <p>Reads and writes fail closed: if the framework or the group is unavailable, the switch is
 * OFF and a write reports failure rather than throwing.</p>
 */
public final class ModuleRuntime {

    private static volatile XposedInterface framework;

    private ModuleRuntime() {}

    static void bind(XposedInterface base) {
        framework = base;
    }

    /** Read a boolean switch once from remote preferences. Defaults to false on any failure. */
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

    /** Read from the module's own process for UI display. Defaults to false on any failure. */
    public static boolean readSwitch(String prefsName, String key) {
        return switchOn(prefsName, key);
    }

    /**
     * Write the switch from the module's own process. Returns true only when the value was
     * committed to the remote group the host reads.
     */
    public static boolean writeSwitch(String prefsName, String key, boolean value) {
        try {
            XposedInterface base = framework;
            if (base == null) return false;
            SharedPreferences prefs = base.getRemotePreferences(prefsName);
            return prefs.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the module runs under an LSPosed framework that bound the interface. */
    public static boolean available() {
        return framework != null;
    }
}
