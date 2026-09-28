package io.github.timeline_unlocker.xposed;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings only. The switch is stored in libxposed remote preferences; the hooked Maps process
 * reads it once at startup (see {@link DiagLog}). No export button, no file merging, no host
 * private-directory reads.
 */
public class LogExportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        boolean active = ModuleRuntime.available();

        Switch toggle = new Switch(this);
        toggle.setText("调试日志");
        toggle.setTextColor(Color.parseColor("#FFFFFF"));
        toggle.setEnabled(active);
        toggle.setChecked(active && ModuleRuntime.readSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_ON));
        toggle.setOnCheckedChangeListener(this::onToggle);
        root.addView(toggle);

        TextView body = text(
                active
                        ? "默认关闭。打开后回到地图，日志出现在系统「下载」。不用强停。"
                        : "模块未在 LSPosed 中激活，无法保存开关。请先激活模块。",
                15, "#E0E0E0");
        body.setPadding(0, dp(16), 0, dp(24));
        root.addView(body);

        TextView open = text("打开系统下载", 16, "#8AB4F8");
        open.setOnClickListener(v -> openDownloads());
        root.addView(open);
        setContentView(root);
    }

    private void onToggle(CompoundButton button, boolean checked) {
        boolean ok = ModuleRuntime.writeSwitch(DiagLog.PREFS_NAME, DiagLog.KEY_ON, checked);
        if (!ok) {
            button.setOnCheckedChangeListener(null);
            button.setChecked(!checked);
            button.setOnCheckedChangeListener(this::onToggle);
            Toast.makeText(this, "开关没写上，模块可能未激活。", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
                checked
                        ? "已打开。回到地图即可，文件出现在系统「下载」。"
                        : "已关闭。回到地图后停止写。",
                Toast.LENGTH_LONG).show();
    }

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
