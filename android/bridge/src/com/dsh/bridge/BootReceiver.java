package com.dsh.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自动启动手势服务。
 * 之前每次重装/重启都要手动点「② 启动手势服务」，很容易漏，
 * 漏了就会表现为"双击没反应"。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        try {
            Intent i = new Intent(context, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            BridgeService.log("开机/更新后自动启动（" + a + "）");
        } catch (Throwable t) {
            BridgeService.log("自动启动失败：" + t);
        }
    }
}