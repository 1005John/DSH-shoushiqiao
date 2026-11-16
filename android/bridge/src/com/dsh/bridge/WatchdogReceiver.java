package com.dsh.bridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 看门狗：每 10 分钟自检一次，服务没跑就拉起来。
 *
 * MIUI 只要内存紧张就可能杀掉前台服务（前台服务不是免死金牌）。
 * 这里用 AlarmManager 的 allow-while-idle 闹钟，即使设备在打盹也能唤醒，
 * 杀掉后最多 10 分钟自动复活。
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final int REQ = 2001;
    private static final long INTERVAL_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            // 拉起服务（若已在运行，Android 不会重复创建，只调用 onStartCommand）
            Intent i = new Intent(context, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            BridgeService.log("看门狗：服务自检/拉起");
        } catch (Throwable t) {
            BridgeService.log("看门狗拉起失败：" + t);
        }
        // 续上下一轮
        schedule(context);
    }

    /** 安排下一次自检 */
    public static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, WatchdogReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, REQ, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long trigger = SystemClock.elapsedRealtime() + INTERVAL_MS;
            // setAndAllowWhileIdle：不需要精确闹钟权限，打盹期也能触发
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Throwable t) {
            BridgeService.log("看门狗排程失败：" + t);
        }
    }
}
