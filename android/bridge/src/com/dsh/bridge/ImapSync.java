package com.dsh.bridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeUtility;

/**
 * 双账号 IMAP 邮件同步。
 *
 * 账号配置放在 /sdcard/Download/mail/accounts.json，**不写进代码**，
 * 避免授权码被打包进 APK。
 *
 * 格式：
 * [
 *   {"name":"QQ邮箱","host":"imap.qq.com","user":"xx@qq.com","pass":"授权码"},
 *   {"name":"企业邮箱","host":"imap.cmiot.chinamobile.com","user":"xx@cm.com","pass":"密码"}
 * ]
 *
 * 落盘：/sdcard/Download/mail/messages.jsonl
 */
public class ImapSync {

    public static final String DIR = "/sdcard/Download/mail";
    public static final String FILE = DIR + "/messages.jsonl";
    private static final String ACCOUNTS = DIR + "/accounts.json";

    private static final int MAX_PER_ACCOUNT = 30;
    private static final int BODY_CHARS = 400;

    private static final class Account {
        final String name, host, user, pass;
        Account(String n, String h, String u, String p) { name = n; host = h; user = u; pass = p; }
    }

    /**
     * 关键：jar 里的 META-INF/mailcap 资源不会被 d8 打进 APK，
     * 导致 JavaMail 找不到 multipart 处理器，getContent() 会退化成
     * 返回原始 MIME 文本（"This is a multi-part message..."）。
     * 所以在代码里手动注册一遍。
     */
    static {
        try {
            javax.mail.MailcapCommandMap mc = new javax.mail.MailcapCommandMap();
            mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
            mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
            mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml");
            mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed; x-java-fallback-entry=true");
            mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
            javax.mail.Session.setDefaultInstance(null);
            // 覆盖默认 map
            System.setProperty("mail.mime.multipart.ignoreexistingboundaryparameter", "true");
            javax.mail.CommandMap.setDefaultCommandMap(mc);
        } catch (Throwable t) {
            BridgeService.log("注册 mailcap 失败：" + t);
        }
    }

    private static List<Account> loadAccounts() {
        List<Account> list = new ArrayList<>();
        File f = new File(ACCOUNTS);
        if (!f.exists()) {
            BridgeService.log("邮件配置不存在：" + ACCOUNTS);
            return list;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Account(
                        o.optString("name", "邮箱"),
                        o.optString("host"),
                        o.optString("user"),
                        o.optString("pass")));
            }
        } catch (Exception e) {
            BridgeService.log("读取邮件配置失败：" + e);
        }
        return list;
    }

    /** 同步全部账号，返回新增条数 */
    public static int sync(Context ctx) {
        List<Account> accounts = loadAccounts();
        if (accounts.isEmpty()) return 0;
        int total = 0;
        for (Account a : accounts) {
            try {
                total += syncOne(ctx, a);
            } catch (Throwable t) {
                BridgeService.log("邮件同步失败[" + a.name + "]：" + t);
            }
        }
        if (total > 0) BridgeService.log("邮件同步完成，新增 " + total + " 封");
        return total;
    }

    private static int syncOne(Context ctx, Account a) throws Exception {
        Store store = null;
        Folder inbox = null;
        int written = 0;
        try {
            Properties p = new Properties();
            p.setProperty("mail.store.protocol", "imaps");
            p.setProperty("mail.imaps.host", a.host);
            p.setProperty("mail.imaps.port", "993");
            p.setProperty("mail.imaps.ssl.enable", "true");
            p.setProperty("mail.imaps.ssl.trust", "*");
            p.setProperty("mail.imaps.ssl.checkserveridentity", "false");
            p.setProperty("mail.imaps.connectiontimeout", "20000");
            p.setProperty("mail.imaps.timeout", "30000");

            Session s = Session.getInstance(p);
            store = s.getStore("imaps");
            store.connect(a.host, 993, a.user, a.pass);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int count = inbox.getMessageCount();
            if (count <= 0) return 0;
            int start = Math.max(1, count - MAX_PER_ACCOUNT + 1);

            long lastSync = ctx.getSharedPreferences("mail", Context.MODE_PRIVATE)
                    .getLong("last_" + a.name, 0L);

            Message[] msgs = inbox.getMessages(start, count);
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();

            long newest = lastSync;
            StringBuilder sb = new StringBuilder();
            for (int i = msgs.length - 1; i >= 0; i--) {          // 从旧到新
                Message m = msgs[i];
                Date d = m.getReceivedDate();
                if (d == null) d = m.getSentDate();
                if (d == null) continue;
                long t = d.getTime();
                if (lastSync > 0 && t <= lastSync) continue;
                if (t > newest) newest = t;
                JSONObject o = new JSONObject();
                o.put("account", a.name);
                o.put("from", fromOf(m));
                o.put("subject", decode(m.getSubject()));
                o.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(d));
                o.put("body", bodyOf(m));
                sb.append(o.toString()).append("\n");
                written++;
            }
            if (sb.length() > 0) {
                try (FileOutputStream fos = new FileOutputStream(FILE, true);
                     OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"))) {
                    w.write(sb.toString());
                }
            }
            if (newest > lastSync) {
                ctx.getSharedPreferences("mail", Context.MODE_PRIVATE)
                        .edit().putLong("last_" + a.name, newest).apply();
            }
            return written;
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) { }
            try { if (store != null) store.close(); } catch (Exception ignored) { }
        }
    }

    private static String fromOf(Message m) {
        try {
            Address[] a = m.getFrom();
            if (a == null || a.length == 0) return "";
            if (a[0] instanceof InternetAddress) {
                InternetAddress ia = (InternetAddress) a[0];
                String n = ia.getPersonal();
                return (n == null || n.isEmpty()) ? ia.getAddress() : decode(n);
            }
            return a[0].toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 递归提取正文：优先 text/plain，退而取 text/html（去标签） */
    private static String bodyOf(Message m) {
        try {
            return extractText(m.getContent(), 0);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extractText(Object c, int depth) {
        if (c == null || depth > 5) return "";
        try {
            if (c instanceof String) return snippet((String) c);
            if (c instanceof Multipart) {
                Multipart mp = (Multipart) c;
                String html = "";
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    String ct = String.valueOf(bp.getContentType()).toLowerCase(Locale.ROOT);
                    if (ct.contains("text/plain")) {
                        String t = extractText(bp.getContent(), depth + 1);
                        if (!t.isEmpty()) return t;
                    } else if (ct.contains("text/html")) {
                        if (html.isEmpty()) html = extractText(bp.getContent(), depth + 1);
                    } else if (ct.contains("multipart")) {
                        String t = extractText(bp.getContent(), depth + 1);
                        if (!t.isEmpty()) return t;
                    }
                }
                return html;
            }
            if (c instanceof InputStream) {
                try (InputStream in = (InputStream) c) {
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && sb.length() < 4000) {
                        sb.append(new String(buf, 0, n, Charset.forName("UTF-8")));
                    }
                    return snippet(sb.toString());
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
        return t.length() > BODY_CHARS ? t.substring(0, BODY_CHARS) : t;
    }

    private static String decode(String s) {
        if (s == null) return "";
        try { return MimeUtility.decodeText(s); } catch (Exception e) { return s; }
    }

    public static int localCount() {
        try {
            File f = new File(FILE);
            if (!f.exists()) return 0;
            int n = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")))) {
                while (r.readLine() != null) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
