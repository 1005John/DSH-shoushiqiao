package com.dsh.bridge;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * 本地 TTS 播放（小米内置引擎 com.xiaomi.mibrain.speech）。
 *
 * 为什么改用它：
 *   云端 TTS 接口对同样的输入会返回不同音色的音频（实测 5 次请求 3 种结果，
 *   偶尔整段变成男声），而且是网络请求，有 3~10 秒延迟。
 *   系统本地引擎完全离线、音色固定、几乎零延迟。
 *
 * 前提：settings put secure tts_default_synth com.xiaomi.mibrain.speech
 */
public class LocalTts {

    private static volatile TextToSpeech tts;
    private static volatile boolean ready = false;
    private static volatile boolean initFailed = false;

    public static synchronized void init(Context ctx) {
        if (tts != null || initFailed) return;
        try {
            final Context app = ctx.getApplicationContext();
            tts = new TextToSpeech(app, status -> {
                if (status != TextToSpeech.SUCCESS) {
                    initFailed = true;
                    BridgeService.log("本地 TTS 初始化失败 status=" + status);
                    return;
                }
                try {
                    int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        initFailed = true;
                        BridgeService.log("本地 TTS 不支持中文（" + r + "）");
                        return;
                    }
                    tts.setSpeechRate(1.0f);
                    tts.setPitch(1.0f);
                    ready = true;
                    BridgeService.log("本地 TTS 就绪，引擎=" + tts.getDefaultEngine());
                } catch (Throwable t) {
                    initFailed = true;
                    BridgeService.log("本地 TTS 配置异常：" + t);
                }
            });
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { BridgeService.log("播报结束"); }
                @Override public void onError(String id) { BridgeService.log("本地播报出错 " + id); }
            });
        } catch (Throwable t) {
            initFailed = true;
            BridgeService.log("本地 TTS 创建异常：" + t);
        }
    }

    public static boolean isReady() {
        return ready;
    }

    /** 立即播报（打断上一段），成功返回 true */
    public static boolean speak(String text) {
        if (!ready || tts == null) return false;
        try {
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dsh-" + System.currentTimeMillis());
            return r == TextToSpeech.SUCCESS;
        } catch (Throwable t) {
            BridgeService.log("本地播报异常：" + t);
            return false;
        }
    }

    public static void stop() {
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
    }
}
