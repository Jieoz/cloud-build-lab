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

/** Settings only. The hooked apps write the log into the system Downloads list. */
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
                "默认关闭。打开后，地图、Play 服务、GSF 自己把日志写进系统「下载」。改完开关要强停这三个应用，或重启一次。",
                15, "#E0E0E0");
        body.setPadding(0, dp(16), 0, dp(24));
        root.addView(body);

        TextView open = text("打开系统下载", 16, "#8AB4F8");
        open.setOnClickListener(v -> openDownloads());
        root.addView(open);
        setContentView(root);
    }

    private void onToggle(CompoundButton button, boolean checked) {
        try {
            DiagLog.setEnabled(this, checked);
        } catch (Throwable t) {
            button.setChecked(!checked);
            Toast.makeText(this, "开关没写上: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
                checked
                        ? "已打开。强停地图和 Play 服务后再进地图，文件出现在系统「下载」。"
                        : "已关闭。正在运行的地图和 Play 服务要强停后才停止写。",
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
