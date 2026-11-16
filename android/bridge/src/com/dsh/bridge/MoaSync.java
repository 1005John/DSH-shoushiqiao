package com.dsh.bridge;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * 把云端的 MOA 消息增量同步到手机本地文件。
 *
 * 落盘位置：/sdcard/Download/moa/messages.jsonl
 *   —— 选 Download 是因为 DSH Go 的 Linux 环境能读到它，
 *      Agent 被问到"谁给我发消息"时直接查这个文件即可。
 *
 * 增量依据：上次同步到的最后一条消息时间（time 字段，ISO 字符串可直接比较）。
 */
public class MoaSync {

    public static final String DIR = "/sdcard/Download/moa";
    public static final String FILE = DIR + "/messages.jsonl";
    private static final String PREF_LAST = "moa_last_time";

    /** 同步一轮。返回新增条数，失败返回 -1 */
    public static int sync(Context ctx) {
        try {
            String since = ctx.getSharedPreferences("moa", Context.MODE_PRIVATE)
                    .getString(PREF_LAST, "");
            String path = "/all" + (since.isEmpty() ? "" : "?since=" + urlEncode(since));
            String json = MoaReader.get(path);
            if (json == null) return -1;

            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) {
                BridgeService.log("MOA 同步：无新消息");
                return 0;
            }

            File dir = new File(DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                BridgeService.log("MOA 同步：无法创建目录 " + DIR);
                return -1;
            }
            File f = new File(FILE);
            boolean firstTime = !f.exists() || f.length() == 0;

            String maxTime = since;
            int written = 0;
            try (FileOutputStream fos = new FileOutputStream(f, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"))) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    String t = m.optString("time", "");
                    w.write(m.toString());
                    w.write("\n");
                    written++;
                    if (t.compareTo(maxTime) > 0) maxTime = t;
                }
            }

            ctx.getSharedPreferences("moa", Context.MODE_PRIVATE)
                    .edit().putString(PREF_LAST, maxTime).apply();

            BridgeService.log("MOA 同步：写入 " + written + " 条"
                    + (firstTime ? "（首次）" : "") + "，最新 " + maxTime);
            return written;
        } catch (Exception e) {
            BridgeService.log("MOA 同步异常：" + e);
            return -1;
        }
    }

    /** 本地现有条数（用于自检） */
    public static int localCount() {
        try {
            File f = new File(FILE);
            if (!f.exists()) return 0;
            int lines = 0;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            Charset.forName("UTF-8")))) {
                while (r.readLine() != null) lines++;
            }
            return lines;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s.replace(" ", "%20").replace(":", "%3A");
        }
    }
}
