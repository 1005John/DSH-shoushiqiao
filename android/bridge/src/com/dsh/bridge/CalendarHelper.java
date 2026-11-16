package com.dsh.bridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 把待办静默写入 MIUI 日历，作为系统级提醒备份。
 * 即使我们的进程被 MIUI 杀掉，日历也会按时提醒。
 *
 * 需要 WRITE_CALENDAR 权限（可用 adb 授予）。
 */
public class CalendarHelper {

    /** 插入一条带提醒的日程；成功返回事件 id，失败返回 -1 */
    public static long insertTodo(Context ctx, JSONObject todo) {
        try {
            String title = todo.optString("title", "");
            String due = todo.optString("due", "");
            if (title.isEmpty() || due.length() < 16) return -1;

            long begin = parseTime(due);
            if (begin <= 0) return -1;

            ContentResolver cr = ctx.getContentResolver();
            long calId = pickCalendar(cr);
            if (calId < 0) {
                BridgeService.log("日历：找不到可写日历");
                return -1;
            }

            String desc = "来源：" + todo.optString("source", "")
                    + "\n优先级：" + todo.optString("priority", "")
                    + "\n" + todo.optString("summary", "");

            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, "【待办】" + title);
            v.put(CalendarContract.Events.DESCRIPTION, desc);
            v.put(CalendarContract.Events.DTSTART, begin);
            v.put(CalendarContract.Events.DTEND, begin + 15 * 60_000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.HAS_ALARM, 1);

            Uri uri = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (uri == null) {
                BridgeService.log("日历：插入失败");
                return -1;
            }
            long eventId = Long.parseLong(uri.getLastPathSegment());

            // 提前 10 分钟提醒
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, eventId);
            r.put(CalendarContract.Reminders.MINUTES, 10);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);

            BridgeService.log("日历：已写入「" + title + "」@ " + due + " (id=" + eventId + ")");
            return eventId;
        } catch (Exception e) {
            BridgeService.log("日历写入异常：" + e);
            return -1;
        }
    }

    /**
     * 把所有「【待办】xxx」形式的日历事件与本地待办建立关联。
     * 不用 SQL LIKE（某些 provider 上不生效），直接全量扫描内存匹配。
     */
    public static int backfillEventLinks(Context ctx) {
        int n = 0;
        try (android.database.Cursor c = ctx.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID, CalendarContract.Events.TITLE},
                null, null, null)) {
            if (c == null) {
                BridgeService.log("日历回查：查询返回 null");
                return 0;
            }
            java.util.List<org.json.JSONObject> todos = TodoStore.loadAll();
            int total = c.getCount();
            BridgeService.log("日历回查：共 " + total + " 个事件，本地待办 " + todos.size() + " 条");

            // 按下标遍历，避免 moveToFirst/moveToPosition 干扰迭代
            for (int i = 0; i < total; i++) {
                if (!c.moveToPosition(i)) break;
                long id = c.getLong(0);
                String title = c.getString(1);
                BridgeService.log("遍历 i=" + i + " id=" + id + " title=[" + title + "]");
                if (title == null) continue;
                if (!title.startsWith("【待办】")) {
                    BridgeService.log("  前缀不匹配，跳过");
                    continue;
                }
                String todoTitle = title.substring(4);
                if (i == 0) {
                    BridgeService.log("  待比对：[" + todoTitle + "] 长度" + todoTitle.length());
                    StringBuilder allt = new StringBuilder();
                    for (org.json.JSONObject o : todos) {
                        allt.append(o.optString("title", "")).append(" | ");
                    }
                    BridgeService.log("  全部待办标题：" + allt);
                }

                for (org.json.JSONObject o : todos) {
                    String ct = o.optString("title", "");
                    if (!todoTitle.equals(ct) && !ct.contains(todoTitle) && !todoTitle.contains(ct)) continue;
                    String tid = o.optString("id", "");
                    if (!tid.isEmpty() && TodoStore.getEventId(ctx, tid) <= 0) {
                        TodoStore.saveEventId(ctx, tid, id);
                        TodoStore.markCalendarAdded(ctx, tid);
                        n++;
                        BridgeService.log("日历关联：[" + todoTitle + "] <- id=" + id);
                    }
                    break;
                }
            }
            BridgeService.log("日历关联回填：" + n + " 条");
        } catch (Throwable t) {
            BridgeService.log("日历回查异常：" + t);
        }
        return n;
    }
    /** 按标题回查事件 id（保留，回填失败时兜底） */
    public static long findEventIdByTitle(Context ctx, String todoTitle) {
        try (android.database.Cursor c = ctx.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID, CalendarContract.Events.TITLE},
                null, null, null)) {
            if (c == null) return -1;
            String want = "【待办】" + todoTitle;
            while (c.moveToNext()) {
                if (want.equals(c.getString(1))) return c.getLong(0);
            }
        } catch (Throwable ignored) { }
        return -1;
    }
    /** 删除日历事件（用户已在 Tasks.org 里完成时调用） */
    public static boolean deleteEvent(Context ctx, long eventId) {
        try {
            int n = ctx.getContentResolver().delete(
                    android.content.ContentUris.withAppendedId(
                            CalendarContract.Events.CONTENT_URI, eventId),
                    null, null);
            if (n > 0) BridgeService.log("日历：已删除事件 id=" + eventId);
            return n > 0;
        } catch (Throwable t) {
            BridgeService.log("日历删除异常：" + t);
            return false;
        }
    }

    /** 找第一个可写的主日历 */
    private static long pickCalendar(ContentResolver cr) {
        try (android.database.Cursor c = cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                        CalendarContract.Calendars.IS_PRIMARY
                },
                null, null, null)) {
            if (c == null) return -1;
            long fallback = -1;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                int access = c.getInt(1);
                int primary = c.getInt(2);
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    if (primary == 1) return id;
                    if (fallback < 0) fallback = id;
                }
            }
            return fallback;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parseTime(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            java.util.Date d = f.parse(s);
            if (d == null) return -1;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }
}
