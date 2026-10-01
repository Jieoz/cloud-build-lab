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

        body = text("", 15, "#E0E0E0");
        body.setPadding(0, dp(16), 0, dp(24));
        root.addView(body);

        TextView timeline = text("在地图中打开时间轴", 18, "#8AB4F8");
        timeline.setPadding(0, dp(8), 0, dp(20));
        timeline.setOnClickListener(v -> openTimeline());
        root.addView(timeline);

        TextView restart = text("重新加载地图和 Play 服务", 16, "#8AB4F8");
        restart.setPadding(0, 0, 0, dp(20));
        restart.setOnClickListener(v -> restartTargets());
        root.addView(restart);
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
                ? "默认关闭。打开后立即生效，不用重启。复现后把「下载/TimelineUnlocker」里当天的全部 txt 一起发回：每个进程一个文件（maps、gms、gms.persistent…）。\n\n刚装或更新模块后，点一次「重新加载地图和 Play 服务」让伪装生效，不需要 root，也不用重启手机。"
                : "正在连接 LSPosed 框架…若长时间显示此状态，请确认模块已在 LSPosed 中激活。");
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
     * Opens Timeline through a Maps deep link instead of relying on the in-app entry, which the
     * server can hide on any given day. Tried in order; the first link Maps accepts wins.
     */
    private void openTimeline() {
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
     * Hooks install when a process starts, so after installing or updating the module the three
     * Google packages must start again. No root: every hooked process listens for a reload
     * request pushed through LSPosed remote preferences and restarts itself. Then the framework's
     * list of hooked processes shows which ones now run this build.
     */
    private void restartTargets() {
        if (!ModuleRuntime.requestReload(DiagLog.PREFS_NAME, DiagLog.KEY_RELOAD)) {
            Toast.makeText(this, "框架未连接，请确认模块已在 LSPosed 中激活。", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在重新加载…", Toast.LENGTH_SHORT).show();
        main.postDelayed(() -> {
            openMaps();
            main.postDelayed(this::showTargets, 2500);
        }, 1500);
    }

    /** Which hooked processes run this build and which still run an older one. */
    private void showTargets() {
        long current = currentVersionCode();
        java.util.List<String> stale = new java.util.ArrayList<>();
        int fresh = 0;
        java.util.List<ModuleRuntime.Target> targets = ModuleRuntime.runningTargets();
        if (targets.isEmpty()) {
            status.setText("已发出重新加载。框架没有返回进程列表，以日志首行的版本号为准。");
            return;
        }
        for (ModuleRuntime.Target t : targets) {
            if (t.loadedVersion == current) fresh++;
            else stale.add(staleLine(t.process, t.loadedVersion));
        }
        if (stale.isEmpty()) {
            status.setText("重新加载完成：" + fresh + " 个进程已在用当前版本。");
            return;
        }
        status.setText("还有进程在用旧版本（多半是刚装的这一版，它们还不认识重新加载）：\n"
                + String.join("\n", stale)
                + "\n\n只需这一次：在 Play 服务的应用信息里点「强行停止」，再点一次重新加载。以后就不用了。");
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", GMS, null)));
        } catch (Throwable ignored) {
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

    private static final String GMS = "com.google.android.gms";

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
