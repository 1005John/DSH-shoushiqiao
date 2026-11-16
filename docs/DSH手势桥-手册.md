# DSH 手势桥 —— 架构与运维手册

> 眼镜双击说话 → 手机自己录音、转写、写进 DSH Go 并发送。
> 最后更新：2026-11-15

---

## 一、整体架构

```
┌─────────────┐   AVRCP 媒体键（蓝牙）
│ 华为 Eyewear │ ─────────────────────────┐
└─────────────┘                          │
                                          ▼
                            ┌──────────────────────────┐
                            │  DSH 手势桥 (com.dsh.bridge) │
                            │                          │
                            │  1. MediaSession 接媒体键  │
                            │  2. AudioRecord 连续录音   │
                            │  3. 上传云端转写           │
                            │  4. 无障碍写入 + 发送       │
                            └───────────┬──────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
          /v1/audio/transcriptions  无障碍服务        DSH Go
          TS/SenseVoiceSmall        (读界面/注入触摸)   (com...dsh_app)
```

**交互语义**

| 操作 | 行为 |
|---|---|
| 双击镜腿 | 开始连续录音（不管停顿，全录） |
| 再双击 | 停止 → 整段上传转写 → 写入 DSH Go → 自动发送 |

---

## 二、组件清单

| 组件 | 包名 / 位置 | 说明 |
|---|---|---|
| DSH Go | `io.github.ccch1mneyyy.dsh_app` | 主应用，内置 Linux，跑 DSH 引擎 |
| 手势桥 | `com.dsh.bridge` | 本次开发，25KB |
| 源码（手机） | `/sdcard/Download/dshbridge/` | Manifest + 3 个 java + res |
| 编译产物 | `/sdcard/Download/DSH-Go/bridge.apk` | 由 DSH Go 的 Agent 编译 |
| 构建工作区 | `/root/workspace/apk/dshbridge`（DSH Go 内） | 编译时从上面复制 |
| 构建脚本 | `/root/workspace/apk/build.sh` | aapt2(经 qemu) + javac + d8 + apksigner |

### 源码结构

```
dshbridge/
├── AndroidManifest.xml          # 权限、queries、前台服务类型
├── res/
│   ├── values/strings.xml
│   └── xml/a11y_config.xml      # canPerformGestures=true（必须）
└── src/com/dsh/bridge/
    ├── MainActivity.java        # 设置界面：开无障碍、启动服务、测试点击
    ├── BridgeService.java       # 核心：媒体键/录音/转写/发送
    └── ClickA11yService.java    # 无障碍：找控件、注入触摸、写文字
```

---

## 三、关键参数

| 项 | 值 |
|---|---|
| 包名 | `com.dsh.bridge` |
| minSdk / targetSdk | 26 / 34 |
| 目标应用 | `io.github.ccch1mneyyy.dsh_app` |
| 目标 Activity | `io.github.ccch1mneyyy.dsh_app.MainActivity` |
| ASR 端点 | `https://onerouter.cmaiot.cn/v1/audio/transcriptions` |
| ASR 模型 | `TS/SenseVoiceSmall` |
| 采样率 | 16000 Hz / 单声道 / PCM16 |
| 录音源 | `AudioSource.VOICE_RECOGNITION` |

---

## 四、编译（全部在手机上完成）

### 方式 A：让 DSH Go 的 Agent 编译（推荐）

在 DSH Go 对话框里发：

```
rebuild /sdcard/Download/dshbridge to /sdcard/Download/DSH-Go/bridge.apk
```

约 60–90 秒产出。

### 方式 B：自己驱动（脚本化）

```bash
# 1. 启动 DSH Go，点输入框，输入上面那句，点「发送」
# 2. 等待，检查产物时间戳
adb shell "stat -c '%Y' /sdcard/Download/DSH-Go/bridge.apk"
# 3. 拉下来安装
adb pull /sdcard/Download/DSH-Go/bridge.apk bridge.apk
adb install -r -g bridge.apk
```

---

## 五、安装后必须做的三件事

```bash
# 1. 录音权限（每次重装都会被重置！）
adb shell pm grant com.dsh.bridge android.permission.RECORD_AUDIO

# 2. 悬浮窗（后台拉起 DSH Go 需要）
adb shell appops set com.dsh.bridge SYSTEM_ALERT_WINDOW allow

# 3. 无障碍服务（重装后会掉出列表）
adb shell settings put secure enabled_accessibility_services \
  'io.github.ccch1mneyyy.dsh_app/io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService:com.dsh.bridge/com.dsh.bridge.ClickA11yService'
adb shell settings put secure accessibility_enabled 1
```

然后打开 App 点「② 启动手势服务」。

**MIUI 需要手动授权的两项**（设置 → 应用管理 → DSH 手势桥 → 其他权限）：

- ✅ **后台弹出界面**（不加则从桌面双击拉不起 DSH Go）
- ✅ **显示悬浮窗**（双保险）
- ✅ 链式启动管理（建议）

---

## 六、故障排查表

| 现象 | 原因 | 处理 |
|---|---|---|
| 双击完全无反应 | 眼镜同时连了华为手机，手势被抢 | 断开华为手机的蓝牙/配对 |
| 双击无反应（单机） | 静音音轨没起来 → `musicActive=false`，MIUI 不转发媒体键 | 重启服务；看日志有没有「静音循环已启动」 |
| 拉起了但不录音 | 录音权限被重置 | `pm grant RECORD_AUDIO` |
| 从桌面双击拉不起 | MIUI「后台弹出界面」未授权 | 手动开启该项权限 |
| 写入了但没发送 | 找不到「发送」按钮 | 看日志 `选中 '发送' bounds=...`；按钮不在右侧会跳过 |
| 转写为空 | 录音太短 / 无声 | 说话前先双击，说完再双击 |
| ASR HTTP 非 200 | VPN 断 / key 失效 | 查日志里的响应体；确认 Atrust 连着 |
| 服务启动即崩 | 用了 microphone 类型前台服务 | 已改回 mediaPlayback（MIUI 不允许 mic 类型） |

---

## 七、这一路踩过的坑（都是实机验证的）

### 1. MIUI 的 AVRCP 门槛（最难的一关）

眼镜双击发的是 AVRCP `PLAY/PAUSE`，但 MIUI 只在**真的"有音乐在播"**时才把它转发给应用。必须同时满足：

- `MediaSession` 活跃且 `FLAG_HANDLES_MEDIA_BUTTONS`
- `PlaybackState` = `STATE_PLAYING`
- **`AudioManager.isMusicActive()` == true** → 用 `AudioTrack` 循环播放 1 秒静音（`MODE_STATIC` + `setLoopPoints`，几乎不耗电）
- 通知必须是 `Notification.MediaStyle` 并绑定 session token

日志判据：`AvrcpTargetService: PlayStatus: 1 musicActive: true` 才算过。

### 2. 键值会变

「在播」状态下眼镜发 `KEYCODE_MEDIA_PAUSE(127)`，暂停状态发 `PLAY(126)`。两个都要接。

### 3. Flutter 不吃 `ACTION_CLICK`

`performAction(ACTION_CLICK)` 返回 true 但界面没反应。必须用 `dispatchGesture` 注入真实触摸（需要 `canPerformGestures="true"`）。

### 4. 控件定位三重过滤

Flutter 语义树里有大量干扰：

- 隐藏节点（bounds 高度 0）→ `isVisibleToUser()` + 高度过滤
- 同名节点多个（聊天正文里也有"发送"字样）→ 取**按钮尺寸**（高 40~360、宽 40~520）
- 聊天卡片居中、输入栏在右侧 → 要求 **x > 78% 屏宽**（不受软键盘影响）

### 5. 软键盘弹出后界面找不到

`setText` 后输入框获得焦点、键盘弹出，`getRootInActiveWindow()` 返回的是**输入法窗口**。必须遍历 `getWindows()` 按包名找目标窗口。

### 6. `ACTION_SET_TEXT` 不会让按钮变「发送」

只改文字不改 Flutter 状态。必须先**真实点击输入框**唤起键盘，再写入。

### 7. 系统识别器会丢字（本方案的原动力）

`SpeechRecognizer` 内部强制 VAD，在停顿处切断并**丢弃该段音频**；小米引擎还频繁返回 `ERROR_NO_MATCH(7)`，导致整段丢失。它也不支持 `EXTRA_PARTIAL_RESULTS`。

**结论：不能用它做"整段转写"，必须自己录音 + 云端 ASR。**

### 8. MIUI 不允许 microphone 类型前台服务

`startForeground` 抛 `SecurityException`，即便权限齐全、应用在前台。只能用 `mediaPlayback` 类型——实测后台录音不受影响。

### 9. 每次重装都会重置 RECORD_AUDIO

MIUI 对敏感权限的处理。**先授权再启动服务**，顺序反了会崩。

---

## 八、已知限制

| 限制 | 说明 | 可否解决 |
|---|---|---|
| 依赖 VPN | 转写走内网路由 | 换公网 ASR 即可 |
| 屏幕可能亮起 | 发送前需拉起 DSH Go | 无（无障碍操作要求窗口活跃） |
| 眼镜不能同时连华为手机 | 手势被抢占 | 断开华为或解除配对 |
| 静音音轨常驻 | 保持 `musicActive=true` | 功耗极低（MODE_STATIC 静音循环） |
| 重装后要重新授权 | MIUI 行为 | 可在 App 内加运行时申请 |

---

## 九、快速自检

```bash
# 服务在跑？
adb shell "dumpsys activity services com.dsh.bridge | grep -c BridgeService"

# 权限在？
adb shell "dumpsys package com.dsh.bridge | grep 'RECORD_AUDIO:'"

# 无障碍绑定？
adb shell "dumpsys accessibility | grep -o 'DSH 手势桥[^}]*'"

# 静音音轨活着？（medaPlayback 会话）
adb shell "dumpsys media_session | grep -A8 'DSHBridge'"

# 最近日志
adb shell "logcat -d -s DSHBridge | tail -30"
```

---

## 十、回复播报（总结 + TTS）

### 链路

```
DSH Go 回复完成
   ↓ 无障碍抓取回复全文（过滤界面固定文案，取最长那段）
   ↓ 调模型总结（限 60 字，口语化）
   ↓ 调 TTS 合成 MP3
   ↓ MediaPlayer 播放（USAGE_MEDIA → 自动走眼镜 A2DP）
```

**不念全文**，只念摘要，避免长时间播报。

### 三个模型各司其职

| 用途 | 模型 | 说明 |
|---|---|---|
| 语音识别 | `TS/SenseVoiceSmall` | `/v1/audio/transcriptions`，multipart 上传 WAV |
| 总结 | `Qwen3-Next-80B-A3B-Instruct` | **无思考**，0.5s，输出口语化 |
| 语音合成 | `TS/CosyVoice2-0.5B` | `voice` 必须用中文音色名 |

> ⚠️ **key 与模型权限是绑定的**。换 key 后必须重新确认可用模型：
> 用 `Auto-Multi` 会返回 `403 model_not_visible`，总结直接失败并退回"念前 60 字"。

### TTS 参数

```http
POST /v1/audio/speech
{
  "model": "TS/CosyVoice2-0.5B",
  "input": "要念的文字",
  "voice": "中文女"
}
```

| voice | 状态 |
|---|---|
| `中文女` / `中文男` / `英文女` / `英文男` | ✅ 200，返回 MP3 |
| `alloy` / `default` | ❌ 502 |

返回是 **MP3**（帧头 `FF F3`）。

### 总结提示词

```
请把下面的内容用不超过 60 个字总结成一句口语化的话，
直接给结论，不要开场白、不要分点、不要用 markdown：

<回复全文>
```

### 抓取回复的两个坑

**① 抓到了界面文案**
最初"取最后一段长文本"，结果抓到输入栏上方的「上下文已用 18%」。

修法：
- 维护一个固定文案黑名单（`上下文已用`/`查看用量`/`本轮`/`模型：`/`语音输入`…）
- 过滤后取**最长**的一段，而不是最后一段
- 排除自己刚发出去的那句

**② 推理模型返回空 content**
`TS-MOMA/DeepSeek-V4-Flash`、`GLM-5.3-Flash` 这类会先把 token 花在 `reasoning_content` 上，
`content` 是空的（`finish_reason=length`）。

修法：`content` 为空时回退取 `reasoning_content` 最后一个有意义的行；但仍建议**直接用 Instruct 模型**。

### 播报时机

发送成功后启动看门狗线程：

1. 每 400ms 查界面有没有「停止生成」，最多 6 秒 → 确认开始生成
2. 每 800ms 查「停止生成」是否消失，最多 3 分钟 → 等待生成结束
3. 结束后等 1 秒，抓取回复
4. 总结 → TTS → 播放

### 播报相关日志判据

```
检测到生成中
抓到回复（501 字）：<前 50 字>
播报内容：[<摘要>]
TTS 音频 81168 字节，开始播放
播报结束
```

若出现 `总结 HTTP=403 model_not_visible` → 换 key 或换模型。
若出现 `content 为空，改用 reasoning 兜底` → 该模型是推理模型，建议换掉。

---

## 十一、MOA 聊天记录接入

### 全链路

```
MOA 客户端（PC，D:\software\MOA客户端）
   ↓ watch_moa_cloud.js 每 10 秒采集（SQLCipher 解密，密钥 key.dat）
   ↓ POST http://127.0.0.1:8090/moa
SSH 隧道（moa-tunnel，-L 8090:127.0.0.1:8090）
   ↓
云服务器 YOUR_VPS_IP
   ├─ moa_receiver.py（127.0.0.1:8090）→ /root/moa-chats/<sid>/messages.jsonl
   ├─ IM Bot → todo/im_digest.md
   └─ moa_read_api.py（127.0.0.1:8091，只读，token 鉴权）★新增
   ↓ Caddy 反代 https://39-106-60-159.sslip.io/moa/*
手机 DSH 手势桥
   ├─ 每 5 分钟拉 /digest → 有变化 → 总结 → TTS → 眼镜
   └─ 每 5 分钟拉 /all → 增量写 /sdcard/Download/moa/messages.jsonl
```

### 云端只读 API（`/root/moa_read_api.py`）

| 接口 | 说明 |
|---|---|
| `GET /health` | 自检，返回会话数 |
| `GET /sessions` | 会话列表（sid / name / count / last_time） |
| `GET /messages?sid=&since=&limit=` | 单会话增量 |
| `GET /all?since=&limit=` | 全量/增量（手机同步用） |
| `GET /digest` | IM Bot 摘要 |
| `GET /search?q=` | 全文搜索 |

鉴权：`X-Moa-Token: <48位随机>`，无 token 返回 401。
服务：`moa-read-api.service`（systemd，Restart=always）。

### ★ 关键坑：Android 不能把 IP 放进 SNI

Caddy 要求客户端发 SNI，但 **RFC 6066 禁止 SNI 使用 IP 字面量**，Android 会直接不发。
结果：

```
手机 → https://YOUR_VPS_IP/          ✗ TLS ALERT_INTERNAL_ERROR
手机 → https://39-106-60-159.sslip.io/ ✓ HTTP 200
```

解决：用 `sslip.io` 免费泛解析域名（`39-106-60-159.sslip.io` → `YOUR_VPS_IP`），
在 Caddyfile 里单独开一个站点，**不动原有的 IP 站点**。

> 同理，Windows 的 curl（schannel）也受此限制，PC 端测试要用 Node 显式设 servername，
> 或直接用域名。

### 手机端文件

| 文件 | 用途 |
|---|---|
| `MoaReader.java` | HTTPS 拉取（自签证书 trust-all）、摘要哈希比对 |
| `MoaSync.java` | 增量同步消息到 `/sdcard/Download/moa/messages.jsonl` |

落盘位置选 `Download` 是因为 **DSH Go 的 Linux 能读到**，Agent 可直接分析。

需要权限：`MANAGE_EXTERNAL_STORAGE`（用 `appops set ... allow` 授予）。

### Agent 侧配置

在 DSH Go 的 `/root/workspace/AGENTS.md` 里写明：

```
MOA 聊天记录位于 /sdcard/Download/moa/messages.jsonl
（JSON Lines，字段 session_id / chat_name / sender / time / content）
被问到消息时，先读这个文件。
```

格式示例：

```json
{"session_id":"104529","chat_name":"产品管理委员会","sender":"柴晓楠","time":"2026-10-08 16:11:26","content":"..."}
```

### 播报策略

- **不念全文**，只念摘要（`SUMMARY_MAX_CHARS = 120`）
- 摘要模型：`Qwen3-Next-80B-A3B-Instruct`（无思考，0.5s，口语化）
- 摘要取摘要文件里最新的 `## ` 小节

### 排查

| 现象 | 原因 |
|---|---|
| `MOA 拉取异常: SSLHandshakeException` | 用了 IP 而非域名（SNI 问题） |
| `MOA 拉取失败 HTTP=401` | token 不匹配 |
| `无法创建目录 /sdcard/Download/moa` | 未授予 `MANAGE_EXTERNAL_STORAGE` |
| 摘要一直"无变化" | IM Bot 没产生新摘要（周末无消息属正常） |

---

## 十二、邮箱接入（IMAP）

### 链路

```
双账号 IMAP（SSL 993）
   ├─ QQ 邮箱      imap.qq.com
   └─ 企业邮箱      imap.cmiot.chinamobile.com
   ↓ ImapSync 每 15 分钟同步（每账号最近 30 封，增量）
/sdcard/Download/mail/messages.jsonl
   ↓
Agent 读取 → 语音问答「有什么新邮件」
```

### 账号配置（**不写进代码**）

放在 `/sdcard/Download/mail/accounts.json`：

```json
[
  {"name":"QQ邮箱","host":"imap.qq.com","user":"xxx@qq.com","pass":"授权码"},
  {"name":"企业邮箱","host":"imap.cmiot.chinamobile.com","user":"xxx@cmiot.chinamobile.com","pass":"密码"}
]
```

> 早期版本把凭据写死在 `ImapSync.java` 里，会随 APK 一起分发。
> 现在改为运行时读取，APK 中不含任何凭据。

### 数据格式

```json
{"account":"企业邮箱","from":"陈衍圳","subject":"【测试完成】...","time":"2026-11-10 18:10:17","body":"各位领导、同事：..."}
```

`body` 已解码并去 HTML 标签，截取前 400 字。

### ★ 关键坑：JavaMail 的 mailcap 不会被打进 APK

d8 只把 class 合并进 dex，**jar 里的 `META-INF/mailcap` 资源不会进 APK**。
后果是 `getContent()` 退化成返回原始 MIME 文本：

```
This is a multi-part message in MIME format. ------=_NextPart_... base64 UVHpgq7n...
```

解法：代码里手动注册 handler（`ImapSync` 的 static 块）：

```java
MailcapCommandMap mc = new MailcapCommandMap();
mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed; x-java-fallback-entry=true");
mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
CommandMap.setDefaultCommandMap(mc);
```

### 构建依赖

`/sdcard/Download/dshbridge/libs/` 下放两个 jar：

```
android-mail-1.6.7.jar        646 KB
android-activation-1.6.7.jar   71 KB
```

`build.sh` 已支持 `libs/*.jar`：

- `javac` 的 `-classpath` 拼接 `libs/*.jar`
- `d8` 只把 jar 当 **program input**，不能再放进 `--classpath`，否则报
  `Type ... defined multiple times`

打出来后 APK 从 33 KB 涨到 **341 KB**（JavaMail 类进了 dex）。

### 验证账号是否可用（PC 端，不用改代码）

```bash
node test_imap.js imap.qq.com 993 账号 授权码
node test_imap.js imap.cmiot.chinamobile.com 993 账号 密码
```

输出 `LOGIN 成功` 和收件箱封数即可。

### 排查

| 现象 | 原因 |
|---|---|
| `邮件配置不存在` | 缺 `/sdcard/Download/mail/accounts.json` |
| `AUTHENTICATIONFAILED` | 授权码/密码错，或 QQ 未开启 IMAP 服务 |
| 正文是 `This is a multi-part message` | mailcap 没注册（见上） |
| 正文为空 | 邮件只有 HTML 且解析失败，或纯附件邮件 |
| 同步很慢 | IMAP 首次拉取 30 封需 20–40 秒，正常 |

---

## 十三、TTS 播放：三个坑与最终方案

### 最终实现（v6.8 起）

```
MP3(24kHz) → MediaCodec 解码 → 声道归并 → AudioTrack MODE_STATIC 整段预载播放
静音音轨：24kHz 单声道
```

### 坑 1：接口返回 32 位 WAV，MediaPlayer 播放变调

`response_format=wav` 得到的是 **32 位** PCM（不是 16 位）：

```
采样率=24000  声道=1  位深=32
```

Android MediaPlayer 对 32 位 WAV 支持很差，播放出来像"采样率降低"。
**结论：用 MP3，不要用 WAV。**

### 坑 2：MODE_STREAM 会因 CPU 抢占产生断续

用 `MODE_STREAM` 边写边播时，后台还有网络请求、无障碍查询抢 CPU，
一旦写入跟不上蓝牙缓冲就断续/爆音——表现为"有时正常有时不正常"。

```java
// 错误：缓冲区设得比音频还大 → write 瞬间灌完 → 立刻 stop 把声音掐掉
int bufSize = pcm.length * 2 + 4096;

// 正确：整段预载，播放期间应用完全不参与
.setTransferMode(AudioTrack.MODE_STATIC)
.setBufferSizeInBytes(bytes);
track.write(pcm, 0, pcm.length);
track.play();
while (track.getPlaybackHeadPosition() < pcm.length) Thread.sleep(100);
```

### 坑 3：不该做重采样

曾试过 48kHz 立体声上混，结果被蓝牙 SBC 编码压出伪影。
**直接用源采样率、单声道**，推给蓝牙的数据量最小，最稳。

### 音色的不确定性（无解）

同样的输入文字 + 同样的 `voice`，接口输出**不稳定**：

```
5 次请求 → 3 种不同的 MP3（大小相同，MD5 不同）
加 seed 参数无效
```

这是 CosyVoice 模型采样过程的固有随机性。表现为音色轻微变化，偶尔整段变成另一个音色。
客户端无法控制。备选模型（`TS/MegaTTS3`、`qwen3-tts-flash`、`qwen-audio-3.0-tts-flash`）均返回"服务暂时不可用"。

---

## 十四、重装 App 后必做（否则会出现"假故障"）

`adb install -r` 会强制停止应用，并**重置所有运行时权限**。每次重装后必须执行：

```bash
# 1. 录音权限（不授权 → 双击没反应，日志显示"AudioRecord 初始化失败"）
adb shell pm grant com.dsh.bridge android.permission.RECORD_AUDIO
adb shell appops set com.dsh.bridge RECORD_AUDIO allow

# 2. 悬浮窗 / 外部存储 / 通知
adb shell appops set com.dsh.bridge SYSTEM_ALERT_WINDOW allow
adb shell appops set com.dsh.bridge MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.dsh.bridge POST_NOTIFICATION allow

# 3. 后台运行
adb shell cmd appops set com.dsh.bridge RUN_IN_BACKGROUND allow
adb shell cmd appops set com.dsh.bridge RUN_ANY_IN_BACKGROUND allow

# 4. 无障碍
adb shell settings put secure enabled_accessibility_services \
  'io.github.ccch1mneyyy.dsh_app/io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService:com.dsh.bridge/com.dsh.bridge.ClickA11yService'
adb shell settings put secure accessibility_enabled 1

# 5. 启动服务（最容易漏！）
#    打开 App → 点「② 启动手势服务」
```

**MIUI 手动授权项**（每次重装会重置，adb 改不了）：
设置 → 应用管理 → DSH 手势桥 → 其他权限 → **后台弹出界面** ✅ / **显示悬浮窗** ✅

### 四项自检

```bash
adb shell "dumpsys activity services com.dsh.bridge | grep -c BridgeService"   # 应为 3
adb shell "dumpsys package com.dsh.bridge | grep 'RECORD_AUDIO:'"               # granted=true
adb shell "dumpsys accessibility | grep -o 'DSH 手势桥[^}]*'"                   # capabilities=33
adb shell "logcat -d -s DSHBridge | tail -3"                                   # 有"服务已启动"
```

---

## 十五、MIUI 进程冻结（导致"要按第三次") 

**现象**：双击后应用切到 DSH Go，但不输入文字；再按一次键才突然继续。

**证据**：

```
20:00:24.723  已拉起 DSH Go
20:00:32.210  前台等待耗时 3ms -> true     ← 中间 sleep(2500) 实际过了 7.5 秒
20:00:32.206  GreezeManager: THAW uid = 10348 reason: MediaButton
```

MIUI 的 `GreezeManager` 在应用退到后台几秒后直接 SIGSTOP 整个进程，
**前台服务挡不住**，直到用户按键才解冻。

**解法：保活悬浮窗**。有可见窗口的进程不会被冻结，而我们有悬浮窗权限：

```java
// 1x1 透明、不可触摸、不可聚焦
WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
    1, 1, TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT);
lp.alpha = 0.01f;
wm.addView(view, lp);
```

在"拉起 DSH Go → 输入文字 → 发送"整个窗口期挂住，发送完成后立刻移除。

---

## 十六、待办系统（提炼 / 提醒 / 日历）

### 为什么放手机上

云服务器 `YOUR_VPS_IP` **访问不到内网 LLM**（`onerouter.cmaiot.cn` 解析到 `198.18.0.2`，VPN 虚拟段，公网不可达）。

所以原来云端 IM Bot 用外网 DeepSeek 提炼待办的方式无法改造成内网模型，
**提炼必须放在处于公司网络的设备上**——手机最合适，数据本来就在这里。

### 三层结构

```
① 提炼   TodoExtractor：增量拉新消息 → 内网 LLM → JSON 待办
② 存储   /sdcard/Download/todo/todos.jsonl
③ 提醒   BridgeService：每分钟查到期 → TTS 播报；同时写 MIUI 日历兜底
```

### 提炼（TodoExtractor）

- **增量**：`last_extract` 记在 SharedPreferences，只处理之后的新消息
- **首次运行**：从「本地最新一条消息」往前 2 小时起算
  （直接用当前时间会得到空窗口——周末/间隔久时没有任何消息）
- **上限**：单次最多喂 40 条消息，避免 token 爆掉
- **模型**：`Qwen3-Next-80B-A3B-Instruct`（无思考，0.5 秒）
- **输出格式**：

```json
[{"title":"20字以内","due":"YYYY-MM-DD HH:MM 或 空",
  "priority":"高/中/低","summary":"一句话","source":"来源",
  "urgent":true或false,"urgent_reason":"为什么紧急，10字内"}]
```

### 紧急 vs 普通（分流处理）

```
urgent=true  → 立即 TTS 播报 + 标记已播（不再重复）+ 仍入库
urgent=false → 只入库，等到 due 时间再播报
```

**urgent 判定标准**（写在提示词里）：

1. 明确要求「马上/立即/现在就/尽快」
2. 线上故障、生产问题、系统告警
3. 两小时内截止或开会
4. 领导直接点名要求立刻答复

多条紧急项会**合并成一条播报**（最多 3 条），避免连播轰炸：

```
播报内容：[有 2 件急事：处理核心网关502错误（生产环境告警）；答复张雄威（领导点名）]
```

**提炼频率**：5 分钟一轮（跟上 MOA 同步），无新消息直接跳过，token 成本可忽略。

实测（注入一封"【紧急】生产环境核心网关 502"邮件）：

```
21:58:27  注入
21:59:27  待办提炼：本轮输入 1 条新消息
21:59:28  待办提炼完成，新增 1 条，紧急 1 条
21:59:28  紧急播报：有 1 件急事：处理核心网关502错误（生产环境告警）
```

**注入后 1 分钟内完成识别与播报。**


- **去重**：`sha1(title|due)` 前 12 位作为 id
- **容错**：模型返回解析失败就跳过这批，下轮重试，不污染数据

### 提醒

```java
// 每分钟检查
for (todo : TodoStore.dueNow(ctx)) {          // due <= now 且 24 小时内
    Narrator.speakDirect("重要待办：{title}，截止 {due}");
    TodoStore.markAnnounced(id);              // 播过就不再播
    return;                                   // 一次只播一条
}
```

> `dueNow` 过滤掉 24 小时之前的历史积压，否则导入 51 条历史待办会连播几十次。

### 日历兜底（CalendarHelper）

有明确到期时间的待办会静默写入 MIUI 日历（事件 + 提前 10 分钟提醒），
**即使我们的进程被 MIUI 杀掉，系统也会按时提醒**。

- 需要 `READ_CALENDAR` / `WRITE_CALENDAR`（`pm grant`）
- 选第一个 `CAL_ACCESS_CONTRIBUTOR` 以上的日历，优先主日历
- 启动 20 秒后做一次**回填**：把「未来到期、还没写进日历」的补上（跳过已过期的）

### 已知限制

| 限制 | 说明 |
|---|---|
| 依赖内网 VPN | Atrust 断开时提炼会失败（播报不受影响） |
| 只有新消息才提炼 | 周末没消息时不产生待办，属正常 |
| 模型可能漏提 | 提示词已要求"只提取需要行动的事项"，仍会有误判 |

### 排查

| 现象 | 原因 |
|---|---|
| `待办提炼：无新消息` | `last_extract` 之后没有新消息 |
| `待办提炼 HTTP=4xx` | key 失效或模型不可用 |
| `待办提炼：返回内容无法解析为 JSON` | 模型输出格式漂移，看日志里的原文 |
| `日历：找不到可写日历` | 未授予日历权限，或设备无主日历 |
| 待办不播报 | `due` 为空，或已播报过，或超过 24 小时 |

---

## 十七、Tasks.org 集成（唯一数据源）

### 三方角色

| 系统 | 角色 |
|---|---|
| **Tasks.org** | **唯一数据源**，用户在这里增删改 |
| 桥接 App | 双向同步 + 提醒调度 |
| MIUI 日历 | 只做系统级提醒备份，**跟随 Tasks.org 自动清理** |
| DSH Go Agent | 读镜像文件回答 |

### 第三方 API 的三个硬规则（实测得出）

| 项 | 值 |
|---|---|
| 真实 URI | `content://org.tasks.api/v0/tasks`（**带 v0**，不是 `/tasks`） |
| 权限 | `org.tasks.permission.READ_TASKS` / `WRITE_TASKS`（需在 Manifest 声明） |
| 优先级格式 | 字符串 `high` / `medium` / `low`（不是数字） |

可写列白名单（写别的会抛 `IllegalArgumentException` 并中断整批）：

```
completed_at, due_all_day, due_date, list_id, notes, parent_id,
place_id, priority, recurrence, repeat_from, start_all_day, start_date, title
```

> 其他坑：集合 URI 不支持 `delete`；字段名是 `due_date` 不是 `dueDate`；不能写 `modified_at`。

### 写入：桥接 → Tasks.org

`TasksOrg.importAll()` 以**标题**为键做 upsert（有则 update，无则 insert），幂等。

### 读取：Tasks.org → 镜像

**为什么需要镜像**：Agent 读不到 Tasks.org（DSH Go 没声明该权限），连 adb shell 也被拒。

```
content://org.tasks/tasks（旧版）→ completed(0/1) + dueDate(毫秒)
content://org.tasks.api/v0/tasks  → priority(字符串)
        ↓ 按 _id 合并
/sdcard/Download/todo/tasks_live.jsonl   ← 每 5 分钟刷新
        ↓
DSH Go Agent 读这个文件
```

镜像字段：`id, title, done, due, priority, notes`

> 坑：v0 API 的 `completed_at` 对未完成任务**不返回 0**，必须用旧版 provider 的 `completed` 判断。

### 日历跟随（自动清理）

```
遍历日历事件（标题以【待办】开头）
   ↓ 反查 Tasks.org 实时状态
已完成(done) 或 已删除(不在 tasks_live 里)
   ↓
删除对应日历事件
```

**必须遍历事件，不能遍历待办**——否则待办已从 jsonl 删除时，残留的孤儿日历事件永远清不掉。

```
日历：已删除事件 id=3
日历清理：本地语音合成测试（已完成）
```

> Android 日历删除是**软删除**（`deleted=1`），行还在库里但 UI 不再显示。

### 提醒跟随

到期检查时若该待办在 Tasks.org 已完成，直接跳过：

```
已在你手机 App 里完成，跳过：答复张雄威吧唧服务器搭建
```

---

## 十八、本地 TTS（已取代云端）

### 为什么换

云端 TTS 对**同样的输入**返回不同音色（实测 5 次请求 3 种结果，偶尔整段变男声），
且 3~10 秒网络往返。这是服务端随机性，客户端无法修复。

### 方案

用系统内置引擎 `com.xiaomi.mibrain.speech`（`android.speech.tts.TextToSpeech`）。

**前提**：系统默认引擎原本是 `null`，必须设置：

```bash
adb shell settings put secure tts_default_synth com.xiaomi.mibrain.speech
```

`LocalTts.init()` 在服务启动时调用；就绪后所有播报走本地，不可用时回退云端。

| | 云端 TTS | 本地 TTS |
|---|---|---|
| 音色 | 随机变化 | 固定 |
| 延迟 | 3~10 秒 | <1 秒 |
| 网络 | 需 VPN | 完全离线 |
| 成本 | 消耗 token | 免费 |

---

## 十九、保活与自恢复

### 九层防护

| # | 机制 |
|---|---|
| 1 | 前台服务 + 常驻通知 |
| 2 | `START_STICKY` |
| 3 | 看门狗（`AlarmManager.setAndAllowWhileIdle`，每 10 分钟） |
| 4 | 开机自启（BOOT_COMPLETED） |
| 5 | 更新后自启（MY_PACKAGE_REPLACED） |
| 6 | 打开 App 自动启动 |
| 7 | Doze 白名单 |
| 8 | `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND` |
| 9 | 1×1 透明悬浮窗（关键几秒防冻结） |

### 需要用户手动开的（adb 改不了）

- 设置 → 应用管理 → DSH 手势桥 → **自启动**
- 省电策略 → **无限制**
- 最近任务里**锁定**该 App

### 无法自动恢复的情况

**不要点「强行停止」**——Android 会把 App 标记为 stopped，闹钟/广播/自启动全部失效，
必须手动打开一次 App。

### 权限自愈

`adb install -r` **不会**应用 `-g` 的授权（只有全新安装才生效），所以更新后录音权限必掉。
v8.5 起 App 启动时自己申请，日志：

```
权限结果：RECORD_AUDIO=允许
```








---

## 二十、熄屏 / 锁屏下的语音链路

### 问题

黑屏或锁屏时，双击镜腿能录音、能转写，但最后一步失败：

```
写入失败：找不到目标窗口
```

原因：**锁屏会让目标 App 失去焦点**，无障碍拿不到窗口树，点不到输入框。

实测对照：

| 场景 | 无障碍能否操作 |
|---|---|
| 亮屏 + App 在前台 | ✅ |
| **黑屏但无锁屏** + App 在前台 | ✅ 可用 |
| 黑屏 + 锁屏出现 | ❌ App 失焦 |

> 注意：屏幕熄灭后系统会显示锁屏，即使没有设置密码（滑动锁屏）。

### 解法：自建"解锁壳"

不修改 DSH Go，只在桥接 App 里加一个透明 Activity：

```java
// UnlockActivity
setShowWhenLocked(true);      // 显示在锁屏之上
setTurnScreenOn(true);        // 点亮屏幕
km.requestDismissKeyguard(this, callback);   // 划开无密码锁屏
finish();                     // 立刻退出，把前台交还目标 App
```

配合 `ensureUnlocked()` 轮询 `isKeyguardLocked()`，确认解锁完成再继续。

**前提：手机不能设置安全锁**（PIN / 图案 / 密码 / 指纹）。
有安全锁时 `requestDismissKeyguard` 会失败，日志会打印
`解锁：被取消（多半是设了安全锁）`。

### 时序优化：边说边解锁

解锁要 0.6~1.2 秒。如果等转写完成再解锁，用户会明显感到卡顿。

所以在**开始录音时**就并行启动解锁：

```
双击 → 开始录音 ──┬── 录音进行中
                  └── 同时点亮 + 划开锁屏
再双击 → 转写 → 发送（此时"无需解锁"，省掉 1 秒）
```

实测：从"再双击"到"消息发出"从 2.2 秒降到 **1.0 秒**。

### 自动息屏

发完消息后屏幕会一直亮着（因为 App 被拉到前台）。
用无障碍的 `GLOBAL_ACTION_LOCK_SCREEN` 主动锁屏（不需要设备管理权限）。

**两个必须遵守的约束**：

1. **只在我们自己点亮过的情况下才锁**
   否则用户正在用手机，突然被锁屏会很烦。用 `weUnlocked` 标记。

2. **必须等"回复已抓到"再锁**
   锁屏会让 App 失去焦点，抓取就断了。
   用 `replyCaptured` 标记：发完等 10 秒，然后等抓取完成（最多 90 秒）。

> **TTS 播报不受锁屏影响** —— 锁屏只熄灭屏幕，音频继续播放。
> 实测：22:09:15 锁屏，22:09:21 播报正常结束。

### 实测日志（完整链路）

```
22:08:49  双击 → 开始连续录音
22:08:49  检测到锁屏，唤醒并解锁        ← 边说边解
22:08:50  解锁：成功（0.6 秒）
22:08:54  再双击 → 录音结束 5 秒
22:08:55  转写：明天我有哪些代办？
22:08:55  无需解锁                     ← 预解锁生效
22:08:55  点击输入框 → 写入 → 点发送
22:08:56  已发送                       ← 转写到发出 1 秒
22:09:13  生成结束（16 秒）
22:09:14  抓到回复（177 字）
22:09:14  本地播报：明天只用搞定 ML307B-DC 固件复测…
22:09:15  自动息屏：true
22:09:21  播报结束                     ← 屏幕已灭，声音完整
```

### 排查

| 日志 | 含义 |
|---|---|
| `解锁：被取消（多半是设了安全锁）` | 手机有 PIN/图案，方案失效 |
| `解锁结果：仍锁屏=true` | 解锁失败，后续写入必然失败 |
| `不是我们点亮的屏，不自动息屏` | 正常：用户本来就在用手机 |
| `自动息屏：false` | 无障碍服务没连上，或系统版本 < 28 |