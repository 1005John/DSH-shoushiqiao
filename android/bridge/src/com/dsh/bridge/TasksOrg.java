package com.dsh.bridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 把待办写进 Tasks.org（开源待办 App）。
 *
 * Tasks.org 对第三方开放两个 ContentProvider：
 *   content://org.tasks.api/tasks   （新 API）
 *   content://org.tasks/tasks       （旧 Astrid API）
 * 都需要 org.tasks.permission.READ_TASKS / WRITE_TASKS，
 * 必须在本 App 的 Manifest 里声明后再被授予。
 *
 * 列名不确定，所以先探测实际列，再按存在的列做映射 —— 避免版本差异导致写入失败。
 */
public class TasksOrg {

    private static final String API_URI = "content://org.tasks.api/tasks";
    private static final String LEGACY_URI = "content://org.tasks/tasks";

    private static String workingUri = null;

    /** 探测可用 URI 和列名，返回可读 URI */
    public static String probe(Context ctx) {
        if (workingUri != null) return workingUri;
        String[] candidates = new String[]{
                "content://org.tasks.api/v0/tasks",   // 新版 API（写入用这个）
                "content://org.tasks/tasks",          // 旧版（只读）
        };
        for (String u : candidates) {
            // 带显式投影 + 不限排序，尽量贴近 provider 期望
            String[] proj = u.contains("/v0/") ? null : new String[]{"_id", "title"};
            try (Cursor c = ctx.getContentResolver().query(
                    Uri.parse(u), proj, null, null, null)) {
                if (c == null) {
                    BridgeService.log("Tasks.org " + u + " -> 返回 null");
                    continue;
                }
                String[] cols = c.getColumnNames();
                int count = c.getCount();
                BridgeService.log("Tasks.org 可读：" + u + " 条数=" + count
                        + " 列=" + Arrays.toString(cols));
                things.put(u, cols);
                if (workingUri == null) workingUri = u;
            } catch (Throwable t) {
                BridgeService.log("Tasks.org 探测失败 " + u + " -> "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return workingUri;
    }

    private static final java.util.Map<String, String[]> things = new java.util.HashMap<>();

    /** 写入一条待办；成功返回 true */
    public static boolean insert(Context ctx, JSONObject todo) {
        try {
            String uriStr = probe(ctx);
            if (uriStr == null) return false;

            Set<String> cols = columns(ctx, uriStr);
            ContentValues v = new ContentValues();

            // 标题
            if (cols.contains("title")) v.put("title", todo.optString("title"));
            else return false;

            // 备注：把摘要和来源放进去
            String notes = todo.optString("summary", "")
                    + "\n来源：" + todo.optString("source", "");
            if (cols.contains("notes")) v.put("notes", notes);

            // 到期时间
            String due = todo.optString("due", "");
            if (!due.isEmpty() && due.length() >= 16) {
                long ms = parseTime(due);
                if (ms > 0) {
                    if (cols.contains("due_date")) {
                        v.put("due_date", ms);
                        if (cols.contains("due_all_day")) v.put("due_all_day", 0);
                    } else if (cols.contains("dueDate")) {
                        v.put("dueDate", ms);
                    }
                }
            }

            // 优先级
            String pr = todo.optString("priority", "中");
            String prStr = pr.equals("高") ? "high" : pr.equals("低") ? "low" : "medium";
            if (cols.contains("priority")) v.put("priority", prStr);
            else if (cols.contains("importance")) {
                v.put("importance", pr.equals("高") ? 3 : pr.equals("低") ? 1 : 2);
            }

            long now = System.currentTimeMillis();
            if (cols.contains("created")) v.put("created", now);
            if (cols.contains("modified")) v.put("modified", now);
            if (cols.contains("created_at")) v.put("created_at", now);
            if (cols.contains("modified_at")) v.put("modified_at", now);
            if (cols.contains("completed")) v.put("completed", 0);
            if (cols.contains("deleted")) v.put("deleted", 0);

            Uri r = ctx.getContentResolver().insert(Uri.parse(uriStr), v);
            BridgeService.log("Tasks.org 写入「" + todo.optString("title") + "」-> " + r);
            return r != null;
        } catch (Throwable t) {
            BridgeService.log("Tasks.org 写入异常：" + t);
            return false;
        }
    }

    /** 批量导入本地待办到 Tasks.org（按 id 去重，不重复导入） */
    /** 批量导入/更新本地待办到 Tasks.org（按标题做 upsert，幂等） */
    public static int importAll(Context ctx) {
        int n = 0;
        try {
            String uri = probe(ctx);
            if (uri == null || !uri.contains("/v0/")) {
                BridgeService.log("Tasks.org API 不可用，跳过导入");
                return 0;
            }
            // 先把已有的任务按标题建索引
            java.util.Map<String, String> existing = new java.util.HashMap<>();
            try (Cursor c = ctx.getContentResolver().query(
                    Uri.parse(uri), new String[]{"_id", "title"}, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        existing.put(c.getString(1), c.getString(0));
                    }
                }
            }
            BridgeService.log("Tasks.org 已有 " + existing.size() + " 条，开始同步");

            for (JSONObject o : TodoStore.loadAll()) {
                String title = o.optString("title", "");
                if (title.isEmpty()) continue;
                ContentValues v = buildValues(ctx, uri, o);

                String rowId = existing.get(title);
                if (rowId != null) {
                    int u = ctx.getContentResolver().update(
                            Uri.parse(uri + "/" + rowId), v, null, null);
                    if (u > 0) n++;
                } else {
                    Uri r = ctx.getContentResolver().insert(Uri.parse(uri), v);
                    if (r != null) n++;
                }
            }
            if (n > 0) BridgeService.log("Tasks.org 同步完成，" + n + " 条");
        } catch (Throwable t) {
            BridgeService.log("Tasks.org 导入异常：" + t);
        }
        return n;
    }

    /**
     * 从 Tasks.org 读取「实时」待办，镜像成文件给 Agent 查。
     *
     * 这样用户手动在 App 里勾选完成/修改的，Agent 也能看到，
     * 而不是只看到桥接 App 维护的旧 jsonl。
     */
    public static int syncFromTasks(Context ctx) {
        int n = 0;
        try {
            // v0 API：优先级是字符串（high/medium/low），但 completed_at 不可靠
            java.util.Map<String, String> prio = new java.util.HashMap<>();
            try (Cursor c = ctx.getContentResolver().query(
                    Uri.parse("content://org.tasks.api/v0/tasks"),
                    new String[]{"_id", "priority"}, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        prio.put(c.getString(0), c.isNull(1) ? "medium" : c.getString(1));
                    }
                }
            }

            // 旧版 provider：completed(0/1) 和 dueDate(毫秒) 语义明确
            String[] proj = {"_id", "title", "notes", "dueDate", "completed"};
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            StringBuilder sb = new StringBuilder();

            try (Cursor c = ctx.getContentResolver().query(
                    Uri.parse("content://org.tasks/tasks"), proj, null, null, null)) {
                if (c == null) {
                    BridgeService.log("Tasks.org 读取失败（返回 null）");
                    return 0;
                }
                int iId = c.getColumnIndex("_id");
                int iTitle = c.getColumnIndex("title");
                int iNotes = c.getColumnIndex("notes");
                int iDue = c.getColumnIndex("dueDate");
                int iDone = c.getColumnIndex("completed");

                while (c.moveToNext()) {
                    String id = iId >= 0 ? c.getString(iId) : "";
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id", id);
                    o.put("title", iTitle >= 0 ? c.getString(iTitle) : "");
                    int done = (iDone >= 0 && !c.isNull(iDone)) ? c.getInt(iDone) : 0;
                    o.put("done", done != 0);
                    long dueVal = (iDue >= 0 && !c.isNull(iDue)) ? c.getLong(iDue) : 0L;
                    o.put("due", dueVal > 0 ? fmt.format(new java.util.Date(dueVal)) : "");
                    o.put("priority", prio.containsKey(id) ? prio.get(id) : "medium");
                    if (iNotes >= 0 && !c.isNull(iNotes)) o.put("notes", c.getString(iNotes));
                    sb.append(o.toString()).append("\n");
                    n++;
                }
            }
            File dir = new File(TodoStore.DIR);
            if (!dir.exists()) dir.mkdirs();
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream("/sdcard/Download/todo/tasks_live.jsonl"),
                    java.nio.charset.Charset.forName("UTF-8"))) {
                w.write(sb.toString());
            }
            int undone = 0;
            for (String line : sb.toString().split("\n")) {
                if (line.contains("\"done\":false")) undone++;
            }
            BridgeService.log("Tasks.org 实时镜像已更新：" + n + " 条（未完成 " + undone + "）");
        } catch (Throwable t) {
            BridgeService.log("Tasks.org 实时镜像失败：" + t);
        }
        return n;
    }
    /** 实时数据里仍存在的标题集合（用于识别"已在 App 里删除"） */
    public static java.util.Set<String> existingTitles() {
        java.util.Set<String> all = new java.util.HashSet<>();
        try {
            java.io.File f = new java.io.File("/sdcard/Download/todo/tasks_live.jsonl");
            if (!f.exists()) return all;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.Charset.forName("UTF-8")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(line);
                        all.add(o.optString("title", ""));
                    } catch (Exception ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return all;
    }

    /** 从实时数据里取「已在 App 里完成」的标题集合 */
    public static java.util.Set<String> completedTitles(Context ctx) {
        java.util.Set<String> done = new java.util.HashSet<>();
        try {
            java.io.File f = new java.io.File("/sdcard/Download/todo/tasks_live.jsonl");
            if (!f.exists()) return done;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.Charset.forName("UTF-8")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(line);
                        if (o.optBoolean("done", false)) done.add(o.optString("title", ""));
                    } catch (Exception ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return done;
    }
    /** 按 API 实际列名构造 ContentValues */
    private static ContentValues buildValues(Context ctx, String uri, JSONObject todo) {
        Set<String> cols = columns(ctx, uri);
        ContentValues v = new ContentValues();
        v.put("title", todo.optString("title"));
        if (cols.contains("notes")) {
            v.put("notes", todo.optString("summary", "")
                    + "\n来源：" + todo.optString("source", "")
                    + "\n优先级：" + todo.optString("priority", ""));
        }
        if (cols.contains("priority")) {
            String pr = todo.optString("priority", "中");
            v.put("priority", pr.equals("高") ? "high" : pr.equals("低") ? "low" : "medium");
        }
        String due = todo.optString("due", "");
        if (!due.isEmpty() && due.length() >= 16 && cols.contains("due_date")) {
            long ms = parseTime(due);
            if (ms > 0) {
                v.put("due_date", ms);
                if (cols.contains("due_all_day")) v.put("due_all_day", 0);
            }
        }
        // 注意：API 只接受这些可写列
        // completed_at, due_all_day, due_date, list_id, notes, parent_id,
        // place_id, priority, recurrence, repeat_from, start_all_day, start_date, title
        return v;
    }
    private static Set<String> columns(Context ctx, String uri) {
        Set<String> s = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(uri), null, null, null, null)) {
            if (c != null) s.addAll(Arrays.asList(c.getColumnNames()));
        } catch (Throwable ignored) { }
        return s;
    }

    private static long parseTime(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(s).getTime();
        } catch (Exception e) {
            return -1;
        }
    }
}
