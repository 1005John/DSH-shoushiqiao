package com.dsh.bridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 用内网 LLM 从 MOA 消息和邮件里提炼待办。
 *
 * 为什么放手机上：
 *   云服务器（YOUR_VPS_IP）访问不到 onerouter.cmaiot.cn（内网域名解析到 198.18.0.2），
 *   所以提炼只能在处于公司网络的设备上做 —— 手机最合适，数据本来就在这里。
 *
 * 策略：增量。只处理"上次提炼时间"之后的新消息，避免重复烧 token。
 */
public class TodoExtractor {

    private static final String API = "https://onerouter.cmaiot.cn/v1/chat/completions";
    private static final String KEY = "YOUR_LLM_ROUTER_TOKEN";
    private static final String MODEL = "Qwen3-Next-80B-A3B-Instruct";   // 不带思考，快

    private static final String MOA_FILE = "/sdcard/Download/moa/messages.jsonl";
    private static final String MAIL_FILE = "/sdcard/Download/mail/messages.jsonl";

    private static final int MAX_ITEMS = 40;        // 单次最多喂多少条消息
    private static final String PREF_LAST = "last_extract";

    /** 执行一次提炼，返回新增待办数；-1 表示失败 */
    public static int extract(Context ctx) {
        try {
            String since = ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                    .getString(PREF_LAST, "");
            if (since.isEmpty()) {
                // 首次：从「本地最新一条消息」往前 2 小时起算。
                // 直接用当前时间会得到空窗口（周末/间隔久时没有任何消息）。
                String newest = newestMessageTime();
                if (newest.isEmpty()) {
                    BridgeService.log("待办提炼：本地没有消息，跳过");
                    return 0;
                }
                since = shiftMinutes(newest, -120);
                BridgeService.log("待办提炼：首次运行，起点 " + since);
            }

            StringBuilder feed = new StringBuilder();
            int n = collectMoa(since, feed) + collectMail(since, feed);
            if (n == 0) {
                BridgeService.log("待办提炼：无新消息");
                return 0;
            }
            BridgeService.log("待办提炼：本轮输入 " + n + " 条新消息");

            String json = callLlm(feed.toString());
            if (json == null) return -1;

            JSONArray arr = parseArray(json);
            if (arr == null) {
                BridgeService.log("待办提炼：返回内容无法解析为 JSON");
                return -1;
            }

            int added = 0;
            java.util.List<String> urgent = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("title", "").trim();
                if (title.isEmpty()) continue;
                String due = normalizeDue(o.optString("due", ""));

                JSONObject todo = new JSONObject();
                String id = TodoStore.idOf(title, due);
                todo.put("id", id);
                todo.put("title", title);
                todo.put("due", due);
                todo.put("priority", o.optString("priority", "中"));
                todo.put("summary", o.optString("summary", ""));
                todo.put("source", o.optString("source", ""));
                todo.put("created", TodoStore.nowStr());

                if (TodoStore.add(todo)) {
                    added++;
                    // 有明确时间的才写日历
                    if (!due.isEmpty() && !TodoStore.isCalendarAdded(ctx, id)) {
                        long evt = CalendarHelper.insertTodo(ctx, todo);
                        if (evt > 0) {
                            TodoStore.markCalendarAdded(ctx, id);
                            TodoStore.saveEventId(ctx, id, evt);
                        }
                    }
                    // 紧急的：立刻播报，并标记已播（避免到期再播一次）
                    if (o.optBoolean("urgent", false)) {
                        String why = o.optString("urgent_reason", "");
                        urgent.add(title + (why.isEmpty() ? "" : "（" + why + "）"));
                        TodoStore.markAnnounced(ctx, id);
                    }
                }
            }

            ctx.getSharedPreferences("todo", Context.MODE_PRIVATE)
                    .edit().putString(PREF_LAST, TodoStore.nowStr()).apply();
            BridgeService.log("待办提炼完成，新增 " + added + " 条，紧急 " + urgent.size() + " 条");

            // 有紧急项就立刻播报（合并成一条，避免连播）
            if (!urgent.isEmpty()) {
                StringBuilder say = new StringBuilder();
                say.append("有 ").append(urgent.size()).append(" 件急事：");
                for (int i = 0; i < urgent.size() && i < 3; i++) {
                    if (i > 0) say.append("；");
                    say.append(urgent.get(i));
                }
                BridgeService.log("紧急播报：" + say);
                Narrator.speakDirect(ctx, say.toString());
            }
            return added;
        } catch (Throwable t) {
            BridgeService.log("待办提炼异常：" + t);
            return -1;
        }
    }

    // ---------- 收集新消息 ----------

    private static int collectMoa(String since, StringBuilder sb) {
        int n = 0;
        for (JSONObject m : readLines(MOA_FILE)) {
            String t = m.optString("time", "");
            if (t.length() < 16 || t.compareTo(since) <= 0) continue;
            if (n >= MAX_ITEMS) break;
            sb.append("[MOA] ").append(m.optString("chat_name", "")).append(" | ")
                    .append(m.optString("sender", "")).append(" | ")
                    .append(t).append(" | ")
                    .append(cut(m.optString("content", ""), 160)).append("\n");
            n++;
        }
        return n;
    }

    private static int collectMail(String since, StringBuilder sb) {
        int n = 0;
        for (JSONObject m : readLines(MAIL_FILE)) {
            String t = m.optString("time", "");
            if (t.length() < 16 || t.compareTo(since) <= 0) continue;
            if (n >= MAX_ITEMS) break;
            sb.append("[邮件] ").append(m.optString("account", "")).append(" | ")
                    .append(m.optString("from", "")).append(" | ")
                    .append(t).append(" | ")
                    .append(m.optString("subject", "")).append(" | ")
                    .append(cut(m.optString("body", ""), 120)).append("\n");
            n++;
        }
        return n;
    }

    /** 本地最新一条消息的时间 */
    private static String newestMessageTime() {
        String max = "";
        for (JSONObject m : readLines(MOA_FILE)) {
            String t = m.optString("time", "");
            if (t.length() >= 16 && t.compareTo(max) > 0) max = t;
        }
        for (JSONObject m : readLines(MAIL_FILE)) {
            String t = m.optString("time", "");
            if (t.length() >= 16 && t.compareTo(max) > 0) max = t;
        }
        return max.length() >= 16 ? max.substring(0, 16) : "";
    }

    private static List<JSONObject> readLines(String path) {
        List<JSONObject> out = new ArrayList<>();
        File f = new File(path);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------- LLM ----------

    private static String callLlm(String feed) {
        try {
            String prompt =
                    "你是待办提取助手。下面是某人最近的即时消息和邮件，每行一条。\n"
                            + "请提取出「需要他本人采取行动」的事项，只输出 JSON 数组，不要任何解释文字。\n"
                            + "每条格式：{\"title\":\"20字以内标题\",\"due\":\"YYYY-MM-DD HH:MM 或 空字符串\","
                            + "\"priority\":\"高/中/低\",\"summary\":\"一句话说明要做什么\",\"source\":\"来源会话或发件人\","
                            + "\"urgent\":true或false,\"urgent_reason\":\"为什么紧急，10字内\"}\n"
                            + "urgent 判定标准（满足任一即为 true）：\n"
                            + "  1. 明确要求「马上/立即/现在就/尽快」处理\n"
                            + "  2. 线上故障、生产问题、系统告警\n"
                            + "  3. 两小时内就要截止或开会\n"
                            + "  4. 领导直接点名要求立刻答复\n"
                            + "其余一律 urgent=false。\n"
                            + "规则：闲聊、通知、广告、已完成的事都不算；没有待办就输出 []。\n\n"
                            + "消息：\n" + feed;

            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("max_tokens", 1200);
            body.put("temperature", 0.1);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", prompt);
            msgs.put(m);
            body.put("messages", msgs);

            String resp = post(body.toString());
            if (resp == null) return null;
            JSONObject j = new JSONObject(resp);
            return j.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
        } catch (Exception e) {
            BridgeService.log("待办提炼调模型失败：" + e);
            return null;
        }
    }

    private static String post(String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API).openConnection();
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
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String resp = new String(bos.toByteArray(), Charset.forName("UTF-8"));
            if (code < 200 || code >= 300) {
                BridgeService.log("待办提炼 HTTP=" + code + " "
                        + (resp.length() > 120 ? resp.substring(0, 120) : resp));
                return null;
            }
            return resp;
        } catch (Exception e) {
            BridgeService.log("待办提炼请求异常：" + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从模型输出里抠出 JSON 数组（可能被 ```json 包裹） */
    private static JSONArray parseArray(String s) {
        if (s == null) return null;
        int a = s.indexOf('[');
        int b = s.lastIndexOf(']');
        if (a < 0 || b <= a) return null;
        try { return new JSONArray(s.substring(a, b + 1)); } catch (Exception e) { return null; }
    }

    // ---------- 工具 ----------

    private static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ").trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String normalizeDue(String due) {
        if (due == null) return "";
        String d = due.trim();
        if (d.isEmpty() || d.equals("无") || d.equals("null")) return "";
        // 只接受 yyyy-MM-dd HH:mm
        if (d.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) return d;
        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) return d + " 09:00";
        return "";
    }

    private static String shiftMinutes(String time, int minutes) {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            java.util.Date d = f.parse(time);
            return f.format(new java.util.Date(d.getTime() + minutes * 60_000L));
        } catch (Exception e) {
            return time;
        }
    }
}
