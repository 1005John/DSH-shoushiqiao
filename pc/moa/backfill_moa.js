/**
 * MOA 消息补推 —— 直接从加密数据库读出指定时间段的全部消息，推给云端接收端。
 *
 * 为什么不复用采集脚本的 repush：
 *   它的 SQL 带 `message_status NOT IN (2, 4)` 过滤，某些消息会被静默跳过；
 *   而且它按 im_session_list 遍历，表存在但会话不在列表里的消息也会漏。
 *   这里直接遍历所有 im_chat_* 表，不做任何状态过滤。
 *
 * 接收端按指纹去重，所以重复推送是安全的。
 *
 * 用法：
 *   node backfill_moa.js --from 2026-11-15 --to 2026-11-18
 */
const fs = require('fs');
const path = require('path');
const Database = require(process.env.MOA_SQLITE_MODULE || 'better-sqlite3-multiple-ciphers');

const args = {};
process.argv.slice(2).forEach((v, i, a) => {
  if (v.startsWith('--')) args[v.slice(2)] = a[i + 1];
});

const DB_DIR = process.env.MOA_DB_DIR || 'C:/Users/USERNAME/Documents/MoaFiles/db';
const DB_FILE = path.join(DB_DIR, 'user-e4e366799af24e61663ca8f2815d43bb524290d2.db');
const KEY_FILE = path.join(DB_DIR, 'moa_key.txt');
const PUSH_URL = args.url || 'http://127.0.0.1:8090/moa';
const SECRET = args.secret || 'YOUR_MOA_PUSH_SECRET';

const from = args.from || '2026-11-15';
const to = args.to || '2026-11-18';
const lo = new Date(from + 'T00:00:00+08:00').getTime();
const hi = new Date(to + 'T00:00:00+08:00').getTime();

const BATCH = 100;

function pad(n) { return String(n).padStart(2, '0'); }
function fmt(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

const key = fs.readFileSync(KEY_FILE, 'utf8').trim();
const db = new Database(DB_FILE, { readonly: true, fileMustExist: true });
db.pragma("cipher='sqlcipher'");
db.pragma("legacy=4");
db.pragma("key='" + key + "'");

// 会话名映射
const nameById = {};
try {
  db.prepare('SELECT session_id, session_name FROM im_session_list').all()
    .forEach(s => { nameById[String(s.session_id)] = s.session_name; });
} catch (e) { console.log('读取会话列表失败:', e.message); }

const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'im_chat_%'")
  .all().map(t => t.name);

let totalRows = 0, totalSent = 0, failed = 0;
const perDay = {};

(async () => {
  for (const table of tables) {
    const sid = table.replace('im_chat_', '');
    let rows;
    try {
      rows = db.prepare(
        'SELECT sender, sender_dispaly_name, create_time, content, content_type, message_type, search_text '
        + 'FROM "' + table + '" WHERE create_time >= ? AND create_time < ? ORDER BY create_time ASC'
      ).all(lo, hi);
    } catch (e) { continue; }
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
    totalRows += msgs.length;

    for (let i = 0; i < msgs.length; i += BATCH) {
      const batch = msgs.slice(i, i + BATCH);
      try {
        const resp = await fetch(PUSH_URL, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Moa-Secret': SECRET },
          body: JSON.stringify({ session_id: sid, chat_name: chatName, messages: batch }),
          signal: AbortSignal.timeout(15000),
        });
        if (resp.ok) {
          const j = await resp.json().catch(() => ({}));
          totalSent += (j.stored != null ? j.stored : batch.length);
        } else { failed += batch.length; }
      } catch (e) {
        failed += batch.length;
      }
      await new Promise(r => setTimeout(r, 60));
    }
    for (const r of msgs) {
      const d = r.time.slice(0, 10);
      perDay[d] = (perDay[d] || 0) + 1;
    }
    process.stdout.write('.');
  }

  console.log('\n=== 完成 ===');
  console.log('数据库命中:', totalRows, '条');
  console.log('推送成功:', totalSent, '条');
  console.log('推送失败:', failed, '条');
  console.log('按日期:', JSON.stringify(perDay, null, 2));
  db.close();
})();
