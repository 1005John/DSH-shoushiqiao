# MOA 采集与补推

两个脚本，解决同一件事：**把 MOA（企业 IM）的聊天消息送进云端，供手机读取**。

| 文件 | 用途 |
|---|---|
| `moa_collect.js` | 常驻采集器：每 10 秒扫一次数据库，把新消息推到云端 |
| `backfill_moa.js` | 一次性补推：按日期区间把历史消息重新推一遍 |

---

## 为什么不用 MOA 采集器自带的 `repush`

这个项目最初用的是 `watch_moa_cloud.js`（脚本内按 `r` 触发 repush）。
实测发现它有两个会**静默丢消息**的缺陷：

### 缺陷一：按会话列表遍历，会漏表

```js
// 旧脚本
const sessions = db.prepare('SELECT session_id, session_name FROM im_session_list').all();
for (const session of sessions) {
  const chatTable = 'im_chat_' + session.session_id;
  ...
}
```

数据库里有 **628 张 `im_chat_*` 表**，但 `im_session_list` 里的会话远少于此。
不在列表里的表，**里面的消息永远不会被采集**。

新脚本直接遍历所有 `im_chat_*` 表：

```js
const tables = db.prepare(
  "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'im_chat_%'").all();
```

### 缺陷二：先写本地再推送，失败也算成功

旧脚本的流程是：

```
读数据库 → 写本地 Markdown（推进水位） → 推送云端
                    ↑ 写在这里
```

推送失败时（断网、隧道断、云端重启），**水位已经推进了**，
那些消息再也不会被重推。

新脚本把顺序反过来，并且**只有全部批次都成功才推进水位**：

```js
if (ok) {
  wm[table] = rows[rows.length - 1].create_time;   // 仅成功才推进
} else {
  log('推送失败，保留水位等待重试: ' + chatName);
}
```

> 真实事故：11-16 那天网络切换导致隧道断了 2 小时，
> 云端只收到 13 条，而数据库里有 **201 条**，漏了 188 条。
> 这就是上面两个缺陷叠加的结果。

---

## 用法

### 采集器（常驻）

```bash
export MOA_DB_DIR="/path/to/MoaFiles/db"          # 含 user-*.db 和 moa_key.txt
export MOA_SQLITE_MODULE="better-sqlite3-multiple-ciphers"
node moa_collect.js --url http://127.0.0.1:8090/moa --secret YOUR_PUSH_SECRET --interval 10000
```

- 水位文件：同目录 `moa_watermark.json`（每张表一个最后成功推送的时间戳）
- 日志：同目录 `moa_collect.log`
- 首次运行只看最近 2 小时（`--lookback-hours N` 可调），避免一次推几万条历史

### 补推（一次性）

```bash
node backfill_moa.js --from 2026-11-15 --to 2026-11-18
```

接收端按指纹去重，重复推送是安全的。

---

## 数据库解锁

MOA 用 SQLCipher 加密，需要 `moa_key.txt` 里的密钥：

```js
db.pragma("cipher='sqlcipher'");
db.pragma("legacy=4");
db.pragma("key='" + key + "'");
```

> 少了 `legacy=4` 会报 `file is not a database` —— 这是最容易卡住的一步。

消息正文取 `search_text` 优先（已含解析后的文本），
回退 `content`，再回退 `[消息类型:N]`。

---

## 守护

采集器本身不重启自己，交给外部看门狗（见 `pc_watchdog.ps1`）：
读 `moa_collect.pid` 判断存活，不在就拉起。

> ⚠️ 看门狗判断进程时**不要按命令行字符串匹配**：
> 命令行里会包含调用方自己的脚本文本，容易把自己杀掉。
> 用 PID 文件，或把匹配模式在运行时拼接出来。
