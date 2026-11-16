package com.dsh.bridge;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 待办存储：/sdcard/Download/todo/todos.jsonl
 *
 * 一行一条：
 * {"id":"a1b2c3","title":"周五前反馈文档需求","due":"2026-11-13 10:00",
 *  "priority":"高","summary":"…","source":"MOA/周紫薇","created":"2026-11-15 21:40"}
 */
public class TodoStore {

    public static final String DIR = "/sdcard/Download/todo";
    public static final String FILE = DIR + "/todos.jsonl";

    /** 读取全部待办 */
    public static synchronized List<JSONObject> loadAll() {
        List<JSONObject> out = new ArrayList<>();
        File f = new File(FILE);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            BridgeService.log("读取待办失败：" + e);
        }
        return out;
    }

    /** 追加一条待办（按 id 去重） */
    public static synchronized boolean add(JSONObject todo) {
        String id = todo.optString("id", "");
        if (id.isEmpty()) return false;
        for (JSONObject o : loadAll()) {
            if (id.equals(o.optString("id"))) return false;
        }
        try {
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(FILE, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"))) {
                w.write(todo.toString());
                w.write("\n");
            }
            exportHtml();
            return true;
        } catch (Exception e) {
            BridgeService.log("写入待办失败：" + e);
            return false;
        }
    }

    /** 到点且未播报的待办（只保留 24 小时内到期的，避免历史积压一次性全播） */
    public static List<JSONObject> dueNow(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        String now = nowStr();
        String stale = shiftHours(now, -24);
        for (JSONObject o : loadAll()) {
            String due = o.optString("due", "");
            if (due.isEmpty() || due.length() < 16) continue;
            String id = o.optString("id", "");
            if (isAnnounced(ctx, id)) continue;
            if (due.compareTo(now) <= 0 && due.compareTo(stale) >= 0) out.add(o);
        }
        return out;
    }

    /** 未来 24 小时内到期的待办（用于早晨汇总） */
    public static List<JSONObject> dueWithin(Context ctx, int hours) {
        List<JSONObject> out = new ArrayList<>();
        String now = nowStr();
        String limit = shiftHours(now, hours);
        for (JSONObject o : loadAll()) {
            String due = o.optString("due", "");
            if (due.isEmpty() || due.length() < 16) continue;
            if (isAnnounced(ctx, o.optString("id", ""))) continue;
            if (due.compareTo(now) >= 0 && due.compareTo(limit) <= 0) out.add(o);
        }
        return out;
    }

    /** 生成 HTML 列表，方便在浏览器里查看 /sdcard/Download/todo/index.html */
    public static synchronized void exportHtml() {
        try {
            List<JSONObject> all = loadAll();
            List<JSONObject> sorted = new ArrayList<>(all);
            sorted.sort((a, b) -> {
                String da = a.optString("due", "");
                String db = b.optString("due", "");
                if (da.isEmpty() != db.isEmpty()) return da.isEmpty() ? 1 : -1;
                if (!da.isEmpty() && !da.equals(db)) return da.compareTo(db);
                return a.optString("priority", "中").compareTo(b.optString("priority", "中"));
            });

            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
              .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
              .append("<title>待办清单</title><style>")
              .append("body{font-family:-apple-system,\"PingFang SC\",sans-serif;margin:0;padding:16px;background:#f5f6f8;color:#222}")
              .append("h1{font-size:20px;margin:0 0 4px}.meta{color:#888;font-size:12px;margin-bottom:16px}")
              .append("table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.08)}")
              .append("td{padding:10px 8px;border-bottom:1px solid #f0f0f0;vertical-align:top;font-size:14px}")
              .append(".title{font-weight:600}.due{white-space:nowrap;color:#666;font-size:13px}")
              .append(".src{color:#999;font-size:12px}.sum{font-size:12px;color:#888}")
              .append(".p-high{color:#d93025;font-weight:700}.p-mid{color:#f29900}.p-low{color:#999}")
              .append(".overdue .due{color:#d93025;font-weight:700}")
              .append("</style></head><body><h1>待办清单</h1><div class=\"meta\">共 ")
              .append(sorted.size()).append(" 条 · 生成于 ").append(nowStr())
              .append(" · 红色=已过期</div><table>");

            String now = nowStr();
            for (JSONObject o : sorted) {
                String due = o.optString("due", "");
                String pr = o.optString("priority", "中");
                String cls = "p-mid";
                if (pr.equals("高")) cls = "p-high";
                else if (pr.equals("低")) cls = "p-low";
                boolean overdue = !due.isEmpty() && due.compareTo(now) < 0;
                sb.append("<tr").append(overdue ? " class=\"overdue\"" : "").append(">")
                  .append("<td class=\"").append(cls).append("\">").append(pr).append("</td>")
                  .append("<td class=\"title\">").append(esc(o.optString("title", ""))).append("</td>")
                  .append("<td class=\"due\">").append(due.isEmpty() ? "无期限" : esc(due)).append("</td>")
                  .append("<td class=\"src\">").append(esc(o.optString("source", ""))).append("</td></tr>")
                  .append("<tr class=\"sum\"><td></td><td colspan=\"3\">")
                  .append(esc(o.optString("summary", ""))).append("</td></tr>");
            }
            sb.append("</table></body></html>");

            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(DIR + "/index.html");
                 OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"))) {
                w.write(sb.toString());
            }
        } catch (Throwable t) {
            BridgeService.log("生成待办 HTML 失败：" + t);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    public static boolean isAnnounced(Context ctx, String id) {
        return ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .getBoolean("said_" + id, false);
    }

    public static void markAnnounced(Context ctx, String id) {
        ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .edit().putBoolean("said_" + id, true).apply();
    }

    public static boolean isCalendarAdded(Context ctx, String id) {
        return ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .getBoolean("cal_" + id, false);
    }

    public static void markCalendarAdded(Context ctx, String id) {
        ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .edit().putBoolean("cal_" + id, true).apply();
    }

    public static void saveEventId(Context ctx, String id, long evt) {
        ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .edit().putLong("evt_" + id, evt).apply();
    }

    public static long getEventId(Context ctx, String id) {
        return ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .getLong("evt_" + id, -1);
    }

    public static void clearEventId(Context ctx, String id) {
        ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                .edit().remove("evt_" + id).remove("cal_" + id).apply();
    }

    public static String nowStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }

    private static String shiftHours(String time, int hours) {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            java.util.Date d = f.parse(time);
            return f.format(new java.util.Date(d.getTime() + hours * 3600_000L));
        } catch (Exception e) {
            return time;
        }
    }

    /** 标题哈希作为 id，保证同一条待办不重复入库 */
    public static String idOf(String title, String due) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((title + "|" + due).getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf((title + due).hashCode());
        }
    }
}
