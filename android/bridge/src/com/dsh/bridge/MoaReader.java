package com.dsh.bridge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 从云服务器拉取 MOA 摘要，检测变化后触发眼镜播报。
 *
 * 服务器：https://39-106-60-159.sslip.io/moa/digest
 * 证书是 Caddy 的 tls internal（自签），所以这里放开校验。
 * 只访问这一个固定 IP，风险可控。
 */
public class MoaReader {

    private static final String BASE = "https://39-106-60-159.sslip.io/moa";
    private static final String TOKEN = "YOUR_MOA_READ_TOKEN";

    private static boolean trustAllInstalled = false;

    private static void installTrustAll() {
        if (trustAllInstalled) return;
        try {
            TrustManager[] tm = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override public boolean verify(String host, SSLSession session) { return true; }
            });
            trustAllInstalled = true;
        } catch (Exception e) {
            BridgeService.log("安装信任管理器失败：" + e);
        }
    }

    /** GET 请求，返回响应体；失败返回 null */
    public static String get(String path) {
        installTrustAll();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Moa-Token", TOKEN);
            int code = conn.getResponseCode();
            if (code != 200) {
                BridgeService.log("MOA 拉取失败 HTTP=" + code + " path=" + path);
                return null;
            }
            return readAll(conn.getInputStream());
        } catch (Exception e) {
            BridgeService.log("MOA 拉取异常：" + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    /** 从 {"path":..,"content":..} 里取出 content */
    public static String digestContent(String json) {
        if (json == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            return o.optString("content", "");
        } catch (Exception e) {
            return null;
        }
    }

    /** 读上次见过的摘要指纹 */
    public static String lastHash(Context ctx) {
        return ctx.getSharedPreferences("moa", Context.MODE_PRIVATE)
                .getString("digest_hash", "");
    }

    public static void saveHash(Context ctx, String h) {
        ctx.getSharedPreferences("moa", Context.MODE_PRIVATE)
                .edit().putString("digest_hash", h).apply();
    }

    public static String hash(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.length());
        }
    }
}
