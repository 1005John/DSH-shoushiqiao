package com.dsh.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 双击眼镜镜腿 → 连续录音 → 整段上传 ASR 转写 → 写入 DSH Go 并发送。
 *
 * 关键设计（按用户要求）：
 *   第 1 次双击 → 开始录音（AudioRecord 连续录，不分段、不判断静音）
 *   中间可以随便停顿
 *   第 2 次双击 → 停止 → 整段 WAV 上传 /v1/audio/transcriptions →
 *                 拿回完整文字 → 写入 DSH Go 输入框 → 点发送
 *
 * 为什么不用系统 SpeechRecognizer：
 *   它只认麦克风、内部强制 VAD，会在停顿处切断并丢弃音频，导致丢字。
 *   连续录音 + 一次性转写才能保证完整。
 */
public class BridgeService extends Service {

    private static final String TAG = "DSHBridge";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1001;

    private static final String DSH_PKG = "io.github.ccch1mneyyy.dsh_app";
    private static final String DSH_ACT = "io.github.ccch1mneyyy.dsh_app.MainActivity";

    /** 是不是我们主动点亮并划开了锁屏（只有这种情况才在结束时自动息屏） */
    private volatile boolean weUnlocked = false;
    /** 这一轮的回复是否已经抓到（抓到之后才允许息屏，否则会打断抓取） */
    private volatile boolean replyCaptured = false;

    // ASR 配置
    private static final String ASR_URL = "https://onerouter.cmaiot.cn/v1/audio/transcriptions";
    private static final String ASR_KEY = "YOUR_LLM_ROUTER_TOKEN";
    private static final String ASR_MODEL = "TS/SenseVoiceSmall";
    private static final int SAMPLE_RATE = 16000;

    private MediaSession session;
    private AudioTrack silent;
    private AudioRecord recorder;

    private volatile boolean recording = false;
    private ByteArrayOutputStream pcmBuffer;
    private Thread recordThread;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void log(String msg) {
        Log.i(TAG, msg);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        session = new MediaSession(this, "DSHBridge");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ke == null) return false;
                log("收到媒体键 keyCode=" + ke.getKeyCode() + " action=" + ke.getAction());
                if (ke.getAction() != KeyEvent.ACTION_DOWN) return true;
                int code = ke.getKeyCode();
                if (code == KeyEvent.KEYCODE_MEDIA_PLAY
                        || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                        || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || code == KeyEvent.KEYCODE_MEDIA_STOP
                        || code == KeyEvent.KEYCODE_HEADSETHOOK) {
                    onTrigger();
                }
                return true;
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build());
        session.setActive(true);

        LocalTts.init(this);      // 本地语音合成
        WatchdogReceiver.schedule(this);   // 每 10 分钟自检，被杀后自动复活
        startSilentPlayback();
        startMoaWatch();
        startTodoWatch();
        TodoHttp.start();

        startForeground(NOTIF_ID, buildNotification("就绪 · 双击开始说话"));
        log("服务已启动");
    }

    // ---------- MOA 摘要轮询（主动通知） ----------

    private void startMoaWatch() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                new Thread(() -> checkMoaDigest()).start();
                handler.postDelayed(this, 5 * 60 * 1000);   // 5 分钟一次
            }
        }, 60 * 1000);   // 启动 1 分钟后首查
    }

    private int moaCycle = 0;
    private int todoCycle = 0;

    // ---------- 待办：提炼 + 到期提醒 ----------

    private void startTodoWatch() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                new Thread(() -> checkDueTodos()).start();
                handler.postDelayed(this, 60 * 1000);   // 每分钟检查一次到期
            }
        }, 30 * 1000);
        // 启动 20 秒后补写日历
        handler.postDelayed(() -> new Thread(this::backfillCalendar).start(), 20 * 1000);
    }

    /**
     * 让日历跟随 Tasks.org。
     * 遍历日历事件（而不是待办列表），这样即使待办已从 jsonl 删除，
     * 残留的孤儿日历事件也能被清理。
     */
    private void syncCalendarWithTasks() {
        try {
            java.util.Set<String> done = TasksOrg.completedTitles(this);
            java.util.Set<String> alive = TasksOrg.existingTitles();
            if (alive.isEmpty()) {
                log("Tasks.org 实时数据为空，跳过日历清理");
                return;
            }
            int removed = 0;
            try (android.database.Cursor c = getContentResolver().query(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    new String[]{android.provider.CalendarContract.Events._ID,
                            android.provider.CalendarContract.Events.TITLE},
                    null, null, null)) {
                if (c == null) return;
                int total = c.getCount();
                for (int i = 0; i < total; i++) {
                    if (!c.moveToPosition(i)) break;
                    long evtId = c.getLong(0);
                    String t = c.getString(1);
                    if (t == null || !t.startsWith("【待办】")) continue;
                    String todoTitle = t.substring(4);
                    boolean finished = done.contains(todoTitle);
                    boolean gone = !alive.contains(todoTitle);
                    if (finished || gone) {
                        CalendarHelper.deleteEvent(this, evtId);
                        removed++;
                        log("日历清理：" + todoTitle + "（" + (finished ? "已完成" : "已从待办删除") + "）");
                    }
                }
            }
            if (removed == 0) log("日历与待办一致，无需清理");
        } catch (Throwable t) {
            log("日历同步异常：" + t);
        }
    }
    /** 启动时把「未来到期、还没写进日历」的待办补进日历 */
    private void backfillCalendar() {
        try {
            String now = TodoStore.nowStr();
            int n = 0;
            for (org.json.JSONObject o : TodoStore.loadAll()) {
                String due = o.optString("due", "");
                String id = o.optString("id", "");
                if (due.isEmpty() || id.isEmpty()) continue;
                if (due.compareTo(now) < 0) continue;          // 过期的就不补了
                if (TodoStore.isCalendarAdded(this, id)) continue;
                long evt = CalendarHelper.insertTodo(this, o);
                if (evt > 0) {
                    TodoStore.markCalendarAdded(this, id);
                    TodoStore.saveEventId(this, id, evt);
                    n++;
                }
            }
            CalendarHelper.backfillEventLinks(this);   // 先把老日历事件和待办关联起来
            TodoStore.exportHtml();   // 顺便刷新 HTML 列表
            TasksOrg.importAll(this); // 导入到 Tasks.org 待办 App
            if (n > 0) log("日历回填完成，新增 " + n + " 条");
        } catch (Throwable t) {
            log("日历回填异常：" + t);
        }
    }

    private void checkDueTodos() {
        try {
            java.util.List<org.json.JSONObject> due = TodoStore.dueNow(this);
            if (due.isEmpty()) return;
            // 你在 Tasks.org 里勾选完成的，就不再提醒
            java.util.Set<String> done = TasksOrg.completedTitles(this);
            for (org.json.JSONObject o : due) {
                String id = o.optString("id");
                if (id.isEmpty() || TodoStore.isAnnounced(this, id)) continue;
                String title = o.optString("title");
                if (done.contains(title)) {
                    TodoStore.markAnnounced(this, id);
                    log("已在你手机 App 里完成，跳过：" + title);
                    continue;
                }
                String dueStr = o.optString("due");
                String pr = o.optString("priority", "中");
                String say = (pr.equals("高") ? "重要待办：" : "待办提醒：")
                        + title + "，截止 " + dueStr;
                BridgeService.log("到期待办：" + title + " @ " + dueStr);
                Narrator.speakDirect(this, say);
                TodoStore.markAnnounced(this, id);
                return;   // 一次只播一条，避免连播
            }
        } catch (Throwable t) {
            log("待办检查异常：" + t);
        }
    }

    /** 每 5 分钟一轮：MOA 同步 → 邮件同步 → Tasks.org 镜像 → 日历跟随 → 待办提炼 */
    private void checkMoaDigest() {
        try {
            // ① MOA 消息增量同步
            int n = MoaSync.sync(this);
            if (n > 0) log("MOA 本地已有 " + MoaSync.localCount() + " 条");

            // ② 每 3 轮（约 15 分钟）同步一次邮件
            if (moaCycle++ % 3 == 0) {
                try {
                    int mail = ImapSync.sync(this);
                    if (mail > 0) log("邮件本地已有 " + ImapSync.localCount() + " 封");
                } catch (Throwable t) {
                    log("邮件同步异常：" + t);
                }
            }

            // ③ Tasks.org 实时镜像 + 日历跟随清理
            try {
                TasksOrg.syncFromTasks(this);
                syncCalendarWithTasks();
            } catch (Throwable t) {
                log("实时镜像异常：" + t);
            }

            // ④ 待办提炼（有新消息才真正调模型）
            try {
                TodoExtractor.extract(this);
            } catch (Throwable t) {
                log("待办提炼异常：" + t);
            }
        } catch (Throwable t) {
            log("同步周期异常：" + t);
        }
    }
    /** 取摘要里最新的一节（第一个 "## " 到下一个 "## "） */
    private String firstDigestSection(String content) {
        int start = content.indexOf("\n## ");
        if (start < 0) start = 0;
        int next = content.indexOf("\n## ", start + 4);
        String sec = next > 0 ? content.substring(start, next) : content.substring(start);
        if (sec.length() > 1500) sec = sec.substring(0, 1500);
        return sec.trim();
    }

    // ---------- 录音 ----------

    private void startRecording() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 4096) * 2;
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord 初始化失败");
                update("录音初始化失败");
                return;
            }
            pcmBuffer = new ByteArrayOutputStream();
            recording = true;
            recorder.startRecording();
            log("开始连续录音");

            recordThread = new Thread(() -> {
                byte[] buf = new byte[3200];
                while (recording) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n > 0) {
                        synchronized (pcmBuffer) { pcmBuffer.write(buf, 0, n); }
                    }
                }
            });
            recordThread.start();
            update("录音中… 再双击结束并转写");

            // 边说边把屏幕点亮并划开锁屏：等用户说完就绪了，
            // 省掉发送时那 1 秒多的解锁等待
            new Thread(() -> {
                try { ensureUnlocked(); } catch (Throwable ignored) { }
            }).start();
        } catch (Exception e) {
            log("录音启动异常：" + e);
            update("录音启动失败");
            recording = false;
        }
    }

    private void stopRecordingAndTranscribe() {
        recording = false;
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
        } catch (Exception ignored) { }
        byte[] pcm;
        synchronized (pcmBuffer) { pcm = pcmBuffer.toByteArray(); }
        int seconds = pcm.length / (SAMPLE_RATE * 2);
        log("录音结束，长度 " + seconds + " 秒（" + pcm.length + " 字节）");
        if (pcm.length < SAMPLE_RATE) {
            update("录音太短");
            scheduleAutoLock();
            return;
        }
        update("正在转写…");
        new Thread(() -> {
            String text = transcribe(pcm);
            if (text == null || text.trim().isEmpty()) {
                update("转写结果为空");
                scheduleAutoLock();     // 屏幕已被点亮，别一直亮着
                return;
            }
            log("转写结果：[" + text + "]");
            sendToDsh(text.trim());
        }).start();
    }

    // ---------- 上传转写 ----------

    private String transcribe(byte[] pcm) {
        try {
            byte[] wav = buildWav(pcm);
            String boundary = "----dsh" + System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) new URL(ASR_URL).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + ASR_KEY);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream();
                 DataOutputStream out = new DataOutputStream(os)) {
                Charset utf8 = Charset.forName("UTF-8");

                out.write(("--" + boundary + "\r\n").getBytes(utf8));
                out.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes(utf8));
                out.write((ASR_MODEL + "\r\n").getBytes(utf8));

                out.write(("--" + boundary + "\r\n").getBytes(utf8));
                out.write("Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
                        .getBytes(utf8));
                out.write("Content-Type: audio/wav\r\n\r\n".getBytes(utf8));
                out.write(wav);
                out.write("\r\n".getBytes(utf8));

                out.write(("--" + boundary + "--\r\n").getBytes(utf8));
                out.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            log("ASR HTTP=" + code + " 响应=" + body);
            if (code < 200 || code >= 300) return null;
            return extractText(body);
        } catch (Exception e) {
            log("转写异常：" + e);
            return null;
        }
    }

    private byte[] buildWav(byte[] pcm) throws Exception {
        File f = new File(getCacheDir(), "rec.wav");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            int dataLen = pcm.length;
            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            writeIntLE(header, 4, 36 + dataLen);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            writeIntLE(header, 16, 16);
            header[20] = 1; header[21] = 0; // PCM
            header[22] = 1; header[23] = 0; // mono
            writeIntLE(header, 24, SAMPLE_RATE);
            writeIntLE(header, 28, SAMPLE_RATE * 2);
            header[32] = 2; header[33] = 0; // block align
            header[34] = 16; header[35] = 0; // bits
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            writeIntLE(header, 40, dataLen);
            fos.write(header);
            fos.write(pcm);
        }
        byte[] out = new byte[(int) f.length()];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return out;
    }

    private void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), Charset.forName("UTF-8"));
    }

    /** 从 {"text":"..."} 里取文本，处理 \\uXXXX 转义 */
    private String extractText(String json) {
        int i = json.indexOf("\"text\"");
        if (i < 0) return null;
        int q1 = json.indexOf('"', i + 6);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int p = q1 + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\') {
                char nx = json.charAt(p + 1);
                if (nx == 'u') {
                    sb.append((char) Integer.parseInt(json.substring(p + 2, p + 6), 16));
                    p += 5;
                } else {
                    if (nx == 'n') sb.append('\n');
                    else if (nx == 't') sb.append('\t');
                    else sb.append(nx);
                    p += 1;
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- 写入并发送 ----------

    private void sendToDsh(String text) {
        update("转写：" + text);
        new Thread(() -> {
            try {
                // 锁屏状态下无障碍拿不到目标窗口，必须先点亮并划开锁屏
                ensureUnlocked();
                ClickA11yService svc = ClickA11yService.get();
                if (svc == null) {
                    update("无障碍服务未开启");
                    return;
                }
                if (!svc.isForeground(DSH_PKG)) {
                    // 关键：整个后台窗口期挂住悬浮窗，防止 MIUI 冻结进程
                    showKeepAlive();
                    bringDshForward();
                    sleepMs(700);
                    long t0 = System.currentTimeMillis();
                    boolean okWait = svc.waitFor(DSH_PKG, 6000);
                    log("前台等待耗时 " + (System.currentTimeMillis() - t0) + "ms -> " + okWait);
                }
                log("步骤：点击输入框");
                svc.tapEditable(DSH_PKG);
                sleepMs(700);
                log("步骤：拍快照");
                // 发送前给界面做快照：之后只有"新增的文本块"才算这次的回复
                java.util.List<String> before = svc.textBlocks(DSH_PKG);
                log("发送前快照 " + (before == null ? "null" : before.size()) + " 块");
                log("步骤：写文字");
                boolean typed = svc.setText(DSH_PKG, text);
                log("写入输入框=" + typed);
                if (!typed) {
                    update("写入输入框失败");
                    return;
                }
                sleepMs(350);
                // 键盘挡住发送键时先收起来
                svc.closeKeyboardIfIme(DSH_PKG);
                boolean sent = svc.clickInPackage(DSH_PKG, "发送");
                update(sent ? "已发送：" + text : "已写入但没找到发送键");
                if (sent) {
                    watchReply(text, before);
                    scheduleAutoLock();
                } else {
                    scheduleAutoLock();
                }
                hideKeepAlive();
            } catch (Throwable t) {
                // 之前这里没有兜底，线程一挂就完全没有日志，只能干瞪眼
                log("发送流程异常：" + t);
                update("发送失败：" + t.getClass().getSimpleName());
                hideKeepAlive();
            }
        }).start();
    }

    /** 等 Agent 答完，抓取回复，总结后播报（不念全文） */
    private void watchReply(String sentText, java.util.List<String> before) {
        new Thread(() -> {
            ClickA11yService svc = ClickA11yService.get();
            if (svc == null) return;

            // 等生成开始（最多 8 秒）
            boolean started = false;
            for (int i = 0; i < 20; i++) {
                if (svc.hasText(DSH_PKG, "停止生成")) { started = true; break; }
                sleepMs(400);
            }
            log(started ? "检测到生成中" : "未检测到生成状态，仍等待新增内容");

            // 等生成结束：要求连续 2 次都看不到「停止生成」才算真结束
            // （流式输出时该按钮会瞬断，单次判断会误判为结束）
            int quiet = 0;
            int waited = 0;
            while (waited < 180000) {
                sleepMs(800);
                waited += 800;
                if (svc.hasText(DSH_PKG, "停止生成")) {
                    quiet = 0;
                } else {
                    quiet++;
                    if (quiet >= 2) break;
                }
            }
            log("生成结束判定，用时 " + (waited / 1000) + " 秒");
            sleepMs(500);

            // 只从"新增的文本块"里挑，避免抓到上一条回复
            String reply = svc.pickNewReply(DSH_PKG, before, sentText, 6);
            if (reply == null || reply.trim().isEmpty()) {
                log("没有新增文本，改用兜底抓取");
                reply = svc.lastTextBlock(DSH_PKG, sentText, 12);
            }
            if (reply == null || reply.trim().isEmpty()) {
                log("没抓到回复文本，跳过播报");
                replyCaptured = true;   // 抓不到也别一直亮着屏
                return;
            }
            log("抓到回复（" + reply.length() + " 字）："
                    + (reply.length() > 50 ? reply.substring(0, 50) + "…" : reply));
            replyCaptured = true;   // 抓到了，可以允许息屏了
            Narrator.summarizeAndSpeak(this, reply);
        }).start();
    }

    /**
     * 发完 10 秒后自动息屏。
     *
     * 两个约束：
     *   1. 只在我们自己点亮屏幕的情况下才锁（用户本来在用手机时不打扰）
     *   2. 必须等"回复已抓到"再锁 —— 锁屏会让 App 失去焦点，
     *      抓取就断了；而 TTS 播放不受锁屏影响，所以照常能听到。
     */
    private void scheduleAutoLock() {
        new Thread(() -> {
            try {
                sleepMs(10000);                    // 发完先等 10 秒
                if (!weUnlocked) {
                    log("不是我们点亮的屏，不自动息屏");
                    return;
                }
                // 等回复抓取完成（最多再等 90 秒），避免锁屏打断抓取
                for (int i = 0; i < 45 && !replyCaptured; i++) sleepMs(2000);
                sleepMs(1500);                     // 留出 TTS 起播的时间
                boolean ok = ClickA11yService.lockScreen();
                log("自动息屏：" + ok);
                weUnlocked = false;
                replyCaptured = false;
            } catch (Throwable t) {
                log("自动息屏异常：" + t);
            }
        }).start();
    }

    /** 锁屏时点亮屏幕并自动划开（需要手机未设安全锁） */
    private void ensureUnlocked() {        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && !km.isKeyguardLocked()) {
                log("无需解锁");
                return;
            }
            log("检测到锁屏，唤醒并解锁");
            Intent i = new Intent(this, UnlockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
            for (int k = 0; k < 20; k++) {
                sleepMs(200);
                if (km == null || !km.isKeyguardLocked()) break;
            }
            boolean stillLocked = km != null && km.isKeyguardLocked();
            log("解锁结果：仍锁屏=" + stillLocked);
            if (!stillLocked) weUnlocked = true;   // 记下来：是我们点亮并解锁的
            sleepMs(400);
        } catch (Throwable t) {
            log("解锁异常：" + t);
        }
    }

    private void bringDshForward() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(DSH_PKG, DSH_ACT);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            log("已拉起 DSH Go");
        } catch (Exception e) {
            log("拉起失败：" + e);
        }
    }

    // ---------- 触发 ----------

    private void onTrigger() {
        if (!recording) startRecording(); else stopRecordingAndTranscribe();
    }

    // ---------- 保活悬浮窗 ----------
    //
    // MIUI 的 GreezeManager 会在应用退到后台几秒后直接 SIGSTOP 整个进程，
    // 连前台服务都挡不住（实测 sleep(2500) 被拉长到 7.5 秒，
    // 直到用户按了媒体键才解冻）。
    // 有"可见窗口"的应用不会被冻结 —— 所以这几秒挂一个 1x1 透明悬浮窗。

    private android.view.View overlayView;
    private android.view.WindowManager overlayWm;

    private void showKeepAlive() {
        // View 必须在有 Looper 的线程创建，否则会报
        // Can't create handler inside thread that has not called Looper.prepare()
        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::showKeepAliveOnMain);
    }

    private void showKeepAliveOnMain() {
        try {
            if (overlayView != null) return;
            overlayWm = (android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE);
            overlayView = new android.view.View(this);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    1, 1,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = 0;
            lp.y = 0;
            lp.alpha = 0.01f;
            overlayWm.addView(overlayView, lp);
            log("保活悬浮窗已显示");
        } catch (Throwable t) {
            log("保活悬浮窗失败：" + t);
        }
    }

    private void hideKeepAlive() {
        try {
            if (overlayView != null && overlayWm != null) overlayWm.removeView(overlayView);
            log("保活悬浮窗已移除");
        } catch (Throwable ignored) { }
        overlayView = null;
    }
    // ---------- 静音音轨 ----------

    private static AudioTrack silentRef;

    /** 播报时暂停静音音轨，避免两路混流被重采样 */
    public static void pauseSilentTrack() {
        try {
            if (silentRef != null) {
                silentRef.pause();
                log("静音音轨已暂停");
            }
        } catch (Throwable ignored) { }
    }

    public static void resumeSilentTrack() {
        try {
            if (silentRef != null) {
                silentRef.play();
                log("静音音轨已恢复");
            }
        } catch (Throwable ignored) { }
    }

    private void startSilentPlayback() {
        try {
            // 采样率必须用 48000！
            // 之前用 8000，会把音频输出设备锁在 8kHz，
            // 导致 TTS（22k/24k）被重采样到 8k 播放 —— 声音发闷、听不清。
            // 48kHz 是 A2DP / 系统混音的标准采样率，不会被降级。
            int sr = 24000;                // 与 TTS 源一致
            int frames = sr / 10;          // 100ms 循环
            silent = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            short[] zeros = new short[frames];
            silent.write(zeros, 0, zeros.length);
            silent.setLoopPoints(0, zeros.length, -1);
            silent.setVolume(0.0001f);
            silent.play();
            silentRef = silent;
            log("静音循环已启动（24kHz 单声道）");
        } catch (Exception e) {
            log("静音循环启动失败：" + e);
        }
    }

    // ---------- 通知 ----------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "DSH 手势桥", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now),
                "触发", pi).build();
        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("DSH 手势桥")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setStyle(style)
                .addAction(action)
                .build();
    }

    private void update(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try { nm.notify(NOTIF_ID, buildNotification(text)); } catch (Exception ignored) { }
        }
        log("状态：" + text);
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WatchdogReceiver.schedule(this);   // 每次启动都续一次看门狗
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉时，尽快把自己拉回来
        WatchdogReceiver.schedule(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        recording = false;
        try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) { }
        if (silent != null) {
            try { silent.stop(); silent.release(); } catch (Exception ignored) { }
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        log("服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
