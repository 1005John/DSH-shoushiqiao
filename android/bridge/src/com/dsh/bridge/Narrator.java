package com.dsh.bridge;

import android.media.AudioAttributes;
import android.media.MediaPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 回复播报：总结 → TTS → 播放。
 *
 * 不念全文，只念摘要，避免长时间播报。
 */
public class Narrator {

    private static final String BASE = "https://onerouter.cmaiot.cn/v1";
    private static final String KEY = "YOUR_LLM_ROUTER_TOKEN";
    private static final String SUMMARY_MODEL = "Qwen3-Next-80B-A3B-Instruct";
    private static final String TTS_MODEL = "TS/CosyVoice2-0.5B";
    private static final String TTS_VOICE = "中文女";
    private static final int SUMMARY_MAX_CHARS = 120;

    /**
     * 播报前清洗文本。
     * 界面统计文案（"本轮 N 秒…"）、emoji（✅）、markdown 符号
     * 都会被 TTS 念成奇怪的短音，必须去掉。
     */
    static String cleanForTts(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replaceAll("本轮\\s*\\d+\\s*秒[^\\n]{0,60}查看本轮轨迹", " ");
        t = t.replaceAll("查看本轮轨迹", " ");
        t = t.replaceAll("[\\u2705\\u274C\\u26A0\\uFE0F\\u2728\\u2B50\\u2764]", " ");
        t = t.replaceAll("[\\p{So}\\p{Sk}\\p{Cs}\\p{Co}]", " ");   // emoji / 符号
        t = t.replaceAll("[`*#_>\\[\\]{}|<>~^]", " ");
        t = t.replaceAll("[\\u00B7\\u2022\\u2026]", "，");          // · • …
        t = t.replaceAll("[「」『』【】〔〕《》〈〉\\u3000]", " ");     // CJK 引号括号
        t = t.replaceAll("[~～^&@#$%\\\\/|=+_]", " ");               // 杂符号
        t = t.replaceAll("[，,]{2,}", "，");
        t = t.replaceAll("[ \\t]+", " ");
        t = t.replaceAll("\\n{2,}", "\\n").trim();
        return t;
    }
    /** 主流程：总结并播报（阻塞，需在后台线程调用） */
    public static void summarizeAndSpeak(android.content.Context ctx, String fullText) {
        if (fullText == null || fullText.trim().isEmpty()) return;
        String text = cleanForTts(fullText);

        String say;
        if (text.length() <= 20) {
            // 只有极短的（如"好的"）才直接念
            say = text;
            BridgeService.log("极短回复，直接播报");
        } else {
            say = summarize(text);
            if (say == null || say.trim().isEmpty()) {
                BridgeService.log("总结失败，退回播报前 60 字");
                say = text.length() > SUMMARY_MAX_CHARS
                        ? text.substring(0, SUMMARY_MAX_CHARS) + "…"
                        : text;
            }
        }
        BridgeService.log("播报内容：[" + say + "]");
        playTts(ctx, say);
    }

    /** 直接播报（不总结），用于待办提醒这类已经很短的文本 */
    public static void speakDirect(android.content.Context ctx, String text) {
        String say = cleanForTts(text);
        if (say.isEmpty()) return;
        BridgeService.log("播报内容：[" + say + "]");
        playTts(ctx, say);
    }
    // ---------- 总结 ----------

    private static String summarize(String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", SUMMARY_MODEL);
            body.put("max_tokens", 300);
            body.put("temperature", 0.3);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content",
                    "请把下面的内容用不超过 " + SUMMARY_MAX_CHARS
                            + " 个字总结成一句口语化的话，直接给结论，不要开场白、不要分点、不要用 markdown：\n\n"
                            + text);
            msgs.put(m);
            body.put("messages", msgs);

            String resp = postJson(BASE + "/chat/completions", body.toString());
            if (resp == null) return null;
            JSONObject json = new JSONObject(resp);
            JSONObject msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String answer = msg.optString("content", "").trim();

            // 兜底：万一选到了推理模型，content 会是空的，用思考内容最后一行
            if (answer.isEmpty()) {
                String r = msg.optString("reasoning_content", "");
                String[] lines = r.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i].trim().length() >= 6) { answer = lines[i].trim(); break; }
                }
                BridgeService.log("content 为空，改用 reasoning 兜底");
            }
            return answer;
        } catch (Exception e) {
            BridgeService.log("总结异常：" + e);
            return null;
        }
    }

    // ---------- TTS + 播放 ----------

    private static void playTts(android.content.Context ctx, String text) {
        // ① 本地 TTS 优先：离线、音色固定、零延迟
        //    云端接口对同样输入会返回不同音色（实测 5 次 3 种结果，偶尔变男声）
        if (LocalTts.isReady() && LocalTts.speak(text)) {
            BridgeService.log("本地播报：" + text);
            return;
        }

        // ② 回退：云端 TTS
        try {
            JSONObject body = new JSONObject();
            body.put("model", TTS_MODEL);
            body.put("input", text);
            body.put("voice", TTS_VOICE);

            byte[] audio = postBinary(BASE + "/audio/speech", body.toString());
            if (audio == null || audio.length < 500) {
                BridgeService.log("TTS 失败或无音频");
                return;
            }
            boolean isWav = audio.length > 12 && audio[0] == 'R' && audio[1] == 'I'
                    && audio[2] == 'F' && audio[3] == 'F';
            int rate = isWav ? wavSampleRate(audio) : mp3SampleRate(audio);
            BridgeService.log("云端 TTS " + audio.length + " 字节，"
                    + (isWav ? "WAV" : "MP3") + " 采样率 " + rate + "Hz");

            // 自己解码 + 声道归并播放，绕开 MediaPlayer
            if (!isWav && TtsPlayer.play(ctx, audio)) return;
            BridgeService.log("TtsPlayer 失败，回退 MediaPlayer");

            File f = new File(ctx.getCacheDir(), isWav ? "say.wav" : "say.mp3");
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(audio); }

            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                try { m.release(); } catch (Exception ignored) { }
                BridgeService.log("播报结束");
            });
            mp.setOnErrorListener((m, what, extra) -> {
                BridgeService.log("播放出错 what=" + what + " extra=" + extra);
                try { m.release(); } catch (Exception ignored) { }
                return true;
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            BridgeService.log("TTS/播放异常：" + e);
        }
    }

    /** WAV 头里的采样率（偏移 24，小端 4 字节） */
    static int wavSampleRate(byte[] b) {
        try {
            if (b.length < 28) return -1;
            return (b[24] & 0xFF) | ((b[25] & 0xFF) << 8)
                    | ((b[26] & 0xFF) << 16) | ((b[27] & 0xFF) << 24);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- HTTP ----------

    /**
     * 解析 MP3 帧头拿采样率，用于诊断"声音变了"到底是音源问题还是播放链路问题。
     * MPEG1: [44100,48000,32000]  MPEG2/2.5: [22050,24000,16000] / [11025,12000,8000]
     */
    static int mp3SampleRate(byte[] b) {
        try {
            for (int i = 0; i + 3 < Math.min(b.length, 8192); i++) {
                if ((b[i] & 0xFF) != 0xFF || (b[i + 1] & 0xE0) != 0xE0) continue;
                int ver = (b[i + 1] >> 3) & 0x03;      // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
                int srIdx = (b[i + 2] >> 2) & 0x03;
                if (srIdx == 3) continue;
                if (ver == 3) {
                    int[] t = {44100, 48000, 32000};
                    return t[srIdx];
                }
                int[] t2 = {22050, 24000, 16000};
                int base = t2[srIdx];
                return ver == 0 ? base / 2 : base;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static String postJson(String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(Charset.forName("UTF-8")));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                BridgeService.log("总结 HTTP=" + code + " " + trim(resp));
                return null;
            }
            return resp;
        } catch (Exception e) {
            BridgeService.log("总结请求异常：" + e);
            return null;
        }
    }

    private static byte[] postBinary(String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(Charset.forName("UTF-8")));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                BridgeService.log("TTS HTTP=" + code + " " + trim(err));
                return null;
            }
            return readBytes(conn.getInputStream());
        } catch (Exception e) {
            BridgeService.log("TTS 请求异常：" + e);
            return null;
        }
    }

    private static String readAll(InputStream is) {
        byte[] b = readBytes(is);
        return b == null ? "" : new String(b, Charset.forName("UTF-8"));
    }

    private static byte[] readBytes(InputStream is) {
        if (is == null) return new byte[0];
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
