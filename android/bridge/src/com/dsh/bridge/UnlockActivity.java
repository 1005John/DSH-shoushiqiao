package com.dsh.bridge;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 一个透明的"解锁壳"Activity。
 *
 * 为什么需要它：
 *   锁屏时无障碍拿不到目标 App 的窗口（dump 不到 EditText），
 *   "写入失败：找不到目标窗口"。而普通 App 无法直接让系统解锁。
 *
 * 做法（官方支持的正规路径）：
 *   setShowWhenLocked(true) + setTurnScreenOn(true)  显示在锁屏之上并点亮屏幕
 *   KeyguardManager.requestDismissKeyguard(...)      划开"无密码"的锁屏
 *   然后立刻 finish()，把前台交还给目标 App。
 *
 * 前提：手机没有设置安全锁（PIN / 图案 / 密码）。
 *       设了安全锁时 requestDismissKeyguard 会失败，这里只记录，不阻塞流程。
 */
public class UnlockActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) {
            BridgeService.log("解锁：没有 KeyguardManager");
            finish();
            return;
        }

        if (!km.isKeyguardLocked()) {
            BridgeService.log("解锁：本来就没锁");
            finish();
            return;
        }

        boolean secure = km.isKeyguardSecure();
        BridgeService.log("解锁：开始划开锁屏（有安全锁=" + secure + "）");

        if (Build.VERSION.SDK_INT >= 26) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    BridgeService.log("解锁：成功");
                    finish();
                }

                @Override
                public void onDismissCancelled() {
                    BridgeService.log("解锁：被取消（多半是设了安全锁）");
                    finish();
                }

                @Override
                public void onDismissError() {
                    BridgeService.log("解锁：失败");
                    finish();
                }
            });
        } else {
            finish();
        }

        // 兜底：无论回调是否回来，2.5 秒内一定关闭，绝不卡住流程
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 2500);
    }

    @Override
    public void finish() {
        super.finish();
        // 不要动画，避免锁屏上闪一下
        overridePendingTransition(0, 0);
    }
}
