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
 * <b>service</b> (writable in this module app process); the hooked Maps process reads it once at
 * startup through the read-only hook interface (see {@link DiagLog} / {@link ModuleRuntime}).
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
                ? "默认关闭。打开后请重启手机（地图和 Play 服务都要重新启动），复现后把「下载/TimelineUnlocker」里当天的全部 txt 一起发回：每个进程一个文件（maps、gms、gms.persistent…）。"
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
                        ? "已打开。请重启手机，文件出现在系统「下载/TimelineUnlocker」。"
                        : "已关闭。地图下次重启后停止写。",
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
