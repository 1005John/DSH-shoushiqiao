package com.dsh.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String DSH_PKG = "io.github.ccch1mneyyy.dsh_app";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(56, 72, 56, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("DSH 手势桥");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#1A73E8"));

        TextView desc = new TextView(this);
        desc.setText("双击眼镜镜腿 → 开始说话\n再双击 → 结束并发送\n\n需要两步设置：");
        desc.setTextSize(15);
        desc.setPadding(0, 28, 0, 36);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, 36);

        Button a11y = new Button(this);
        a11y.setAllCaps(false);
        a11y.setText("① 开启无障碍服务");
        a11y.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) { }
        });

        Button start = new Button(this);
        start.setAllCaps(false);
        start.setText("② 启动手势服务");
        start.setOnClickListener(v -> {
            startForegroundService(new Intent(this, BridgeService.class));
            BridgeService.log("用户点击启动");
            refresh();
        });

        Button stop = new Button(this);
        stop.setAllCaps(false);
        stop.setText("停止服务");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            refresh();
        });

        Button test = new Button(this);
        test.setAllCaps(false);
        test.setText("测试一次点击（点「开始录音」）");
        test.setOnClickListener(v -> {
            status.setText("测试中…");
            new Thread(() -> {
                ClickA11yService s = ClickA11yService.get();
                if (s == null) {
                    runOnUiThread(() -> status.setText("无障碍未开启"));
                    return;
                }
                boolean ok = s.clickInPackage(DSH_PKG, "开始录音", "语音输入", "语音");
                runOnUiThread(() -> status.setText(ok ? "测试成功：已点击" : "测试失败：没找到控件"));
            }).start();
        });

        root.addView(title);
        root.addView(desc);
        root.addView(status);
        root.addView(a11y);
        root.addView(start);
        root.addView(stop);
        root.addView(test);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        refresh();

        // 打开 App 就自动拉起服务，不用再手动点②
        try {
            startForegroundService(new Intent(this, BridgeService.class));
            BridgeService.log("打开应用，自动启动服务");
        } catch (Throwable ignored) { }

        // 运行时申请权限：重装后系统会重置，不申请就会"双击没反应"
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** 重装后系统会重置运行时权限，不重新申请就会出现"双击没反应" */
    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CALENDAR);
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_CALENDAR);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 1001) {
            StringBuilder sb = new StringBuilder("权限结果：");
            for (int i = 0; i < perms.length && i < results.length; i++) {
                sb.append(perms[i].substring(perms[i].lastIndexOf('.') + 1))
                        .append(results[i] == PackageManager.PERMISSION_GRANTED ? "=允许 " : "=拒绝 ");
            }
            BridgeService.log(sb.toString());
            refresh();
        }
    }

    private void refresh() {
        boolean a = ClickA11yService.isRunning();
        status.setText("无障碍：" + (a ? "已开启 ✓" : "未开启 ✗（必须先开）"));
    }
}
