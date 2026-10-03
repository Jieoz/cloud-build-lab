package io.github.timeline_unlocker.xposed;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings only. The switch is stored in libxposed remote preferences through the Xposed
 * <b>service</b> (writable in this module app process); every hooked process follows it live
 * through the read-only hook interface (see {@link DiagLog} / {@link ModuleRuntime}).
 * No export button, no file merging, no host private-directory reads.
 *
 * <p>The service binds asynchronously a moment after the process starts (only when the module is
 * activated in LSPosed), so the UI starts binding in {@code onCreate} and refreshes the control
 * state a few times until the service is ready — the switch was previously dead because the UI
 * tried to write through the hook interface, which does not exist in this process.</p>
 */
public class LogExportActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private Switch toggle;
    private Switch mapsUs;
    private TextView body;
    private TextView status;
    private int retries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ModuleRuntime.startServiceBinding();

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = text("时间轴入口", 22, "#FFFFFF");
        root.addView(title);
        TextView version = text(versionLine(), 13, "#9E9E9E");
        version.setPadding(0, dp(4), 0, dp(16));
        root.addView(version);

        toggle = new Switch(this);
        toggle.setText("调试日志");
        toggle.setTextColor(Color.parseColor("#FFFFFF"));
        toggle.setOnCheckedChangeListener(this::onToggle);
        root.addView(toggle);

        mapsUs = new Switch(this);
        mapsUs.setText("地图也读 us（实验）");
        mapsUs.setTextColor(Color.parseColor("#FFFFFF"));
        mapsUs.setPadding(0, dp(12), 0, 0);
        mapsUs.setOnCheckedChangeListener(this::onMapsUs);
        root.addView(mapsUs);
        TextView mapsUsHint = text("关：地图读真实 SIM（cn），蓝点由地图自己校正，对齐。"
                + "开：地图也读 us，模块代替地图做蓝点校正；切换后地图自动重新加载。", 13, "#9E9E9E");
        root.addView(mapsUsHint);

        body = text("", 15, "#E0E0E0");
        body.setPadding(0, dp(16), 0, dp(24));
        root.addView(body);

        TextView timeline = text("在地图中打开时间轴", 18, "#8AB4F8");
        timeline.setPadding(0, dp(8), 0, dp(20));
        timeline.setOnClickListener(v -> openTimeline());
        root.addView(timeline);

        TextView reloadMaps = text("重新加载地图", 16, "#8AB4F8");
        reloadMaps.setPadding(0, 0, 0, dp(16));
        reloadMaps.setOnClickListener(v -> reload(true));
        root.addView(reloadMaps);

        TextView reloadGms = text("重新加载 Play 服务", 16, "#8AB4F8");
        reloadGms.setPadding(0, 0, 0, dp(16));
        reloadGms.setOnClickListener(v -> reload(false));
        root.addView(reloadGms);
        status = text("", 14, "#E0E0E0");
        status.setPadding(0, 0, 0, dp(20));
        root.addView(status);

        TextView open = text("打开系统下载", 16, "#8AB4F8");
        open.setOnClickListener(v -> openDownloads());
        root.addView(open);
        setContentView(root);

        refreshState();
    }

    /**
     * Reflect the current service state. The service binds asynchronously, so re-check a few times
     * with a short backoff instead of deciding once at {@code onCreate}.
     */
    private void refreshState() {
        boolean ready = ModuleRuntime.serviceReady();
        setControlsWithoutCallback(
                ready,
                ready && ModuleRuntime.readSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_ON));
        body.setText(ready
                ? "默认关闭。打开后立即生效，不用重启。复现后把「下载/TimelineUnlocker」里当天的全部 txt 一起发回：每个进程一个文件（maps、gms、gms.persistent…）。\n\n查入口消失：日志一直开着正常用地图，入口没了就把这几天的 maps txt 发回（里面 watch 开头的行记录每次启动、入口出现/消失和配置变化）。\n\n刚装或更新模块后，分别点「重新加载 Play 服务」和「重新加载地图」让新版生效，不需要 root。"
                : "正在连接 LSPosed 框架…若长时间显示此状态，请确认模块已在 LSPosed 中激活。");
        if (ready) snapshot("open");
        if (!ready && retries < 10) {
            retries++;
            main.postDelayed(this::refreshState, 300);
        }
    }

    private void setControlsWithoutCallback(boolean enabled, boolean checked) {
        toggle.setOnCheckedChangeListener(null);
        toggle.setEnabled(enabled);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(this::onToggle);
        mapsUs.setOnCheckedChangeListener(null);
        mapsUs.setEnabled(enabled);
        mapsUs.setChecked(enabled && ModuleRuntime.readSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_MAPS_US));
        mapsUs.setOnCheckedChangeListener(this::onMapsUs);
    }

    /** Maps reads the identity once at start, so a change reloads Maps right away. */
    private void onMapsUs(CompoundButton button, boolean checked) {
        if (!ModuleRuntime.writeSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_MAPS_US, checked)) {
            button.setOnCheckedChangeListener(null);
            button.setChecked(!checked);
            button.setOnCheckedChangeListener(this::onMapsUs);
            Toast.makeText(this, "没写上，框架未连接或模块未激活。", Toast.LENGTH_LONG).show();
            return;
        }
        DiagLog.line("maps_us set " + checked);
        reload(true);
    }

    /**
     * The module app's own log file (timeline-*-module.txt). It records the framework's list of
     * hooked processes, so a missing GMS file can be told apart: GMS not hooked, GMS on an old
     * build, or GMS hooked but unable to write.
     */
    private void snapshot(String why) {
        if (!ModuleRuntime.readSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_ON)) return;
        if (!DiagLog.isEnabled()) DiagLog.bind(getApplicationContext(), true, "module");
        long current = currentVersionCode();
        java.util.List<ModuleRuntime.Target> targets = ModuleRuntime.runningTargets();
        DiagLog.line("snapshot " + why + " module=" + current + " hooked=" + targets.size());
        for (ModuleRuntime.Target t : targets) {
            DiagLog.line("  hooked " + t.process + " version=" + t.loadedVersion
                    + (t.loadedVersion == current ? "" : " (old)"));
        }
    }

    private void onToggle(CompoundButton button, boolean checked) {
        boolean ok = ModuleRuntime.writeSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_ON, checked);
        if (!ok) {
            setControlsWithoutCallback(button.isEnabled(), !checked);
            Toast.makeText(this, "开关没写上，框架未连接或模块未激活。", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
                checked
                        ? "已打开，立即生效。文件在系统「下载/TimelineUnlocker」。"
                        : "已关闭，立即停止写。",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Opens Timeline in Maps. Direct first: the in-process opener (veneer method capture / deep
     * links, judged by the TimelineWrapper sighting). The plain VIEW deep link stays as the
     * fallback for when Maps is not running the current build yet.
     */
    private void openTimeline() {
        if (ModuleRuntime.requestOpen(DiagLog.PREFS_NAME, DiagLog.KEY_OPEN_REQUEST)) {
            DiagLog.line("open request sent (in-process opener)");
            Toast.makeText(this, "已请求地图打开时间轴（进程内）。", Toast.LENGTH_SHORT).show();
            main.postDelayed(() -> {
                // No wrapper sighting will come from a dead Maps process; fall back to a link.
                if (!isMapsRunning()) openViaLinks();
            }, 2_500);
            return;
        }
        openViaLinks();
    }

    private boolean isMapsRunning() {
        for (ModuleRuntime.Target t : ModuleRuntime.runningTargets()) {
            if (t.process.startsWith(MAPS)) return true;
        }
        return false;
    }

    /** Plain VIEW deep links; the first one Maps accepts wins. */
    private void openViaLinks() {
        for (String link : TIMELINE_LINKS) {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))
                    .setPackage(MAPS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                Toast.makeText(this, "已发给地图：" + link, Toast.LENGTH_SHORT).show();
                return;
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(this, "地图没有接收时间轴链接，请确认已安装 Google 地图。", Toast.LENGTH_LONG).show();
    }

    private static final String MAPS = "com.google.android.apps.maps";
    /**
     * Hooks install when a process starts, so after installing or updating the module the hooked
     * processes must start again. No root: each hooked process listens on its group's key in
     * LSPosed remote preferences and kills itself when it changes. The system brings Play
     * services back on demand; Maps is reopened here. Afterwards the framework's process list
     * shows whether anything in the group still runs an older build.
     */
    private void reload(boolean maps) {
        String key = maps ? DiagLog.KEY_RELOAD_MAPS : DiagLog.KEY_RELOAD_GMS;
        if (!ModuleRuntime.requestReload(DiagLog.PREFS_NAME, key)) {
            Toast.makeText(this, "框架未连接，请确认模块已在 LSPosed 中激活。", Toast.LENGTH_LONG).show();
            return;
        }
        // Also go through the framework's hot reload: it thaws cached processes the push above
        // cannot wake. Processes from 4.8 on restart; older ones refuse and are shown afterwards.
        int asked = 0;
        for (ModuleRuntime.Target t : ModuleRuntime.runningTargets()) {
            boolean isMaps = DiagLog.KEY_RELOAD_MAPS.equals(DiagLog.reloadKeyFor(t.process));
            if (isMaps == maps && ModuleRuntime.hotReload(t)) asked++;
        }
        DiagLog.line("reload " + (maps ? "maps" : "gms") + " push sent, hot reload asked=" + asked);
        status.setText(maps ? "正在重新加载地图…" : "正在重新加载 Play 服务…");
        main.postDelayed(() -> {
            if (maps) openMaps();
            main.postDelayed(() -> showTargets(maps), 2500);
        }, 1500);
    }

    /** Which processes of the group run this build and which still run an older one. */
    private void showTargets(boolean maps) {
        snapshot(maps ? "after reload maps" : "after reload gms");
        long current = currentVersionCode();
        java.util.List<String> stale = new java.util.ArrayList<>();
        java.util.List<String> staleProcesses = new java.util.ArrayList<>();
        int fresh = 0;
        for (ModuleRuntime.Target t : ModuleRuntime.runningTargets()) {
            boolean isMaps = DiagLog.KEY_RELOAD_MAPS.equals(DiagLog.reloadKeyFor(t.process));
            if (isMaps != maps) continue;
            if (t.loadedVersion == current) fresh++;
            else {
                stale.add(staleLine(t.process, t.loadedVersion));
                staleProcesses.add(t.process);
            }
        }
        String name = maps ? "地图" : "Play 服务";
        if (stale.isEmpty()) {
            status.setText(fresh > 0
                    ? name + "已重新加载：" + fresh + " 个进程在用当前版本。"
                    : name + "已重新加载。现在没有正在运行的" + name + "进程，下次启动即用当前版本。");
            return;
        }
        // A reload request is a push to the process. Processes on a build before 4.4 never listen,
        // and a cached (frozen) process does not run the listener until it is thawed, so the push
        // cannot be relied on to replace them. Settings' force stop can, without root: open the
        // app info page of the package that owns the stale processes.
        String owner = maps ? MAPS : stalePackage(staleProcesses);
        String ownerName = maps ? "地图" : GSF.equals(owner) ? "Google 服务框架" : "Google Play 服务";
        status.setText(name + "还有进程在用旧版本：\n" + String.join("\n", stale) + "\n\n"
                + "已打开「" + ownerName + "」的应用信息：点「强行停止」→「确定」，然后返回这里，会自动再核对一次。\n\n"
                + "应用信息里找不到或点不了强行停止时：打开 LSPosed →「模块」→ 本模块 → 在作用域列表里长按「" + ownerName + "」→「强行停止」。");
        pendingCheck = maps ? CHECK_MAPS : CHECK_GMS;
        openAppInfo(owner);
    }

    /** Package whose app info page force-stops the given stale Play services processes. */
    static String stalePackage(java.util.List<String> processes) {
        boolean onlyGservices = !processes.isEmpty();
        for (String p : processes) {
            if (!p.startsWith("com.google.process.gservices")) onlyGservices = false;
        }
        // Force-stopping Play services takes gapps, unstable, persistent and the main process.
        // gservices belongs to the services framework and may survive it; then open that one.
        return onlyGservices ? GSF : GMS;
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开应用信息页", Toast.LENGTH_LONG).show();
        }
    }

    private static final String GMS = "com.google.android.gms";
    private static final String GSF = "com.google.android.gsf";
    private static final int CHECK_NONE = 0;
    private static final int CHECK_MAPS = 1;
    private static final int CHECK_GMS = 2;
    /** Set when app info was opened; on return the same group is checked again. */
    private int pendingCheck = CHECK_NONE;

    @Override
    protected void onResume() {
        super.onResume();
        int check = pendingCheck;
        if (check == CHECK_NONE) return;
        pendingCheck = CHECK_NONE;
        if (check == CHECK_MAPS) {
            // Maps was stopped by hand; start it so it loads the current build, then check.
            main.postDelayed(() -> {
                openMaps();
                main.postDelayed(() -> showTargets(true), 2500);
            }, 500);
        } else {
            // Play services restarts on demand; give it a moment before reading the list.
            main.postDelayed(() -> showTargets(false), 2500);
        }
    }

    static String staleLine(String process, long loadedVersion) {
        return "· " + DiagLog.shortProcess(process) + "（版本 " + loadedVersion + "）";
    }

    private long currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void openMaps() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(MAPS);
            if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }
    static final String[] TIMELINE_LINKS = {
            "https://www.google.com/maps/timeline",
            "https://timeline.google.com/maps/timeline",
    };

    private void openDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开系统下载", Toast.LENGTH_LONG).show();
        }
    }

    private String versionLine() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    private TextView text(String value, int sp, String color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.parseColor(color));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setGravity(Gravity.START);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
