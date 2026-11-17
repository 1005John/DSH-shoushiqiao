/**
 * MOA 采集器 v2 —— 替代 watch_moa_cloud.js
 *
 * 修掉旧脚本的两个缺陷：
 *   1. 旧脚本按 im_session_list 遍历，表存在但会话不在列表里的消息永远采不到
 *      → 本脚本直接遍历所有 im_chat_* 表
 *   2. 旧脚本先写本地 Markdown（推进水位）再推送，推送失败也不回退
 *      → 本脚本**只有推送成功才推进水位**，失败下轮重试
 *
 * 水位文件：moa_watermark.json（每张表一个最后成功推送的时间戳）
 * 接收端按指纹去重，所以重推是安全的。
 *
 * 用法：node moa_collect.js [--url ...] [--secret ...] [--interval 10000]
 */
const fs = require('fs');
const path = require('path');
const Database = require(process.env.MOA_SQLITE_MODULE || 'better-sqlite3-multiple-ciphers');

const args = {};
process.argv.slice(2).forEach((v, i, a) => {
  if (v.startsWith('--')) args[v.slice(2)] = a[i + 1];
});

const BASE = __dirname;   // 水位/日志落在脚本同目录
const DB_DIR = process.env.MOA_DB_DIR || 'C:/Users/USERNAME/Documents/MoaFiles/db';
const DB_FILE = path.join(DB_DIR, 'user-e4e366799af24e61663ca8f2815d43bb524290d2.db');
const KEY_FILE = path.join(DB_DIR, 'moa_key.txt');
const WM_FILE = path.join(BASE, 'moa_watermark.json');
const LOG_FILE = path.join(BASE, 'moa_collect.log');
const PUSH_URL = args.url || 'http://127.0.0.1:8090/moa';
const SECRET = args.secret || 'YOUR_MOA_PUSH_SECRET';
const INTERVAL = parseInt(args.interval || '10000', 10);
const BATCH = 100;

function log(msg) {
  const line = new Date().toISOString() + ' ' + msg;
  console.log(line);
  try {
    fs.appendFileSync(LOG_FILE, line + '\n');
    if (fs.statSync(LOG_FILE).size > 2 * 1024 * 1024) {
      const keep = fs.readFileSync(LOG_FILE, 'utf8').split('\n').slice(-800).join('\n');
      fs.writeFileSync(LOG_FILE, keep);
    }
  } catch (_) {}
}

function pad(n) { return String(n).padStart(2, '0'); }
function fmt(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

// ---------- 数据库 ----------
let db = null;
let nameById = {};

function openDb() {
  const key = fs.readFileSync(KEY_FILE, 'utf8').trim();
  db = new Database(DB_FILE, { readonly: true, fileMustExist: true });
  db.pragma("cipher='sqlcipher'");
  db.pragma("legacy=4");
  db.pragma("key='" + key + "'");
  db.prepare('SELECT 1').get();   // 验证密钥
  nameById = {};
  try {
    db.prepare('SELECT session_id, session_name FROM im_session_list').all()
      .forEach(s => { nameById[String(s.session_id)] = s.session_name; });
  } catch (_) {}
  log('数据库已连接，会话名 ' + Object.keys(nameById).length + ' 个');
}

// ---------- 水位 ----------
let wm = {};
function loadWm() {
  try { wm = JSON.parse(fs.readFileSync(WM_FILE, 'utf8')); } catch (_) { wm = {}; }
}
function saveWm() {
  try {
    const tmp = WM_FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(wm, null, 2), 'utf8');
    fs.renameSync(tmp, WM_FILE);
  } catch (e) { log('保存水位失败: ' + e.message); }
}

// ---------- 采集一轮 ----------
async function tick() {
  let tables;
  try {
    tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'im_chat_%'")
      .all().map(t => t.name);
  } catch (e) {
    log('读表失败，重开数据库: ' + e.message);
    try { db.close(); } catch (_) {}
    openDb();
    return;
  }

  // 没有水位的表：只回看 lookback 小时，避免把整段历史一次推上去
  // （旧脚本这里是 0，新表会把几万条历史全推，是个坑）
  const lookbackMs = (parseInt(args['lookback-hours'] || '2', 10)) * 3600 * 1000;
  const defaultWm = Date.now() - lookbackMs;

  let pushedSessions = 0;
  let pushedMsgs = 0;

  for (const table of tables) {
    const sid = table.replace('im_chat_', '');
    const since = wm[table] != null ? wm[table] : defaultWm;

    let rows;
    try {
      rows = db.prepare(
        'SELECT sender, sender_dispaly_name, create_time, content, content_type, message_type, search_text '
        + 'FROM "' + table + '" WHERE create_time > ? ORDER BY create_time ASC LIMIT 500'
      ).all(since);
    } catch (_) { continue; }
    if (!rows.length) continue;

    const msgs = rows.map(r => {
      let content = '';
      if (r.search_text && String(r.search_text).trim()) content = String(r.search_text).trim();
      else if (r.content && String(r.content).trim()) content = String(r.content).trim();
      else content = '[消息类型:' + (r.message_type || '?') + ']';
      return {
        sender: r.sender_dispaly_name || r.sender || '未知',
        time: fmt(r.create_time),
        content: content.slice(0, 4000),
      };
    });

    const chatName = nameById[sid] || sid;
    let ok = true;
    for (let i = 0; i < msgs.length; i += BATCH) {
      const batch = msgs.slice(i, i + BATCH);
      try {
        const resp = await fetch(PUSH_URL, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Moa-Secret': SECRET },
          body: JSON.stringify({ session_id: sid, chat_name: chatName, messages: batch }),
          signal: AbortSignal.timeout(15000),
        });
        if (!resp.ok) { ok = false; break; }
      } catch (e) {
        ok = false;
        break;
      }
    }

    // 关键：只有全部批次成功才推进水位
    if (ok) {
      wm[table] = rows[rows.length - 1].create_time;
      pushedSessions++;
      pushedMsgs += msgs.length;
    } else {
      log('推送失败，保留水位等待重试: ' + chatName + ' (' + sid + ')');
    }
  }

  if (pushedSessions > 0) {
    saveWm();
    log('推送 ' + pushedSessions + ' 个会话 / ' + pushedMsgs + ' 条消息');
  }
}

// ---------- 主循环 ----------
(async () => {
  log('==== MOA 采集器 v2 启动，间隔 ' + INTERVAL + 'ms ====');
  try { openDb(); } catch (e) { log('数据库打开失败: ' + e.message); process.exit(1); }
  loadWm();
  log('已加载水位 ' + Object.keys(wm).length + ' 张表');

  while (true) {
    try { await tick(); } catch (e) { log('tick 异常: ' + e.message); }
    await new Promise(r => setTimeout(r, INTERVAL));
  }
})();
