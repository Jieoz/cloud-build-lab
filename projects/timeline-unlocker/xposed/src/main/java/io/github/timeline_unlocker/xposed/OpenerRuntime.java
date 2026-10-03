package io.github.timeline_unlocker.xposed;

import android.content.Context;
import android.content.Intent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime side of the opener: capture which obfuscated method opens the Timeline page, then
 * drive it on request without the in-app entry. Maps main process only, debug log on only —
 * with the log off nothing here hooks or runs, matching {@link EntryWatch}.
 *
 * <p>Capture: the {@code TimelineWrapper} ctor hook in {@link MainHook} feeds the construction
 * stack here; the lowest obfuscated frames become candidates, and candidate methods are hooked to
 * observe natural opens. The first candidate that fires within the confirm window after a
 * wrapper construction is the real opener ({@link OpenerDriver#confirms}); its {@code this} and
 * the date argument are kept for replay.</p>
 *
 * <p>Replay: the module UI writes a fresh timestamp into the {@code open_request} key; this class
 * consumes it once and runs the {@link OpenerDriver#ladder} on the main thread. Success is only
 * ever declared from a {@code TimelineWrapper} sighting inside the window — never from "no
 * exception", which would mean nothing.</p>
 */
final class OpenerRuntime {

    private static final String STATE = "timeline-unlocker-opener.properties";
    private static final String KEY_CANDIDATES = "candidates";
    private static final String KEY_CONFIRMED = "confirmed";
    private static final String KEY_ARGDUMP = "argdump";
    private static final long CONFIRM_WINDOW_MS = 4_000;
    private static final long VERDICT_DELAY_MS = 2_000;
    /** A request written this recently is honored by a process that starts after it. */
    private static final long STARTUP_GRACE_MS = 60_000;
    /** Deep links inside Maps' own process, last in the ladder (same list as the module UI). */
    private static final String[] LINKS = {
            "https://www.google.com/maps/timeline"};

    private static volatile boolean armed;
    private static volatile Context context;
    private static volatile ClassLoader classLoader;
    private static final Properties state = new Properties();
    private static volatile boolean loaded;
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "timeline-unlocker-opener");
        t.setDaemon(true);
        return t;
    });
    private static volatile android.os.Handler main;

    /** Live references for replay: newest instance per candidate class, last captured argument. */
    private static final Map<String, WeakReference<Object>> instances = new ConcurrentHashMap<>();
    private static volatile Object capturedArg;
    private static volatile String capturedArgDump;
    private static volatile String confirmedClass;
    private static volatile String confirmedMethod;
    private static volatile long wrapperSeenAt;
    private static volatile long pageSeenAt;
    private static final AtomicInteger attempts = new AtomicInteger();
    private static volatile long stepStartedAt;
    private static volatile List<OpenerDriver.Step> pending;

    /** One request per timestamp, survives process restarts via the state file. */
    private static final OpenerState consumer = new OpenerState(4);

    private OpenerRuntime() {}

    /** Executes hooks in the hooked process; implemented by MainHook (XposedModule.hook). */
    interface Hooker {
        io.github.libxposed.api.XposedInterface.HookBuilder hook(java.lang.reflect.Executable member);
    }

    private static volatile Hooker hooker;

    /** Maps main process, log switch on. Idempotent. */
    static synchronized void arm(Context app, Hooker hookExecutor) {
        if (armed || !DiagLog.isEnabled() || app == null || hookExecutor == null) return;
        armed = true;
        context = app;
        hooker = hookExecutor;
        classLoader = app.getClassLoader();
        main = new android.os.Handler(android.os.Looper.getMainLooper());
        io.execute(() -> {
            load();
            installFromState();
        });
    }

    /** The TimelineWrapper ctor fired: record the sighting and pick up new candidates. */
    static void onWrapperConstructed(StackTraceElement[] stack) {
        if (!armed) return;
        wrapperSeenAt = System.currentTimeMillis();
        final OpenerCapture.Frame[] frames = OpenerCapture.pickVeneerFrames(stack);
        if (frames.length == 0) return;
        io.execute(() -> {
            load();
            String csv = OpenerCapture.encodeCandidates(frames);
            boolean changed = !csv.equals(state.getProperty(KEY_CANDIDATES, ""));
            if (changed) {
                // New candidates first: the newest stack is the best guess for the current build.
                state.setProperty(KEY_CANDIDATES, csv);
            }
            for (OpenerCapture.Frame f : frames) {
                if (installCandidateHook(f.className, f.methodName) && !changed) changed = true;
            }
            if (changed) save();
            DiagLog.line("opener wrapper stack candidates=" + csv
                    + (changed ? " (updated)" : " (known)"));
        });
    }

    /** The module UI asked for an open: consume the newest request once and run the ladder. */
    static void onRequest(long request) {
        if (!armed || !DiagLog.isEnabled()) return;
        if (!consumer.shouldHandle(request)) return;
        DiagLog.line("opener request accepted ts=" + request);
        android.os.Handler h = main;
        if (h == null) return;
        h.post(OpenerRuntime::runLadder);
    }

    /**
     * Called once when the Maps process arms the opener: if the UI wrote a request while no
     * process was alive (reload race), a fresh one is still honored instead of being lost.
     */
    static void consumeStartupRequest(long request) {
        long now = System.currentTimeMillis();
        if (!OpenerState.isFresh(now, request, STARTUP_GRACE_MS)) return;
        DiagLog.line("opener startup request pending age_ms=" + (now - request));
        onRequest(request);
    }

    // ---- ladder -----------------------------------------------------------------------------

    private static void runLadder() {
        attempts.set(0);
        String argDump = capturedArgDump;
        boolean haveArg = capturedArg != null && confirmedClass != null;
        String[] candidates;
        synchronized (state) {
            candidates = OpenerCapture.decodeCandidates(state.getProperty(KEY_CANDIDATES, ""));
        }
        List<OpenerDriver.Step> steps = OpenerDriver.ladder(
                haveArg, confirmedClass, confirmedMethod, haveArg ? argDump : null,
                candidates, LINKS);
        if (steps.isEmpty()) {
            DiagLog.line("opener no steps: no capture yet, open Timeline once with the entry"
                    + " present so the module learns the method");
            return;
        }
        DiagLog.line("opener ladder start steps=" + steps.size());
        pending = steps;
        wrapperSeenAt = 0; // ignore sightings from before this ladder
        runStep(0);
    }

    private static void runStep(final int index) {
        List<OpenerDriver.Step> steps = pending;
        if (steps == null || index >= steps.size()) {
            DiagLog.line("opener ladder exhausted attempts=" + attempts.get());
            pending = null;
            return;
        }
        OpenerDriver.Step step = steps.get(index);
        stepStartedAt = System.currentTimeMillis();
        attempts.incrementAndGet();
        pageSeenAt = 0; // only a sighting after this step began counts for it
        boolean invoked;
        try {
            invoked = execute(step);
        } catch (Throwable t) {
            DiagLog.line("opener step failed: " + step.describe() + " "
                    + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage()));
            invoked = false;
        }
        final long startedAt = stepStartedAt;
        main.postDelayed(() -> {
            boolean opened = OpenerDriver.confirms(attempts.get(), wrapperSeenAt, startedAt,
                    CONFIRM_WINDOW_MS)
                    // The deep link can land on the Timeline page without constructing
                    // TimelineWrapper (observed 10-03): a page sighting confirms too.
                    || OpenerDriver.confirms(attempts.get(), pageSeenAt, startedAt,
                            CONFIRM_WINDOW_MS);
            DiagLog.line(OpenerDriver.verdict(step.describe(), opened));
            if (opened) {
                pending = null;
                noteOpened();
                return;
            }
            runStep(index + 1);
        }, VERDICT_DELAY_MS);
    }

    /** MainHook's entry scan reports Timeline texts on screen; feeds link-step verdicts. */
    static void onPageTexts(boolean timelineText, int views) {
        if (!armed || !timelineText || views < ResumeVerdict.MIN_SCREEN_VIEWS) return;
        pageSeenAt = System.currentTimeMillis();
    }

    private static boolean execute(OpenerDriver.Step step) {
        if (step.kind == OpenerDriver.KIND_LINK) {
            Context app = context;
            if (app == null) return false;
            app.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(step.link))
                    .setPackage("com.google.android.apps.maps")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        boolean withArg = step.kind == OpenerDriver.KIND_VENEER_ARG;
        return invokeVeneer(step.className, step.methodName, withArg);
    }

    /** Reflectively call {@code className.methodName(arg|null)} on the newest known instance. */
    private static boolean invokeVeneer(String className, String methodName, boolean withArg) {
        ClassLoader cl = classLoader;
        if (cl == null) return false;
        Class<?> type;
        try {
            type = cl.loadClass(className);
        } catch (Throwable t) {
            DiagLog.line("opener class gone: " + className + " (" + t.getClass().getSimpleName() + ")");
            return false;
        }
        Method target = null;
        for (Method m : type.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1) {
                target = m; // the veneer shape: one argument (the date protobuf)
                break;
            }
            if (params.length == 0 && target == null) target = m;
        }
        if (target == null) {
            DiagLog.line("opener method missing: " + className + "." + methodName);
            return false;
        }
        target.setAccessible(true);
        Object receiver = null;
        if (!java.lang.reflect.Modifier.isStatic(target.getModifiers())) {
            WeakReference<Object> ref = instances.get(className);
            receiver = ref == null ? null : ref.get();
            if (receiver == null) {
                DiagLog.line("opener no instance yet: " + className
                        + " (appears when Maps builds its graph)");
                return false;
            }
        }
        Object arg = withArg ? capturedArg : null;
        Class<?>[] params = target.getParameterTypes();
        try {
            if (params.length == 0) {
                target.invoke(receiver);
            } else {
                target.invoke(receiver, params[0].isPrimitive() ? null : arg);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            DiagLog.line("opener invoke threw: " + target.getName() + " "
                    + (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName()));
            return false;
        } catch (IllegalAccessException e) {
            DiagLog.line("opener invoke denied: " + target.getName()
                    + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
        return true;
    }

    // ---- candidate hooks ----------------------------------------------------------------------

    /** Hook one candidate for observation; also grabs instances from its constructors. */
    private static boolean installCandidateHook(final String className, final String methodName) {
        ClassLoader cl = classLoader;
        if (cl == null) return false;
        try {
            Class<?> type = cl.loadClass(className);
            for (final Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length > 1) continue;
                hookCandidateMethod(className, m, params.length);
            }
            hookCandidateConstructors(className, type);
            return true;
        } catch (Throwable t) {
            DiagLog.line("opener hook failed: " + className + "." + methodName
                    + " (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    private static void hookCandidateMethod(final String className, final Method m,
                                            final int paramCount) {
        try {
            hookIn(m).intercept(chain -> {
                try {
                    Object thiz = chain.getThisObject();
                    if (thiz != null) instances.put(className, new WeakReference<>(thiz));
                    Object arg = paramCount == 1 && !chain.getArgs().isEmpty()
                            ? chain.getArgs().get(0) : null;
                    if (arg != null) {
                        capturedArg = arg;
                        capturedArgDump = OpenerCapture.dumpFields(arg);
                    }
                    noteNaturalFire(className, m.getName(), arg, thiz == null);
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            DiagLog.line("opener method hook failed: " + className + "." + m.getName()
                    + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookCandidateConstructors(final String className, Class<?> type) {
        try {
            for (final Constructor<?> ctor : type.getDeclaredConstructors()) {
                hookIn(ctor).intercept(chain -> {
                    try {
                        instances.put(className,
                                new WeakReference<>(chain.getThisObject()));
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            DiagLog.line("opener ctor hook failed: " + className
                    + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void installFromState() {
        String[] candidates;
        synchronized (state) {
            candidates = OpenerCapture.decodeCandidates(state.getProperty(KEY_CANDIDATES, ""));
        }
        for (String key : candidates) {
            int dot = key.lastIndexOf('.');
            if (dot <= 0) continue;
            installCandidateHook(key.substring(0, dot), key.substring(dot + 1));
        }
        String confirmed;
        synchronized (state) {
            confirmed = state.getProperty(KEY_CONFIRMED, "");
        }
        if (!confirmed.isEmpty()) {
            int dot = confirmed.lastIndexOf('.');
            if (dot > 0) {
                confirmedClass = confirmed.substring(0, dot);
                confirmedMethod = confirmed.substring(dot + 1);
            }
        }
        synchronized (state) {
            String dump = state.getProperty(KEY_ARGDUMP, "");
            if (!dump.isEmpty()) capturedArgDump = dump;
        }
    }

    // ---- state ------------------------------------------------------------------------------

    private static void noteNaturalFire(String className, String methodName, Object arg,
                                        boolean isStatic) {
        final String key = className + "." + methodName;
        final String dump = arg == null ? null : OpenerCapture.dumpFields(arg);
        io.execute(() -> {
            load();
            if (!key.equals(state.getProperty(KEY_CONFIRMED, ""))) {
                state.setProperty(KEY_CONFIRMED, key);
                DiagLog.line("opener confirmed " + key + (isStatic ? " (static)" : ""));
            }
            if (dump != null && !dump.isEmpty() && !dump.equals(state.getProperty(KEY_ARGDUMP, ""))) {
                state.setProperty(KEY_ARGDUMP, dump);
                DiagLog.line("opener captured arg fields=" + OpenerCapture.dumpSize(dump)
                        + " " + dump);
            }
            save();
        });
    }

    private static void noteOpened() {
        io.execute(() -> {
            load();
            state.setProperty("last_opened", String.valueOf(System.currentTimeMillis()));
            save();
        });
    }

    private static java.io.File stateFile() {
        Context app = context;
        return app == null ? null : new java.io.File(app.getNoBackupFilesDir(), STATE);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        java.io.File f = stateFile();
        if (!f.exists()) return;
        java.util.Properties loadedState = OpenerState.loadQuietly(f);
        synchronized (state) {
            state.clear();
            state.putAll(loadedState);
        }
    }

    private static void save() {
        java.util.Properties copy;
        synchronized (state) {
            copy = new java.util.Properties();
            copy.putAll(state);
        }
        OpenerState.saveQuietly(copy, stateFile());
    }

    private static io.github.libxposed.api.XposedInterface.HookBuilder hookIn(
            java.lang.reflect.Executable member) {
        Hooker executor = hooker;
        if (executor == null) throw new IllegalStateException("opener not bound to MainHook");
        return executor.hook(member);
    }
}
