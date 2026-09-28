package io.github.timeline_unlocker.xposed;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/** Launcher screen. The hooks do not run here; this only copies their log files out. */
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
        version.setPadding(0, dp(4), 0, dp(20));
        root.addView(version);

        TextView body = text(
                "国家码伪装和道路贴合仍按原模块工作，这里不改那些逻辑。\n\n"
                        + "地图、Play 服务、GSF 启动后会把调试日志写到各自的外部文件目录。"
                        + "点下面的按钮，把三份日志合成一个文件放到「下载/TimelineUnlocker」，并弹出分享。",
                15, "#E0E0E0");
        body.setPadding(0, 0, 0, dp(24));
        root.addView(body);

        Button export = new Button(this);
        export.setText("导出调试日志");
        export.setAllCaps(false);
        export.setOnClickListener(v -> exportLogs());
        root.addView(export);

        TextView hint = text("改完作用域后要强停地图和 Play 服务，或重启一次，钩子才会装上。", 12, "#757575");
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint);
        setContentView(root);
    }

    private void exportLogs() {
        try {
            File out = DiagLog.exportToDownloads(this);
            Toast.makeText(this, "已导出 " + out.getName(), Toast.LENGTH_LONG).show();
            share(out);
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void share(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".logfile", file);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("timeline-log", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "发送调试日志"));
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
