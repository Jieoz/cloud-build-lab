package io.github.timeline_unlocker.xposed;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Launcher screen. The hooks do not run here. */
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

        Switch toggle = new Switch(this);
        toggle.setText("调试日志");
        toggle.setTextColor(Color.parseColor("#FFFFFF"));
        toggle.setChecked(DiagLog.isEnabled(this));
        toggle.setOnCheckedChangeListener(this::onToggle);
        root.addView(toggle);

        TextView body = text(
                "默认关闭，关闭时不写文件。打开后，地图、Play 服务、GSF 会把日志写到「下载/TimelineUnlocker」。"
                        + "改完开关要强停这三个应用，或重启一次。",
                15, "#E0E0E0");
        body.setPadding(0, dp(16), 0, dp(24));
        root.addView(body);

        Button export = new Button(this);
        export.setText("导出调试日志");
        export.setAllCaps(false);
        export.setOnClickListener(v -> exportLogs());
        root.addView(export);

        TextView hint = text("导出只是把下载目录里已有的会话文件合成一份。开关关着、或打开后还没重新进地图，导出来是空的。", 12, "#757575");
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint);
        setContentView(root);
    }

    private void onToggle(CompoundButton button, boolean checked) {
        DiagLog.setEnabled(this, checked);
        Toast.makeText(this,
                checked
                        ? "已打开。强停地图和 Play 服务后再进地图，日志才会写到「下载/TimelineUnlocker」。"
                        : "已关闭。正在运行的地图和 Play 服务要强停后才停止写日志。",
                Toast.LENGTH_LONG).show();
    }

    private void exportLogs() {
        try {
            File out = DiagLog.exportToDownloads(this);
            Toast.makeText(this, "已导出 " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(Uri.fromFile(out), "text/plain");
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(view);
            } catch (Throwable ignored) {
                // The file is already in Downloads. Opening it is optional.
            }
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String versionLine() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + info.versionName + " (" + info.versionCode + ") · 基于 SherlockChiang/ReLocationReportEnabler";
        } catch (Throwable t) {
            return "基于 SherlockChiang/ReLocationReportEnabler";
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
