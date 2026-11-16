package com.dsh.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 极简本地 HTTP 服务，只用来把待办页面给浏览器看。
 *
 * 为什么要它：Android 11+ 的分区存储禁止 Chrome 访问 file:///sdcard/…
 * （会报"访问文件遭拒"），只能走 http://。
 *
 * 访问：http://127.0.0.1:8099
 */
public class TodoHttp {

    private static final int PORT = 8099;
    private static final String ROOT = "/sdcard/Download/todo";

    public static void start() {
        new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                BridgeService.log("待办页面服务已启动 http://127.0.0.1:" + PORT);
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (Throwable t) {
                BridgeService.log("待办页面服务异常：" + t);
            } finally {
                try { if (ss != null) ss.close(); } catch (Exception ignored) { }
            }
        }).start();
    }

    private static void handle(Socket s) {
        try (Socket socket = s) {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), Charset.forName("UTF-8")));
            String line = r.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            if (path.contains("..")) path = "/index.html";

            File f = new File(ROOT + path);
            byte[] body;
            String status;
            if (f.exists() && f.isFile()) {
                body = readFile(f);
                status = "200 OK";
            } else {
                body = ("未找到 " + path).getBytes(Charset.forName("UTF-8"));
                status = "404 Not Found";
            }

            String ct = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".jsonl") ? "text/plain; charset=utf-8"
                    : "application/octet-stream";
            OutputStream os = socket.getOutputStream();
            String header = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + ct + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(header.getBytes(Charset.forName("UTF-8")));
            os.write(body);
            os.flush();
        } catch (Throwable ignored) { }
    }

    private static byte[] readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
